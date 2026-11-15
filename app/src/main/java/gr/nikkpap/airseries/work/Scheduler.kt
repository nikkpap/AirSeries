package gr.nikkpap.airseries.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import gr.nikkpap.airseries.container
import gr.nikkpap.airseries.data.local.EpisodeEntity
import gr.nikkpap.airseries.data.local.ListStatus
import gr.nikkpap.airseries.data.local.MovieEntity
import gr.nikkpap.airseries.data.local.MovieStatus
import java.time.LocalDate
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val TAG_REMINDER = "reminder"
    private const val HOUR = 3_600_000L
    private const val MIN = 60_000L

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Refreshes all favorites every 6 hours (survives reboots). */
    fun schedulePeriodicSync(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync_periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun syncNow(ctx: Context, force: Boolean) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "sync_now",
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            req,
        )
    }

    /** Paused and dropped shows get no episode reminders. */
    fun wantsReminders(listStatus: Int) = listStatus == ListStatus.WATCHING || listStatus == ListStatus.PLAN

    suspend fun scheduleShow(ctx: Context, showId: Int) {
        val dao = ctx.container.db.dao()
        val show = dao.show(showId) ?: return
        if (!wantsReminders(show.listStatus)) return
        scheduleEpisodes(ctx, dao.episodes(showId))
    }

    /** One reminder per upcoming episode (next 14 days), fired [offset] minutes before air. */
    fun scheduleEpisodes(ctx: Context, eps: List<EpisodeEntity>) {
        val prefs = ctx.container.prefs
        if (!prefs.notifyEnabled.value) return
        val offset = prefs.notifyOffset.value * MIN
        val wm = WorkManager.getInstance(ctx)
        val now = System.currentTimeMillis()
        for (ep in eps) {
            val air = ep.airstamp ?: continue
            if (ep.notified || air > now + 14 * 24 * HOUR) continue
            val fireAt = air - offset
            if (fireAt < now - 15 * MIN) continue // missed long ago
            val req = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay((fireAt - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(ReminderWorker.KEY_EP to ep.id))
                .addTag(TAG_REMINDER)
                .addTag("show_${ep.showId}")
                .build()
            wm.enqueueUniqueWork("rem_${ep.id}", ExistingWorkPolicy.REPLACE, req)
        }
    }

    /** Morning digest: one notification a day at the chosen hour with today's episodes. */
    fun scheduleDigest(ctx: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val prefs = ctx.container.prefs
        val wm = WorkManager.getInstance(ctx)
        if (!prefs.digestEnabled.value || !prefs.notifyEnabled.value) {
            wm.cancelUniqueWork("digest")
            return
        }
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var next = now.toLocalDate().atTime(prefs.digestHour.value, 0).atZone(zone)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val req = OneTimeWorkRequestBuilder<DigestWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork("digest", policy, req)
    }

    /** Movie reminders: 10:00 on the Greek cinema day (bit 1) and online day (bit 2). */
    fun scheduleMovie(ctx: Context, m: MovieEntity) {
        if (m.status != MovieStatus.PLAN) { cancelMovie(ctx, m.id); return }
        if (!ctx.container.prefs.notifyEnabled.value) return
        val wm = WorkManager.getInstance(ctx)
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        listOf(1 to m.releaseGr, 2 to m.digitalGr).forEach { (bit, date) ->
            if (date == null || (m.notified and bit) != 0) return@forEach
            val day = runCatching { LocalDate.parse(date.take(10)) }.getOrNull() ?: return@forEach
            val fire = day.atTime(10, 0).atZone(zone)
            if (fire.isBefore(now.minusHours(12))) return@forEach
            val req = OneTimeWorkRequestBuilder<MovieReminderWorker>()
                .setInitialDelay(Duration.between(now, fire).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(MovieReminderWorker.KEY_ID to m.id, MovieReminderWorker.KEY_BIT to bit))
                .addTag("movie_${m.id}")
                .build()
            wm.enqueueUniqueWork("movie_${m.id}_$bit", ExistingWorkPolicy.REPLACE, req)
        }
    }

    fun cancelMovie(ctx: Context, id: Int) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag("movie_$id")
    }

    fun cancelShow(ctx: Context, showId: Int) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag("show_$showId")
    }

    suspend fun rescheduleAll(ctx: Context) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(TAG_REMINDER)
        val dao = ctx.container.db.dao()
        val active = dao.shows().filter { wantsReminders(it.listStatus) }.mapTo(HashSet()) { it.id }
        scheduleEpisodes(ctx, dao.allEpisodes().filter { it.showId in active })
    }
}
