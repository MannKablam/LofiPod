package com.lofipod.app.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Lightweight phase-timing telemetry for app startup. Records (name,
 * startNs, endNs) tuples for key initialization steps so the diagnostics
 * screen can show "where did the time go" for slow cold starts.
 *
 * Designed for the report flow: a user with a slow device opens
 * Settings -> Audio diagnostics, scrolls to the Startup section, and
 * either eyeballs the slowest phase or hits "Copy to clipboard" to
 * share the readout. The dev compares against their own readout to
 * pinpoint the regression.
 *
 * Threading: [record] is safe from any thread (atomic StateFlow update);
 * [phase] / [phaseAsync] are inlined timing wrappers. Reads from the UI
 * use [phases] (a StateFlow) or [snapshot] (one-shot).
 *
 * Lifetime: singleton, lives for the process. Records persist across
 * screen navigation but are wiped when the process dies.
 *
 * Cost: each [record] call is one StateFlow update (an atomic CAS over
 * a copied list). Negligible for the dozens of phases we record per
 * startup. Avoid calling from hot loops.
 */
object StartupTimings {

    /** One recorded phase. [durationMs] is the wall-clock duration. */
    data class Phase(
        val name: String,
        val startNs: Long,
        val endNs: Long,
    ) {
        val durationMs: Double get() = (endNs - startNs) / 1_000_000.0
    }

    /**
     * Wallclock at this object's class-load — the closest proxy we have
     * for "process start." Captured eagerly when StartupTimings is first
     * touched, which on a typical cold start is from
     * [com.lofipod.app.LofiPodApp]'s static init or [onCreate].
     */
    val processStartNs: Long = System.nanoTime()

    private val _phases = MutableStateFlow<List<Phase>>(emptyList())
    val phases: StateFlow<List<Phase>> = _phases.asStateFlow()

    /** Append a phase entry. Safe from any thread. */
    fun record(name: String, startNs: Long, endNs: Long = System.nanoTime()) {
        _phases.update { it + Phase(name, startNs, endNs) }
    }

    /**
     * Inline timing helper for synchronous blocks. Records the phase
     * even if [block] throws, so failures still show up in the
     * diagnostics readout.
     */
    inline fun <T> phase(name: String, block: () -> T): T {
        val t0 = System.nanoTime()
        return try {
            block()
        } finally {
            record(name, t0)
        }
    }

    /** One-shot snapshot of all recorded phases, sorted by start time. */
    fun snapshot(): List<Phase> = _phases.value.sortedBy { it.startNs }

    /** Reset the recorded phases. Test-only; not exposed in production UI. */
    internal fun reset() {
        _phases.value = emptyList()
    }
}
