package com.snapreel.app.preservation

import android.net.Uri
import android.provider.DocumentsContract
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.SortOrder
import org.json.JSONObject

/**
 * Property 2: Preservation - the ORIGINAL (unfixed) logic, copied verbatim.
 *
 * Every function here is a frozen copy of code in the unfixed app (the source location is noted on
 * each one). Only the plumbing around the logic changed: player calls, `_uiState.update`,
 * `dataStore.edit` and the network are replaced by plain values, so the logic can run as an
 * oracle. The preservation tests assert `production == oracle` for inputs outside the bug
 * condition. Never edit these to match new behavior: a mismatch is a regression in the app.
 */
object LegacyOracles {

    // ─── Order (MediaRepository) ────────────────────────────────────────────────────────

    /** Verbatim `MediaRepository.sortMedia` (private in the app). */
    fun sortMedia(items: List<MediaItem>, order: SortOrder): List<MediaItem> {
        return when (order) {
            SortOrder.NAME_ASC -> items.sortedBy { it.name.lowercase() }
            SortOrder.NAME_DESC -> items.sortedByDescending { it.name.lowercase() }
            SortOrder.DATE_NEWEST -> items.sortedByDescending { it.dateModified }
            SortOrder.DATE_OLDEST -> items.sortedBy { it.dateModified }
            SortOrder.SIZE_LARGEST -> items.sortedByDescending { it.size }
            SortOrder.SIZE_SMALLEST -> items.sortedBy { it.size }
            SortOrder.TYPE_VIDEO_FIRST -> items.sortedByDescending { it.isVideo }
            SortOrder.TYPE_IMAGE_FIRST -> items.sortedBy { it.isVideo }
        }
    }

    /**
     * Verbatim row handling from `MediaRepository.scanDocumentTree` for a non-directory child:
     * which rows are kept and how the [MediaItem] is built. Returns null for a skipped row.
     */
    fun scanRow(treeUri: Uri, docId: String, name: String?, mimeOrNull: String?, size: Long, date: Long): MediaItem? {
        val nameValue = name ?: return null
        val mime = mimeOrNull ?: ""
        return if (MediaItem.isSupportedExtension(nameValue) ||
            MediaItem.isVideoMime(mime) || MediaItem.isImageMime(mime)
        ) {
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val isVideo = MediaItem.isVideoMime(mime) || MediaItem.isVideoExtension(name)
            MediaItem(
                uri = fileUri,
                name = nameValue,
                mimeType = mime,
                size = size,
                dateModified = date,
                isVideo = isVideo,
            )
        } else null
    }

    // ─── Grid ↔ landscape index mapping ─────────────────────────────────────────────────

    /** Verbatim `displayedItems` from `FolderMediaGridScreen`. */
    fun gridDisplayedItems(mediaItems: List<MediaItem>, isLandscapeMode: Boolean): List<MediaItem> =
        if (isLandscapeMode) mediaItems.filter { it.isVideo } else mediaItems

    /** Verbatim tile `clickable` in `FolderMediaGridScreen`: displayed index → index passed to the viewer route. */
    fun gridTapToFullIndex(mediaItems: List<MediaItem>, isLandscapeMode: Boolean, index: Int): Int {
        val displayedItems = gridDisplayedItems(mediaItems, isLandscapeMode)
        val item = displayedItems[index]
        val originalIndex = mediaItems.indexOfFirst { it.uri == item.uri }
        return if (originalIndex >= 0) originalIndex else index
    }

    /**
     * Verbatim scroll-restore `LaunchedEffect` in `FolderMediaGridScreen`: the full-list index
     * returned by a viewer → the grid position scrolled to, or null when no scroll happens.
     */
    fun gridScrollIndex(mediaItems: List<MediaItem>, isLandscapeMode: Boolean, targetIndex: Int): Int? {
        val displayedItems = gridDisplayedItems(mediaItems, isLandscapeMode)
        if (displayedItems.isEmpty()) return null
        val scrollIndex = if (isLandscapeMode) {
            val targetUri = mediaItems.getOrNull(targetIndex)?.uri
            val found = displayedItems.indexOfFirst { it.uri == targetUri }
            if (found >= 0) found else 0
        } else {
            targetIndex
        }
        return if (scrollIndex in displayedItems.indices) scrollIndex else null
    }

    /**
     * Verbatim `LandscapeVideoViewerViewModel.loadVideos`: full-list `requestedIndex` → video-list
     * index. Returns null when the folder has no videos (the viewer shows "No videos found").
     */
    fun fullToLandscapeIndex(allItems: List<MediaItem>, requestedIndex: Int): Int? {
        val videoList = allItems.filter { it.isVideo }
        if (videoList.isEmpty()) return null
        val targetVideoUri = allItems.getOrNull(requestedIndex)?.uri
        return if (targetVideoUri != null) {
            val found = videoList.indexOfFirst { it.uri == targetVideoUri }
            if (found >= 0) found else 0
        } else {
            requestedIndex.coerceIn(0, videoList.size - 1)
        }
    }

    /**
     * Verbatim `LandscapeVideoViewerViewModel.saveLastViewedIndex`: video-list index → the full-list
     * index saved to Recents. Returns null when nothing is saved.
     */
    fun landscapeToFullIndex(allItems: List<MediaItem>, videoIndex: Int): Int? {
        val videos = allItems.filter { it.isVideo }
        val currentVideo = videos.getOrNull(videoIndex) ?: return null
        val originalIndex = allItems.indexOfFirst { it.uri == currentVideo.uri }
        return if (originalIndex >= 0) originalIndex else videoIndex
    }

    /** Verbatim `ReelsViewerViewModel.loadMedia` (cached branch): the start index shown in the reels viewer. */
    fun reelsStartIndex(items: List<MediaItem>, startIndex: Int): Int? =
        if (items.isEmpty()) null else startIndex.coerceIn(0, items.size - 1)

    // ─── Reels 3-state tap cycle (ReelsViewerViewModel + VideoPage) ─────────────────────

    /** `VideoPage`: `LaunchedEffect(showControls, isPlaying, isDraggingSlider) { delay(3000); onControlsTimeout() }`. */
    const val REELS_AUTO_HIDE_MS = 3_000L

    /** `LandscapeVideoViewerScreen`: the same effect with `delay(3500)` (recorded, not driven here). */
    const val LANDSCAPE_AUTO_HIDE_MS = 3_500L

    /**
     * Verbatim state logic of `ReelsViewerViewModel` (`ViewerUiState` defaults, `loadMedia`'s cached
     * branch, `onPageSettled`, `onVideoTap`, `onImageTap`, `onControlsTimeout`, `onAppPaused`,
     * `toggleMute`) with the `playerManager` calls dropped. Page taps are routed as the screen does:
     * a video page's `VideoPage.onTap` → `onVideoTap`, an image page's `ImagePage.onTap` → `onImageTap`.
     */
    class ReelsTapMachine(private val isVideoAt: List<Boolean>) {
        var currentIndex = 0; private set
        var isPlaying = true; private set
        var isMuted = false; private set
        var showControls = false; private set

        fun loadCached(startIndex: Int) {
            if (isVideoAt.isEmpty()) return
            val safeIndex = startIndex.coerceIn(0, isVideoAt.size - 1)
            currentIndex = safeIndex
            onPageSettled(safeIndex)
        }

        fun onPageSettled(index: Int) {
            if (index < 0 || index >= isVideoAt.size) return
            currentIndex = index
            if (isVideoAt[index]) {
                isPlaying = true; showControls = false
            } else {
                isPlaying = false; showControls = false
            }
        }

        fun tap() = if (isVideoAt.getOrNull(currentIndex) == true) onVideoTap() else onImageTap()

        fun onVideoTap() {
            when {
                !showControls && isPlaying -> showControls = true
                showControls && isPlaying -> isPlaying = false
                showControls && !isPlaying -> { isPlaying = true; showControls = false }
                else -> showControls = true
            }
        }

        fun onImageTap() {
            showControls = !showControls
        }

        fun onControlsTimeout() {
            if (isPlaying && showControls) showControls = false
        }

        fun onAppPaused() {
            if (isVideoAt.getOrNull(currentIndex) == true) {
                isPlaying = false; showControls = true
            }
        }

        fun toggleMute() {
            isMuted = !isMuted
        }
    }

    // ─── Recents (AppPreferences) ───────────────────────────────────────────────────────

    /** The unfixed Recent entry fields (`RecentFolderInfo`). */
    data class RecentEntry(val uri: String, val name: String, val lastIndex: Int)

    /** Verbatim `AppPreferences.recentFolders` mapping of the raw `recent_folders` value. */
    fun recentFolders(raw: String?): List<String> {
        val value = raw ?: ""
        return if (value.isBlank()) emptyList() else value.split("|||")
    }

    /** Verbatim `AppPreferences.addRecentFolder` edit: old raw value → new raw value. */
    fun addRecentFolder(raw: String?, uriString: String, displayName: String): String {
        val existing = (raw ?: "")
            .split("|||")
            .filter { it.isNotBlank() && !it.startsWith("$uriString<<>>") }
        val entry = "$uriString<<>>$displayName<<>>0"
        val updated = (listOf(entry) + existing).take(10)
        return updated.joinToString("|||")
    }

    /** Verbatim `AppPreferences.updateLastViewedIndex` edit. */
    fun updateLastViewedIndex(raw: String?, uriString: String, index: Int): String {
        val allFolders = (raw ?: "")
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
        return updated.joinToString("|||")
    }

    /** Verbatim `AppPreferences.removeRecentFolder` edit. */
    fun removeRecentFolder(raw: String?, uriString: String): String {
        val existing = (raw ?: "")
            .split("|||")
            .filter { it.isNotBlank() && !it.startsWith("$uriString<<>>") }
        return existing.joinToString("|||")
    }

    /** Verbatim `AppPreferences.parseRecentFolderEntry`. */
    fun parseRecentFolderEntry(entry: String): RecentEntry? {
        val parts = entry.split("<<>>")
        return if (parts.size >= 2) {
            val lastIndex = parts.getOrNull(2)?.toIntOrNull() ?: 0
            RecentEntry(parts[0], parts[1], lastIndex)
        } else null
    }

    /** Verbatim `getSavedLastIndex` (grid and reels ViewModels) over the raw value. */
    fun savedLastIndex(raw: String?, folderUri: String): Int {
        val entries = recentFolders(raw)
        val match = entries.find { it.startsWith("${folderUri}<<>>") }
        return match?.let { parseRecentFolderEntry(it)?.lastIndex } ?: 0
    }

    // ─── Update offer (UpdateManager) ───────────────────────────────────────────────────

    /** Verbatim `UpdateManager.isNewerVersion`. */
    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        if (remoteVersion.isBlank() || currentVersion.isBlank()) return false
        val remoteParts = remoteVersion.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentVersion.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val remote = remoteParts.getOrElse(i) { 0 }
            val current = currentParts.getOrElse(i) { 0 }
            if (remote > current) return true
            if (remote < current) return false
        }
        return false
    }

    /** The offer fields the update dialog shows (`AppUpdateInfo` in the unfixed app). */
    data class Offer(
        val versionName: String,
        val releaseTitle: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val apkSize: Long,
    )

    /**
     * Verbatim response handling from `UpdateManager.checkForUpdates`, after the connection:
     * status code and body → the offer, or null (no dialog). [currentVersionName] stands in for
     * `BuildConfig.VERSION_NAME`.
     */
    fun parseReleaseOffer(responseCode: Int, responseText: String, currentVersionName: String): Offer? {
        try {
            if (responseCode != 200) {
                return null
            }

            val json = JSONObject(responseText)

            val tagName = json.optString("tag_name", "").removePrefix("v").trim()
            val releaseTitle = json.optString("name", "New Update Available")
            val releaseNotes = json.optString("body", "Bug fixes and performance improvements.")
            val currentVersion = currentVersionName.removePrefix("v").trim()

            if (!isNewerVersion(tagName, currentVersion)) {
                return null
            }

            // Find APK in assets
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            var apkSize = 0L

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }

            if (apkUrl.isNullOrBlank()) {
                return null
            }

            return Offer(
                versionName = tagName,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                downloadUrl = apkUrl,
                apkSize = apkSize,
            )
        } catch (e: Exception) {
            return null
        }
    }
}
