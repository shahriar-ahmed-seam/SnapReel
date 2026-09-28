package com.snapreel.app.testsupport

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.FakeMediaSourceFactory
import androidx.media3.test.utils.TestExoPlayerBuilder
import com.snapreel.app.player.PlayerPool
import com.snapreel.app.player.ReelPlayerPool
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Players for pool tests: Media3's fake renderers, a fake clock and fake media, so nothing is
 * decoded and (unless [ScriptedFailures] says otherwise) nothing fails.
 */
@OptIn(UnstableApi::class)
object TestPlayers {

    /** A player whose media always prepares and plays (FakeMediaSource + fake renderers). */
    fun nonFailing(context: Context): ExoPlayer =
        TestExoPlayerBuilder(context).setMediaSourceFactory(FakeMediaSourceFactory()).build()

    /** A player whose media fails as [failures] scripts it, per item URI and per prepare. */
    fun scripted(context: Context, failures: ScriptedFailures): ExoPlayer =
        TestExoPlayerBuilder(context).setMediaSourceFactory(failures.sourceFactory()).build()

    /** A pool factory (for the viewer ViewModels) whose players never fail: the runs stay outside C6. */
    fun nonFailingPoolFactory(context: Context): ReelPlayerPool.Factory =
        object : ReelPlayerPool.Factory(context) {
            override fun create(): PlayerPool = ReelPlayerPool(context, playerFactory = { nonFailing(context) })
        }

    /** Counts and remembers every player the pool asks for. */
    class CountingFactory(private val build: () -> ExoPlayer) : () -> ExoPlayer {
        val created: MutableList<ExoPlayer> = Collections.synchronizedList(mutableListOf())
        /** Playlist changes (`setMediaItem`) seen per player, in creation order. */
        val playlistChanges: MutableMap<ExoPlayer, AtomicInteger> = ConcurrentHashMap()

        override fun invoke(): ExoPlayer = build().also { p ->
            created += p
            val counter = AtomicInteger()
            playlistChanges[p] = counter
            p.addListener(object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) counter.incrementAndGet()
                }
            })
        }

        val count: Int get() = created.size
        val held: Int get() = created.count { !it.isReleased }
    }

    /**
     * Scripted source errors: every prepare of an item's media (`player.prepare()`, including a
     * retry) takes the next code from its queue and fails with it; an empty queue means the prepare
     * succeeds. The error reaches the player
     * as a [DataSourceException] whose reason becomes `PlaybackException.errorCode`.
     */
    class ScriptedFailures {
        private val queues = ConcurrentHashMap<String, ArrayDeque<Int>>()
        private val prepares = ConcurrentHashMap<String, AtomicInteger>()

        fun script(uri: Uri, codes: List<Int>) {
            queues[uri.toString()] = ArrayDeque(codes)
        }

        /** How many times the item's media was prepared (one per attempt). */
        fun prepares(uri: Uri): Int = prepares[uri.toString()]?.get() ?: 0

        /** Codes still unused for [uri]. */
        fun remaining(uri: Uri): Int = synchronized(this) { queues[uri.toString()]?.size ?: 0 }

        internal fun nextFailure(mediaId: String): Int? = synchronized(this) {
            prepares.getOrPut(mediaId) { AtomicInteger() }.incrementAndGet()
            queues[mediaId]?.removeFirstOrNull()
        }

        fun sourceFactory(): MediaSource.Factory = object : MediaSource.Factory {
            private val fake = FakeMediaSourceFactory()
            override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = this
            override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = this
            override fun getSupportedTypes(): IntArray = fake.supportedTypes
            override fun createMediaSource(mediaItem: Media3Item): MediaSource =
                ScriptedSource(fake.createMediaSource(mediaItem), mediaItem.mediaId, this@ScriptedFailures)
        }
    }

    private class ScriptedSource(
        child: MediaSource,
        private val mediaId: String,
        private val failures: ScriptedFailures,
    ) : WrappingMediaSource(child) {
        /** The scripted outcome of the current prepare (one per `player.prepare()`; loops don't count). */
        @Volatile private var failure: Int? = null

        override fun prepareSourceInternal() {
            failure = failures.nextFailure(mediaId)
            super.prepareSourceInternal()
        }

        override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod {
            val code = failure
            return if (code == null) super.createPeriod(id, allocator, startPositionUs) else FailingPeriod(code)
        }

        override fun releasePeriod(mediaPeriod: MediaPeriod) {
            if (mediaPeriod !is FailingPeriod) super.releasePeriod(mediaPeriod)
        }
    }

    /** A period that never prepares and reports [errorCode] as its prepare error. */
    private class FailingPeriod(private val errorCode: Int) : MediaPeriod {
        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) = Unit
        override fun maybeThrowPrepareError() = throw DataSourceException(errorCode)
        override fun getTrackGroups(): TrackGroupArray = TrackGroupArray.EMPTY
        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long = positionUs
        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit
        override fun readDiscontinuity(): Long = C.TIME_UNSET
        override fun seekToUs(positionUs: Long): Long = positionUs
        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs
        override fun getBufferedPositionUs(): Long = C.TIME_END_OF_SOURCE
        override fun getNextLoadPositionUs(): Long = C.TIME_END_OF_SOURCE
        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false
        override fun isLoading(): Boolean = false
        override fun reevaluateBuffer(positionUs: Long) = Unit
    }
}
