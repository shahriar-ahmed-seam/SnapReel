package com.snapreel.app.util.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.disk.DiskCache
import coil3.disk.directory
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Persistent, size-bounded LRU of video thumbnails (JPEG), backed by Coil's journaled [DiskCache].
 *
 * Production lives in `noBackupFilesDir/video_thumbs`: internal storage (never reaches the gallery,
 * so no `.nomedia` is needed) and excluded from Auto Backup. Entries are keyed by
 * [VideoThumbnail.key]. Reads move an entry to the most-recently-used end, and writes evict
 * least-recently-read entries once the total exceeds [maxSizeBytes].
 *
 * One instance per directory (Coil's cache isn't safe to open twice), so it's a singleton in DI.
 *
 * @param cleanupContext where Coil trims the cache after a write. Tests pass
 *   `Dispatchers.Unconfined` so trimming runs inside the write and the bound holds right after it.
 */
class ThumbnailDiskCache(
    directory: File,
    val maxSizeBytes: Long = DEFAULT_MAX_BYTES,
    cleanupContext: CoroutineContext = Dispatchers.IO,
) {
    private val cache: DiskCache = DiskCache.Builder()
        .directory(directory)
        .maxSizeBytes(maxSizeBytes)
        .cleanupCoroutineContext(cleanupContext)
        .build()

    /** Total bytes of all committed entries. */
    val size: Long get() = cache.size

    /**
     * Decodes the entry for [key], or returns null on a miss. An entry that can't be decoded is
     * removed (so it gets regenerated) and reads as a miss.
     */
    fun read(key: String, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
        val snapshot = try {
            cache.openSnapshot(key)
        } catch (_: Exception) {
            null
        } ?: return null
        val bitmap = snapshot.use {
            val path = it.data.toFile().absolutePath
            decode(path, config)
                // A hardware allocation can fail (e.g. under memory pressure); fall back to software
                // before treating the entry as corrupt.
                ?: if (config == Bitmap.Config.HARDWARE) decode(path, Bitmap.Config.ARGB_8888) else null
        }
        if (bitmap == null) remove(key)
        return bitmap
    }

    private fun decode(path: String, config: Bitmap.Config): Bitmap? =
        try {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inPreferredConfig = config })
        } catch (_: Exception) {
            null
        }

    /** Whether a committed entry exists for [key]. Doesn't count as a read for LRU order. */
    fun contains(key: String): Boolean =
        try {
            cache.fileSystem.exists(cache.directory.resolve(entryFileName(key)))
        } catch (_: Exception) {
            false
        }

    /**
     * Stores [bitmap] as JPEG q80. Returns false when the entry is being written by someone else
     * or the write fails (the partial entry is aborted).
     */
    fun write(key: String, bitmap: Bitmap): Boolean {
        val editor = try {
            cache.openEditor(key)
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            val ok = editor.data.toFile().outputStream().buffered().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (ok) {
                editor.commit()
                true
            } else {
                editor.abort()
                false
            }
        } catch (_: Exception) {
            runCatching { editor.abort() }
            false
        }
    }

    fun remove(key: String): Boolean = runCatching { cache.remove(key) }.getOrDefault(false)

    fun clear() = cache.clear()

    fun shutdown() = cache.shutdown()

    companion object {
        const val DIRECTORY_NAME = "video_thumbs"
        const val DEFAULT_MAX_BYTES = 100L * 1024 * 1024
        const val JPEG_QUALITY = 80

        /**
         * Coil stores an entry's data as `<sha256(key)>.1` (`.0` is the metadata file).
         * Only used by [contains], which must not touch the LRU order.
         */
        private fun entryFileName(key: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            return digest.joinToString("") { "%02x".format(it) } + ".1"
        }
    }
}
