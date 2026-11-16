package gr.nikkpap.airseries

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gr.nikkpap.airseries.ui.AppNav
import gr.nikkpap.airseries.ui.theme.AirSeriesTheme
import gr.nikkpap.airseries.update.UpdateDialog
import gr.nikkpap.airseries.work.Notifier
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : AppCompatActivity() {

    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val prefs = container.prefs
        setContent {
            val theme by prefs.themeMode.collectAsStateWithLifecycle()
            val dynamic by prefs.dynamicColor.collectAsStateWithLifecycle()
            val dark = when (theme) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    // Solid-ish bar behind the phone's 3 buttons so they never sit on top of content.
                    navigationBarStyle = SystemBarStyle.auto(Color.argb(0xF2, 0xF8, 0xF8, 0xFC), Color.argb(0xF2, 0x0E, 0x0F, 0x1A)) { dark },
                )
                onDispose { }
            }
            AirSeriesTheme(dark = dark, dynamic = dynamic) {
                RequestNotificationPermission()
                AppNav(pendingRoute)
                UpdateDialog()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val show = i?.getIntExtra(Notifier.EXTRA_SHOW_ID, -1) ?: -1
        val movie = i?.getIntExtra(Notifier.EXTRA_MOVIE_ID, -1) ?: -1
        if (show > 0) pendingRoute.value = "show/$show"
        if (movie > 0) pendingRoute.value = "movie/$movie"
        i?.removeExtra(Notifier.EXTRA_SHOW_ID)
        i?.removeExtra(Notifier.EXTRA_MOVIE_ID)
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
