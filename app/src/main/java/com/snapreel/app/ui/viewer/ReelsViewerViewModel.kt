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
import com.snapreel.app.player.PageState
import com.snapreel.app.player.PlaybackFailure
import com.snapreel.app.player.PlayerPool
import com.snapreel.app.player.ReelPlayerPool
import com.snapreel.app.util.thumbnail.ThumbnailWorkGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ViewerUiState(
    val mediaItems: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: FolderLoadError? = null,
    val currentIndex: Int = 0,
    val isPlaying: Boolean = true,
    val isMuted: Boolean = false,
    val showControls: Boolean = false,
    val settings: AppSettings = AppSettings()
)

@HiltViewModel
class ReelsViewerViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val appPreferences: AppPreferences,
    poolFactory: ReelPlayerPool.Factory,
    private val thumbnailWorkGate: ThumbnailWorkGate
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

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
                _uiState.update { it.copy(settings = settings) }
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
        return s.mediaItems.getOrNull(s.currentIndex)?.takeIf { it.isVideo }?.uri
    }

    /** The current page's playback failure, if any. */
    fun currentFailure(): PlaybackFailure? = currentUri()?.let { pool.pages.value[it]?.failure }

    private var loadStarted = false

    /**
     * Loads the folder once per ViewModel. [fresh] (Home's play button) rescans the folder and
     * starts at the saved item, found by its URI, falling back to [startIndex]. Otherwise (a grid
     * tap) the viewer opens the snapshot the grid shows, at [startIndex].
     */
    fun loadMedia(folderUri: Uri, startIndex: Int = 0, fresh: Boolean = false) {
        if (loadStarted) return
        loadStarted = true
        load(folderUri, startIndex, fresh)
    }

    private fun load(folderUri: Uri, startIndex: Int, fresh: Boolean) {
        viewModelScope.launch {
            val cached = if (fresh) null else mediaRepository.getCachedMedia(folderUri)
            if (cached != null && cached.isNotEmpty()) {
                val safeIndex = startIndex.coerceIn(0, cached.size - 1)
                _uiState.update {
                    it.copy(mediaItems = cached, isLoading = false, currentIndex = safeIndex, error = null)
                }
                onPageSettled(safeIndex)
                return@launch
            }

            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val items = mediaRepository.scanFolder(folderUri, forceRefresh = fresh)
                if (items.isEmpty()) {
                    _uiState.update {
                        it.copy(isLoading = false, error = FolderLoadError.NoMedia)
                    }
                } else {
                    val start = if (fresh) {
                        val saved = appPreferences.recentFolder(folderUri.toString())
                        MediaIndexMapping.resolveStartIndex(items, saved?.lastItemUri, startIndex)
                    } else {
                        startIndex
                    }
                    val safeIndex = start.coerceIn(0, items.size - 1)
                    _uiState.update {
                        it.copy(mediaItems = items, isLoading = false, currentIndex = safeIndex, error = null)
                    }
                    onPageSettled(safeIndex)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.toFolderLoadError())
                }
            }
        }
    }

    /**
     * "Pick folder again" returned [picked]. The same tree reloads in place (fresh); a different
     * tree is added to Recents and opened as its own grid ([FolderEvent.OpenOtherFolder]).
     */
    fun onFolderRePicked(currentFolder: Uri, picked: Uri, startIndex: Int) {
        mediaRepository.takePersistableAccess(picked)
        if (mediaRepository.isSameTree(currentFolder, picked)) {
            load(currentFolder, startIndex, fresh = true)
        } else {
            viewModelScope.launch {
                mediaRepository.addPickedFolder(picked)
                _events.send(FolderEvent.OpenOtherFolder(picked))
            }
        }
    }

    fun onPageSettled(index: Int) {
        val items = _uiState.value.mediaItems
        if (index < 0 || index >= items.size) return

        _uiState.update { it.copy(currentIndex = index) }

        val item = items[index]
        pool.setWindow(items, index, playCurrent = item.isVideo)
        if (item.isVideo) {
            // Always start in State A (Immersive): playing, controls hidden
            _uiState.update { it.copy(isPlaying = currentFailure() == null, showControls = false) }
        } else {
            // Images start with controls hidden (immersive)
            _uiState.update { it.copy(isPlaying = false, showControls = false) }
        }
    }

    // ─── 3-State Tap Logic (Video) ─────────────────────────────────────
    //
    //   State A (Immersive):  isPlaying=true,  showControls=false
    //   State B (Controls):   isPlaying=true,  showControls=true
    //   State C (Paused):     isPlaying=false,  showControls=true
    //
    //   A → tap → B (show controls)
    //   B → tap → C (pause video)
    //   C → tap → A (resume + hide controls)
    //
    fun onVideoTap() {
        val current = _uiState.value
        if (currentFailure() != null) {
            // Nothing can play until Retry: taps only show or hide the controls.
            _uiState.update { it.copy(isPlaying = false, showControls = !it.showControls) }
            return
        }
        when {
            // State A → B: Show controls, keep playing
            !current.showControls && current.isPlaying -> {
                _uiState.update { it.copy(showControls = true) }
            }
            // State B → C: Pause
            current.showControls && current.isPlaying -> {
                pool.pause()
                _uiState.update { it.copy(isPlaying = false) }
            }
            // State C → A: Resume + hide controls
            current.showControls && !current.isPlaying -> {
                pool.play()
                _uiState.update { it.copy(isPlaying = true, showControls = false) }
            }
            // Fallback (shouldn't happen): show controls
            else -> {
                _uiState.update { it.copy(showControls = true) }
            }
        }
    }

    // ─── Image Tap Logic ───────────────────────────────────────────────
    fun onImageTap() {
        _uiState.update { it.copy(showControls = !it.showControls) }
    }

    // ─── Auto-hide timeout (called after 3s delay) ─────────────────────
    fun onControlsTimeout() {
        val current = _uiState.value
        // Only auto-hide if video is still playing (State B → A)
        // Never auto-hide while paused (State C)
        if (current.isPlaying && current.showControls) {
            _uiState.update { it.copy(showControls = false) }
        }
    }

    // ─── Lifecycle: App backgrounded ───────────────────────────────────
    fun onAppPaused() {
        val current = _uiState.value
        val currentItem = current.mediaItems.getOrNull(current.currentIndex)
        if (currentItem?.isVideo == true) {
            pool.pause()
            _uiState.update { it.copy(isPlaying = false, showControls = true) }
        }
    }

    suspend fun getSavedLastIndex(folderUri: Uri): Int {
        val entries = appPreferences.recentFolders.first()
        val match = entries.find { it.startsWith("${folderUri}<<>>") }
        return match?.let { appPreferences.parseRecentFolderEntry(it)?.lastIndex } ?: 0
    }

    /** Saves the settled position: its full-list index and the item's URI. */
    fun saveLastViewed(folderUri: Uri, index: Int) {
        val itemUri = _uiState.value.mediaItems.getOrNull(index)?.uri?.toString()
        viewModelScope.launch {
            appPreferences.updateLastViewed(folderUri.toString(), index, itemUri)
        }
    }

    fun toggleMute() {
        val newMuted = !_uiState.value.isMuted
        pool.setMuted(newMuted)
        _uiState.update { it.copy(isMuted = newMuted) }
    }

    fun seekForward() {
        pool.seekBy(10_000)
    }

    fun seekBackward() {
        pool.seekBy(-10_000)
    }

    fun seekTo(positionMs: Long) {
        pool.seekTo(positionMs)
    }

    /** Pause while the slider is dragged so seeking is smooth. */
    fun onSliderDragStart() {
        pool.pause()
    }

    /** Seek to the dropped position and resume if the video was playing before the drag. */
    fun onSliderDragEnd(positionMs: Long) {
        pool.seekTo(positionMs)
        if (_uiState.value.isPlaying) pool.play()
    }

    /** The failure overlay's Retry: a fresh attempt on the current page, back in State A. */
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

    override fun onCleared() {
        super.onCleared()
        pool.release()
        thumbnailWorkGate.viewerClosed()
    }
}
