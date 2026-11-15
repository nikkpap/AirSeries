package gr.nikkpap.airseries.data

import android.content.Context
import gr.nikkpap.airseries.data.local.EpisodeEntity
import gr.nikkpap.airseries.data.local.ShowEntity
import gr.nikkpap.airseries.data.local.genreList
import gr.nikkpap.airseries.util.locale
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle

/** "My year" statistics, from the moments you marked episodes as watched. */
data class Stats(
    val year: Int?,                       // null = all time
    val episodes: Int,
    val minutes: Int,
    val showCount: Int,
    val months: List<Int>,                // 12 values
    val monthKeys: List<YearMonth>,
    val topShows: List<Pair<ShowEntity, Int>>,
    val topGenres: List<Pair<String, Int>>, // genre -> minutes
    val bestDay: Pair<Long, Int>?,        // epoch millis of that day, episodes
    val favoriteWeekday: Int?,            // 1 = Monday … 7 = Sunday
    val completedShows: Int,
    val avgMyRating: Double?,
    val longestShow: Pair<String, Int>?,  // most episodes of one show in a single day
) {
    fun monthLabels(ctx: Context): List<String> =
        monthKeys.map { it.month.getDisplayName(TextStyle.NARROW_STANDALONE, ctx.locale()) }
}

fun computeStats(shows: List<ShowEntity>, allEps: List<EpisodeEntity>, year: Int?): Stats {
    val zone = ZoneId.systemDefault()
    val byId = shows.associateBy { it.id }
    val watched = allEps.filter { it.watched && it.watchedAt != null && it.showId in byId }
    fun dateOf(e: EpisodeEntity): LocalDate = Instant.ofEpochMilli(e.watchedAt!!).atZone(zone).toLocalDate()
    val inPeriod = if (year == null) watched else watched.filter { dateOf(it).year == year }
    fun minutesOf(e: EpisodeEntity) = e.runtime ?: byId[e.showId]?.runtime ?: 0

    val monthKeys: List<YearMonth> = if (year != null) (1..12).map { YearMonth.of(year, it) }
    else { val now = YearMonth.now(zone); (11 downTo 0).map { now.minusMonths(it.toLong()) } }
    val perMonth = inPeriod.groupingBy { YearMonth.from(dateOf(it)) }.eachCount()
    val months = monthKeys.map { perMonth[it] ?: 0 }

    val topShows = inPeriod.groupingBy { it.showId }.eachCount().entries
        .sortedByDescending { it.value }.take(5).mapNotNull { (id, n) -> byId[id]?.let { it to n } }

    val genreMinutes = HashMap<String, Int>()
    inPeriod.forEach { e -> byId[e.showId]?.genreList()?.forEach { g -> genreMinutes.merge(g, minutesOf(e), Int::plus) } }
    val topGenres = genreMinutes.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }

    val perDay = inPeriod.groupBy { dateOf(it) }
    val best = perDay.maxByOrNull { it.value.size }
    val bestDay = best?.let { it.key.atStartOfDay(zone).toInstant().toEpochMilli() to it.value.size }
    val longest = perDay.values.flatMap { day -> day.groupingBy { it.showId }.eachCount().entries }
        .maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.let { (byId[it.key]?.name ?: "") to it.value }
    val weekday = inPeriod.groupingBy { dateOf(it).dayOfWeek.value }.eachCount().maxByOrNull { it.value }?.key

    val now = System.currentTimeMillis()
    val epsByShow = allEps.groupBy { it.showId }
    val completed = shows.count { buildProgress(it, epsByShow[it.id].orEmpty(), now).completed }
    val rated = shows.filter { it.myRating > 0 }

    return Stats(
        year = year,
        episodes = inPeriod.size,
        minutes = inPeriod.sumOf { minutesOf(it) },
        showCount = inPeriod.map { it.showId }.distinct().size,
        months = months,
        monthKeys = monthKeys,
        topShows = topShows,
        topGenres = topGenres,
        bestDay = bestDay,
        favoriteWeekday = weekday,
        completedShows = completed,
        avgMyRating = rated.takeIf { it.isNotEmpty() }?.map { it.myRating }?.average(),
        longestShow = longest,
    )
}
