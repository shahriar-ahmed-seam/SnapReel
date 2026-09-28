package com.snapreel.app.ui.viewer.landscape

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.preferences.AppSettings
import com.snapreel.app.data.repository.FolderLoadError
import com.snapreel.app.data.repository.MediaRepository
import com.snapreel.app.data.repository.toFolderLoadError
import com.snapreel.app.player.PageState
import com.snapreel.app.player.PlaybackFailure
import com.snapreel.app.player.PlayerPool
import com.snapreel.app.player.ReelPlayerPool
import com.snapreel.app.ui.viewer.FolderEvent
import com.snapreel.app.ui.viewer.MediaIndexMapping
import com.snapreel.app.util.thumbnail.ThumbnailWorkGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LandscapeViewerUiState(
    val videos: List<MediaItem> = emptyList(),
    val originalItems: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: FolderLoadError? = null,
    val currentIndex: Int = 0,
    val isPlaying: Boolean = true,
    val isMuted: Boolean = false,
    val isLooping: Boolean = true,
    val showControls: Boolean = false,
    val settings: AppSettings = AppSettings()
)

@HiltViewModel
class LandscapeVideoViewerViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val appPreferences: AppPreferences,
    poolFactory: ReelPlayerPool.Factory,
    private val thumbnailWorkGate: ThumbnailWorkGate
) : ViewModel() {

    private val _uiState = MutableStateFlow(LandscapeViewerUiState())
    val uiState: StateFlow<LandscapeViewerUiState> = _uiState.asStateFlow()

    private val _events = Channel<FolderEvent>(Channel.BUFFERED)
    val events: Flow<FolderEvent> = _events.receiveAsFlow()

    /** Up to 3 players (current, next, previous), released in [onCleared]. */
    val pool: PlayerPool = poolFactory.create()

    /** Per-page player state (player, video size, first frame, failure), keyed by item URI. */
    val pages: StateFlow<Map<Uri, PageState>> = pool.pages

    init {
        // No thumbnail generation starts while a viewer is open (the player gets the decoders).
        thumbnailWorkGate.viewerOpened()
        viewModelScope.launch {
            appPreferences.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings, isLooping = settings.loopVideos) }
                pool.setLoop(settings.loopVideos)
            }
        }
        viewModelScope.launch {
            // A failure on the current page means nothing is playing, whatever the tap state was.
            pool.pages.collect { pages ->
                val uri = currentUri() ?: return@collect
                if (pages[uri]?.failure != null && _uiState.value.isPlaying) {
                    _uiState.update { it.copy(isPlaying = false) }
                }
            }
        }
    }

    private fun currentUri(): Uri? {
        val s = _uiState.value
        return s.videos.getOrNull(s.currentIndex)?.uri
    }

    /** The current page's playback failure, if any. */
    fun currentFailure(): PlaybackFailure? = currentUri()?.let { pool.pages.value[it]?.failure }

    private var loadStarted = false

    /**
     * Loads the folder's videos once per ViewModel. [fresh] (Home's play button) rescans the folder
     * and starts at the saved item, found by its URI, falling back to [requestedIndex] (a full-list
     * index). Otherwise (a grid tap) the viewer opens the snapshot the grid shows.
     */
    fun loadVideos(folderUri: Uri, requestedIndex: Int = 0, fresh: Boolean = false) {
        if (loadStarted) return
        loadStarted = true
        load(folderUri, requestedIndex, fresh)
    }

    private fun load(folderUri: Uri, requestedIndex: Int, fresh: Boolean) {
        viewModelScope.launch {
            val cached = if (fresh) null else mediaRepository.getCachedMedia(folderUri)
            if (cached == null) _uiState.update { it.copy(isLoading = true, error = null) }
            val allItems = cached
                ?: try {
                    mediaRepository.scanFolder(folderUri, forceRefresh = fresh)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(isLoading = false, error = e.toFolderLoadError())
                    }
                    return@launch
                }

            val videoList = allItems.filter { it.isVideo }
            if (videoList.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        originalItems = allItems,
                        error = FolderLoadError.NoVideos
                    )
                }
                return@launch
            }

            val requestedFullIndex = if (fresh) {
                val saved = appPreferences.recentFolder(folderUri.toString())
                MediaIndexMapping.resolveStartIndex(allItems, saved?.lastItemUri, requestedIndex)
            } else {
                requestedIndex
            }
            // Map the requested index from the original full list to the videoList index
            val resolvedIndex = MediaIndexMapping.fullToLandscapeIndex(allItems, requestedFullIndex) ?: 0

            _uiState.update {
                it.copy(
                    videos = videoList,
                    originalItems = allItems,
                    isLoading = false,
                    currentIndex = resolvedIndex,
                    error = null
                )
            }

            onPageSettled(resolvedIndex)
        }
    }

    fun onPageSettled(index: Int) {
        val videos = _uiState.value.videos
        if (index < 0 || index >= videos.size) return

        _uiState.update { it.copy(currentIndex = index) }
        pool.setWindow(videos, index, playCurrent = true)
        _uiState.update { it.copy(isPlaying = currentFailure() == null, showControls = false) }
    }

    fun onVideoTap() {
        val current = _uiState.value
        if (currentFailure() != null) {
            // Nothing can play until Retry: taps only show or hide the controls.
            _uiState.update { it.copy(isPlaying = false, showControls = !it.showControls) }
            return
        }
        when {
            // Immersive -> Show controls
            !current.showControls && current.isPlaying -> {
                _uiState.update { it.copy(showControls = true) }
            }
            // Controls active -> Pause
            current.showControls && current.isPlaying -> {
                pool.pause()
                _uiState.update { it.copy(isPlaying = false) }
            }
            // Controls active & Paused -> Resume and hide controls
            current.showControls && !current.isPlaying -> {
                pool.play()
                _uiState.update { it.copy(isPlaying = true, showControls = false) }
            }
            else -> {
                _uiState.update { it.copy(showControls = true) }
            }
        }
    }

    fun togglePlayPause() {
        val current = _uiState.value
        if (current.isPlaying) {
            pool.pause()
            _uiState.update { it.copy(isPlaying = false, showControls = true) }
        } else if (currentFailure() != null) {
            retry()
        } else {
            pool.play()
            _uiState.update { it.copy(isPlaying = true) }
        }
    }

    fun onControlsTimeout() {
        val current = _uiState.value
        if (current.isPlaying && current.showControls) {
            _uiState.update { it.copy(showControls = false) }
        }
    }

    fun toggleMute() {
        val newMuted = !_uiState.value.isMuted
        pool.setMuted(newMuted)
        _uiState.update { it.copy(isMuted = newMuted) }
    }

    fun toggleLoop() {
        val newLoop = !_uiState.value.isLooping
        pool.setLoop(newLoop)
        _uiState.update { it.copy(isLooping = newLoop) }
    }

    fun seekForward(millis: Long = 10_000) {
        pool.seekBy(millis)
    }

    fun seekBackward(millis: Long = 10_000) {
        pool.seekBy(-millis)
    }

    fun seekTo(positionMs: Long) {
        pool.seekTo(positionMs)
    }

    /** Pause while the slider is dragged so seeking is smooth. */
    fun onSliderDragStart() {
        pool.pause()
    }

    /** Seek to the dropped position (if known) and resume if the video was playing before the drag. */
    fun onSliderDragEnd(positionMs: Long?) {
        if (positionMs != null) pool.seekTo(positionMs)
        if (_uiState.value.isPlaying) pool.play()
    }

    /** The failure overlay's Retry: a fresh attempt on the current page. */
    fun retry() {
        val uri = currentUri() ?: return
        pool.retry(uri)
        _uiState.update { it.copy(isPlaying = true, showControls = false) }
    }

    /** `ON_STOP`: free the neighbors' decoders while the app is in the background. */
    fun onAppStopped() {
        pool.trimToCurrent()
    }

    /** `ON_START`: prepare the neighbors again. */
    fun onAppStarted() {
        pool.restoreNeighbors()
    }

    /** Back from the viewer: stop playback now and free the neighbors before the screen goes away. */
    fun onLeaving() {
        pool.pause()
        pool.trimToCurrent()
    }

    fun onAppPaused() {
        if (_uiState.value.isPlaying) {
            pool.pause()
            _uiState.update { it.copy(isPlaying = false, showControls = true) }
        }
    }

    /** Saves the settled video's position: its full-list index and its URI. */
    fun saveLastViewed(folderUri: Uri, videoIndex: Int) {
        viewModelScope.launch {
            val state = _uiState.value
            val currentVideo = state.videos.getOrNull(videoIndex) ?: return@launch
            val indexToSave = MediaIndexMapping.landscapeToFullIndex(state.originalItems, videoIndex) ?: videoIndex
            appPreferences.updateLastViewed(folderUri.toString(), indexToSave, currentVideo.uri.toString())
        }
    }

    /**
     * "Pick folder again" returned [picked]. The same tree reloads in place (fresh); a different
     * tree is added to Recents and opened as its own grid ([FolderEvent.OpenOtherFolder]).
     */
    fun onFolderRePicked(currentFolder: Uri, picked: Uri, requestedIndex: Int) {
        mediaRepository.takePersistableAccess(picked)
        if (mediaRepository.isSameTree(currentFolder, picked)) {
            load(currentFolder, requestedIndex, fresh = true)
        } else {
            viewModelScope.launch {
                mediaRepository.addPickedFolder(picked)
                _events.send(FolderEvent.OpenOtherFolder(picked))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pool.release()
        thumbnailWorkGate.viewerClosed()
    }
}
