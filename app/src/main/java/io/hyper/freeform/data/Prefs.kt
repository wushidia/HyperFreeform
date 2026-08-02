package io.hyper.freeform.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import io.hyper.freeform.service.FreeformBridge
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

private const val PREFS_NAME = "freeform_prefs"
private const val MIGRATED_DATASTORE = "_migrated_datastore_v1"

/**
 * Small reactive preferences wrapper.
 *
 * SharedPreferences is sufficient for this module's ten scalar/set options and avoids shipping
 * DataStore's protobuf/Okio/runtime plus four ABI helper libraries. The one-time reader below
 * migrates the previous `preferences_pb` file before exposing any flow, so an upgrade keeps all
 * existing selections.
 */
class Prefs(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyDataStore(appContext, prefs)
        migrateDpiPercentV2(appContext, prefs)
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_NOTIFICATION = "notification_freeform"
        const val KEY_RECENTS = "recents_freeform"
        const val KEY_KEEP_ALIVE = "keep_alive_pin"
        const val KEY_MAX = "max_windows"
        const val KEY_SCALE = "default_scale"
        const val KEY_DPI_PERCENT_LEGACY = "freeform_dpi_percent"
        const val KEY_DPI_PERCENT = "freeform_dpi_percent_v2"
        const val KEY_WINDOW_WIDTH_PERCENT = "freeform_window_width_percent"
        const val KEY_WINDOW_HEIGHT_PERCENT = "freeform_window_height_percent"
        const val KEY_FAV = "favorite_apps"
        const val KEY_SIDEBAR_SIDE = "sidebar_side"
        const val KEY_SIDEBAR_APPS = "sidebar_apps"
        const val KEY_SIDEBAR_SHOW_APP_NAMES = "sidebar_show_app_names"

        private val migrationLock = Any()

        private fun migrateLegacyDataStore(context: Context, prefs: SharedPreferences) {
            if (prefs.getBoolean(MIGRATED_DATASTORE, false)) return
            synchronized(migrationLock) {
                if (prefs.getBoolean(MIGRATED_DATASTORE, false)) return
                val legacyFile = File(
                    context.filesDir,
                    "datastore/$PREFS_NAME.preferences_pb",
                )
                val values = runCatching {
                    if (legacyFile.isFile) parsePreferenceMap(legacyFile.readBytes()) else emptyMap()
                }.getOrDefault(emptyMap())
                prefs.edit().apply {
                    values.forEach { (key, value) ->
                        when (value) {
                            is Boolean -> putBoolean(key, value)
                            is Int -> putInt(key, value)
                            is Float -> putFloat(key, value)
                            is String -> putString(key, value)
                            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                    putBoolean(MIGRATED_DATASTORE, true)
                    commit()
                }
            }
        }

        /** Migrate v1's fake 0..100 slider to the real 0..100 percentage without ambiguity. */
        private fun migrateDpiPercentV2(context: Context, prefs: SharedPreferences) {
            if (prefs.contains(KEY_DPI_PERCENT)) {
                val current = FreeformBridge.sanitizeDpiPercent(
                    prefs.getInt(KEY_DPI_PERCENT, FreeformBridge.DEFAULT_DPI_PERCENT),
                )
                if (current != prefs.getInt(KEY_DPI_PERCENT, current)) {
                    prefs.edit().putInt(KEY_DPI_PERCENT, current).commit()
                }
                return
            }

            val cr = context.contentResolver
            val globalV2 = runCatching {
                Settings.Global.getInt(cr, FreeformBridge.SETTING_DPI_PERCENT, -1)
            }.getOrDefault(-1)
            val migrated = when {
                globalV2 >= 0 -> FreeformBridge.sanitizeDpiPercent(globalV2)
                prefs.contains(KEY_DPI_PERCENT_LEGACY) -> FreeformBridge.migrateLegacyDpiPercent(
                    prefs.getInt(KEY_DPI_PERCENT_LEGACY, 65),
                )
                else -> {
                    val legacyGlobal = runCatching {
                        Settings.Global.getInt(cr, FreeformBridge.SETTING_DPI_PERCENT_LEGACY, -1)
                    }.getOrDefault(-1)
                    if (legacyGlobal >= 0) {
                        FreeformBridge.migrateLegacyDpiPercent(legacyGlobal)
                    } else {
                        FreeformBridge.DEFAULT_DPI_PERCENT
                    }
                }
            }
            prefs.edit().putInt(KEY_DPI_PERCENT, migrated).commit()
        }

        /** Minimal decoder for androidx.datastore.preferences.PreferencesProto.PreferenceMap. */
        private fun parsePreferenceMap(bytes: ByteArray): Map<String, Any> {
            val result = LinkedHashMap<String, Any>()
            val map = ProtoReader(bytes)
            while (map.hasRemaining()) {
                val tag = map.readVarint().toInt()
                if (tag ushr 3 == 1 && tag and 7 == 2) {
                    val entry = ProtoReader(map.readBytes())
                    var key: String? = null
                    var value: Any? = null
                    while (entry.hasRemaining()) {
                        val entryTag = entry.readVarint().toInt()
                        when {
                            entryTag ushr 3 == 1 && entryTag and 7 == 2 ->
                                key = entry.readBytes().toString(Charsets.UTF_8)

                            entryTag ushr 3 == 2 && entryTag and 7 == 2 ->
                                value = parseValue(entry.readBytes())

                            else -> entry.skip(entryTag and 7)
                        }
                    }
                    if (key != null && value != null) result[key] = value
                } else {
                    map.skip(tag and 7)
                }
            }
            return result
        }

        private fun parseValue(bytes: ByteArray): Any? {
            val value = ProtoReader(bytes)
            while (value.hasRemaining()) {
                val tag = value.readVarint().toInt()
                return when (tag ushr 3) {
                    1 -> value.readVarint() != 0L
                    2 -> Float.fromBits(value.readFixed32())
                    3 -> value.readVarint().toInt()
                    5 -> value.readBytes().toString(Charsets.UTF_8)
                    6 -> parseStringSet(value.readBytes())
                    else -> {
                        value.skip(tag and 7)
                        null
                    }
                }
            }
            return null
        }

        private fun parseStringSet(bytes: ByteArray): Set<String> {
            val strings = linkedSetOf<String>()
            val set = ProtoReader(bytes)
            while (set.hasRemaining()) {
                val tag = set.readVarint().toInt()
                if (tag ushr 3 == 1 && tag and 7 == 2) {
                    strings += set.readBytes().toString(Charsets.UTF_8)
                } else {
                    set.skip(tag and 7)
                }
            }
            return strings
        }
    }

    val enabled: Flow<Boolean> = valueFlow(KEY_ENABLED) { prefs.getBoolean(KEY_ENABLED, true) }
    val notificationFreeform: Flow<Boolean> =
        valueFlow(KEY_NOTIFICATION) { prefs.getBoolean(KEY_NOTIFICATION, true) }
    val recentsFreeform: Flow<Boolean> =
        valueFlow(KEY_RECENTS) { prefs.getBoolean(KEY_RECENTS, true) }
    val keepAlivePin: Flow<Boolean> =
        valueFlow(KEY_KEEP_ALIVE) { prefs.getBoolean(KEY_KEEP_ALIVE, true) }
    val maxWindows: Flow<Int> = valueFlow(KEY_MAX) { prefs.getInt(KEY_MAX, 2) }
    val defaultScale: Flow<Float> = valueFlow(KEY_SCALE) { prefs.getFloat(KEY_SCALE, 1f) }
    /** In-window DPI as a percentage of system density (100 = follow system). */
    val dpiPercent: Flow<Int> =
        valueFlow(KEY_DPI_PERCENT) { readDpiPercent() }
    /** Normal freeform width/height as real percentages of the natural portrait display. */
    val windowWidthPercent: Flow<Int> = valueFlow(KEY_WINDOW_WIDTH_PERCENT) {
        prefs.getInt(KEY_WINDOW_WIDTH_PERCENT, FreeformBridge.DEFAULT_WINDOW_WIDTH_PERCENT)
    }
    val windowHeightPercent: Flow<Int> = valueFlow(KEY_WINDOW_HEIGHT_PERCENT) {
        prefs.getInt(KEY_WINDOW_HEIGHT_PERCENT, FreeformBridge.DEFAULT_WINDOW_HEIGHT_PERCENT)
    }
    val favorites: Flow<Set<String>> = stringSetFlow(KEY_FAV)
    /** 0 = left edge, 1 = right edge (default). */
    val sidebarSide: Flow<Int> = valueFlow(KEY_SIDEBAR_SIDE) {
        prefs.getInt(KEY_SIDEBAR_SIDE, 1)
    }
    /** Packages the user chose to show in the sidebar (empty = all launchable apps). */
    val sidebarApps: Flow<Set<String>> = stringSetFlow(KEY_SIDEBAR_APPS)
    /** Whether app labels are shown beside icons in the sidebar. */
    val sidebarShowAppNames: Flow<Boolean> = valueFlow(KEY_SIDEBAR_SHOW_APP_NAMES) {
        prefs.getBoolean(KEY_SIDEBAR_SHOW_APP_NAMES, false)
    }

    suspend fun setEnabled(value: Boolean) = put { putBoolean(KEY_ENABLED, value) }
    suspend fun setNotificationFreeform(value: Boolean) = put { putBoolean(KEY_NOTIFICATION, value) }
    suspend fun setRecentsFreeform(value: Boolean) = put { putBoolean(KEY_RECENTS, value) }
    suspend fun setKeepAlivePin(value: Boolean) = put { putBoolean(KEY_KEEP_ALIVE, value) }
    suspend fun setMaxWindows(value: Int) = put { putInt(KEY_MAX, value) }
    suspend fun setDefaultScale(value: Float) = put { putFloat(KEY_SCALE, value) }
    suspend fun setDpiPercent(value: Int) = put {
        putInt(KEY_DPI_PERCENT, FreeformBridge.sanitizeDpiPercent(value))
    }
    suspend fun setWindowWidthPercent(value: Int) = put {
        putInt(KEY_WINDOW_WIDTH_PERCENT, FreeformBridge.sanitizeWindowSizePercent(value))
    }
    suspend fun setWindowHeightPercent(value: Int) = put {
        putInt(KEY_WINDOW_HEIGHT_PERCENT, FreeformBridge.sanitizeWindowSizePercent(value))
    }

    suspend fun toggleFavorite(packageName: String) = toggleInSet(KEY_FAV, packageName)

    suspend fun setSidebarSide(side: Int) = put {
        putInt(KEY_SIDEBAR_SIDE, side.coerceIn(0, 1))
    }

    /** Replace the full sidebar app selection. Empty set clears it (→ all launchable). */
    suspend fun setSidebarApps(packages: Set<String>) = put {
        putStringSet(KEY_SIDEBAR_APPS, packages.toSet())
    }

    suspend fun setSidebarShowAppNames(value: Boolean) = put {
        putBoolean(KEY_SIDEBAR_SHOW_APP_NAMES, value)
    }

    /** Toggle a single package in the sidebar selection. */
    suspend fun toggleSidebarApp(packageName: String) =
        toggleInSet(KEY_SIDEBAR_APPS, packageName)

    private fun <T> valueFlow(key: String, read: () -> T): Flow<T> = callbackFlow {
        trySend(read())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private fun stringSetFlow(key: String): Flow<Set<String>> = valueFlow(key) {
        prefs.getStringSet(key, emptySet()).orEmpty().toSet()
    }

    private fun readDpiPercent(): Int {
        if (prefs.contains(KEY_DPI_PERCENT)) {
            return FreeformBridge.sanitizeDpiPercent(
                prefs.getInt(KEY_DPI_PERCENT, FreeformBridge.DEFAULT_DPI_PERCENT),
            )
        }
        return FreeformBridge.sanitizeDpiPercent(Settings.Global.getInt(
            appContext.contentResolver,
            FreeformBridge.SETTING_DPI_PERCENT,
            FreeformBridge.DEFAULT_DPI_PERCENT,
        ))
    }

    private inline fun put(change: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(change).apply()
    }

    private fun toggleInSet(key: String, value: String) {
        synchronized(prefs) {
            val current = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
            if (!current.add(value)) current.remove(value)
            prefs.edit().putStringSet(key, current).apply()
        }
    }
}

private class ProtoReader(private val bytes: ByteArray) {
    private var position = 0

    fun hasRemaining(): Boolean = position < bytes.size

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (position < bytes.size && shift < 64) {
            val byte = bytes[position++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        error("Malformed protobuf varint")
    }

    fun readFixed32(): Int {
        require(position + 4 <= bytes.size) { "Truncated protobuf fixed32" }
        return (bytes[position++].toInt() and 0xff) or
            ((bytes[position++].toInt() and 0xff) shl 8) or
            ((bytes[position++].toInt() and 0xff) shl 16) or
            ((bytes[position++].toInt() and 0xff) shl 24)
    }

    fun readBytes(): ByteArray {
        val size = readVarint().toInt()
        require(size >= 0 && position + size <= bytes.size) { "Truncated protobuf bytes" }
        return bytes.copyOfRange(position, position + size).also { position += size }
    }

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> advance(8)
            2 -> advance(readVarint().toInt())
            5 -> advance(4)
            else -> error("Unsupported protobuf wire type $wireType")
        }
    }

    private fun advance(count: Int) {
        require(count >= 0 && position + count <= bytes.size) { "Truncated protobuf field" }
        position += count
    }
}
