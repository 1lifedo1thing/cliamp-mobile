package stream.cliamp.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import stream.cliamp.mobile.net.Http

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("cliamp")

/** Everything the settings screen writes, plus favourites and history blobs. */
class Prefs(private val context: Context) {

    private object K {
        val palette = stringPreferencesKey("palette")           // dark | light | system
        val haptics = booleanPreferencesKey("haptics")
        val visualizer = stringPreferencesKey("visualizer")     // spectrum | scope | off
        val cellular = booleanPreferencesKey("cellular")
        val bufferSeconds = intPreferencesKey("buffer_seconds")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = stringPreferencesKey("eq_preset")
        val eqBands = stringPreferencesKey("eq_bands")
        val favorites = stringPreferencesKey("favorites")
        val history = stringPreferencesKey("history")
        val custom = stringPreferencesKey("custom")
        val lastStation = stringPreferencesKey("last_station")
        val volume = floatPreferencesKey("volume")
        val autoResume = booleanPreferencesKey("auto_resume")
        val wPlaying = booleanPreferencesKey("w_playing")
        val wTrack = stringPreferencesKey("w_track")
    }

    val palette: Flow<String> = context.dataStore.data.map { it[K.palette] ?: "dark" }
    val haptics: Flow<Boolean> = context.dataStore.data.map { it[K.haptics] ?: true }
    val visualizer: Flow<String> = context.dataStore.data.map { it[K.visualizer] ?: "spectrum" }
    val cellular: Flow<Boolean> = context.dataStore.data.map { it[K.cellular] ?: true }
    val bufferSeconds: Flow<Int> = context.dataStore.data.map { it[K.bufferSeconds] ?: 20 }
    val eqEnabled: Flow<Boolean> = context.dataStore.data.map { it[K.eqEnabled] ?: false }
    val eqPreset: Flow<String> = context.dataStore.data.map { it[K.eqPreset] ?: "flat" }
    val autoResume: Flow<Boolean> = context.dataStore.data.map { it[K.autoResume] ?: false }
    val volume: Flow<Float> = context.dataStore.data.map { it[K.volume] ?: 1f }

    val eqBands: Flow<List<Float>> = context.dataStore.data.map { p ->
        p[K.eqBands]?.let { raw -> runCatching { Http.json.decodeFromString<List<Float>>(raw) }.getOrNull() }
            ?: List(7) { 0f }
    }

    val favorites: Flow<List<Station>> = context.dataStore.data.map { decodeStations(it[K.favorites]) }

    /**
     * The widget can be rendered long after the app process died, so anything
     * it draws has to survive on disk - the in-memory PlaybackBus is no use
     * there.
     */
    val widgetPlaying: Flow<Boolean> = context.dataStore.data.map { it[K.wPlaying] ?: false }
    val widgetTrack: Flow<String> = context.dataStore.data.map { it[K.wTrack] ?: "" }
    val history: Flow<List<Station>> = context.dataStore.data.map { decodeStations(it[K.history]) }
    val custom: Flow<List<Station>> = context.dataStore.data.map { decodeStations(it[K.custom]) }
    val lastStation: Flow<Station?> = context.dataStore.data.map { p ->
        p[K.lastStation]?.let { raw -> runCatching { Http.json.decodeFromString<Station>(raw) }.getOrNull() }
    }

    private fun decodeStations(raw: String?): List<Station> =
        raw?.let { runCatching { Http.json.decodeFromString<List<Station>>(it) }.getOrNull() } ?: emptyList()

    suspend fun setPalette(v: String) = put(K.palette, v)
    suspend fun setHaptics(v: Boolean) = put(K.haptics, v)
    suspend fun setVisualizer(v: String) = put(K.visualizer, v)
    suspend fun setCellular(v: Boolean) = put(K.cellular, v)
    suspend fun setBufferSeconds(v: Int) = put(K.bufferSeconds, v)
    suspend fun setEqEnabled(v: Boolean) = put(K.eqEnabled, v)
    suspend fun setEqPreset(v: String) = put(K.eqPreset, v)
    suspend fun setAutoResume(v: Boolean) = put(K.autoResume, v)
    suspend fun setVolume(v: Float) = put(K.volume, v)

    suspend fun setWidgetPlaying(v: Boolean) = put(K.wPlaying, v)
    suspend fun setWidgetTrack(v: String) = put(K.wTrack, v)

    suspend fun readLastStation(): Station? =
        context.dataStore.data.first()[K.lastStation]
            ?.let { runCatching { Http.json.decodeFromString<Station>(it) }.getOrNull() }

    suspend fun setEqBands(v: List<Float>) =
        put(K.eqBands, Http.json.encodeToString(v))

    suspend fun setLastStation(s: Station) =
        put(K.lastStation, Http.json.encodeToString(s))

    suspend fun toggleFavorite(s: Station): Boolean {
        var added = false
        context.dataStore.edit { p ->
            val list = decodeStations(p[K.favorites]).toMutableList()
            val i = list.indexOfFirst { it.url == s.url }
            if (i >= 0) list.removeAt(i) else { list.add(0, s); added = true }
            p[K.favorites] = Http.json.encodeToString(list)
        }
        return added
    }

    suspend fun removeFavorite(s: Station) {
        context.dataStore.edit { p ->
            val list = decodeStations(p[K.favorites]).filterNot { it.url == s.url }
            p[K.favorites] = Http.json.encodeToString(list)
        }
    }

    suspend fun pushHistory(s: Station) {
        context.dataStore.edit { p ->
            val list = decodeStations(p[K.history]).filterNot { it.url == s.url }.toMutableList()
            list.add(0, s)
            while (list.size > 60) list.removeAt(list.size - 1)
            p[K.history] = Http.json.encodeToString(list)
        }
    }

    suspend fun clearHistory() = put(K.history, "[]")

    suspend fun addCustom(s: Station) {
        context.dataStore.edit { p ->
            val list = decodeStations(p[K.custom]).filterNot { it.url == s.url }.toMutableList()
            list.add(0, s)
            p[K.custom] = Http.json.encodeToString(list)
        }
    }

    suspend fun removeCustom(s: Station) {
        context.dataStore.edit { p ->
            p[K.custom] = Http.json.encodeToString(decodeStations(p[K.custom]).filterNot { it.url == s.url })
        }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
