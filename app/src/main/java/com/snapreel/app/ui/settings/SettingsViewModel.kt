package com.snapreel.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.preferences.AppSettings
import com.snapreel.app.data.preferences.SortOrder
import com.snapreel.app.util.AppUpdateInfo
import com.snapreel.app.util.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUpdateState(
    val isChecking: Boolean = false,
    val availableUpdate: AppUpdateInfo? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null,
    val isUpToDate: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val updateManager: UpdateManager
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _updateState = MutableStateFlow(SettingsUpdateState())
    val updateState: StateFlow<SettingsUpdateState> = _updateState.asStateFlow()

    fun checkForUpdates() {
        _updateState.update { it.copy(isChecking = true, isUpToDate = false, error = null) }
        viewModelScope.launch {
            val update = updateManager.checkForUpdates()
            if (update != null) {
                _updateState.update {
                    it.copy(isChecking = false, availableUpdate = update, isUpToDate = false)
                }
            } else {
                _updateState.update {
                    it.copy(isChecking = false, isUpToDate = true)
                }
            }
        }
    }

    fun startUpdate() {
        val update = _updateState.value.availableUpdate ?: return
        _updateState.update {
            it.copy(isDownloading = true, error = null, downloadProgress = 0f)
        }
        viewModelScope.launch {
            updateManager.downloadAndInstallApk(
                downloadUrl = update.downloadUrl,
                onProgress = { progress, downloaded, total ->
                    _updateState.update {
                        it.copy(
                            downloadProgress = progress,
                            downloadedBytes = downloaded,
                            totalBytes = total
                        )
                    }
                },
                onComplete = {
                    _updateState.update { it.copy(isDownloading = false, availableUpdate = null) }
                },
                onError = { errorMsg ->
                    _updateState.update { it.copy(isDownloading = false, error = errorMsg) }
                }
            )
        }
    }

    fun dismissUpdateDialog() {
        _updateState.update { it.copy(availableUpdate = null, isUpToDate = false, error = null) }
    }

    fun setLoopVideos(value: Boolean) {
        viewModelScope.launch { appPreferences.updateLoopVideos(value) }
    }

    fun setShuffleMedia(value: Boolean) {
        viewModelScope.launch { appPreferences.updateShuffleMedia(value) }
    }

    fun setSortOrder(order: SortOrder) {
        viewModelScope.launch { appPreferences.updateSortOrder(order) }
    }

    fun setAutoAdvanceImages(value: Boolean) {
        viewModelScope.launch { appPreferences.updateAutoAdvanceImages(value) }
    }

    fun setAutoAdvanceDelay(seconds: Int) {
        viewModelScope.launch { appPreferences.updateAutoAdvanceDelay(seconds) }
    }

    fun setHapticFeedback(value: Boolean) {
        viewModelScope.launch { appPreferences.updateHapticFeedback(value) }
    }

    fun setShowFileName(value: Boolean) {
        viewModelScope.launch { appPreferences.updateShowFileName(value) }
    }

    fun setFillScreen(value: Boolean) {
        viewModelScope.launch { appPreferences.updateFillScreen(value) }
    }
}
