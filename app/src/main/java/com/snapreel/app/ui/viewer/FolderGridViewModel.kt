package com.snapreel.app.ui.viewer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GridUiState(
    val mediaItems: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class FolderGridViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(GridUiState())
    val uiState: StateFlow<GridUiState> = _uiState.asStateFlow()

    fun loadMedia(folderUri: Uri) {
        viewModelScope.launch {
            val cached = mediaRepository.getCachedMedia(folderUri)
            if (cached != null && cached.isNotEmpty()) {
                _uiState.update {
                    it.copy(mediaItems = cached, isLoading = false, error = null)
                }
                return@launch
            }

            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val items = mediaRepository.scanFolder(folderUri)
                if (items.isEmpty()) {
                    _uiState.update {
                        it.copy(isLoading = false, error = "No media found in this folder")
                    }
                } else {
                    _uiState.update {
                        it.copy(mediaItems = items, isLoading = false, error = null)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to load media: ${e.message}")
                }
            }
        }
    }

    suspend fun getSavedLastIndex(folderUri: Uri): Int {
        val entries = appPreferences.recentFolders.first()
        val match = entries.find { it.startsWith("${folderUri}<<>>") }
        return match?.let { appPreferences.parseRecentFolderEntry(it)?.lastIndex } ?: 0
    }
}
