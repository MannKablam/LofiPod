package com.lofipod.app.data.model

data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String?,
    val description: String?,
    val artworkUrl: String?,
    val episodes: List<Episode>
)

data class Episode(
    val guid: String,           // unique within the feed
    val feedUrl: String,        // back-reference to its podcast
    val title: String,
    val description: String?,
    val pubDateMillis: Long?,   // epoch ms, null if unparsable
    val audioUrl: String,       // the enclosure URL — this is what we share
    val audioMimeType: String?,
    val durationSeconds: Long?, // null if not in feed
    val episodeArtworkUrl: String?  // many feeds set per-episode art
)
