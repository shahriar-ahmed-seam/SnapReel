package com.snapreel.app.util.thumbnail

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts open viewers. While any viewer is open no thumbnail generation starts, so the viewer's
 * player gets the decoders and IO to itself. Generations already running finish.
 *
 * Starting a generation ([tryStartWork]) and opening a viewer ([viewerOpened]) are serialized, so
 * once [viewerOpened] returns, every generation that started earlier is already counted in
 * [runningGenerations] and no new one can start until the last viewer closes.
 */
@Singleton
class ThumbnailWorkGate @Inject constructor() {
    private val lock = Any()
    private val openViewers = MutableStateFlow(0)
    private var running = 0

    fun viewerOpened() {
        synchronized(lock) { openViewers.value = openViewers.value + 1 }
    }

    fun viewerClosed() {
        synchronized(lock) { openViewers.value = (openViewers.value - 1).coerceAtLeast(0) }
    }

    val isViewerOpen: Boolean get() = openViewers.value > 0

    /** Generations currently running (started and not yet finished). */
    val runningGenerations: Int get() = synchronized(lock) { running }

    /** Suspends (without holding a thread) until no viewer is open. */
    suspend fun awaitNoViewer() {
        openViewers.first { it == 0 }
    }

    /** Atomically checks that no viewer is open and, if so, counts a generation as started. */
    internal fun tryStartWork(): Boolean = synchronized(lock) {
        if (openViewers.value > 0) {
            false
        } else {
            running++
            true
        }
    }

    internal fun workFinished() {
        synchronized(lock) { running-- }
    }
}

/**
 * Runs thumbnail generation on at most [PARALLELISM] workers.
 *
 * - A request cancelled while queued for a worker never runs (cancellable dispatch).
 * - Waiting for the [ThumbnailWorkGate] suspends without holding a worker.
 * - One generation per key at a time; a waiter that finds the entry cached returns it.
 * - Once started, a generation runs to completion (`NonCancellable`) and is persisted, so the
 *   work is never wasted even if the tile scrolled away.
 * - A key gets fewer than [MAX_ATTEMPTS] + 1 attempts per process, and none after a permanent failure.
 */
@Singleton
class ThumbnailScheduler(
    private val cache: ThumbnailDiskCache,
    private val generator: ThumbnailGenerator,
    private val gate: ThumbnailWorkGate,
    private val workers: CoroutineDispatcher,
) {
    @Inject
    constructor(
        cache: ThumbnailDiskCache,
        generator: ThumbnailGenerator,
        gate: ThumbnailWorkGate,
    ) : this(cache, generator, gate, Dispatchers.IO.limitedParallelism(PARALLELISM))

    private class Attempts(var count: Int = 0, var permanent: Boolean = false)

    private val attempts = ConcurrentHashMap<String, Attempts>()
    private val locks = Array(LOCK_STRIPES) { Mutex() }

    private fun lockFor(key: String): Mutex = locks[(key.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES]

    /** Whether [key] may be generated again in this process. */
    fun canAttempt(key: String): Boolean {
        val a = attempts[key] ?: return true
        synchronized(a) { return !a.permanent && a.count < MAX_ATTEMPTS }
    }

    /** Attempts made for [key] in this process (reset by a success). */
    fun attemptsFor(key: String): Int = attempts[key]?.let { synchronized(it) { it.count } } ?: 0

    /**
     * Returns the cached thumbnail for [thumbnail] or generates, persists and returns it.
     *
     * @throws ThumbnailGenerationException when generation fails, or when the key has no
     *   attempts left (then `permanent = true`).
     */
    suspend fun generate(thumbnail: VideoThumbnail): Bitmap = withContext(workers) {
        val key = thumbnail.key
        lockFor(key).withLock {
            cache.read(key)?.let { return@withLock it }

            // Wait for every viewer to close, then claim the start atomically with the gate.
            while (true) {
                gate.awaitNoViewer()
                currentCoroutineContext().ensureActive()
                if (gate.tryStartWork()) break
            }
            try {
                val record = attempts.getOrPut(key) { Attempts() }
                synchronized(record) {
                    if (record.permanent || record.count >= MAX_ATTEMPTS) {
                        throw ThumbnailGenerationException("No attempts left for $key", permanent = true)
                    }
                    record.count++
                }
                withContext(NonCancellable) {
                    val bitmap = try {
                        generator.generate(thumbnail)
                    } catch (e: Exception) {
                        val failure = e as? ThumbnailGenerationException
                            ?: ThumbnailGenerationException("Generation failed for $key", permanent = false, e)
                        // Still under the key's lock: if this was the last allowed attempt, no later
                        // attempt can succeed, so report it as permanent and callers stop retrying.
                        val exhausted = synchronized(record) {
                            if (failure.permanent) record.permanent = true
                            record.permanent || record.count >= MAX_ATTEMPTS
                        }
                        throw if (exhausted && !failure.permanent) {
                            ThumbnailGenerationException("Gave up on $key after $MAX_ATTEMPTS attempts", permanent = true, failure)
                        } else {
                            failure
                        }
                    }
                    cache.write(key, bitmap)
                    attempts.remove(key)
                    bitmap
                }
            } finally {
                gate.workFinished()
            }
        }
    }

    companion object {
        const val PARALLELISM = 2
        const val MAX_ATTEMPTS = 3
        private const val LOCK_STRIPES = 64
    }
}
