package gr.nikkpap.airseries.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import gr.nikkpap.airseries.MainActivity
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.container
import gr.nikkpap.airseries.data.buildProgress
import gr.nikkpap.airseries.data.local.EpisodeEntity
import gr.nikkpap.airseries.data.local.ListStatus
import gr.nikkpap.airseries.data.local.ShowEntity
import gr.nikkpap.airseries.data.local.airTime
import gr.nikkpap.airseries.util.epCode
import gr.nikkpap.airseries.util.relativeAir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One line in the widget. [checkEpId] != null shows the ✓ button. */
data class WidgetRow(
    val key: Long,
    val showId: Int,
    val showName: String,
    val episode: String,
    val info: String,
    val checkEpId: Int?,
)

private val ShowIdKey = ActionParameters.Key<Int>("showId")
private val EpIdKey = ActionParameters.Key<Int>("epId")

fun buildWidgetRows(ctx: Context, shows: List<ShowEntity>, eps: List<EpisodeEntity>, now: Long): List<WidgetRow> {
    val byShow = eps.groupBy { it.showId }
    val active = shows.filter { it.listStatus == ListStatus.WATCHING || it.listStatus == ListStatus.PLAN }
    val progress = active.map { buildProgress(it, byShow[it.id].orEmpty(), now) }

    // 1) shows with something to watch now (✓ button), most recently watched first
    val toWatch = progress.filter { it.nextUnwatched != null && it.show.listStatus == ListStatus.WATCHING }
        .sortedByDescending { it.lastWatchedAt }
        .map { p ->
            val e = p.nextUnwatched!!
            WidgetRow(
                key = e.id.toLong(),
                showId = p.show.id,
                showName = p.show.name,
                episode = "${epCode(e.season, e.number)} · ${e.name}",
                info = ctx.resources.getQuantityString(R.plurals.left_to_watch, p.unwatchedAired, p.unwatchedAired),
                checkEpId = e.id,
            )
        }
    // 2) episodes airing in the next 7 days
    val week = now + 7 * 24 * 3_600_000L
    val upcoming = progress.mapNotNull { p ->
        val e = p.nextAiring ?: return@mapNotNull null
        val t = e.airTime() ?: return@mapNotNull null
        if (t > week) return@mapNotNull null
        t to WidgetRow(
            key = -e.id.toLong(),
            showId = p.show.id,
            showName = p.show.name,
            episode = "${epCode(e.season, e.number)} · ${e.name}",
            info = relativeAir(ctx, t, now),
            checkEpId = null,
        )
    }.sortedBy { it.first }.map { it.second }

    return (toWatch + upcoming).take(8)
}

class AirSeriesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.container.repo
        val rows = combine(repo.shows, repo.allEpisodes) { s, e -> buildWidgetRows(context, s, e, System.currentTimeMillis()) }
        val initial = rows.first()
        provideContent {
            val data by rows.collectAsState(initial)
            GlanceTheme { WidgetContent(context, data) }
        }
    }
}

@Composable
private fun WidgetContent(ctx: Context, rows: List<WidgetRow>) {
    Column(
        GlanceModifier.fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_notification),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(20.dp),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                ctx.getString(R.string.app_name),
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        if (rows.isEmpty()) {
            Text(
                ctx.getString(R.string.widget_empty),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
        } else {
            LazyColumn {
                items(rows, itemId = { it.key }) { row -> WidgetLine(row) }
            }
        }
    }
}

@Composable
private fun WidgetLine(row: WidgetRow) {
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            GlanceModifier.defaultWeight()
                .clickable(actionStartActivity<MainActivity>(actionParametersOf(ShowIdKey to row.showId))),
        ) {
            Text(
                row.showName, maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
            Text(row.episode, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp))
            Text(
                row.info, maxLines = 1,
                style = TextStyle(
                    color = if (row.checkEpId != null) GlanceTheme.colors.secondary else GlanceTheme.colors.tertiary,
                    fontSize = 11.sp,
                ),
            )
        }
        if (row.checkEpId != null) {
            Spacer(GlanceModifier.width(8.dp))
            Box(
                GlanceModifier.size(38.dp)
                    .background(GlanceTheme.colors.primary)
                    .cornerRadius(19.dp)
                    .clickable(actionRunCallback<MarkWatchedAction>(actionParametersOf(EpIdKey to row.checkEpId))),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

/** ✓ button on the widget: marks the episode as watched. */
class MarkWatchedAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val epId = parameters[EpIdKey] ?: return
        context.container.repo.setWatched(epId, true)
    }
}

class AirSeriesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AirSeriesWidget()
}

/** Called whenever favorites / watched state change so the widget stays current. */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { AirSeriesWidget().updateAll(app) } }
    }
}
