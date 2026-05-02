package com.lofipod.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.PodcastRepository.FeedStatus
import com.lofipod.app.data.Settings
import com.lofipod.app.data.db.PodcastSourceEntity
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
    private val sourceDao = (app as LofiPodApp).db.podcastSourceDao()
    private val settings = Settings(app)

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // One-time bootstrap: existing users had a sources URI in DataStore. If
            // Room's source list is empty but a URI exists, import from it once so
            // they don't have to re-pick.
            if (sourceDao.getAll().isEmpty()) {
                val uriStr = settings.sourcesUri.first()
                if (uriStr != null) {
                    runCatching { importInto(Uri.parse(uriStr)) }
                }
            }
            loadFromStored()
        }
    }

    /** Import a sources file. Adds new entries; existing entries are kept untouched. */
    fun onPickSourcesFile(uri: Uri) {
        viewModelScope.launch {
            settings.setSourcesUri(uri.toString())
            importInto(uri)
            loadFromStored()
        }
    }

    fun removeSource(feedUrl: String) {
        viewModelScope.launch {
            sourceDao.remove(feedUrl)
            // Update the visible list immediately; no need to re-fetch.
            _state.update { current ->
                current.copy(podcasts = current.podcasts.filterNot { it.feedUrl == feedUrl })
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { loadFromStored() }
    }

    private suspend fun importInto(uri: Uri) {
        val parsed = repo.loadSourcesFile(uri)
        if (parsed.isEmpty()) {
            _state.value = _state.value.copy(error = "No valid feeds in that file")
            return
        }
        val now = System.currentTimeMillis()
        sourceDao.insertIfAbsent(
            parsed.map { PodcastSourceEntity(it.feedUrl, it.displayName, now) }
        )
    }

    private suspend fun loadFromStored() {
        val stored = sourceDao.getAll()
        if (stored.isEmpty()) {
            _state.value = LibraryUiState()    // empty state — prompt to import
            return
        }
        val sources = stored.map { SourceEntry(it.feedUrl, it.displayName) }
        _state.value = LibraryUiState(
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
            _state.value = LibraryUiState(podcasts = pods)
        } catch (e: Exception) {
            _state.value = LibraryUiState(error = e.message ?: "Failed to load")
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
