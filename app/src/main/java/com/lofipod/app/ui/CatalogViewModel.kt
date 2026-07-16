package com.lofipod.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.PodcastRepository.FeedStatus
import com.lofipod.app.data.Sources
import com.lofipod.app.data.model.Podcast
import com.lofipod.app.parser.SourceEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reads the podcast canon from [Sources.PODCASTS] (compiled in) and fetches each
 * feed. There is no in-app way to add or remove podcasts — by design.
 */
class CatalogViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as LofiPodApp).repo

    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { loadCanon(force = false) }
    }

    /** User-triggered refresh from the overflow menu. Forces a network round trip. */
    fun refresh() {
        viewModelScope.launch { loadCanon(force = true) }
    }

    /**
     * Refresh ONE feed — the catalog card's long-press action. Fetches
     * just that source (network for RSS, asset re-parse for kabod://)
     * and swaps its entry in [state] in place, leaving every other
     * card and the global loading machinery untouched. [onResult]
     * receives the fresh podcast, or null when the fetch failed, on
     * the main dispatcher — the card's snackbar feedback hangs off it.
     */
    fun refreshOne(src: SourceEntry, onResult: (Podcast?) -> Unit = {}) {
        viewModelScope.launch {
            val pod = try {
                repo.fetchOne(src)
            } catch (_: Exception) {
                null
            }
            if (pod != null) {
                _state.update { current ->
                    val replaced = current.podcasts.map {
                        if (it.feedUrl == pod.feedUrl) pod else it
                    }
                    current.copy(
                        podcasts = if (replaced.any { it.feedUrl == pod.feedUrl }) replaced
                        else replaced + pod
                    )
                }
            }
            onResult(pod)
        }
    }

    /**
     * Stale-while-revalidate. On any call (init or refresh), surface
     * whatever the in-memory + disk caches hold IMMEDIATELY so the
     * Catalog renders without waiting on the network. Then trigger a
     * network refresh in the background and update the UI when each
     * feed lands.
     *
     * The disk cache (populated by [PodcastRepository.diskCache] writes
     * on each successful fetchOne) means a cold start renders the
     * Catalog from disk in milliseconds — no more "loading feeds"
     * stall while every RSS gets re-fetched, which on slower devices
     * (Pixel 7 reports) was taking long enough to feel broken.
     *
     * [force] is now informational only — we always refresh, and the
     * cached snapshot always shows first if non-empty. Kept as a param
     * so the overflow-menu Refresh action explicitly says "refresh now"
     * without changing behaviour.
     */
    private suspend fun loadCanon(force: Boolean) {
        val sources = Sources.ALL
        if (sources.isEmpty()) {
            _state.value = CatalogUiState()
            return
        }
        // 1) Hydrate the in-memory cache from disk (idempotent — first
        //    call reads the JSON files, subsequent calls are no-ops).
        repo.hydrateFromDisk()
        // 2) Surface whatever's cached right now. Even partial coverage
        //    is better than blank: render what we have, mark loading
        //    only for sources we don't have yet.
        val cachedNow = sources.mapNotNull { repo.cached(it.feedUrl) }
        if (cachedNow.isNotEmpty()) {
            _state.value = CatalogUiState(
                podcasts = cachedNow,
                loading = cachedNow.size < sources.size,
                feedProgress = sources.map { src ->
                    val cached = repo.cached(src.feedUrl)
                    FeedLoadStatus(
                        source = src,
                        status = if (cached != null) FeedStatus.OK else FeedStatus.LOADING,
                        errorMessage = null,
                    )
                }
            )
        } else {
            _state.value = CatalogUiState(
                loading = true,
                feedProgress = sources.map { FeedLoadStatus(it, FeedStatus.LOADING) }
            )
        }
        // 3) Network refresh in the background. Updates per-feed status
        //    as each one lands, replacing cached versions with fresh ones.
        try {
            val pods = repo.fetchFeeds(sources) { src, status, err ->
                _state.update { current ->
                    current.copy(
                        feedProgress = current.feedProgress.map { fs ->
                            if (fs.source.feedUrl == src.feedUrl) {
                                fs.copy(status = status, errorMessage = err)
                            } else fs
                        }
                    )
                }
            }
            _state.value = CatalogUiState(podcasts = pods)
        } catch (e: Exception) {
            // If we have cached content, keep showing it and surface the
            // error in feedProgress instead of wiping the screen.
            if (cachedNow.isNotEmpty()) {
                _state.update { it.copy(loading = false, error = e.message) }
            } else {
                _state.value = CatalogUiState(error = e.message ?: "Failed to load")
            }
        }
    }
}

data class CatalogUiState(
    val loading: Boolean = false,
    val feedProgress: List<FeedLoadStatus> = emptyList(),
    val podcasts: List<Podcast> = emptyList(),
    val error: String? = null
)

data class FeedLoadStatus(
    val source: SourceEntry,
    val status: FeedStatus,
    val errorMessage: String? = null
)
