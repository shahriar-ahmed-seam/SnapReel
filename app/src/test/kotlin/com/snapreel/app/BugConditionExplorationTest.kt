package com.snapreel.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.data.preferences.AppSettings
import com.snapreel.app.navigation.Routes
import com.snapreel.app.testsupport.Adapters
import com.snapreel.app.testsupport.FakeDocumentsProvider
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Property 1: Bug Condition - Defects 1.1–1.20 are fixed.
 *
 * Bug condition exploration test. Each case encodes the expected (fixed) behavior for a concrete
 * input where `isBugCondition` holds, so every case FAILS on the unfixed code. Code that the fix
 * replaces is reached only through [Adapters]; fix groups repoint the adapters, never these
 * assertions.
 *
 * Counterexamples observed on the UNFIXED code
 * (`./gradlew :app:testDebugUnitTest --tests '*BugConditionExplorationTest'`):
 *
 * - `thumbnail_appFetcherSelected` (C1–C3): the grid's video-tile data `android.net.Uri` is mapped
 *   to `coil3.Uri` and `ContentUriFetcher` is selected. `MediaThumbnailFetcher.Factory` is a
 *   `Fetcher.Factory<android.net.Uri>` and never matches, so the app's fetcher never runs.
 * - `thumbnail_keyTracksFileVersion` (C1): one key for two file versions. Shrunk counterexample:
 *   `…/document/DCIM%2F<name>.mp4` with size 0 vs 1 (dateModified 0) both key to the bare URI
 *   string; dateModified changes likewise leave the key unchanged. The dead
 *   `PersistentThumbnailStore` key (MD5 of the URI) has the same flaw by inspection.
 * - `player_noPlayerAfterRelease` (C10): after `release()`, the lazy `player` getter and
 *   `playUri` re-create an ExoPlayer: 1 created after release, 1 held.
 * - `player_failureSurfaced` (C6): the player is in error with `ERROR_CODE_IO_FILE_NOT_FOUND`,
 *   yet after 10 s the UI still has `isPlaying = true`, `showControls = false` and no page failure.
 * - `nav_burstActsOnce` (C9): two taps from Home give back stack `[home, grid, grid]`; two back
 *   taps from the grid pop Home too, leaving an empty back stack `[]`.
 * - `prefs_corruptFileFallsBack` (C12): `settings.first()` throws
 *   `androidx.datastore.core.CorruptionException: Unable to parse preferences proto.`
 * - `update_pendingApkKept` (C19): `snapreel_update.apk` (pending 1.3.0 / versionCode 14 over
 *   installed 13) is deleted by `UpdateManager.cleanupOldUpdateApks` on startup.
 * - C17 (manual): `./gradlew :app:signingReport` shows variant `release` with
 *   Config `debug`, Store `~/.android/debug.keystore`, Alias `AndroidDebugKey`, the same as `debug`.
 *
 * 1.5, 1.7, 1.8, 1.11, 1.13–1.16, 1.18 and 1.20 are confirmed by code inspection and covered by
 * P3–P12 in the fix groups.
 */
@RunWith(AndroidJUnit4::class)
class BugConditionExplorationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        Adapters.resetProcessState()
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        FakeDocumentsProvider.files = emptyList()
        Adapters.resetProcessState()
    }

    // ─── Thumbnails ─────────────────────────────────────────────────────────────────────

    /** C1–C3: a video tile must go through the app's own thumbnail fetcher. */
    @Test
    fun thumbnail_appFetcherSelected() {
        val loader = Adapters.appImageLoader(context)
        val item = Adapters.videoItem(
            uri = FakeDocumentsProvider.documentUri("DCIM/clip.mp4"),
            size = 12_345_678L,
            dateModified = 1_700_000_000_000L,
        )
        val request = Adapters.gridVideoTileRequest(context, item)

        val (mapped, fetcher) = Adapters.resolveFetcher(context, loader, request)
        val fetcherClass = fetcher?.javaClass?.name

        val detail = "request data ${request.data::class.qualifiedName} mapped to ${mapped::class.qualifiedName}; " +
            "selected fetcher = $fetcherClass"
        assertTrue("Coil's ContentUriFetcher was selected: $detail", fetcherClass?.endsWith(".ContentUriFetcher") != true)
        assertTrue(
            "the app's own fetcher (com.snapreel.app.*) must be selected: $detail",
            fetcherClass?.startsWith("com.snapreel.app.") == true,
        )
    }

    /** C1: changing a file's size or dateModified must change its thumbnail cache key. */
    @Test
    fun thumbnail_keyTracksFileVersion() = runTest {
        val loader = Adapters.appImageLoader(context)
        checkAll(
            100,
            Arb.stringPattern("[a-zA-Z0-9_]{1,24}"),
            Arb.long(0L..50_000_000_000L),
            Arb.long(0L..4_000_000_000_000L),
            Arb.long(1L..1_000_000L),
            Arb.long(1L..1_000_000L),
        ) { name, size, dateModified, sizeDelta, dateDelta ->
            val uri = FakeDocumentsProvider.documentUri("DCIM/$name.mp4")
            val key = Adapters.videoTileCacheKey(context, loader, Adapters.videoItem(uri, size, dateModified))
            val sameKey = Adapters.videoTileCacheKey(context, loader, Adapters.videoItem(uri, size, dateModified))
            val resized = Adapters.videoTileCacheKey(context, loader, Adapters.videoItem(uri, size + sizeDelta, dateModified))
            val retouched = Adapters.videoTileCacheKey(context, loader, Adapters.videoItem(uri, size, dateModified + dateDelta))

            assertEquals("equal (uri, size, dateModified) must share a key", key, sameKey)
            assertNotEquals(
                "one key for two sizes ($size vs ${size + sizeDelta}) of $uri: $key",
                key, resized,
            )
            assertNotEquals(
                "one key for two dateModified values ($dateModified vs ${dateModified + dateDelta}) of $uri: $key",
                key, retouched,
            )
        }
    }

    // ─── Playback ───────────────────────────────────────────────────────────────────────

    /** C10: once released, the viewer's player holder must never create or hold an ExoPlayer. */
    @Test
    fun player_noPlayerAfterRelease() {
        val uri = FakeDocumentsProvider.documentUri("clip.mp4")
        val probe = Adapters.playerProbe(context)
        try {
            probe.warmUp(uri)
            probe.release()
            probe.exerciseAfterRelease(uri)

            val created = probe.createdAfterRelease
            val held = probe.held
            assertTrue(
                "after release(): $created ExoPlayer(s) created and $held held (expected 0 and 0)",
                created == 0 && held == 0,
            )
        } finally {
            probe.dispose()
        }
    }

    /** C6: a video that can't be opened must end with isPlaying == false and a page failure. */
    @Test
    fun player_failureSurfaced() {
        Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(FakeDocumentsProvider.AUTHORITY)
        FakeDocumentsProvider.files = listOf(
            FakeDocumentsProvider.FakeFile("missing.mp4", "missing.mp4", "video/mp4", 1_000_000L, 1_700_000_000_000L),
        )
        val viewer = Adapters.reelsViewerProbe(context)
        try {
            viewer.open(FakeDocumentsProvider.treeUri, startIndex = 0)

            // Wait (up to 10 s of real time) for the scan, the settle and the player's failure to
            // propagate. On timeout, fall through to the assertion, which reports the state reached.
            idleUntil(10_000L) { viewer.itemCount > 0 && !viewer.isPlaying && viewer.currentPageFailure != null }

            val state = viewer.describe()
            assertTrue("the folder scan must list the missing video: $state", viewer.itemCount == 1)
            assertTrue(
                "playback failed but the UI still reports playing / no failure is exposed: $state",
                !viewer.isPlaying && viewer.currentPageFailure != null,
            )
        } finally {
            viewer.close()
        }
    }

    /**
     * Runs the main looper and advances Robolectric's clock in 10 ms steps until [condition] holds
     * or [timeoutMs] of real time passes. ExoPlayer's playback thread schedules its work against
     * that clock, so it only makes progress while the clock moves; the short sleep lets the real
     * IO and loader threads run.
     */
    private fun idleUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
            Thread.sleep(2)
        }
        return condition()
    }

    // ─── Navigation ─────────────────────────────────────────────────────────────────────

    private class ResumedOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun newNavController(): TestNavHostController =
        TestNavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            setLifecycleOwner(ResumedOwner())
            setViewModelStore(ViewModelStore())
            graph = Adapters.appNavGraph(this)
        }

    private fun TestNavHostController.routes(): List<String> =
        backStack.mapNotNull { it.destination.route }.filter { it != graph.route }

    /** C9: a burst of taps before the next frame must change the back stack at most once and keep Home. */
    @Test
    fun nav_burstActsOnce() {
        val folder = FakeDocumentsProvider.treeUri.toString()

        // Two taps on a Home folder in one frame.
        val forward = newNavController()
        val home = forward.currentBackStackEntry!!
        Adapters.homeOpenGrid(forward, home, folder)
        Adapters.homeOpenGrid(forward, home, folder)
        val afterDoubleOpen = forward.routes()
        val gridEntries = afterDoubleOpen.count { it == Routes.GRID }

        // Two taps on the grid's back arrow in one frame.
        val backward = newNavController()
        backward.navigate(Routes.grid(folder))
        val grid = backward.currentBackStackEntry!!
        Adapters.gridBack(backward, grid)
        Adapters.gridBack(backward, grid)
        val afterDoubleBack = backward.routes()

        assertTrue(
            "double open from Home: back stack $afterDoubleOpen (expected one grid entry); " +
                "double back from grid: back stack $afterDoubleBack (expected Home kept)",
            gridEntries == 1 && Routes.HOME in afterDoubleBack,
        )
    }

    // ─── Preferences ────────────────────────────────────────────────────────────────────

    /** C12: a corrupt settings file must yield defaults, not an exception. */
    @Test
    fun prefs_corruptFileFallsBack() = runTest {
        val file = Adapters.settingsFile(context)
        file.parentFile!!.mkdirs()
        // Not a valid protobuf: field 1 (the preferences map) claims 127 bytes but the file ends
        // after a few bytes of text, so parsing hits "input ended unexpectedly".
        file.writeBytes(byteArrayOf(0x0A, 0x7F) + "garbage".toByteArray())

        val prefs = Adapters.appPreferences(context)
        val result = runCatching { prefs.settings.first() }

        assertTrue(
            "settings.first() on a corrupt ${file.name} threw ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        assertEquals(AppSettings(), result.getOrNull())
    }

    // ─── Update ─────────────────────────────────────────────────────────────────────────

    /** C19: a pending update newer than the installed version must survive startup cleanup. */
    @Test
    fun update_pendingApkKept() {
        val installed = Adapters.installedVersionCode
        val pending = Adapters.stagePendingUpdate(context, versionCode = installed + 1, versionName = "1.3.0")
        assertTrue("setup: pending APK was not written", pending.exists())

        Adapters.runStartupUpdateCleanup(context)

        assertTrue(
            "startup cleanup deleted the pending update ${pending.name} (versionCode ${installed + 1} > installed $installed)",
            pending.exists(),
        )
    }
}
