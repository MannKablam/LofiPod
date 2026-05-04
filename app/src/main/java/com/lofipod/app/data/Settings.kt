package com.lofipod.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
     * How many days a *played* episode lingers in the per-podcast list before
     * the auto-archive sweep moves it to the archive. 0 = off (never
     * auto-archive). Default 3 days, matching the original hardcoded constant.
     * Only finished episodes get swept; in-progress ones never auto-archive
     * regardless of this setting.
     */
    val autoArchiveDays: Flow<Int> =
        context.dataStore.data.map { (it[KEY_AUTO_ARCHIVE_DAYS] ?: 3).coerceAtLeast(0) }

    suspend fun setAutoArchiveDays(days: Int) {
        context.dataStore.edit { it[KEY_AUTO_ARCHIVE_DAYS] = days.coerceAtLeast(0) }
    }

    /**
     * Skip-silence aggressiveness: 0 = off (passthrough), 1..3 = stages from
     * gentle to aggressive (matches SilenceSkippingProcessor's `level`).
     * Default 0 — silence skipping is opt-in. Persists across restarts;
     * PlaybackService rehydrates the processor on onCreate.
     */
    val skipSilenceLevel: Flow<Int> =
        context.dataStore.data.map { (it[KEY_SKIP_SILENCE_LEVEL] ?: 0).coerceIn(0, 3) }

    suspend fun setSkipSilenceLevel(level: Int) {
        context.dataStore.edit { it[KEY_SKIP_SILENCE_LEVEL] = level.coerceIn(0, 3) }
    }

    // ---- Auto-backup ----

    /** Tree URI (SAF) the user picked as the backup folder; null = unset. */
    val backupTreeUri: Flow<String?> =
        context.dataStore.data.map { it[KEY_BACKUP_TREE_URI] }

    suspend fun setBackupTreeUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(KEY_BACKUP_TREE_URI)
            else it[KEY_BACKUP_TREE_URI] = uri
        }
    }

    /**
     * Auto-backup interval in hours; 0 = disabled. Default 0. The auto-backup
     * worker reschedules itself when this changes.
     */
    val backupIntervalHours: Flow<Int> =
        context.dataStore.data.map { (it[KEY_BACKUP_INTERVAL_HOURS] ?: 0).coerceAtLeast(0) }

    suspend fun setBackupIntervalHours(hours: Int) {
        context.dataStore.edit { it[KEY_BACKUP_INTERVAL_HOURS] = hours.coerceAtLeast(0) }
    }

    /** Epoch ms of the last successful backup write; 0 = never. */
    val backupLastSuccessAt: Flow<Long> =
        context.dataStore.data.map { it[KEY_BACKUP_LAST_SUCCESS] ?: 0L }

    suspend fun setBackupLastSuccessAt(ts: Long) {
        context.dataStore.edit { it[KEY_BACKUP_LAST_SUCCESS] = ts }
    }

    // ---- Update checker ----

    /**
     * When true, the nightly UpdateWorker fires at 23:59 local time and
     * checks GitHub for a newer release. Default true — the cost of a
     * once-a-day HTTPS request is negligible and makes "Why isn't there an
     * update yet?" never come up.
     */
    val updateAutoCheckEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_UPDATE_AUTO_CHECK] ?: true }

    suspend fun setUpdateAutoCheckEnabled(v: Boolean) {
        context.dataStore.edit { it[KEY_UPDATE_AUTO_CHECK] = v }
    }

    /** Epoch ms of the last successful update check; 0 = never. */
    val updateLastCheckedAt: Flow<Long> =
        context.dataStore.data.map { it[KEY_UPDATE_LAST_CHECKED] ?: 0L }

    suspend fun setUpdateLastCheckedAt(ts: Long) {
        context.dataStore.edit { it[KEY_UPDATE_LAST_CHECKED] = ts }
    }

    /** versionCode of a downloaded-but-not-yet-installed update; 0 = none. */
    val updateAvailableVersionCode: Flow<Int> =
        context.dataStore.data.map { it[KEY_UPDATE_AVAILABLE_CODE] ?: 0 }

    suspend fun setUpdateAvailableVersionCode(code: Int) {
        context.dataStore.edit { it[KEY_UPDATE_AVAILABLE_CODE] = code }
    }

    /** versionName of a downloaded-but-not-yet-installed update; null = none. */
    val updateAvailableVersionName: Flow<String?> =
        context.dataStore.data.map { it[KEY_UPDATE_AVAILABLE_NAME] }

    suspend fun setUpdateAvailableVersionName(name: String?) {
        context.dataStore.edit {
            if (name == null) it.remove(KEY_UPDATE_AVAILABLE_NAME)
            else it[KEY_UPDATE_AVAILABLE_NAME] = name
        }
    }

    /**
     * Selected visual direction. Default Lowlight (eye-friendly amber/charcoal).
     * Migration map for legacy values:
     *  - TWILIGHT/FOREST/CORAL → Cassette (pre-direction-overhaul color names)
     *  - GAMEBOY/DMG           → Lowlight (DMG Handheld theme was retired
     *                                       2026-05-03; both names map forward
     *                                       to the new default rather than
     *                                       leaving existing users on a now-
     *                                       missing enum value)
     */
    val theme: Flow<LofiTheme> = context.dataStore.data.map {
        val raw = it[KEY_THEME] ?: LofiTheme.LOWLIGHT.name
        runCatching { LofiTheme.valueOf(raw) }.getOrElse {
            when (raw) {
                "TWILIGHT", "FOREST", "CORAL" -> LofiTheme.CASSETTE
                "GAMEBOY", "DMG" -> LofiTheme.LOWLIGHT
                else -> LofiTheme.LOWLIGHT
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
        private val KEY_AUTO_ARCHIVE_DAYS =
            androidx.datastore.preferences.core.intPreferencesKey("auto_archive_days")
        private val KEY_SKIP_SILENCE_LEVEL =
            androidx.datastore.preferences.core.intPreferencesKey("skip_silence_level")
        private val KEY_BACKUP_TREE_URI =
            androidx.datastore.preferences.core.stringPreferencesKey("backup_tree_uri")
        private val KEY_BACKUP_INTERVAL_HOURS =
            androidx.datastore.preferences.core.intPreferencesKey("backup_interval_hours")
        private val KEY_BACKUP_LAST_SUCCESS =
            androidx.datastore.preferences.core.longPreferencesKey("backup_last_success_at")
        private val KEY_UPDATE_AUTO_CHECK =
            androidx.datastore.preferences.core.booleanPreferencesKey("update_auto_check")
        private val KEY_UPDATE_LAST_CHECKED =
            androidx.datastore.preferences.core.longPreferencesKey("update_last_checked_at")
        private val KEY_UPDATE_AVAILABLE_CODE =
            androidx.datastore.preferences.core.intPreferencesKey("update_available_version_code")
        private val KEY_UPDATE_AVAILABLE_NAME =
            androidx.datastore.preferences.core.stringPreferencesKey("update_available_version_name")
    }
}

/**
 * Visual direction. Each is a complete palette + type + decorative-chrome lane.
 * Cassette is Direction B (the original); Reel + Ticker are the lo-fi family
 * cousins from design/specs/Direction-{D,F}.md. Daylight + Lowlight are the
 * two plain themes for outdoor and night use respectively. The palette +
 * display font + accent rule are what each direction actually wires into the
 * Material theme; richer chrome (sprockets, reels) lives in per-direction
 * composables.
 *
 * Lowlight is the default — it's the most universally comfortable, and it
 * doubles as the migration target for any retired theme value (see Settings).
 */
enum class LofiTheme(val displayName: String, val tagline: String) {
    LOWLIGHT("Lowlight",     "Near-black + warm amber. Best at night."),
    CASSETTE("Cassette",     "Twilight navy, amber tape — the original."),
    REEL    ("Reel-to-Reel", "Cream faceplate, brass + oxblood, mono type."),
    TICKER  ("Ticker Tape",  "Newsroom paper, courier ink, spot red."),
    DAYLIGHT("Daylight",     "High-contrast white + ink. Best in sunlight."),
}
