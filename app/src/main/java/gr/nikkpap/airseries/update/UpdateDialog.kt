package gr.nikkpap.airseries.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gr.nikkpap.airseries.BuildConfig
import gr.nikkpap.airseries.R
import kotlinx.coroutines.launch

/** Global update dialog: shown on top of every screen when an update is found. */
@Composable
fun UpdateDialog() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsStateWithLifecycle()

    // Silent check every time the app opens.
    LaunchedEffect(Unit) { Updater.check(userInitiated = false) }

    when (val s = st) {
        is UpdateUi.Available -> AlertDialog(
            onDismissRequest = Updater::dismiss,
            icon = { Icon(Icons.Filled.SystemUpdate, null) },
            title = { Text(stringResource(R.string.upd_title, s.info.versionName)) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.upd_current, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelMedium)
                    if (s.info.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(s.info.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { Button(onClick = { scope.launch { Updater.download(ctx, s.info) } }) { Text(stringResource(R.string.upd_install)) } },
            dismissButton = { TextButton(onClick = Updater::dismiss) { Text(stringResource(R.string.upd_later)) } },
        )
        is UpdateUi.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.upd_downloading)) },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(s.progress * 100).toInt()}%")
                }
            },
            confirmButton = {},
        )
        is UpdateUi.ReadyToInstall -> AlertDialog(
            onDismissRequest = Updater::dismiss,
            title = { Text(stringResource(R.string.upd_ready)) },
            text = {
                Text(
                    stringResource(if (Updater.canInstall(ctx)) R.string.upd_ready_text else R.string.upd_permission_text)
                )
            },
            confirmButton = { Button(onClick = { Updater.install(ctx, s.file) }) { Text(stringResource(R.string.upd_install)) } },
            dismissButton = { TextButton(onClick = Updater::dismiss) { Text(stringResource(R.string.upd_later)) } },
        )
        is UpdateUi.UpToDate -> AlertDialog(
            onDismissRequest = Updater::dismiss,
            title = { Text(stringResource(R.string.upd_uptodate)) },
            text = { Text(stringResource(R.string.upd_current, BuildConfig.VERSION_NAME)) },
            confirmButton = { TextButton(onClick = Updater::dismiss) { Text(stringResource(R.string.ok)) } },
        )
        is UpdateUi.Error -> AlertDialog(
            onDismissRequest = Updater::dismiss,
            title = { Text(stringResource(R.string.upd_error)) },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = Updater::dismiss) { Text(stringResource(R.string.ok)) } },
        )
        else -> Unit
    }
}
