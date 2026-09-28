package com.snapreel.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.SortOrder
import com.snapreel.app.data.repository.FolderLoadError
import com.snapreel.app.data.repository.FolderScanner
import com.snapreel.app.data.repository.MediaRepository
import com.snapreel.app.preservation.LegacyOracles
import com.snapreel.app.preservation.PreservationArbs
import com.snapreel.app.testsupport.Adapters
import com.snapreel.app.testsupport.FakeDocumentsProvider
import com.snapreel.app.testsupport.TestPlayers
import com.snapreel.app.testsupport.awaitMain
import com.snapreel.app.ui.viewer.FolderGridViewModel
import com.snapreel.app.ui.viewer.MediaIndexMapping
import com.snapreel.app.ui.viewer.ReelsViewerViewModel
import com.snapreel.app.util.thumbnail.ThumbnailWorkGate
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property 7: Bug Condition - Fresh snapshot shared by grid and viewers.
 *
 * - Opening a folder from Home (a new grid, or the play button's `fresh` viewer) rescans it with
 *   the current contents and sort/shuffle settings; returning from a viewer reuses the snapshot.
 * - Grid taps, the landscape mapping and the saved position all refer to the same item.
 *
 * **Validates: Requirements 2.15, 3.2, 3.3, 3.5, 3.10**
 */
@RunWith(AndroidJUnit4::class)
class SnapshotPropertyTest {

    private lateinit var context: Context
    private val tree get() = FakeDocumentsProvider.treeUri

    @Before
    fun setUp() {
        Adapters.resetProcessState()
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        FakeDocumentsProvider.reset()
        Adapters.resetProcessState()
    }

    /** A folder scanner returning [contents], counting scans. */
    private class FakeFolder(var contents: List<MediaItem> = emptyList()) : FolderScanner {
        val scans = AtomicInteger()
        override fun scan(treeUri: android.net.Uri): List<MediaItem> {
            scans.incrementAndGet()
            return contents
        }
    }

    private fun gridViewModel(store: ViewModelStore, repo: MediaRepository): FolderGridViewModel =
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V =
                FolderGridViewModel(repo, Adapters.appPreferences(context)) as V
        })[FolderGridViewModel::class.java]

    private fun reelsViewModel(store: ViewModelStore, repo: MediaRepository): ReelsViewerViewModel =
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = ReelsViewerViewModel(
                repo,
                Adapters.appPreferences(context),
                TestPlayers.nonFailingPoolFactory(context),
                ThumbnailWorkGate(),
            ) as V
        })[ReelsViewerViewModel::class.java]

    /** What a scan with these settings lists: the sorted contents, or a permutation when shuffled. */
    private fun assertOrdered(label: String, contents: List<MediaItem>, order: SortOrder, shuffle: Boolean, actual: List<MediaItem>) {
        val sorted = LegacyOracles.sortMedia(contents, order)
        if (shuffle) {
            assertEquals("$label: shuffle must be a permutation of the contents", sorted.map { it.uri }.toSet(), actual.map { it.uri }.toSet())
            assertEquals("$label: shuffle keeps every item once", sorted.size, actual.size)
        } else {
            assertEquals("$label: order must follow the current sort setting", sorted, actual)
        }
    }

    @Test
    fun openingFromHomeRescansAndScreensShareOneSnapshot() = runBlocking<Unit> {
        checkAll(
            50,
            PreservationArbs.mediaList, PreservationArbs.mediaList,
            Arb.enum<SortOrder>(), Arb.boolean(),
            Arb.enum<SortOrder>(), Arb.boolean(),
            Arb.int(0..40),
        ) { first, second, order1, shuffle1, order2, shuffle2, tapSeed ->
            val prefs = Adapters.appPreferences(context)
            val folder = FakeFolder(first)
            val repo = MediaRepository(context, prefs).apply { scanner = folder }
            val stores = mutableListOf<ViewModelStore>()
            try {
                // 1. Open the grid from Home.
                Adapters.applySortSettings(prefs, order1, shuffle1)
                val store1 = ViewModelStore().also { stores += it }
                val grid1 = gridViewModel(store1, repo)
                grid1.loadMedia(tree)
                assertTrue("grid 1 never finished loading", awaitMain { !grid1.uiState.value.isLoading })
                val snapshot1 = grid1.uiState.value.mediaItems
                if (first.isEmpty()) {
                    assertEquals(FolderLoadError.NoMedia, grid1.uiState.value.error)
                } else {
                    assertOrdered("first open", first, order1, shuffle1, snapshot1)
                    assertEquals("the grid shows the shared snapshot", repo.getCachedMedia(tree), snapshot1)

                    // A grid tap opens the reels viewer on the shared snapshot, at exactly that item.
                    val tapped = tapSeed % snapshot1.size
                    val fullIndex = MediaIndexMapping.gridTapToFullIndex(snapshot1, false, tapped)
                    val reelsStore = ViewModelStore().also { stores += it }
                    val reels = reelsViewModel(reelsStore, repo)
                    reels.loadMedia(tree, fullIndex, fresh = false)
                    assertTrue("reels never loaded", awaitMain { !reels.uiState.value.isLoading })
                    assertEquals("the viewer uses the grid's snapshot", snapshot1, reels.uiState.value.mediaItems)
                    assertEquals(snapshot1[tapped], reels.uiState.value.mediaItems[reels.uiState.value.currentIndex])
                    reelsStore.clear()
                }

                // 2. The folder and the settings change while the app runs.
                folder.contents = second
                Adapters.applySortSettings(prefs, order2, shuffle2)

                // Returning from a viewer to the same grid reuses its snapshot (no rescan).
                val scansBefore = folder.scans.get()
                grid1.loadMedia(tree)
                assertTrue(awaitMain { !grid1.uiState.value.isLoading })
                if (first.isNotEmpty()) {
                    assertEquals("returning to the grid must not rescan", scansBefore, folder.scans.get())
                    assertEquals(snapshot1, grid1.uiState.value.mediaItems)
                }

                // 3. Opening the folder from Home again rescans with the new contents and settings.
                val store2 = ViewModelStore().also { stores += it }
                val grid2 = gridViewModel(store2, repo)
                grid2.loadMedia(tree)
                assertTrue("grid 2 never finished loading", awaitMain { !grid2.uiState.value.isLoading })
                if (second.isEmpty()) {
                    assertEquals(FolderLoadError.NoMedia, grid2.uiState.value.error)
                } else {
                    val snapshot2 = grid2.uiState.value.mediaItems
                    assertOrdered("reopen from Home", second, order2, shuffle2, snapshot2)
                    assertEquals("the reopened grid shows the new shared snapshot", repo.getCachedMedia(tree), snapshot2)
                }
            } finally {
                stores.forEach { it.clear() }
                Adapters.resetProcessState()
            }
        }
    }

    @Test
    fun playButtonRescansAndStartsAtSavedItem() = runBlocking<Unit> {
        checkAll(
            50,
            PreservationArbs.mediaList, PreservationArbs.mediaList,
            Arb.enum<SortOrder>(), Arb.boolean(), Arb.int(0..40), Arb.boolean(),
        ) { before, after, order, shuffle, savedSeed, itemStillThere ->
            if (before.isEmpty()) return@checkAll
            val prefs = Adapters.appPreferences(context)
            val savedIndex = savedSeed % before.size
            val savedItem = before[savedIndex]
            // The folder changes after the position was saved; the saved item may be gone.
            val contents = if (itemStillThere) after.filter { it.uri != savedItem.uri } + savedItem
            else after.filter { it.uri != savedItem.uri }
            val folder = FakeFolder(contents)
            val repo = MediaRepository(context, prefs).apply { scanner = folder }
            val store = ViewModelStore()
            try {
                Adapters.applySortSettings(prefs, order, shuffle)
                prefs.addRecentFolder(tree.toString(), "Folder")
                prefs.updateLastViewed(tree.toString(), savedIndex, savedItem.uri.toString())

                val reels = reelsViewModel(store, repo)
                reels.loadMedia(tree, savedIndex, fresh = true)
                assertTrue("reels never loaded", awaitMain { !reels.uiState.value.isLoading })
                assertEquals("the play button must rescan", 1, folder.scans.get())
                val state = reels.uiState.value
                if (contents.isEmpty()) {
                    assertEquals(FolderLoadError.NoMedia, state.error)
                    return@checkAll
                }
                assertOrdered("play button", contents, order, shuffle, state.mediaItems)
                assertEquals(repo.getCachedMedia(tree), state.mediaItems)
                val expected = MediaIndexMapping
                    .resolveStartIndex(state.mediaItems, savedItem.uri.toString(), savedIndex)
                    .coerceIn(0, state.mediaItems.size - 1)
                assertEquals(expected, state.currentIndex)
                if (itemStillThere) {
                    assertEquals("the saved item must open, wherever it moved", savedItem.uri, state.mediaItems[state.currentIndex].uri)
                }
            } finally {
                store.clear()
                Adapters.resetProcessState()
            }
        }
    }

    // ─── Pure mapping ───────────────────────────────────────────────────────────────────

    @Test
    fun gridAndLandscapeMappingsRoundTrip() = runBlocking<Unit> {
        checkAll(1_000, PreservationArbs.mediaList, Arb.boolean(), Arb.int(0..40)) { items, landscape, seed ->
            val displayed = MediaIndexMapping.gridDisplayedItems(items, landscape)
            if (displayed.isEmpty()) return@checkAll
            val tapped = seed % displayed.size
            val full = MediaIndexMapping.gridTapToFullIndex(items, landscape, tapped)
            assertEquals("the tap maps to the tapped item", displayed[tapped], items[full])
            if (landscape) {
                val videos = items.filter { it.isVideo }
                val videoIndex = MediaIndexMapping.fullToLandscapeIndex(items, full)!!
                assertEquals("landscape opens the tapped video", displayed[tapped], videos[videoIndex])
                assertEquals("back returns the tapped item", full, MediaIndexMapping.landscapeToFullIndex(items, videoIndex))
            }
        }
    }

    @Test
    fun resolveStartIndexFindsSavedItemOrFallsBack() = runBlocking<Unit> {
        checkAll(1_000, PreservationArbs.mediaList, Arb.int(0..40), Arb.int(0..60), Arb.int(0..2)) { items, pick, fallback, mode ->
            val savedUri = when (mode) {
                0 -> null
                1 -> items.getOrNull(pick % (items.size.coerceAtLeast(1)))?.uri?.toString()
                else -> "content://gone/document/missing$pick"
            }
            val result = MediaIndexMapping.resolveStartIndex(items, savedUri, fallback)
            val found = items.indexOfFirst { it.uri.toString() == savedUri }
            if (savedUri != null && found >= 0) {
                assertEquals(found, result)
            } else {
                assertEquals("a missing item falls back to the saved index", fallback, result)
            }
        }
    }
}
