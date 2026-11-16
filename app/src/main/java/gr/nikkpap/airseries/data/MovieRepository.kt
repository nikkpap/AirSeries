package gr.nikkpap.airseries.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import gr.nikkpap.airseries.BuildConfig
import gr.nikkpap.airseries.data.local.AppDatabase
import gr.nikkpap.airseries.data.local.MovieEntity
import gr.nikkpap.airseries.data.local.MovieStatus
import gr.nikkpap.airseries.data.remote.TmdbApi
import gr.nikkpap.airseries.data.remote.TmdbMovie
import gr.nikkpap.airseries.data.trakt.Trakt
import gr.nikkpap.airseries.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Locale

/** Movies from TMDB, with release dates and streaming providers for Greece. */
class MovieRepository(
    private val ctx: Context,
    private val api: TmdbApi,
    db: AppDatabase,
) {
    private val dao = db.dao()

    val hasKey: Boolean get() = BuildConfig.TMDB_API_KEY.isNotBlank()

    val movies: Flow<List<MovieEntity>> = dao.moviesFlow()
    fun movieFlow(id: Int) = dao.movieFlow(id)

    /** "el-GR" when the app runs in Greek, otherwise "en-US". */
    fun lang(): String {
        val app = AppCompatDelegate.getApplicationLocales()
        val l = if (!app.isEmpty) app[0]?.language else Locale.getDefault().language
        return if (l == "el") "el-GR" else "en-US"
    }

    val isGreek: Boolean get() = lang() == "el-GR"

    private val listCache = HashMap<String, Pair<Long, List<TmdbMovie>>>()

    private suspend fun cachedList(key: String, load: suspend () -> List<TmdbMovie>): List<TmdbMovie> {
        val now = System.currentTimeMillis()
        listCache[key]?.let { (t, v) -> if (now - t < 30 * 60_000L) return v }
        val v = load()
        listCache[key] = now to v
        return v
    }

    suspend fun search(q: String): List<TmdbMovie> = withContext(Dispatchers.IO) {
        api.search(q, lang(), "GR", false).results
    }

    suspend fun nowPlaying() = withContext(Dispatchers.IO) { cachedList("now_${lang()}") { api.nowPlaying(lang(), "GR").results } }
    suspend fun upcoming() = withContext(Dispatchers.IO) {
        val today = java.time.LocalDate.now().toString()
        cachedList("up_${lang()}") { api.upcoming(lang(), "GR").results.filter { (it.releaseDate ?: "") >= today } }
    }
    suspend fun popular() = withContext(Dispatchers.IO) { cachedList("pop_${lang()}") { api.popular(lang(), "GR").results } }

    /** Full details; Greek text falls back to English when TMDB has no Greek overview. */
    suspend fun details(id: Int): TmdbMovie = withContext(Dispatchers.IO) {
        val d = api.movie(id, lang(), TmdbApi.DETAILS_APPEND)
        if (d.overview.isNullOrBlank() && isGreek) {
            val en = runCatching { api.movieBasic(id, "en-US") }.getOrNull()
            if (en != null) return@withContext d.copy(overview = en.overview, tagline = d.tagline ?: en.tagline)
        }
        d
    }

    /** Movies like the ones you rated well / watched recently. */
    suspend fun forYou(mine: List<MovieEntity>): List<TmdbMovie> = withContext(Dispatchers.IO) {
        val seeds = mine.sortedWith(compareByDescending<MovieEntity> { it.myRating }.thenByDescending { it.watchedAt ?: it.addedAt }).take(4)
        if (seeds.isEmpty()) return@withContext emptyList()
        val mineIds = mine.mapTo(HashSet()) { it.id }
        val score = HashMap<Int, Pair<TmdbMovie, Double>>()
        for (s in seeds) {
            val recs = runCatching { cachedList("rec_${s.id}_${lang()}") { api.recommendations(s.id, lang()).results } }.getOrDefault(emptyList())
            val w = if (s.myRating > 0) s.myRating / 3.0 else 1.0
            recs.forEachIndexed { i, m ->
                if (m.id in mineIds || m.posterPath == null) return@forEachIndexed
                val add = w * (1.0 - i / 40.0) + (m.voteAverage ?: 0.0) / 20.0
                score[m.id] = m to ((score[m.id]?.second ?: 0.0) + add)
            }
        }
        score.values.sortedByDescending { it.second }.take(20).map { it.first }
    }

    // ---------- my movies ----------
    suspend fun add(id: Int, status: Int) {
        addSilently(id, status)
        if (status == MovieStatus.WATCHED) Trakt.pushMovieWatched(ctx, id, true) else Trakt.pushMovieWatchlist(ctx, id, true)
    }

    /** Adds without telling Trakt (used when the data came from Trakt). */
    suspend fun addSilently(id: Int, status: Int) {
        val d = details(id)
        val prev = dao.movie(id)
        val now = System.currentTimeMillis()
        dao.upsertMovie(
            d.toEntity(prev, now).copy(
                status = status,
                watchedAt = if (status == MovieStatus.WATCHED) (prev?.watchedAt ?: now) else null,
            )
        )
        dao.movie(id)?.let { Scheduler.scheduleMovie(ctx, it) }
    }

    suspend fun setStatus(id: Int, status: Int) {
        val before = dao.movie(id)?.status
        dao.setMovieStatus(id, status, if (status == MovieStatus.WATCHED) System.currentTimeMillis() else null)
        if (before != status) {
            if (status == MovieStatus.WATCHED) Trakt.pushMovieWatched(ctx, id, true)
            else { Trakt.pushMovieWatched(ctx, id, false); Trakt.pushMovieWatchlist(ctx, id, true) }
        }
        if (status == MovieStatus.WATCHED) Scheduler.cancelMovie(ctx, id)
        else dao.movie(id)?.let { Scheduler.scheduleMovie(ctx, it) }
    }

    suspend fun remove(id: Int) {
        if (dao.movie(id)?.status == MovieStatus.PLAN) Trakt.pushMovieWatchlist(ctx, id, false)
        dao.deleteMovie(id)
        Scheduler.cancelMovie(ctx, id)
    }

    suspend fun setRating(id: Int, r: Int) = dao.setMovieRating(id, r.coerceIn(0, 5))
    suspend fun setNote(id: Int, n: String?) = dao.setMovieNote(id, n?.trim()?.takeIf { it.isNotEmpty() })

    /** Refreshes "plan to watch" movies (release dates and providers change). */
    suspend fun refreshPlanned() {
        if (!hasKey) return
        val now = System.currentTimeMillis()
        for (m in dao.movies().filter { it.status == MovieStatus.PLAN && now - it.lastSync > 20 * 3_600_000L }) {
            runCatching { dao.upsertMovie(details(m.id).toEntity(m, now)) }
            delay(250)
        }
        dao.movies().forEach { Scheduler.scheduleMovie(ctx, it) }
    }
}

/** Earliest Greek date of the given release types (yyyy-MM-dd). */
private fun TmdbMovie.greekRelease(vararg types: Int): String? =
    releaseDates?.results?.firstOrNull { it.country == "GR" }?.dates
        ?.filter { it.type in types && it.date.length >= 10 }
        ?.minOfOrNull { it.date.take(10) }

fun TmdbMovie.trailerKey(): String? {
    val yt = videos?.results.orEmpty().filter { it.site == "YouTube" && it.key.isNotBlank() }
    return (yt.firstOrNull { it.type == "Trailer" && it.lang == "el" }
        ?: yt.firstOrNull { it.type == "Trailer" && it.official }
        ?: yt.firstOrNull { it.type == "Trailer" }
        ?: yt.firstOrNull { it.type == "Teaser" })?.key
}

fun TmdbMovie.genreNames(greek: Boolean): List<String> =
    genres.map { it.name }.ifEmpty { genreIds.mapNotNull { TmdbApi.GENRES[it]?.let { p -> if (greek) p.second else p.first } } }

fun TmdbMovie.toEntity(prev: MovieEntity?, now: Long) = MovieEntity(
    id = id,
    title = title,
    originalTitle = originalTitle,
    overview = overview,
    poster = posterPath,
    backdrop = backdropPath,
    releaseDate = releaseDate,
    releaseGr = greekRelease(3, 2) ?: prev?.releaseGr,
    digitalGr = greekRelease(4) ?: prev?.digitalGr,
    runtime = runtime,
    rating = voteAverage,
    genres = genres.joinToString("|") { it.name },
    providers = providers?.results?.get("GR")?.flatrate?.joinToString("|") { it.name }?.takeIf { it.isNotEmpty() },
    trailerKey = trailerKey(),
    imdb = imdbId,
    status = prev?.status ?: MovieStatus.PLAN,
    watchedAt = prev?.watchedAt,
    myRating = prev?.myRating ?: 0,
    note = prev?.note,
    addedAt = prev?.addedAt ?: now,
    lastSync = now,
    notified = prev?.notified ?: 0,
)
