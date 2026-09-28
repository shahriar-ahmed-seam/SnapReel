package com.snapreel.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.data.preferences.AppSettings
import com.snapreel.app.data.preferences.AspectRatioMode
import com.snapreel.app.data.preferences.RecentFolderInfo
import com.snapreel.app.data.preferences.SortOrder
import com.snapreel.app.di.createSettingsDataStore
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Property 11: Bug Condition - Settings storage failures fall back safely.
 *
 * Runs the production settings DataStore (same corruption handler as `AppModule`) on temp files.
 * Plain JVM: `AppPreferences` and DataStore need no Android framework.
 *
 * **Validates: Requirements 2.12, 3.1, 3.12**
 */
class PreferencesPropertyTest {

    private lateinit var dir: File
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("prefs-pbt").toFile()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    private var fileCounter = 0

    /** A fresh production DataStore on its own file (DataStore allows one active instance per file). */
    private fun newStore(initialBytes: ByteArray? = null): DataStore<Preferences> {
        val file = File(dir, "settings_${fileCounter++}.preferences_pb")
        if (initialBytes != null) file.writeBytes(initialBytes)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return createSettingsDataStore(scope) { file }
    }

    /** Wraps a DataStore and throws `IOException` on reads and/or writes while the flags are set. */
    private class FaultyDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile var failReads = false
        @Volatile var failWrites = false

        override val data: Flow<Preferences> = flow {
            if (failReads) throw IOException("injected read failure")
            emitAll(delegate.data)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("injected write failure")
            return delegate.updateData(transform)
        }
    }

    // ─── Garbage files ──────────────────────────────────────────────────────────────────

    @Test
    fun corruptFileYieldsDefaultsAndStaysWritable() = runBlocking<Unit> {
        checkAll(100, Arb.byteArray(Arb.int(1..256), Arb.byte())) { garbage ->
            val prefs = AppPreferences(newStore(garbage))

            val read = runCatching { prefs.settings.first() }
            assertTrue("reading a garbage file threw ${read.exceptionOrNull()}", read.isSuccess)
            assertEquals(AppSettings(), read.getOrNull())
            assertEquals(emptyList<String>(), prefs.recentFolders.first())

            // The corrupt file was replaced, so edits work and round-trip afterwards.
            assertTrue("edit after corruption failed", prefs.updateLoopVideos(false))
            assertEquals(false, prefs.settings.first().loopVideos)
        }
    }

    // ─── Injected read/write failures ───────────────────────────────────────────────────

    private sealed interface Op {
        data class Loop(val v: Boolean) : Op
        data class Shuffle(val v: Boolean) : Op
        data class Sort(val v: SortOrder) : Op
        data class AutoAdvance(val v: Boolean) : Op
        data class Delay(val v: Int) : Op
        data class Haptic(val v: Boolean) : Op
        data class ShowName(val v: Boolean) : Op
        data class Aspect(val v: AspectRatioMode) : Op
        data class Landscape(val v: Boolean) : Op
        data class FailReads(val v: Boolean) : Op
        data class FailWrites(val v: Boolean) : Op
    }

    private val opArb: Arb<Op> = Arb.choice(
        Arb.boolean().map { Op.Loop(it) },
        Arb.boolean().map { Op.Shuffle(it) },
        Arb.enum<SortOrder>().map { Op.Sort(it) },
        Arb.boolean().map { Op.AutoAdvance(it) },
        Arb.int(1..30).map { Op.Delay(it) },
        Arb.boolean().map { Op.Haptic(it) },
        Arb.boolean().map { Op.ShowName(it) },
        Arb.enum<AspectRatioMode>().map { Op.Aspect(it) },
        Arb.boolean().map { Op.Landscape(it) },
        Arb.boolean().map { Op.FailReads(it) },
        Arb.boolean().map { Op.FailWrites(it) },
    )

    @Test
    fun ioFailuresNeverThrowAndFallBack() = runBlocking<Unit> {
        checkAll(100, Arb.list(opArb, 1..30)) { ops ->
            val store = FaultyDataStore(newStore())
            val prefs = AppPreferences(store)
            var model = AppSettings()

            for (op in ops) {
                val (edit, next) = when (op) {
                    is Op.Loop -> suspend { prefs.updateLoopVideos(op.v) } to model.copy(loopVideos = op.v)
                    is Op.Shuffle -> suspend { prefs.updateShuffleMedia(op.v) } to model.copy(shuffleMedia = op.v)
                    is Op.Sort -> suspend { prefs.updateSortOrder(op.v) } to model.copy(sortOrder = op.v)
                    is Op.AutoAdvance -> suspend { prefs.updateAutoAdvanceImages(op.v) } to model.copy(autoAdvanceImages = op.v)
                    is Op.Delay -> suspend { prefs.updateAutoAdvanceDelay(op.v) } to model.copy(autoAdvanceDelaySeconds = op.v)
                    is Op.Haptic -> suspend { prefs.updateHapticFeedback(op.v) } to model.copy(hapticFeedback = op.v)
                    is Op.ShowName -> suspend { prefs.updateShowFileName(op.v) } to model.copy(showFileName = op.v)
                    is Op.Aspect -> suspend { prefs.updateAspectRatioMode(op.v) } to model.copy(aspectRatioMode = op.v)
                    is Op.Landscape -> suspend { prefs.updateLandscapeVideoMode(op.v) } to model.copy(landscapeVideoMode = op.v)
                    is Op.FailReads -> { store.failReads = op.v; null to model }
                    is Op.FailWrites -> { store.failWrites = op.v; null to model }
                }
                if (edit != null) {
                    val result = runCatching { edit() }
                    assertTrue("edit $op threw ${result.exceptionOrNull()}", result.isSuccess)
                    assertEquals("edit $op result (failWrites=${store.failWrites})", !store.failWrites, result.getOrThrow())
                    if (result.getOrThrow()) model = next
                }

                val read = runCatching { prefs.settings.first() }
                assertTrue("read after $op threw ${read.exceptionOrNull()}", read.isSuccess)
                val expected = if (store.failReads) AppSettings() else model
                assertEquals("settings after $op (failReads=${store.failReads})", expected, read.getOrThrow())
                val recents = runCatching { prefs.recentFolders.first() }
                assertTrue("recents read after $op threw ${recents.exceptionOrNull()}", recents.isSuccess)
            }
        }
    }

    // ─── Recents round trip (3- and 4-field entries) ────────────────────────────────────

    private sealed interface RecentOp {
        data class Add(val uri: String, val name: String) : RecentOp
        data class Update(val uri: String, val index: Int, val itemUri: String?) : RecentOp
        data class Remove(val uri: String) : RecentOp
    }

    private val folderUris = (0 until 14).map { "content://com.test.docs/tree/folder$it" }
    private val folderUriArb = Arb.element(folderUris)
    private val nameArb = Arb.stringPattern("[A-Za-z0-9 _.-]{0,12}")
    // No `orNull`: Kotest's edge-case builder crashes on lists of orNull elements. ~40% null.
    private val itemUriArb: Arb<String?> =
        Arb.int(0..50).map { if (it < 20) null else "content://com.test.docs/document/item$it" }

    private val recentOpArb: Arb<RecentOp> = Arb.choice(
        Arb.bind(folderUriArb, nameArb) { u, n -> RecentOp.Add(u, n) },
        Arb.bind(folderUriArb, Arb.int(0..500), itemUriArb) { u, i, item -> RecentOp.Update(u, i, item) },
        folderUriArb.map { RecentOp.Remove(it) },
    )

    @Test
    fun recentsRoundTripWithOptionalItemUri() = runBlocking<Unit> {
        checkAll(100, Arb.list(recentOpArb, 1..40)) { ops ->
            val prefs = AppPreferences(newStore())
            var model = listOf<RecentFolderInfo>()

            for (op in ops) {
                when (op) {
                    is RecentOp.Add -> {
                        assertTrue(prefs.addRecentFolder(op.uri, op.name))
                        model = (listOf(RecentFolderInfo(op.uri, op.name, 0, null)) + model.filter { it.uri != op.uri }).take(10)
                    }
                    is RecentOp.Update -> {
                        assertTrue(prefs.updateLastViewed(op.uri, op.index, op.itemUri))
                        model = model.map {
                            if (it.uri == op.uri) it.copy(lastIndex = op.index, lastItemUri = op.itemUri) else it
                        }
                    }
                    is RecentOp.Remove -> {
                        assertTrue(prefs.removeRecentFolder(op.uri))
                        model = model.filter { it.uri != op.uri }
                    }
                }
                val stored = prefs.recentFolders.first().mapNotNull { prefs.parseRecentFolderEntry(it) }
                assertEquals("recents after $op", model, stored)
                model.forEach { assertEquals(it, prefs.recentFolder(it.uri)) }
            }
        }
    }

    @Test
    fun threeAndFourFieldEntriesParse() = runBlocking<Unit> {
        val prefs = AppPreferences(newStore())
        checkAll(1_000, folderUriArb, nameArb, Arb.int(0..100_000), itemUriArb) { uri, name, index, itemUri ->
            assertEquals(
                RecentFolderInfo(uri, name, index, null),
                prefs.parseRecentFolderEntry("$uri<<>>$name<<>>$index"),
            )
            val fourField = if (itemUri == null) "$uri<<>>$name<<>>$index" else "$uri<<>>$name<<>>$index<<>>$itemUri"
            assertEquals(RecentFolderInfo(uri, name, index, itemUri), prefs.parseRecentFolderEntry(fourField))
        }
    }
}
