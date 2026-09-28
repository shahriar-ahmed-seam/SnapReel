package com.snapreel.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The settings file name (`datastore/snapreel_settings.preferences_pb`), unchanged since the first release. */
const val SETTINGS_DATASTORE_NAME = "snapreel_settings"

enum class SortOrder {
    NAME_ASC, NAME_DESC, DATE_NEWEST, DATE_OLDEST, SIZE_LARGEST, SIZE_SMALLEST, TYPE_VIDEO_FIRST, TYPE_IMAGE_FIRST
}

enum class AspectRatioMode {
    SMART, FILL, FIT
}

data class AppSettings(
    val loopVideos: Boolean = true,
    val shuffleMedia: Boolean = false,
    val sortOrder: SortOrder = SortOrder.NAME_ASC,
    val autoAdvanceImages: Boolean = false,
    val autoAdvanceDelaySeconds: Int = 5,
    val hapticFeedback: Boolean = true,
    val showFileName: Boolean = true,
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.SMART,
    val landscapeVideoMode: Boolean = false
)

/**
 * Settings and Recents over the app's single settings [DataStore] (provided by `AppModule`, with a
 * corruption handler that replaces an unreadable file with empty preferences).
 *
 * Storage failures never crash: a read `IOException` falls back to defaults, and every edit
 * returns `false` instead of throwing, so the UI keeps its last-known value.
 */
@Singleton
class AppPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val LOOP_VIDEOS = booleanPreferencesKey("loop_videos")
        val SHUFFLE_MEDIA = booleanPreferencesKey("shuffle_media")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val AUTO_ADVANCE_IMAGES = booleanPreferencesKey("auto_advance_images")
        val AUTO_ADVANCE_DELAY = intPreferencesKey("auto_advance_delay")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val SHOW_FILE_NAME = booleanPreferencesKey("show_file_name")
        val ASPECT_RATIO_MODE = stringPreferencesKey("aspect_ratio_mode")
        val LANDSCAPE_VIDEO_MODE = booleanPreferencesKey("landscape_video_mode")
        val RECENT_FOLDERS = stringPreferencesKey("recent_folders")
    }

    /** The stored preferences; an `IOException` while reading yields empty preferences (defaults). */
    private val data: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: Flow<AppSettings> = data.map { prefs ->
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
            aspectRatioMode = try {
                AspectRatioMode.valueOf(prefs[Keys.ASPECT_RATIO_MODE] ?: AspectRatioMode.SMART.name)
            } catch (_: Exception) {
                AspectRatioMode.SMART
            },
            landscapeVideoMode = prefs[Keys.LANDSCAPE_VIDEO_MODE] ?: false
        )
    }

    val recentFolders: Flow<List<String>> = data.map { prefs ->
        val raw = prefs[Keys.RECENT_FOLDERS] ?: ""
        if (raw.isBlank()) emptyList() else raw.split("|||")
    }

    /** Runs an edit; returns `false` (and changes nothing) if storage fails. */
    private suspend fun safeEdit(transform: suspend (MutablePreferences) -> Unit): Boolean =
        try {
            dataStore.edit(transform)
            true
        } catch (_: IOException) {
            false
        }

    suspend fun updateLoopVideos(value: Boolean): Boolean =
        safeEdit { it[Keys.LOOP_VIDEOS] = value }

    suspend fun updateShuffleMedia(value: Boolean): Boolean =
        safeEdit { it[Keys.SHUFFLE_MEDIA] = value }

    suspend fun updateSortOrder(order: SortOrder): Boolean =
        safeEdit { it[Keys.SORT_ORDER] = order.name }

    suspend fun updateAutoAdvanceImages(value: Boolean): Boolean =
        safeEdit { it[Keys.AUTO_ADVANCE_IMAGES] = value }

    suspend fun updateAutoAdvanceDelay(seconds: Int): Boolean =
        safeEdit { it[Keys.AUTO_ADVANCE_DELAY] = seconds }

    suspend fun updateHapticFeedback(value: Boolean): Boolean =
        safeEdit { it[Keys.HAPTIC_FEEDBACK] = value }

    suspend fun updateShowFileName(value: Boolean): Boolean =
        safeEdit { it[Keys.SHOW_FILE_NAME] = value }

    suspend fun updateAspectRatioMode(mode: AspectRatioMode): Boolean =
        safeEdit { it[Keys.ASPECT_RATIO_MODE] = mode.name }

    suspend fun updateLandscapeVideoMode(value: Boolean): Boolean =
        safeEdit { it[Keys.LANDSCAPE_VIDEO_MODE] = value }

    suspend fun addRecentFolder(uriString: String, displayName: String): Boolean = safeEdit { prefs ->
        val existing = (prefs[Keys.RECENT_FOLDERS] ?: "")
            .split("|||")
            .filter { it.isNotBlank() && !it.startsWith("$uriString<<>>") }
        val entry = "$uriString<<>>$displayName<<>>0"
        val updated = (listOf(entry) + existing).take(10)
        prefs[Keys.RECENT_FOLDERS] = updated.joinToString("|||")
    }

    /**
     * Saves the viewer position for a listed folder: the full-list [index] and, when known, the
     * item's URI ([itemUri], the optional 4th field). Unlisted folders are left alone.
     */
    suspend fun updateLastViewed(folderUri: String, index: Int, itemUri: String? = null): Boolean = safeEdit { prefs ->
        val allFolders = (prefs[Keys.RECENT_FOLDERS] ?: "")
            .split("|||")
            .filter { it.isNotBlank() }

        val updated = allFolders.map { entry ->
            if (entry.startsWith("$folderUri<<>>")) {
                val parts = entry.split("<<>>")
                if (parts.size >= 2) {
                    val base = "${parts[0]}<<>>${parts[1]}<<>>$index"
                    if (itemUri.isNullOrEmpty()) base else "$base<<>>$itemUri"
                } else entry
            } else {
                entry
            }
        }
        prefs[Keys.RECENT_FOLDERS] = updated.joinToString("|||")
    }

    /** Legacy entry point: saves only the index (no item URI). */
    suspend fun updateLastViewedIndex(uriString: String, index: Int): Boolean =
        updateLastViewed(uriString, index, null)

    suspend fun removeRecentFolder(uriString: String): Boolean = safeEdit { prefs ->
        val existing = (prefs[Keys.RECENT_FOLDERS] ?: "")
            .split("|||")
            .filter { it.isNotBlank() && !it.startsWith("$uriString<<>>") }
        prefs[Keys.RECENT_FOLDERS] = existing.joinToString("|||")
    }

    /** The Recent entry for [folderUri], if listed. */
    suspend fun recentFolder(folderUri: String): RecentFolderInfo? =
        recentFolders.first()
            .find { it.startsWith("$folderUri<<>>") }
            ?.let { parseRecentFolderEntry(it) }

    /** Parses `uri<<>>name<<>>index` (every released version) or `uri<<>>name<<>>index<<>>itemUri`. */
    fun parseRecentFolderEntry(entry: String): RecentFolderInfo? {
        val parts = entry.split("<<>>")
        return if (parts.size >= 2) {
            val lastIndex = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val lastItemUri = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
            RecentFolderInfo(parts[0], parts[1], lastIndex, lastItemUri)
        } else null
    }
}

data class RecentFolderInfo(
    val uri: String,
    val name: String,
    val lastIndex: Int,
    val lastItemUri: String? = null,
)
