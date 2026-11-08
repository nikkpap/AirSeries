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

    suspend fun scheduleShow(ctx: Context, showId: Int) {
        scheduleEpisodes(ctx, ctx.container.db.dao().episodes(showId))
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

    fun cancelShow(ctx: Context, showId: Int) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag("show_$showId")
    }

    suspend fun rescheduleAll(ctx: Context) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(TAG_REMINDER)
        scheduleEpisodes(ctx, ctx.container.db.dao().allEpisodes())
    }
}
