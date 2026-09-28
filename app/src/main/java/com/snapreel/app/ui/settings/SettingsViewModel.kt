package com.snapreel.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.preferences.AppSettings
import com.snapreel.app.data.preferences.AspectRatioMode
import com.snapreel.app.data.preferences.SortOrder
import com.snapreel.app.util.update.CheckStatus
import com.snapreel.app.util.update.UpdateCoordinator
import com.snapreel.app.util.update.UpdateUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    val updateCoordinator: UpdateCoordinator
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** The "Check for Updates" row: idle, checking, up to date, or failed (never shown as up to date). */
    val checkStatus: StateFlow<CheckStatus> = updateCoordinator.checkStatus

    /** The update dialog (shared with Home). */
    val updateState: StateFlow<UpdateUiState> = updateCoordinator.state

    fun checkForUpdates() = updateCoordinator.check()

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

    fun setAspectRatioMode(mode: AspectRatioMode) {
        viewModelScope.launch { appPreferences.updateAspectRatioMode(mode) }
    }

    fun setLandscapeVideoMode(value: Boolean) {
        viewModelScope.launch { appPreferences.updateLandscapeVideoMode(value) }
    }
}
