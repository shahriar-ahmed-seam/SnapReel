package com.snapreel.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.repository.MediaRepository
import com.snapreel.app.util.AppUpdateInfo
import com.snapreel.app.util.UpdateManager
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
    val scanningFolderName: String? = null,
    val availableUpdate: AppUpdateInfo? = null,
    val isDownloadingUpdate: Boolean = false,
    val updateDownloadProgress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val updateError: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val mediaRepository: MediaRepository,
    private val updateManager: UpdateManager
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

        checkForUpdates()
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            val update = updateManager.checkForUpdates()
            if (update != null) {
                _uiState.update { it.copy(availableUpdate = update, updateError = null) }
            }
        }
    }

    fun startUpdate() {
        val update = _uiState.value.availableUpdate ?: return
        _uiState.update { it.copy(isDownloadingUpdate = true, updateError = null, updateDownloadProgress = 0f) }
        viewModelScope.launch {
            updateManager.downloadAndInstallApk(
                downloadUrl = update.downloadUrl,
                onProgress = { progress, downloaded, total ->
                    _uiState.update {
                        it.copy(
                            updateDownloadProgress = progress,
                            downloadedBytes = downloaded,
                            totalBytes = total
                        )
                    }
                },
                onComplete = {
                    _uiState.update { it.copy(isDownloadingUpdate = false, availableUpdate = null) }
                },
                onError = { errorMsg ->
                    _uiState.update { it.copy(isDownloadingUpdate = false, updateError = errorMsg) }
                }
            )
        }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(availableUpdate = null, updateError = null) }
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
