package gr.nikkpap.airseries.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.data.trakt.Trakt
import gr.nikkpap.airseries.util.formatDateTime
import gr.nikkpap.airseries.util.openUrl
import kotlinx.coroutines.launch

/** Settings → Trakt: connect, auto-sync, download / upload, disconnect. */
@Composable
fun TraktSection(onShowProgress: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Trakt.status.collectAsStateWithLifecycle()
    var showLogin by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(Trakt.autoSync(ctx)) }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(st.message) {
        when (val m = st.message) {
            null -> Unit
            "ok" -> Toast.makeText(ctx, R.string.trakt_upload_done, Toast.LENGTH_LONG).show()
            else -> Toast.makeText(ctx, ctx.getString(R.string.trakt_error, m), Toast.LENGTH_LONG).show()
        }
    }

    if (!Trakt.configured) {
        ListItem(
            leadingContent = { Icon(Icons.Filled.SyncAlt, null) },
            headlineContent = { Text("Trakt") },
            supportingContent = { Text(stringResource(R.string.trakt_not_configured)) },
        )
        return
    }

    if (!st.connected) {
        ListItem(
            leadingContent = { Icon(Icons.Filled.Link, null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(stringResource(R.string.trakt_connect), fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(stringResource(R.string.trakt_connect_desc)) },
            modifier = Modifier.clickable { showLogin = true },
        )
    } else {
        ListItem(
            leadingContent = { Icon(Icons.Filled.SyncAlt, null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(stringResource(R.string.trakt_connected_as, st.user ?: "Trakt"), fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                val last = Trakt.lastPull(ctx)
                Text(if (last > 0) stringResource(R.string.last_sync, formatDateTime(ctx, last)) else stringResource(R.string.never_synced))
            },
            trailingContent = { if (st.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.trakt_auto)) },
            supportingContent = { Text(stringResource(R.string.trakt_auto_desc)) },
            trailingContent = { Switch(checked = auto, onCheckedChange = { auto = it; Trakt.setAutoSync(ctx, it) }) },
            modifier = Modifier.clickable { auto = !auto; Trakt.setAutoSync(ctx, auto) },
        )
        ListItem(
            leadingContent = { Icon(Icons.Filled.CloudDownload, null) },
            headlineContent = { Text(stringResource(R.string.trakt_download)) },
            supportingContent = { Text(stringResource(R.string.trakt_download_desc)) },
            modifier = Modifier.clickable(enabled = !st.busy) { Trakt.pullInBackground(ctx); onShowProgress() },
        )
        ListItem(
            leadingContent = { Icon(Icons.Filled.CloudUpload, null) },
            headlineContent = { Text(stringResource(R.string.trakt_upload)) },
            supportingContent = { Text(stringResource(R.string.trakt_upload_desc)) },
            modifier = Modifier.clickable(enabled = !st.busy) { Trakt.pushAllInBackground(ctx) },
        )
        ListItem(
            leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
            headlineContent = { Text(stringResource(R.string.trakt_logout)) },
            modifier = Modifier.clickable { confirmLogout = true },
        )
    }

    if (showLogin) TraktLoginDialog(
        onDone = { ok ->
            showLogin = false
            if (ok) {
                Toast.makeText(ctx, R.string.trakt_connected, Toast.LENGTH_LONG).show()
                Trakt.pullInBackground(ctx)
                onShowProgress()
            }
        },
    )

    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text(stringResource(R.string.trakt_logout)) },
        text = { Text(stringResource(R.string.trakt_logout_text)) },
        confirmButton = { TextButton(onClick = { confirmLogout = false; scope.launch { Trakt.logout(ctx) } }) { Text(stringResource(R.string.trakt_logout)) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Shows the short code, opens trakt.tv/activate and waits for approval. */
@Composable
private fun TraktLoginDialog(onDone: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf<Trakt.DeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val dc = Trakt.startLogin()
            code = dc
            copy(ctx, dc.userCode)
            val err = Trakt.waitForApproval(ctx, dc)
            if (err == null) onDone(true) else error = err
        } catch (e: Exception) {
            error = e.message ?: "error"
        }
    }

    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(stringResource(R.string.trakt_connect)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val dc = code
                when {
                    error != null -> Text(stringResource(R.string.trakt_error, error.orEmpty()), color = MaterialTheme.colorScheme.error)
                    dc == null -> CircularProgressIndicator()
                    else -> {
                        Text(stringResource(R.string.trakt_step1), textAlign = TextAlign.Center)
                        SelectionContainer {
                            Text(
                                dc.userCode, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary, letterSpacing = 4.sp,
                            )
                        }
                        Text(stringResource(R.string.trakt_copied), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { openUrl(ctx, dc.url) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.trakt_open_activate))
                        }
                        Text(stringResource(R.string.trakt_step2), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.trakt_waiting), style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(false) }) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun copy(ctx: Context, text: String) {
    runCatching {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Trakt code", text))
    }
}
