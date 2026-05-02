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
     * When the queue runs empty and an episode finishes, auto-advance to the
     * next published episode of the SAME feed (most-recent that hasn't been
     * played to completion). Default true so listeners who play a feed straight
     * through don't have to babysit the player.
     */
    val autoPlayNextInFeed: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_AUTO_PLAY_NEXT_FEED] ?: true }

    suspend fun setAutoPlayNextInFeed(v: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_PLAY_NEXT_FEED] = v }
    }

    /**
     * UI text scale multiplier (Compose density.fontScale override). 1.0 = stock.
     * Range 0.85 .. 1.4. Stored as float; clamped on read.
     */
    val textScale: Flow<Float> =
        context.dataStore.data.map { (it[KEY_TEXT_SCALE] ?: 1.0f).coerceIn(0.85f, 1.4f) }

    suspend fun setTextScale(v: Float) {
        context.dataStore.edit { it[KEY_TEXT_SCALE] = v.coerceIn(0.85f, 1.4f) }
    }

    /**
     * When true, "Played" episodes (positionMs >= durationMs - 5s) stay visible
     * in the per-podcast list with the strike-through / dim treatment. When
     * false, played episodes are hidden until the user toggles "Show archived"
     * in the top bar (which already shows archived episodes — turning this off
     * makes the un-archived list strictly forward-looking). Default true.
     */
    val showPlayedInList: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_SHOW_PLAYED] ?: true }

    suspend fun setShowPlayedInList(v: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_PLAYED] = v }
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
        private val KEY_AUTO_PLAY_NEXT_FEED =
            androidx.datastore.preferences.core.booleanPreferencesKey("auto_play_next_feed")
        private val KEY_TEXT_SCALE =
            androidx.datastore.preferences.core.floatPreferencesKey("text_scale")
        private val KEY_SHOW_PLAYED =
            androidx.datastore.preferences.core.booleanPreferencesKey("show_played_in_list")
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
