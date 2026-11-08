package gr.nikkpap.airseries.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import gr.nikkpap.airseries.container
import gr.nikkpap.airseries.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Refreshes favorites, posts "new episode announced" alerts and re-plans reminders. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        return try {
            val announced = c.repo.syncAll()
            if (c.prefs.notifyEnabled.value && c.prefs.notifyAnnouncements.value) {
                announced.forEach { Notifier.announcement(applicationContext, it.show, it.episode) }
            }
            Scheduler.rescheduleAll(applicationContext)
            if (System.currentTimeMillis() - c.prefs.lastRecsRefresh > 7L * 24 * 3_600_000) {
                runCatching { c.recommender.refreshCandidates() }
            }
            Updater.newerVersion()?.let { info ->
                if (c.prefs.lastNotifiedUpdate < info.versionCode) {
                    Notifier.update(applicationContext, info.versionName)
                    c.prefs.lastNotifiedUpdate = info.versionCode
                }
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/** Fires at (air time - offset) and shows the episode notification. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val dao = c.db.dao()
        val ep = dao.episode(inputData.getInt(KEY_EP, -1)) ?: return Result.success()
        val show = dao.show(ep.showId) ?: return Result.success()
        if (ep.notified || !c.prefs.notifyEnabled.value) return Result.success()
        val air = ep.airstamp ?: return Result.success()
        val fireAt = air - c.prefs.notifyOffset.value * 60_000L
        // Air time was moved later: the next sync will plan a new reminder.
        if (fireAt - System.currentTimeMillis() > 10 * 60_000L) return Result.success()
        val bmp = Notifier.loadBitmap(
            applicationContext,
            ep.imageOriginal ?: ep.imageMedium ?: show.imageOriginal ?: show.imageMedium,
        )
        Notifier.episode(applicationContext, show, ep, bmp)
        dao.setNotified(ep.id)
        return Result.success()
    }

    companion object { const val KEY_EP = "epId" }
}

/** "✓ Watched" button inside the notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val epId = intent.getIntExtra(Notifier.EXTRA_EP_ID, -1)
        if (epId < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                context.container.repo.setWatched(epId, true)
                NotificationManagerCompat.from(context).cancel(epId)
            } finally {
                pending.finish()
            }
        }
    }
}
