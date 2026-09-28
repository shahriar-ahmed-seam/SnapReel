package com.snapreel.app.util.thumbnail

import android.content.Context
import android.net.Uri
import coil3.Extras
import coil3.key.Keyer
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.crossfade
import com.snapreel.app.data.model.MediaItem

/**
 * Coil data type for a video tile's thumbnail.
 *
 * Its [key] identifies one version of one file: a change in size or dateModified gives a new key,
 * so an edited or replaced video never shows a stale thumbnail. The key is used both as the
 * memory cache key and as the persistent [ThumbnailDiskCache] key.
 */
data class VideoThumbnail(
    val uri: Uri,
    val size: Long,
    val dateModified: Long,
    val supportsThumbnail: Boolean,
) {
    val key: String get() = "vt1|$uri|$size|$dateModified"

    companion object {
        fun of(item: MediaItem) = VideoThumbnail(item.uri, item.size, item.dateModified, item.supportsThumbnail)
    }
}

class VideoThumbnailKeyer : Keyer<VideoThumbnail> {
    override fun key(data: VideoThumbnail, options: Options): String = data.key
}

/** When true, the request reads memory/disk only and never generates (used by the viewers). */
val CacheOnlyKey = Extras.Key(default = false)

/** Target short side of a generated thumbnail and the size every thumbnail request asks for. */
const val THUMB_PX = 300

/**
 * The only way the grid and the viewers build a video thumbnail request, so their memory keys and
 * sizes always match: a viewer's cache-only preview hits the entry the grid produced.
 */
fun videoThumbnailRequest(
    context: Context,
    item: MediaItem,
    cacheOnly: Boolean,
    crossfade: Boolean,
): ImageRequest {
    val data = VideoThumbnail.of(item)
    return ImageRequest.Builder(context)
        .data(data)
        .size(THUMB_PX, THUMB_PX)
        .memoryCacheKey(data.key)
        .crossfade(crossfade)
        .apply { extras[CacheOnlyKey] = cacheOnly }
        .build()
}
