package com.snapreel.app

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.data.repository.AccessLostReason
import com.snapreel.app.data.repository.FolderAccessLostException
import com.snapreel.app.data.repository.FolderLoadError
import com.snapreel.app.data.repository.MediaRepository
import com.snapreel.app.testsupport.Adapters
import com.snapreel.app.testsupport.FakeDocumentsProvider
import com.snapreel.app.testsupport.FakeDocumentsProvider.Companion.RootMode
import com.snapreel.app.testsupport.FakeDocumentsProvider.FakeFile
import com.snapreel.app.testsupport.awaitMain
import com.snapreel.app.ui.common.FolderAccessLostTags
import com.snapreel.app.ui.home.ACCESS_MAY_NOT_PERSIST_MESSAGE
import com.snapreel.app.ui.home.handlePickedFolder
import com.snapreel.app.ui.viewer.FolderEvent
import com.snapreel.app.ui.viewer.FolderGridViewModel
import com.snapreel.app.ui.viewer.FolderMediaGridScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowToast

/**
 * Folder access (1.11, 1.13, 1.14): a refused persistable grant never crashes, the folder-name
 * lookup runs off the main thread, and a folder that can't be read shows "access lost" (with a
 * re-pick) instead of "No media found".
 *
 * **Validates: Requirements 2.11, 2.13, 2.14**
 */
@RunWith(AndroidJUnit4::class)
class FolderAccessTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private val tree get() = FakeDocumentsProvider.treeUri
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        Adapters.resetProcessState()
        context = ApplicationProvider.getApplicationContext()
        Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(FakeDocumentsProvider.AUTHORITY)
        FakeDocumentsProvider.reset()
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        FakeDocumentsProvider.reset()
        Adapters.resetProcessState()
    }

    private fun repository(ctx: Context = context) = MediaRepository(ctx, Adapters.appPreferences(context))

    private fun grid(repo: MediaRepository): FolderGridViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V =
                FolderGridViewModel(repo, Adapters.appPreferences(context)) as V
        })[FolderGridViewModel::class.java]
    }

    private fun video(id: String) = FakeFile(id, "$id.mp4", "video/mp4", 1_000L, 1_700_000_000_000L)

    // ─── 1.11: persistable grant refused ────────────────────────────────────────────────

    /** A context whose resolver refuses persistable grants, like providers that don't offer them. */
    private class RefusingContext(base: Context) : ContextWrapper(base) {
        private val resolver = object : ContentResolver(base) {
            override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
                throw SecurityException("No persistable permission grants found for $uri")
            }
        }
        override fun getContentResolver(): ContentResolver = resolver
    }

    @Test
    fun takePersistableAccess_returnsFalseOnSecurityException() {
        assertFalse(repository(RefusingContext(context)).takePersistableAccess(tree))
        assertTrue(repository().takePersistableAccess(tree))
    }

    @Test
    fun homePicker_warnsAndStillOpensWhenAccessIsRefused() {
        val picked = mutableListOf<Uri>()
        val opened = mutableListOf<Uri>()

        handlePickedFolder(context, tree, takeAccess = { false }, onFolderPicked = { picked += it }, onFolderSelected = { opened += it })
        assertEquals(ACCESS_MAY_NOT_PERSIST_MESSAGE, ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(tree), picked)
        assertEquals(listOf(tree), opened)

        ShadowToast.reset()
        handlePickedFolder(context, tree, takeAccess = { true }, onFolderPicked = { picked += it }, onFolderSelected = { opened += it })
        assertEquals("no warning when access is granted", 0, ShadowToast.shownToastCount())
        assertEquals(2, opened.size)
    }

    // ─── 1.13: folder name lookup off the main thread ───────────────────────────────────

    @Test
    fun getFolderDisplayName_queriesOffMain() = runBlocking<Unit> {
        assertSame("the test must call from the main thread", Looper.getMainLooper(), Looper.myLooper())
        val name = repository().getFolderDisplayName(tree)
        assertEquals("Fake Folder", name)
        val queryThread = FakeDocumentsProvider.lastQueryThread
        assertNotNull(queryThread)
        assertNotSame("the provider query ran on the main thread", Looper.getMainLooper().thread, queryThread)
    }

    // ─── 1.14: access loss is recognized ────────────────────────────────────────────────

    private suspend fun scanError(repo: MediaRepository, uri: Uri): AccessLostReason? =
        try {
            repo.scanFolder(uri, forceRefresh = true)
            null
        } catch (e: FolderAccessLostException) {
            e.reason
        }

    @Test
    fun rootCheck_mapsEachFailureToItsReason() = runBlocking<Unit> {
        val expected = mapOf(
            RootMode.SECURITY_EXCEPTION to AccessLostReason.PERMISSION,
            RootMode.NULL_CURSOR to AccessLostReason.MISSING,
            RootMode.NO_ROW to AccessLostReason.MISSING,
            RootMode.FILE_NOT_FOUND to AccessLostReason.MISSING,
        )
        val repo = repository()
        for ((mode, reason) in expected) {
            FakeDocumentsProvider.rootMode = mode
            assertEquals("root mode $mode", reason, scanError(repo, tree))
        }
        // Not a tree URI at all (IllegalArgumentException from the contract).
        FakeDocumentsProvider.rootMode = RootMode.NORMAL
        val notATree = DocumentsContract.buildDocumentUri(FakeDocumentsProvider.AUTHORITY, "x")
        assertEquals(AccessLostReason.MISSING, scanError(repo, notATree))
    }

    @Test
    fun grid_distinguishesAccessLostFromEmpty() {
        FakeDocumentsProvider.rootMode = RootMode.SECURITY_EXCEPTION
        val lost = grid(repository())
        lost.loadMedia(tree)
        assertTrue(awaitMain { !lost.uiState.value.isLoading })
        assertEquals(FolderLoadError.AccessLost(AccessLostReason.PERMISSION), lost.uiState.value.error)

        FakeDocumentsProvider.rootMode = RootMode.NORMAL
        val empty = grid(repository())
        empty.loadMedia(tree)
        assertTrue(awaitMain { !empty.uiState.value.isLoading })
        assertEquals(FolderLoadError.NoMedia, empty.uiState.value.error)
    }

    @Test
    fun rePickingTheSameTreeReloadsInPlace() {
        FakeDocumentsProvider.rootMode = RootMode.SECURITY_EXCEPTION
        val vm = grid(repository())
        vm.loadMedia(tree)
        assertTrue(awaitMain { !vm.uiState.value.isLoading })
        assertTrue(vm.uiState.value.error is FolderLoadError.AccessLost)

        // The user picks the same folder again, which restores access.
        FakeDocumentsProvider.rootMode = RootMode.NORMAL
        FakeDocumentsProvider.files = listOf(video("a"), video("b"))
        vm.onFolderRePicked(tree, tree)
        assertTrue(awaitMain { !vm.uiState.value.isLoading && vm.uiState.value.mediaItems.size == 2 })
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun rePickingAnotherTreeOpensItAboveHome() = runBlocking<Unit> {
        FakeDocumentsProvider.rootMode = RootMode.SECURITY_EXCEPTION
        val vm = grid(repository())
        vm.loadMedia(tree)
        assertTrue(awaitMain { !vm.uiState.value.isLoading })

        val events = mutableListOf<FolderEvent>()
        val collector = CoroutineScope(Dispatchers.Main)
        collector.launch { vm.events.collect { events += it } }
        try {
            FakeDocumentsProvider.rootMode = RootMode.NORMAL
            val other = DocumentsContract.buildTreeDocumentUri(FakeDocumentsProvider.AUTHORITY, "other")
            vm.onFolderRePicked(tree, other)
            assertTrue("no OpenOtherFolder event", awaitMain { events.isNotEmpty() })
            assertEquals(FolderEvent.OpenOtherFolder(other), events.single())
            val recents = Adapters.appPreferences(context).recentFolders.first()
            assertTrue("the other folder must be added to Recents: $recents", recents.first().startsWith("$other<<>>"))
        } finally {
            collector.cancel()
        }
    }

    // ─── 1.14: the grid UI ──────────────────────────────────────────────────────────────

    @Test
    fun gridScreen_showsAccessLostInsteadOfNoMedia() {
        FakeDocumentsProvider.rootMode = RootMode.SECURITY_EXCEPTION
        val vm = grid(repository())
        compose.setContent {
            FolderMediaGridScreen(folderUri = tree, onBack = {}, onMediaClick = { _, _ -> }, viewModel = vm)
        }
        compose.waitUntil(10_000) { !vm.uiState.value.isLoading }
        compose.onNodeWithTag(FolderAccessLostTags.ROOT).assertIsDisplayed()
        compose.onNodeWithTag(FolderAccessLostTags.PICK_AGAIN).assertIsDisplayed()
        compose.onAllNodesWithText("No media found in this folder").assertCountEquals(0)
    }
}
