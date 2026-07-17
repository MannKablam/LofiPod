@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
import com.lofipod.app.bible.BibleCanon
import com.lofipod.app.data.Settings
import com.lofipod.app.data.Sources
import com.lofipod.app.data.db.EpisodeScriptureEntity
import com.lofipod.app.data.model.Episode
import com.lofipod.app.data.model.Podcast
import com.lofipod.app.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Canon-order Bible-index browse. Logos-style. Tier hierarchy:
 *
 *   Books (color-coded grid by canonical group, grayed when no coverage)
 *     -> Chapters (numeric grid, grayed when no coverage)
 *       -> Sermons covering that chapter, with a "Play through from here"
 *          affordance that flips the canon-order autoplay flag and
 *          starts the chosen episode.
 *
 * v0.6.4 dropped the verse-grid layer that originally sat between
 * chapter and sermons — verse-level coverage was too sparse to be
 * useful given the description-only chapter detection most RSS feeds
 * provide. Chapter-level granularity matches what the auto-tagger can
 * reliably extract and what users want to navigate by.
 *
 * Excluded feeds (per [Settings.canonBrowseExcludedFeeds]) are filtered
 * out of the coverage data so the user can hide noisy or off-topic
 * sources from this view without removing them from the catalog.
 */
@Composable
fun CanonBrowseScreen(
    controller: PlayerController,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val settings = remember { Settings(app) }
    val scope = rememberCoroutineScope()

    val excluded by settings.canonBrowseExcludedFeeds.collectAsState(initial = emptySet())

    // Coverage is loaded once per (excluded, refreshTick) and grouped by book.
    // Tick bumps when the user comes back from playing something or toggles
    // a source filter so the grid reflects fresh data.
    var refreshTick by remember { mutableIntStateOf(0) }
    var coverage by remember { mutableStateOf<Coverage?>(null) }
    LaunchedEffect(excluded, refreshTick) {
        coverage = withContext(Dispatchers.IO) { loadCoverage(app, excluded) }
    }

    // BrowsePath holds a BibleCanon.Book reference (data class with
    // IntArray fields), which doesn't survive Bundle marshalling cleanly,
    // so we use plain `remember`. Rotation will reset to the book grid;
    // acceptable for v1.
    var path by remember { mutableStateOf(BrowsePath.empty()) }
    var sourceFilterOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            path.book == null -> "Bible index"
                            path.chapter == null -> path.book!!.canonicalName
                            else -> "${path.book!!.canonicalName} ${path.chapter}"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (path.isEmpty()) onBack() else path = path.pop()
                    }) {
                        Icon(
                            painterResource(R.drawable.arrow_back_24),
                            contentDescription = "Back",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { sourceFilterOpen = true }) {
                        Icon(
                            painterResource(R.drawable.tune_24),
                            contentDescription = "Source filter",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val cov = coverage
        if (cov == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                path.book == null -> BookGrid(
                    cov = cov,
                    onBookTap = { book ->
                        if (cov.bookCounts[book.canonicalName].let { it != null && it > 0 }) {
                            path = path.copy(book = book)
                        }
                    },
                )
                path.chapter == null -> ChapterGrid(
                    book = path.book!!,
                    cov = cov,
                    onChapterTap = { ch -> path = path.copy(chapter = ch) },
                )
                else -> SermonsForChapter(
                    book = path.book!!,
                    chapter = path.chapter!!,
                    onPlayCanonOrder = { episode ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                settings.setCanonAutoplayEnabled(true)
                            }
                            controller.playEpisode(
                                ep = episode,
                                podcastTitle = "",
                                podcastArt = episode.episodeArtworkUrl,
                            )
                        }
                    },
                    onPlayOnce = { episode ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                // Manual play of one episode: ensure canon-autoplay
                                // doesn't carry over from a prior selection.
                                settings.setCanonAutoplayEnabled(false)
                            }
                            controller.playEpisode(
                                ep = episode,
                                podcastTitle = "",
                                podcastArt = episode.episodeArtworkUrl,
                            )
                        }
                    },
                )
            }
        }
    }

    if (sourceFilterOpen) {
        SourceFilterDialog(
            current = excluded,
            onClose = { sourceFilterOpen = false },
            onSave = { newSet ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        settings.setCanonBrowseExcludedFeeds(newSet)
                    }
                    refreshTick++
                }
                sourceFilterOpen = false
            },
        )
    }
}

/** Path through the browse hierarchy. Plain remember (not rememberSaveable)
 *  because BibleCanon.Book has IntArray fields that don't bundle cleanly. */
private data class BrowsePath(
    val book: BibleCanon.Book? = null,
    val chapter: Int? = null,
) {
    fun isEmpty(): Boolean = book == null
    fun pop(): BrowsePath = when {
        chapter != null -> copy(chapter = null)
        else -> empty()
    }
    companion object { fun empty() = BrowsePath() }
}

/**
 * Per-feed-excluded coverage snapshot. Built once per filter change, then
 * the grids consult the maps directly without re-querying the DB. v0.6.4
 * dropped the verse-grid layer per user feedback (verse-level was too
 * sparse to be useful given description-only chapter detection).
 */
private data class Coverage(
    /** book canonical name → number of chapter-resolved episodes for that book. */
    val bookCounts: Map<String, Int>,
    /** book → set of chapter numbers with at least one covered episode. */
    val chaptersByBook: Map<String, Set<Int>>,
)

private suspend fun loadCoverage(app: LofiPodApp, excluded: Set<String>): Coverage {
    val all = app.db.episodeScriptureDao().getAll()
    val excludedGuids: Set<String> = if (excluded.isEmpty()) emptySet() else {
        // Resolve guid → feedUrl from episode_state to filter rows whose
        // feed is excluded. One pass; cache the lookup map locally.
        val states = app.db.episodeStateDao().getAll()
        states.filter { it.feedUrl in excluded }.map { it.guid }.toSet()
    }
    val filtered = if (excludedGuids.isEmpty()) all else all.filter { it.guid !in excludedGuids }
    val bookCounts = HashMap<String, Int>()
    val chapters = HashMap<String, MutableSet<Int>>()
    for (row in filtered) {
        // A book only "counts" (= renders highlighted in the book grid)
        // when it has a chapter-resolved row. Without this guard, a row
        // that detected a book name but no chapter (e.g., a Kabod pack
        // where scriptureStartCh was null, or a sloppy regex hit) would
        // mark the book as covered but every chapter cell stays gray —
        // user reports tapping a "highlighted" book and seeing nothing.
        val sCh = row.startCh ?: continue
        val eCh = row.endCh ?: sCh
        bookCounts[row.book] = (bookCounts[row.book] ?: 0) + 1
        for (ch in sCh..eCh) {
            chapters.getOrPut(row.book) { mutableSetOf() }.add(ch)
        }
    }
    return Coverage(bookCounts, chapters)
}

@Composable
private fun BookGrid(
    cov: Coverage,
    onBookTap: (BibleCanon.Book) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(BibleCanon.BOOKS, key = { it.canonicalName }) { book ->
            val count = cov.bookCounts[book.canonicalName] ?: 0
            BookCell(book = book, count = count, onClick = { onBookTap(book) })
        }
    }
}

@Composable
private fun BookCell(book: BibleCanon.Book, count: Int, onClick: () -> Unit) {
    val active = count > 0
    val baseColor = groupColor(book.group)
    val bg = if (active) baseColor else baseColor.copy(alpha = 0.18f)
    val ink = if (active) Color.White.copy(alpha = 0.95f)
              else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .let { if (active) it.clickable(onClick = onClick) else it }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                book.canonicalName,
                color = ink,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
            if (active) {
                Text("$count", color = ink, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun ChapterGrid(
    book: BibleCanon.Book,
    cov: Coverage,
    onChapterTap: (Int) -> Unit,
) {
    val coveredChapters = cov.chaptersByBook[book.canonicalName] ?: emptySet()
    val baseColor = groupColor(book.group)
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items((1..book.chapterCount).toList(), key = { it }) { ch ->
            val active = ch in coveredChapters
            NumericCell(
                label = "$ch",
                active = active,
                accent = baseColor,
                onClick = { if (active) onChapterTap(ch) },
            )
        }
    }
}


@Composable
private fun NumericCell(
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val bg = if (active) accent else accent.copy(alpha = 0.10f)
    val ink = if (active) Color.White
              else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .let { if (active) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SermonsForChapter(
    book: BibleCanon.Book,
    chapter: Int,
    onPlayCanonOrder: (Episode) -> Unit,
    onPlayOnce: (Episode) -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    var rows by remember { mutableStateOf<List<EnrichedSermon>>(emptyList()) }
    LaunchedEffect(book, chapter) {
        rows = withContext(Dispatchers.IO) {
            // Pull every row tagged for this book; filter to those whose
            // [startCh, endCh] range overlaps the requested chapter.
            // Sort by start chapter+verse so the user gets the natural
            // canonical order — the same order the smart-queue resolver
            // walks at end-of-stream.
            val all = app.db.episodeScriptureDao().forBook(book.canonicalName)
            val inChapter = all.filter { row ->
                val sCh = row.startCh ?: return@filter false
                val eCh = row.endCh ?: sCh
                chapter in sCh..eCh
            }
            val out = ArrayList<EnrichedSermon>(inChapter.size)
            for (m in inChapter) {
                val state = app.db.episodeStateDao().get(m.guid) ?: continue
                val podcast: Podcast? = app.repo.cached(state.feedUrl)
                val ep = podcast?.episodes?.firstOrNull { it.guid == m.guid }
                if (ep != null) {
                    out.add(EnrichedSermon(ep, podcast.title, m))
                }
            }
            out.sortedWith(compareBy(
                { it.scripture.startCh ?: Int.MAX_VALUE },
                { it.scripture.startV ?: Int.MAX_VALUE },
            ))
        }
    }
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No sermons covering this chapter yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(rows, key = { it.episode.guid }) { row ->
            SermonRow(
                row = row,
                onPlayCanonOrder = { onPlayCanonOrder(row.episode) },
                onPlayOnce = { onPlayOnce(row.episode) },
            )
        }
    }
}

private data class EnrichedSermon(
    val episode: Episode,
    val podcastTitle: String,
    val scripture: EpisodeScriptureEntity,
)

@Composable
private fun SermonRow(
    row: EnrichedSermon,
    onPlayCanonOrder: () -> Unit,
    onPlayOnce: () -> Unit,
) {
    val sc = row.scripture
    val ref = buildString {
        append(sc.book)
        if (sc.startCh != null) {
            append(' '); append(sc.startCh)
            if (sc.startV != null) {
                append(':'); append(sc.startV)
                if (sc.endV != null && (sc.endV != sc.startV || (sc.endCh != null && sc.endCh != sc.startCh))) {
                    append('-')
                    if (sc.endCh != null && sc.endCh != sc.startCh) {
                        append(sc.endCh); append(':')
                    }
                    append(sc.endV)
                }
            }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(row.episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Spacer(Modifier.height(2.dp))
            Text(
                "$ref · ${row.podcastTitle}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPlayCanonOrder, modifier = Modifier.weight(1f)) {
                    Text("Play through from here", fontSize = 12.sp, maxLines = 1)
                }
                OutlinedButton(onClick = onPlayOnce) {
                    Text("Just this", fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SourceFilterDialog(
    current: Set<String>,
    onClose: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    var selected by remember { mutableStateOf(current) }
    val sources = remember { Sources.ALL }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Sources for canon browse") },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Uncheck a source to hide its sermons from the Bible index. " +
                        "The catalog and direct playback are unaffected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                for (src in sources) {
                    val isIncluded = src.feedUrl !in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (isIncluded) selected + src.feedUrl
                                           else selected - src.feedUrl
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = isIncluded, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        val title = app.repo.cached(src.feedUrl)?.title
                            ?: src.displayName
                            ?: src.feedUrl.take(40)
                        Text(title, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Cancel") }
        },
    )
}

/**
 * Maps a canonical group to a tonal accent. Picked once here rather than
 * in the theme so the Bible index has a stable identity even when the
 * user switches visual directions (Lowlight, Cassette, etc.). The values
 * are inspired by traditional Christian publishing color associations
 * (royal blue for prophets, scarlet for the Gospels, etc.) without
 * cloning Logos directly.
 */
private fun groupColor(group: BibleCanon.Group): Color = when (group) {
    BibleCanon.Group.PENTATEUCH -> Color(0xFF8B6F47)        // earth / sand
    BibleCanon.Group.HISTORICAL -> Color(0xFF4A6741)        // forest olive
    BibleCanon.Group.WISDOM -> Color(0xFFB48638)            // amber gold
    BibleCanon.Group.MAJOR_PROPHETS -> Color(0xFF2D4F7C)    // royal blue
    BibleCanon.Group.MINOR_PROPHETS -> Color(0xFF3F7B8E)    // teal
    BibleCanon.Group.GOSPELS -> Color(0xFFA52A2A)           // scarlet
    BibleCanon.Group.ACTS -> Color(0xFFD4A017)              // pentecost yellow
    BibleCanon.Group.PAULINE -> Color(0xFF6A4C93)           // violet
    BibleCanon.Group.GENERAL_EPISTLES -> Color(0xFF4F7349)  // sage green
    BibleCanon.Group.REVELATION -> Color(0xFF3D2B56)        // deep purple
}
