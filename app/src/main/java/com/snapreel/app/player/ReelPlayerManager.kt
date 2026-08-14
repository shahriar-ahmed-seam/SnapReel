package com.snapreel.app.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.snapreel.app.data.preferences.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Single High-Performance ExoPlayer Manager.
 * Operates a single video decoder instance with an ultra-low 50ms buffer for instant local playback,
 * completely eliminating hardware codec exhaustion, black screens, and native media daemon crashes.
 */
class ReelPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences
) {
    private var exoPlayer: ExoPlayer? = null
    private var currentUri: Uri? = null

    private var isInitialized = false
    private var currentLoopMode = true
    private var isMuted = false

    val player: ExoPlayer
        get() {
            ensureInitialized()
            return exoPlayer!!
        }

    private fun ensureInitialized() {
        if (!isInitialized || exoPlayer == null) {
            exoPlayer = createPlayer()
            isInitialized = true
        }
    }

    @OptIn(UnstableApi::class)
    private fun createPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1000,   // Min buffer: 1s
                10000,  // Max buffer: 10s
                50,     // Instant start: 50ms
                150     // After rebuffer: 150ms
            )
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .build()
            .apply {
                repeatMode = if (currentLoopMode) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                playWhenReady = false
                volume = if (isMuted) 0f else 1f
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
    }

    fun playUri(uri: Uri) {
        ensureInitialized()
        if (currentUri == uri && exoPlayer?.playbackState != Player.STATE_IDLE) {
            if (exoPlayer?.playbackState == Player.STATE_ENDED) {
                exoPlayer?.seekTo(0)
            }
            exoPlayer?.playWhenReady = true
            return
        }

        currentUri = uri
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer?.apply {
            stop()
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    fun stop() {
        if (isInitialized) {
            exoPlayer?.playWhenReady = false
            exoPlayer?.stop()
            currentUri = null
        }
    }

    fun pause() {
        if (isInitialized) {
            exoPlayer?.playWhenReady = false
        }
    }

    fun resume() {
        if (!isInitialized) return
        val p = exoPlayer ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
        }
        p.playWhenReady = true
    }

    fun seekTo(positionMs: Long) {
        if (!isInitialized) return
        exoPlayer?.seekTo(positionMs)
    }

    fun seekForward(millis: Long = 10_000) {
        if (!isInitialized) return
        val p = exoPlayer ?: return
        val newPos = (p.currentPosition + millis).coerceAtMost(p.duration.coerceAtLeast(0))
        p.seekTo(newPos)
    }

    fun seekBackward(millis: Long = 10_000) {
        if (!isInitialized) return
        val p = exoPlayer ?: return
        val newPos = (p.currentPosition - millis).coerceAtLeast(0)
        p.seekTo(newPos)
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        val vol = if (muted) 0f else 1f
        if (isInitialized) {
            exoPlayer?.volume = vol
        }
    }

    fun isMuted(): Boolean = isMuted

    fun updateLoopMode(loop: Boolean) {
        currentLoopMode = loop
        val mode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (isInitialized) {
            exoPlayer?.repeatMode = mode
        }
    }

    fun release() {
        if (isInitialized) {
            exoPlayer?.stop()
            exoPlayer?.release()
            exoPlayer = null
            currentUri = null
            isInitialized = false
        }
    }
}
