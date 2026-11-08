package gr.nikkpap.airseries.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("airseries_prefs", Context.MODE_PRIVATE)

    private val _notifyEnabled = MutableStateFlow(sp.getBoolean(K_NOTIFY, true))
    val notifyEnabled: StateFlow<Boolean> = _notifyEnabled.asStateFlow()

    /** Minutes before air time to notify (0 = at air time). */
    private val _notifyOffset = MutableStateFlow(sp.getInt(K_OFFSET, 0))
    val notifyOffset: StateFlow<Int> = _notifyOffset.asStateFlow()

    private val _notifyAnnouncements = MutableStateFlow(sp.getBoolean(K_ANNOUNCE, true))
    val notifyAnnouncements: StateFlow<Boolean> = _notifyAnnouncements.asStateFlow()

    private val _spoilers = MutableStateFlow(sp.getBoolean(K_SPOILER, true))
    val spoilerProtection: StateFlow<Boolean> = _spoilers.asStateFlow()

    /** 0 = system, 1 = light, 2 = dark */
    private val _theme = MutableStateFlow(sp.getInt(K_THEME, 0))
    val themeMode: StateFlow<Int> = _theme.asStateFlow()

    private val _dynamic = MutableStateFlow(sp.getBoolean(K_DYNAMIC, false))
    val dynamicColor: StateFlow<Boolean> = _dynamic.asStateFlow()

    private val _lastSync = MutableStateFlow(sp.getLong(K_LAST_SYNC, 0L))
    val lastSync: StateFlow<Long> = _lastSync.asStateFlow()

    fun setNotifyEnabled(v: Boolean) { sp.edit().putBoolean(K_NOTIFY, v).apply(); _notifyEnabled.value = v }
    fun setNotifyOffset(v: Int) { sp.edit().putInt(K_OFFSET, v).apply(); _notifyOffset.value = v }
    fun setNotifyAnnouncements(v: Boolean) { sp.edit().putBoolean(K_ANNOUNCE, v).apply(); _notifyAnnouncements.value = v }
    fun setSpoilerProtection(v: Boolean) { sp.edit().putBoolean(K_SPOILER, v).apply(); _spoilers.value = v }
    fun setThemeMode(v: Int) { sp.edit().putInt(K_THEME, v).apply(); _theme.value = v }
    fun setDynamicColor(v: Boolean) { sp.edit().putBoolean(K_DYNAMIC, v).apply(); _dynamic.value = v }
    fun setLastSync(v: Long) { sp.edit().putLong(K_LAST_SYNC, v).apply(); _lastSync.value = v }

    var lastRecsRefresh: Long
        get() = sp.getLong(K_RECS, 0L)
        set(v) = sp.edit().putLong(K_RECS, v).apply()

    var lastCatalogPage: Int
        get() = sp.getInt(K_PAGE, 0)
        set(v) = sp.edit().putInt(K_PAGE, v).apply()

    var lastNotifiedUpdate: Int
        get() = sp.getInt(K_UPD, 0)
        set(v) = sp.edit().putInt(K_UPD, v).apply()

    companion object {
        private const val K_UPD = "last_notified_update"
        private const val K_NOTIFY = "notify"
        private const val K_OFFSET = "notify_offset"
        private const val K_ANNOUNCE = "notify_announce"
        private const val K_SPOILER = "spoilers"
        private const val K_THEME = "theme"
        private const val K_DYNAMIC = "dynamic"
        private const val K_LAST_SYNC = "last_sync"
        private const val K_RECS = "recs_refresh"
        private const val K_PAGE = "catalog_page"
    }
}
