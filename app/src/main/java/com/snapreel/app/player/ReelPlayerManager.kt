package com.snapreel.app.player

import android.content.Context
import androidx.annotation.OptIn
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
import javax.inject.Singleton

@Singleton
class ReelPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences
) {
    private var _player: ExoPlayer? = null

    val player: ExoPlayer
        get() {
            if (_player == null) {
                _player = createPlayer()
            }
            return _player!!
        }

    @OptIn(UnstableApi::class)
    private fun createPlayer(): ExoPlayer {
        val loopVideos = runBlocking { appPreferences.settings.first().loopVideos }
        
        // Fast buffering optimized for local files/reels
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2000,   // Min buffer: 2s
                15000,  // Max buffer: 15s
                250,    // Buffer for playback: 250ms
                500     // Buffer for playback after rebuffer: 500ms
            )
            .build()
            
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .build()
            .apply {
                repeatMode = if (loopVideos) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                playWhenReady = true
                volume = 1f
                videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
    }

    fun playUri(uri: android.net.Uri) {
        val mediaItem = MediaItem.fromUri(uri)
        player.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    fun pause() {
        _player?.playWhenReady = false
    }

    fun resume() {
        if (_player?.playbackState == Player.STATE_ENDED) {
            _player?.seekTo(0)
        }
        _player?.playWhenReady = true
    }


    fun seekForward(millis: Long = 10_000) {
        _player?.let {
            val newPos = (it.currentPosition + millis).coerceAtMost(it.duration)
            it.seekTo(newPos)
        }
    }

    fun seekBackward(millis: Long = 10_000) {
        _player?.let {
            val newPos = (it.currentPosition - millis).coerceAtLeast(0)
            it.seekTo(newPos)
        }
    }

    fun setMuted(muted: Boolean) {
        _player?.volume = if (muted) 0f else 1f
    }

    fun isMuted(): Boolean = (_player?.volume ?: 1f) == 0f

    fun updateLoopMode(loop: Boolean) {
        _player?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun release() {
        _player?.release()
        _player = null
    }
}
