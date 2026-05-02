package com.lofipod.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.PodcastRepository
import com.lofipod.app.data.PodcastRepository.FeedStatus
import com.lofipod.app.data.Settings
import com.lofipod.app.data.model.Podcast
import com.lofipod.app.parser.SourceEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as LofiPodApp).repo
    private val settings = Settings(app)

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        // On startup, try to reuse the previously picked sources file
        viewModelScope.launch {
            val uriStr = settings.sourcesUri.first()
            if (uriStr != null) {
                runCatching { loadFrom(Uri.parse(uriStr)) }
            }
        }
    }

    fun onPickSourcesFile(uri: Uri) {
        viewModelScope.launch {
            settings.setSourcesUri(uri.toString())
            loadFrom(uri)
        }
    }

    private suspend fun loadFrom(uri: Uri) {
        _state.value = LibraryUiState(loading = true)
        try {
            val sources = repo.loadSourcesFile(uri)
            if (sources.isEmpty()) {
                _state.value = LibraryUiState(error = "No valid feeds in sources file")
                return
            }
            // Seed per-feed progress in LOADING state.
            _state.value = LibraryUiState(
                loading = true,
                feedProgress = sources.map { FeedLoadStatus(it, FeedStatus.LOADING) }
            )
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
            _state.value = LibraryUiState(podcasts = pods)
        } catch (e: Exception) {
            _state.value = LibraryUiState(error = e.message ?: "Failed to load")
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val uriStr = settings.sourcesUri.first() ?: return@launch
            loadFrom(Uri.parse(uriStr))
        }
    }
}

data class LibraryUiState(
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
