package com.lofipod.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
import com.lofipod.app.data.Sources
import com.lofipod.app.data.model.Podcast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Kabod pack list — where the individual pack cards live now that
 * the catalog collapses them under [GoldKabodCard] (v0.11). Renders the
 * same [KabodPackRow] cards the catalog used to inline, in
 * [Sources.KABOD_PACKS] order, resolving each pack from the in-memory
 * repo cache with an asset-loader fallback (same cold-start-safe pattern
 * as EpisodesScreen: deep links / process-death restore can land here
 * before the catalog hydrated the cache).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KabodPacksScreen(
    onBack: () -> Unit,
    onPackClick: (Podcast) -> Unit,
) {
    val app = LocalContext.current.applicationContext as LofiPodApp

    var packs by remember { mutableStateOf<List<Podcast>>(emptyList()) }
    LaunchedEffect(Unit) {
        packs = withContext(Dispatchers.IO) {
            Sources.KABOD_PACKS.mapNotNull { entry ->
                app.repo.cached(entry.feedUrl)
                    ?: app.kabodLoader.loadIntoCache(entry.feedUrl)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "כבוד",
                            fontSize = 24.sp,
                            fontFamily = stamHebrewFamily,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Kabod packs")
                    }
                },
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
        if (packs.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Loading packs…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(packs, key = { it.feedUrl }) { pod ->
                    KabodPackRow(
                        pod = pod,
                        artworkUrl = pod.artworkUrl,
                        // New-episode badges are a live-feed affordance; the
                        // packs are archived bodies of work, so the badge is
                        // always 0 here (the catalog's gold card aggregates
                        // any "new" state instead).
                        newEpisodeCount = 0,
                        onClick = { onPackClick(pod) }
                    )
                }
            }
        }
    }
}
