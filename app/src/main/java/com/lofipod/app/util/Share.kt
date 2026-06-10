package com.lofipod.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * Send the raw audio enclosure URL out via the OS share sheet.
 * Recipients can paste this into any other podcast app or download it directly.
 */
fun Context.shareEnclosure(audioUrl: String, episodeTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, episodeTitle)
        putExtra(Intent.EXTRA_TEXT, audioUrl)
    }
    startActivity(Intent.createChooser(send, "Share episode link").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

/**
 * LofiPod deep-link plumbing for sharing a note together with its episode +
 * playback position. A receiving LofiPod instance opens the episode and
 * starts playback at the note's position.
 *
 * Link shape: `lofipod://note?feed=<enc>&guid=<enc>&t=<positionMs>`
 *
 * Two ingestion paths on the receiving side (both wired in MainActivity):
 *   - Tapping the link directly (ACTION_VIEW, scheme `lofipod`) in apps
 *     that linkify custom schemes.
 *   - Sharing the whole message INTO LofiPod (ACTION_SEND text/plain) for
 *     apps that don't — [extractNoteLink] pulls the lofipod:// URI out of
 *     the surrounding prose.
 */
object NoteLinks {
    const val SCHEME = "lofipod"
    const val HOST_NOTE = "note"

    data class NoteLink(val feedUrl: String, val guid: String, val positionMs: Long)

    fun build(feedUrl: String, guid: String, positionMs: Long): String {
        val f = URLEncoder.encode(feedUrl, "UTF-8")
        val g = URLEncoder.encode(guid, "UTF-8")
        return "$SCHEME://$HOST_NOTE?feed=$f&guid=$g&t=$positionMs"
    }

    /** Parse a lofipod://note URI; null when malformed or not a note link. */
    fun parse(uri: Uri?): NoteLink? {
        if (uri == null) return null
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        if (!HOST_NOTE.equals(uri.host, ignoreCase = true)) return null
        // Uri.getQueryParameter already URL-decodes.
        val feed = uri.getQueryParameter("feed")?.takeIf { it.isNotBlank() } ?: return null
        val guid = uri.getQueryParameter("guid")?.takeIf { it.isNotBlank() } ?: return null
        val t = uri.getQueryParameter("t")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        return NoteLink(feed, guid, t)
    }

    /** Find a lofipod:// link inside free text (an inbound ACTION_SEND). */
    fun extractNoteLink(text: String?): NoteLink? {
        if (text.isNullOrBlank()) return null
        val match = Regex("$SCHEME://\\S+").find(text) ?: return null
        return parse(Uri.parse(match.value.trimEnd('.', ',', ')', ']')))
    }
}

/**
 * Share a note's text + a deep link back to its (episode, position) via the
 * OS share sheet. The plain-text body keeps the note readable for anyone;
 * the trailing link is what a receiving LofiPod instance acts on.
 */
fun Context.shareNoteWithLink(
    noteText: String,
    episodeTitle: String,
    feedUrl: String,
    guid: String,
    positionMs: Long,
    positionLabel: String,
) {
    val link = NoteLinks.build(feedUrl, guid, positionMs)
    val body = buildString {
        append('"').append(noteText).append('"')
        append("\n\n")
        append(episodeTitle).append(" — at ").append(positionLabel)
        append("\n")
        append("Open in LofiPod: ").append(link)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Note on: $episodeTitle")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    startActivity(Intent.createChooser(send, "Share note").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
