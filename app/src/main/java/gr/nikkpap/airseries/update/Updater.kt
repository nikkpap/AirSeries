package gr.nikkpap.airseries.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import gr.nikkpap.airseries.BuildConfig
import gr.nikkpap.airseries.data.remote.TvMazeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class GhAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tag: String = "",
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

data class UpdateInfo(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String, val size: Long)

sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val info: UpdateInfo) : UpdateUi
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateUi
    data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateUi
    data class Error(val message: String) : UpdateUi
}

/**
 * OTA updates from GitHub Releases (public repo, no token needed).
 * Release tags look like "v1.0.<versionCode>" and carry an .apk asset.
 */
object Updater {
    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val state: StateFlow<UpdateUi> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun parseCode(tag: String): Int? = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull()

    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AirSeries-Android/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("GitHub HTTP ${r.code}")
            val text = r.body?.string().orEmpty()
            val rel = TvMazeApi.json.decodeFromString(GhRelease.serializer(), text)
            val code = parseCode(rel.tag) ?: return@use null
            val apk = rel.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@use null
            UpdateInfo(code, rel.tag.removePrefix("v"), rel.body.orEmpty().trim(), apk.url, apk.size)
        }
    }

    /** Silent check returns the new version (if any) without touching the UI state. */
    suspend fun newerVersion(): UpdateInfo? =
        runCatching { fetchLatest() }.getOrNull()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    suspend fun check(userInitiated: Boolean) {
        if (_state.value is UpdateUi.Downloading) return
        if (userInitiated) _state.value = UpdateUi.Checking
        try {
            val info = fetchLatest()
            _state.value = when {
                info != null && info.versionCode > BuildConfig.VERSION_CODE -> UpdateUi.Available(info)
                userInitiated -> UpdateUi.UpToDate
                else -> UpdateUi.Idle
            }
        } catch (e: Exception) {
            _state.value = if (userInitiated) UpdateUi.Error(e.message ?: e.javaClass.simpleName) else UpdateUi.Idle
        }
    }

    suspend fun download(ctx: Context, info: UpdateInfo) {
        _state.value = UpdateUi.Downloading(info, 0f)
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "AirSeries-${info.versionName}.apk")
                val req = Request.Builder().url(info.apkUrl).header("User-Agent", "AirSeries-Android").build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    val body = r.body ?: throw IllegalStateException("empty body")
                    val total = body.contentLength().takeIf { it > 0 } ?: info.size
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastEmit = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0 && done - lastEmit > 256 * 1024) {
                                    lastEmit = done
                                    _state.value = UpdateUi.Downloading(info, (done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }
                out
            }
            _state.value = UpdateUi.ReadyToInstall(info, file)
            install(ctx, file)
        } catch (e: Exception) {
            _state.value = UpdateUi.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Opens the system installer, or first the "Install unknown apps" permission screen. */
    fun install(ctx: Context, file: File) {
        if (!canInstall(ctx)) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun dismiss() {
        if (_state.value !is UpdateUi.Downloading) _state.value = UpdateUi.Idle
    }
}
