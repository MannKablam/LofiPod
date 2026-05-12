package com.lofipod.app.audio

import android.content.Context
import android.os.Build

/**
 * Bridge to Android's `PerformanceHintManager` (API 31+).
 *
 * The audio chain calls this on the audio thread once per buffer to tell the
 * OS scheduler:
 *   1. **Target work duration** — "I expect to finish this buffer in N ns of
 *      wall-clock time."
 *   2. **Actual work duration** — "I actually finished it in M ns."
 *
 * The OS uses this to keep the CPU at the right frequency for the workload.
 * Without it, the governor downclocks during light segments and then has to
 * spin back up under load, producing exactly the kind of scheduling jitter
 * that the v0.7.0 AudioTrack buffer expansion was working around. With it,
 * the CPU stays at the level the audio chain needs — particularly relevant
 * to the FIR convolution + Sonic + 2× oversampler chain, and especially at
 * 2× playback speed where each buffer's wall-clock budget halves.
 *
 * **API gating.** `PerformanceHintManager` only exists on API 31 (Android 12)
 * and later. To stay safe on older devices the class stores all API-31 types
 * as [Any] and casts at call sites — the JVM never has to load the
 * `PerformanceHintManager` symbol when running on a lower SDK.
 *
 * **Defensive.** Some OEM builds ship broken implementations of this service
 * (it can return null from `getSystemService`, `createHintSession` can return
 * null, or session methods can throw). Every API call is wrapped in
 * try/catch and degrades silently — a hint failure must never disrupt audio.
 *
 * Created once at application startup via
 * [AudioChainTelemetry.installPerformanceHintBridge]; the audio thread
 * accesses it through the telemetry singleton.
 */
class PerformanceHintBridge(context: Context) {

    // Stored as Any? so the API 31+ class isn't touched on older devices.
    // The JVM only resolves the actual class when one of the typed casts
    // below executes, which is gated on SDK_INT >= S.
    private var manager: Any? = null
    private var session: Any? = null

    @Volatile private var currentTargetNsValue: Long = 0L
    @Volatile private var lastErrorMessage: String? = null

    /**
     * TID the active session is pinned to. -1 means "no session". A change
     * detected on subsequent `ensureSession` calls indicates the audio sink
     * has swapped threads (sink rebuild on format change, low-power audio
     * path swap on some OEMs); the existing session is bound to a dead
     * thread and must be torn down so the new TID gets its own hint
     * session. Without this, a thread swap leaves the hint pointed at a TID
     * the scheduler will never see again — the new thread runs unhinted
     * for the lifetime of the process.
     */
    @Volatile private var currentThreadId: Int = -1

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                manager = context.applicationContext
                    .getSystemService(android.os.PerformanceHintManager::class.java)
            } catch (t: Throwable) {
                // Some devices throw at lookup time. Treat as unsupported.
                manager = null
                lastErrorMessage = "lookup: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    /** True if the device exposes the PerformanceHintManager service at all. */
    val isSupported: Boolean get() = manager != null

    /** True once a session has been successfully created. */
    val isActive: Boolean get() = session != null

    /** Last target wall-clock budget we set on the session, in nanoseconds. */
    val currentTargetNs: Long get() = currentTargetNsValue

    /** Last error message captured (lookup, create, update, or report). */
    val lastError: String? get() = lastErrorMessage

    /**
     * Idempotent. On first call with a valid [threadId] + non-zero [targetNs]
     * creates the session; on subsequent calls retargets only if the target
     * changed (avoids spamming `updateTargetWorkDuration` when the audio
     * format is stable).
     */
    fun ensureSession(threadId: Int, targetNs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mgr = manager ?: return
        if (targetNs <= 0L || threadId <= 0) return
        try {
            val typedMgr = mgr as android.os.PerformanceHintManager
            var typedSession = session as? android.os.PerformanceHintManager.Session

            // TID swap: existing session is pinned to a dead thread, tear
            // it down so the create-branch below builds a fresh session for
            // the new TID. Close errors are swallowed — close is best-
            // effort. After this block we may be in the no-session state
            // and the create-branch handles it identically to first call.
            if (typedSession != null && threadId != currentThreadId) {
                runCatching { typedSession.close() }
                session = null
                typedSession = null
                currentTargetNsValue = 0L
                // Don't clear lastErrorMessage — preserve diagnostic history
                // across the swap. currentThreadId updates on successful
                // create below.
            }

            if (typedSession == null) {
                val created = typedMgr.createHintSession(intArrayOf(threadId), targetNs)
                if (created != null) {
                    session = created
                    currentTargetNsValue = targetNs
                    currentThreadId = threadId
                }
            } else if (targetNs != currentTargetNsValue) {
                typedSession.updateTargetWorkDuration(targetNs)
                currentTargetNsValue = targetNs
            }
        } catch (t: Throwable) {
            // Drop the session on error; the audio thread will keep running
            // unimpaired and we won't keep retrying every buffer.
            session = null
            currentThreadId = -1
            lastErrorMessage = "ensure: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * Report actual wall-clock spent on the just-completed buffer. No-op if
     * the session hasn't been created yet (the OS gets nothing useful from
     * a report without a target). Cheap — JNI call into native scheduler.
     */
    fun reportActual(actualNs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val s = session ?: return
        if (actualNs <= 0L) return
        try {
            (s as android.os.PerformanceHintManager.Session)
                .reportActualWorkDuration(actualNs)
        } catch (t: Throwable) {
            // Don't log per-buffer — would spam the breadcrumb log if the
            // service is broken. Just remember the most recent error.
            lastErrorMessage = "report: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** Releases the session. Safe to call repeatedly. */
    fun close() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            (session as? android.os.PerformanceHintManager.Session)?.close()
        } catch (_: Throwable) {
            // Closing a session is best-effort; ignore errors.
        }
        session = null
        currentTargetNsValue = 0L
        currentThreadId = -1
    }
}
