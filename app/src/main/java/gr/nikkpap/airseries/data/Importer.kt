package gr.nikkpap.airseries.data

import android.content.Context
import gr.nikkpap.airseries.container
import gr.nikkpap.airseries.data.local.ListStatus
import gr.nikkpap.airseries.data.remote.ShowDto
import gr.nikkpap.airseries.data.remote.TvMazeApi
import gr.nikkpap.airseries.widget.WidgetUpdater
import gr.nikkpap.airseries.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

data class ImportItem(
    val title: String,
    val imdb: String? = null,
    val tvdb: Int? = null,
    val watched: Set<Pair<Int, Int>> = emptySet(),
    val listStatus: Int = ListStatus.WATCHING,
)

data class ImportState(
    val running: Boolean = false,
    val finished: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val added: Int = 0,
    val episodes: Int = 0,
    val notFound: List<String> = emptyList(),
    val error: String? = null,
)

/** Imports shows from a Trakt export (.zip / .json) or a plain list of names (TV Time, notes…). */
object Importer {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun reset() { if (!_state.value.running) _state.value = ImportState() }

    // ---------- parsing ----------
    fun parseFile(bytes: ByteArray): List<ImportItem> {
        val isZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (isZip) {
            val out = mutableListOf<ImportItem>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/').lowercase()
                    if (name == "watched-shows.json") out += parseTraktJson(zip.readBytes().decodeToString(), ListStatus.WATCHING)
                    if (name == "watchlist-shows.json") out += parseTraktJson(zip.readBytes().decodeToString(), ListStatus.PLAN)
                    entry = zip.nextEntry
                }
            }
            return merge(out)
        }
        val text = bytes.decodeToString().trimStart('﻿')
        val t = text.trimStart()
        if (t.startsWith("[")) {
            val status = if (text.contains("\"seasons\"")) ListStatus.WATCHING else ListStatus.PLAN
            return merge(parseTraktJson(text, status))
        }
        return parseNames(text)
    }

    private fun parseTraktJson(text: String, status: Int): List<ImportItem> {
        val root = runCatching { TvMazeApi.json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return root.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val show = o["show"]?.jsonObject ?: return@mapNotNull null
            val ids = show["ids"]?.jsonObject
            val watched = HashSet<Pair<Int, Int>>()
            o["seasons"]?.jsonArray?.forEach { s ->
                val so = s.jsonObject
                val sn = so["number"]?.jsonPrimitive?.intOrNull ?: return@forEach
                so["episodes"]?.jsonArray?.forEach { e ->
                    e.jsonObject["number"]?.jsonPrimitive?.intOrNull?.let { watched += sn to it }
                }
            }
            ImportItem(
                title = show["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                imdb = ids?.get("imdb")?.jsonPrimitive?.contentOrNull,
                tvdb = ids?.get("tvdb")?.jsonPrimitive?.intOrNull,
                watched = watched,
                listStatus = status,
            )
        }
    }

    /** One show name per line; for CSV files the first column is used. */
    fun parseNames(text: String): List<ImportItem> {
        val skip = setOf("name", "title", "tv_show_name", "show", "series", "show_name")
        return text.lines()
            .map { line -> line.split(',', ';', '\t').first().trim().trim('"').trim() }
            .filter { it.length >= 2 && it.lowercase() !in skip }
            .distinctBy { it.lowercase() }
            .map { ImportItem(title = it) }
    }

    private fun merge(items: List<ImportItem>): List<ImportItem> =
        items.groupBy { it.imdb ?: it.tvdb?.toString() ?: it.title.lowercase() }.values.map { group ->
            group.reduce { a, b ->
                a.copy(
                    watched = a.watched + b.watched,
                    listStatus = if (a.watched.isNotEmpty() || b.watched.isNotEmpty()) ListStatus.WATCHING else a.listStatus,
                )
            }
        }

    // ---------- running ----------
    fun start(ctx: Context, items: List<ImportItem>) {
        if (_state.value.running || items.isEmpty()) return
        val app = ctx.applicationContext
        val c = app.container
        _state.value = ImportState(running = true, total = items.size)
        scope.launch {
            for ((i, item) in items.withIndex()) {
                _state.update { it.copy(done = i, current = item.title) }
                try {
                    val dto: ShowDto? = item.imdb?.let { c.api.lookup(it, null).body() }
                        ?: item.tvdb?.let { c.api.lookup(null, it).body() }
                        ?: item.title.takeIf { it.isNotBlank() }?.let { c.api.singleSearch(it).body() }
                    if (dto == null) {
                        _state.update { it.copy(notFound = it.notFound + item.title) }
                    } else {
                        val isNew = c.repo.getShow(dto.id) == null
                        if (isNew) c.repo.refreshShow(dto.id, isNewFavorite = true)
                        c.repo.markWatchedPairs(dto.id, item.watched)
                        if (isNew) c.db.dao().setListStatus(dto.id, item.listStatus)
                        _state.update { it.copy(added = it.added + if (isNew) 1 else 0, episodes = it.episodes + item.watched.size) }
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(notFound = it.notFound + item.title) }
                }
                delay(450) // be gentle with the free TVmaze API
            }
            runCatching { Scheduler.rescheduleAll(app) }
            WidgetUpdater.refresh(app)
            _state.update { it.copy(running = false, finished = true, done = items.size, current = "") }
        }
    }
}
