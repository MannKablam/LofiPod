package com.lofipod.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
import com.lofipod.app.data.Sources
import com.lofipod.app.parser.SourceEntry
import com.lofipod.app.parser.SourceGroup

/**
 * Read-only view of the app's internal sources document — the [Sources]
 * object that defines the catalog's canon. There is deliberately no
 * in-app editing (the canon changes by editing Sources.kt, committing,
 * building, sideloading); this screen just makes the current contents
 * inspectable without a laptop. Text is selectable for copying URLs out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LofiPodApp

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sources") },
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
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                "The catalog's canon, as compiled into this build. Read-only: " +
                    "sources change with app updates, not in-app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            SelectionContainer {
                Column {
                    SourcesSectionLabel("Kabod packs (${Sources.KABOD_PACKS.size})")
                    Sources.KABOD_PACKS.forEach { entry ->
                        SourceLine(
                            // Resolve the pack's own title from the repo cache
                            // when it's loaded; the raw feedUrl still shows below.
                            name = entry.displayName
                                ?: app.repo.cached(entry.feedUrl)?.title
                                ?: entry.feedUrl.removePrefix("kabod://"),
                            url = entry.feedUrl,
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    SourcesSectionLabel("Podcast feeds")
                    Sources.PODCASTS.forEach { item ->
                        when (item) {
                            is SourceEntry -> SourceLine(
                                name = item.displayName
                                    ?: app.repo.cached(item.feedUrl)?.title
                                    ?: "(title from feed)",
                                url = item.feedUrl,
                            )
                            is SourceGroup -> {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    item.groupName,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                item.children.forEach { child ->
                                    SourceLine(
                                        name = child.displayName
                                            ?: app.repo.cached(child.feedUrl)?.title
                                            ?: "(title from feed)",
                                        url = child.feedUrl,
                                        indent = true,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SourcesSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SourceLine(name: String, url: String, indent: Boolean = false) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 16.dp else 0.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(
            url,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
