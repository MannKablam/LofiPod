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
     * Direction the feed-fallback autoplay walks the episode list when the
     * queue is empty. Default `true` (= "up the list" = newer episodes), to
     * match the pre-toggle behaviour. `false` walks downward — older episodes
     * — useful for working through a backlog of older content chronologically.
     *
     * Only relevant when [autoPlayNextInFeed] is true AND the queue is empty;
     * an in-queue advance always honours the queue order.
     */
    val autoplayDirectionUp: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_AUTOPLAY_DIRECTION_UP] ?: true }

    suspend fun setAutoplayDirectionUp(v: Boolean) {
        context.dataStore.edit { it[KEY_AUTOPLAY_DIRECTION_UP] = v }
    }

    /**
     * Require user confirmation to keep playing autoplay-induced episodes.
     * When true, every autoplay (queue-next or feed-next) starts a 3:10
     * confirmation window: at 1:00 / 2:00 / 3:00 the app emits 1/2/3 short
     * beeps, ducking playback for the duration of each beep; at 3:10 the
     * episode auto-pauses unless the user (or a Bluetooth play/pause press)
     * has confirmed. Prevents indefinite background autoplay when nobody's
     * listening. Default true.
     */
    val autoplayConfirmEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_AUTOPLAY_CONFIRM] ?: true }

    suspend fun setAutoplayConfirmEnabled(v: Boolean) {
        context.dataStore.edit { it[KEY_AUTOPLAY_CONFIRM] = v }
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
     * Surfaces an extra "Diagnostics" tab on the Player screen alongside
     * Notes / Details / Transcript. When the user is actively listening and
     * something sounds wrong, this is the fastest path to the audio-chain
     * telemetry — no top-bar trip → Settings → Audio Fine-tuning →
     * Diagnostics. Default false: the tab is off-the-shelf only useful for
     * users actively diagnosing, and a 4th tab crowds the strip on narrow
     * devices.
     *
     * Toggle lives in the Settings → Audio diagnostics section (alongside
     * the "Open full audio diagnostics" entry point); when on, the Player
     * screen auto-grows a 4th tab + the tab supports an in-player
     * full-screen mode that keeps the mini-player at the bottom for
     * transport while you read the live readouts.
     */
    val showDiagnosticsTabInPlayer: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_SHOW_DIAGNOSTICS_TAB_IN_PLAYER] ?: false }

    suspend fun setShowDiagnosticsTabInPlayer(v: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_DIAGNOSTICS_TAB_IN_PLAYER] = v }
    }

    /**
     * Show a flush-valve icon in the player screen for manual AudioTrack
     * flush (v0.10.1+, redesigned in v0.10.10). Same vertical row as the
     * speed chip, justified right (speed stays centered). Pressing the
     * icon triggers
     * [PlayerController.flushAudio]. Off by default — niche debugging
     * affordance.
     */
    val showFlushButtonInPlayer: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_SHOW_FLUSH_BUTTON_IN_PLAYER] ?: false }

    suspend fun setShowFlushButtonInPlayer(v: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_FLUSH_BUTTON_IN_PLAYER] = v }
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

    /**
     * Master "Audio enhancement" toggle from the EQ screen. Default true.
     * Distinct from the per-podcast `podcast_state.eqDisabled` override —
     * both feed into [com.lofipod.app.player.PlayerController.applyEqOverrideFor],
     * which applies effective enabled = (global && !podcastDisabled). Persisting
     * here means the global toggle survives navigation and isn't silently
     * overwritten by per-podcast overrides on track transition.
     */
    val audioEnhancementEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_AUDIO_ENHANCEMENT_ENABLED] ?: true }

    suspend fun setAudioEnhancementEnabled(v: Boolean) {
        context.dataStore.edit { it[KEY_AUDIO_ENHANCEMENT_ENABLED] = v }
    }

    /**
     * Run a one-pole high-pass at ~5 Hz on the signal *before* the EQ chain.
     * Removes any DC offset that source material may carry — common in
     * low-bitrate MP3 sermons. Default false because well-mastered podcasts
     * have no DC and this is pure CPU for them; users who notice
     * headroom-stealing in their feeds can flip it on.
     */
    val dcBlockerEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_DC_BLOCKER_ENABLED] ?: false }

    suspend fun setDcBlockerEnabled(v: Boolean) {
        context.dataStore.edit { it[KEY_DC_BLOCKER_ENABLED] = v }
    }

    /**
     * Tone filters (v0.11) — global corrective stage that runs before the
     * per-podcast EQ in every phase mode. Like the DC blocker, these are
     * source-conditioning controls rather than per-show voicing, so they
     * live app-wide in Settings. 0 = off for all three.
     */
    val toneLowCutHz: Flow<Float> =
        context.dataStore.data.map { it[KEY_TONE_LOW_CUT_HZ] ?: 0f }

    suspend fun setToneLowCutHz(v: Float) {
        context.dataStore.edit { it[KEY_TONE_LOW_CUT_HZ] = v }
    }

    val toneHighCutHz: Flow<Float> =
        context.dataStore.data.map { it[KEY_TONE_HIGH_CUT_HZ] ?: 0f }

    suspend fun setToneHighCutHz(v: Float) {
        context.dataStore.edit { it[KEY_TONE_HIGH_CUT_HZ] = v }
    }

    val toneTiltDb: Flow<Float> =
        context.dataStore.data.map { (it[KEY_TONE_TILT_DB] ?: 0f).coerceIn(-6f, 6f) }

    suspend fun setToneTiltDb(v: Float) {
        context.dataStore.edit { it[KEY_TONE_TILT_DB] = v.coerceIn(-6f, 6f) }
    }

    /**
     * Set of feedUrls excluded from the canon-browse Bible index. Lets the
     * user hide a noisy feed (e.g., one that mistags a lot) from the
     * book/chapter/verse grids without removing it from the catalog. CSV
     * stored as the value (DataStore doesn't have native Set support).
     * Empty = include everything.
     */
    val canonBrowseExcludedFeeds: Flow<Set<String>> =
        context.dataStore.data.map {
            it[KEY_CANON_BROWSE_EXCLUDED]?.split(",")?.filter { s -> s.isNotBlank() }?.toSet()
                ?: emptySet()
        }

    suspend fun setCanonBrowseExcludedFeeds(feedUrls: Set<String>) {
        context.dataStore.edit {
            if (feedUrls.isEmpty()) it.remove(KEY_CANON_BROWSE_EXCLUDED)
            else it[KEY_CANON_BROWSE_EXCLUDED] = feedUrls.joinToString(",")
        }
    }

    /**
     * When the user starts an episode that has a detected scripture ref,
     * the next-up at end-of-stream comes from the canon-order resolver
     * (next sermon strictly after the current passage in Bible order)
     * instead of the standard queue/feed-next chain. Default off — the
     * user opts in via the canon-browse "Play through" affordance.
     */
    val canonAutoplayEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_CANON_AUTOPLAY] ?: false }

    suspend fun setCanonAutoplayEnabled(v: Boolean) {
        context.dataStore.edit { it[KEY_CANON_AUTOPLAY] = v }
    }

    /**
     * Legacy EQ phase-mode Boolean (pre-v0.9.3). False = minimum-phase
     * biquad; true = linear-phase 4096-tap FIR. Superseded by [phaseMode]
     * below, but the Boolean key is **preserved** in DataStore so a
     * downgrade (or a future re-read by something else) still finds it.
     * v0.9.3's reader prefers the new enum key; if absent, migrates from
     * the Boolean: true → "LINEAR_FIR", false → "PURE_IIR".
     */
    val phaseModeLinear: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_PHASE_MODE_LINEAR] ?: false }

    suspend fun setPhaseModeLinear(v: Boolean) {
        context.dataStore.edit { it[KEY_PHASE_MODE_LINEAR] = v }
    }

    /**
     * EQ phase mode (v0.9.3+). Three values:
     *   - `"PURE_IIR"` — minimum-phase biquad cascade (~6.4 ms total
     *     chain latency, no FIR convolution). Default. Fast slider
     *     response, lowest CPU.
     *   - `"MIN_FIR"` — minimum-phase FIR via UPC + cepstrum-derived
     *     kernel (~29 ms total latency, no pre-ringing, surgical
     *     magnitude precision). Best for transient-heavy speech.
     *   - `"LINEAR_FIR"` — linear-phase FIR via UPC + symmetric kernel
     *     (~70 ms total latency, preserves transient waveform shape,
     *     audible pre-ringing on transients). Academic-pure option.
     *
     * Reads do an in-place migration: if the new key is absent and the
     * legacy Boolean is present, derive the enum from it and surface
     * that value. The DataStore is not written here — it's first written
     * the next time the user actively chooses a mode in EqScreen.
     */
    val phaseMode: Flow<String> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_PHASE_MODE]
                ?: run {
                    val legacy = prefs[KEY_PHASE_MODE_LINEAR] ?: false
                    if (legacy) PHASE_MODE_LINEAR_FIR else PHASE_MODE_PURE_IIR
                }
        }

    suspend fun setPhaseMode(value: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PHASE_MODE] = value
            // Keep the legacy Boolean in sync so external readers (any
            // hypothetical telemetry, downgrade path) still see a usable
            // value. true ↔ any FIR mode (MIN_FIR, LINEAR_FIR, MIXED);
            // false ↔ pure IIR.
            prefs[KEY_PHASE_MODE_LINEAR] = value != PHASE_MODE_PURE_IIR
        }
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

    /**
     * Selected body font. Stored as the [com.lofipod.app.ui.theme.BodyFontChoice]
     * enum name; default `THEME_DEFAULT` so the theme spec's bodyFont stays
     * in charge until the user actively picks something on the Text settings
     * screen. Read by `LofiPodTheme` and folded into the active Material
     * typography.
     */
    val bodyFontChoiceKey: Flow<String> =
        context.dataStore.data.map { it[KEY_BODY_FONT_CHOICE] ?: "THEME_DEFAULT" }

    suspend fun setBodyFontChoiceKey(key: String) {
        context.dataStore.edit { it[KEY_BODY_FONT_CHOICE] = key }
    }

    /**
     * Notes text size in sp. Applies to the body of each [NoteCard] (the
     * text the user typed). 14 sp default matches the prior bodyMedium-ish
     * sizing; range 12..22 sp gives readable body up through "I prefer
     * larger text" without growing past the card's reasonable bounds.
     */
    val notesTextSizeSp: Flow<Float> =
        context.dataStore.data.map { it[KEY_NOTES_TEXT_SIZE] ?: 14f }

    suspend fun setNotesTextSizeSp(v: Float) {
        context.dataStore.edit { it[KEY_NOTES_TEXT_SIZE] = v.coerceIn(10f, 28f) }
    }

    /**
     * Notes pop-up (editor dialog) text size in sp. Independent from the
     * card display size because the editing surface is a different
     * ergonomic context — typing benefits from a larger size than reading.
     * Default 16 sp; same 12..22 range.
     */
    val notesPopupTextSizeSp: Flow<Float> =
        context.dataStore.data.map { it[KEY_NOTES_POPUP_TEXT_SIZE] ?: 16f }

    suspend fun setNotesPopupTextSizeSp(v: Float) {
        context.dataStore.edit { it[KEY_NOTES_POPUP_TEXT_SIZE] = v.coerceIn(10f, 28f) }
    }

    companion object {
        private val KEY_THEME = androidx.datastore.preferences.core.stringPreferencesKey("theme")
        private val KEY_AUTO_PLAY_NEXT_FEED =
            androidx.datastore.preferences.core.booleanPreferencesKey("auto_play_next_feed")
        private val KEY_AUTOPLAY_DIRECTION_UP =
            androidx.datastore.preferences.core.booleanPreferencesKey("autoplay_direction_up")
        private val KEY_AUTOPLAY_CONFIRM =
            androidx.datastore.preferences.core.booleanPreferencesKey("autoplay_confirm_enabled")
        private val KEY_TEXT_SCALE =
            androidx.datastore.preferences.core.floatPreferencesKey("text_scale")
        private val KEY_SHOW_PLAYED =
            androidx.datastore.preferences.core.booleanPreferencesKey("show_played_in_list")
        private val KEY_SHOW_DIAGNOSTICS_TAB_IN_PLAYER =
            androidx.datastore.preferences.core.booleanPreferencesKey("show_diagnostics_tab_in_player")
        private val KEY_SHOW_FLUSH_BUTTON_IN_PLAYER =
            androidx.datastore.preferences.core.booleanPreferencesKey("show_flush_button_in_player")
        private val KEY_AUTO_ARCHIVE_DAYS =
            androidx.datastore.preferences.core.intPreferencesKey("auto_archive_days")
        private val KEY_SKIP_SILENCE_LEVEL =
            androidx.datastore.preferences.core.intPreferencesKey("skip_silence_level")
        private val KEY_AUDIO_ENHANCEMENT_ENABLED =
            androidx.datastore.preferences.core.booleanPreferencesKey("audio_enhancement_enabled")
        private val KEY_DC_BLOCKER_ENABLED =
            androidx.datastore.preferences.core.booleanPreferencesKey("dc_blocker_enabled")
        private val KEY_TONE_LOW_CUT_HZ =
            androidx.datastore.preferences.core.floatPreferencesKey("tone_low_cut_hz")
        private val KEY_TONE_HIGH_CUT_HZ =
            androidx.datastore.preferences.core.floatPreferencesKey("tone_high_cut_hz")
        private val KEY_TONE_TILT_DB =
            androidx.datastore.preferences.core.floatPreferencesKey("tone_tilt_db")
        private val KEY_PHASE_MODE_LINEAR =
            androidx.datastore.preferences.core.booleanPreferencesKey("phase_mode_linear")
        private val KEY_PHASE_MODE =
            androidx.datastore.preferences.core.stringPreferencesKey("phase_mode")

        /** Phase-mode enum values stored as strings in DataStore. Kept as
         *  string-typed constants rather than a Kotlin enum because
         *  DataStore preferences only support primitive types directly,
         *  and a string key is the cleanest way to round-trip without an
         *  extra serialization layer. */
        const val PHASE_MODE_PURE_IIR = "PURE_IIR"
        const val PHASE_MODE_MIN_FIR = "MIN_FIR"
        const val PHASE_MODE_LINEAR_FIR = "LINEAR_FIR"
        const val PHASE_MODE_MIXED = "MIXED"
        private val KEY_CANON_BROWSE_EXCLUDED =
            androidx.datastore.preferences.core.stringPreferencesKey("canon_browse_excluded_feeds")
        private val KEY_CANON_AUTOPLAY =
            androidx.datastore.preferences.core.booleanPreferencesKey("canon_autoplay_enabled")
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
        private val KEY_BODY_FONT_CHOICE =
            androidx.datastore.preferences.core.stringPreferencesKey("body_font_choice")
        private val KEY_NOTES_TEXT_SIZE =
            androidx.datastore.preferences.core.floatPreferencesKey("notes_text_size_sp")
        private val KEY_NOTES_POPUP_TEXT_SIZE =
            androidx.datastore.preferences.core.floatPreferencesKey("notes_popup_text_size_sp")
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
