package gr.nikkpap.airseries.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.data.remote.CastDto
import gr.nikkpap.airseries.data.remote.PersonDto
import gr.nikkpap.airseries.data.remote.ShowDto
import gr.nikkpap.airseries.ui.components.Poster
import gr.nikkpap.airseries.util.GreekTranslator
import gr.nikkpap.airseries.util.year

/** Your own 1–5 star rating and a private note. Tap the same star again to clear. */
@Composable
fun MyOpinionCard(rating: Int, note: String?, onRate: (Int) -> Unit, onSaveNote: (String) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
            Text(stringResource(R.string.my_opinion), fontWeight = FontWeight.SemiBold)
            // Five equal star slots — the label goes on its own line underneath, so it never wraps.
            Row(Modifier.fillMaxWidth()) {
                (1..5).forEach { i ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { onRate(if (rating == i) 0 else i) }) {
                            Icon(
                                if (i <= rating) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = "$i",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }
            }
            Text(
                if (rating > 0) stringResource(ratingLabel(rating)) else stringResource(R.string.rate_hint),
                style = MaterialTheme.typography.labelLarge,
                color = if (rating > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            var text by rememberSaveable(note) { mutableStateOf(note.orEmpty()) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.note_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6,
            )
            if (text.trim() != note.orEmpty()) {
                TextButton(onClick = { onSaveNote(text) }, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

private fun ratingLabel(r: Int) = when (r) {
    1 -> R.string.rate_1
    2 -> R.string.rate_2
    3 -> R.string.rate_3
    4 -> R.string.rate_4
    else -> R.string.rate_5
}

/** Show description with optional on-device Greek translation. */
@Composable
fun SummaryBlock(summary: String, translate: Boolean, showButton: Boolean, onToggleTranslate: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var translated by remember(summary) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(summary, translate) {
        if (translate && translated == null && summary.isNotEmpty()) {
            busy = true
            failed = false
            translated = runCatching { GreekTranslator.translate(summary) }.onFailure { failed = true }.getOrNull()
            busy = false
        }
    }
    Column {
        val shown = if (translate) translated ?: summary else summary
        Text(
            shown,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (open) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.animateContentSize()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        if (showButton) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggleTranslate) {
                    Icon(Icons.Filled.Translate, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (translate) R.string.tr_original else R.string.tr_to_greek))
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tr_working), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (failed) {
                Text(
                    stringResource(R.string.tr_failed),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
fun CastRow(cast: List<CastDto>, onPerson: (PersonDto) -> Unit) {
    if (cast.isEmpty()) return
    Column {
        Text(
            stringResource(R.string.cast),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cast, key = { it.person.id }) { c ->
                Column(
                    Modifier.width(84.dp).clickable { onPerson(c.person) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Poster(c.person.image?.medium, Modifier.size(72.dp), corner = 36.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        c.person.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                    c.character?.name?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** Bottom sheet with every show an actor appears in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonSheet(person: PersonDto, shows: List<ShowDto>?, currentShowId: Int, onDismiss: () -> Unit, onOpen: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Poster(person.image?.medium, Modifier.size(64.dp), corner = 32.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(person.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.also_in), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (shows == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    items(shows.filter { it.id != currentShowId }, key = { it.id }) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(s.id) }.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Poster(s.image?.medium, Modifier.width(48.dp).height(70.dp), corner = 8.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(year(s.premiered), s.network?.name ?: s.webChannel?.name, s.rating?.average?.let { "★ %.1f".format(it) })
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Streaming services we can deep-link to by search (others go to JustWatch Greece). */
private val streamingSearch = mapOf(
    "netflix" to "https://www.netflix.com/search?q=",
    "prime video" to "https://www.primevideo.com/search?phrase=",
    "amazon prime video" to "https://www.primevideo.com/search?phrase=",
    "apple tv+" to "https://tv.apple.com/search?term=",
    "apple tv" to "https://tv.apple.com/search?term=",
)

fun justWatchUrl(name: String) =
    "https://www.google.com/search?q=" + java.net.URLEncoder.encode("site:justwatch.com/gr $name", "UTF-8")

fun platformUrl(name: String, network: String): String {
    val direct = streamingSearch[network.lowercase()]
    return direct?.let { it + java.net.URLEncoder.encode(name, "UTF-8") }
        ?: ("https://www.google.com/search?q=" + java.net.URLEncoder.encode("$name $network", "UTF-8"))
}

/** "Where to watch": JustWatch Greece + the original platform. */
@Composable
fun WhereToWatchRow(name: String, network: String?, open: (String) -> Unit) {
    val q = java.net.URLEncoder.encode(name, "UTF-8")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.where_to_watch), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        AssistChip(
            onClick = { open("https://www.google.com/search?q=" + java.net.URLEncoder.encode("site:justwatch.com/gr $name", "UTF-8")) },
            label = { Text("JustWatch 🇬🇷") },
            leadingIcon = { Icon(Icons.Filled.Tv, null, Modifier.size(16.dp)) },
        )
        if (!network.isNullOrBlank()) {
            val direct = streamingSearch[network.lowercase()]
            AssistChip(
                onClick = { open(direct?.let { it + q } ?: ("https://www.google.com/search?q=" + java.net.URLEncoder.encode("$name $network", "UTF-8"))) },
                label = { Text(network) },
            )
        }
    }
}
