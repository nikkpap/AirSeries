package gr.nikkpap.airseries.util

import android.util.LruCache
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * English → Greek translation that runs on the phone (Google ML Kit).
 * The dictionary (~30 MB) downloads once on first use; after that it works offline.
 */
object GreekTranslator {
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.GREEK)
                .build()
        )
    }
    private val cache = LruCache<String, String>(300)
    private val lock = Mutex()
    @Volatile private var ready = false

    suspend fun isModelDownloaded(): Boolean = ready || runCatching {
        RemoteModelManager.getInstance()
            .isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.GREEK).build())
            .await()
    }.getOrDefault(false)

    private suspend fun ensureModel() = lock.withLock {
        if (!ready) {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            ready = true
        }
    }

    suspend fun translate(text: String): String {
        if (text.isBlank()) return text
        cache.get(text)?.let { return it }
        ensureModel()
        val out = text.split('\n').joinToString("\n") { line ->
            if (line.isBlank()) line else translator.translate(line).await()
        }
        cache.put(text, out)
        return out
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
