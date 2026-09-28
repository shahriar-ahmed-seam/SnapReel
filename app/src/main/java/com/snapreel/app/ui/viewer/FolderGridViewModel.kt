package com.snapreel.app.ui.viewer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.preferences.AppSettings
import com.snapreel.app.data.repository.FolderLoadError
import com.snapreel.app.data.repository.MediaRepository
import com.snapreel.app.data.repository.toFolderLoadError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GridUiState(
    val mediaItems: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: FolderLoadError? = null
)

/** One-off events for the grid screen. */
sealed interface FolderEvent {
    /** A different folder was re-picked: open its grid above Home. */
    data class OpenOtherFolder(val uri: Uri) : FolderEvent
}

@HiltViewModel
class FolderGridViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(GridUiState())
    val uiState: StateFlow<GridUiState> = _uiState.asStateFlow()

    private val _events = Channel<FolderEvent>(Channel.BUFFERED)
    val events: Flow<FolderEvent> = _events.receiveAsFlow()

    /** The first load per ViewModel (one open from Home) scans fresh; later loads use the snapshot. */
    private var loadedOnce = false

    val settings: StateFlow<AppSettings> = appPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun toggleLandscapeMode() {
        viewModelScope.launch {
            val current = settings.value.landscapeVideoMode
            appPreferences.updateLandscapeVideoMode(!current)
        }
    }

    /**
     * Opening the folder from Home rescans it (with the current sort and shuffle) behind the
     * loading state, so no stale list is shown and taps index the fresh shared snapshot.
     * Returning from a viewer reuses that snapshot.
     */
    fun loadMedia(folderUri: Uri) {
        val fresh = !loadedOnce
        loadedOnce = true
        if (!fresh) {
            val cached = mediaRepository.getCachedMedia(folderUri)
            if (cached != null && cached.isNotEmpty()) {
                _uiState.update { it.copy(mediaItems = cached, isLoading = false, error = null) }
                return
            }
        }
        scan(folderUri, forceRefresh = fresh)
    }

    /** The Failed state's Retry. */
    fun retry(folderUri: Uri) = scan(folderUri, forceRefresh = true)

    private fun scan(folderUri: Uri, forceRefresh: Boolean) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val items = mediaRepository.scanFolder(folderUri, forceRefresh = forceRefresh)
                if (items.isEmpty()) {
                    _uiState.update {
                        it.copy(mediaItems = emptyList(), isLoading = false, error = FolderLoadError.NoMedia)
                    }
                } else {
                    _uiState.update {
                        it.copy(mediaItems = items, isLoading = false, error = null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(mediaItems = emptyList(), isLoading = false, error = e.toFolderLoadError())
                }
            }
        }
    }

    /**
     * "Pick folder again" returned [picked]. The same tree reloads in place; a different tree is
     * added to Recents and opened as its own grid ([FolderEvent.OpenOtherFolder]).
     */
    fun onFolderRePicked(currentFolder: Uri, picked: Uri) {
        mediaRepository.takePersistableAccess(picked)
        if (mediaRepository.isSameTree(currentFolder, picked)) {
            scan(currentFolder, forceRefresh = true)
        } else {
            viewModelScope.launch {
                mediaRepository.addPickedFolder(picked)
                _events.send(FolderEvent.OpenOtherFolder(picked))
            }
        }
    }

    /** The saved position: the saved item if it is still in the snapshot, else the saved index. */
    suspend fun getSavedLastIndex(folderUri: Uri): Int {
        val info = appPreferences.recentFolder(folderUri.toString())
        return MediaIndexMapping.resolveStartIndex(
            snapshot = _uiState.value.mediaItems,
            savedItemUri = info?.lastItemUri,
            fallbackIndex = info?.lastIndex ?: 0,
        )
    }
}
