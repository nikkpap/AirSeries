package gr.nikkpap.airseries.ui.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import gr.nikkpap.airseries.AppContainer
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.data.genreNames
import gr.nikkpap.airseries.data.local.MovieEntity
import gr.nikkpap.airseries.data.local.MovieStatus
import gr.nikkpap.airseries.data.remote.TmdbApi
import gr.nikkpap.airseries.data.remote.TmdbMovie
import gr.nikkpap.airseries.ui.components.EmptyState
import gr.nikkpap.airseries.ui.components.Poster
import gr.nikkpap.airseries.ui.components.appViewModel
import gr.nikkpap.airseries.util.year
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Small card data shared by lists. */
data class MovieCard(val id: Int, val title: String, val poster: String?, val sub: String?, val rating: Double?)

fun TmdbMovie.toCard() = MovieCard(id, title, TmdbApi.poster(posterPath), year(releaseDate), voteAverage)

data class MovieSearch(val active: Boolean = false, val loading: Boolean = false, val results: List<TmdbMovie> = emptyList(), val error: String? = null)

data class MovieLists(
    val loading: Boolean = true,
    val nowPlaying: List<TmdbMovie> = emptyList(),
    val upcoming: List<TmdbMovie> = emptyList(),
    val popular: List<TmdbMovie> = emptyList(),
    val forYou: List<TmdbMovie> = emptyList(),
    val error: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MoviesViewModel(c: AppContainer) : ViewModel() {
    private val repo = c.movies
    val hasKey = repo.hasKey
    val greek: Boolean get() = repo.isGreek
    val query = MutableStateFlow("")
    val busy = MutableStateFlow<Set<Int>>(emptySet())

    val mine: StateFlow<List<MovieEntity>> = repo.movies.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val search: StateFlow<MovieSearch> = query.debounce(400).map { it.trim() }.distinctUntilChanged()
        .transformLatest { q ->
            if (q.length < 2 || !hasKey) emit(MovieSearch())
            else {
                emit(MovieSearch(active = true, loading = true))
                emit(runCatching { MovieSearch(active = true, results = repo.search(q)) }
                    .getOrElse { MovieSearch(active = true, error = it.message ?: "error") })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MovieSearch())

    val lists = MutableStateFlow(MovieLists())

    init { if (hasKey) load() else lists.value = MovieLists(loading = false) }

    fun load() = viewModelScope.launch {
        lists.update { it.copy(loading = true, error = null) }
        try {
            val now = repo.nowPlaying()
            val up = repo.upcoming()
            val pop = repo.popular()
            lists.value = MovieLists(false, now, up, pop, emptyList())
            val fy = runCatching { repo.forYou(mine.value.ifEmpty { mineOnce() }) }.getOrDefault(emptyList())
            lists.update { it.copy(forYou = fy) }
        } catch (e: Exception) {
            lists.value = MovieLists(loading = false, error = e.message ?: "error")
        }
    }

    private suspend fun mineOnce(): List<MovieEntity> = runCatching { repo.movies.first() }.getOrDefault(emptyList())

    fun add(id: Int, status: Int) = viewModelScope.launch {
        busy.update { it + id }
        runCatching { repo.add(id, status) }
        busy.update { it - id }
    }
}

@Composable
fun MoviesScreen(onOpen: (Int) -> Unit) {
    val vm = appViewModel { MoviesViewModel(it) }
    val q by vm.query.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val mine by vm.mine.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val mineIds = mine.associateBy { it.id }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = q,
            onValueChange = { vm.query.value = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.mv_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, null) } },
            singleLine = true,
            enabled = vm.hasKey,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        if (!vm.hasKey) {
            EmptyState(Icons.Filled.Key, stringResource(R.string.mv_no_key_title), stringResource(R.string.mv_no_key_text))
            return@Column
        }

        if (search.active) {
            when {
                search.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                search.error != null -> EmptyState(Icons.Filled.SearchOff, stringResource(R.string.error_network), search.error.orEmpty())
                search.results.isEmpty() -> EmptyState(Icons.Filled.SearchOff, stringResource(R.string.search_none), q)
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(search.results, key = { it.id }) { m ->
                        MovieRow(
                            m, vm.greek, mineIds[m.id], m.id in busy,
                            onOpen = { focus.clearFocus(); onOpen(m.id) },
                            onAdd = { vm.add(m.id, MovieStatus.PLAN) },
                        )
                    }
                }
            }
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
            val plan = mine.filter { it.status == MovieStatus.PLAN }.sortedBy { it.releaseGr ?: it.releaseDate ?: "9999" }
            val watched = mine.filter { it.status == MovieStatus.WATCHED }.sortedByDescending { it.watchedAt ?: 0L }
            if (plan.isNotEmpty()) item(key = "plan") {
                Shelf(stringResource(R.string.mv_plan) + " (${plan.size})", plan.map { it.toCard() }, mineIds, onOpen)
            }
            if (watched.isNotEmpty()) item(key = "watched") {
                Shelf(stringResource(R.string.mv_watched) + " (${watched.size})", watched.map { it.toCard() }, mineIds, onOpen)
            }
            if (mine.isEmpty()) item(key = "hint") {
                Text(
                    stringResource(R.string.mv_hint),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (lists.forYou.isNotEmpty()) item(key = "foryou") {
                Shelf(stringResource(R.string.mv_for_you), lists.forYou.map { it.toCard() }, mineIds, onOpen)
            }
            if (lists.loading) item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            lists.error?.let { err ->
                item(key = "err") { EmptyStateSmall(stringResource(R.string.error_network), err) { vm.load() } }
            }
            if (lists.nowPlaying.isNotEmpty()) item(key = "now") {
                Shelf(stringResource(R.string.mv_now_playing), lists.nowPlaying.map { it.toCard() }, mineIds, onOpen)
            }
            if (lists.upcoming.isNotEmpty()) item(key = "up") {
                Shelf(stringResource(R.string.mv_upcoming), lists.upcoming.map { it.toCard().copy(sub = it.releaseDate?.let(::shortDate)) }, mineIds, onOpen)
            }
            if (lists.popular.isNotEmpty()) item(key = "pop") {
                Shelf(stringResource(R.string.mv_popular), lists.popular.map { it.toCard() }, mineIds, onOpen)
            }
            item(key = "tmdb") { TmdbAttribution() }
        }
    }
}

fun MovieEntity.toCard() = MovieCard(id, title, TmdbApi.poster(poster), releaseLabel(this), rating)

private fun shortDate(d: String): String = runCatching {
    val date = LocalDate.parse(d.take(10))
    "%02d/%02d".format(date.dayOfMonth, date.monthValue)
}.getOrDefault(d)

/** "12/12" for upcoming releases, year otherwise. */
private fun releaseLabel(m: MovieEntity): String? {
    val d = m.releaseGr ?: m.releaseDate ?: return null
    val date = runCatching { LocalDate.parse(d.take(10)) }.getOrNull() ?: return year(d)
    return if (date.isAfter(LocalDate.now())) "📅 " + shortDate(d) else year(d)
}

@Composable
fun Shelf(title: String, items: List<MovieCard>, mine: Map<Int, MovieEntity>, onOpen: (Int) -> Unit) {
    Column {
        Text(
            title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { m -> PosterCard(m, mine[m.id]) { onOpen(m.id) } }
        }
    }
}

@Composable
private fun PosterCard(m: MovieCard, mine: MovieEntity?, onClick: () -> Unit) {
    Column(Modifier.width(112.dp).clickable(onClick = onClick)) {
        Box {
            Poster(m.poster, Modifier.fillMaxWidth().aspectRatio(0.67f))
            if (mine != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(24.dp)
                        .background(if (mine.status == MovieStatus.WATCHED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (mine.status == MovieStatus.WATCHED) Icons.Filled.Check else Icons.Filled.Schedule, null,
                        tint = Color.White, modifier = Modifier.size(15.dp),
                    )
                }
            }
            m.rating?.takeIf { it > 0 }?.let {
                Text(
                    "★ %.1f".format(it), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(m.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        m.sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun MovieRow(m: TmdbMovie, greek: Boolean, mine: MovieEntity?, busy: Boolean, onOpen: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Poster(TmdbApi.poster(m.posterPath, "w185"), Modifier.width(70.dp).height(104.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                m.voteAverage?.takeIf { it > 0 }?.let {
                    Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text(" %.1f  ".format(it), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Text(year(m.releaseDate).orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val g = m.genreNames(greek)
            if (g.isNotEmpty()) Text(g.take(3).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            m.overview?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(28.dp).padding(4.dp), strokeWidth = 2.dp)
            mine != null -> FilledTonalIconButton(onClick = onOpen) {
                Icon(if (mine.status == MovieStatus.WATCHED) Icons.Filled.Check else Icons.Filled.Schedule, null)
            }
            else -> FilledTonalIconButton(onClick = onAdd) { Icon(Icons.Filled.Add, stringResource(R.string.mv_add_plan)) }
        }
    }
}

@Composable
private fun EmptyStateSmall(title: String, text: String, onRetry: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp).clickable(onClick = onRetry),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.retry), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TmdbAttribution() {
    Text(
        stringResource(R.string.tmdb_attribution),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Suppress("unused")
private fun daysUntil(date: LocalDate) = ChronoUnit.DAYS.between(LocalDate.now(), date)
