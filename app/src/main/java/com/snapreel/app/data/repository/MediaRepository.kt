package com.snapreel.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.VisibleForTesting
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.preferences.SortOrder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Why a folder can't be read. */
enum class AccessLostReason {
    /** The app's grant is gone (revoked, or never restored after a reinstall or restore). */
    PERMISSION,

    /** The folder was moved, renamed or deleted. */
    MISSING,
}

/** Why a folder load produced no items. */
sealed interface FolderLoadError {
    data class AccessLost(val reason: AccessLostReason) : FolderLoadError
    data object NoMedia : FolderLoadError
    data object NoVideos : FolderLoadError
    data class Failed(val message: String) : FolderLoadError
}

/** Thrown by [MediaRepository.scanFolder] when the folder can't be read at all. */
class FolderAccessLostException(
    val reason: AccessLostReason,
    cause: Throwable? = null,
) : Exception("Folder access lost: $reason", cause)

/** Lists every supported media file under a tree (unsorted). Throws [FolderAccessLostException]. */
fun interface FolderScanner {
    fun scan(treeUri: Uri): List<MediaItem>
}

/** The text shown for a load error (access loss has its own screen). */
val FolderLoadError.displayMessage: String
    get() = when (this) {
        is FolderLoadError.AccessLost -> ACCESS_LOST_MESSAGE
        FolderLoadError.NoMedia -> "No media found in this folder"
        FolderLoadError.NoVideos -> "No videos found in this folder"
        is FolderLoadError.Failed -> "Failed to load media: $message"
    }

const val ACCESS_LOST_MESSAGE =
    "SnapReel no longer has access to this folder. It may have been moved, renamed or deleted, " +
        "or access was reset after a reinstall or restore."

/** Maps a load failure to its [FolderLoadError]. */
fun Throwable.toFolderLoadError(): FolderLoadError = when (this) {
    is FolderAccessLostException -> FolderLoadError.AccessLost(reason)
    else -> FolderLoadError.Failed(message ?: javaClass.simpleName)
}

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences
) {
    /** One snapshot per folder: the grid and both viewers share it until the folder is rescanned. */
    private val mediaCache = ConcurrentHashMap<String, List<MediaItem>>()

    /** Single flight per tree: one scan at a time, and a waiting caller reuses a fresh result. */
    private val scanLocks = ConcurrentHashMap<String, Mutex>()

    /** The document walk. A test seam: tests swap in a fake scanner. */
    @VisibleForTesting
    internal var scanner: FolderScanner = FolderScanner { treeUri -> scanDocuments(treeUri) }

    fun getCachedMedia(treeUri: Uri): List<MediaItem>? {
        return mediaCache[treeUri.toString()]
    }

    /**
     * The folder's items, sorted (or shuffled) with the current settings. [forceRefresh] rescans
     * even if a snapshot exists. Throws [FolderAccessLostException] if the folder can't be read.
     */
    suspend fun scanFolder(treeUri: Uri, forceRefresh: Boolean = false): List<MediaItem> = withContext(Dispatchers.IO) {
        val uriString = treeUri.toString()
        val lock = scanLocks.getOrPut(uriString) { Mutex() }
        lock.withLock {
            if (!forceRefresh) {
                mediaCache[uriString]?.let { return@withLock it }
            }

            val items = scanner.scan(treeUri)

            val settings = appPreferences.settings.first()
            val sorted = sortMedia(items, settings.sortOrder)
            val result = if (settings.shuffleMedia) sorted.shuffled() else sorted

            mediaCache[uriString] = result
            result
        }
    }

    /** The real scanner: checks the root document, then walks the tree. */
    private fun scanDocuments(treeUri: Uri): List<MediaItem> {
        val rootDocUri = checkRootReadable(treeUri)
        val items = mutableListOf<MediaItem>()
        try {
            scanDocumentTree(treeUri, rootDocUri, items)
        } catch (e: SecurityException) {
            throw FolderAccessLostException(AccessLostReason.PERMISSION, e)
        }
        return items
    }

    /**
     * Queries the tree's root document. A `SecurityException` means the grant is gone; a null
     * cursor, no row, `FileNotFoundException` or `IllegalArgumentException` means the folder is gone.
     */
    private fun checkRootReadable(treeUri: Uri): Uri {
        try {
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val cursor = context.contentResolver.query(
                rootDocUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            ) ?: throw FolderAccessLostException(AccessLostReason.MISSING)
            val hasRow = cursor.use { it.moveToFirst() }
            if (!hasRow) throw FolderAccessLostException(AccessLostReason.MISSING)
            return rootDocUri
        } catch (e: SecurityException) {
            throw FolderAccessLostException(AccessLostReason.PERMISSION, e)
        } catch (e: FileNotFoundException) {
            throw FolderAccessLostException(AccessLostReason.MISSING, e)
        } catch (e: IllegalArgumentException) {
            throw FolderAccessLostException(AccessLostReason.MISSING, e)
        }
    }

    private fun scanDocumentTree(treeUri: Uri, docUri: Uri, items: MutableList<MediaItem>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(docUri)
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val dateIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            // Optional: some providers omit COLUMN_FLAGS, which then reads as "no provider thumbnail".
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex) ?: continue
                val mime = cursor.getString(mimeIndex) ?: ""
                val size = cursor.getLong(sizeIndex)
                val date = cursor.getLong(dateIndex)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    // Recurse into subdirectories
                    val subDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    scanDocumentTree(treeUri, subDocUri, items)
                } else if (MediaItem.isSupportedExtension(name) ||
                    MediaItem.isVideoMime(mime) || MediaItem.isImageMime(mime)) {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val isVideo = MediaItem.isVideoMime(mime) || MediaItem.isVideoExtension(name)
                    val flags = if (flagsIndex >= 0 && !cursor.isNull(flagsIndex)) cursor.getInt(flagsIndex) else 0
                    items.add(
                        MediaItem(
                            uri = fileUri,
                            name = name,
                            mimeType = mime,
                            size = size,
                            dateModified = date,
                            isVideo = isVideo,
                            supportsThumbnail =
                                flags and DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL != 0
                        )
                    )
                }
            }
        }
    }

    private fun sortMedia(items: List<MediaItem>, order: SortOrder): List<MediaItem> {
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

    /** The folder's name from its provider, looked up off the main thread (providers can be slow). */
    suspend fun getFolderDisplayName(treeUri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: treeUri.lastPathSegment?.substringAfterLast(':') ?: "Unknown"
        } catch (_: Exception) {
            treeUri.lastPathSegment?.substringAfterLast(':') ?: "Unknown"
        }
    }

    /**
     * Persists read access to a picked tree. Some providers don't offer persistable grants; then
     * this returns `false` (the folder still opens now, but access may not survive a restart).
     */
    fun takePersistableAccess(treeUri: Uri): Boolean =
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            false
        }

    /** Adds a picked folder to the top of Recents, with its provider display name. */
    suspend fun addPickedFolder(treeUri: Uri) {
        val displayName = getFolderDisplayName(treeUri)
        appPreferences.addRecentFolder(treeUri.toString(), displayName)
    }

    /** Whether two tree URIs name the same folder: same authority and the same tree document ID. */
    fun isSameTree(a: Uri, b: Uri): Boolean {
        if (a.authority != b.authority) return false
        return try {
            DocumentsContract.getTreeDocumentId(a) == DocumentsContract.getTreeDocumentId(b)
        } catch (_: IllegalArgumentException) {
            a == b
        }
    }
}
