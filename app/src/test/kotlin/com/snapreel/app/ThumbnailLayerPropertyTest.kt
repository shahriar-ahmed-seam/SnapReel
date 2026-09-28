package com.snapreel.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Size
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.testsupport.FakeDocumentsProvider
import com.snapreel.app.util.thumbnail.THUMB_PX
import com.snapreel.app.util.thumbnail.ThumbnailDiskCache
import com.snapreel.app.util.thumbnail.ThumbnailGenerationException
import com.snapreel.app.util.thumbnail.ThumbnailGenerator
import com.snapreel.app.util.thumbnail.ThumbnailNotCached
import com.snapreel.app.util.thumbnail.ThumbnailScheduler
import com.snapreel.app.util.thumbnail.ThumbnailWorkGate
import com.snapreel.app.util.thumbnail.VideoThumbnail
import com.snapreel.app.util.thumbnail.VideoThumbnailFetcher
import com.snapreel.app.util.thumbnail.VideoThumbnailKeyer
import com.snapreel.app.util.thumbnail.videoThumbnailRequest
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.minutes

/**
 * Property 3: Bug Condition - Thumbnail keying, bounds and scheduling.
 *
 * For generated items (uri, size, dateModified), random cancellations, generator failures and
 * viewer open/close events, the thumbnail layer:
 * - maps equal (uri, size, dateModified) to one key, and any change in size or dateModified to a new key;
 * - generates a key at most once while its entry stays cached;
 * - keeps disk bytes ≤ the configured bound after every write, evicting least-recently-read first;
 * - never runs more than 2 generations concurrently;
 * - never starts a generation for a request cancelled while queued, or while a viewer is open;
 * - never generates for a cache-only request;
 * - attempts a failing key at most 3 times per process.
 *
 * The generator is a fake (call counter, latency, failures). The disk cache is a real
 * [ThumbnailDiskCache] in a temp dir, with Coil's background trim made synchronous so the bound
 * can be checked right after each write. The scheduler uses its production workers
 * (`Dispatchers.IO.limitedParallelism(2)`), so concurrency is real.
 *
 * _Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.10_
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailLayerPropertyTest {

    private lateinit var context: Context
    private lateinit var root: File
    private var cacheCounter = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "p3-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ─── Test doubles ───────────────────────────────────────────────────────────────────

    /**
     * Runs Coil's post-write cleanup (trim to size) inline. Coil wraps the cleanup dispatcher in
     * `limitedParallelism(1)`, which `Dispatchers.Unconfined` rejects, hence a custom dispatcher.
     */
    private object ImmediateCleanup : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        override fun limitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher = this
    }

    enum class Mode { OK, PERMANENT, RETRYABLE, RUNTIME, FLAKY_1, FLAKY_2 }

    /** Fake generator: counts calls per key and overall, tracks concurrency, fails per [modes]. */
    private class FakeGenerator(
        private val modes: Map<String, Mode> = emptyMap(),
        private val latencyMs: Long = 0,
    ) : ThumbnailGenerator {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val total = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val blockers = ConcurrentHashMap<String, CountDownLatch>()

        fun callsFor(key: String): Int = calls[key]?.get() ?: 0

        override fun generate(thumbnail: VideoThumbnail): Bitmap {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, now) }
            try {
                val n = calls.computeIfAbsent(thumbnail.key) { AtomicInteger() }.incrementAndGet()
                total.incrementAndGet()
                blockers[thumbnail.key]?.await(10, TimeUnit.SECONDS)
                if (latencyMs > 0) Thread.sleep(latencyMs)
                return when (modes[thumbnail.key] ?: Mode.OK) {
                    Mode.OK -> solidBitmap(16, 16, Color.RED)
                    Mode.PERMANENT -> throw ThumbnailGenerationException("gone", permanent = true)
                    Mode.RETRYABLE -> throw ThumbnailGenerationException("no frame", permanent = false)
                    Mode.RUNTIME -> throw IllegalStateException("decoder died")
                    Mode.FLAKY_1 -> if (n <= 1) throw ThumbnailGenerationException("flaky", false) else solidBitmap(16, 16, Color.BLUE)
                    Mode.FLAKY_2 -> if (n <= 2) throw ThumbnailGenerationException("flaky", false) else solidBitmap(16, 16, Color.GREEN)
                }
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private fun newCache(maxBytes: Long = 64L * 1024 * 1024) =
        ThumbnailDiskCache(File(root, "cache${cacheCounter++}"), maxBytes, ImmediateCleanup)

    private fun thumbnail(name: String, size: Long = 1_000L, dateModified: Long = 1_700_000_000_000L, flag: Boolean = false) =
        VideoThumbnail(FakeDocumentsProvider.documentUri("DCIM/$name.mp4"), size, dateModified, flag)

    private fun item(t: VideoThumbnail) =
        MediaItem(t.uri, "clip.mp4", "video/mp4", t.size, t.dateModified, isVideo = true, supportsThumbnail = t.supportsThumbnail)

    /** A fetcher built exactly like Coil builds it for the app's request (same data and extras). */
    private fun fetcher(
        t: VideoThumbnail,
        cacheOnly: Boolean,
        cache: ThumbnailDiskCache,
        scheduler: ThumbnailScheduler,
    ): VideoThumbnailFetcher {
        val request = videoThumbnailRequest(context, item(t), cacheOnly = cacheOnly, crossfade = false)
        val options = Options(context = context, size = Size(THUMB_PX, THUMB_PX), extras = request.extras)
        return VideoThumbnailFetcher(request.data as VideoThumbnail, options, cache, scheduler, backoffMs = listOf(1L, 3L))
    }

    private fun assertDisk(result: Result<FetchResult>, what: String) {
        val r = result.getOrElse { throw AssertionError("$what failed: $it", it) }
        assertEquals("$what: data source", DataSource.DISK, (r as ImageFetchResult).dataSource)
    }

    /** Polls [condition] in real time. */
    private suspend fun awaitReal(timeoutMs: Long = 5_000, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            delay(1)
        }
    }

    private suspend fun <T> Deferred<T>.outcome(): Result<T> =
        try {
            Result.success(await())
        } catch (e: Throwable) {
            Result.failure(e)
        }

    // ─── Keys ───────────────────────────────────────────────────────────────────────────

    /** Equal (uri, size, dateModified) share one key; a change in any of them gives a new key. */
    @Test
    fun keys_trackUriSizeAndDateModified() = runTest(timeout = 5.minutes) {
        val keyer = VideoThumbnailKeyer()
        val options = Options(context = context, size = Size(THUMB_PX, THUMB_PX))
        checkAll(
            1_000,
            Arb.stringPattern("[a-zA-Z0-9_ %.-]{1,24}"),
            Arb.long(0L..50_000_000_000L),
            Arb.long(0L..4_000_000_000_000L),
            Arb.long(1L..1_000_000L),
            Arb.long(1L..1_000_000L),
            Arb.boolean(),
            Arb.boolean(),
        ) { name, size, dateModified, sizeDelta, dateDelta, flag, cacheOnly ->
            val t = thumbnail(name, size, dateModified, flag)
            val key = t.key

            assertEquals("same file version, same key", key, thumbnail(name, size, dateModified, !flag).key)
            assertEquals("keyer agrees with the data key", key, keyer.key(t, options))
            assertNotEquals("size change must change the key", key, t.copy(size = size + sizeDelta).key)
            assertNotEquals("dateModified change must change the key", key, t.copy(dateModified = dateModified + dateDelta).key)
            assertNotEquals("another file must have another key", key, thumbnail("${name}x", size, dateModified).key)

            // Grid and viewers build requests only through the helper: same data, key and size.
            val request = videoThumbnailRequest(context, item(t), cacheOnly = cacheOnly, crossfade = !cacheOnly)
            assertEquals(t, request.data)
            assertEquals(key, request.memoryCacheKey)
            val other = videoThumbnailRequest(context, item(t), cacheOnly = !cacheOnly, crossfade = cacheOnly)
            assertEquals("grid and viewer requests share the memory key", request.memoryCacheKey, other.memoryCacheKey)
        }
    }

    // ─── Disk bound and LRU ─────────────────────────────────────────────────────────────

    private sealed interface DiskOp {
        data class Write(val key: Int, val side: Int, val color: Int) : DiskOp
        data class Read(val key: Int) : DiskOp
    }

    private val diskOp: Arb<DiskOp> = Arb.choice(
        Arb.bind(Arb.int(0..11), Arb.int(4..48), Arb.int()) { k, s, c -> DiskOp.Write(k, s, c or 0xFF000000.toInt()) },
        Arb.int(0..11).map { DiskOp.Read(it) },
    )

    private fun jpegSize(bitmap: Bitmap): Long =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, ThumbnailDiskCache.JPEG_QUALITY, it) }
            .size().toLong()

    /**
     * After every write the cache holds ≤ maxBytes, and its contents equal an LRU model: the
     * longest run of most-recently-read/written entries that fits.
     */
    @Test
    fun disk_boundedAndEvictsLeastRecentlyReadFirst() = runTest(timeout = 5.minutes) {
        checkAll(60, Arb.long(1_500L..12_000L), Arb.list(diskOp, 1..60)) { maxBytes, ops ->
            val cache = newCache(maxBytes)
            val keys = (0..11).map { "vt1|content://t/doc/$it|$it|0" }
            val lru = LinkedHashMap<String, Long>() // insertion order = recency, oldest first
            try {
                for (op in ops) {
                    when (op) {
                        is DiskOp.Write -> {
                            val bitmap = solidBitmap(op.side, op.side, op.color)
                            val key = keys[op.key]
                            assertTrue("write $op", cache.write(key, bitmap))
                            lru.remove(key)
                            lru[key] = jpegSize(bitmap)
                            while (lru.values.sum() > maxBytes) lru.remove(lru.keys.first())

                            assertTrue("size ${cache.size} > bound $maxBytes after $op", cache.size <= maxBytes)
                            assertEquals("size after $op", lru.values.sum(), cache.size)
                        }
                        is DiskOp.Read -> {
                            val key = keys[op.key]
                            val bitmap = cache.read(key)
                            assertEquals("read hit after $op", key in lru, bitmap != null)
                            lru.remove(key)?.let { lru[key] = it }
                        }
                    }
                    for (key in keys) {
                        assertEquals("presence of $key after $op (LRU model ${lru.keys})", key in lru, cache.contains(key))
                    }
                }
            } finally {
                cache.shutdown()
            }
        }
    }

    /** A corrupt entry reads as a miss and is removed, so it gets regenerated. */
    @Test
    fun disk_corruptEntryIsRemoved() {
        val cache = newCache()
        val key = thumbnail("corrupt").key
        assertTrue(cache.write(key, solidBitmap(16, 16, Color.RED)))
        assertNotNull(cache.read(key))
        val dataFile = File(root, "cache0").listFiles()!!.single { it.name.endsWith(".1") }
        dataFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertNull(cache.read(key))
        assertFalse(cache.contains(key))
        cache.shutdown()
    }

    // ─── Scheduling: random schedules ───────────────────────────────────────────────────

    private sealed interface Op {
        data class Request(val key: Int, val cacheOnly: Boolean, val cancelAfterMs: Int?) : Op
        data class Pause(val ms: Int) : Op
        data class Viewer(val span: Int) : Op
    }

    private val schedOp: Arb<Op> = Arb.choice(
        Arb.bind(
            Arb.int(0..7),
            Arb.int(0..3),
            Arb.choice(Arb.int(-1..-1), Arb.int(0..0), Arb.int(1..15)),
        ) { k, c, cancel -> Op.Request(k, cacheOnly = c == 0, cancelAfterMs = cancel.takeIf { it >= 0 }) },
        Arb.int(0..6).map { Op.Pause(it) },
        Arb.int(1..6).map { Op.Viewer(it) },
    )

    private data class Issued(val op: Op.Request, val deferred: Deferred<FetchResult>)

    /**
     * Random interleavings of grid requests (some cache-only, some cancelled), generator failures
     * and viewer open/close events. Checks concurrency, the gate, cache-only, at-most-once and the
     * attempt bound.
     */
    @Test
    fun schedule_randomInterleavingsKeepEveryBound() = runTest(timeout = 5.minutes) {
        checkAll(60, Arb.list(Arb.enum<Mode>(), 8..8), Arb.list(schedOp, 1..40), Arb.long(0L..3L)) { modes, ops, latency ->
            val things = (0..7).map { thumbnail("clip$it") }
            val gen = FakeGenerator(things.indices.associate { things[it].key to modes[it] }, latency)
            val gate = ThumbnailWorkGate()
            val cache = newCache()
            val scheduler = ThumbnailScheduler(cache, gen, gate)
            val issued = mutableListOf<Issued>()

            withContext(Dispatchers.Default) {
                supervisorScope {
                    var viewerLeft = 0
                    var callsAtOpen = 0

                    fun closeViewer() {
                        assertEquals("generation started while a viewer was open", callsAtOpen, gen.total.get())
                        assertEquals("generation running while a viewer was open", 0, gate.runningGenerations)
                        gate.viewerClosed()
                    }

                    for (op in ops) {
                        when (op) {
                            is Op.Request -> {
                                val f = fetcher(things[op.key], op.cacheOnly, cache, scheduler)
                                val d = async { f.fetch() }
                                when (op.cancelAfterMs) {
                                    null -> Unit
                                    0 -> d.cancel()
                                    else -> launch { delay(op.cancelAfterMs.toLong()); d.cancel() }
                                }
                                issued += Issued(op, d)
                            }
                            is Op.Pause -> delay(op.ms.toLong())
                            is Op.Viewer -> if (viewerLeft == 0) {
                                gate.viewerOpened()
                                // Generations that started before the viewer opened may finish.
                                awaitReal(what = "in-flight generations to finish") { gate.runningGenerations == 0 }
                                callsAtOpen = gen.total.get()
                                viewerLeft = op.span + 1
                            }
                        }
                        if (viewerLeft > 0 && --viewerLeft == 0) closeViewer()
                    }
                    if (viewerLeft > 0) closeViewer()

                    withTimeout(30_000) { issued.forEach { it.deferred.join() } }
                }
            }

            assertTrue("max concurrent generations ${gen.maxActive.get()} > 2", gen.maxActive.get() <= 2)
            for ((i, t) in things.withIndex()) {
                val calls = gen.callsFor(t.key)
                val requests = issued.filter { it.op.key == i }
                assertTrue("key $i (${modes[i]}): $calls attempts > 3", calls <= 3)
                when (modes[i]) {
                    Mode.OK -> assertTrue("key $i generated $calls times while cached", calls <= 1)
                    Mode.PERMANENT -> assertTrue("key $i retried after a permanent failure ($calls)", calls <= 1)
                    Mode.FLAKY_1 -> assertTrue("key $i: $calls", calls <= 2)
                    else -> Unit
                }
                if (requests.all { it.op.cacheOnly }) {
                    assertEquals("key $i: only cache-only requests, yet generated", 0, calls)
                }
            }
            for ((op, d) in issued) {
                if (op.cancelAfterMs != null) continue
                val result = d.outcome()
                val what = "request $op (${modes[op.key]})"
                if (op.cacheOnly) {
                    val e = result.exceptionOrNull()
                    if (e == null) assertDisk(result, what)
                    else assertTrue("$what threw $e", e is ThumbnailNotCached)
                } else when (modes[op.key]) {
                    Mode.OK, Mode.FLAKY_1, Mode.FLAKY_2 -> assertDisk(result, what)
                    else -> assertTrue("$what: ${result.exceptionOrNull()}", result.exceptionOrNull() is ThumbnailGenerationException)
                }
            }
            cache.shutdown()
        }
    }

    // ─── Scheduling: targeted properties ────────────────────────────────────────────────

    /** A burst of concurrent requests for the same keys generates each key once; later reads hit disk. */
    @Test
    fun schedule_burstGeneratesEachKeyOnce() = runTest(timeout = 5.minutes) {
        checkAll(50, Arb.int(1..4), Arb.int(2..12), Arb.long(0L..5L)) { keyCount, perKey, latency ->
            val things = (0 until keyCount).map { thumbnail("burst$it") }
            val gen = FakeGenerator(latencyMs = latency)
            val cache = newCache()
            val scheduler = ThumbnailScheduler(cache, gen, ThumbnailWorkGate())
            withContext(Dispatchers.Default) {
                val results = things.flatMap { t -> List(perKey) { async { fetcher(t, false, cache, scheduler).fetch() } } }
                results.forEach { assertDisk(it.outcome(), "burst request") }
                things.forEach { assertDisk(runCatching { fetcher(it, false, cache, scheduler).fetch() }, "re-read") }
            }
            things.forEach { assertEquals("generations for ${it.key}", 1, gen.callsFor(it.key)) }
            assertTrue(gen.maxActive.get() <= 2)
            cache.shutdown()
        }
    }

    /** With both workers busy, requests cancelled while queued never run; the rest run once. */
    @Test
    fun schedule_cancelledWhileQueuedNeverRuns() = runTest(timeout = 5.minutes) {
        checkAll(50, Arb.list(Arb.boolean(), 1..8)) { cancelMask ->
            val gen = FakeGenerator()
            val cache = newCache()
            val scheduler = ThumbnailScheduler(cache, gen, ThumbnailWorkGate())
            val blockers = (0..1).map { thumbnail("blocker$it") }
            val queued = cancelMask.indices.map { thumbnail("queued$it") }
            val latch = CountDownLatch(1)
            blockers.forEach { gen.blockers[it.key] = latch }

            withContext(Dispatchers.Default) {
                supervisorScope {
                    val busy = blockers.map { t -> async { fetcher(t, false, cache, scheduler).fetch() } }
                    awaitReal(what = "both workers busy") { gen.active.get() == 2 }
                    val waiting = queued.map { t -> async { fetcher(t, false, cache, scheduler).fetch() } }
                    delay(20) // let them reach the workers' queue
                    waiting.zip(cancelMask).forEach { (d, cancel) -> if (cancel) d.cancel() }
                    latch.countDown()
                    withTimeout(20_000) { (busy + waiting).forEach { it.join() } }

                    busy.forEach { assertDisk(it.outcome(), "blocker") }
                    waiting.zip(cancelMask).forEachIndexed { i, (d, cancel) ->
                        if (cancel) {
                            assertEquals("cancelled queued request $i ran", 0, gen.callsFor(queued[i].key))
                            assertTrue(d.outcome().exceptionOrNull() is CancellationException)
                        } else {
                            assertDisk(d.outcome(), "queued request $i")
                            assertEquals(1, gen.callsFor(queued[i].key))
                        }
                    }
                }
            }
            assertTrue(gen.maxActive.get() <= 2)
            cache.shutdown()
        }
    }

    /**
     * While any viewer is open no generation starts, and disk hits are still served without
     * waiting; once the last viewer closes, the waiting requests generate.
     */
    @Test
    fun schedule_noGenerationWhileViewerOpen() = runTest(timeout = 5.minutes) {
        checkAll(50, Arb.list(Arb.boolean(), 1..6), Arb.int(1..3)) { cachedMask, viewers ->
            val gen = FakeGenerator()
            val gate = ThumbnailWorkGate()
            val cache = newCache()
            val scheduler = ThumbnailScheduler(cache, gen, gate)
            val things = cachedMask.indices.map { thumbnail("gate$it") }
            things.zip(cachedMask).forEach { (t, cached) -> if (cached) cache.write(t.key, solidBitmap(8, 8, Color.GRAY)) }

            withContext(Dispatchers.Default) {
                supervisorScope {
                    repeat(viewers) { gate.viewerOpened() }
                    val requests = things.map { t -> async { fetcher(t, false, cache, scheduler).fetch() } }
                    withTimeout(5_000) {
                        requests.zip(cachedMask).forEach { (d, cached) -> if (cached) assertDisk(d.outcome(), "disk hit with viewer open") }
                    }
                    repeat(viewers - 1) { gate.viewerClosed() }
                    delay(30)
                    assertEquals("generation started while a viewer was open", 0, gen.total.get())
                    requests.zip(cachedMask).forEach { (d, cached) -> if (!cached) assertFalse("uncached request done early", d.isCompleted) }

                    gate.viewerClosed()
                    withTimeout(20_000) { requests.forEach { assertDisk(it.outcome(), "after viewer closed") } }
                }
            }
            things.zip(cachedMask).forEach { (t, cached) -> assertEquals(if (cached) 0 else 1, gen.callsFor(t.key)) }
            cache.shutdown()
        }
    }

    /** A cache-only request returns a disk hit or throws [ThumbnailNotCached]; it never generates. */
    @Test
    fun schedule_cacheOnlyNeverGenerates() = runTest(timeout = 5.minutes) {
        checkAll(100, Arb.list(Arb.boolean(), 1..10)) { cachedMask ->
            val gen = FakeGenerator()
            val cache = newCache()
            val scheduler = ThumbnailScheduler(cache, gen, ThumbnailWorkGate())
            val things = cachedMask.indices.map { thumbnail("co$it") }
            things.zip(cachedMask).forEach { (t, cached) -> if (cached) cache.write(t.key, solidBitmap(8, 8, Color.CYAN)) }

            withContext(Dispatchers.Default) {
                things.zip(cachedMask).forEach { (t, cached) ->
                    val result = runCatching { fetcher(t, true, cache, scheduler).fetch() }
                    if (cached) assertDisk(result, "cache-only hit")
                    else assertTrue("cache-only miss threw ${result.exceptionOrNull()}", result.exceptionOrNull() is ThumbnailNotCached)
                }
            }
            assertEquals("cache-only requests generated", 0, gen.total.get())
            cache.shutdown()
        }
    }

    /** A failing key is attempted at most 3 times per process, and once after a permanent failure. */
    @Test
    fun schedule_failingKeyAttemptedAtMostThreeTimes() = runTest(timeout = 5.minutes) {
        val failing = listOf(Mode.PERMANENT, Mode.RETRYABLE, Mode.RUNTIME, Mode.FLAKY_1, Mode.FLAKY_2)
        checkAll(50, Arb.int(0 until failing.size), Arb.int(1..5), Arb.int(1..3)) { modeIndex, rounds, parallel ->
            val mode = failing[modeIndex]
            val t = thumbnail("fail")
            val gen = FakeGenerator(mapOf(t.key to mode))
            val cache = newCache()
            val scheduler = ThumbnailScheduler(cache, gen, ThumbnailWorkGate())

            withContext(Dispatchers.Default) {
                repeat(rounds) {
                    val results = List(parallel) { async { runCatching { fetcher(t, false, cache, scheduler).fetch() } } }
                        .map { it.await() }
                    results.forEach { r ->
                        when (mode) {
                            Mode.FLAKY_1, Mode.FLAKY_2 -> assertDisk(r, "$mode")
                            else -> assertTrue("$mode: ${r.exceptionOrNull()}", r.exceptionOrNull() is ThumbnailGenerationException)
                        }
                    }
                }
            }
            val expected = when (mode) {
                Mode.PERMANENT -> 1
                Mode.FLAKY_1 -> 2
                else -> 3
            }
            assertEquals("attempts for $mode after $rounds×$parallel requests", expected, gen.callsFor(t.key))
            assertFalse(mode !in setOf(Mode.FLAKY_1, Mode.FLAKY_2) && scheduler.canAttempt(t.key))
            cache.shutdown()
        }
    }
}

private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
