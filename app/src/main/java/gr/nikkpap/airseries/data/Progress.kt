package gr.nikkpap.airseries.data

import gr.nikkpap.airseries.data.local.EpisodeEntity
import gr.nikkpap.airseries.data.local.ShowEntity
import gr.nikkpap.airseries.data.local.airTime
import gr.nikkpap.airseries.data.local.isAired

data class ShowProgress(
    val show: ShowEntity,
    val total: Int,              // all regular episodes known (incl. announced)
    val aired: Int,
    val watched: Int,
    val nextUnwatched: EpisodeEntity?,
    val nextAiring: EpisodeEntity?,
    val lastWatchedAt: Long,
    val unwatchedAired: Int,
    val watchedMinutes: Int,
) {
    val fraction: Float get() = if (total == 0) 0f else watched.toFloat() / total
    val upToDate: Boolean get() = aired > 0 && unwatchedAired == 0
    val completed: Boolean get() = upToDate && nextAiring == null && show.status == "Ended"
    val started: Boolean get() = watched > 0
}

fun buildProgress(show: ShowEntity, eps: List<EpisodeEntity>, now: Long): ShowProgress {
    val regular = eps.filter { it.number != null }
        .sortedWith(compareBy<EpisodeEntity>({ it.season }, { it.number }))
    val aired = regular.filter { it.isAired(now) }
    val lastWatchedIdx = aired.indexOfLast { it.watched }
    val nextUnwatched = aired.drop(lastWatchedIdx + 1).firstOrNull { !it.watched }
        ?: aired.firstOrNull { !it.watched }
    val nextAiring = regular.firstOrNull { e -> e.airTime()?.let { it > now } == true }
    val watched = regular.filter { it.watched }
    return ShowProgress(
        show = show,
        total = regular.size,
        aired = aired.size,
        watched = watched.size,
        nextUnwatched = nextUnwatched,
        nextAiring = nextAiring,
        lastWatchedAt = watched.maxOfOrNull { it.watchedAt ?: 0L } ?: 0L,
        unwatchedAired = aired.count { !it.watched },
        watchedMinutes = watched.sumOf { it.runtime ?: show.runtime ?: 0 },
    )
}
