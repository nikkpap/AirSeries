package gr.nikkpap.airseries.data.trakt

import android.content.Context
import gr.nikkpap.airseries.BuildConfig
import gr.nikkpap.airseries.container
import gr.nikkpap.airseries.data.ImportItem
import gr.nikkpap.airseries.data.Importer
import gr.nikkpap.airseries.data.MovieImportItem
import gr.nikkpap.airseries.data.local.EpisodeEntity
import gr.nikkpap.airseries.data.local.ListStatus
import gr.nikkpap.airseries.data.local.MovieStatus
import gr.nikkpap.airseries.data.local.ShowEntity
import gr.nikkpap.airseries.data.remote.TvMazeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Trakt.tv two-way sync.
 *  • Login: device code (enter a short code on trakt.tv/activate).
 *  • Access tokens are refreshed automatically (refresh tokens are single-use, the new one is saved each time).
 *  • Pull: watched + watchlist for shows and movies (paginated, 250 per page).
 *  • Push: every ✓ / un-✓ in AirSeries goes to your Trakt history (queued when offline).
 */
object Trakt {
    private const val BASE = "https://api.trakt.tv"
    private const val OOB = "urn:ietf:wg:oauth:2.0:oob"
    private const val APP_REDIRECT = "airseries://trakt"
    private val JSON = "application/json".toMediaType()

    /** Only the Client ID is needed: since Oct 2026 new Trakt apps are secret-less (PKCE / device code). */
    val configured: Boolean get() = BuildConfig.TRAKT_CLIENT_ID.isNotBlank()

    /** Older Trakt apps still have a secret — send it only if one was configured. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putSecret() {
        if (BuildConfig.TRAKT_CLIENT_SECRET.isNotBlank()) put("client_secret", BuildConfig.TRAKT_CLIENT_SECRET)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenLock = Mutex()

    data class Status(val connected: Boolean = false, val user: String? = null, val busy: Boolean = false, val message: String? = null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // ---------- stored state ----------
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("trakt", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        val p = sp(ctx)
        _status.value = Status(connected = p.getString("refresh", null) != null, user = p.getString("user", null))
    }

    fun isConnected(ctx: Context) = sp(ctx).getString("refresh", null) != null
    fun autoSync(ctx: Context) = sp(ctx).getBoolean("auto", true)
    fun setAutoSync(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto", v).apply()
    fun lastPull(ctx: Context) = sp(ctx).getLong("last_pull", 0L)

    // ---------- HTTP ----------
    private fun base(path: String) = Request.Builder()
        .url(BASE + path)
        .header("Content-Type", "application/json")
        .header("trakt-api-version", "2")
        .header("trakt-api-key", BuildConfig.TRAKT_CLIENT_ID)
        .header("User-Agent", "AirSeries/${BuildConfig.VERSION_NAME} (Android; github.com/nikkpap/AirSeries)")

    private data class Resp(val code: Int, val body: String, val pageCount: Int)

    private suspend fun call(req: Request): Resp = withContext(Dispatchers.IO) {
        var tries = 0
        while (true) {
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.code == 429 && tries < 3) {
                    tries++
                    val wait = r.header("Retry-After")?.toLongOrNull() ?: 2L
                    Thread.sleep(wait.coerceIn(1, 10) * 1000)
                } else {
                    return@withContext Resp(r.code, body, r.header("X-Pagination-Page-Count")?.toIntOrNull() ?: 1)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") Resp(0, "", 1)
    }

    private fun post(path: String, json: JsonElement) = base(path).post(json.toString().toRequestBody(JSON))

    private suspend fun authed(ctx: Context, b: Request.Builder): Resp {
        val token = accessToken(ctx) ?: throw IllegalStateException("not connected")
        return call(b.header("Authorization", "Bearer $token").build())
    }

    // ---------- login (device code) ----------
    data class DeviceCode(val userCode: String, val url: String, val deviceCode: String, val interval: Int, val expiresIn: Int)

    suspend fun startLogin(): DeviceCode {
        val r = call(post("/oauth/device/code", buildJsonObject { put("client_id", BuildConfig.TRAKT_CLIENT_ID) }).build())
        if (r.code !in 200..299) throw IllegalStateException("Trakt HTTP ${r.code}")
        val o = TvMazeApi.json.parseToJsonElement(r.body).jsonObject
        return DeviceCode(
            userCode = o["user_code"]!!.jsonPrimitive.content,
            url = o["verification_url"]?.jsonPrimitive?.contentOrNull ?: "https://trakt.tv/activate",
            deviceCode = o["device_code"]!!.jsonPrimitive.content,
            interval = o["interval"]?.jsonPrimitive?.intOrNull ?: 5,
            expiresIn = o["expires_in"]?.jsonPrimitive?.intOrNull ?: 600,
        )
    }

    /** Polls until the user approves. Returns null on success, or an error message. */
    suspend fun waitForApproval(ctx: Context, dc: DeviceCode): String? {
        var interval = dc.interval.coerceAtLeast(5)
        val until = System.currentTimeMillis() + dc.expiresIn * 1000L
        while (System.currentTimeMillis() < until) {
            delay(interval * 1000L)
            val r = call(post("/oauth/device/token", buildJsonObject {
                put("code", dc.deviceCode)
                put("client_id", BuildConfig.TRAKT_CLIENT_ID)
                putSecret()
            }).build())
            when (r.code) {
                200 -> {
                    saveTokens(ctx, TvMazeApi.json.parseToJsonElement(r.body).jsonObject)
                    fetchUser(ctx)
                    return null
                }
                400 -> Unit // still waiting
                429 -> interval += 1
                404 -> return "invalid code"
                409 -> return "code already used"
                410 -> return "expired"
                418 -> return "denied"
                else -> return "HTTP ${r.code}"
            }
        }
        return "expired"
    }

    private fun saveTokens(ctx: Context, o: JsonObject) {
        val created = o["created_at"]?.jsonPrimitive?.long ?: (System.currentTimeMillis() / 1000)
        val expires = o["expires_in"]?.jsonPrimitive?.long ?: 86_400
        sp(ctx).edit()
            .putString("access", o["access_token"]!!.jsonPrimitive.content)
            .putString("refresh", o["refresh_token"]!!.jsonPrimitive.content)
            .putLong("expires_at", (created + expires) * 1000)
            .apply()
        _status.value = _status.value.copy(connected = true)
    }

    private suspend fun fetchUser(ctx: Context) {
        runCatching {
            val r = authed(ctx, base("/users/settings").get())
            val user = TvMazeApi.json.parseToJsonElement(r.body).jsonObject["user"]?.jsonObject
            val name = user?.get("username")?.jsonPrimitive?.contentOrNull
            sp(ctx).edit().putString("user", name).apply()
            _status.value = _status.value.copy(user = name)
        }
    }

    /** A valid access token, refreshed when it is about to expire (Trakt tokens live 24 h). */
    suspend fun accessToken(ctx: Context): String? = tokenLock.withLock {
        val p = sp(ctx)
        val access = p.getString("access", null) ?: return null
        if (System.currentTimeMillis() < p.getLong("expires_at", 0L) - 10 * 60_000L) return access
        val refresh = p.getString("refresh", null) ?: return null
        // Device-code apps normally use the OOB redirect; newer Trakt apps may only have the custom scheme.
        var last = 0
        for (redirect in listOf(OOB, APP_REDIRECT)) {
            val r = call(post("/oauth/token", buildJsonObject {
                put("refresh_token", refresh)
                put("client_id", BuildConfig.TRAKT_CLIENT_ID)
                putSecret()
                put("redirect_uri", redirect)
                put("grant_type", "refresh_token")
            }).build())
            last = r.code
            if (r.code == 200) {
                saveTokens(ctx, TvMazeApi.json.parseToJsonElement(r.body).jsonObject)
                return p.getString("access", null)
            }
            if (r.code != 400 && r.code != 401) break
        }
        if (last == 400 || last == 401) { clear(ctx); return null } // refresh token revoked → log in again
        return access
    }

    suspend fun logout(ctx: Context) {
        val token = sp(ctx).getString("access", null)
        if (token != null) runCatching {
            call(post("/oauth/revoke", buildJsonObject {
                put("token", token)
                put("client_id", BuildConfig.TRAKT_CLIENT_ID)
                putSecret()
            }).build())
        }
        clear(ctx)
    }

    private fun clear(ctx: Context) {
        sp(ctx).edit().clear().apply()
        _status.value = Status()
    }

    // ---------- pull ----------
    private suspend fun allPages(ctx: Context, path: String): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        var page = 1
        while (page <= 40) {
            val sep = if (path.contains('?')) '&' else '?'
            val r = authed(ctx, base("$path${sep}page=$page&limit=250").get())
            if (r.code !in 200..299) throw IllegalStateException("Trakt HTTP ${r.code}")
            val arr = TvMazeApi.json.parseToJsonElement(r.body) as? JsonArray ?: break
            out.addAll(arr)
            if (arr.isEmpty() || page >= r.pageCount) break
            page++
        }
        return out
    }

    private fun showItems(els: List<JsonElement>, status: Int): List<ImportItem> =
        Importer.parseTraktJson(JsonArray(els).toString(), status)

    private fun movieItems(els: List<JsonElement>, status: Int): List<MovieImportItem> = els.mapNotNull { el ->
        val m = runCatching { el.jsonObject["movie"]?.jsonObject }.getOrNull() ?: return@mapNotNull null
        val tmdb = m["ids"]?.jsonObject?.get("tmdb")?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        MovieImportItem(tmdb, m["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), status)
    }

    /** Downloads everything from Trakt and merges it into AirSeries (never deletes local data). */
    suspend fun pull(ctx: Context, showProgress: Boolean) {
        val shows = showItems(allPages(ctx, "/sync/watched/shows?extended=progress"), ListStatus.WATCHING) +
            showItems(allPages(ctx, "/sync/watchlist/shows"), ListStatus.PLAN)
        val movies = if (ctx.container.movies.hasKey) {
            movieItems(allPages(ctx, "/sync/watched/movies"), MovieStatus.WATCHED) +
                movieItems(allPages(ctx, "/sync/watchlist/movies"), MovieStatus.PLAN)
        } else emptyList()
        val mergedShows = Importer.merge(shows)
        val mergedMovies = movies.groupBy { it.tmdb }.values.map { g -> g.firstOrNull { it.status == MovieStatus.WATCHED } ?: g.first() }
        if (showProgress) Importer.runVisible(ctx, mergedShows, mergedMovies) else Importer.execute(ctx, mergedShows, mergedMovies, report = false)
        sp(ctx).edit().putLong("last_pull", System.currentTimeMillis()).apply()
    }

    fun pullInBackground(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            _status.value = _status.value.copy(busy = true, message = null)
            val err = runCatching { pull(app, showProgress = true) }.exceptionOrNull()
            _status.value = _status.value.copy(busy = false, message = err?.message)
        }
    }

    /** Cheap check: only pull when something changed on Trakt since last time. */
    suspend fun autoPullIfChanged(ctx: Context) {
        if (!isConnected(ctx) || !autoSync(ctx)) return
        flushQueue(ctx)
        val r = authed(ctx, base("/sync/last_activities").get())
        if (r.code !in 200..299) return
        val o = TvMazeApi.json.parseToJsonElement(r.body).jsonObject
        fun at(a: String, b: String) = o[a]?.jsonObject?.get(b)?.jsonPrimitive?.contentOrNull.orEmpty()
        val sig = listOf(at("episodes", "watched_at"), at("shows", "watchlisted_at"), at("movies", "watched_at"), at("movies", "watchlisted_at"))
            .joinToString("|")
        if (sig == sp(ctx).getString("activity", null)) return
        pull(ctx, showProgress = false)
        sp(ctx).edit().putString("activity", sig).apply()
    }

    // ---------- push ----------
    private fun queue(ctx: Context): MutableList<String> =
        sp(ctx).getString("queue", null)?.split("\u0001")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()

    private fun saveQueue(ctx: Context, q: List<String>) = sp(ctx).edit().putString("queue", q.takeLast(300).joinToString("\u0001")).apply()

    /** Sends one sync request; if offline it is stored and retried on the next sync. */
    private fun send(ctx: Context, path: String, body: JsonObject) {
        if (!isConnected(ctx) || !autoSync(ctx)) return
        val app = ctx.applicationContext
        scope.launch {
            val ok = runCatching { authed(app, post(path, body)).code in 200..299 }.getOrDefault(false)
            if (!ok) synchronized(this@Trakt) { saveQueue(app, queue(app) + "$path\u0002$body") }
        }
    }

    suspend fun flushQueue(ctx: Context) {
        val items = synchronized(this) { queue(ctx).also { saveQueue(ctx, emptyList()) } }
        val failed = mutableListOf<String>()
        for (item in items) {
            val (path, body) = item.split("\u0002", limit = 2).let { it[0] to it.getOrElse(1) { "{}" } }
            val ok = runCatching {
                authed(ctx, base(path).post(body.toRequestBody(JSON))).code in 200..299
            }.getOrDefault(false)
            if (!ok) failed += item
        }
        if (failed.isNotEmpty()) synchronized(this) { saveQueue(ctx, failed + queue(ctx)) }
    }

    /** TVmaze knows the IMDb / TheTVDB ids Trakt needs. */
    private suspend fun showIds(ctx: Context, show: ShowEntity): JsonObject? {
        if (show.imdb != null) return buildJsonObject { put("imdb", show.imdb) }
        val tvdb = runCatching { ctx.container.api.showFull(show.id, emptyList()).externals?.thetvdb }.getOrNull()
        return tvdb?.let { buildJsonObject { put("tvdb", it) } }
    }

    private fun episodesBody(show: ShowEntity, ids: JsonObject, eps: List<EpisodeEntity>, withDates: Boolean) = buildJsonObject {
        putJsonArray("shows") {
            add(buildJsonObject {
                put("title", show.name)
                put("ids", ids)
                putJsonArray("seasons") {
                    eps.filter { it.number != null }.groupBy { it.season }.forEach { (season, list) ->
                        add(buildJsonObject {
                            put("number", season)
                            putJsonArray("episodes") {
                                list.forEach { e ->
                                    add(buildJsonObject {
                                        put("number", e.number!!)
                                        if (withDates) e.watchedAt?.let { put("watched_at", Instant.ofEpochMilli(it).toString()) }
                                    })
                                }
                            }
                        })
                    }
                }
            })
        }
    }

    /** Called when episodes are marked (un)watched in AirSeries. */
    fun pushEpisodes(ctx: Context, show: ShowEntity, eps: List<EpisodeEntity>, watched: Boolean) {
        if (eps.isEmpty() || !isConnected(ctx) || !autoSync(ctx)) return
        val app = ctx.applicationContext
        scope.launch {
            val ids = showIds(app, show) ?: return@launch
            send(app, if (watched) "/sync/history" else "/sync/history/remove", episodesBody(show, ids, eps, withDates = watched))
        }
    }

    private fun movieBody(tmdb: Int, watchedAt: Long? = null) = buildJsonObject {
        putJsonArray("movies") {
            add(buildJsonObject {
                putJsonObject("ids") { put("tmdb", tmdb) }
                watchedAt?.let { put("watched_at", Instant.ofEpochMilli(it).toString()) }
            })
        }
    }

    fun pushMovieWatched(ctx: Context, tmdb: Int, watched: Boolean) {
        send(ctx, if (watched) "/sync/history" else "/sync/history/remove", movieBody(tmdb, if (watched) System.currentTimeMillis() else null))
        if (watched) send(ctx, "/sync/watchlist/remove", movieBody(tmdb))
    }

    fun pushMovieWatchlist(ctx: Context, tmdb: Int, add: Boolean) =
        send(ctx, if (add) "/sync/watchlist" else "/sync/watchlist/remove", movieBody(tmdb))

    /** Uploads everything AirSeries knows that Trakt doesn't have yet (no duplicate plays). */
    fun pushAllInBackground(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            _status.value = _status.value.copy(busy = true, message = null)
            val err = runCatching { pushAll(app) }.exceptionOrNull()
            _status.value = _status.value.copy(busy = false, message = err?.message ?: "ok")
        }
    }

    private suspend fun pushAll(ctx: Context) {
        val dao = ctx.container.db.dao()
        // what Trakt already has: imdb/tvdb -> set of (season, episode)
        val remote = HashMap<String, MutableSet<Pair<Int, Int>>>()
        allPages(ctx, "/sync/watched/shows?extended=progress").forEach { el ->
            val o = el.jsonObject
            val ids = o["show"]?.jsonObject?.get("ids")?.jsonObject
            val keys = listOfNotNull(
                ids?.get("imdb")?.jsonPrimitive?.contentOrNull?.let { "imdb:$it" },
                ids?.get("tvdb")?.jsonPrimitive?.intOrNull?.let { "tvdb:$it" },
            )
            val set = HashSet<Pair<Int, Int>>()
            o["seasons"]?.jsonArray?.forEach { s ->
                val sn = s.jsonObject["number"]?.jsonPrimitive?.int ?: return@forEach
                s.jsonObject["episodes"]?.jsonArray?.forEach { e -> e.jsonObject["number"]?.jsonPrimitive?.intOrNull?.let { set += sn to it } }
            }
            keys.forEach { remote.getOrPut(it) { HashSet() }.addAll(set) }
        }
        val eps = dao.allEpisodes().filter { it.watched && it.number != null }.groupBy { it.showId }
        for (show in dao.shows()) {
            val watched = eps[show.id].orEmpty()
            if (watched.isEmpty()) continue
            val ids = showIds(ctx, show) ?: continue
            val key = ids["imdb"]?.jsonPrimitive?.contentOrNull?.let { "imdb:$it" } ?: "tvdb:${ids["tvdb"]?.jsonPrimitive?.contentOrNull}"
            val have = remote[key].orEmpty()
            val missing = watched.filter { (it.season to it.number!!) !in have }
            if (missing.isEmpty()) continue
            authed(ctx, post("/sync/history", episodesBody(show, ids, missing, withDates = true)))
            delay(300)
        }
        // movies
        val remoteMovies = allPages(ctx, "/sync/watched/movies").mapNotNull {
            it.jsonObject["movie"]?.jsonObject?.get("ids")?.jsonObject?.get("tmdb")?.jsonPrimitive?.intOrNull
        }.toSet()
        val watchlist = allPages(ctx, "/sync/watchlist/movies").mapNotNull {
            it.jsonObject["movie"]?.jsonObject?.get("ids")?.jsonObject?.get("tmdb")?.jsonPrimitive?.intOrNull
        }.toSet()
        val myMovies = dao.movies()
        val toHistory = myMovies.filter { it.status == MovieStatus.WATCHED && it.id !in remoteMovies }
        val toWatchlist = myMovies.filter { it.status == MovieStatus.PLAN && it.id !in watchlist }
        if (toHistory.isNotEmpty()) authed(ctx, post("/sync/history", buildJsonObject {
            putJsonArray("movies") { toHistory.forEach { m -> add(movieBody(m.id, m.watchedAt)["movies"]!!.jsonArray[0]) } }
        }))
        if (toWatchlist.isNotEmpty()) authed(ctx, post("/sync/watchlist", buildJsonObject {
            putJsonArray("movies") { toWatchlist.forEach { m -> add(movieBody(m.id)["movies"]!!.jsonArray[0]) } }
        }))
        // shows on "plan to watch" → Trakt watchlist
        val planShows = dao.shows().filter { it.listStatus == ListStatus.PLAN }
        if (planShows.isNotEmpty()) {
            val arr = buildJsonArray { planShows.forEach { s -> showIds(ctx, s)?.let { add(buildJsonObject { put("ids", it) }) } } }
            if (arr.isNotEmpty()) authed(ctx, post("/sync/watchlist", buildJsonObject { put("shows", arr) }))
        }
    }
}
