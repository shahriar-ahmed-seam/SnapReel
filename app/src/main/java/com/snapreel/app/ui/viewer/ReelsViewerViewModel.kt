package com.snapreel.app.ui.viewer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.preferences.AppSettings
import com.snapreel.app.data.repository.MediaRepository
import com.snapreel.app.player.ReelPlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ViewerUiState(
    val mediaItems: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
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
    val playerManager: ReelPlayerManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appPreferences.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
                playerManager.updateLoopMode(settings.loopVideos)
            }
        }
    }

    fun loadMedia(folderUri: Uri, startIndex: Int = 0) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val items = mediaRepository.scanFolder(folderUri)
                if (items.isEmpty()) {
                    _uiState.update {
                        it.copy(isLoading = false, error = "No media found in this folder")
                    }
                } else {
                    val safeIndex = startIndex.coerceIn(0, items.size - 1)
                    _uiState.update {
                        it.copy(mediaItems = items, isLoading = false, currentIndex = safeIndex)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to load media: ${e.message}")
                }
            }
        }
    }

    fun onPageSettled(index: Int) {
        val items = _uiState.value.mediaItems
        if (index < 0 || index >= items.size) return

        _uiState.update { it.copy(currentIndex = index) }

        val item = items[index]
        if (item.isVideo) {
            playerManager.playUri(item.uri)
            // Always start in State A (Immersive): playing, controls hidden
            _uiState.update { it.copy(isPlaying = true, showControls = false) }
        } else {
            playerManager.pause()
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
        when {
            // State A → B: Show controls, keep playing
            !current.showControls && current.isPlaying -> {
                _uiState.update { it.copy(showControls = true) }
            }
            // State B → C: Pause
            current.showControls && current.isPlaying -> {
                playerManager.pause()
                _uiState.update { it.copy(isPlaying = false) }
            }
            // State C → A: Resume + hide controls
            current.showControls && !current.isPlaying -> {
                playerManager.resume()
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
            playerManager.pause()
            _uiState.update { it.copy(isPlaying = false, showControls = true) }
        }
    }

    fun saveLastViewedIndex(folderUri: Uri, index: Int) {
        viewModelScope.launch {
            appPreferences.updateLastViewedIndex(folderUri.toString(), index)
        }
    }

    fun toggleMute() {
        val newMuted = !_uiState.value.isMuted
        playerManager.setMuted(newMuted)
        _uiState.update { it.copy(isMuted = newMuted) }
    }

    fun seekForward() {
        playerManager.seekForward()
    }

    fun seekBackward() {
        playerManager.seekBackward()
    }

    fun seekTo(positionMs: Long) {
        playerManager.player.seekTo(positionMs)
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }
}
