package gr.nikkpap.airseries.data

import android.content.Context
import androidx.room.withTransaction
import gr.nikkpap.airseries.data.local.AppDatabase
import gr.nikkpap.airseries.data.local.EpisodeEntity
import gr.nikkpap.airseries.data.local.ListStatus
import gr.nikkpap.airseries.data.local.ShowEntity
import gr.nikkpap.airseries.data.local.isAired
import gr.nikkpap.airseries.data.remote.CastDto
import gr.nikkpap.airseries.data.remote.EpisodeDto
import gr.nikkpap.airseries.data.remote.ShowDto
import gr.nikkpap.airseries.data.remote.TvMazeApi
import gr.nikkpap.airseries.widget.WidgetUpdater
import gr.nikkpap.airseries.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class BackupShow(
    val id: Int,
    val name: String = "",
    val watched: List<Int> = emptyList(),
    val listStatus: Int = 0,
    val myRating: Int = 0,
    val note: String? = null,
)

@Serializable
data class BackupMovie(val id: Int, val title: String = "", val status: Int = 0, val myRating: Int = 0, val note: String? = null)

@Serializable
data class BackupFile(
    val app: String = "AirSeries",
    val version: Int = 1,
    val created: Long = 0,
    val shows: List<BackupShow> = emptyList(),
    val movies: List<BackupMovie> = emptyList(),
)

data class Announcement(val show: ShowEntity, val episode: EpisodeEntity)

class Repository(
    private val ctx: Context,
    private val api: TvMazeApi,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val moviesRepo: () -> MovieRepository,
) {
    private val dao = db.dao()

    val shows: Flow<List<ShowEntity>> = dao.showsFlow()
    val allEpisodes: Flow<List<EpisodeEntity>> = dao.allEpisodesFlow()
    val favoriteIds: Flow<List<Int>> = dao.favoriteIdsFlow()
    fun showFlow(id: Int) = dao.showFlow(id)
    fun episodesFlow(id: Int) = dao.episodesFlow(id)
    suspend fun getShow(id: Int) = dao.show(id)

    suspend fun search(q: String): List<ShowDto> = withContext(Dispatchers.IO) {
        api.search(q).map { it.show }
    }

    suspend fun cast(id: Int): List<CastDto> = withContext(Dispatchers.IO) {
        api.cast(id).distinctBy { it.person.id }.take(24)
    }

    /** Other shows an actor appears in, newest first. */
    suspend fun personShows(personId: Int): List<ShowDto> = withContext(Dispatchers.IO) {
        api.castCredits(personId).mapNotNull { it.embedded?.show }.distinctBy { it.id }
            .sortedByDescending { it.premiered.orEmpty() }
    }

    /** Loads a show that is NOT in favorites, for preview only (nothing saved). */
    suspend fun fetchRemote(id: Int): Pair<ShowEntity, List<EpisodeEntity>> = withContext(Dispatchers.IO) {
        val dto = api.showFull(id, TvMazeApi.EMBED)
        val now = System.currentTimeMillis()
        dto.toEntity(null, now) to dto.embedded?.episodes.orEmpty().map { it.toEntity(id, null) }
    }

    /**
     * Downloads the latest show + episodes and merges them with local state
     * (watched / notified flags are kept). Returns a new-episode announcement, if any.
     */
    suspend fun refreshShow(id: Int, isNewFavorite: Boolean = false): Announcement? = withContext(Dispatchers.IO) {
        val dto = api.showFull(id, TvMazeApi.EMBED)
        val now = System.currentTimeMillis()
        var announcement: Announcement? = null
        db.withTransaction {
            val prev = dao.show(id)
            if (prev == null && !isNewFavorite) return@withTransaction // removed meanwhile
            val existing = dao.episodes(id).associateBy { it.id }
            val eps = dto.embedded?.episodes.orEmpty().map { it.toEntity(id, existing[it.id]) }
            val show = dto.toEntity(prev, now)
            dao.upsertShow(show)
            dao.upsertEpisodes(eps)
            val keep = eps.mapTo(HashSet()) { it.id }
            existing.keys.filter { it !in keep }.chunked(500).forEach { dao.deleteEpisodes(it) }
            val next = dto.embedded?.nextepisode
            if (prev != null && prev.nextEpisodeId == null && next != null) {
                eps.firstOrNull { it.id == next.id }?.let { announcement = Announcement(show, it) }
            }
        }
        announcement
    }

    suspend fun addFavorite(id: Int) {
        refreshShow(id, isNewFavorite = true)
        Scheduler.scheduleShow(ctx, id)
        WidgetUpdater.refresh(ctx)
    }

    suspend fun removeFavorite(id: Int) {
        db.withTransaction {
            dao.deleteEpisodesOfShow(id)
            dao.deleteShow(id)
        }
        Scheduler.cancelShow(ctx, id)
        WidgetUpdater.refresh(ctx)
    }

    suspend fun setWatched(epId: Int, watched: Boolean) {
        dao.setWatched(epId, watched, if (watched) System.currentTimeMillis() else null)
        if (watched) dao.episode(epId)?.let { autoStartWatching(it.showId) }
        WidgetUpdater.refresh(ctx)
    }

    /** Watching an episode of a "plan to watch" show moves it to "watching". */
    private suspend fun autoStartWatching(showId: Int) {
        val s = dao.show(showId) ?: return
        if (s.listStatus == ListStatus.PLAN) dao.setListStatus(showId, ListStatus.WATCHING)
    }

    suspend fun setMyRating(showId: Int, rating: Int) = dao.setMyRating(showId, rating.coerceIn(0, 5))
    suspend fun setNote(showId: Int, note: String?) = dao.setNote(showId, note?.trim()?.takeIf { it.isNotEmpty() })

    suspend fun setListStatus(showId: Int, status: Int) {
        dao.setListStatus(showId, status)
        if (status == ListStatus.DROPPED) Scheduler.cancelShow(ctx, showId) else Scheduler.scheduleShow(ctx, showId)
        WidgetUpdater.refresh(ctx)
    }

    suspend fun setSeasonWatched(showId: Int, season: Int, watched: Boolean) {
        val now = System.currentTimeMillis()
        val ids = dao.episodes(showId)
            .filter { it.season == season && (!watched || it.isAired(now)) }
            .map { it.id }
        ids.chunked(500).forEach { dao.setWatchedMany(it, watched, if (watched) now else null) }
        if (watched) autoStartWatching(showId)
        WidgetUpdater.refresh(ctx)
    }

    /** Marks this episode and every earlier aired episode as watched. */
    suspend fun markUpTo(ep: EpisodeEntity) {
        val now = System.currentTimeMillis()
        val ids = dao.episodes(ep.showId)
            .filter { it.isAired(now) && !it.watched && (it.season < ep.season || (it.season == ep.season && (it.number ?: 0) <= (ep.number ?: 0))) }
            .map { it.id }
        ids.chunked(500).forEach { dao.setWatchedMany(it, true, now) }
        autoStartWatching(ep.showId)
        WidgetUpdater.refresh(ctx)
    }

    /** Marks episodes given as (season, number) pairs as watched — used by imports. */
    suspend fun markWatchedPairs(showId: Int, pairs: Set<Pair<Int, Int>>) {
        if (pairs.isEmpty()) return
        val now = System.currentTimeMillis()
        val ids = dao.episodes(showId).filter { e -> e.number != null && (e.season to e.number) in pairs }.map { it.id }
        ids.chunked(500).forEach { dao.setWatchedMany(it, true, now) }
    }

    /** Refreshes every favorite (slowly, to respect the API rate limit). */
    suspend fun syncAll(): List<Announcement> {
        val out = mutableListOf<Announcement>()
        for (s in dao.shows()) {
            runCatching { refreshShow(s.id) }.getOrNull()?.let { out += it }
            delay(400)
        }
        prefs.setLastSync(System.currentTimeMillis())
        WidgetUpdater.refresh(ctx)
        return out
    }

    // ---- backup ----
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val eps = dao.allEpisodes().groupBy { it.showId }
        val file = BackupFile(
            created = System.currentTimeMillis(),
            shows = dao.shows().map { s ->
                BackupShow(s.id, s.name, eps[s.id].orEmpty().filter { it.watched }.map { it.id }, s.listStatus, s.myRating, s.note)
            },
            movies = dao.movies().map { BackupMovie(it.id, it.title, it.status, it.myRating, it.note) },
        )
        TvMazeApi.json.encodeToString(BackupFile.serializer(), file)
    }

    /** Returns how many shows were imported. */
    suspend fun importJson(text: String): Int = withContext(Dispatchers.IO) {
        val file = TvMazeApi.json.decodeFromString(BackupFile.serializer(), text)
        var count = 0
        for (b in file.shows) {
            val ok = runCatching { if (dao.show(b.id) == null) addFavorite(b.id) }.isSuccess
            if (ok) {
                val now = System.currentTimeMillis()
                b.watched.chunked(500).forEach { dao.setWatchedMany(it, true, now) }
                dao.setListStatus(b.id, b.listStatus)
                dao.setMyRating(b.id, b.myRating)
                dao.setNote(b.id, b.note)
                count++
            }
            delay(400)
        }
        val mr = moviesRepo()
        if (mr.hasKey) {
            for (m in file.movies) {
                val ok = runCatching { if (dao.movie(m.id) == null) mr.add(m.id, m.status) }.isSuccess
                if (ok) {
                    dao.setMovieRating(m.id, m.myRating)
                    dao.setMovieNote(m.id, m.note)
                    count++
                }
                delay(200)
            }
        }
        count
    }
}

// ---------- mappers ----------
fun parseAirstamp(s: String?): Long? =
    s?.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

fun ShowDto.toEntity(prev: ShowEntity?, now: Long) = ShowEntity(
    id = id,
    name = name,
    imageMedium = image?.medium,
    imageOriginal = image?.original,
    summary = summary,
    genres = genres.joinToString("|"),
    status = status,
    network = network?.name ?: webChannel?.name,
    language = language,
    rating = rating?.average,
    premiered = premiered,
    ended = ended,
    runtime = runtime ?: averageRuntime,
    scheduleTime = schedule?.time,
    scheduleDays = schedule?.days?.joinToString(", "),
    url = url,
    officialSite = officialSite,
    imdb = externals?.imdb,
    weight = weight,
    nextEpisodeId = embedded?.nextepisode?.id,
    addedAt = prev?.addedAt ?: now,
    lastSync = now,
    listStatus = prev?.listStatus ?: ListStatus.WATCHING,
    myRating = prev?.myRating ?: 0,
    note = prev?.note,
)

fun EpisodeDto.toEntity(showId: Int, prev: EpisodeEntity?) = EpisodeEntity(
    id = id,
    showId = showId,
    season = season,
    number = number,
    name = name,
    airdate = airdate,
    airstamp = parseAirstamp(airstamp),
    runtime = runtime,
    imageMedium = image?.medium,
    imageOriginal = image?.original,
    summary = summary,
    rating = rating?.average,
    url = url,
    watched = prev?.watched ?: false,
    watchedAt = prev?.watchedAt,
    notified = if (prev != null && prev.airstamp == parseAirstamp(airstamp)) prev.notified else false,
)
