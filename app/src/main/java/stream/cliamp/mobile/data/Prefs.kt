package stream.cliamp.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import stream.cliamp.mobile.data.db.CliampDatabase
import stream.cliamp.mobile.data.db.CustomStationEntity
import stream.cliamp.mobile.data.db.FavoriteEntity
import stream.cliamp.mobile.data.db.HistoryEntity
import stream.cliamp.mobile.data.db.toEntity
import stream.cliamp.mobile.net.Http

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("cliamp")

/**
 * Settings only.
 *
 * The scalars stay here because that is what a preferences store is good at:
 * a dozen values, read as flows, updated atomically. The collections that used
 * to live alongside them as JSON blobs are in SQLite now, because DataStore
 * has no partial write: adding one favourite rewrote the whole file, and a
 * track change did that four times over.
 */
class Prefs(private val context: Context) {

    private val db by lazy { CliampDatabase.get(context) }

    private object K {
        val palette = stringPreferencesKey("palette")           // system | oxide | dark | ...
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
        val wLevels = stringPreferencesKey("w_levels")
        val wPeaks = stringPreferencesKey("w_peaks")
        val wNext = stringPreferencesKey("w_next")
        val wSource = stringPreferencesKey("w_source")
        val wPosition = longPreferencesKey("w_position")
        val wDuration = longPreferencesKey("w_duration")
    }

    val palette: Flow<String> = context.settingsStore.data.map { it[K.palette] ?: "system" }
    val haptics: Flow<Boolean> = context.settingsStore.data.map { it[K.haptics] ?: true }
    val visualizer: Flow<String> = context.settingsStore.data.map { it[K.visualizer] ?: "spectrum" }
    val cellular: Flow<Boolean> = context.settingsStore.data.map { it[K.cellular] ?: true }
    val bufferSeconds: Flow<Int> = context.settingsStore.data.map { it[K.bufferSeconds] ?: 30 }
    val eqEnabled: Flow<Boolean> = context.settingsStore.data.map { it[K.eqEnabled] ?: false }
    val eqPreset: Flow<String> = context.settingsStore.data.map { it[K.eqPreset] ?: "flat" }
    val autoResume: Flow<Boolean> = context.settingsStore.data.map { it[K.autoResume] ?: false }
    val volume: Flow<Float> = context.settingsStore.data.map { it[K.volume] ?: 1f }

    val eqBands: Flow<List<Float>> = context.settingsStore.data.map { p ->
        p[K.eqBands]?.let { raw -> runCatching { Http.json.decodeFromString<List<Float>>(raw) }.getOrNull() }
            ?: List(7) { 0f }
    }

    val favorites: Flow<List<Station>> =
        db.favorites().all().map { rows -> rows.map { it.toStation() } }

    /**
     * The widget can be rendered long after the app process died, so anything
     * it draws has to survive on disk - the in-memory PlaybackBus is no use
     * there.
     */
    val widgetPlaying: Flow<Boolean> = context.settingsStore.data.map { it[K.wPlaying] ?: false }
    val widgetTrack: Flow<String> = context.settingsStore.data.map { it[K.wTrack] ?: "" }

    /**
     * The up-next stations (4 after the current one in the list being played)
     * the widget shows under the meter. Written by the service whenever the
     * now-playing or the list changes; empty when nothing is loaded.
     */
    val widgetNext: Flow<List<Station>> = context.settingsStore.data.map { p ->
        p[K.wNext]?.let { raw ->
            runCatching { Http.json.decodeFromString<List<Station>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    /**
     * A bounded window of the list being played, centred on the current song,
     * so the widget's prev/next can walk the right domain (local / radio /
     * podcast) without relying on the in-memory PlaybackBus, which is empty on
     * a cold process. Written by PlayerConnection whenever the list changes.
     */
    val widgetSource: Flow<List<Station>> = context.settingsStore.data.map { p ->
        p[K.wSource]?.let { raw ->
            runCatching { Http.json.decodeFromString<List<Station>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    /**
     * The widget's brick meter snapshot: the smoothed lit levels and the
     * lagging peak caps, computed by the same shared MeterCore the in-app
     * visualizer uses. Two comma-separated lists of 0..1 values, one per
     * column (empty before the service has written once). Widgets cannot
     * animate or read the live spectrum bus, so the service persists this on a
     * 2Hz throttle and the widget renders it as static bricks - a true mirror
     * of the in-app meter frozen per frame. The service always writes a
     * snapshot (idle when nothing is playing), so the widget never falls back
     * to a bare placeholder.
     */
    val widgetLevels: Flow<List<Float>> = context.settingsStore.data.map { p ->
        p[K.wLevels]?.let { raw ->
            runCatching { raw.split(',').mapNotNull { it.trim().toFloatOrNull() } }.getOrNull()
        } ?: emptyList()
    }
    val widgetPeaks: Flow<List<Float>> = context.settingsStore.data.map { p ->
        p[K.wPeaks]?.let { raw ->
            runCatching { raw.split(',').mapNotNull { it.trim().toFloatOrNull() } }.getOrNull()
        } ?: emptyList()
    }

    /**
     * The current playback clock for the widget's progress bar and time readout,
     * persisted with the spectrum so a cold process can still draw it. Duration
     * is 0 for live radio (nothing to scrub), which is exactly the gate the
     * widget uses to decide whether to show a seekable bar at all.
     */
    val widgetPositionMs: Flow<Long> =
        context.settingsStore.data.map { it[K.wPosition] ?: 0L }
    val widgetDurationMs: Flow<Long> =
        context.settingsStore.data.map { it[K.wDuration] ?: 0L }

    val history: Flow<List<Station>> =
        db.history().recent().map { rows -> rows.map { it.toStation() } }
    val custom: Flow<List<Station>> =
        db.customStations().all().map { rows -> rows.map { it.toStation() } }
    val lastStation: Flow<Station?> = context.settingsStore.data.map { p ->
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
    suspend fun setWidgetLevels(v: List<Float>) = put(K.wLevels, v.joinToString(","))
    suspend fun setWidgetPeaks(v: List<Float>) = put(K.wPeaks, v.joinToString(","))
    suspend fun setWidgetNext(v: List<Station>) = put(K.wNext, Http.json.encodeToString(v))
    suspend fun setWidgetSource(v: List<Station>) = put(K.wSource, Http.json.encodeToString(v))
    suspend fun setWidgetPositionMs(v: Long) = put(K.wPosition, v)
    suspend fun setWidgetDurationMs(v: Long) = put(K.wDuration, v)

    suspend fun readLastStation(): Station? =
        context.settingsStore.data.first()[K.lastStation]
            ?.let { runCatching { Http.json.decodeFromString<Station>(it) }.getOrNull() }

    suspend fun setEqBands(v: List<Float>) =
        put(K.eqBands, Http.json.encodeToString(v))

    suspend fun setLastStation(s: Station) =
        put(K.lastStation, Http.json.encodeToString(s))

    suspend fun toggleFavorite(s: Station): Boolean {
        if (db.favorites().contains(s.url)) {
            db.favorites().remove(s.url)
            return false
        }
        db.stations().upsert(s.toEntity())
        db.favorites().add(FavoriteEntity(s.url, db.favorites().nextTopPosition()))
        return true
    }

    suspend fun removeFavorite(s: Station) = db.favorites().remove(s.url)

    suspend fun pushHistory(s: Station) {
        db.stations().upsert(s.toEntity())
        db.history().touch(HistoryEntity(s.url, System.currentTimeMillis()))
        // trimming is a DELETE with a subquery, not a list rebuilt in full
        db.history().trim()
    }

    suspend fun clearHistory() = db.history().clear()

    suspend fun addCustom(s: Station) {
        db.stations().upsert(s.toEntity())
        db.customStations().add(CustomStationEntity(s.url, db.customStations().nextTopPosition()))
    }

    suspend fun removeCustom(s: Station) = db.customStations().remove(s.url)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.settingsStore.edit { it[key] = value }
    }
}
