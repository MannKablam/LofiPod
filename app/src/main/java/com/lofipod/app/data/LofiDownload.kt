package com.lofipod.app.data

/**
 * App-side download state, replacing Media3's `Download` type. We own this so
 * the UI doesn't ride on a third-party type with quirks (no equals override,
 * shared-mutable progress field, listener that doesn't fire on byte progress)
 * and so the rest of the app can treat downloads as plain data.
 *
 * State machine mirrors the meaningful slice of Media3's for visual continuity:
 *
 *   - **QUEUED**: queued behind another download (parallelism cap), no bytes
 *     yet. Visually a spinner.
 *   - **DOWNLOADING**: actively pulling bytes. Render with [percent] +
 *     [bytesDownloaded] / [contentLength].
 *   - **COMPLETED**: file fully on disk at [filePath]. The player can play it
 *     locally instead of streaming.
 *   - **FAILED**: hit a non-recoverable error. [errorMessage] holds the user-
 *     visible reason (HTTP code, IO message, etc).
 *
 * No "REMOVING" / "RESTARTING" — those were Media3 bookkeeping leaks; the new
 * downloader just deletes the row + file synchronously on remove and starts
 * fresh on retry.
 */
data class LofiDownload(
    val guid: String,
    val state: State,
    val bytesDownloaded: Long = 0L,
    val contentLength: Long = -1L,
    val filePath: String? = null,
    val errorMessage: String? = null,
) {
    /** 0..100, or -1 when [contentLength] is unknown (chunked/no Content-Length). */
    val percent: Float
        get() = if (contentLength > 0L) {
            (bytesDownloaded.toFloat() / contentLength.toFloat()) * 100f
        } else {
            -1f
        }

    enum class State { QUEUED, DOWNLOADING, COMPLETED, FAILED }
}
