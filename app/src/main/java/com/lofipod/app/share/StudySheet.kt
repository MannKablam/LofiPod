package com.lofipod.app.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.db.EpisodeNoteEntryEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Study sheets — the app's "your listening becomes an artifact" export.
 * Joins everything LofiPod already knows about an episode (identity,
 * speaker, scripture, the user's positioned notes, and — when cached —
 * the transcript) into one shareable markdown document.
 *
 * Entirely on-device: reads Room + the in-memory feed cache, writes a
 * .md into cacheDir/study_sheets/, shares via the existing FileProvider.
 */
enum class StudySheetMode {
    /** Header + notes. */
    NOTES_ONLY,

    /** Header + notes, each with the transcript paragraph nearest its
     *  playback position (proportional estimate — transcripts carry no
     *  timestamps). */
    NOTES_WITH_PARAGRAPHS,

    /** NOTES_WITH_PARAGRAPHS behavior for citations (by paragraph number,
     *  not duplicated text) + the full transcript appended. */
    NOTES_WITH_FULL_TRANSCRIPT,
}

/** Everything the renderer needs, pre-joined. Pure data. */
data class StudySheetData(
    val episodeTitle: String,
    val podcastTitle: String?,
    val speaker: String?,
    val pubDateMillis: Long?,
    val scripture: String?,
    val partNumber: Int?,
    val durationMs: Long,
    val notes: List<EpisodeNoteEntryEntity>,
    val transcriptParagraphs: List<String>?,
    val transcriptSourceUrl: String?,
    val transcriptFetchedAt: Long?,
)

object StudySheet {

    /**
     * Join episode_state + episode_kabod (+ pack) + feed cache + notes +
     * transcript for [guid]. Null when there's no episode_state row (the
     * sheet can't even be titled). Call on Dispatchers.IO.
     */
    suspend fun load(app: LofiPodApp, guid: String): StudySheetData? {
        val state = app.db.episodeStateDao().get(guid) ?: return null
        val kabod = app.db.episodeKabodDao().get(guid)
        val pack = kabod?.let { app.db.kabodPackDao().get(it.packId) }
        val pod = app.repo.cached(state.feedUrl)
        val ep = pod?.episodes?.find { it.guid == guid }
        // Study order is POSITION order (where in the sermon), not the
        // order notes were typed — they diverge when the user scrubs back
        // and adds an earlier-position note later. Tie-break on createdAt.
        val notes = app.db.episodeNoteEntryDao().getForEpisode(guid)
            .sortedWith(compareBy({ it.playbackPosMs }, { it.createdAt }))
        val transcriptRow = app.db.episodeTranscriptDao().get(guid)
        val paragraphs = transcriptRow?.let { row ->
            try {
                val arr = org.json.JSONArray(row.paragraphsJson)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                null
            }
        }
        val durationMs = state.durationMs.takeIf { it > 0 }
            ?: ep?.durationSeconds?.let { it * 1000L }
            ?: 0L
        return StudySheetData(
            episodeTitle = state.title,
            podcastTitle = pod?.title ?: pack?.title
                ?: if (state.feedUrl == "device://files") "Device file" else null,
            speaker = kabod?.speaker ?: pack?.speaker ?: pod?.author,
            pubDateMillis = ep?.pubDateMillis,
            scripture = kabod?.scripture,
            partNumber = kabod?.partNumber,
            durationMs = durationMs,
            notes = notes,
            transcriptParagraphs = paragraphs,
            transcriptSourceUrl = transcriptRow?.sourceUrl,
            transcriptFetchedAt = transcriptRow?.fetchedAt,
        )
    }

    /** Pure renderer: data + mode → markdown. */
    fun renderMarkdown(data: StudySheetData, mode: StudySheetMode): String = buildString {
        val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val titleSuffix = data.partNumber?.let { " (Part $it)" } ?: ""
        appendLine("# Study Sheet — ${data.episodeTitle}$titleSuffix")
        appendLine()
        data.podcastTitle?.let { appendLine("**Podcast:** $it  ") }
        data.speaker?.let { appendLine("**Speaker:** $it  ") }
        data.pubDateMillis?.let { appendLine("**Date:** ${dateFmt.format(Date(it))}  ") }
        data.scripture?.let { appendLine("**Scripture:** $it  ") }
        if (data.durationMs > 0) {
            appendLine("**Length:** ${data.durationMs / 60_000} min  ")
        }
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("## Notes")
        appendLine()
        if (data.notes.isEmpty()) {
            appendLine("*(No notes were taken for this episode.)*")
            appendLine()
        }
        val paragraphs = data.transcriptParagraphs
        val citeParagraphs = mode != StudySheetMode.NOTES_ONLY && paragraphs != null
        for (note in data.notes) {
            appendLine("### ${formatPos(note.playbackPosMs)}")
            appendLine()
            for (line in note.text.lines()) {
                appendLine("> ${escapeMarkdownPrefix(line)}")
            }
            appendLine()
            if (citeParagraphs) {
                val idx = paragraphIndexFor(
                    note.playbackPosMs, data.durationMs, paragraphs!!.size
                )
                if (idx != null) {
                    if (mode == StudySheetMode.NOTES_WITH_FULL_TRANSCRIPT) {
                        // Full transcript is appended below — cite by number
                        // instead of duplicating the text.
                        appendLine("*See ¶${idx + 1} in the transcript below (approximate).*")
                    } else {
                        appendLine("*Transcript near this point (approximate — matched by position):*")
                        appendLine()
                        appendLine("> ${escapeMarkdownPrefix(paragraphs[idx])}")
                    }
                    appendLine()
                }
            }
        }
        if (mode == StudySheetMode.NOTES_WITH_FULL_TRANSCRIPT && paragraphs != null) {
            appendLine("---")
            appendLine()
            appendLine("## Full Transcript")
            appendLine()
            val src = data.transcriptSourceUrl?.let { url ->
                val host = try {
                    android.net.Uri.parse(url).host ?: url
                } catch (_: Exception) {
                    url
                }
                val fetched = data.transcriptFetchedAt
                    ?.let { " · fetched ${dateFmt.format(Date(it))}" } ?: ""
                "> Source: $host$fetched"
            }
            src?.let {
                appendLine(it)
                appendLine()
            }
            paragraphs.forEachIndexed { i, p ->
                appendLine("¶${i + 1} — $p")
                appendLine()
            }
        }
        appendLine("---")
        appendLine()
        append("*Generated by LofiPod on ${dateFmt.format(Date())}.")
        if (citeParagraphs) {
            append(
                " Transcript citations are estimated from playback position" +
                    " (the transcript source provides no timestamps) and may" +
                    " be off by a paragraph or two."
            )
        }
        appendLine("*")
    }

    /**
     * Proportional note→paragraph mapping. Null when duration or the
     * paragraph list can't support even an estimate.
     */
    internal fun paragraphIndexFor(posMs: Long, durationMs: Long, paragraphCount: Int): Int? {
        if (durationMs <= 0 || paragraphCount <= 0) return null
        val idx = (posMs.toDouble() / durationMs * paragraphCount).toInt()
        return idx.coerceIn(0, paragraphCount - 1)
    }

    /** "study-sheet-<slug>.md", slug capped + sanitized for any filesystem. */
    fun fileNameFor(episodeTitle: String): String {
        val slug = episodeTitle.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)
        return if (slug.isBlank()) "study-sheet.md" else "study-sheet-$slug.md"
    }

    private fun formatPos(ms: Long): String {
        val totalSec = ms / 1000
        return "%02d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    }

    /** Keep user prose intact; only defuse line-leading markdown controls
     *  that would break the blockquote structure. */
    private fun escapeMarkdownPrefix(line: String): String =
        if (line.startsWith("#") || line.startsWith(">") || line.startsWith("```")) {
            "\\" + line
        } else line
}

/**
 * Write the sheet to cacheDir/study_sheets/ and fire a share chooser.
 * Stream-first (FileProvider content:// URI) with the markdown ALSO in
 * EXTRA_TEXT for text-only targets — unless the payload is large enough
 * to threaten the Binder transaction limit, in which case stream-only.
 */
fun Context.shareStudySheet(markdown: String, fileName: String, episodeTitle: String) {
    val dir = File(cacheDir, "study_sheets").apply { mkdirs() }
    // Cache hygiene: yesterday's sheets are stale exports, not documents.
    val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
    dir.listFiles()?.forEach { if (it.lastModified() < dayAgo) it.delete() }
    val file = File(dir, fileName)
    file.writeText(markdown)
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Study sheet — $episodeTitle")
        if (markdown.length < 200_000) putExtra(Intent.EXTRA_TEXT, markdown)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, "Share study sheet"))
}
