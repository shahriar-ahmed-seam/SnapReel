package com.snapreel.app.testsupport

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.FileNotFoundException

/**
 * Minimal SAF-style provider for Robolectric tests.
 *
 * It exposes one tree (`root`) whose children are [files]. Every file is listed by the scan but
 * can't be opened (`openFile` throws [FileNotFoundException]), which models a video that was
 * moved or deleted after the folder was scanned.
 *
 * Register with `Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(AUTHORITY)`.
 */
class FakeDocumentsProvider : ContentProvider() {

    data class FakeFile(
        val documentId: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long,
    )

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        lastQueryThread = Thread.currentThread()
        val columns = projection ?: DEFAULT_COLUMNS
        val cursor = MatrixCursor(columns)
        if (uri.lastPathSegment == "children") {
            files.forEach { f ->
                cursor.addRow(columns.map { col -> valueFor(col, f.documentId, f.name, f.mimeType, f.size, f.lastModified) })
            }
        } else {
            when (rootMode) {
                RootMode.NORMAL -> Unit
                RootMode.SECURITY_EXCEPTION -> throw SecurityException("Fake: permission to $uri was revoked")
                RootMode.NULL_CURSOR -> return null
                RootMode.NO_ROW -> return cursor
                RootMode.FILE_NOT_FOUND -> throw FileNotFoundException("Fake: $uri was moved or deleted")
            }
            // Root (or any single document) lookup: the tree root is a readable directory.
            cursor.addRow(
                columns.map { col ->
                    valueFor(col, ROOT_ID, "Fake Folder", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 0L)
                }
            )
        }
        return cursor
    }

    private fun valueFor(col: String, id: String, name: String, mime: String, size: Long, modified: Long): Any? =
        when (col) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name
            DocumentsContract.Document.COLUMN_MIME_TYPE -> mime
            DocumentsContract.Document.COLUMN_SIZE -> size
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> modified
            DocumentsContract.Document.COLUMN_FLAGS -> 0
            else -> null
        }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        throw FileNotFoundException("Fake document was moved or deleted: $uri")

    override fun getType(uri: Uri): String? =
        files.firstOrNull { uri.toString().endsWith(Uri.encode(it.documentId)) }?.mimeType

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.snapreel.test.documents"
        const val ROOT_ID = "root"

        private val DEFAULT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )

        /** Children of the tree root. Tests set this before scanning. */
        @Volatile
        var files: List<FakeFile> = emptyList()

        /** How a root (single-document) query behaves: readable, or one of the access-loss shapes. */
        enum class RootMode { NORMAL, SECURITY_EXCEPTION, NULL_CURSOR, NO_ROW, FILE_NOT_FOUND }

        @Volatile
        var rootMode: RootMode = RootMode.NORMAL

        /** The thread that ran the most recent query. */
        @Volatile
        var lastQueryThread: Thread? = null

        /** Restores the defaults (no files, readable root). */
        fun reset() {
            files = emptyList()
            rootMode = RootMode.NORMAL
            lastQueryThread = null
        }

        val treeUri: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)

        fun documentUri(documentId: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }
}
