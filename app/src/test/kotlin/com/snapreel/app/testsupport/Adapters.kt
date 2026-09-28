package com.snapreel.app.testsupport

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.PlaybackException
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navArgument
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.fetch.Fetcher
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Size
import com.snapreel.app.BuildConfig
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.preferences.SortOrder
import com.snapreel.app.data.repository.MediaRepository
import com.snapreel.app.di.createSettingsDataStore
import com.snapreel.app.navigation.Routes
import com.snapreel.app.navigation.navigateFrom
import com.snapreel.app.navigation.popFrom
import com.snapreel.app.ui.viewer.MediaIndexMapping
import com.snapreel.app.player.PageState
import com.snapreel.app.player.ReelPlayerPool
import com.snapreel.app.preservation.LegacyOracles
import com.snapreel.app.ui.viewer.FolderGridViewModel
import com.snapreel.app.ui.viewer.ReelsViewerViewModel
import com.snapreel.app.ui.viewer.landscape.LandscapeVideoViewerViewModel
import com.snapreel.app.util.UpdateCheckMapping
import com.snapreel.app.util.UpdateCheckResult
import com.snapreel.app.util.update.PendingUpdate
import com.snapreel.app.util.update.UpdateFiles
import com.snapreel.app.util.thumbnail.ThumbnailWorkGate
import com.snapreel.app.util.thumbnail.videoThumbnailRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The only place tests touch app code that the fix replaces or re-signatures.
 *
 * Every adapter currently targets the UNFIXED code. When a fix group lands, it repoints the
 * adapters in its section at the new API. Test assertions never change.
 */
object Adapters {

    // ─── Process state ──────────────────────────────────────────────────────────────────

    /**
     * Resets process-wide state between Robolectric tests.
     *
     * Was (unfixed): cleared the `preferencesDataStore` delegate's cached instance by reflection.
     * Now (6.2): the delegate is gone. Closes every settings DataStore that [appPreferences]
     * opened (cancelling its scope releases the file), so the next test gets a fresh instance.
     */
    fun resetProcessState() {
        val open = synchronized(settingsStores) {
            settingsStores.values.toList().also { settingsStores.clear() }
        }
        runBlocking { open.forEach { it.scope.coroutineContext[Job]?.cancelAndJoin() } }
    }

    private class OpenSettingsStore(val scope: CoroutineScope, val prefs: AppPreferences)

    /** The settings DataStores [appPreferences] opened, by file path (one active instance per file). */
    private val settingsStores = mutableMapOf<String, OpenSettingsStore>()

    // ─── thumbnail_ (fix group 4) ───────────────────────────────────────────────────────

    /** The app's configured Coil loader (built by `SnapReelApp.newImageLoader`). */
    fun appImageLoader(context: Context): ImageLoader = SingletonImageLoader.get(context)

    fun videoItem(uri: Uri, size: Long, dateModified: Long, name: String = "clip.mp4"): MediaItem =
        MediaItem(uri = uri, name = name, mimeType = "video/mp4", size = size, dateModified = dateModified, isVideo = true)

    /**
     * The request the folder grid builds for a video tile.
     * Was (unfixed): a verbatim copy of the inline `.data(uri).size(300, 300)` builder.
     * Now (4.1/4.4): the shared helper the grid calls.
     */
    fun gridVideoTileRequest(context: Context, item: MediaItem): ImageRequest =
        videoThumbnailRequest(context, item, cacheOnly = false, crossfade = true)

    /** Options equivalent to what Coil derives for [request] at the grid's 300×300 size. */
    fun optionsFor(context: Context, request: ImageRequest): Options =
        Options(context = context, size = Size(300, 300), extras = request.extras)

    /** The fetcher Coil's component registry picks for [request] (after its mappers run). */
    fun resolveFetcher(context: Context, loader: ImageLoader, request: ImageRequest): Pair<Any, Fetcher?> {
        val options = optionsFor(context, request)
        val mapped = loader.components.map(request.data, options)
        return mapped to loader.components.newFetcher(mapped, options, loader)?.first
    }

    /**
     * The cache key the pipeline uses for a video tile: the request's explicit memory key, or
     * else the key Coil's keyers derive from the mapped data.
     */
    fun videoTileCacheKey(context: Context, loader: ImageLoader, item: MediaItem): String? {
        val request = gridVideoTileRequest(context, item)
        request.memoryCacheKey?.let { return it }
        val options = optionsFor(context, request)
        val mapped = loader.components.map(request.data, options)
        return loader.components.key(mapped, options)
    }

    // ─── player_ (fix group 5) ──────────────────────────────────────────────────────────

    /** Observes player creation around `release()` for the viewers' player holder. */
    interface PlayerProbe {
        fun warmUp(uri: Uri)
        fun release()
        /** Every transport/accessor call a viewer can still make after release. */
        fun exerciseAfterRelease(uri: Uri)
        val createdAfterRelease: Int
        val held: Int
        fun dispose()
    }

    /** Was (unfixed): `ReelPlayerManager`. Now (5.2): `ReelPlayerPool` with a counting `playerFactory`. */
    fun playerProbe(context: Context): PlayerProbe = PoolProbe(context)

    private class PoolProbe(context: Context) : PlayerProbe {
        private val factory = TestPlayers.CountingFactory { ReelPlayerPool.buildDefaultPlayer(context) }
        private val pool = ReelPlayerPool(context, playerFactory = factory)
        private var createdBeforeRelease = 0

        private fun items(uri: Uri) = listOf(videoItem(uri, size = 1L, dateModified = 1L))

        override fun warmUp(uri: Uri) {
            pool.setWindow(items(uri), 0, playCurrent = true)
            pool.pause()
        }

        override fun release() {
            pool.release()
            createdBeforeRelease = factory.count
        }

        override fun exerciseAfterRelease(uri: Uri) {
            pool.pause()
            pool.play()
            pool.seekTo(0)
            pool.seekBy(10_000)
            pool.setMuted(true)
            pool.setLoop(false)
            pool.positionMs()
            pool.durationMs()
            pool.retry(uri)
            pool.trimToCurrent()
            pool.restoreNeighbors()
            pool.pages.value[uri]?.player // what VideoPage and the slider read
            pool.setWindow(items(uri), 0, playCurrent = true)
        }

        override val createdAfterRelease: Int get() = factory.count - createdBeforeRelease
        override val held: Int get() = factory.held

        override fun dispose() = pool.release()
    }

    /** Drives `ReelsViewerViewModel` the way `ReelsViewerScreen` does. */
    interface ReelsViewerProbe {
        fun open(folderUri: Uri, startIndex: Int)
        val isPlaying: Boolean
        val itemCount: Int
        /** The error reported by the current page's player, if any. */
        val playerError: PlaybackException?
        /** The current page's surfaced failure (message + retry), or null if none is exposed. */
        val currentPageFailure: Any?
        fun describe(): String
        fun close()
    }

    /**
     * Was (unfixed): `ReelsViewerViewModel(repository, prefs, ReelPlayerManager, ThumbnailWorkGate)`
     * with no page failure concept. Now (5.3): the pool-backed ViewModel with the production pool
     * factory; the failure is `pages[currentUri].failure`.
     */
    fun reelsViewerProbe(context: Context): ReelsViewerProbe = PoolReelsViewerProbe(context)

    private class PoolReelsViewerProbe(context: Context) : ReelsViewerProbe {
        private val store = ViewModelStore()
        private val prefs = appPreferences(context)
        private val vm: ReelsViewerViewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReelsViewerViewModel(
                        MediaRepository(context, prefs), prefs, ReelPlayerPool.Factory(context), ThumbnailWorkGate(),
                    ) as T
            },
        )[ReelsViewerViewModel::class.java]

        private fun currentPage(): PageState? {
            val s = vm.uiState.value
            val uri = s.mediaItems.getOrNull(s.currentIndex)?.uri ?: return null
            return vm.pages.value[uri]
        }

        override fun open(folderUri: Uri, startIndex: Int) = vm.loadMedia(folderUri, startIndex)
        override val isPlaying: Boolean get() = vm.uiState.value.isPlaying
        override val itemCount: Int get() = vm.uiState.value.mediaItems.size
        override val playerError: PlaybackException? get() = currentPage()?.player?.playerError
        override val currentPageFailure: Any? get() = currentPage()?.failure

        override fun describe(): String {
            val s = vm.uiState.value
            val err = playerError
            return "items=${s.mediaItems.size}, isLoading=${s.isLoading}, error=${s.error}, " +
                "currentIndex=${s.currentIndex}, isPlaying=${s.isPlaying}, showControls=${s.showControls}, " +
                "playerError=${err?.errorCodeName}, pageFailure=$currentPageFailure"
        }

        override fun close() = store.clear()
    }

    // ─── nav_ (fix group 6) ─────────────────────────────────────────────────────────────

    /** The app's destinations (content is irrelevant for back-stack checks). */
    fun appNavGraph(nav: NavController): NavGraph = nav.createGraph(startDestination = Routes.HOME) {
        composable(Routes.HOME) {}
        composable(Routes.GRID, arguments = listOf(navArgument("folderUri") { type = NavType.StringType })) {}
        val viewerArguments = listOf(
            navArgument("folderUri") { type = NavType.StringType },
            navArgument("startIndex") { type = NavType.IntType },
            navArgument("fresh") {
                type = NavType.BoolType
                defaultValue = false
            },
        )
        composable(Routes.VIEWER, arguments = viewerArguments) {}
        composable(Routes.LANDSCAPE_VIEWER, arguments = viewerArguments) {}
        composable(Routes.SETTINGS) {}
    }

    /**
     * Home's `onFolderSelected` callback, invoked from the Home entry [source].
     * Was (unfixed): an unguarded `navigate`. Now (6.1): `navigateFrom(source, …)`, as in `NavGraph.kt`.
     */
    fun homeOpenGrid(nav: NavController, source: NavBackStackEntry, folderUri: String) {
        nav.navigateFrom(source, Routes.grid(folderUri))
    }

    /**
     * The grid's `onBack` callback, invoked from the grid entry [source].
     * Was (unfixed): an unguarded `popBackStack`. Now (6.1): `popFrom(source)`, as in `NavGraph.kt`.
     */
    fun gridBack(nav: NavController, source: NavBackStackEntry) {
        nav.popFrom(source)
    }

    // ─── prefs_ (fix group 6) ───────────────────────────────────────────────────────────

    /**
     * Was (unfixed): `AppPreferences(context)` over the delegate DataStore.
     * Now (6.2): `AppPreferences(dataStore)` over the DataStore `AppModule` provides (same file, with
     * the corruption handler). DataStore forbids two active instances on one file, so calls within
     * a test share one instance; [resetProcessState] closes it, so each test starts fresh.
     */
    fun appPreferences(context: Context): AppPreferences {
        val path = settingsFile(context).absolutePath
        return synchronized(settingsStores) {
            settingsStores.getOrPut(path) {
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                OpenSettingsStore(scope, AppPreferences(createSettingsDataStore(context, scope)))
            }.prefs
        }
    }

    /** The settings file both the unfixed and fixed code use. */
    fun settingsFile(context: Context): File =
        File(context.filesDir, "datastore/snapreel_settings.preferences_pb")

    // ─── update_ (fix group 7) ──────────────────────────────────────────────────────────

    val installedVersionCode: Int get() = BuildConfig.VERSION_CODE

    /**
     * Leaves a downloaded, validated update APK for [versionCode] where the app keeps a pending
     * update. Was (unfixed): `snapreel_update.apk` in `externalCacheDir ?: cacheDir`. Now (7.4):
     * `cacheDir/updates/snapreel-<v>.apk` plus its `pending.json` sidecar, through `UpdateFiles`
     * (the coordinator writes the sidecar right after validation passes).
     */
    fun stagePendingUpdate(context: Context, versionCode: Int, versionName: String): File {
        val files = UpdateFiles(context)
        files.dir.mkdirs()
        val apk = files.apkFileFor(versionName).apply {
            writeBytes("fake apk for $versionName ($versionCode)".toByteArray())
        }
        files.writePending(
            PendingUpdate(versionName, versionCode.toLong(), apk.length(), "SnapReel $versionName", "", apk.name)
        )
        return apk
    }

    /** Was (unfixed): `UpdateManager.cleanupOldUpdateApks`. Now (7.4): what `SnapReelApp.onCreate` runs. */
    fun runStartupUpdateCleanup(context: Context) {
        UpdateFiles(context).deleteStale(BuildConfig.VERSION_CODE.toLong())
    }

    // ═══ Preservation (Property 2, task 3) ═══════════════════════════════════════════════
    //
    // Adapters for `com.snapreel.app.preservation.*`. Each says whether it calls REAL app code or
    // is ROUTED to its `LegacyOracles` copy because the logic is still inline (in a composable).
    // Fix groups repoint the routed ones at the extracted code and re-signature the real ones.

    // ─── order_ (3.3) ───────────────────────────────────────────────────────────────────

    /** REAL. Unfixed: `MediaRepository(context, prefs)`. */
    fun mediaRepository(context: Context, prefs: AppPreferences): MediaRepository = MediaRepository(context, prefs)

    /** REAL. The Settings edits that choose the scan order. After 6.2: same calls (they return a Boolean). */
    suspend fun applySortSettings(prefs: AppPreferences, order: SortOrder, shuffle: Boolean) {
        prefs.updateSortOrder(order)
        prefs.updateShuffleMedia(shuffle)
    }

    /**
     * REAL. A fresh scan: the recursive document walk, then the private `sortMedia` and optional
     * shuffle. It also fills the repository's snapshot, which the viewer probes below read.
     * After 6.3: `scanFolder(treeUri, forceRefresh = true)` still returns the items (it throws
     * `FolderAccessLostException` on access loss, which never happens with the fake provider).
     */
    suspend fun scanFresh(repo: MediaRepository, treeUri: Uri): List<MediaItem> =
        repo.scanFolder(treeUri, forceRefresh = true)

    /** REAL. The list every screen reads after a scan (`getCachedMedia`). After 6.3/6.4: the shared snapshot accessor. */
    fun snapshot(repo: MediaRepository, treeUri: Uri): List<MediaItem>? = repo.getCachedMedia(treeUri)

    // ─── mapping_ (3.5, 3.10) ───────────────────────────────────────────────────────────

    /** REAL. Was ROUTED (inline in the grid's tile `clickable`). Now (6.4): `MediaIndexMapping.gridTapToFullIndex`. */
    fun gridTapToFullIndex(items: List<MediaItem>, isLandscapeMode: Boolean, displayedIndex: Int): Int =
        MediaIndexMapping.gridTapToFullIndex(items, isLandscapeMode, displayedIndex)

    /** ROUTED: inline in `FolderMediaGridScreen` (scroll-restore effect). Stays inline after 6.4 unless extracted. */
    fun gridScrollIndex(items: List<MediaItem>, isLandscapeMode: Boolean, targetFullIndex: Int): Int? =
        LegacyOracles.gridScrollIndex(items, isLandscapeMode, targetFullIndex)

    /**
     * REAL. Was ROUTED (inline in `LandscapeVideoViewerViewModel.loadVideos`). Now (6.4):
     * `MediaIndexMapping.fullToLandscapeIndex`, which the ViewModel calls; the ViewModel is also
     * driven by [landscapeViewerProbe].
     */
    fun fullToLandscapeIndex(items: List<MediaItem>, requestedFullIndex: Int): Int? =
        MediaIndexMapping.fullToLandscapeIndex(items, requestedFullIndex)

    /**
     * REAL (pure form). Was ROUTED (inline in `saveLastViewedIndex`). Now (6.4):
     * `MediaIndexMapping.landscapeToFullIndex`; the ViewModel's save is driven by [landscapeViewerProbe].
     */
    fun landscapeToFullIndex(items: List<MediaItem>, videoIndex: Int): Int? =
        MediaIndexMapping.landscapeToFullIndex(items, videoIndex)

    /** Drives the REAL `LandscapeVideoViewerViewModel` for the full ↔ video index mapping. */
    interface LandscapeViewerProbe {
        /** `loadVideos(folderUri, requestedIndex)`. The folder must already be in the repository's snapshot. */
        fun open(folderUri: Uri, requestedFullIndex: Int)
        val videos: List<MediaItem>
        val currentIndex: Int
        /** `saveLastViewedIndex(folderUri, videoIndex)` (writes Recents asynchronously). */
        fun saveLastViewed(folderUri: Uri, videoIndex: Int)
        fun close()
    }

    /**
     * REAL. Unfixed: `LandscapeVideoViewerViewModel(repository, prefs, ReelPlayerManager, ThumbnailWorkGate)`. The
     * snapshot branch of `loadVideos` runs synchronously on the main thread, before the main
     * looper is idled. `saveLastViewed` needs the caller to idle the main looper (DataStore runs
     * the edit's transform in the ViewModel's main-thread context); that also delivers the player's
     * error for the fake (unopenable) video, which the unfixed ViewModel ignores.
     * Now (5.4): the pool-backed ViewModel with a pool whose players never fail (fake media).
     * After 6.5: `loadVideos(…, fresh = false)`.
     */
    fun landscapeViewerProbe(context: Context, repo: MediaRepository, prefs: AppPreferences): LandscapeViewerProbe =
        LegacyLandscapeViewerProbe(context, repo, prefs)

    private class LegacyLandscapeViewerProbe(
        context: Context,
        repo: MediaRepository,
        prefs: AppPreferences,
    ) : LandscapeViewerProbe {
        private val store = ViewModelStore()
        private val vm: LandscapeVideoViewerViewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LandscapeVideoViewerViewModel(repo, prefs, TestPlayers.nonFailingPoolFactory(context), ThumbnailWorkGate()) as T
            },
        )[LandscapeVideoViewerViewModel::class.java]

        override fun open(folderUri: Uri, requestedFullIndex: Int) = vm.loadVideos(folderUri, requestedFullIndex, fresh = false)
        override val videos: List<MediaItem> get() = vm.uiState.value.videos
        override val currentIndex: Int get() = vm.uiState.value.currentIndex
        override fun saveLastViewed(folderUri: Uri, videoIndex: Int) = vm.saveLastViewed(folderUri, videoIndex)
        override fun close() = store.clear()
    }

    // ─── tap_ (3.6, 3.11) ───────────────────────────────────────────────────────────────

    /** ROUTED: `delay(3000)` inline in `VideoPage`'s auto-hide effect (keys: showControls, isPlaying, dragging). */
    val reelsAutoHideDelayMs: Long get() = LegacyOracles.REELS_AUTO_HIDE_MS

    /** Drives the REAL `ReelsViewerViewModel` state machine the way `ReelsViewerScreen` does. */
    interface ReelsTapProbe {
        val items: List<MediaItem>
        val currentIndex: Int
        val isPlaying: Boolean
        val showControls: Boolean
        val isMuted: Boolean
        /** A single tap on the current page: `VideoPage.onTap` → `onVideoTap`, `ImagePage.onTap` → `onImageTap`. */
        fun tap()
        /** `VideoPage`'s auto-hide effect firing. */
        fun controlsTimeout()
        /** `ON_PAUSE`. */
        fun appPaused()
        /** The pager's settled page changed. */
        fun settle(index: Int)
        fun toggleMute()
        fun close()
    }

    /**
     * REAL. Unfixed: `ReelsViewerViewModel(repository, prefs, ReelPlayerManager, ThumbnailWorkGate)` opened with
     * `loadMedia(folderUri, startIndex)`, whose snapshot branch runs synchronously. The main looper
     * is never idled, so no playback failure reaches the (unfixed) ViewModel: the run stays outside
     * C6. Now (5.3): the pool-backed ViewModel with a pool whose players never fail (fake media),
     * so the run stays outside C6 even when the looper is idled. After 6.5: `loadMedia(…, fresh = false)`.
     */
    fun reelsTapProbe(
        context: Context,
        repo: MediaRepository,
        prefs: AppPreferences,
        folderUri: Uri,
        startIndex: Int,
    ): ReelsTapProbe = LegacyReelsTapProbe(context, repo, prefs).apply { vm.loadMedia(folderUri, startIndex, fresh = false) }

    private class LegacyReelsTapProbe(
        context: Context,
        repo: MediaRepository,
        prefs: AppPreferences,
    ) : ReelsTapProbe {
        private val store = ViewModelStore()
        val vm: ReelsViewerViewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReelsViewerViewModel(repo, prefs, TestPlayers.nonFailingPoolFactory(context), ThumbnailWorkGate()) as T
            },
        )[ReelsViewerViewModel::class.java]

        private val state get() = vm.uiState.value
        override val items: List<MediaItem> get() = state.mediaItems
        override val currentIndex: Int get() = state.currentIndex
        override val isPlaying: Boolean get() = state.isPlaying
        override val showControls: Boolean get() = state.showControls
        override val isMuted: Boolean get() = state.isMuted

        override fun tap() {
            if (state.mediaItems.getOrNull(state.currentIndex)?.isVideo == true) vm.onVideoTap() else vm.onImageTap()
        }

        override fun controlsTimeout() = vm.onControlsTimeout()
        override fun appPaused() = vm.onAppPaused()
        override fun settle(index: Int) = vm.onPageSettled(index)
        override fun toggleMute() = vm.toggleMute()
        override fun close() = store.clear()
    }

    // ─── recents_ (3.1, 3.2, 3.12) ──────────────────────────────────────────────────────

    /** REAL. Home's pick flow (`HomeViewModel.onFolderPicked`). After 6.2: same call. */
    suspend fun recentsAdd(prefs: AppPreferences, uri: String, name: String) {
        prefs.addRecentFolder(uri, name)
    }

    /** REAL. Home's delete button. */
    suspend fun recentsRemove(prefs: AppPreferences, uri: String) {
        prefs.removeRecentFolder(uri)
    }

    /** REAL. A viewer's settled-page save. Was `updateLastViewedIndex`; now (6.2) `updateLastViewed(uri, index, itemUri = null)`. */
    suspend fun recentsUpdateLastViewed(prefs: AppPreferences, uri: String, index: Int) {
        prefs.updateLastViewed(uri, index, itemUri = null)
    }

    /** REAL. The stored entries (`recentFolders`), before parsing. */
    suspend fun recentsRawEntries(prefs: AppPreferences): List<String> = prefs.recentFolders.first()

    /** REAL. `parseRecentFolderEntry`, reduced to the unfixed fields (6.2's optional `lastItemUri` is dropped). */
    fun parseRecentEntry(prefs: AppPreferences, entry: String): LegacyOracles.RecentEntry? =
        prefs.parseRecentFolderEntry(entry)?.let { LegacyOracles.RecentEntry(it.uri, it.name, it.lastIndex) }

    /** REAL. What Home lists (`HomeViewModel`: `recentFolders.mapNotNull(parseRecentFolderEntry)`). */
    suspend fun recents(prefs: AppPreferences): List<LegacyOracles.RecentEntry> =
        recentsRawEntries(prefs).mapNotNull { parseRecentEntry(prefs, it) }

    /**
     * REAL. `FolderGridViewModel.getSavedLastIndex` (grid scroll restore). Since 6.4 it resolves the
     * saved item URI first; with no saved URI (as here) it returns the saved index, as before.
     */
    suspend fun gridSavedLastIndex(repo: MediaRepository, prefs: AppPreferences, folderUri: Uri): Int {
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = FolderGridViewModel(repo, prefs) as T
            },
        )[FolderGridViewModel::class.java]
        return try {
            vm.getSavedLastIndex(folderUri)
        } finally {
            store.clear()
        }
    }

    // ─── update_ offer (3.13) ───────────────────────────────────────────────────────────

    /** The installed `versionName` the update check compares against. */
    val installedVersionName: String get() = BuildConfig.VERSION_NAME

    /** REAL. Was `UpdateManager.isNewerVersion`; now (7.2) `UpdateCheckMapping.isNewerVersion`, which the check uses. */
    fun isNewerVersion(context: Context, remote: String, current: String): Boolean =
        UpdateCheckMapping.isNewerVersion(remote, current)

    /**
     * REAL. Was ROUTED (inline in the network-bound `checkForUpdates()`). Now (7.2):
     * `UpdateCheckMapping.mapCheckResponse(code, emptyMap(), body, current)`, with `Available(info)`
     * → its fields and every other result (UpToDate / Failed) → null (no dialog).
     */
    fun releaseOffer(responseCode: Int, body: String, currentVersionName: String): LegacyOracles.Offer? =
        when (val r = UpdateCheckMapping.mapCheckResponse(responseCode, emptyMap(), body, currentVersionName)) {
            is UpdateCheckResult.Available -> with(r.info) {
                LegacyOracles.Offer(versionName, releaseTitle, releaseNotes, downloadUrl, apkSize)
            }
            else -> null
        }
}
