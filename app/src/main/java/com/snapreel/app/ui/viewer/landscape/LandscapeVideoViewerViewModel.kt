package com.snapreel.app.ui.viewer.landscape

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

data class LandscapeViewerUiState(
    val videos: List<MediaItem> = emptyList(),
    val originalItems: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
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
    val playerManager: ReelPlayerManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LandscapeViewerUiState())
    val uiState: StateFlow<LandscapeViewerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appPreferences.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings, isLooping = settings.loopVideos) }
                playerManager.updateLoopMode(settings.loopVideos)
            }
        }
    }

    fun loadVideos(folderUri: Uri, requestedIndex: Int = 0) {
        viewModelScope.launch {
            val allItems = mediaRepository.getCachedMedia(folderUri)
                ?: try {
                    mediaRepository.scanFolder(folderUri)
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Failed to load media: ${e.message}")
                    }
                    return@launch
                }

            val videoList = allItems.filter { it.isVideo }
            if (videoList.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        originalItems = allItems,
                        error = "No videos found in this folder"
                    )
                }
                return@launch
            }

            // Map the requested index from the original full list to the videoList index
            val targetVideoUri = allItems.getOrNull(requestedIndex)?.uri
            val resolvedIndex = if (targetVideoUri != null) {
                val found = videoList.indexOfFirst { it.uri == targetVideoUri }
                if (found >= 0) found else 0
            } else {
                requestedIndex.coerceIn(0, videoList.size - 1)
            }

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
        val item = videos[index]
        playerManager.playUri(item.uri)
        _uiState.update { it.copy(isPlaying = true, showControls = false) }
    }

    fun onVideoTap() {
        val current = _uiState.value
        when {
            // Immersive -> Show controls
            !current.showControls && current.isPlaying -> {
                _uiState.update { it.copy(showControls = true) }
            }
            // Controls active -> Pause
            current.showControls && current.isPlaying -> {
                playerManager.pause()
                _uiState.update { it.copy(isPlaying = false) }
            }
            // Controls active & Paused -> Resume and hide controls
            current.showControls && !current.isPlaying -> {
                playerManager.resume()
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
            playerManager.pause()
            _uiState.update { it.copy(isPlaying = false, showControls = true) }
        } else {
            playerManager.resume()
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
        playerManager.setMuted(newMuted)
        _uiState.update { it.copy(isMuted = newMuted) }
    }

    fun toggleLoop() {
        val newLoop = !_uiState.value.isLooping
        playerManager.updateLoopMode(newLoop)
        _uiState.update { it.copy(isLooping = newLoop) }
    }

    fun seekForward(millis: Long = 10_000) {
        playerManager.seekForward(millis)
    }

    fun seekBackward(millis: Long = 10_000) {
        playerManager.seekBackward(millis)
    }

    fun seekTo(positionMs: Long) {
        playerManager.player.seekTo(positionMs)
    }

    fun onAppPaused() {
        if (_uiState.value.isPlaying) {
            playerManager.pause()
            _uiState.update { it.copy(isPlaying = false, showControls = true) }
        }
    }

    fun saveLastViewedIndex(folderUri: Uri, videoIndex: Int) {
        viewModelScope.launch {
            val state = _uiState.value
            val currentVideo = state.videos.getOrNull(videoIndex) ?: return@launch
            val originalIndex = state.originalItems.indexOfFirst { it.uri == currentVideo.uri }
            val indexToSave = if (originalIndex >= 0) originalIndex else videoIndex
            appPreferences.updateLastViewedIndex(folderUri.toString(), indexToSave)
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }
}
