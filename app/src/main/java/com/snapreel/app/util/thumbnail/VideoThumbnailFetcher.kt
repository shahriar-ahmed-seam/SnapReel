package com.snapreel.app.util.thumbnail

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.getExtra
import coil3.request.Options
import coil3.request.bitmapConfig
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/** A cache-only request (viewer preview) found no thumbnail; the viewer shows none. */
class ThumbnailNotCached(key: String) : Exception("No cached thumbnail for $key")

/**
 * Coil fetcher for [VideoThumbnail].
 *
 * 1. A disk hit returns immediately ([DataSource.DISK]); reads never wait on workers or the gate.
 * 2. A cache-only request that misses throws [ThumbnailNotCached]; it never generates.
 * 3. Otherwise it generates through the [ThumbnailScheduler], retrying a retryable failure after
 *    1 s and then 3 s while the tile is still visible (the request's coroutine is active).
 *    A permanent failure, or a key with no attempts left, is rethrown: the tile keeps its placeholder.
 */
class VideoThumbnailFetcher(
    private val data: VideoThumbnail,
    private val options: Options,
    private val cache: ThumbnailDiskCache,
    private val scheduler: ThumbnailScheduler,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val key = data.key
        cache.read(key, options.bitmapConfig)?.let { return result(it) }

        if (options.getExtra(CacheOnlyKey)) throw ThumbnailNotCached(key)

        var retries = 0
        while (true) {
            try {
                // Re-checks the cache under the key's lock first, so a tile whose key another tile
                // is generating gets that result, and throws (permanent) once no attempts are left.
                return result(scheduler.generate(data))
            } catch (e: ThumbnailGenerationException) {
                // The scheduler reports the key's last allowed attempt as permanent.
                if (e.permanent) throw e
            }
            // Cancelled here when the tile leaves the screen.
            delay(backoffMs[retries.coerceAtMost(backoffMs.lastIndex)])
            retries++
        }
    }

    private fun result(bitmap: android.graphics.Bitmap) =
        ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)

    @Singleton
    class Factory @Inject constructor(
        private val cache: ThumbnailDiskCache,
        private val scheduler: ThumbnailScheduler,
    ) : Fetcher.Factory<VideoThumbnail> {
        override fun create(data: VideoThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            VideoThumbnailFetcher(data, options, cache, scheduler)
    }

    companion object {
        val DEFAULT_BACKOFF_MS = listOf(1_000L, 3_000L)
    }
}
