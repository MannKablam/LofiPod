@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
import com.lofipod.app.data.model.Episode
import com.lofipod.app.data.model.Podcast
import com.lofipod.app.player.PlayerController

/**
 * Live search across all cached episode titles. Results show podcast title +
 * episode title; tap to play. Searches in-memory only — never hits the
 * network or DB on the search path, so typing is instantaneous even with
 * dozens of feeds loaded.
 *
 * "Cached" here means "fetched in the current Catalog load." Feeds the user
 * hasn't loaded since opening the app aren't searchable yet — the empty-
 * state nudges them to open Catalog to populate the cache.
 */
@Composable
fun EpisodeSearchScreen(
    controller: PlayerController,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LofiPodApp
    var query by remember { mutableStateOf("") }

    // Snapshot the cache once on entry. Re-snapping on every search keystroke
    // would be wasteful — the cache only changes when Catalog refreshes,
    // which the user has to navigate to anyway.
    val allPods = remember { app.repo.allCached() }
    val totalEpisodeCount = remember(allPods) { allPods.sumOf { it.episodes.size } }

    val results: List<Match> = remember(query, allPods) {
        if (query.isBlank()) emptyList()
        else {
            val needle = query.trim().lowercase()
            buildList {
                for (pod in allPods) {
                    for (ep in pod.episodes) {
                        if (ep.title.lowercase().contains(needle)) {
                            add(Match(pod, ep))
                            // Cap so a wildcard-ish search doesn't render
                            // hundreds of rows. 200 is plenty for a-needle-
                            // in-the-feed-canon use case.
                            if (size >= 200) return@buildList
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search episodes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.arrow_back_24),
                            contentDescription = "Back",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = {
                    Text("Search $totalEpisodeCount episode${if (totalEpisodeCount == 1) "" else "s"}…")
                },
                leadingIcon = { Icon(painterResource(R.drawable.search_24), contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(painterResource(R.drawable.close_24), contentDescription = "Clear")
                        }
                    }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )

            when {
                allPods.isEmpty() -> EmptyState(
                    "No feeds in the cache yet. Open Catalog and let it load, then come back."
                )

                query.isBlank() -> EmptyState(
                    "Type to search across $totalEpisodeCount cached episode${if (totalEpisodeCount == 1) "" else "s"}."
                )

                results.isEmpty() -> EmptyState("No matches.")

                else -> {
                    Text(
                        "${results.size} match${if (results.size == 1) "" else "es"}" +
                            if (results.size >= 200) " (capped)" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    val highlightBg = MaterialTheme.colorScheme.primary
                    val highlightFg = MaterialTheme.colorScheme.onPrimary
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(results, key = { it.episode.guid }) { match ->
                            ResultCard(
                                match = match,
                                highlighted = highlightedTitle(
                                    match.episode.title, query, highlightBg, highlightFg
                                ),
                                onPlay = {
                                    controller.playEpisode(
                                        match.episode,
                                        match.podcast.title,
                                        match.podcast.artworkUrl
                                    )
                                    onOpenPlayer()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    match: Match,
    highlighted: AnnotatedString,
    onPlay: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    match.podcast.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(highlighted, style = MaterialTheme.typography.titleSmall, maxLines = 3)
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                painterResource(R.drawable.play_circle_24),
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(24.dp)
        )
    }
}

/**
 * Build an AnnotatedString that paints every case-insensitive occurrence of
 * [needle] in [source] with [highlightBg] background + [highlightFg]
 * foreground. Mirrors the helper in NotesBrowserScreen so the visual reads
 * the same across both search surfaces.
 */
private fun highlightedTitle(
    source: String,
    needle: String,
    highlightBg: androidx.compose.ui.graphics.Color,
    highlightFg: androidx.compose.ui.graphics.Color
): AnnotatedString {
    if (needle.isBlank()) return AnnotatedString(source)
    return buildAnnotatedString {
        var cursor = 0
        val lower = source.lowercase()
        val target = needle.lowercase()
        while (cursor < source.length) {
            val hit = lower.indexOf(target, cursor)
            if (hit < 0) {
                append(source.substring(cursor))
                break
            }
            if (hit > cursor) append(source.substring(cursor, hit))
            withStyle(SpanStyle(background = highlightBg, color = highlightFg)) {
                append(source.substring(hit, hit + needle.length))
            }
            cursor = hit + needle.length
        }
    }
}

private data class Match(val podcast: Podcast, val episode: Episode)
