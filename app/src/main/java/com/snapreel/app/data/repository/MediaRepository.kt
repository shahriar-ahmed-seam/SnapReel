package com.snapreel.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.preferences.SortOrder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences
) {
    private val mediaCache = java.util.concurrent.ConcurrentHashMap<String, List<MediaItem>>()

    fun getCachedMedia(treeUri: Uri): List<MediaItem>? {
        return mediaCache[treeUri.toString()]
    }

    suspend fun scanFolder(treeUri: Uri, forceRefresh: Boolean = false): List<MediaItem> = withContext(Dispatchers.IO) {
        val uriString = treeUri.toString()
        
        if (!forceRefresh && mediaCache.containsKey(uriString)) {
            return@withContext mediaCache[uriString]!!
        }

        val items = mutableListOf<MediaItem>()
        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        scanDocumentTree(treeUri, docUri, items)

        val settings = appPreferences.settings.first()
        val sorted = sortMedia(items, settings.sortOrder)
        val result = if (settings.shuffleMedia) sorted.shuffled() else sorted
        
        mediaCache[uriString] = result
        result
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
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val dateIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

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
                    items.add(
                        MediaItem(
                            uri = fileUri,
                            name = name,
                            mimeType = mime,
                            size = size,
                            dateModified = date,
                            isVideo = isVideo
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

    fun getFolderDisplayName(treeUri: Uri): String {
        return try {
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
}
