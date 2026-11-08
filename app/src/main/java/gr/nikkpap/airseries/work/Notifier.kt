package gr.nikkpap.airseries.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import gr.nikkpap.airseries.MainActivity
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.data.local.EpisodeEntity
import gr.nikkpap.airseries.data.local.ShowEntity
import gr.nikkpap.airseries.util.epCode
import gr.nikkpap.airseries.util.formatDateTime

object Notifier {
    const val CH_EPISODES = "episodes"
    const val CH_NEWS = "news"
    const val EXTRA_SHOW_ID = "showId"
    const val EXTRA_EP_ID = "epId"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_EPISODES, ctx.getString(R.string.ch_episodes), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = ctx.getString(R.string.ch_episodes_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_NEWS, ctx.getString(R.string.ch_news), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = ctx.getString(R.string.ch_news_desc) }
        )
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    suspend fun loadBitmap(ctx: Context, url: String?): Bitmap? {
        if (url == null) return null
        return runCatching {
            val req = ImageRequest.Builder(ctx).data(url).allowHardware(false).size(1024).build()
            (ctx.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap()
        }.getOrNull()
    }

    private fun openShow(ctx: Context, showId: Int, requestCode: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SHOW_ID, showId)
        }
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission")
    fun episode(ctx: Context, show: ShowEntity, ep: EpisodeEntity, bmp: Bitmap?) {
        if (!canNotify(ctx)) return
        val text = "${epCode(ep.season, ep.number)} · ${ep.name}"
        val watchedIntent = PendingIntent.getBroadcast(
            ctx, ep.id,
            Intent(ctx, NotificationActionReceiver::class.java).putExtra(EXTRA_EP_ID, ep.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(ctx, CH_EPISODES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_airing_title, show.name))
            .setContentText(text)
            .setSubText(show.network)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openShow(ctx, show.id, ep.id))
            .addAction(R.drawable.ic_notification, ctx.getString(R.string.notif_mark_watched), watchedIntent)
        if (bmp != null) {
            b.setLargeIcon(bmp)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as Bitmap?).setSummaryText(text))
        }
        NotificationManagerCompat.from(ctx).notify(ep.id, b.build())
    }

    @SuppressLint("MissingPermission")
    fun announcement(ctx: Context, show: ShowEntity, ep: EpisodeEntity) {
        if (!canNotify(ctx)) return
        val whenTxt = ep.airstamp?.let { formatDateTime(ctx, it) } ?: ep.airdate.orEmpty()
        val n = NotificationCompat.Builder(ctx, CH_NEWS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_announce_title, show.name))
            .setContentText("${epCode(ep.season, ep.number)} · $whenTxt")
            .setAutoCancel(true)
            .setContentIntent(openShow(ctx, show.id, -ep.id))
            .build()
        NotificationManagerCompat.from(ctx).notify(-ep.id, n)
    }

    @SuppressLint("MissingPermission")
    fun update(ctx: Context, versionName: String) {
        if (!canNotify(ctx)) return
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val n = NotificationCompat.Builder(ctx, CH_NEWS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.upd_notif_title, versionName))
            .setContentText(ctx.getString(R.string.upd_notif_text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, 2, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        NotificationManagerCompat.from(ctx).notify(2, n)
    }

    @SuppressLint("MissingPermission")
    fun test(ctx: Context) {
        if (!canNotify(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_EPISODES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_test_title))
            .setContentText(ctx.getString(R.string.notif_test_text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(ctx).notify(1, n)
    }
}
