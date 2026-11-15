package gr.nikkpap.airseries.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.data.Repository
import gr.nikkpap.airseries.data.Stats
import gr.nikkpap.airseries.data.computeStats
import gr.nikkpap.airseries.data.local.EpisodeEntity
import gr.nikkpap.airseries.data.local.ShowEntity
import gr.nikkpap.airseries.ui.components.EmptyState
import gr.nikkpap.airseries.ui.components.Poster
import gr.nikkpap.airseries.ui.components.appViewModel
import gr.nikkpap.airseries.util.StatsCard
import gr.nikkpap.airseries.util.formatDate
import gr.nikkpap.airseries.util.locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle

class StatsViewModel(repo: Repository) : ViewModel() {
    val data = combine(repo.shows, repo.allEpisodes) { s, e -> s to e }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val vm = appViewModel { StatsViewModel(it.repo) }
    val data by vm.data.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 0 = all time, otherwise the year
    var year by rememberSaveable { mutableIntStateOf(java.time.Year.now().value) }

    val pair = data
    val shows: List<ShowEntity> = pair?.first.orEmpty()
    val eps: List<EpisodeEntity> = pair?.second.orEmpty()
    val years = eps.mapNotNull { it.watchedAt }.map { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).year }
        .distinct().sortedDescending()
    val stats = computeStats(shows, eps, if (year == 0) null else year)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title), fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (stats.episodes > 0) {
                        IconButton(onClick = { scope.launch { runCatching { StatsCard.share(ctx, stats, year) } } }) {
                            Icon(Icons.Filled.Share, stringResource(R.string.share))
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (listOf(java.time.Year.now().value) + years).distinct().forEach { y ->
                    FilterChip(selected = year == y, onClick = { year = y }, label = { Text("$y") })
                }
                FilterChip(selected = year == 0, onClick = { year = 0 }, label = { Text(stringResource(R.string.stats_all_time)) })
            }

            if (stats.episodes == 0) {
                Box(Modifier.height(420.dp)) {
                    EmptyState(Icons.Filled.Insights, stringResource(R.string.stats_empty_title), stringResource(R.string.stats_empty_text))
                }
                return@Column
            }

            // ---- big numbers ----
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigNumber("${stats.episodes}", stringResource(R.string.stats_episodes), Modifier.weight(1f))
                BigNumber("${stats.minutes / 60}", stringResource(R.string.stats_hours), Modifier.weight(1f))
                BigNumber("${stats.showCount}", stringResource(R.string.stats_shows), Modifier.weight(1f))
            }
            Text(
                stringResource(R.string.stats_days_equiv, "%.1f".format(stats.minutes / 1440.0)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )

            // ---- months ----
            Section(stringResource(if (year == 0) R.string.stats_last_12 else R.string.stats_per_month))
            MonthBars(stats.monthLabels(ctx), stats.months)

            // ---- top shows ----
            if (stats.topShows.isNotEmpty()) {
                Section(stringResource(R.string.stats_top_shows))
                stats.topShows.forEachIndexed { i, (show, count) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(show.id) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                        Poster(show.imageMedium, Modifier.width(40.dp).height(58.dp), corner = 6.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(show.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(ctx.resources.getQuantityString(R.plurals.n_episodes, count, count), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // ---- genres ----
            if (stats.topGenres.isNotEmpty()) {
                Section(stringResource(R.string.stats_top_genres))
                val max = stats.topGenres.first().second.coerceAtLeast(1)
                stats.topGenres.forEach { (g, mins) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(g, Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.weight(1f).height(14.dp)) {
                            Box(
                                Modifier.fillMaxHeight().fillMaxWidth(mins.toFloat() / max)
                                    .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(7.dp))
                            )
                        }
                        Text("  ${mins / 60}h", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // ---- fun facts ----
            Section(stringResource(R.string.stats_facts))
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    stats.bestDay?.let { (day, count) ->
                        Fact("🍿", stringResource(R.string.stats_best_day, formatDate(ctx, day), count))
                    }
                    stats.favoriteWeekday?.let { d ->
                        Fact("📅", stringResource(R.string.stats_weekday, DayOfWeek.of(d).getDisplayName(TextStyle.FULL, ctx.locale())))
                    }
                    if (stats.completedShows > 0) Fact("🏁", stringResource(R.string.stats_completed, stats.completedShows))
                    stats.avgMyRating?.let { Fact("⭐", stringResource(R.string.stats_avg_rating, "%.1f".format(it))) }
                    stats.longestShow?.let { (name, count) -> Fact("🔥", stringResource(R.string.stats_marathon, name, count)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BigNumber(value: String, label: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Fact(emoji: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(emoji, Modifier.width(30.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MonthBars(labels: List<String>, values: List<Int>) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { i, v ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                if (v > 0) Text("$v", style = MaterialTheme.typography.labelSmall)
                Box(
                    Modifier.fillMaxWidth().height((100f * v / max).dp.coerceAtLeast(2.dp))
                        .background(
                            if (v == max) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                        )
                )
                Text(labels[i], style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
