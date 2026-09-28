package com.snapreel.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.player.ReelPlayerPool
import com.snapreel.app.player.SlotPlanner
import com.snapreel.app.testsupport.TestPlayers
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/**
 * Property 4: Bug Condition - Bounded player pool with prepared neighbors.
 *
 * _For any_ item list (random mix of videos and images), start index, and sequence of swipes,
 * stops/starts and a final close, the pool SHALL:
 * - bind at most min(3, capacity) players;
 * - always bind the current page when it is a video;
 * - bind only the current page and its immediate video neighbors;
 * - keep existing bindings for pages that stay in the window (no re-prepare);
 * - keep every non-current player paused;
 * - create no player after `release()`;
 * - hold zero players after the viewer closes.
 *
 * **Validates: Requirements 2.7, 2.8, 2.9, 2.10**
 */
@RunWith(AndroidJUnit4::class)
class PlayerPoolPropertyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun uri(i: Int): Uri = Uri.parse("content://com.snapreel.test/item/$i")

    private fun itemsOf(kinds: List<Boolean>): List<MediaItem> = kinds.mapIndexed { i, video ->
        MediaItem(
            uri = uri(i),
            name = if (video) "v$i.mp4" else "i$i.jpg",
            mimeType = if (video) "video/mp4" else "image/jpeg",
            size = i.toLong(),
            dateModified = i.toLong(),
            isVideo = video,
        )
    }

    private val kinds: Arb<List<Boolean>> = Arb.list(Arb.boolean(), 1..9)

    /** The pages the pool may bind around [current]: the current page and its immediate video neighbors. */
    private fun window(items: List<MediaItem>, current: Int): Set<Uri> =
        listOf(current - 1, current, current + 1).mapNotNull { items.getOrNull(it)?.takeIf { v -> v.isVideo }?.uri }.toSet()

    // ─── SlotPlanner (pure) ─────────────────────────────────────────────────────────────

    @Test
    fun planner_invariants() = runTest {
        checkAll(
            1_000,
            kinds,
            Arb.int(0..8),
            Arb.int(1..3),
            // Per slot: -5..-3 = free, -2..-1 and past the end = foreign URIs, else an item's URI.
            Arb.list(Arb.int(-5..10), 3..3),
        ) { k, rawCurrent, capacity, boundIdx ->
            val items = itemsOf(k)
            val current = rawCurrent.coerceAtMost(items.lastIndex)
            // Slots bound to items in the list (-2..-1 and past the end are foreign URIs), no duplicates.
            val bound: List<Uri?> = boundIdx.map { i -> if (i <= -3) null else uri(i) }.let { raw ->
                val seen = HashSet<Uri>()
                raw.map { u -> u?.takeIf { seen.add(it) } }
            }

            val plan = SlotPlanner.plan(items, current, capacity, bound)
            val wanted = SlotPlanner.wanted(items, current, capacity)
            val ctx = "items=${k.map { if (it) 'V' else 'I' }} current=$current capacity=$capacity bound=$bound plan=$plan"

            assertEquals("one entry per slot: $ctx", bound.size, plan.size)
            val boundNow = plan.filterNotNull()
            assertEquals("no page in two slots: $ctx", boundNow.size, boundNow.toSet().size)
            assertTrue("at most min(3, capacity) bound: $ctx", boundNow.size <= minOf(3, capacity))
            assertEquals("exactly the wanted pages are bound: $ctx", wanted.toSet(), boundNow.toSet())
            if (items[current].isVideo) assertTrue("current video bound: $ctx", items[current].uri in boundNow)
            assertTrue("only current and immediate video neighbors: $ctx", window(items, current).containsAll(boundNow))
            bound.forEachIndexed { i, u ->
                if (u != null && u in wanted) assertEquals("slot $i keeps its wanted page: $ctx", u, plan[i])
            }
            // Priority: current first, then next, then previous.
            val order = listOf(current, current + 1, current - 1)
                .mapNotNull { items.getOrNull(it)?.takeIf { v -> v.isVideo }?.uri }
            assertEquals("priority order: $ctx", order.take(capacity), wanted)
        }
    }

    // ─── Real pool (Robolectric, fake media) ────────────────────────────────────────────

    sealed interface Op {
        data class Swipe(val delta: Int) : Op
        data object Resettle : Op
        data object Stop : Op
        data object Start : Op
        data object Mute : Op
        data object Loop : Op
        data class Idle(val ms: Long) : Op
    }

    private val op: Arb<Op> = Arb.choice(
        Arb.element(-1, 1).map { Op.Swipe(it) },
        Arb.element(-1, 1).map { Op.Swipe(it) },
        Arb.constant(Op.Resettle),
        Arb.constant(Op.Stop),
        Arb.constant(Op.Start),
        Arb.constant(Op.Mute),
        Arb.constant(Op.Loop),
        Arb.long(0L..500L).map { Op.Idle(it) },
    )

    private class Snapshot(val players: Map<Uri, ExoPlayer>, val playlistChanges: Map<Uri, Int>)

    private fun snapshot(pool: ReelPlayerPool, factory: TestPlayers.CountingFactory): Snapshot {
        val players = pool.pages.value.mapValues { it.value.player as ExoPlayer }
        return Snapshot(players, players.mapValues { factory.playlistChanges.getValue(it.value).get() })
    }

    @Test
    fun pool_boundedWindowAndRelease() = runTest(timeout = 5.minutes) {
        checkAll(60, kinds, Arb.int(0..8), Arb.list(op, 1..25)) { k, rawStart, ops ->
            val items = itemsOf(k)
            val factory = TestPlayers.CountingFactory { TestPlayers.nonFailing(context) }
            val pool = ReelPlayerPool(context, playerFactory = factory)
            var current = rawStart.coerceAtMost(items.lastIndex)
            var trimmed = false
            var muted = false
            var loop = true
            val trace = StringBuilder("items=${k.map { if (it) 'V' else 'I' }} start=$current\n")

            fun check(before: Snapshot?, step: String) {
                val ctx = "after $step\n$trace"
                val bound = pool.boundUris
                val pages = pool.pages.value
                assertEquals("pages lists exactly the bound pages: $ctx", bound.toSet(), pages.keys)
                assertTrue("at most min(3, capacity) bound: $ctx", bound.size <= minOf(3, pool.capacity))
                assertTrue("at most 3 players held: $ctx", pool.heldPlayerCount <= 3)
                val currentUri = items[current].takeIf { it.isVideo }?.uri
                if (currentUri != null) assertTrue("current video bound: $ctx", currentUri in bound)
                assertTrue("only current and immediate video neighbors: $ctx", window(items, current).containsAll(bound))
                val expected = if (trimmed) setOfNotNull(currentUri) else SlotPlanner.wanted(items, current, pool.capacity).toSet()
                assertEquals("bound pages: $ctx", expected, bound.toSet())

                if (before != null) {
                    for ((u, player) in before.players) {
                        if (u !in pages) continue
                        assertSame("page $u kept its player: $ctx", player, pages.getValue(u).player)
                        assertEquals(
                            "page $u was not re-prepared: $ctx",
                            before.playlistChanges.getValue(u), factory.playlistChanges.getValue(player).get(),
                        )
                    }
                }

                val currentPlayer = currentUri?.let { pages[it]?.player }
                for (p in factory.created) {
                    if (p.isReleased || p === currentPlayer) continue
                    assertFalse("non-current player is paused: $ctx", p.playWhenReady)
                }
                for (p in factory.created.filter { !it.isReleased }) {
                    assertEquals("mute applies to every player: $ctx", if (muted) 0f else 1f, p.volume)
                    assertEquals(
                        "loop applies to every player: $ctx",
                        if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF, p.repeatMode,
                    )
                }
            }

            try {
                pool.setWindow(items, current, playCurrent = items[current].isVideo)
                check(null, "open")
                for (o in ops) {
                    val before = snapshot(pool, factory)
                    when (o) {
                        is Op.Swipe -> {
                            val target = current + o.delta
                            if (target in items.indices) {
                                current = target
                                pool.setWindow(items, current, playCurrent = items[current].isVideo)
                            }
                        }
                        Op.Resettle -> pool.setWindow(items, current, playCurrent = items[current].isVideo)
                        Op.Stop -> { pool.trimToCurrent(); trimmed = true }
                        Op.Start -> { pool.restoreNeighbors(); trimmed = false }
                        Op.Mute -> { muted = !muted; pool.setMuted(muted) }
                        Op.Loop -> { loop = !loop; pool.setLoop(loop) }
                        is Op.Idle -> ShadowLooper.idleMainLooper(o.ms, TimeUnit.MILLISECONDS)
                    }
                    trace.append("$o → current=$current bound=${pool.boundUris.map { it.lastPathSegment }}\n")
                    check(before, o.toString())
                }

                // Close: every player released, none created afterwards, nothing held.
                val createdBeforeRelease = factory.count
                pool.release()
                pool.release() // idempotent
                pool.setWindow(items, current, playCurrent = true)
                pool.play(); pool.pause(); pool.seekBy(1_000); pool.seekTo(0); pool.retry(items[current].uri)
                pool.trimToCurrent(); pool.restoreNeighbors(); pool.setMuted(true); pool.setLoop(false)
                ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
                assertEquals("no player created after release()\n$trace", createdBeforeRelease, factory.count)
                assertEquals("zero players held after close\n$trace", 0, factory.held)
                assertEquals(0, pool.heldPlayerCount)
                assertTrue("pages empty after release\n$trace", pool.pages.value.isEmpty())
            } finally {
                pool.release()
            }
        }
    }

    /**
     * Regression (found by `pool_currentRetriedOnceNeighborsSilent`, seed 378875236648939998): an
     * excluded (failed) neighbor must free its place for the next candidate, not just leave a
     * slot empty while a healthy neighbor stays unbound.
     */
    @Test
    fun planner_excludedNeighborGivesUpItsPlace() {
        val items = itemsOf(listOf(true, true, true))
        val (previous, current, next) = items.map { it.uri }
        // A full pool with capacity 2 wants the current page and the next one.
        assertEquals(listOf(current, next), SlotPlanner.wanted(items, 1, 2))
        // The next page failed: the previous page takes its place.
        assertEquals(listOf(current, previous), SlotPlanner.wanted(items, 1, 2, exclude = setOf(next)))
        assertEquals(
            listOf(current, previous, null),
            SlotPlanner.plan(items, 1, 2, listOf(current, null, null), exclude = setOf(next)),
        )
        // The current page is never excluded.
        assertEquals(listOf(current), SlotPlanner.wanted(items, 1, 1, exclude = setOf(current)))
    }

    /** Neighbors are bound right away but only prepare once the warm-up delay passes (no first frame here). */
    @Test
    fun pool_neighborsPrepareAfterWarmUp() {
        val items = itemsOf(listOf(true, true, true))
        val factory = TestPlayers.CountingFactory { TestPlayers.nonFailing(context) }
        val pool = ReelPlayerPool(context, playerFactory = factory)
        try {
            pool.setWindow(items, 1, playCurrent = true)
            val pages = pool.pages.value
            val current = pages.getValue(items[1].uri).player!!
            val next = pages.getValue(items[2].uri).player!!
            val previous = pages.getValue(items[0].uri).player!!
            assertNotEquals("current prepares at once", Player.STATE_IDLE, current.playbackState)
            assertTrue("current plays", current.playWhenReady)
            assertEquals("next waits for the current page", Player.STATE_IDLE, next.playbackState)
            assertEquals("previous waits for the current page", Player.STATE_IDLE, previous.playbackState)

            ShadowLooper.idleMainLooper(ReelPlayerPool.WARM_UP_DELAY_MS, TimeUnit.MILLISECONDS)
            assertNotEquals("next prepared after the warm-up delay", Player.STATE_IDLE, next.playbackState)
            assertNotEquals("previous prepared after the warm-up delay", Player.STATE_IDLE, previous.playbackState)
            assertFalse("next stays paused", next.playWhenReady)
            assertFalse("previous stays paused", previous.playWhenReady)

            // Swipe to the next page: its prepared player becomes current and plays; the old one pauses at 0.
            pool.setWindow(items, 2, playCurrent = true)
            assertSame(next, pool.pages.value.getValue(items[2].uri).player)
            assertTrue(next.playWhenReady)
            assertFalse(current.playWhenReady)
            assertEquals(0L, current.currentPosition)
            assertFalse("previous page left the window", items[0].uri in pool.pages.value)
        } finally {
            pool.release()
        }
        assertEquals(0, factory.held)
    }
}
