package com.lofipod.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("lofipod_settings")

class Settings(private val context: Context) {

    private val KEY_SOURCES_URI = stringPreferencesKey("sources_uri")
    private val KEY_EQ_BANDS = stringPreferencesKey("eq_bands")    // CSV of gains
    private val KEY_GAIN_DB = floatPreferencesKey("gain_db")
    private val KEY_PAUSE_ON_NOTE = booleanPreferencesKey("pause_on_note")

    val sourcesUri: Flow<String?> =
        context.dataStore.data.map { it[KEY_SOURCES_URI] }

    suspend fun setSourcesUri(uri: String) {
        context.dataStore.edit { it[KEY_SOURCES_URI] = uri }
    }

    val eqBandsCsv: Flow<String?> =
        context.dataStore.data.map { it[KEY_EQ_BANDS] }

    suspend fun setEqBandsCsv(csv: String) {
        context.dataStore.edit { it[KEY_EQ_BANDS] = csv }
    }

    val gainDb: Flow<Float> =
        context.dataStore.data.map { it[KEY_GAIN_DB] ?: 0f }

    suspend fun setGainDb(v: Float) {
        context.dataStore.edit { it[KEY_GAIN_DB] = v }
    }

    /** Auto-pause playback while the user is composing a note. Default true. */
    val pauseOnNote: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_PAUSE_ON_NOTE] ?: true }

    suspend fun setPauseOnNote(v: Boolean) {
        context.dataStore.edit { it[KEY_PAUSE_ON_NOTE] = v }
    }

    /**
     * Selected visual direction. Default Cassette (the original look).
     * Old color-only theme names from before the direction overhaul map to
     * Cassette so existing installs keep working.
     */
    val theme: Flow<LofiTheme> = context.dataStore.data.map {
        val raw = it[KEY_THEME] ?: LofiTheme.CASSETTE.name
        runCatching { LofiTheme.valueOf(raw) }.getOrElse {
            when (raw) {
                "TWILIGHT", "FOREST", "CORAL" -> LofiTheme.CASSETTE
                "GAMEBOY" -> LofiTheme.DMG
                else -> LofiTheme.CASSETTE
            }
        }
    }

    suspend fun setTheme(t: LofiTheme) {
        context.dataStore.edit { it[KEY_THEME] = t.name }
    }

    companion object {
        private val KEY_THEME = androidx.datastore.preferences.core.stringPreferencesKey("theme")
    }
}

/**
 * Visual direction. Each is a complete palette + type + decorative-chrome lane.
 * Cassette is Direction B (the original); D/E/F are the lo-fi family cousins from
 * design/specs/Direction-{D,E,F}.md. The palette + display font + accent rule are
 * what each direction actually wires into the Material theme; richer chrome
 * (sprockets, reels, sprites) lives in per-direction composables.
 */
enum class LofiTheme(val displayName: String, val tagline: String) {
    CASSETTE("Cassette",     "Twilight navy, amber tape — the original."),
    REEL    ("Reel-to-Reel", "Cream faceplate, brass + oxblood, mono type."),
    DMG     ("DMG Handheld", "Olive LCD, magenta chrome, pixel-only."),
    TICKER  ("Ticker Tape",  "Newsroom paper, courier ink, spot red."),
    DAYLIGHT("Daylight",     "High-contrast white + ink. Best in sunlight."),
    LOWLIGHT("Lowlight",     "Near-black + warm amber. Best at night.")
}
