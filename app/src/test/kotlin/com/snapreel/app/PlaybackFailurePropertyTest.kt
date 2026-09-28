package com.snapreel.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.SortOrder
import com.snapreel.app.player.PageState
import com.snapreel.app.player.PlaybackErrorPolicy
import com.snapreel.app.player.PlaybackFailureKind
import com.snapreel.app.player.PlayerPool
import com.snapreel.app.player.ReelPlayerPool
import com.snapreel.app.testsupport.Adapters
import com.snapreel.app.testsupport.FakeDocumentsProvider
import com.snapreel.app.testsupport.FakeDocumentsProvider.FakeFile
import com.snapreel.app.testsupport.TestPlayers
import com.snapreel.app.ui.viewer.ReelsViewerViewModel
import com.snapreel.app.util.thumbnail.ThumbnailWorkGate
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper
import androidx.media3.test.utils.robolectric.RobolectricUtil
import kotlin.time.Duration.Companion.minutes

/**
 * Property 5: Bug Condition - Playback failures are surfaced and retried once.
 *
 * _For any_ Media3 error code raised by the current page's player, the viewer SHALL classify it
 * deterministically and retry automatically exactly once when it is transient. Otherwise, or when
 * the retry fails, it SHALL expose a page failure with a message and a retry action, with
 * `isPlaying = false`, while swiping keeps working. Errors on neighbor pages SHALL never surface
 * as UI errors.
 *
 * Three layers: the pure [PlaybackErrorPolicy]; the real [ReelPlayerPool] with scripted source
 * errors (fake media, Robolectric); and `ReelsViewerViewModel` driven by a fake [PlayerPool].
 *
 * **Validates: Requirements 2.6**
 */
@RunWith(AndroidJUnit4::class)
class PlaybackFailurePropertyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Adapters.resetProcessState()
        Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(FakeDocumentsProvider.AUTHORITY)
    }

    @After
    fun tearDown() {
        FakeDocumentsProvider.files = emptyList()
        Adapters.resetProcessState()
    }

    /** Every `PlaybackException.ERROR_CODE_*` constant, by reflection. */
    private val allErrorCodes: Map<String, Int> = PlaybackException::class.java.fields
        .filter { it.name.startsWith("ERROR_CODE_") && it.type == Int::class.javaPrimitiveType }
        .associate { it.name to it.getInt(null) }

    private val errorCode: Arb<Int> = Arb.choice(
        Arb.element(allErrorCodes.values.toList()),
        Arb.int(-200..8_000),
        Arb.int(PlaybackException.CUSTOM_ERROR_CODE_BASE..PlaybackException.CUSTOM_ERROR_CODE_BASE + 10),
    )

    // ─── Policy (pure) ──────────────────────────────────────────────────────────────────

    @Test
    fun classify_everyConstantHasKindAndTransientFlag() {
        assertTrue("found the ERROR_CODE_* constants", allErrorCodes.size >= 50)
        for ((name, code) in allErrorCodes) {
            val f = PlaybackErrorPolicy.classify(code)
            assertTrue("$name has a message", f.message.isNotBlank())
            val expected = when (name) {
                "ERROR_CODE_IO_FILE_NOT_FOUND" -> PlaybackFailureKind.FILE_MISSING to false
                "ERROR_CODE_IO_NO_PERMISSION" -> PlaybackFailureKind.ACCESS_LOST to false
                "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", "ERROR_CODE_PARSING_CONTAINER_MALFORMED",
                "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED", "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES",
                -> PlaybackFailureKind.UNSUPPORTED to false
                "ERROR_CODE_DECODER_INIT_FAILED", "ERROR_CODE_DECODER_QUERY_FAILED", "ERROR_CODE_DECODING_FAILED",
                "ERROR_CODE_DECODING_RESOURCES_RECLAIMED", "ERROR_CODE_AUDIO_TRACK_INIT_FAILED",
                "ERROR_CODE_AUDIO_TRACK_WRITE_FAILED", "ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED",
                "ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED",
                -> PlaybackFailureKind.DECODER_BUSY to true
                else -> PlaybackFailureKind.UNKNOWN to true
            }
            assertEquals(name, expected, f.kind to f.transient)
        }
        assertEquals("This video was moved or deleted", PlaybackErrorPolicy.classify(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND).message)
    }

    @Test
    fun classify_isDeterministic() = runTest {
        checkAll(1_000, errorCode) { code ->
            val a = PlaybackErrorPolicy.classify(code)
            assertEquals(a, PlaybackErrorPolicy.classify(code))
            assertTrue(a.message.isNotBlank())
            if (a.kind == PlaybackFailureKind.DECODER_BUSY || a.kind == PlaybackFailureKind.UNKNOWN) assertTrue(a.transient)
            else assertFalse(a.transient)
        }
    }

    // ─── Real pool with scripted source errors ──────────────────────────────────────────

    private fun uri(i: Int): Uri = Uri.parse("content://com.snapreel.test/item/$i")

    private fun videos(n: Int): List<MediaItem> = (0 until n).map { i ->
        MediaItem(uri(i), "v$i.mp4", "video/mp4", i.toLong(), i.toLong(), isVideo = true)
    }

    private fun Player.settled() = playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED

    @Test
    fun pool_currentRetriedOnceNeighborsSilent() = runTest(timeout = 5.minutes) {
        checkAll(
            60,
            Arb.int(1..4),
            Arb.int(0..3),
            Arb.list(errorCode, 0..2), // current page: the first attempts' codes
            Arb.list(errorCode, 2..2), // next / previous: one code each…
            Arb.list(Arb.boolean(), 2..2), // …used only when its flag is set
        ) { n, rawStart, currentCodes, rawNeighborCodes, neighborFails ->
            val neighborCodes: List<Int?> = rawNeighborCodes.mapIndexed { i, c -> c.takeIf { neighborFails[i] } }
            val items = videos(n)
            val start = rawStart.coerceAtMost(n - 1)
            val current = items[start].uri
            val neighbors = listOfNotNull(items.getOrNull(start + 1)?.uri, items.getOrNull(start - 1)?.uri)
            val failures = TestPlayers.ScriptedFailures()
            failures.script(current, currentCodes)
            neighbors.forEachIndexed { i, u -> failures.script(u, listOfNotNull(neighborCodes[i])) }

            val pool = ReelPlayerPool(context, playerFactory = { TestPlayers.scripted(context, failures) })
            val ctx = "n=$n start=$start current=$currentCodes neighbors=${neighborCodes.take(neighbors.size)}"
            try {
                pool.setWindow(items, start, playCurrent = true)

                // Classification decides the outcome for the current page.
                val first = currentCodes.firstOrNull()?.let(PlaybackErrorPolicy::classify)
                val retried = first != null && first.transient
                val finalCode = when {
                    first == null -> null
                    retried -> currentCodes.getOrNull(1)
                    else -> currentCodes[0]
                }
                val expectedFailure = finalCode?.let(PlaybackErrorPolicy::classify)
                val busyTrim = retried && first!!.kind == PlaybackFailureKind.DECODER_BUSY

                RobolectricUtil.runMainLooperUntil {
                    val pages = pool.pages.value
                    val cur = pages[current]
                    val currentDone = cur?.failure != null || cur?.player?.settled() == true
                    // Neighbors: either dropped, or prepared and ready (the warm-up delay has passed).
                    val neighborsDone = neighbors.all { u -> pages[u]?.player?.settled() ?: true } &&
                        neighbors.all { u -> failures.remaining(u) == 0 || u !in pages && busyTrim }
                    currentDone && neighborsDone
                }

                val pages = pool.pages.value
                val cur = pages.getValue(current)
                assertEquals("current page failure: $ctx", expectedFailure, cur.failure)
                assertEquals(
                    "current prepared once, plus exactly one automatic retry when transient: $ctx",
                    if (retried) 2 else 1, failures.prepares(current),
                )
                if (expectedFailure == null) assertTrue("current plays after success: $ctx", cur.player!!.settled())

                for ((i, u) in neighbors.withIndex()) {
                    assertNull("neighbor errors never surface: $ctx", pages[u]?.failure)
                    val code = neighborCodes[i]
                    when {
                        busyTrim -> assertFalse("DECODER_BUSY on the current page trims neighbors: $ctx", u in pages)
                        code != null -> assertFalse("a failed neighbor is unbound silently: $ctx", u in pages)
                        else -> assertTrue("a healthy neighbor stays bound: $ctx", u in pages)
                    }
                    assertTrue("neighbors never play: $ctx", pages[u]?.player?.playWhenReady != true)
                }
                val busyNeighbors = neighbors.indices.count { i ->
                    !busyTrim && neighborCodes[i]?.let(PlaybackErrorPolicy::classify)?.kind == PlaybackFailureKind.DECODER_BUSY &&
                        failures.prepares(neighbors[i]) > 0
                }
                if (!busyTrim) {
                    assertEquals("capacity shrinks once per busy neighbor: $ctx", maxOf(1, 3 - busyNeighbors), pool.capacity)
                }

                // Manual retry of a failed current page prepares it again and clears the failure.
                if (expectedFailure != null) {
                    val attempts = failures.prepares(current)
                    pool.retry(current)
                    assertNull("retry clears the failure: $ctx", pool.pages.value.getValue(current).failure)
                    RobolectricUtil.runMainLooperUntil {
                        val page = pool.pages.value.getValue(current)
                        failures.prepares(current) > attempts && (page.failure != null || page.player!!.settled())
                    }
                    // The one automatic retry per binding is still available if it wasn't used yet.
                    val manualCode = currentCodes.getOrNull(attempts)
                    val autoAfterManual = !retried && manualCode != null && PlaybackErrorPolicy.classify(manualCode).transient
                    RobolectricUtil.runMainLooperUntil {
                        val page = pool.pages.value.getValue(current)
                        failures.prepares(current) >= attempts + 1 + (if (autoAfterManual) 1 else 0) &&
                            (page.failure != null || page.player!!.settled())
                    }
                    assertEquals(
                        "a manual retry is one more attempt (plus the unused automatic one): $ctx",
                        attempts + 1 + (if (autoAfterManual) 1 else 0), failures.prepares(current),
                    )
                }

                // Swiping keeps working after any failure.
                if (n > 1) {
                    val target = if (start + 1 < n) start + 1 else start - 1
                    pool.setWindow(items, target, playCurrent = true)
                    assertNotNull("the next page binds a player: $ctx", pool.pages.value[items[target].uri]?.player)
                    assertTrue(pool.pages.value.getValue(items[target].uri).player!!.playWhenReady)
                }
            } finally {
                pool.release()
            }
        }
    }

    // ─── ViewModel over a fake pool ─────────────────────────────────────────────────────

    private class FakePool : PlayerPool {
        val state = MutableStateFlow<Map<Uri, PageState>>(emptyMap())
        override val pages: StateFlow<Map<Uri, PageState>> = state
        var current: Uri? = null
        var playing = false
        val retries = mutableListOf<Uri>()

        override fun setWindow(items: List<MediaItem>, current: Int, playCurrent: Boolean) {
            this.current = items.getOrNull(current)?.takeIf { it.isVideo }?.uri
            playing = playCurrent
            // The real pool re-prepares a failed page when it becomes current: a fresh page state.
            this.current?.let { u -> state.value = state.value + (u to PageState(null, null, false, null)) }
        }
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(ms: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setLoop(loop: Boolean) = Unit
        override fun positionMs(): Long = 0
        override fun durationMs(): Long = 0
        override fun retry(uri: Uri) {
            retries += uri
            state.value = state.value + (uri to PageState(null, null, false, null))
        }
        override fun trimToCurrent() = Unit
        override fun restoreNeighbors() = Unit
        override fun release() { state.value = emptyMap() }

        fun fail(uri: Uri, code: Int) {
            state.value = state.value + (uri to PageState(null, null, false, PlaybackErrorPolicy.classify(code)))
        }
    }

    sealed interface Event {
        data class FailCurrent(val code: Int) : Event
        data class FailNeighbor(val delta: Int, val code: Int) : Event
        data object Tap : Event
        data object Retry : Event
        data class Swipe(val delta: Int) : Event
    }

    @Test
    fun viewModel_failureStopsPlaybackAndSwipingWorks() = runTest(timeout = 5.minutes) {
        val prefs = Adapters.appPreferences(context)
        val repo = Adapters.mediaRepository(context, prefs)
        val tree = FakeDocumentsProvider.treeUri
        Adapters.applySortSettings(prefs, SortOrder.NAME_ASC, shuffle = false)

        val event: Arb<Event> = Arb.choice(
            errorCode.map { Event.FailCurrent(it) },
            Arb.bind(Arb.element(-1, 1), errorCode) { d, c -> Event.FailNeighbor(d, c) },
            Arb.constant(Event.Tap),
            Arb.constant(Event.Retry),
            Arb.element(-1, 1).map { Event.Swipe(it) },
        )

        checkAll(100, Arb.list(Arb.boolean(), 1..6), Arb.int(0..5), Arb.list(event, 1..20)) { kinds, rawStart, events ->
            FakeDocumentsProvider.files = kinds.mapIndexed { i, video ->
                FakeFile("doc$i", "f$i.${if (video) "mp4" else "jpg"}", if (video) "video/mp4" else "image/jpeg", 1, 1)
            }
            val items = Adapters.scanFresh(repo, tree)
            val start = rawStart.coerceAtMost(items.lastIndex)

            val pool = FakePool()
            val store = ViewModelStore()
            val vm = ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        ReelsViewerViewModel(
                            repo, prefs,
                            object : ReelPlayerPool.Factory(context) { override fun create(): PlayerPool = pool },
                            ThumbnailWorkGate(),
                        ) as T
                },
            )[ReelsViewerViewModel::class.java]
            try {
                vm.loadMedia(tree, start)
                val trace = StringBuilder("kinds=${items.map { if (it.isVideo) 'V' else 'I' }} start=$start\n")
                for (e in events) {
                    val s = vm.uiState.value
                    val cur = s.mediaItems[s.currentIndex]
                    when (e) {
                        is Event.FailCurrent -> if (cur.isVideo) pool.fail(cur.uri, e.code)
                        is Event.FailNeighbor -> items.getOrNull(s.currentIndex + e.delta)
                            ?.takeIf { it.isVideo }?.let { pool.fail(it.uri, e.code) }
                        Event.Tap -> if (cur.isVideo) vm.onVideoTap() else vm.onImageTap()
                        Event.Retry -> vm.retry()
                        is Event.Swipe -> (s.currentIndex + e.delta).takeIf { it in items.indices }?.let { vm.onPageSettled(it) }
                    }
                    ShadowLooper.idleMainLooper()
                    val after = vm.uiState.value
                    val now = after.mediaItems[after.currentIndex]
                    val failure = if (now.isVideo) vm.pages.value[now.uri]?.failure else null
                    trace.append("$e → i=${after.currentIndex} playing=${after.isPlaying} failure=${failure?.kind}\n")
                    val ctx = trace.toString()

                    if (e is Event.Swipe) {
                        val target = s.currentIndex + e.delta
                        if (target in items.indices) assertEquals("swiping keeps working: $ctx", target, after.currentIndex)
                    }
                    if (failure != null) {
                        assertFalse("a current-page failure means isPlaying = false: $ctx", after.isPlaying)
                        assertTrue("the failure has a message: $ctx", failure.message.isNotBlank())
                    }
                    if (e is Event.FailNeighbor && failure == null && s.isPlaying) {
                        assertTrue("a neighbor failure never changes the current page: $ctx", after.isPlaying)
                    }
                    if (e is Event.Retry && s.mediaItems[s.currentIndex].isVideo) {
                        assertEquals("Retry targets the current page: $ctx", s.mediaItems[s.currentIndex].uri, pool.retries.last())
                        assertTrue("Retry returns to State A: $ctx", after.isPlaying && !after.showControls)
                    }
                    if (now.isVideo && failure == null && e is Event.Swipe && s.currentIndex != after.currentIndex) {
                        assertTrue("settling on a healthy video plays it (State A): $ctx", after.isPlaying && pool.playing)
                    }
                }
            } finally {
                store.clear()
            }
        }
    }
}
