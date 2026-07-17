package com.lofipod.app.player

import kotlinx.coroutines.flow.MutableStateFlow

/** One embedded chapter (ID3 CHAP) of the current media item. */
data class PodChapter(val title: String?, val startMs: Long)

/** Chapters keyed to the media item they were read from. */
data class ChapterPayload(val mediaId: String, val chapters: List<PodChapter>)

/**
 * Same-process bridge for ID3 chapters, following the sharedEq companion
 * pattern: the REAL ExoPlayer lives in PlaybackService, and Format.metadata
 * does not survive the MediaController binder marshaling (deliberately
 * omitted — it can carry megabytes of APIC art), so the service reads
 * ChapterFrames from its own player's tracks and publishes them here.
 * PlayerController folds the payload into PlayerState for the UI.
 */
object ChapterBridge {
    val chapters = MutableStateFlow<ChapterPayload?>(null)
}
