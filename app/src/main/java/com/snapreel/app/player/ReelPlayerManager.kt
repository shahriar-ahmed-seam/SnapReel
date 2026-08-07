package com.snapreel.app.player

import android.content.Context
import android.net.Uri
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
    private val playerPool = mutableListOf<ExoPlayer>()
    private val uriToPlayerMap = mutableMapOf<String, ExoPlayer>()
    private var currentPlayerUri: String? = null

    val player: ExoPlayer
        get() = getActivePlayer()

    init {
        for (i in 0 until 3) {
            playerPool.add(createPlayer())
        }
    }

    private fun getActivePlayer(): ExoPlayer {
        return currentPlayerUri?.let { uriToPlayerMap[it] } ?: playerPool.first()
    }

    @OptIn(UnstableApi::class)
    private fun createPlayer(): ExoPlayer {
        val loopVideos = runBlocking { appPreferences.settings.first().loopVideos }
        
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
                playWhenReady = false
                volume = 1f
                videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
    }

    fun getPlayerForUri(uri: Uri): ExoPlayer {
        return uriToPlayerMap[uri.toString()] ?: getActivePlayer()
    }

    fun onPageChanged(currentUri: Uri?, nextUri: Uri?, prevUri: Uri?, playCurrent: Boolean = true) {
        val currentUriStr = currentUri?.toString()
        val nextUriStr = nextUri?.toString()
        val prevUriStr = prevUri?.toString()

        currentPlayerUri = currentUriStr

        val wantedUris = listOfNotNull(currentUriStr, nextUriStr, prevUriStr)

        val unusedPlayers = playerPool.filter { player ->
            val uriForPlayer = uriToPlayerMap.entries.find { it.value == player }?.key
            uriForPlayer !in wantedUris
        }.toMutableList()

        for (uriStr in wantedUris) {
            if (!uriToPlayerMap.containsKey(uriStr)) {
                if (unusedPlayers.isNotEmpty()) {
                    val playerToReuse = unusedPlayers.removeAt(0)
                    val oldEntry = uriToPlayerMap.entries.find { it.value == playerToReuse }
                    if (oldEntry != null) {
                        uriToPlayerMap.remove(oldEntry.key)
                    }
                    uriToPlayerMap[uriStr] = playerToReuse
                    prepareUri(Uri.parse(uriStr), playerToReuse)
                }
            }
        }

        for ((uriStr, player) in uriToPlayerMap) {
            if (uriStr != currentUriStr) {
                player.playWhenReady = false
                if (player.playbackState == Player.STATE_ENDED) {
                     player.seekTo(0)
                }
            }
        }
        
        currentUriStr?.let {
            val player = uriToPlayerMap[it]
            if (playCurrent) {
                if (player?.playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                }
                player?.playWhenReady = true
            } else {
                player?.playWhenReady = false
            }
        }
    }

    private fun prepareUri(uri: Uri, playerToUse: ExoPlayer) {
        val mediaItem = MediaItem.fromUri(uri)
        playerToUse.apply {
            playWhenReady = false
            setMediaItem(mediaItem)
            prepare()
        }
    }

    fun pause() {
        getActivePlayer().playWhenReady = false
    }

    fun resume() {
        val active = getActivePlayer()
        if (active.playbackState == Player.STATE_ENDED) {
            active.seekTo(0)
        }
        active.playWhenReady = true
    }

    fun seekForward(millis: Long = 10_000) {
        val active = getActivePlayer()
        val newPos = (active.currentPosition + millis).coerceAtMost(active.duration)
        active.seekTo(newPos)
    }

    fun seekBackward(millis: Long = 10_000) {
        val active = getActivePlayer()
        val newPos = (active.currentPosition - millis).coerceAtLeast(0)
        active.seekTo(newPos)
    }

    fun setMuted(muted: Boolean) {
        val vol = if (muted) 0f else 1f
        playerPool.forEach { it.volume = vol }
    }

    fun isMuted(): Boolean = (getActivePlayer().volume) == 0f

    fun updateLoopMode(loop: Boolean) {
        val mode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        playerPool.forEach { it.repeatMode = mode }
    }

    fun release() {
        playerPool.forEach { it.release() }
        playerPool.clear()
        uriToPlayerMap.clear()
        currentPlayerUri = null
    }
}
