package gr.nikkpap.airseries.ui.movies

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import gr.nikkpap.airseries.AppContainer
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.data.genreNames
import gr.nikkpap.airseries.data.local.MovieEntity
import gr.nikkpap.airseries.data.local.MovieStatus
import gr.nikkpap.airseries.data.remote.TmdbApi
import gr.nikkpap.airseries.data.remote.TmdbMovie
import gr.nikkpap.airseries.data.remote.TmdbProvider
import gr.nikkpap.airseries.data.trailerKey
import gr.nikkpap.airseries.ui.components.EmptyState
import gr.nikkpap.airseries.ui.components.Poster
import gr.nikkpap.airseries.ui.components.appViewModel
import gr.nikkpap.airseries.ui.detail.MyOpinionCard
import gr.nikkpap.airseries.ui.detail.SummaryBlock
import gr.nikkpap.airseries.util.formatDate
import gr.nikkpap.airseries.util.openUrl
import gr.nikkpap.airseries.util.year
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class MovieDetailViewModel(c: AppContainer, private val id: Int) : ViewModel() {
    private val repo = c.movies
    val greek: Boolean get() = repo.isGreek
    val autoTranslate = c.prefs.autoTranslate
    val details = MutableStateFlow<TmdbMovie?>(null)
    val loading = MutableStateFlow(true)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val mine: StateFlow<MovieEntity?> = repo.movieFlow(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { load() }

    fun load() = viewModelScope.launch {
        loading.value = true
        error.value = null
        try {
            details.value = repo.details(id)
        } catch (e: Exception) {
            error.value = e.message ?: "error"
        }
        loading.value = false
    }

    fun setStatus(status: Int) = viewModelScope.launch {
        busy.value = true
        runCatching { if (mine.value == null) repo.add(id, status) else repo.setStatus(id, status) }
        busy.value = false
    }

    fun remove() = viewModelScope.launch { runCatching { repo.remove(id) } }
    fun setRating(r: Int) = viewModelScope.launch { runCatching { repo.setRating(id, r) } }
    fun setNote(n: String) = viewModelScope.launch { runCatching { repo.setNote(id, n) } }
}

@Composable
fun MovieDetailScreen(movieId: Int, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val vm = appViewModel(key = "movie_$movieId") { MovieDetailViewModel(it, movieId) }
    val d by vm.details.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val mine by vm.mine.collectAsStateWithLifecycle()
    val autoTr by vm.autoTranslate.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var confirmRemove by remember { mutableStateOf(false) }
    var translateOn by rememberSaveable(movieId) { mutableStateOf(false) }

    val m = d
    if (m == null) {
        Box(Modifier.fillMaxSize()) {
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else EmptyState(Icons.Filled.WifiOff, stringResource(R.string.error_network), error.orEmpty(), stringResource(R.string.retry)) { vm.load() }
            BackBtn(onBack, Modifier.statusBarsPadding().padding(8.dp))
        }
        return
    }

    val greek = vm.greek
    val gr = m.providers?.results?.get("GR")
    val grCinema = m.releaseDates?.results?.firstOrNull { it.country == "GR" }?.dates?.filter { it.type == 3 || it.type == 2 }?.minOfOrNull { it.date.take(10) }
    val grOnline = m.releaseDates?.results?.firstOrNull { it.country == "GR" }?.dates?.filter { it.type == 4 }?.minOfOrNull { it.date.take(10) }
    val trailer = m.trailerKey()
    val director = m.credits?.crew?.firstOrNull { it.job == "Director" }?.name

    LazyColumn(Modifier.fillMaxSize()) {
        // ---- header ----
        item(key = "header") {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                AsyncImage(
                    model = TmdbApi.poster(m.backdropPath ?: m.posterPath, "w780"), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(6.dp),
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), MaterialTheme.colorScheme.background))))
                Row(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.Bottom) {
                    Poster(TmdbApi.poster(m.posterPath), Modifier.width(120.dp).height(178.dp), corner = 12.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(m.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (!m.originalTitle.isNullOrBlank() && m.originalTitle != m.title) {
                            Text(m.originalTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            listOfNotNull(year(m.releaseDate), m.runtime?.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}′" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        m.voteAverage?.takeIf { it > 0 }?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                                Text(" %.1f".format(it) + (m.voteCount?.let { c -> "  ($c)" } ?: ""), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                BackBtn(onBack, Modifier.statusBarsPadding().padding(8.dp))
            }
        }

        // ---- genres + actions ----
        item(key = "actions") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val genres = m.genreNames(greek)
                if (genres.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        genres.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                }
                m.tagline?.takeIf { it.isNotBlank() }?.let {
                    Text("“$it”", fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                }
                val current = mine
                if (current == null) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.setStatus(MovieStatus.PLAN) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.mv_add_plan))
                        }
                        OutlinedButton(onClick = { vm.setStatus(MovieStatus.WATCHED) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Check, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.mv_mark_watched))
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = current.status == MovieStatus.PLAN, onClick = { vm.setStatus(MovieStatus.PLAN) },
                            label = { Text(stringResource(R.string.mv_plan)) },
                        )
                        FilterChip(
                            selected = current.status == MovieStatus.WATCHED, onClick = { vm.setStatus(MovieStatus.WATCHED) },
                            label = { Text(stringResource(R.string.mv_watched)) },
                            leadingIcon = if (current.status == MovieStatus.WATCHED) { { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) } } else null,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.remove)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (trailer != null) {
                        TextButton(onClick = { openUrl(ctx, "https://www.youtube.com/watch?v=$trailer") }) {
                            Icon(Icons.Filled.SmartDisplay, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.trailer))
                        }
                    }
                    TextButton(onClick = { shareMovie(ctx, m) }) {
                        Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.share))
                    }
                    IconButton(onClick = {
                        openUrl(ctx, m.imdbId?.let { "https://www.imdb.com/title/$it/" } ?: "https://www.themoviedb.org/movie/${m.id}")
                    }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "IMDb / TMDB") }
                }
            }
        }

        // ---- release in Greece ----
        if (grCinema != null || grOnline != null) item(key = "release") {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.mv_release_gr), fontWeight = FontWeight.SemiBold)
                    grCinema?.let { Text("🎬 " + stringResource(R.string.mv_cinema, dateText(ctx, it))) }
                    grOnline?.let { Text("📺 " + stringResource(R.string.mv_online, dateText(ctx, it))) }
                    val future = listOfNotNull(grCinema, grOnline).any { isFuture(it) }
                    if (future && mine?.status != MovieStatus.WATCHED) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.NotificationsActive, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(if (mine != null) R.string.mv_will_notify else R.string.mv_add_to_notify),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }

        // ---- where to watch ----
        item(key = "providers") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.mv_where), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val groups = listOf(
                    R.string.mv_stream to gr?.flatrate.orEmpty(),
                    R.string.mv_free to (gr?.free.orEmpty() + gr?.ads.orEmpty()),
                    R.string.mv_rent to gr?.rent.orEmpty(),
                    R.string.mv_buy to gr?.buy.orEmpty(),
                ).filter { it.second.isNotEmpty() }
                if (groups.isEmpty()) {
                    Text(stringResource(R.string.mv_not_online), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    groups.forEach { (label, list) -> ProviderLine(stringResource(label), list.distinctBy { it.id }) }
                }
                gr?.link?.let { link ->
                    TextButton(onClick = { openUrl(ctx, link) }) { Text(stringResource(R.string.mv_all_options)) }
                }
            }
        }

        // ---- my opinion ----
        mine?.let { my ->
            item(key = "opinion") { MyOpinionCard(my.myRating, my.note, onRate = vm::setRating, onSaveNote = vm::setNote) }
        }

        // ---- overview ----
        item(key = "overview") {
            val ov = m.overview.orEmpty()
            if (ov.isNotBlank()) {
                val isGreekText = ov.any { it in '\u0370'..'\u03FF' }
                SummaryBlock(ov, (translateOn || autoTr) && !isGreekText, greek && !isGreekText) { translateOn = !translateOn }
            }
            director?.let {
                Text(
                    stringResource(R.string.mv_director, it), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // ---- cast ----
        val cast = m.credits?.cast.orEmpty().take(16)
        if (cast.isNotEmpty()) item(key = "cast") {
            Column {
                Text(
                    stringResource(R.string.cast), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cast, key = { it.id }) { p ->
                        Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Poster(TmdbApi.poster(p.profilePath, "w185"), Modifier.size(72.dp), corner = 36.dp)
                            Text(p.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                            p.character?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }

        // ---- recommendations ----
        val recs = m.recommendations?.results.orEmpty().filter { it.posterPath != null }.take(20)
        if (recs.isNotEmpty()) item(key = "recs") {
            Shelf(stringResource(R.string.mv_similar), recs.map { it.toCard() }, emptyMap(), onOpen)
        }

        item(key = "tmdb") { Box(Modifier.navigationBarsPadding()) { TmdbAttribution() } }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.mv_remove_title)) },
            text = { Text(m.title) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.remove() }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ProviderLine(label: String, list: List<TmdbProvider>) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            list.forEach { p ->
                Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Poster(TmdbApi.poster(p.logoPath, "w92"), Modifier.size(48.dp), corner = 12.dp)
                    Text(p.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun BackBtn(onBack: () -> Unit, modifier: Modifier) {
    IconButton(
        onClick = onBack, modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White),
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
}

private fun isFuture(d: String) = runCatching { LocalDate.parse(d.take(10)).isAfter(LocalDate.now()) }.getOrDefault(false)

private fun dateText(ctx: android.content.Context, d: String): String = runCatching {
    formatDate(ctx, LocalDate.parse(d.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
}.getOrDefault(d)

private fun shareMovie(ctx: android.content.Context, m: TmdbMovie) {
    val text = buildString {
        append("🎬 ").append(m.title)
        year(m.releaseDate)?.let { append(" (").append(it).append(")") }
        m.voteAverage?.takeIf { it > 0 }?.let { append("\n⭐ %.1f".format(it)) }
        m.overview?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(if (it.length > 240) it.take(240).trimEnd() + "…" else it) }
        append("\n\nhttps://www.themoviedb.org/movie/").append(m.id)
        append("\n\n").append(ctx.getString(R.string.shared_via))
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, m.title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.share_with)))
}
