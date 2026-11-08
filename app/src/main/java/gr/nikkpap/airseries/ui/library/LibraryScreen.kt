package gr.nikkpap.airseries.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import gr.nikkpap.airseries.data.ShowProgress
import gr.nikkpap.airseries.data.buildProgress
import gr.nikkpap.airseries.ui.components.EmptyState
import gr.nikkpap.airseries.ui.components.Poster
import gr.nikkpap.airseries.ui.components.ProgressLine
import gr.nikkpap.airseries.ui.components.appViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class LibraryViewModel(repo: Repository) : ViewModel() {
    val items = combine(repo.shows, repo.allEpisodes) { shows, eps ->
        val now = System.currentTimeMillis()
        val g = eps.groupBy { it.showId }
        shows.map { buildProgress(it, g[it.id].orEmpty(), now) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

private enum class Filter(val label: Int, val test: (ShowProgress) -> Boolean) {
    All(R.string.f_all, { true }),
    Watching(R.string.f_watching, { it.started && !it.upToDate }),
    UpToDate(R.string.f_uptodate, { it.upToDate && !it.completed }),
    Completed(R.string.f_completed, { it.completed }),
    NotStarted(R.string.f_notstarted, { !it.started }),
    Running(R.string.f_running, { it.show.status == "Running" }),
    Ended(R.string.f_ended, { it.show.status == "Ended" }),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (Int) -> Unit, onSettings: () -> Unit, onDiscover: () -> Unit) {
    val vm = appViewModel { LibraryViewModel(it.repo) }
    val items by vm.items.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title), fontWeight = FontWeight.Bold) },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort)) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf(R.string.sort_name, R.string.sort_recent, R.string.sort_progress, R.string.sort_rating).forEachIndexed { i, res ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(res), fontWeight = if (i == sort) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { sort = i; sortMenu = false },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
                },
            )
        },
    ) { pad ->
        val all = items ?: return@Scaffold
        if (all.isEmpty()) {
            Box(Modifier.padding(pad)) {
                EmptyState(Icons.Filled.FavoriteBorder, stringResource(R.string.library_empty), stringResource(R.string.home_empty_text), stringResource(R.string.home_empty_action), onDiscover)
            }
            return@Scaffold
        }
        val f = Filter.entries[filter]
        val shown = all.filter(f.test).let { l ->
            when (sort) {
                1 -> l.sortedByDescending { it.show.addedAt }
                2 -> l.sortedByDescending { it.fraction }
                3 -> l.sortedByDescending { it.show.rating ?: 0.0 }
                else -> l.sortedBy { it.show.name.lowercase() }
            }
        }
        val watchedEps = all.sumOf { it.watched }
        val hours = all.sumOf { it.watchedMinutes } / 60

        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(
                        ctx.getString(R.string.stats_line, all.size, watchedEps, hours),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Filter.entries.forEachIndexed { i, fl ->
                            val count = all.count(fl.test)
                            FilterChip(selected = i == filter, onClick = { filter = i }, label = { Text("${stringResource(fl.label)} ($count)") })
                        }
                    }
                }
            }
            items(shown, key = { it.show.id }) { p -> LibraryTile(p) { onOpen(p.show.id) } }
        }
    }
}

@Composable
private fun LibraryTile(p: ShowProgress, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            Poster(p.show.imageMedium, Modifier.fillMaxWidth().aspectRatio(0.68f))
            Text(
                "${p.watched}/${p.total}",
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (p.unwatchedAired > 0 && p.started) {
                Text(
                    "+${p.unwatchedAired}",
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ProgressLine(p.fraction)
        Spacer(Modifier.height(4.dp))
        Text(p.show.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
