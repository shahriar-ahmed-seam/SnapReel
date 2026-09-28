package com.snapreel.app.preservation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.preferences.SortOrder
import com.snapreel.app.testsupport.Adapters
import com.snapreel.app.testsupport.FakeDocumentsProvider
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper
import kotlin.time.Duration.Companion.minutes

/**
 * Property 2: Preservation - order and index mapping (3.3, 3.5, 3.10).
 *
 * Production is reached through [Adapters]: the scan and its sort are REAL (`MediaRepository`
 * over [FakeDocumentsProvider]), and so are the landscape viewer's full ↔ video mapping and the
 * reels start index (the real ViewModels). The grid's tap and scroll-restore mappings are inline
 * in `FolderMediaGridScreen`, so they are ROUTED to [LegacyOracles] until 6.4 extracts them.
 *
 * Observed on the UNFIXED code:
 * - Sorts are stable: ties keep provider order. NAME_* compare `name.lowercase()`, so "Clip.mp4",
 *   "clip.mp4" and "CLIP.MP4" keep their provider order; TYPE_VIDEO_FIRST is `sortedByDescending { isVideo }`.
 * - Rows are kept when the extension is supported or the MIME is `video/…`/`image/…`; `isVideo` is
 *   a video MIME or a video extension. "x.txt" with `text/plain` is skipped.
 * - Shuffle is applied after the sort and yields a permutation. Every screen reads the same list.
 * - A grid tap passes the tapped item's full-list index (in landscape mode too). The landscape
 *   viewer maps it to the same item's video index; an out-of-range saved index is clamped to the
 *   video list; a non-video in range maps to video 0. Back saves the full-list index of the
 *   current video, and the grid scrolls back to that same tile.
 */
@RunWith(AndroidJUnit4::class)
class OrderAndMappingPreservationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        Adapters.resetProcessState()
        context = ApplicationProvider.getApplicationContext()
        Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(FakeDocumentsProvider.AUTHORITY)
    }

    @After
    fun tearDown() {
        FakeDocumentsProvider.files = emptyList()
        Adapters.resetProcessState()
    }

    /** 3.3: the scan keeps the same rows and the same order; shuffle is a permutation; screens share it. */
    @Test
    fun order_scanMatchesLegacySort() = runTest(timeout = 5.minutes) {
        val prefs = Adapters.appPreferences(context)
        val repo = Adapters.mediaRepository(context, prefs)
        val tree = FakeDocumentsProvider.treeUri

        checkAll(100, PreservationArbs.folder(), Arb.enum<SortOrder>(), Arb.boolean()) { files, order, shuffle ->
            FakeDocumentsProvider.files = files
            Adapters.applySortSettings(prefs, order, shuffle)

            val production = Adapters.scanFresh(repo, tree)
            val listed = PreservationArbs.expectedScan(files)
            val oracle = LegacyOracles.sortMedia(listed, order)

            if (shuffle) {
                assertEquals("shuffle must be a permutation of the listed items", listed.size, production.size)
                assertEquals(
                    "shuffle must be a permutation of the listed items",
                    oracle.sortedBy { it.uri.toString() },
                    production.sortedBy { it.uri.toString() },
                )
            } else {
                assertEquals("scan order for $order", oracle, production)
            }
            assertEquals("every screen reads the scanned list", production, Adapters.snapshot(repo, tree))
        }
    }

    /** 3.5, 3.10 (pure): a tap opens exactly that item, and back returns the grid to it. */
    @Test
    fun mapping_tapOpensThatItemAndBackReturnsToIt() = runTest {
        checkAll(1_000, PreservationArbs.mediaList, Arb.boolean(), Arb.int(0..1_000), Arb.int(-3..40)) { items, landscape, pick, savedIndex ->
            val displayed = LegacyOracles.gridDisplayedItems(items, landscape)

            // Home's play button passes the saved full-list index as is (possibly stale).
            assertEquals(
                "landscape start for saved index $savedIndex",
                LegacyOracles.fullToLandscapeIndex(items, savedIndex),
                Adapters.fullToLandscapeIndex(items, savedIndex),
            )
            if (displayed.isEmpty()) return@checkAll

            val tapped = pick % displayed.size
            val fullIndex = Adapters.gridTapToFullIndex(items, landscape, tapped)
            assertEquals("grid tap $tapped (landscape=$landscape)", LegacyOracles.gridTapToFullIndex(items, landscape, tapped), fullIndex)
            assertEquals("the tap must open the tapped item", displayed[tapped].uri, items[fullIndex].uri)

            val returnedIndex = if (landscape) {
                val videoIndex = Adapters.fullToLandscapeIndex(items, fullIndex)
                assertEquals(LegacyOracles.fullToLandscapeIndex(items, fullIndex), videoIndex)
                assertNotNull(videoIndex)
                val videos = items.filter { it.isVideo }
                assertEquals("the landscape viewer must open the tapped video", displayed[tapped].uri, videos[videoIndex!!].uri)

                val saved = Adapters.landscapeToFullIndex(items, videoIndex)
                assertEquals(LegacyOracles.landscapeToFullIndex(items, videoIndex), saved)
                saved!!
            } else {
                fullIndex
            }

            val scroll = Adapters.gridScrollIndex(items, landscape, returnedIndex)
            assertEquals(LegacyOracles.gridScrollIndex(items, landscape, returnedIndex), scroll)
            assertEquals("back must return the grid to the tapped tile", tapped, scroll)
        }
    }

    /** 3.2, 3.5, 3.10: the real viewers map indices exactly like the unfixed code. */
    @Test
    fun mapping_viewersMatchLegacy() = runTest(timeout = 5.minutes) {
        val prefs = Adapters.appPreferences(context)
        val repo = Adapters.mediaRepository(context, prefs)
        val tree = FakeDocumentsProvider.treeUri
        Adapters.recentsAdd(prefs, tree.toString(), "Fake Folder")

        checkAll(
            50,
            PreservationArbs.folderWithVideo(),
            Arb.enum<SortOrder>(),
            Arb.boolean(),
            Arb.int(-3..25),
            Arb.int(0..1_000),
        ) { files, order, shuffle, requested, pick ->
            FakeDocumentsProvider.files = files
            Adapters.applySortSettings(prefs, order, shuffle)
            val items = Adapters.scanFresh(repo, tree)
            val videos = items.filter { it.isVideo }

            // Reels viewer: the requested full-list index is clamped.
            val reels = Adapters.reelsTapProbe(context, repo, prefs, tree, requested)
            try {
                assertEquals("reels start for $requested", LegacyOracles.reelsStartIndex(items, requested), reels.currentIndex)
            } finally {
                reels.close()
            }

            // Landscape viewer: full → video index on open, video → full index on save.
            val landscape = Adapters.landscapeViewerProbe(context, repo, prefs)
            try {
                landscape.open(tree, requested)
                assertEquals("landscape videos", videos, landscape.videos)
                assertEquals(
                    "landscape start for $requested",
                    LegacyOracles.fullToLandscapeIndex(items, requested),
                    landscape.currentIndex,
                )

                val videoIndex = pick % videos.size
                Adapters.recentsUpdateLastViewed(prefs, tree.toString(), SENTINEL)
                landscape.saveLastViewed(tree, videoIndex)
                val saved = awaitSavedIndex(prefs, tree.toString())
                assertEquals(
                    "landscape save for video $videoIndex",
                    LegacyOracles.landscapeToFullIndex(items, videoIndex),
                    saved,
                )
            } finally {
                landscape.close()
            }
        }
    }

    /**
     * Polls Recents (real time, up to 5 s) until the viewer's asynchronous save replaces [SENTINEL].
     * DataStore runs an edit's transform in the caller's context, here the ViewModel's main-thread
     * scope, so the main looper must be idled for the save to go through.
     */
    private suspend fun awaitSavedIndex(prefs: AppPreferences, uri: String): Int? {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            ShadowLooper.idleMainLooper()
            val index = Adapters.recents(prefs).firstOrNull { it.uri == uri }?.lastIndex
            if (index != SENTINEL || System.nanoTime() > deadline) return index
            Thread.sleep(2)
        }
    }

    private companion object {
        const val SENTINEL = -1
    }

    /** Examples recorded from the unfixed code, kept as a readable record of the mapping. */
    @Test
    fun mapping_observedExamples() {
        // Full list: [img0, vid1, img2, vid3]; videos: [vid1, vid3].
        val items = PreservationArbs.expectedScan(
            listOf(
                FakeDocumentsProvider.FakeFile("doc0", "a.jpg", "image/jpeg", 1, 1),
                FakeDocumentsProvider.FakeFile("doc1", "b.mp4", "video/mp4", 1, 1),
                FakeDocumentsProvider.FakeFile("doc2", "c.jpg", "image/jpeg", 1, 1),
                FakeDocumentsProvider.FakeFile("doc3", "d.mp4", "video/mp4", 1, 1),
            ),
        )
        assertEquals(3, Adapters.gridTapToFullIndex(items, isLandscapeMode = true, displayedIndex = 1))
        assertEquals(2, Adapters.gridTapToFullIndex(items, isLandscapeMode = false, displayedIndex = 2))
        assertEquals(1, Adapters.fullToLandscapeIndex(items, 3))
        assertEquals("a non-video in range opens video 0", 0, Adapters.fullToLandscapeIndex(items, 2))
        assertEquals("an out-of-range index is clamped", 1, Adapters.fullToLandscapeIndex(items, 9))
        assertEquals(0, Adapters.fullToLandscapeIndex(items, -1))
        assertEquals(3, Adapters.landscapeToFullIndex(items, 1))
        assertEquals(1, Adapters.gridScrollIndex(items, isLandscapeMode = true, targetFullIndex = 3))
        assertEquals("landscape grid falls back to tile 0", 0, Adapters.gridScrollIndex(items, true, 0))
        assertEquals("reels grid skips an out-of-range index", null, Adapters.gridScrollIndex(items, false, 7))
        assertTrue(Adapters.fullToLandscapeIndex(items.filter { !it.isVideo }, 0) == null)
    }
}
