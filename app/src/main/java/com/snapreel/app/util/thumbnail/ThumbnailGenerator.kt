package com.snapreel.app.util.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Why a thumbnail couldn't be generated.
 *
 * @property permanent the file is gone or unreadable (`FileNotFoundException`/`SecurityException`),
 *   so retrying in this process is pointless. Otherwise (no frame, decoder error) a retry may work.
 */
class ThumbnailGenerationException(
    message: String,
    val permanent: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Produces a thumbnail bitmap (short side ≈ [THUMB_PX]) for one video. Blocking; runs on a worker. */
fun interface ThumbnailGenerator {
    /** @throws ThumbnailGenerationException when no thumbnail can be produced. */
    fun generate(thumbnail: VideoThumbnail): Bitmap
}

/**
 * Sources, fastest first:
 * 1. The provider's own thumbnail when the document has `FLAG_SUPPORTS_THUMBNAIL`.
 * 2. A frame decoded with [MediaMetadataRetriever], scaled to a [THUMB_PX] short side
 *    (matching the grid's crop). The retriever is always released.
 */
@Singleton
class AndroidThumbnailGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
) : ThumbnailGenerator {

    override fun generate(thumbnail: VideoThumbnail): Bitmap {
        if (thumbnail.supportsThumbnail) {
            providerThumbnail(thumbnail)?.let { return it }
        }
        return decodeFrame(thumbnail)
    }

    private fun providerThumbnail(thumbnail: VideoThumbnail): Bitmap? =
        try {
            DocumentsContract.getDocumentThumbnail(
                context.contentResolver,
                thumbnail.uri,
                Point(THUMB_PX, THUMB_PX),
                null,
            )
        } catch (_: Exception) {
            // Fall back to decoding a frame, which classifies a missing/unreadable file precisely.
            null
        }

    private fun decodeFrame(thumbnail: VideoThumbnail): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            val pfd = context.contentResolver.openFileDescriptor(thumbnail.uri, "r")
                ?: throw ThumbnailGenerationException("No file descriptor for ${thumbnail.uri}", permanent = true)
            pfd.use { retriever.setDataSource(it.fileDescriptor) }

            val width = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            // A square box of the scaled long side: the frame keeps its aspect ratio inside it, so
            // its short side lands on THUMB_PX whatever the rotation metadata says.
            val box = scaledLongSide(width, height)

            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, box, box)
                    ?: retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, box, box)
            } else {
                val source = retriever.getFrameAtTime(FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                source?.let { scaleToShortSide(it) }
            }
            return frame ?: throw ThumbnailGenerationException("No frame for ${thumbnail.uri}", permanent = false)
        } catch (e: ThumbnailGenerationException) {
            throw e
        } catch (e: FileNotFoundException) {
            throw ThumbnailGenerationException("Video not found: ${thumbnail.uri}", permanent = true, e)
        } catch (e: SecurityException) {
            throw ThumbnailGenerationException("No access to ${thumbnail.uri}", permanent = true, e)
        } catch (e: RuntimeException) {
            throw ThumbnailGenerationException("Frame decode failed for ${thumbnail.uri}", permanent = false, e)
        } catch (e: Exception) {
            throw ThumbnailGenerationException("Frame decode failed for ${thumbnail.uri}", permanent = false, e)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun MediaMetadataRetriever.intMetadata(key: Int): Int =
        extractMetadata(key)?.toIntOrNull() ?: 0

    /** API 26 path: scale to a THUMB_PX short side, then recycle the full-size source. */
    private fun scaleToShortSide(source: Bitmap): Bitmap {
        val shortSide = min(source.width, source.height)
        if (shortSide <= THUMB_PX) return source
        val scale = THUMB_PX.toFloat() / shortSide
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private companion object {
        const val FRAME_TIME_US = 1_000_000L

        /** Long side after scaling the short side down to THUMB_PX (never upscales). */
        fun scaledLongSide(width: Int, height: Int): Int {
            if (width <= 0 || height <= 0) return THUMB_PX
            val shortSide = min(width, height)
            val longSide = max(width, height)
            if (shortSide <= THUMB_PX) return longSide
            return (longSide.toLong() * THUMB_PX / shortSide).toInt().coerceAtLeast(THUMB_PX)
        }
    }
}
