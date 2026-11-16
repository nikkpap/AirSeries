package gr.nikkpap.airseries.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import gr.nikkpap.airseries.AppContainer
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.data.ShowProgress
import gr.nikkpap.airseries.data.buildProgress
import gr.nikkpap.airseries.data.local.EpisodeEntity
import gr.nikkpap.airseries.data.local.ListStatus
import gr.nikkpap.airseries.data.local.ShowEntity
import gr.nikkpap.airseries.data.local.airTime
import gr.nikkpap.airseries.data.local.genreList
import gr.nikkpap.airseries.data.local.isAired
import gr.nikkpap.airseries.data.remote.CastDto
import gr.nikkpap.airseries.data.remote.PersonDto
import gr.nikkpap.airseries.data.remote.ShowDto
import gr.nikkpap.airseries.ui.components.ActionBar
import gr.nikkpap.airseries.ui.components.ActionItem
import gr.nikkpap.airseries.ui.components.EmptyState
import gr.nikkpap.airseries.ui.components.Poster
import gr.nikkpap.airseries.ui.components.ProgressLine
import gr.nikkpap.airseries.ui.components.appViewModel
import gr.nikkpap.airseries.util.GreekTranslator
import gr.nikkpap.airseries.util.ShareCard
import gr.nikkpap.airseries.util.addToCalendar
import gr.nikkpap.airseries.util.locale
import gr.nikkpap.airseries.util.epCode
import gr.nikkpap.airseries.util.formatDate
import gr.nikkpap.airseries.util.formatDateTime
import gr.nikkpap.airseries.util.openUrl
import gr.nikkpap.airseries.util.relativeAir
import gr.nikkpap.airseries.util.shareShow
import gr.nikkpap.airseries.util.stripHtml
import gr.nikkpap.airseries.util.year
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URLEncoder

data class DetailState(
    val loading: Boolean = true,
    val error: String? = null,
    val show: ShowEntity? = null,
    val episodes: List<EpisodeEntity> = emptyList(),
    val isFavorite: Boolean = false,
    val progress: ShowProgress? = null,
)

class DetailViewModel(c: AppContainer, private val id: Int) : ViewModel() {
    private val repo = c.repo
    private val remote = MutableStateFlow<Pair<ShowEntity, List<EpisodeEntity>>?>(null)
    private val loading = MutableStateFlow(true)
    private val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val spoilers: StateFlow<Boolean> = c.prefs.spoilerProtection
    val autoTranslate: StateFlow<Boolean> = c.prefs.autoTranslate
    val cast = MutableStateFlow<List<CastDto>>(emptyList())
    /** Actor sheet: person + their shows (null list = loading). */
    val person = MutableStateFlow<Pair<PersonDto, List<ShowDto>?>?>(null)

    val state: StateFlow<DetailState> = combine(repo.showFlow(id), repo.episodesFlow(id), remote, loading, error) { local, eps, rem, l, e ->
        val fav = local != null
        val show = local ?: rem?.first
        val list = if (fav) eps else rem?.second.orEmpty()
        DetailState(
            loading = l && show == null,
            error = if (show == null) e else null,
            show = show,
            episodes = list,
            isFavorite = fav,
            progress = show?.let { buildProgress(it, list, System.currentTimeMillis()) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState())

    init {
        load()
        viewModelScope.launch { cast.value = runCatching { repo.cast(id) }.getOrDefault(emptyList()) }
    }

    fun openPerson(p: PersonDto) {
        person.value = p to null
        viewModelScope.launch {
            val shows = runCatching { repo.personShows(p.id) }.getOrDefault(emptyList())
            if (person.value?.first?.id == p.id) person.value = p to shows
        }
    }

    fun closePerson() { person.value = null }

    fun setRating(r: Int) = viewModelScope.launch { runCatching { repo.setMyRating(id, r) } }
    fun setNote(n: String) = viewModelScope.launch { runCatching { repo.setNote(id, n) } }

    fun load() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            try {
                val local = repo.getShow(id)
                if (local == null) {
                    remote.value = repo.fetchRemote(id)
                } else if (System.currentTimeMillis() - local.lastSync > 30 * 60_000L) {
                    runCatching { repo.refreshShow(id) }
                }
            } catch (e: Exception) {
                error.value = e.message ?: "error"
            }
            loading.value = false
        }
    }

    fun toggleFavorite() {
        val st = state.value
        viewModelScope.launch {
            busy.value = true
            try {
                if (st.isFavorite) {
                    val s = st.show
                    if (s != null) remote.value = s to st.episodes.map { it.copy(watched = false, watchedAt = null) }
                    repo.removeFavorite(id)
                } else {
                    repo.addFavorite(id)
                }
            } catch (_: Exception) {
            }
            busy.value = false
        }
    }

    fun setList(status: Int) = viewModelScope.launch { runCatching { repo.setListStatus(id, status) } }

    private suspend fun ensureFavorite() {
        if (!state.value.isFavorite) repo.addFavorite(id)
    }

    fun toggleWatched(ep: EpisodeEntity) = viewModelScope.launch {
        runCatching { ensureFavorite(); repo.setWatched(ep.id, !ep.watched) }
    }

    fun setSeason(season: Int, watched: Boolean) = viewModelScope.launch {
        runCatching { ensureFavorite(); repo.setSeasonWatched(id, season, watched) }
    }

    fun markUpTo(ep: EpisodeEntity) = viewModelScope.launch {
        runCatching { ensureFavorite(); repo.markUpTo(ep) }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(showId: Int, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val vm = appViewModel(key = "show_$showId") { DetailViewModel(it, showId) }
    val st by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val spoilers by vm.spoilers.collectAsStateWithLifecycle()
    val autoTranslate by vm.autoTranslate.collectAsStateWithLifecycle()
    val cast by vm.cast.collectAsStateWithLifecycle()
    val person by vm.person.collectAsStateWithLifecycle()
    var translateOn by rememberSaveable(showId) { mutableStateOf(false) }
    val translate = translateOn || autoTranslate
    val offerTranslate = autoTranslate || LocalContext.current.locale().language == "el"
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmRemove by remember { mutableStateOf(false) }
    var markUpToEp by remember { mutableStateOf<EpisodeEntity?>(null) }
    var summaryOpen by rememberSaveable { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    val now = System.currentTimeMillis()

    val show = st.show
    if (show == null) {
        Box(Modifier.fillMaxSize()) {
            when {
                st.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> EmptyState(Icons.Filled.WifiOff, stringResource(R.string.error_network), st.error.orEmpty(), stringResource(R.string.retry)) { vm.load() }
            }
            BackButton(onBack, Modifier.statusBarsPadding().padding(8.dp))
        }
        return
    }

    val seasons = st.episodes.groupBy { it.season }.toSortedMap()
    val defaultSeason = st.progress?.nextUnwatched?.season ?: st.progress?.nextAiring?.season ?: seasons.keys.lastOrNull()

    LazyColumn(Modifier.fillMaxSize()) {
        // ---------- header ----------
        item {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                AsyncImage(
                    model = show.imageOriginal ?: show.imageMedium,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(18.dp),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), MaterialTheme.colorScheme.background))
                    )
                )
                Row(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.Bottom) {
                    Poster(show.imageMedium, Modifier.width(120.dp).height(176.dp), corner = 12.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(show.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val years = year(show.premiered)?.let { y -> if (show.status == "Ended") "$y–${year(show.ended) ?: ""}" else "$y–" }
                        Text(
                            listOfNotNull(years, show.network, show.runtime?.let { "$it′" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            show.rating?.let {
                                Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                                Text(" %.1f   ".format(it), fontWeight = FontWeight.Bold)
                            }
                            Text(statusLabel(show.status), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                BackButton(onBack, Modifier.statusBarsPadding().padding(8.dp))
            }
        }

        // ---------- genres & actions ----------
        item(key = "actions") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (show.genreList().isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        show.genreList().forEach { AssistChip(onClick = {}, label = { Text(it, maxLines = 1) }) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (st.isFavorite) {
                    FilledTonalButton(
                        onClick = { confirmRemove = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.in_favorites), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Button(onClick = vm::toggleFavorite, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(Icons.Filled.FavoriteBorder, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_favorite), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val actions = buildList {
                    add(ActionItem(Icons.Filled.Share, stringResource(R.string.share)) {
                        val prog = st.progress.takeIf { st.isFavorite }
                        scope.launch { runCatching { ShareCard.share(ctx, show, prog) }.onFailure { shareShow(ctx, show) } }
                    })
                    add(ActionItem(Icons.Filled.SmartDisplay, stringResource(R.string.trailer)) {
                        val q = URLEncoder.encode("${show.name} ${year(show.premiered).orEmpty()} trailer", "UTF-8")
                        openUrl(ctx, "https://www.youtube.com/results?search_query=$q")
                    })
                    add(ActionItem(Icons.Filled.Tv, "JustWatch") { openUrl(ctx, justWatchUrl(show.name)) })
                    show.network?.takeIf { it.isNotBlank() }?.let { net ->
                        add(ActionItem(Icons.Filled.PlayCircle, net) { openUrl(ctx, platformUrl(show.name, net)) })
                    }
                    add(ActionItem(Icons.AutoMirrored.Filled.OpenInNew, if (show.imdb != null) "IMDb" else "TVmaze") {
                        openUrl(ctx, show.imdb?.let { "https://www.imdb.com/title/$it/" } ?: show.url ?: "https://www.tvmaze.com/shows/${show.id}")
                    })
                }
                ActionBar(actions)
            }
        }

        // ---------- personal list ----------
        if (st.isFavorite) {
            item(key = "list") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.my_list), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            ListStatus.WATCHING to R.string.list_watching,
                            ListStatus.PLAN to R.string.list_plan,
                            ListStatus.PAUSED to R.string.list_paused,
                            ListStatus.DROPPED to R.string.list_dropped,
                        ).forEach { (value, label) ->
                            val selected = show.listStatus == value
                            FilterChip(
                                selected = selected,
                                onClick = { vm.setList(value) },
                                label = { Text(stringResource(label), maxLines = 1) },
                                leadingIcon = if (selected) { { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) } } else null,
                            )
                        }
                    }
                }
            }
        }

        // ---------- my rating & note ----------
        if (st.isFavorite) {
            item(key = "opinion") {
                MyOpinionCard(show.myRating, show.note, onRate = vm::setRating, onSaveNote = vm::setNote)
            }
        }

        // ---------- progress ----------
        st.progress?.let { p ->
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.progress), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(
                                "${p.watched}/${p.total}  (${(p.fraction * 100).toInt()}%)",
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        ProgressLine(p.fraction)
                        Spacer(Modifier.height(8.dp))
                        p.nextUnwatched?.let {
                            Text(stringResource(R.string.next_to_watch, "${epCode(it.season, it.number)} · ${it.name}"), style = MaterialTheme.typography.bodyMedium)
                        }
                        p.nextAiring?.let { na ->
                            na.airTime()?.let { t ->
                                Text(
                                    stringResource(R.string.next_airing, epCode(na.season, na.number), formatDateTime(ctx, t), relativeAir(ctx, t)),
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                )
                            }
                        } ?: Text(
                            stringResource(if (show.status == "Ended") R.string.no_next_ended else R.string.no_next_tba),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!st.isFavorite) {
                            Text(stringResource(R.string.hint_add_to_track), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }

        // ---------- summary ----------
        item {
            val sum = stripHtml(show.summary)
            if (sum.isNotEmpty()) {
                SummaryBlock(sum, translate, offerTranslate) { translateOn = !translateOn }
                Spacer(Modifier.height(8.dp))
            }
            show.scheduleDays?.takeIf { it.isNotBlank() }?.let { days ->
                Text(
                    stringResource(R.string.airs_on, days + (show.scheduleTime?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            CastRow(cast) { vm.openPerson(it) }
            Text(
                stringResource(R.string.episodes_header),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }

        // ---------- seasons ----------
        seasons.forEach { (season, eps) ->
            val regular = eps.filter { it.number != null }
            val watchedCount = regular.count { it.watched }
            val airedCount = regular.count { it.isAired(now) }
            val isOpen = expanded[season] ?: (season == defaultSeason)
            item(key = "s$season") {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded[season] = !isOpen }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (season == 0) stringResource(R.string.specials) else stringResource(R.string.season_n, season),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        )
                        Text("$watchedCount/${regular.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(
                        checked = airedCount > 0 && watchedCount >= airedCount,
                        enabled = airedCount > 0,
                        onCheckedChange = { vm.setSeason(season, it) },
                    )
                    Icon(if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.padding(8.dp))
                }
                HorizontalDivider()
            }
            if (isOpen) {
                items(eps, key = { "e${it.id}" }) { ep ->
                    EpisodeRow(
                        ep = ep,
                        aired = ep.isAired(now),
                        hideSpoilers = spoilers && !ep.watched,
                        onToggle = { vm.toggleWatched(ep) },
                        onLongPress = { markUpToEp = ep },
                        translate = translate,
                        onCalendar = { addToCalendar(ctx, show, ep) },
                    )
                }
            }
        }

        item {
            Text(
                stringResource(R.string.data_by_tvmaze),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp).navigationBarsPadding(),
            )
        }
    }

    person?.let { (p, shows) ->
        PersonSheet(p, shows, show.id, onDismiss = vm::closePerson) { id ->
            vm.closePerson()
            onOpen(id)
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_title)) },
            text = { Text(stringResource(R.string.remove_text, show.name)) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.toggleFavorite() }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    markUpToEp?.let { ep ->
        AlertDialog(
            onDismissRequest = { markUpToEp = null },
            title = { Text(stringResource(R.string.mark_upto_title)) },
            text = { Text(stringResource(R.string.mark_upto_text, epCode(ep.season, ep.number))) },
            confirmButton = { TextButton(onClick = { markUpToEp = null; vm.markUpTo(ep) }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { markUpToEp = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier) {
    IconButton(
        onClick = onBack,
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White),
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
}

@Composable
private fun statusLabel(status: String?): String = when (status) {
    "Running" -> stringResource(R.string.status_running)
    "Ended" -> stringResource(R.string.status_ended)
    "To Be Determined" -> stringResource(R.string.status_tbd)
    "In Development" -> stringResource(R.string.status_dev)
    else -> status.orEmpty()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    ep: EpisodeEntity,
    aired: Boolean,
    hideSpoilers: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    translate: Boolean,
    onCalendar: () -> Unit,
) {
    val ctx = LocalContext.current
    var open by rememberSaveable(ep.id) { mutableStateOf(false) }
    var reveal by rememberSaveable(ep.id) { mutableStateOf(false) }
    val hidden = hideSpoilers && !reveal
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = { open = !open }, onLongClick = { if (aired) onLongPress() })
            .animateContentSize()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(118.dp).height(66.dp)) {
            Poster(ep.imageMedium, Modifier.fillMaxSize().then(if (hidden) Modifier.blur(14.dp) else Modifier), corner = 8.dp)
            if (ep.watched) {
                Icon(
                    Icons.Filled.CheckCircle, null,
                    Modifier.align(Alignment.Center).size(30.dp).background(Color.White, CircleShape),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${ep.number?.let { "E%02d".format(it) } ?: "SP"} · ${ep.name}",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis,
            )
            val t = ep.airTime()
            val dateTxt = when {
                t == null -> stringResource(R.string.tba)
                aired -> formatDate(ctx, t)
                else -> "${if (ep.airstamp != null) formatDateTime(ctx, t) else formatDate(ctx, t)} · ${relativeAir(ctx, t)}"
            }
            Text(
                dateTxt + (ep.rating?.let { "  ★ %.1f".format(it) } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = if (aired) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
            )
            if (open) {
                val original = stripHtml(ep.summary)
                var translated by remember(ep.id) { mutableStateOf<String?>(null) }
                LaunchedEffect(translate, hidden) {
                    if (translate && !hidden && translated == null && original.isNotEmpty()) {
                        translated = runCatching { GreekTranslator.translate(original) }.getOrNull()
                    }
                }
                val sum = if (translate) translated ?: original else original
                when {
                    sum.isEmpty() -> Text(stringResource(R.string.no_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    hidden -> TextButton(onClick = { reveal = true }) { Text(stringResource(R.string.spoiler_reveal)) }
                    else -> Text(sum, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (aired) {
            IconButton(onClick = onToggle) {
                if (ep.watched) Icon(Icons.Filled.CheckCircle, stringResource(R.string.mark_unwatched), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                else Icon(Icons.Outlined.RadioButtonUnchecked, stringResource(R.string.mark_watched), modifier = Modifier.size(30.dp))
            }
        } else {
            IconButton(onClick = onCalendar) {
                Icon(Icons.Filled.EditCalendar, stringResource(R.string.add_to_calendar), tint = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}
