package gr.nikkpap.airseries.util

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.data.local.EpisodeEntity
import gr.nikkpap.airseries.data.local.ShowEntity
import gr.nikkpap.airseries.data.local.airTime

/** Opens the phone's calendar app with the episode pre-filled (no permission needed). */
fun addToCalendar(ctx: Context, show: ShowEntity, ep: EpisodeEntity) {
    val start = ep.airTime() ?: return
    val allDay = ep.airstamp == null
    val duration = (ep.runtime ?: show.runtime ?: 60) * 60_000L
    val details = buildString {
        if (ep.name.isNotBlank()) append(ep.name).append('\n')
        show.network?.let { append(it).append('\n') }
        append('\n').append(ctx.getString(R.string.shared_via))
    }
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + duration)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
        .putExtra(CalendarContract.Events.TITLE, "📺 ${show.name} — ${epCode(ep.season, ep.number)}")
        .putExtra(CalendarContract.Events.DESCRIPTION, details)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, show.network.orEmpty())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }
        .onFailure { Toast.makeText(ctx, R.string.no_calendar_app, Toast.LENGTH_LONG).show() }
}
