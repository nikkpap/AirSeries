package gr.nikkpap.airseries.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.data.Repository
import gr.nikkpap.airseries.data.local.EpisodeEntity
import gr.nikkpap.airseries.data.local.ShowEntity
import gr.nikkpap.airseries.data.local.airTime
import gr.nikkpap.airseries.ui.components.EmptyState
import gr.nikkpap.airseries.ui.components.Poster
import gr.nikkpap.airseries.ui.components.SectionHeader
import gr.nikkpap.airseries.ui.components.appViewModel
import gr.nikkpap.airseries.ui.home.minuteTicker
import gr.nikkpap.airseries.util.daysFromToday
import gr.nikkpap.airseries.util.epCode
import gr.nikkpap.airseries.util.formatDayHeader
import gr.nikkpap.airseries.util.formatTime
import gr.nikkpap.airseries.util.relativeAir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScheduleItem(val show: ShowEntity, val ep: EpisodeEntity, val time: Long)
data class ScheduleState(val upcoming: List<ScheduleItem> = emptyList(), val recent: List<ScheduleItem> = emptyList())

class ScheduleViewModel(private val repo: Repository) : ViewModel() {
    val state = combine(repo.shows, repo.allEpisodes, minuteTicker()) { shows, eps, now ->
        val byId = shows.associateBy { it.id }
        val items = eps.mapNotNull { e ->
            val s = byId[e.showId] ?: return@mapNotNull null
            val t = e.airTime() ?: return@mapNotNull null
            ScheduleItem(s, e, t)
        }
        val day = 24 * 3_600_000L
        ScheduleState(
            upcoming = items.filter { it.time > now && it.time < now + 60 * day }.sortedBy { it.time },
            recent = items.filter { it.time <= now && it.time > now - 14 * day }.sortedByDescending { it.time },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleState())

    fun toggle(ep: EpisodeEntity) = viewModelScope.launch { repo.setWatched(ep.id, !ep.watched) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onOpen: (Int) -> Unit) {
    val vm = appViewModel { ScheduleViewModel(it.repo) }
    val st by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val ctx = LocalContext.current

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.schedule_title), fontWeight = FontWeight.Bold) }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.schedule_upcoming)) })
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.schedule_recent)) })
            }
            val list = if (tab == 0) st.upcoming else st.recent
            if (list.isEmpty()) {
                EmptyState(
                    Icons.Filled.EventBusy,
                    stringResource(if (tab == 0) R.string.schedule_empty_up else R.string.schedule_empty_recent),
                    stringResource(R.string.schedule_empty_text),
                )
            } else {
                val grouped = list.groupBy { daysFromToday(it.time) }
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    grouped.forEach { (d, dayItems) ->
                        item(key = "h$d") {
                            val label = when (d) {
                                0L -> stringResource(R.string.today)
                                1L -> stringResource(R.string.tomorrow)
                                -1L -> stringResource(R.string.yesterday)
                                else -> formatDayHeader(ctx, dayItems.first().time).replaceFirstChar { it.uppercase() }
                            }
                            SectionHeader(label)
                        }
                        items(dayItems, key = { it.ep.id }) { item ->
                            ScheduleRow(item, showCheck = tab == 1, onOpen = { onOpen(item.show.id) }, onToggle = { vm.toggle(item.ep) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(item: ScheduleItem, showCheck: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(item.show.imageMedium, Modifier.width(48.dp).height(70.dp), corner = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.show.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${epCode(item.ep.season, item.ep.number)} · ${item.ep.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(if (item.ep.airstamp != null) formatTime(ctx, item.time) else null, item.show.network).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showCheck) {
            IconButton(onClick = onToggle) {
                if (item.ep.watched) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                else Icon(Icons.Outlined.RadioButtonUnchecked, null)
            }
        } else {
            Box { AssistChip(onClick = onOpen, label = { Text(relativeAir(ctx, item.time), maxLines = 1) }) }
        }
    }
}
