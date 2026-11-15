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
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.data.ShowProgress
import gr.nikkpap.airseries.data.local.ShowEntity
import gr.nikkpap.airseries.data.local.genreList
import gr.nikkpap.airseries.work.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Builds a 1080×1350 poster card (Instagram/WhatsApp friendly) and opens the share sheet. */
object ShareCard {
    private const val W = 1080
    private const val H = 1350
    private const val BG = 0xFF0E0F1A.toInt()
    private const val PINK = 0xFFF472B6.toInt()
    private const val INDIGO = 0xFF818CF8.toInt()

    suspend fun share(ctx: Context, show: ShowEntity, progress: ShowProgress?) {
        val poster = Notifier.loadBitmap(ctx, show.imageOriginal ?: show.imageMedium)
        val file = withContext(Dispatchers.Default) {
            val card = render(ctx, show, progress, poster)
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            File(dir, "AirSeries-${show.id}.png").also { f -> f.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, shareText(ctx, show))
            putExtra(Intent.EXTRA_SUBJECT, show.name)
            clipData = ClipData.newRawUri(show.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.share_with)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layout(text: String, p: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    fun render(ctx: Context, show: ShowEntity, progress: ShowProgress?, poster: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(BG)

        // poster, center-cropped into the top 78%
        val imgH = (H * 0.78f)
        if (poster != null) {
            val scale = max(W / poster.width.toFloat(), imgH / poster.height.toFloat())
            val dw = poster.width * scale
            val dh = poster.height * scale
            val left = (W - dw) / 2f
            val top = (imgH - dh) / 3f // keep faces near the top
            c.save()
            c.clipRect(0f, 0f, W.toFloat(), imgH)
            c.drawBitmap(poster, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            c.restore()
        }
        // fade poster into background
        val fade = Paint().apply {
            shader = LinearGradient(0f, H * 0.32f, 0f, imgH, 0x000E0F1A, BG, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, H * 0.32f, W.toFloat(), H.toFloat(), fade)
        // top shade for the badge
        val shade = Paint().apply { shader = LinearGradient(0f, 0f, 0f, 220f, 0x99000000.toInt(), 0x00000000, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, W.toFloat(), 220f, shade)

        val pad = 64f
        val textW = (W - 2 * pad).toInt()

        // badge
        val badgeP = paint(34f, 0xFFFFFFFF.toInt(), bold = true)
        val badge = "📺  AirSeries"
        val bw = badgeP.measureText(badge) + 48f
        c.drawRoundRect(RectF(pad, 56f, pad + bw, 56f + 64f), 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC4F46E5.toInt() })
        c.drawText(badge, pad + 24f, 56f + 44f, badgeP)

        // ---- text block, laid out from the bottom up ----
        var y = H - pad
        val footP = paint(28f, 0xFF9CA3AF.toInt())
        c.drawText(ctx.getString(R.string.share_card_footer), pad, y, footP)
        y -= 64f

        if (progress != null && progress.total > 0 && progress.watched > 0) {
            val barTop = y - 18f
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2B3D.toInt() }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(pad, 0f, W - pad, 0f, INDIGO, PINK, Shader.TileMode.CLAMP)
            }
            c.drawRoundRect(RectF(pad, barTop, W - pad, y), 9f, 9f, track)
            c.drawRoundRect(RectF(pad, barTop, pad + (W - 2 * pad) * progress.fraction.coerceIn(0.03f, 1f), y), 9f, 9f, fill)
            y = barTop - 22f
            val label = if (progress.watched >= progress.total) ctx.getString(R.string.share_card_done)
            else ctx.getString(R.string.share_card_progress, progress.watched, progress.total, (progress.fraction * 100).toInt())
            c.drawText(label, pad, y, paint(38f, 0xFFFFFFFF.toInt(), bold = true))
            y -= 70f
        }

        val genres = show.genreList().take(4).joinToString("  ·  ")
        if (genres.isNotEmpty()) {
            c.drawText(TextUtils.ellipsize(genres, paint(34f, PINK), textW.toFloat(), TextUtils.TruncateAt.END).toString(), pad, y, paint(34f, PINK))
            y -= 56f
        }

        val years = year(show.premiered)?.let { yr -> if (show.status == "Ended") "$yr–${year(show.ended) ?: ""}" else "$yr–" }
        val meta = listOfNotNull(years, show.network, show.rating?.let { "★ %.1f".format(it) }).joinToString("  ·  ")
        if (meta.isNotEmpty()) {
            c.drawText(meta, pad, y, paint(38f, 0xFFD1D5DB.toInt()))
            y -= 40f
        }

        val title = layout(show.name, paint(88f, 0xFFFFFFFF.toInt(), bold = true), textW, 2)
        y -= title.height
        c.save()
        c.translate(pad, y)
        title.draw(c)
        c.restore()
        return out
    }
}
