package com.snapreel.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecentFolder(
    val uri: Uri,
    val displayName: String,
    val lastIndex: Int
)

data class HomeUiState(
    val recentFolders: List<RecentFolder> = emptyList(),
    val isLoading: Boolean = false,
    val scanningFolderName: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val mediaRepository: MediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appPreferences.recentFolders.collect { entries ->
                val folders = entries.mapNotNull { entry ->
                    appPreferences.parseRecentFolderEntry(entry)?.let { info ->
                        RecentFolder(Uri.parse(info.uri), info.name, info.lastIndex)
                    }
                }
                _uiState.update { it.copy(recentFolders = folders) }
            }
        }
    }

    fun onFolderPicked(treeUri: Uri) {
        viewModelScope.launch {
            val displayName = mediaRepository.getFolderDisplayName(treeUri)
            appPreferences.addRecentFolder(treeUri.toString(), displayName)
        }
    }

    fun removeRecentFolder(folder: RecentFolder) {
        viewModelScope.launch {
            appPreferences.removeRecentFolder(folder.uri.toString())
        }
    }
}
