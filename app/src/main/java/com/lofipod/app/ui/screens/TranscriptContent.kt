package com.lofipod.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.scripture.DetectedRef
import com.lofipod.app.scripture.ScriptureRef
import kotlinx.coroutines.launch

/**
 * Shared paragraph renderer used by both the in-Player [TranscriptTab] and
 * the full-page [TranscriptScreen]. Renders cached or freshly-fetched
 * transcript paragraphs with scripture references highlighted in primary
 * color and tappable — taps fire `Intent.ACTION_VIEW` for the matching
 * `ref.ly` URL, which Logos (or any installed Bible app that handles
 * `ref.ly`) intercepts.
 *
 * No WebView — text is rendered via Compose `Text` + `AnnotatedString` only.
 */
@Composable
fun TranscriptContent(
    episodeGuid: String?,
    showFullPageButton: Boolean,
    onOpenFullPage: () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
) {
    if (episodeGuid == null) {
        EmptyTab("Nothing playing.")
        return
    }
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val scope = rememberCoroutineScope()

    var loading by remember(episodeGuid) { mutableStateOf(true) }
    var paragraphs by remember(episodeGuid) { mutableStateOf<List<String>>(emptyList()) }
    var sourceUrl by remember(episodeGuid) { mutableStateOf<String?>(null) }
    var errorText by remember(episodeGuid) { mutableStateOf<String?>(null) }
    var transcriptUrl by remember(episodeGuid) { mutableStateOf<String?>(null) }
    var selectorHint by remember(episodeGuid) { mutableStateOf<String?>(null) }

    LaunchedEffect(episodeGuid) {
        val kabod = app.db.episodeKabodDao().get(episodeGuid)
        transcriptUrl = kabod?.transcriptUrl
        selectorHint = kabod?.transcriptSelector
        if (transcriptUrl.isNullOrBlank()) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        errorText = null
        try {
            val result = app.transcripts.loadFor(
                guid = episodeGuid,
                sourceUrl = transcriptUrl!!,
                selectorHint = selectorHint,
            )
            if (result == null) {
                errorText = "Couldn't extract transcript text from the page."
            } else {
                paragraphs = result.paragraphs
                sourceUrl = result.sourceUrl
            }
        } catch (e: Exception) {
            errorText = e.message ?: "Failed to load transcript."
        } finally {
            loading = false
        }
    }

    when {
        transcriptUrl.isNullOrBlank() -> EmptyTab("No transcript available for this episode.")
        loading -> LoadingTranscript()
        errorText != null -> TranscriptError(errorText!!) {
            scope.launch {
                loading = true
                errorText = null
                try {
                    val r = app.transcripts.loadFor(
                        guid = episodeGuid,
                        sourceUrl = transcriptUrl!!,
                        selectorHint = selectorHint,
                        forceRefresh = true,
                    )
                    if (r == null) errorText = "Couldn't extract transcript text from the page."
                    else { paragraphs = r.paragraphs; sourceUrl = r.sourceUrl }
                } catch (e: Exception) {
                    errorText = e.message ?: "Failed to load transcript."
                } finally {
                    loading = false
                }
            }
        }
        else -> LoadedTranscript(
            paragraphs = paragraphs,
            sourceUrl = sourceUrl,
            showFullPageButton = showFullPageButton,
            onOpenFullPage = onOpenFullPage,
            onScriptureClick = { ref ->
                val url = ScriptureRef.buildRefLyUrl(
                    book = ref.book,
                    startCh = ref.startCh,
                    startV = ref.startV,
                    endCh = ref.endCh,
                    endV = ref.endV,
                )
                if (url == null) {
                    scope.launch { snackbarHostState?.showSnackbar("Couldn't build a Bible reference for that link.") }
                } else {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    try {
                        ctx.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        scope.launch {
                            snackbarHostState?.showSnackbar("No Bible app installed to open ${ref.book} ${ref.startCh}${if (ref.startV != null) ":${ref.startV}" else ""}.")
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun LoadingTranscript() {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("Loading transcript…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TranscriptError(message: String, onRetry: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(4.dp))
            Text("Retry")
        }
    }
}

@Composable
private fun LoadedTranscript(
    paragraphs: List<String>,
    sourceUrl: String?,
    showFullPageButton: Boolean,
    onOpenFullPage: () -> Unit,
    onScriptureClick: (DetectedRef) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showFullPageButton) {
            item {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${paragraphs.size} paragraphs",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    IconButton(onClick = onOpenFullPage) {
                        Icon(
                            Icons.Filled.OpenInFull,
                            contentDescription = "Read full page",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
        items(paragraphs) { para ->
            Text(
                text = annotateScripture(para, onScriptureClick),
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.5f
                ),
            )
        }
        if (sourceUrl != null) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Source: $sourceUrl",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun annotateScripture(
    text: String,
    onScriptureClick: (DetectedRef) -> Unit,
): AnnotatedString {
    val refs = ScriptureRef.detectInText(text)
    if (refs.isEmpty()) return AnnotatedString(text)
    val primary = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var cursor = 0
        for (r in refs) {
            if (r.range.first > cursor) append(text.substring(cursor, r.range.first))
            withLink(
                LinkAnnotation.Clickable(
                    tag = "scripture-${r.book}-${r.startCh}-${r.startV ?: 0}",
                    styles = TextLinkStyles(
                        style = SpanStyle(color = primary, fontWeight = FontWeight.SemiBold)
                    ),
                    linkInteractionListener = { onScriptureClick(r) }
                )
            ) {
                append(text.substring(r.range.first, r.range.last + 1))
            }
            cursor = r.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

@Composable
private fun EmptyTab(message: String) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
