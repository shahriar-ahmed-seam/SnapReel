package com.snapreel.app.preservation

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.data.preferences.AppPreferences
import com.snapreel.app.testsupport.Adapters
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.choose
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

/**
 * Property 2: Preservation - Recents (3.1, 3.2, 3.12).
 *
 * Production is the REAL `AppPreferences` (through [Adapters]): `addRecentFolder`,
 * `removeRecentFolder`, `updateLastViewedIndex`, `recentFolders`, `parseRecentFolderEntry`, and
 * `FolderGridViewModel.getSavedLastIndex`. The oracle replays the same operations on the raw
 * `recent_folders` string with the verbatim unfixed code. Entries are compared as Home shows them
 * (uri, name, lastIndex), so the fix's optional 4th field doesn't count as a change.
 *
 * Observed on the UNFIXED code:
 * - A pick puts the folder at the top with lastIndex 0. Picking a listed folder again moves it to
 *   the top and resets its lastIndex to 0 (its name is replaced by the new one).
 * - The list is capped at 10: the 11th distinct pick drops the oldest (bottom) entry.
 * - Saving a position changes only that entry's lastIndex and never reorders; for an unlisted
 *   folder it is a no-op. Removing an unlisted folder is a no-op.
 * - Entries are `uri<<>>name<<>>index`; a 3-field entry parses to (uri, name, index), and a
 *   non-numeric index parses as 0. The grid's saved index is the entry's lastIndex, or 0 if absent.
 */
@RunWith(AndroidJUnit4::class)
class RecentsPreservationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        Adapters.resetProcessState()
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Adapters.resetProcessState()
    }

    private sealed interface Op {
        data class Add(val uri: String, val name: String) : Op
        data class Remove(val uri: String) : Op
        data class UpdateLastViewed(val uri: String, val index: Int) : Op
    }

    // 14 folders (more than the cap). "Folder1" and "Folder10".."Folder13" share a prefix.
    private val folderUris = (1..14).map { "content://com.android.externalstorage.documents/tree/primary%3AFolder$it" }
    private val folderUri = Arb.element(folderUris)
    private val displayName = Arb.stringPattern("[A-Za-z0-9 _.()-]{1,20}")

    // Weighted toward picks so most sequences reach the cap of 10.
    private val op: Arb<Op> = Arb.choose(
        5 to Arb.bind(folderUri, displayName) { u, n -> Op.Add(u, n) },
        1 to folderUri.map { Op.Remove(it) },
        2 to Arb.bind(folderUri, Arb.int(0..500)) { u, i -> Op.UpdateLastViewed(u, i) },
    )

    private fun oracleEntries(raw: String?) =
        LegacyOracles.recentFolders(raw).mapNotNull { LegacyOracles.parseRecentFolderEntry(it) }

    private suspend fun clearRecents(prefs: AppPreferences) {
        Adapters.recents(prefs).forEach { Adapters.recentsRemove(prefs, it.uri) }
        assertTrue("setup: Recents must start empty", Adapters.recentsRawEntries(prefs).isEmpty())
    }

    @Test
    fun recents_sequencesMatchLegacy() = runTest(timeout = 5.minutes) {
        val prefs = Adapters.appPreferences(context)
        val repo = Adapters.mediaRepository(context, prefs)

        var cappedRuns = 0

        checkAll(100, Arb.list(op, 1..40), folderUri) { ops, probeUri ->
            clearRecents(prefs)
            var raw: String? = null
            val trace = StringBuilder()
            var reachedCap = false
            for (o in ops) {
                when (o) {
                    is Op.Add -> {
                        Adapters.recentsAdd(prefs, o.uri, o.name)
                        raw = LegacyOracles.addRecentFolder(raw, o.uri, o.name)
                    }
                    is Op.Remove -> {
                        Adapters.recentsRemove(prefs, o.uri)
                        raw = LegacyOracles.removeRecentFolder(raw, o.uri)
                    }
                    is Op.UpdateLastViewed -> {
                        Adapters.recentsUpdateLastViewed(prefs, o.uri, o.index)
                        raw = LegacyOracles.updateLastViewedIndex(raw, o.uri, o.index)
                    }
                }
                trace.append(o).append('\n')
                val expected = oracleEntries(raw)
                assertEquals("Recents after\n$trace", expected, Adapters.recents(prefs))
                assertEquals("stored entry count after\n$trace", LegacyOracles.recentFolders(raw).size, Adapters.recentsRawEntries(prefs).size)
                assertTrue("cap of 10", expected.size <= 10)
                if (expected.size == 10) reachedCap = true
            }
            if (reachedCap) cappedRuns++
            assertEquals(
                "grid saved index for $probeUri after\n$trace",
                LegacyOracles.savedLastIndex(raw, probeUri),
                Adapters.gridSavedLastIndex(repo, prefs, Uri.parse(probeUri)),
            )
        }
        assertTrue("coverage: only $cappedRuns of 100 runs reached the cap", cappedRuns >= 20)
    }

    /** 3-field entries (as stored by every released version) parse exactly as before. */
    @Test
    fun recents_threeFieldEntriesParseAsBefore() = runTest {
        val prefs = Adapters.appPreferences(context)
        val indexField = Arb.choice(
            Arb.int().map { it.toString() },
            Arb.int(0..500).map { it.toString() },
            Arb.element("", "abc", "1.5", " 3", "+4", "007", "2147483648", "-0"),
        )
        checkAll(
            1_000,
            Arb.stringPattern("content://[a-z.]{1,20}/tree/[A-Za-z0-9%]{1,20}"),
            Arb.stringPattern("[A-Za-z0-9 _.()|<>-]{0,20}"),
            indexField,
        ) { uri, name, index ->
            // A delimiter inside a field isn't a 3-field entry.
            if ("<<>>" in name || "|||" in name) return@checkAll
            val entry = "$uri<<>>$name<<>>$index"
            assertEquals("parse of \"$entry\"", LegacyOracles.parseRecentFolderEntry(entry), Adapters.parseRecentEntry(prefs, entry))
        }
    }

    @Test
    fun recents_observedBehavior() = runTest {
        val prefs = Adapters.appPreferences(context)
        val (a, b) = folderUris
        fun e(uri: String, name: String, index: Int) = LegacyOracles.RecentEntry(uri, name, index)

        Adapters.recentsAdd(prefs, a, "A")
        Adapters.recentsAdd(prefs, b, "B")
        assertEquals("newest first", listOf(e(b, "B", 0), e(a, "A", 0)), Adapters.recents(prefs))

        Adapters.recentsUpdateLastViewed(prefs, a, 7)
        assertEquals("a save never reorders", listOf(e(b, "B", 0), e(a, "A", 7)), Adapters.recents(prefs))
        assertEquals(7, Adapters.gridSavedLastIndex(Adapters.mediaRepository(context, prefs), prefs, Uri.parse(a)))

        Adapters.recentsAdd(prefs, a, "A2")
        assertEquals("a re-pick moves to the top and resets the index", listOf(e(a, "A2", 0), e(b, "B", 0)), Adapters.recents(prefs))

        Adapters.recentsUpdateLastViewed(prefs, folderUris[5], 3)
        Adapters.recentsRemove(prefs, folderUris[6])
        assertEquals("unlisted update/remove are no-ops", listOf(e(a, "A2", 0), e(b, "B", 0)), Adapters.recents(prefs))

        folderUris.drop(2).take(9).forEach { Adapters.recentsAdd(prefs, it, "x") }
        val capped = Adapters.recents(prefs)
        assertEquals("cap of 10", 10, capped.size)
        assertEquals("the oldest entry is dropped", listOf(folderUris[2], a), capped.takeLast(2).map { it.uri })
        assertTrue(capped.none { it.uri == b })

        assertEquals(e("u", "n", 0), Adapters.parseRecentEntry(prefs, "u<<>>n<<>>abc"))
        assertEquals(e("u", "n", 12), Adapters.parseRecentEntry(prefs, "u<<>>n<<>>12"))
    }
}
