package gr.nikkpap.airseries.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gr.nikkpap.airseries.BuildConfig
import gr.nikkpap.airseries.R
import gr.nikkpap.airseries.container
import gr.nikkpap.airseries.util.Battery
import gr.nikkpap.airseries.util.formatDateTime
import gr.nikkpap.airseries.util.openUrl
import gr.nikkpap.airseries.work.Notifier
import gr.nikkpap.airseries.work.Scheduler
import gr.nikkpap.airseries.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val p = c.prefs
    val scope = rememberCoroutineScope()

    val notify by p.notifyEnabled.collectAsStateWithLifecycle()
    val offset by p.notifyOffset.collectAsStateWithLifecycle()
    val announce by p.notifyAnnouncements.collectAsStateWithLifecycle()
    val spoilers by p.spoilerProtection.collectAsStateWithLifecycle()
    val theme by p.themeMode.collectAsStateWithLifecycle()
    val dynamic by p.dynamicColor.collectAsStateWithLifecycle()
    val lastSync by p.lastSync.collectAsStateWithLifecycle()
    var working by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(Battery.isUnrestricted(ctx)) }
    LifecycleResumeEffect(Unit) {
        batteryOk = Battery.isUnrestricted(ctx)
        onPauseOrDispose { }
    }

    fun toast(res: Int, vararg args: Any) = Toast.makeText(ctx, ctx.getString(res, *args), Toast.LENGTH_LONG).show()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val json = c.repo.exportJson()
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
            }.onSuccess { toast(R.string.backup_done) }.onFailure { toast(R.string.backup_failed) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            working = true
            runCatching {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.orEmpty()
                c.repo.importJson(text)
            }.onSuccess { toast(R.string.restore_done, it) }.onFailure { toast(R.string.restore_failed) }
            working = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            // ---- notifications ----
            Header(R.string.set_notifications)
            SwitchRow(Icons.Filled.Notifications, R.string.set_notify, R.string.set_notify_desc, notify) {
                p.setNotifyEnabled(it)
                scope.launch { Scheduler.rescheduleAll(ctx) }
            }
            Text(stringResource(R.string.set_when), Modifier.padding(start = 16.dp, top = 4.dp), style = MaterialTheme.typography.labelLarge)
            ChipRow(
                options = listOf(0 to R.string.off_at_air, 15 to R.string.off_15, 60 to R.string.off_60, 180 to R.string.off_180),
                selected = offset,
            ) { v ->
                p.setNotifyOffset(v)
                scope.launch { Scheduler.rescheduleAll(ctx) }
            }
            SwitchRow(Icons.Filled.NotificationsActive, R.string.set_announce, R.string.set_announce_desc, announce) { p.setNotifyAnnouncements(it) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.set_test_notif)) },
                supportingContent = { Text(stringResource(R.string.set_test_notif_desc)) },
                modifier = Modifier.clickable { Notifier.test(ctx) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.set_system_notif)) },
                modifier = Modifier.clickable {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                },
            )
            HorizontalDivider()

            // ---- battery ----
            Header(R.string.bat_header)
            ListItem(
                leadingContent = {
                    Icon(
                        if (batteryOk) Icons.Filled.BatteryFull else Icons.Filled.BatteryAlert, null,
                        tint = if (batteryOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                },
                headlineContent = { Text(stringResource(if (batteryOk) R.string.bat_ok else R.string.bat_restricted)) },
                supportingContent = { Text(stringResource(if (batteryOk) R.string.bat_ok_desc else R.string.bat_restricted_desc)) },
                trailingContent = {
                    if (!batteryOk) Button(onClick = { Battery.requestUnrestricted(ctx) }) { Text(stringResource(R.string.bat_fix)) }
                },
                modifier = Modifier.clickable { if (batteryOk) Battery.openBatterySettings(ctx) else Battery.requestUnrestricted(ctx) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.bat_brand, Build.MANUFACTURER.replaceFirstChar { it.uppercase() })) },
                supportingContent = { Text(stringResource(R.string.bat_brand_desc)) },
                modifier = Modifier.clickable { Battery.openBrandGuide(ctx) },
            )
            HorizontalDivider()

            // ---- appearance ----
            Header(R.string.set_appearance)
            Text(stringResource(R.string.set_theme), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.labelLarge)
            ChipRow(listOf(0 to R.string.theme_system, 1 to R.string.theme_light, 2 to R.string.theme_dark), theme) { p.setThemeMode(it) }
            if (Build.VERSION.SDK_INT >= 31) {
                SwitchRow(null, R.string.set_dynamic, R.string.set_dynamic_desc, dynamic) { p.setDynamicColor(it) }
            }
            Text(stringResource(R.string.set_language), Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge)
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            val langSel = when {
                current.startsWith("el") -> 1
                current.startsWith("en") -> 2
                else -> 0
            }
            ChipRow(listOf(0 to R.string.lang_system, 1 to R.string.lang_el, 2 to R.string.lang_en), langSel) { v ->
                val tags = when (v) { 1 -> "el"; 2 -> "en"; else -> "" }
                AppCompatDelegate.setApplicationLocales(
                    if (tags.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tags)
                )
            }
            SwitchRow(Icons.Filled.VisibilityOff, R.string.set_spoilers, R.string.set_spoilers_desc, spoilers) { p.setSpoilerProtection(it) }
            HorizontalDivider()

            // ---- data ----
            Header(R.string.set_data)
            ListItem(
                leadingContent = { Icon(Icons.Filled.Sync, null) },
                headlineContent = { Text(stringResource(R.string.set_sync_now)) },
                supportingContent = {
                    Text(if (lastSync > 0) stringResource(R.string.last_sync, formatDateTime(ctx, lastSync)) else stringResource(R.string.never_synced))
                },
                modifier = Modifier.clickable { Scheduler.syncNow(ctx, force = true); toast(R.string.sync_started) },
            )
            ListItem(
                leadingContent = { Icon(Icons.Filled.Upload, null) },
                headlineContent = { Text(stringResource(R.string.set_backup)) },
                supportingContent = { Text(stringResource(R.string.set_backup_desc)) },
                modifier = Modifier.clickable { exportLauncher.launch("AirSeries-backup.json") },
            )
            ListItem(
                leadingContent = { Icon(Icons.Filled.Download, null) },
                headlineContent = { Text(stringResource(if (working) R.string.restoring else R.string.set_restore)) },
                supportingContent = { Text(stringResource(R.string.set_restore_desc)) },
                modifier = Modifier.clickable(enabled = !working) { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.set_reset_dismissed)) },
                supportingContent = { Text(stringResource(R.string.set_reset_dismissed_desc)) },
                modifier = Modifier.clickable { scope.launch { c.db.dao().clearDismissed(); toast(R.string.done) } },
            )
            HorizontalDivider()

            // ---- about ----
            Header(R.string.set_about)
            ListItem(
                leadingContent = { Icon(Icons.Filled.Info, null) },
                headlineContent = { Text("${stringResource(R.string.app_name)} v${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text(stringResource(R.string.about_text)) },
                modifier = Modifier.clickable(onClick = onAbout),
            )
            ListItem(
                leadingContent = { Icon(Icons.Filled.SystemUpdate, null) },
                headlineContent = { Text(stringResource(R.string.upd_check)) },
                supportingContent = { Text(stringResource(R.string.upd_check_desc)) },
                modifier = Modifier.clickable { scope.launch { Updater.check(userInitiated = true) } },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.data_by_tvmaze)) },
                supportingContent = { Text("tvmaze.com · CC BY-SA 4.0") },
                modifier = Modifier.clickable { openUrl(ctx, "https://www.tvmaze.com") },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Header(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(icon: ImageVector?, title: Int, desc: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        leadingContent = if (icon != null) { { Icon(icon, null) } } else null,
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(desc)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun ChipRow(options: List<Pair<Int, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(stringResource(label)) })
        }
    }
}
