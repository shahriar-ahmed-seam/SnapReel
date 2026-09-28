package com.snapreel.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.snapreel.app.di.ApplicationScope
import com.snapreel.app.util.thumbnail.VideoThumbnailFetcher
import com.snapreel.app.util.update.UpdateFiles
import com.snapreel.app.util.thumbnail.VideoThumbnailKeyer
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class SnapReelApp : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var videoThumbnailFetcherFactory: VideoThumbnailFetcher.Factory

    @Inject
    @field:ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var updateFiles: UpdateFiles

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch(Dispatchers.IO) {
            // Only stale update files go: a validated pending update newer than this version is
            // kept, so an install interrupted by a process restart can still finish.
            updateFiles.deleteStale(BuildConfig.VERSION_CODE.toLong())
            // The pre-1.3 thumbnail store (unbounded, keyed on the URI only). Replaced by
            // noBackupFilesDir/video_thumbs.
            File(filesDir, LEGACY_THUMBNAIL_DIR).deleteRecursively()
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                // Video tiles use VideoThumbnail data; images keep Coil's native pipeline.
                add(VideoThumbnailKeyer())
                add(videoThumbnailFetcherFactory)
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    private companion object {
        const val LEGACY_THUMBNAIL_DIR = "app_thumbnails"
    }
}
