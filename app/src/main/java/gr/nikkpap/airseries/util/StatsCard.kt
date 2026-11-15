package gr.nikkpap.airseries.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.data.Stats
import gr.nikkpap.airseries.work.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** "My year" share image, 1080×1350. */
object StatsCard {
    private const val W = 1080
    private const val H = 1350

    suspend fun share(ctx: Context, stats: Stats, year: Int) {
        val posters = stats.topShows.take(3).map { Notifier.loadBitmap(ctx, it.first.imageMedium) }
        val file = withContext(Dispatchers.Default) {
            val bmp = render(ctx, stats, year, posters)
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            File(dir, "AirSeries-${if (year == 0) "all" else year}.png").also { f ->
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, ctx.getString(R.string.about_share_text, "https://github.com/nikkpap/AirSeries/releases/latest"))
            clipData = ClipData.newRawUri("AirSeries", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.share_with)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun p(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun Canvas.centerText(text: String, cx: Float, y: Float, paint: TextPaint) {
        drawText(text, cx - paint.measureText(text) / 2f, y, paint)
    }

    fun render(ctx: Context, s: Stats, year: Int, posters: List<Bitmap?>): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), 0xFF1E1B4B.toInt(), 0xFF0E0F1A.toInt(), Shader.TileMode.CLAMP)
        })
        // glow
        c.drawCircle(W * 0.85f, 120f, 380f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33F472B6 })
        c.drawCircle(W * 0.1f, H * 0.62f, 300f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x224F46E5 })

        val pad = 72f
        c.drawText("📺  AirSeries", pad, 120f, p(36f, 0xFFA5B4FC.toInt(), true))
        val title = if (year == 0) ctx.getString(R.string.stats_card_title_all) else ctx.getString(R.string.stats_card_title, year)
        c.drawText(title, pad, 230f, p(84f, 0xFFFFFFFF.toInt(), true))

        // big numbers
        val nums = listOf(
            "${s.episodes}" to ctx.getString(R.string.stats_episodes),
            "${s.minutes / 60}" to ctx.getString(R.string.stats_hours),
            "${s.showCount}" to ctx.getString(R.string.stats_shows),
        )
        val colW = (W - 2 * pad) / 3f
        val boxTop = 280f
        nums.forEachIndexed { i, (v, label) ->
            val left = pad + i * colW
            c.drawRoundRect(RectF(left + 8f, boxTop, left + colW - 8f, boxTop + 200f), 28f, 28f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF })
            c.centerText(v, left + colW / 2f, boxTop + 115f, p(84f, 0xFFFFFFFF.toInt(), true))
            c.centerText(label, left + colW / 2f, boxTop + 165f, p(32f, 0xFFD1D5DB.toInt()))
        }

        // top shows
        var y = boxTop + 290f
        if (s.topShows.isNotEmpty()) {
            c.drawText(ctx.getString(R.string.stats_top_shows), pad, y, p(40f, 0xFFF472B6.toInt(), true))
            y += 30f
            val posterW = 116f
            val posterH = 170f
            s.topShows.take(3).forEachIndexed { i, (show, count) ->
                val top = y + i * (posterH + 20f)
                val bmp = posters.getOrNull(i)
                val rect = RectF(pad, top, pad + posterW, top + posterH)
                if (bmp != null) {
                    c.save()
                    val path = android.graphics.Path().apply { addRoundRect(rect, 18f, 18f, android.graphics.Path.Direction.CW) }
                    c.clipPath(path)
                    val scale = max(posterW / bmp.width, posterH / bmp.height)
                    val dw = bmp.width * scale
                    val dh = bmp.height * scale
                    c.drawBitmap(bmp, null, RectF(rect.centerX() - dw / 2, rect.centerY() - dh / 2, rect.centerX() + dw / 2, rect.centerY() + dh / 2),
                        Paint(Paint.FILTER_BITMAP_FLAG))
                    c.restore()
                } else {
                    c.drawRoundRect(rect, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2B3D.toInt() })
                }
                val tx = pad + posterW + 36f
                val nameP = p(50f, 0xFFFFFFFF.toInt(), true)
                val name = TextUtils.ellipsize(show.name, nameP, W - tx - pad, TextUtils.TruncateAt.END).toString()
                c.drawText("${i + 1}", tx, top + 64f, p(44f, 0xFFA5B4FC.toInt(), true))
                c.drawText(name, tx + 60f, top + 64f, nameP)
                c.drawText(ctx.resources.getQuantityString(R.plurals.n_episodes, count, count), tx + 60f, top + 118f, p(36f, 0xFFD1D5DB.toInt()))
            }
            y += 3 * (posterH + 20f)
        }

        s.topGenres.firstOrNull()?.let { (g, _) ->
            c.drawText(ctx.getString(R.string.stats_card_genre, g), pad, H - 120f, p(40f, 0xFFFFFFFF.toInt(), true))
        }
        c.drawText("github.com/nikkpap/AirSeries", pad, H - 60f, p(30f, 0xFF9CA3AF.toInt()))
        return out
    }
}
