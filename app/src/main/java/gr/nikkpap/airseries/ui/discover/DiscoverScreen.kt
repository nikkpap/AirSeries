package gr.nikkpap.airseries.ui.discover

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Star
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
import gr.nikkpap.airseries.data.Recommendation
import gr.nikkpap.airseries.data.local.DismissedEntity
import gr.nikkpap.airseries.data.local.splitGenres
import gr.nikkpap.airseries.data.remote.ShowDto
import gr.nikkpap.airseries.ui.components.EmptyState
import gr.nikkpap.airseries.ui.components.Poster
import gr.nikkpap.airseries.ui.components.appViewModel
import gr.nikkpap.airseries.util.stripHtml
import gr.nikkpap.airseries.util.year
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row in search results or recommendations. */
data class ShowCardData(
    val id: Int,
    val name: String,
    val image: String?,
    val year: String?,
    val genres: List<String>,
    val rating: Double?,
    val network: String?,
    val summary: String,
    val because: String? = null,
    val match: Int = 0,
)

data class SearchState(val active: Boolean = false, val loading: Boolean = false, val results: List<ShowCardData> = emptyList(), val error: String? = null)

private fun ShowDto.toCard() = ShowCardData(
    id, name, image?.medium, year(premiered), genres, rating?.average, network?.name ?: webChannel?.name, stripHtml(summary),
)

private fun Recommendation.toCard() = ShowCardData(
    c.id, c.name, c.image, year(c.premiered), splitGenres(c.genres), c.rating, c.network, stripHtml(c.summary), becauseOf, match,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DiscoverViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repo
    private val dao = c.db.dao()

    val query = MutableStateFlow("")
    val favoriteIds: StateFlow<Set<Int>> = repo.favoriteIds.map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val busyIds = MutableStateFlow<Set<Int>>(emptySet())
    val refreshing = MutableStateFlow(false)
    val hasFavorites: StateFlow<Boolean> = repo.favoriteIds.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val search: StateFlow<SearchState> = query
        .debounce(400)
        .map { it.trim() }
        .distinctUntilChanged()
        .transformLatest { q ->
            if (q.length < 2) {
                emit(SearchState())
            } else {
                emit(SearchState(active = true, loading = true))
                emit(
                    try {
                        SearchState(active = true, results = repo.search(q).map { it.toCard() })
                    } catch (e: Exception) {
                        SearchState(active = true, error = e.message ?: "error")
                    }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState())

    val recs: StateFlow<List<ShowCardData>> = combine(
        repo.shows, repo.allEpisodes, dao.candidatesFlow(), dao.dismissedFlow(),
    ) { shows, eps, cands, dismissed ->
        c.recommender.recommend(shows, eps.groupBy { it.showId }, cands, dismissed.toSet()).map { it.toCard() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        val stale = System.currentTimeMillis() - c.prefs.lastRecsRefresh > 7L * 24 * 3_600_000
        if (stale) refreshRecs()
    }

    fun refreshRecs() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            runCatching { c.recommender.refreshCandidates() }
            refreshing.value = false
        }
    }

    fun add(id: Int) = viewModelScope.launch {
        busyIds.update { it + id }
        runCatching { repo.addFavorite(id) }
        busyIds.update { it - id }
    }

    fun dismiss(id: Int) = viewModelScope.launch { dao.dismiss(DismissedEntity(id)) }
}

@Composable
fun DiscoverScreen(onOpen: (Int) -> Unit) {
    val vm = appViewModel { DiscoverViewModel(it) }
    val q by vm.query.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val recs by vm.recs.collectAsStateWithLifecycle()
    val favs by vm.favoriteIds.collectAsStateWithLifecycle()
    val busy by vm.busyIds.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val hasFavs by vm.hasFavorites.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = q,
            onValueChange = { vm.query.value = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (q.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, null) }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        if (search.active) {
            when {
                search.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                search.error != null -> EmptyState(Icons.Filled.SearchOff, stringResource(R.string.error_network), search.error.orEmpty())
                search.results.isEmpty() -> EmptyState(Icons.Filled.SearchOff, stringResource(R.string.search_none), q)
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(search.results, key = { it.id }) { s ->
                        ShowRow(s, s.id in favs, s.id in busy, onOpen = { focus.clearFocus(); onOpen(s.id) }, onAdd = { vm.add(s.id) }, onDismiss = null)
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (hasFavs) R.string.recs_for_you else R.string.recs_popular),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                )
                if (refreshing) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                IconButton(onClick = vm::refreshRecs, enabled = !refreshing) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
            }
            if (!hasFavs) {
                Text(
                    stringResource(R.string.recs_hint_no_favs),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (recs.isEmpty()) {
                if (refreshing) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.recs_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    EmptyState(Icons.Filled.AutoAwesome, stringResource(R.string.recs_empty), stringResource(R.string.recs_empty_text), stringResource(R.string.refresh), vm::refreshRecs)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(recs, key = { it.id }) { s ->
                        ShowRow(s, s.id in favs, s.id in busy, onOpen = { onOpen(s.id) }, onAdd = { vm.add(s.id) }, onDismiss = { vm.dismiss(s.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowRow(s: ShowCardData, isFav: Boolean, isBusy: Boolean, onOpen: () -> Unit, onAdd: () -> Unit, onDismiss: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(s.image, Modifier.width(70.dp).height(100.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(s.year, s.network).joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.rating != null) {
                    Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text(" %.1f  ".format(s.rating), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (s.genres.isNotEmpty()) {
                Text(s.genres.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            if (s.because != null) {
                Text(
                    stringResource(R.string.because_you_watch, s.because) + if (s.match > 0) "  •  ${s.match}%" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else if (s.summary.isNotEmpty()) {
                Text(s.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                isBusy -> CircularProgressIndicator(Modifier.size(28.dp).padding(4.dp), strokeWidth = 2.dp)
                isFav -> FilledTonalIconButton(onClick = onOpen) { Icon(Icons.Filled.Check, stringResource(R.string.in_favorites)) }
                else -> FilledTonalIconButton(onClick = onAdd) { Icon(Icons.Filled.Add, stringResource(R.string.add_favorite)) }
            }
            if (onDismiss != null && !isFav) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.not_interested), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
