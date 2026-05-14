package com.lofipod.app.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App-wide bug telemetry. Captures failures across the various
 * subsystems (feed loading, downloads, scripture tagging, etc.) so the
 * user can surface concrete data when something breaks instead of
 * "it didn't work."
 *
 * Sister to [StartupTimings]: that one is timing, this one is errors +
 * notable events. Both are read by the diagnostics screen.
 *
 * Design choices:
 *   - **Bounded ring buffers per category.** A misbehaving subsystem
 *     can't explode memory by spamming entries; the most-recent N are
 *     kept and older ones drop off.
 *   - **Single object, every subsystem reports here.** Avoids spreading
 *     bug-reporting plumbing across the codebase. Each subsystem has
 *     a `recordX(...)` for its category.
 *   - **No PII.** Feed URLs and episode guids are app-internal
 *     identifiers, not personal data. Error messages may include
 *     paths but are generally safe to share in a bug report.
 */
object AppDiagnostics {

    /** A single failure or notable event. */
    data class Entry(
        val timestampMs: Long,
        val category: Category,
        val identifier: String,    // feed URL, guid, etc.
        val detail: String,        // human-readable error / explanation
    )

    enum class Category(val label: String) {
        FEED_FAILURE("Feed failure"),
        FEED_RESCUE("Feed rescued"),
        DOWNLOAD_FAILURE("Download failure"),
        SCRIPTURE_TAG_SKIP("Scripture tag skipped"),
        /**
         * High-signal playback transitions that aren't errors but matter for
         * back-end triage: track changes (manual vs autoplay), download
         * handoffs in either direction, deferred auto-downloads, wake-lock
         * oscillation, feed-aware back-nav targets, etc. Kept separate from
         * OTHER so the diagnostics screen can render a dedicated "what
         * happened during playback" timeline without having to fish events
         * out of a generic bucket.
         */
        PLAYBACK("Playback event"),
        OTHER("Other"),
    }

    private const val PER_CATEGORY_CAP = 50

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private fun record(category: Category, identifier: String, detail: String) {
        val entry = Entry(
            timestampMs = System.currentTimeMillis(),
            category = category,
            identifier = identifier,
            detail = detail,
        )
        _entries.update { current ->
            // Cap per-category so a noisy category can't push out signal
            // from quieter ones. Keep the newest within each.
            val updated = (current + entry).reversed()
            val perCat = HashMap<Category, MutableList<Entry>>()
            for (e in updated) {
                val list = perCat.getOrPut(e.category) { mutableListOf() }
                if (list.size < PER_CATEGORY_CAP) list.add(e)
            }
            // Reassemble newest-first across all categories.
            perCat.values.flatten().sortedByDescending { it.timestampMs }
        }
    }

    /** A feed couldn't be fetched or parsed. */
    fun recordFeedFailure(feedUrl: String, error: String) {
        record(Category.FEED_FAILURE, feedUrl, error)
    }

    /** A feed parsed only after the tolerant retry pass. */
    fun recordFeedRescue(feedUrl: String, reason: String) {
        record(Category.FEED_RESCUE, feedUrl, reason)
    }

    /** DownloadManager.addDownload / removeDownload threw, or a state
     *  change came through with STATE_FAILED. */
    fun recordDownloadFailure(guid: String, error: String) {
        record(Category.DOWNLOAD_FAILURE, guid, error)
    }

    /** A scripture-tag attempt was skipped (book not in canon, low
     *  confidence, etc.) — informational, not an error. */
    fun recordScriptureTagSkip(guid: String, reason: String) {
        record(Category.SCRIPTURE_TAG_SKIP, guid, reason)
    }

    /** Generic catch-all for events that don't fit the named categories. */
    fun recordOther(identifier: String, detail: String) {
        record(Category.OTHER, identifier, detail)
    }

    /**
     * High-signal playback transition. [identifier] is a short stable key
     * (e.g. "track_change", "handoff_forward", "wake_lock_oscillation");
     * [detail] is the human-readable per-event payload. Surfaced on the
     * Audio Diagnostics screen under a "Playback events" section.
     */
    fun recordPlayback(identifier: String, detail: String) {
        record(Category.PLAYBACK, identifier, detail)
    }

    /** UI-side reset. */
    fun clear() {
        _entries.value = emptyList()
    }
}
