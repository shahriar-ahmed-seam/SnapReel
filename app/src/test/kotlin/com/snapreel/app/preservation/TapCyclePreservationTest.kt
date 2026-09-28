package com.snapreel.app.preservation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.data.preferences.SortOrder
import com.snapreel.app.testsupport.Adapters
import com.snapreel.app.testsupport.FakeDocumentsProvider
import com.snapreel.app.testsupport.FakeDocumentsProvider.FakeFile
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import kotlin.time.Duration.Companion.minutes

/**
 * Property 2: Preservation - the reels 3-state tap cycle (3.6, 3.11).
 *
 * Production is the REAL `ReelsViewerViewModel` (through [Adapters.reelsTapProbe]), opened on a
 * scanned folder and driven with tap / timeout / app-pause / swipe / mute events, with no playback
 * failure (¬C6). The auto-hide delay lives inline in `VideoPage`, so it is ROUTED to
 * [LegacyOracles.REELS_AUTO_HIDE_MS]. The harness plays `VideoPage`'s effect: it restarts whenever
 * `showControls` or `isPlaying` changes and fires `onControlsTimeout()` once the delay has elapsed
 * while both are true.
 *
 * Observed on the UNFIXED code (State A = playing/hidden, B = playing/controls, C = paused/controls):
 * - Settling on a video → A; settling on an image → isPlaying = false, controls hidden.
 * - Video tap: A → B, B → C, C → A. Image tap toggles `showControls` only.
 * - Auto-hide: B → A after 3000 ms in B (a tap or swipe in between restarts it); never from C.
 *   The landscape viewer uses 3500 ms.
 * - `ON_PAUSE` on a video → C (from any state); on an image, nothing changes.
 * - Mute toggles independently and survives swipes.
 */
@RunWith(AndroidJUnit4::class)
class TapCyclePreservationTest {

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

    sealed interface Event {
        data object Tap : Event
        data class Elapse(val ms: Long) : Event
        data object AppPause : Event
        data class Swipe(val delta: Int) : Event
        data object Mute : Event
    }

    private val event: Arb<Event> = Arb.choice(
        Arb.constant(Event.Tap),
        Arb.constant(Event.Tap),
        Arb.constant(Event.AppPause),
        Arb.constant(Event.Mute),
        Arb.element(-1, 1).map { Event.Swipe(it) },
        Arb.choice(Arb.element(1L, 2_999L, 3_000L, 3_001L, 6_000L), Arb.long(0L..7_000L)).map { Event.Elapse(it) },
    )

    /** A folder with a video/image mix (every row is listed). */
    private val mixedFolder: Arb<List<FakeFile>> = Arb.list(Arb.element("mp4", "jpg"), 1..8).map { exts ->
        exts.mapIndexed { i, ext ->
            FakeFile("doc$i", "f$i.$ext", if (ext == "mp4") "video/mp4" else "image/jpeg", i.toLong(), i.toLong())
        }
    }

    /** The state machine under test: the real ViewModel or the oracle. */
    private interface Subject {
        val currentIndex: Int
        val isPlaying: Boolean
        val showControls: Boolean
        val isMuted: Boolean
        fun tap(); fun timeout(); fun appPaused(); fun settle(index: Int); fun mute()
    }

    private class ProbeSubject(val probe: Adapters.ReelsTapProbe) : Subject {
        override val currentIndex get() = probe.currentIndex
        override val isPlaying get() = probe.isPlaying
        override val showControls get() = probe.showControls
        override val isMuted get() = probe.isMuted
        override fun tap() = probe.tap()
        override fun timeout() = probe.controlsTimeout()
        override fun appPaused() = probe.appPaused()
        override fun settle(index: Int) = probe.settle(index)
        override fun mute() = probe.toggleMute()
    }

    private class OracleSubject(val m: LegacyOracles.ReelsTapMachine) : Subject {
        override val currentIndex get() = m.currentIndex
        override val isPlaying get() = m.isPlaying
        override val showControls get() = m.showControls
        override val isMuted get() = m.isMuted
        override fun tap() = m.tap()
        override fun timeout() = m.onControlsTimeout()
        override fun appPaused() = m.onAppPaused()
        override fun settle(index: Int) = m.onPageSettled(index)
        override fun mute() = m.toggleMute()
    }

    /** `VideoPage`'s `LaunchedEffect(showControls, isPlaying, isDraggingSlider) { delay(d); onControlsTimeout() }`. */
    private class Screen(val s: Subject, val itemCount: Int, val autoHideMs: Long) {
        private var key = s.showControls to s.isPlaying
        private var armedMs = 0L

        private fun afterChange() {
            val now = s.showControls to s.isPlaying
            if (now != key) { key = now; armedMs = 0L }
        }

        fun apply(e: Event) {
            when (e) {
                Event.Tap -> s.tap()
                Event.AppPause -> s.appPaused()
                Event.Mute -> s.mute()
                is Event.Swipe -> {
                    val target = s.currentIndex + e.delta
                    if (target in 0 until itemCount) s.settle(target) // settledPage changed
                }
                is Event.Elapse -> {
                    var remaining = e.ms
                    while (remaining > 0 && s.showControls && s.isPlaying) {
                        val left = autoHideMs - armedMs
                        if (remaining < left) { armedMs += remaining; remaining = 0 } else {
                            remaining -= left
                            s.timeout()
                            val before = key
                            afterChange()
                            if (key == before) break
                        }
                    }
                }
            }
            afterChange()
        }

        fun snapshot() = "i=${s.currentIndex} playing=${s.isPlaying} controls=${s.showControls} muted=${s.isMuted}"
    }

    @Test
    fun tap_cycleMatchesLegacy() = runTest(timeout = 5.minutes) {
        val prefs = Adapters.appPreferences(context)
        val repo = Adapters.mediaRepository(context, prefs)
        val tree = FakeDocumentsProvider.treeUri
        Adapters.applySortSettings(prefs, SortOrder.NAME_ASC, shuffle = false)

        checkAll(100, mixedFolder, Arb.int(0..7), Arb.list(event, 1..40)) { files, start, events ->
            FakeDocumentsProvider.files = files
            val items = Adapters.scanFresh(repo, tree)

            val probe = Adapters.reelsTapProbe(context, repo, prefs, tree, start)
            try {
                val oracle = LegacyOracles.ReelsTapMachine(items.map { it.isVideo }).apply { loadCached(start) }
                val prod = Screen(ProbeSubject(probe), items.size, Adapters.reelsAutoHideDelayMs)
                val ref = Screen(OracleSubject(oracle), items.size, LegacyOracles.REELS_AUTO_HIDE_MS)

                val trace = StringBuilder("kinds=${items.map { if (it.isVideo) 'V' else 'I' }} start=$start\n")
                trace.append("open → ${ref.snapshot()}\n")
                assertEquals("after open\n$trace", ref.snapshot(), prod.snapshot())
                for (e in events) {
                    prod.apply(e)
                    ref.apply(e)
                    trace.append("$e → ${ref.snapshot()}\n")
                    assertEquals("after $e\n$trace", ref.snapshot(), prod.snapshot())
                }
            } finally {
                probe.close()
            }
        }
    }

    /** The unfixed transitions, observed on the real ViewModel. */
    @Test
    fun tap_observedTransitions() = runTest {
        val prefs = Adapters.appPreferences(context)
        val repo = Adapters.mediaRepository(context, prefs)
        val tree = FakeDocumentsProvider.treeUri
        Adapters.applySortSettings(prefs, SortOrder.NAME_ASC, shuffle = false)
        FakeDocumentsProvider.files = listOf(
            FakeFile("doc0", "a.mp4", "video/mp4", 1, 1),
            FakeFile("doc1", "b.jpg", "image/jpeg", 1, 1),
        )
        Adapters.scanFresh(repo, tree)

        val p = Adapters.reelsTapProbe(context, repo, prefs, tree, 0)
        try {
            val s = Screen(ProbeSubject(p), 2, Adapters.reelsAutoHideDelayMs)
            fun state() = p.isPlaying to p.showControls
            val a = true to false
            val b = true to true
            val c = false to true

            assertEquals("open on a video → A", a, state())
            s.apply(Event.Tap); assertEquals("A tap → B", b, state())
            s.apply(Event.Elapse(2_999)); assertEquals("B holds for 2999 ms", b, state())
            s.apply(Event.Elapse(1)); assertEquals("B → A at 3000 ms", a, state())
            s.apply(Event.Tap); s.apply(Event.Tap); assertEquals("B tap → C", c, state())
            s.apply(Event.Elapse(10_000)); assertEquals("C never auto-hides", c, state())
            s.apply(Event.Tap); assertEquals("C tap → A", a, state())
            s.apply(Event.AppPause); assertEquals("ON_PAUSE on a video → C", c, state())
            s.apply(Event.Mute); assertEquals(true, p.isMuted)

            s.apply(Event.Swipe(1)); assertEquals("settle on an image", false to false, state())
            s.apply(Event.Tap); assertEquals("image tap shows controls", false to true, state())
            s.apply(Event.AppPause); assertEquals("ON_PAUSE on an image changes nothing", false to true, state())
            s.apply(Event.Tap); assertEquals("image tap hides controls", false to false, state())
            s.apply(Event.Swipe(-1)); assertEquals("settle on a video → A", a, state())
            assertEquals("mute survives swipes", true, p.isMuted)
            assertEquals(3_000L, Adapters.reelsAutoHideDelayMs)
        } finally {
            p.close()
        }
    }
}
