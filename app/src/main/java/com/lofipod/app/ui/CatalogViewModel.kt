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
     * If [force] is false (the default for init / lifecycle revival), short-circuit
     * to whatever the in-memory [PodcastRepository] cache has — no network hit and
     * no "loading feeds" flicker on navigation back into Catalog, even if the VM
     * was rebuilt by the framework. Refresh from the overflow menu sets force=true
     * to actually re-pull.
     */
    private suspend fun loadCanon(force: Boolean) {
        val sources = Sources.ALL
        if (sources.isEmpty()) {
            _state.value = CatalogUiState()
            return
        }
        if (!force) {
            val cached = sources.mapNotNull { repo.cached(it.feedUrl) }
            if (cached.size == sources.size) {
                _state.value = CatalogUiState(podcasts = cached)
                return
            }
        }
        _state.value = CatalogUiState(
            loading = true,
            feedProgress = sources.map { FeedLoadStatus(it, FeedStatus.LOADING) }
        )
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
            _state.value = CatalogUiState(error = e.message ?: "Failed to load")
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
