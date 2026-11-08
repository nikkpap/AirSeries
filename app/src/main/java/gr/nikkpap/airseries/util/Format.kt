package gr.nikkpap.airseries.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.text.HtmlCompat
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.data.local.ShowEntity
import gr.nikkpap.airseries.data.local.genreList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

fun epCode(season: Int, number: Int?): String =
    if (number == null) "S%02d · Special".format(season) else "S%02dE%02d".format(season, number)

fun stripHtml(s: String?): String =
    s?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }.orEmpty()

fun Context.locale(): Locale = resources.configuration.locales.get(0) ?: Locale.getDefault()

private fun fmt(ctx: Context, pattern: String, ms: Long): String =
    DateTimeFormatter.ofPattern(pattern, ctx.locale()).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun formatDateTime(ctx: Context, ms: Long) = fmt(ctx, "EEE d MMM, HH:mm", ms)
fun formatDate(ctx: Context, ms: Long) = fmt(ctx, "EEE d MMM yyyy", ms)
fun formatDayHeader(ctx: Context, ms: Long) = fmt(ctx, "EEEE d MMMM", ms)
fun formatTime(ctx: Context, ms: Long) = fmt(ctx, "HH:mm", ms)

fun daysFromToday(ms: Long): Long {
    val zone = ZoneId.systemDefault()
    val today = Instant.now().atZone(zone).toLocalDate()
    val target = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(today, target)
}

/** "in 3 days", "today 22:00", "tomorrow 21:00", "in 40 min", "aired". */
fun relativeAir(ctx: Context, ms: Long, now: Long = System.currentTimeMillis()): String {
    val diff = ms - now
    val days = daysFromToday(ms)
    val r = ctx.resources
    return when {
        diff < 0 -> when (days) {
            0L -> ctx.getString(R.string.aired_today)
            -1L -> ctx.getString(R.string.aired_yesterday)
            else -> r.getQuantityString(R.plurals.days_ago, (-days).toInt(), (-days).toInt())
        }
        diff < 3_600_000L -> {
            val m = (diff / 60_000L).toInt().coerceAtLeast(1)
            r.getQuantityString(R.plurals.in_minutes, m, m)
        }
        days == 0L -> ctx.getString(R.string.today_at, formatTime(ctx, ms))
        days == 1L -> ctx.getString(R.string.tomorrow_at, formatTime(ctx, ms))
        else -> r.getQuantityString(R.plurals.in_days, days.toInt(), days.toInt())
    }
}

fun year(date: String?): String? = date?.takeIf { it.length >= 4 }?.take(4)

fun shareShow(ctx: Context, show: ShowEntity) {
    val link = show.url ?: "https://www.tvmaze.com/shows/${show.id}"
    val text = buildString {
        append("📺 ").append(show.name)
        year(show.premiered)?.let { append(" (").append(it).append(")") }
        append('\n')
        val meta = listOfNotNull(
            show.rating?.let { "⭐ %.1f".format(it) },
            show.genreList().takeIf { it.isNotEmpty() }?.joinToString(", "),
            show.network,
        )
        if (meta.isNotEmpty()) append(meta.joinToString(" • ")).append('\n')
        val sum = stripHtml(show.summary)
        if (sum.isNotEmpty()) append('\n').append(if (sum.length > 240) sum.take(240).trimEnd() + "…" else sum).append('\n')
        append('\n').append(link)
        append("\n\n").append(ctx.getString(R.string.shared_via))
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, show.name)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.share_with)))
}

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
