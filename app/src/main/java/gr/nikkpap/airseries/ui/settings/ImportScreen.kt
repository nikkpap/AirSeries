package gr.nikkpap.airseries.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.data.Importer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Importer.state.collectAsStateWithLifecycle()
    var names by rememberSaveable { mutableStateOf("") }

    fun startFrom(bytes: ByteArray?) {
        val items = bytes?.let { runCatching { Importer.parseFile(it) }.getOrDefault(emptyList()) }.orEmpty()
        if (items.isEmpty()) Toast.makeText(ctx, R.string.imp_nothing, Toast.LENGTH_LONG).show()
        else Importer.start(ctx, items)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            startFrom(bytes)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.imp_title), fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- progress / result ----
            if (st.running || st.finished) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            stringResource(if (st.running) R.string.imp_running else R.string.imp_done),
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (st.total == 0) 0f else st.done.toFloat() / st.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("${st.done}/${st.total}" + if (st.current.isNotEmpty()) " · ${st.current}" else "", style = MaterialTheme.typography.bodySmall)
                        if (st.finished) {
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.imp_result, st.added, st.episodes))
                            if (st.notFound.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.imp_not_found, st.notFound.joinToString(", ")),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                )
                            }
                            OutlinedButton(onClick = { Importer.reset() }, modifier = Modifier.padding(top = 6.dp)) { Text(stringResource(R.string.ok)) }
                        }
                    }
                }
            }

            // ---- Trakt ----
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.imp_trakt_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.imp_trakt_steps), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { picker.launch(arrayOf("application/zip", "application/json", "text/plain", "*/*")) }, enabled = !st.running) {
                        Icon(Icons.Filled.FileOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.imp_pick_file))
                    }
                }
            }

            // ---- names list (TV Time & others) ----
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.imp_names_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.imp_names_steps), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = names,
                        onValueChange = { names = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Breaking Bad\nThe Office\nΤο Νησί") },
                        minLines = 5,
                        maxLines = 12,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { Importer.start(ctx, Importer.parseNames(names)) },
                            enabled = !st.running && names.isNotBlank(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.imp_start))
                        }
                        OutlinedButton(onClick = { picker.launch(arrayOf("text/plain", "text/csv", "text/comma-separated-values", "*/*")) }, enabled = !st.running) {
                            Text(stringResource(R.string.imp_pick_file))
                        }
                    }
                }
            }
            Text(stringResource(R.string.imp_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
