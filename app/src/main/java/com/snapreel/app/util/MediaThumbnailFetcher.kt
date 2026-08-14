package com.snapreel.app.util

import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options

/**
 * Ultra-Fast Video Thumbnail Fetcher powered by PersistentThumbnailStore.
 * Delegates to internal persistent storage (`filesDir/app_thumbnails/`) protected by `.nomedia`.
 */
class MediaThumbnailFetcher(
    private val uri: Uri,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bitmap = PersistentThumbnailStore.getOrGenerateThumbnail(options.context, uri)
        if (bitmap != null) {
            return ImageFetchResult(
                image = bitmap.asImage(),
                isSampled = false,
                dataSource = DataSource.DISK
            )
        }
        return null
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val uriStr = data.toString().lowercase()
            val isVideo = uriStr.endsWith(".mp4") || uriStr.endsWith(".mkv") || uriStr.endsWith(".mov") ||
                          uriStr.endsWith(".3gp") || uriStr.endsWith(".webm") || uriStr.endsWith(".avi") ||
                          uriStr.contains("video") || uriStr.contains(".mp4") || uriStr.contains(".mkv")

            // Only intercept videos. Images will be processed directly by Coil's native image decoders!
            if (isVideo && data.scheme == "content") {
                return MediaThumbnailFetcher(data, options)
            }
            return null
        }
    }
}
