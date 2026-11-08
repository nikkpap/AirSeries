package gr.nikkpap.airseries.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.container
import gr.nikkpap.airseries.util.Battery
import gr.nikkpap.airseries.data.Repository
import gr.nikkpap.airseries.data.ShowProgress
import gr.nikkpap.airseries.data.buildProgress
import gr.nikkpap.airseries.data.local.EpisodeEntity
import gr.nikkpap.airseries.data.local.airTime
import gr.nikkpap.airseries.ui.components.EmptyState
import gr.nikkpap.airseries.ui.components.Poster
import gr.nikkpap.airseries.ui.components.ProgressLine
import gr.nikkpap.airseries.ui.components.SectionHeader
import gr.nikkpap.airseries.ui.components.appViewModel
import gr.nikkpap.airseries.util.epCode
import gr.nikkpap.airseries.util.relativeAir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeState(
    val loaded: Boolean = false,
    val watching: List<ShowProgress> = emptyList(),
    val upcoming: List<ShowProgress> = emptyList(),
    val totalFavorites: Int = 0,
)

/** Emits the current time every minute so countdowns stay fresh. */
fun minuteTicker() = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(60_000)
    }
}

class HomeViewModel(private val repo: Repository) : ViewModel() {
    val state: StateFlow<HomeState> = combine(repo.shows, repo.allEpisodes, minuteTicker()) { shows, eps, now ->
        val byShow = eps.groupBy { it.showId }
        val all = shows.map { buildProgress(it, byShow[it.id].orEmpty(), now) }
        HomeState(
            loaded = true,
            watching = all.filter { it.nextUnwatched != null }
                .sortedWith(compareByDescending<ShowProgress> { it.lastWatchedAt }.thenBy { it.show.name }),
            upcoming = all.filter { it.nextUnwatched == null && it.nextAiring != null }
                .sortedBy { it.nextAiring?.airTime() ?: Long.MAX_VALUE },
            totalFavorites = shows.size,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun setWatched(ep: EpisodeEntity, watched: Boolean) = viewModelScope.launch { repo.setWatched(ep.id, watched) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Int) -> Unit, onSettings: () -> Unit, onDiscover: () -> Unit) {
    val vm = appViewModel { HomeViewModel(it.repo) }
    val st by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val prefs = ctx.container.prefs
    var showBattery by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        showBattery = !prefs.batteryBannerDismissed && !Battery.isUnrestricted(ctx)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title), fontWeight = FontWeight.Bold) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        when {
            !st.loaded -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            st.totalFavorites == 0 -> Box(Modifier.padding(pad)) {
                EmptyState(
                    Icons.Filled.Tv,
                    stringResource(R.string.home_empty_title),
                    stringResource(R.string.home_empty_text),
                    stringResource(R.string.home_empty_action),
                    onDiscover,
                )
            }
            st.watching.isEmpty() && st.upcoming.isEmpty() -> Box(Modifier.padding(pad)) {
                EmptyState(Icons.Filled.Check, stringResource(R.string.home_all_done_title), stringResource(R.string.home_all_done_text))
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (showBattery) {
                    item(key = "battery") {
                        BatteryBanner(
                            onFix = { Battery.requestUnrestricted(ctx) },
                            onDismiss = { prefs.batteryBannerDismissed = true; showBattery = false },
                        )
                    }
                }
                if (st.watching.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.home_continue)) }
                    items(st.watching, key = { "w${it.show.id}" }) { p ->
                        UpNextCard(p, onOpen = { onOpen(p.show.id) }, onCheck = {
                            val ep = p.nextUnwatched ?: return@UpNextCard
                            vm.setWatched(ep, true)
                            scope.launch {
                                val r = snack.showSnackbar(
                                    ctx.getString(R.string.marked_watched, epCode(ep.season, ep.number)),
                                    actionLabel = ctx.getString(R.string.undo),
                                    duration = SnackbarDuration.Short,
                                )
                                if (r == SnackbarResult.ActionPerformed) vm.setWatched(ep, false)
                            }
                        })
                    }
                }
                if (st.upcoming.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.home_upcoming)) }
                    items(st.upcoming, key = { "u${it.show.id}" }) { p ->
                        UpNextCard(p, onOpen = { onOpen(p.show.id) }, onCheck = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpNextCard(p: ShowProgress, onOpen: () -> Unit, onCheck: (() -> Unit)?) {
    val ctx = LocalContext.current
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(p.show.imageMedium, Modifier.width(72.dp).height(104.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.show.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val ep = p.nextUnwatched
                if (ep != null) {
                    Text(
                        "${epCode(ep.season, ep.number)} · ${ep.name}",
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (p.unwatchedAired > 1) {
                        Text(
                            ctx.resources.getQuantityString(R.plurals.left_to_watch, p.unwatchedAired, p.unwatchedAired),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                p.nextAiring?.let { na ->
                    val t = na.airTime()
                    if (t != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.NotificationsActive, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${epCode(na.season, na.number)} · ${relativeAir(ctx, t)}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProgressLine(p.fraction, Modifier.weight(1f))
                    Text("${p.watched}/${p.total}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            if (onCheck != null) {
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = onCheck, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Check, stringResource(R.string.mark_watched))
                }
            }
        }
    }
}

@Composable
private fun BatteryBanner(onFix: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.BatteryAlert, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bat_banner_title), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(stringResource(R.string.bat_banner_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Row {
                    TextButton(onClick = onFix) { Text(stringResource(R.string.bat_fix)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.bat_not_now)) }
                }
            }
        }
    }
}
