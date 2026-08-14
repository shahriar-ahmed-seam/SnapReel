package com.snapreel.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "snapreel_settings")

enum class SortOrder {
    NAME_ASC, NAME_DESC, DATE_NEWEST, DATE_OLDEST, SIZE_LARGEST, SIZE_SMALLEST, TYPE_VIDEO_FIRST, TYPE_IMAGE_FIRST
}

data class AppSettings(
    val loopVideos: Boolean = true,
    val shuffleMedia: Boolean = false,
    val sortOrder: SortOrder = SortOrder.NAME_ASC,
    val autoAdvanceImages: Boolean = false,
    val autoAdvanceDelaySeconds: Int = 5,
    val hapticFeedback: Boolean = true,
    val showFileName: Boolean = true,
    val fillScreen: Boolean = true
)

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val LOOP_VIDEOS = booleanPreferencesKey("loop_videos")
        val SHUFFLE_MEDIA = booleanPreferencesKey("shuffle_media")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val AUTO_ADVANCE_IMAGES = booleanPreferencesKey("auto_advance_images")
        val AUTO_ADVANCE_DELAY = intPreferencesKey("auto_advance_delay")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val SHOW_FILE_NAME = booleanPreferencesKey("show_file_name")
        val FILL_SCREEN = booleanPreferencesKey("fill_screen")
        val RECENT_FOLDERS = stringPreferencesKey("recent_folders")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            loopVideos = prefs[Keys.LOOP_VIDEOS] ?: true,
            shuffleMedia = prefs[Keys.SHUFFLE_MEDIA] ?: false,
            sortOrder = try {
                SortOrder.valueOf(prefs[Keys.SORT_ORDER] ?: SortOrder.NAME_ASC.name)
            } catch (_: Exception) {
                SortOrder.NAME_ASC
            },
            autoAdvanceImages = prefs[Keys.AUTO_ADVANCE_IMAGES] ?: false,
            autoAdvanceDelaySeconds = prefs[Keys.AUTO_ADVANCE_DELAY] ?: 5,
            hapticFeedback = prefs[Keys.HAPTIC_FEEDBACK] ?: true,
            showFileName = prefs[Keys.SHOW_FILE_NAME] ?: true,
            fillScreen = prefs[Keys.FILL_SCREEN] ?: true
        )
    }

    val recentFolders: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.RECENT_FOLDERS] ?: ""
        if (raw.isBlank()) emptyList() else raw.split("|||")
    }

    suspend fun updateLoopVideos(value: Boolean) {
        context.dataStore.edit { it[Keys.LOOP_VIDEOS] = value }
    }

    suspend fun updateShuffleMedia(value: Boolean) {
        context.dataStore.edit { it[Keys.SHUFFLE_MEDIA] = value }
    }

    suspend fun updateSortOrder(order: SortOrder) {
        context.dataStore.edit { it[Keys.SORT_ORDER] = order.name }
    }

    suspend fun updateAutoAdvanceImages(value: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_ADVANCE_IMAGES] = value }
    }

    suspend fun updateAutoAdvanceDelay(seconds: Int) {
        context.dataStore.edit { it[Keys.AUTO_ADVANCE_DELAY] = seconds }
    }

    suspend fun updateHapticFeedback(value: Boolean) {
        context.dataStore.edit { it[Keys.HAPTIC_FEEDBACK] = value }
    }

    suspend fun updateShowFileName(value: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_FILE_NAME] = value }
    }

    suspend fun updateFillScreen(value: Boolean) {
        context.dataStore.edit { it[Keys.FILL_SCREEN] = value }
    }

    suspend fun addRecentFolder(uriString: String, displayName: String) {
        context.dataStore.edit { prefs ->
            val existing = (prefs[Keys.RECENT_FOLDERS] ?: "")
                .split("|||")
                .filter { it.isNotBlank() && !it.startsWith("$uriString<<>>") }
            // preserve the last viewed index if it already exists in the string (not possible in add flow, but just in case)
            val entry = "$uriString<<>>$displayName<<>>0"
            val updated = (listOf(entry) + existing).take(10)
            prefs[Keys.RECENT_FOLDERS] = updated.joinToString("|||")
        }
    }

    suspend fun updateLastViewedIndex(uriString: String, index: Int) {
        context.dataStore.edit { prefs ->
            val allFolders = (prefs[Keys.RECENT_FOLDERS] ?: "")
                .split("|||")
                .filter { it.isNotBlank() }
            
            val updated = allFolders.map { entry ->
                if (entry.startsWith("$uriString<<>>")) {
                    val parts = entry.split("<<>>")
                    if (parts.size >= 2) {
                        "${parts[0]}<<>>${parts[1]}<<>>$index"
                    } else entry
                } else {
                    entry
                }
            }
            prefs[Keys.RECENT_FOLDERS] = updated.joinToString("|||")
        }
    }

    suspend fun removeRecentFolder(uriString: String) {
        context.dataStore.edit { prefs ->
            val existing = (prefs[Keys.RECENT_FOLDERS] ?: "")
                .split("|||")
                .filter { it.isNotBlank() && !it.startsWith("$uriString<<>>") }
            prefs[Keys.RECENT_FOLDERS] = existing.joinToString("|||")
        }
    }

    fun parseRecentFolderEntry(entry: String): RecentFolderInfo? {
        val parts = entry.split("<<>>")
        return if (parts.size >= 2) {
            val lastIndex = parts.getOrNull(2)?.toIntOrNull() ?: 0
            RecentFolderInfo(parts[0], parts[1], lastIndex)
        } else null
    }
}

data class RecentFolderInfo(val uri: String, val name: String, val lastIndex: Int)
