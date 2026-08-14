package com.snapreel.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.preferences.AppSettings
import com.snapreel.app.data.preferences.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

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
}
