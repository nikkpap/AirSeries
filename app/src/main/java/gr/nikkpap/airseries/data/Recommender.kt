package gr.nikkpap.airseries.data

import gr.nikkpap.airseries.data.local.AppDatabase
import gr.nikkpap.airseries.data.local.EpisodeEntity
import gr.nikkpap.airseries.data.local.RecCandidateEntity
import gr.nikkpap.airseries.data.local.ShowEntity
import gr.nikkpap.airseries.data.local.genreList
import gr.nikkpap.airseries.data.remote.ShowDto
import gr.nikkpap.airseries.data.remote.TvMazeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.sqrt

data class Recommendation(
    val c: RecCandidateEntity,
    val score: Double,
    val becauseOf: String?,      // name of a favorite this is related to
    val match: Int,              // 0..99 %
)

/**
 * Content-based recommender.
 *
 * Candidates come from two sources (cached in Room, refreshed weekly):
 *  1. Shared cast: actors of your most-loved favorites -> their other shows.
 *  2. TVmaze catalog: classic popular pages + the newest pages.
 *
 * Score = 40% genre similarity (cosine vs. your weighted genre profile)
 *       + 20% shared cast + 20% rating + 12% popularity
 *       +  8% language + 4% network + 4% "still running" bonus.
 * Favorites you have watched more count more in your profile.
 */
class Recommender(
    private val api: TvMazeApi,
    private val db: AppDatabase,
    private val prefs: Prefs,
) {
    private val dao = db.dao()

    suspend fun refreshCandidates() = withContext(Dispatchers.IO) {
        val favs = dao.shows()
        val eps = dao.allEpisodes().groupBy { it.showId }
        val now = System.currentTimeMillis()
        val map = HashMap<Int, RecCandidateEntity>()

        fun add(s: ShowDto, castInc: Int, via: Int?) {
            if (s.image?.medium == null) return
            val prev = map[s.id]
            map[s.id] = if (prev == null) s.toCandidate(castInc, via, now)
            else prev.copy(castOverlap = prev.castOverlap + castInc, viaShowId = prev.viaShowId ?: via)
        }

        // 1) shared cast with the top favorites
        val top = favs.sortedByDescending { favWeight(it, eps[it.id]) }.take(5)
        for (f in top) {
            val cast = runCatching { api.cast(f.id) }.getOrDefault(emptyList()).take(4)
            delay(350)
            for (member in cast) {
                val credits = runCatching { api.castCredits(member.person.id) }.getOrDefault(emptyList())
                delay(350)
                credits.mapNotNull { it.embedded?.show }
                    .filter { it.id != f.id }
                    .distinctBy { it.id }
                    .forEach { add(it, 1, f.id) }
            }
        }

        // 2) catalog: well-known older shows + newest shows
        val last = findLastPage()
        val pages = (0..7).toList() + listOf(last - 2, last - 1, last).filter { it > 7 }
        for (p in pages.distinct()) {
            val body = runCatching { api.showsPage(p).body() }.getOrNull()
            delay(300)
            val minWeight = if (p > 7) 55 else 75
            body?.filter { (it.weight ?: 0) >= minWeight }?.forEach { add(it, 0, null) }
        }

        if (map.isNotEmpty()) {
            dao.clearCandidates()
            dao.upsertCandidates(map.values.toList())
            prefs.lastRecsRefresh = now
        }
    }

    private suspend fun pageExists(p: Int): Boolean {
        delay(250)
        return runCatching {
            val r = api.showsPage(p)
            r.isSuccessful && !r.body().isNullOrEmpty()
        }.getOrDefault(false)
    }

    /** Binary-searches the last page of the TVmaze show index (newest shows). */
    private suspend fun findLastPage(): Int {
        var lo = maxOf(prefs.lastCatalogPage, 300)
        if (!pageExists(lo)) {
            lo = 100
            if (!pageExists(lo)) return 0
        }
        var hi = lo + 64
        while (pageExists(hi)) { lo = hi; hi += 64 }
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (pageExists(mid)) lo = mid else hi = mid
        }
        prefs.lastCatalogPage = lo
        return lo
    }

    fun recommend(
        favs: List<ShowEntity>,
        eps: Map<Int, List<EpisodeEntity>>,
        candidates: List<RecCandidateEntity>,
        dismissed: Set<Int>,
        limit: Int = 50,
    ): List<Recommendation> {
        val favIds = favs.mapTo(HashSet()) { it.id }
        val pool = candidates.filter { it.id !in favIds && it.id !in dismissed }

        if (favs.isEmpty()) {
            return pool.sortedByDescending { (it.weight ?: 0) * 0.6 + (it.rating ?: 0.0) * 4.0 }
                .take(limit)
                .map { Recommendation(it, 0.0, null, 0) }
        }

        val genreW = HashMap<String, Double>()
        val langW = HashMap<String, Double>()
        val netW = HashMap<String, Double>()
        val favGenres = HashMap<Int, Set<String>>()
        for (f in favs) {
            val w = favWeight(f, eps[f.id])
            val g = f.genreList()
            favGenres[f.id] = g.toSet()
            g.forEach { genreW.merge(it, w, Double::plus) }
            f.language?.let { langW.merge(it, w, Double::plus) }
            f.network?.let { netW.merge(it, w, Double::plus) }
        }
        val gNorm = sqrt(genreW.values.sumOf { it * it }).takeIf { it > 0 } ?: 1.0
        val langTotal = langW.values.sum().takeIf { it > 0 } ?: 1.0
        val netMax = netW.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0

        return pool.asSequence()
            .filter { (it.weight ?: 0) >= 30 || it.castOverlap > 0 }
            .map { c ->
                val g = c.genreList()
                val cos = if (g.isEmpty()) 0.0 else g.sumOf { genreW[it] ?: 0.0 } / (gNorm * sqrt(g.size.toDouble()))
                val rating = (c.rating ?: 6.0) / 10.0
                val pop = (c.weight ?: 0) / 100.0
                val lang = (c.language?.let { langW[it] } ?: 0.0) / langTotal
                val cast = min(c.castOverlap, 3) / 3.0
                val net = (c.network?.let { netW[it] } ?: 0.0) / netMax
                val running = when (c.status) {
                    "Running" -> 1.0
                    "In Development", "To Be Determined" -> 0.5
                    else -> 0.0
                }
                val score = 0.40 * cos + 0.20 * cast + 0.20 * rating + 0.12 * pop +
                    0.08 * lang + 0.04 * net + 0.04 * running

                val gs = g.toSet()
                val viaId = c.viaShowId?.takeIf { it in favIds }
                    ?: favs.maxByOrNull { f -> (favGenres[f.id].orEmpty() intersect gs).size * favWeight(f, eps[f.id]) }
                        ?.takeIf { f -> (favGenres[f.id].orEmpty() intersect gs).isNotEmpty() }?.id
                val because = favs.firstOrNull { it.id == viaId }?.name
                val match = (min(score / 0.85, 0.99) * 100).toInt()
                Recommendation(c, score, because, match)
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    companion object {
        /** 1.0 for an untouched favorite, up to 3.0 for a fully watched one. */
        fun favWeight(f: ShowEntity, eps: List<EpisodeEntity>?): Double {
            val reg = eps.orEmpty().filter { it.number != null }
            if (reg.isEmpty()) return 1.0
            return 1.0 + 2.0 * reg.count { it.watched }.toDouble() / reg.size
        }
    }
}

private fun ShowDto.toCandidate(cast: Int, via: Int?, now: Long) = RecCandidateEntity(
    id = id,
    name = name,
    image = image?.medium,
    genres = genres.joinToString("|"),
    rating = rating?.average,
    weight = weight,
    language = language,
    premiered = premiered,
    status = status,
    summary = summary,
    network = network?.name ?: webChannel?.name,
    castOverlap = cast,
    viaShowId = via,
    fetchedAt = now,
)
