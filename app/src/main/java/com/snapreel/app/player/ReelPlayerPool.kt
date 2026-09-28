package com.snapreel.app.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.snapreel.app.data.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import androidx.media3.common.MediaItem as Media3Item

/** What one page of a viewer sees: its pooled player (if bound) and that player's per-page state. */
data class PageState(
    val player: Player?,
    val videoSize: VideoSize?,
    val firstFrameRendered: Boolean,
    val failure: PlaybackFailure?,
)

/**
 * The players owned by one viewer. [ReelPlayerPool] is the implementation; tests drive the
 * viewer ViewModels with fakes through this seam.
 */
interface PlayerPool {
    /** Per-page state for every bound page, keyed by item URI. Empty after [release]. */
    val pages: StateFlow<Map<Uri, PageState>>

    /** Makes `items[current]` the current page and binds it and its video neighbors. */
    fun setWindow(items: List<MediaItem>, current: Int, playCurrent: Boolean)

    // Transport calls act on the current page's player.
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun seekBy(deltaMs: Long)
    fun setMuted(muted: Boolean)
    fun setLoop(loop: Boolean)
    fun positionMs(): Long
    fun durationMs(): Long

    /** Clears the page's failure and prepares its player again (playing it if it is current). */
    fun retry(uri: Uri)

    /** Stops and unbinds every neighbor, freeing their decoders. The current page keeps its player. */
    fun trimToCurrent()

    /** Undoes [trimToCurrent]: binds and prepares the neighbors of the current window again. */
    fun restoreNeighbors()

    /** Releases every player. Idempotent; afterwards every call is a no-op and [pages] is empty. */
    fun release()
}

/**
 * A ViewModel-scoped pool of at most [maxPlayers] ExoPlayers: the current page, the next and the
 * previous one (design › Playback). Neighbors are prepared paused so a swipe starts from a frame
 * that's already decoded. Must be used from the main thread.
 *
 * @param playerFactory creates one player (test seam). Never called after [release].
 */
@OptIn(UnstableApi::class)
class ReelPlayerPool(
    context: Context,
    private val maxPlayers: Int = SlotPlanner.MAX_CAPACITY,
    private val playerFactory: () -> ExoPlayer = { buildDefaultPlayer(context.applicationContext) },
) : PlayerPool {

    private val handler = Handler(Looper.getMainLooper())
    private val slots = List(maxPlayers.coerceIn(1, SlotPlanner.MAX_CAPACITY)) { Slot() }
    private val states = LinkedHashMap<Uri, PageState>()
    private val _pages = MutableStateFlow<Map<Uri, PageState>>(emptyMap())
    override val pages: StateFlow<Map<Uri, PageState>> = _pages.asStateFlow()

    private var released = false
    private var items: List<MediaItem> = emptyList()
    private var currentIndex = -1
    private var currentUri: Uri? = null

    /** Shrinks when a neighbor reports decoder exhaustion; never below 1. */
    var capacity: Int = slots.size
        private set

    /** Set by [trimToCurrent] (app stopped or viewer leaving) until [restoreNeighbors]. */
    private var trimmed = false
    /** Set when the current page hit DECODER_BUSY; neighbors stay unbound until the window moves. */
    private var busyTrim = false
    /** Neighbors that failed in this window; they aren't rebound until the window moves. */
    private val failedNeighbors = HashSet<Uri>()
    /** Neighbors may prepare once the current page shows its first frame (or after [WARM_UP_DELAY_MS]). */
    private var neighborsMayPrepare = false
    private val warmUp = Runnable { openNeighbors() }

    private var muted = false
    private var loop = true

    /** Players currently held (created and not yet released). */
    val heldPlayerCount: Int get() = slots.count { it.player != null }

    /** Pages that currently hold a player. */
    val boundUris: List<Uri> get() = slots.mapNotNull { it.uri }

    private inner class Slot {
        var player: ExoPlayer? = null
        var uri: Uri? = null
        var prepared = false
        var autoRetried = false
        var surfaceAttached = false

        val listener = object : Player.Listener {
            private fun boundUri(): Uri? {
                val uri = uri ?: return null
                val mediaId = player?.currentMediaItem?.mediaId
                return uri.takeIf { mediaId == null || mediaId == it.toString() }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val uri = boundUri() ?: return
                if (videoSize.width > 0 && videoSize.height > 0) update(uri) { it.copy(videoSize = videoSize) }
            }

            override fun onRenderedFirstFrame() {
                val uri = boundUri() ?: return
                update(uri) { it.copy(firstFrameRendered = true) }
                if (uri == currentUri) openNeighbors()
            }

            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                val attached = width > 0 && height > 0
                val wasAttached = surfaceAttached
                surfaceAttached = attached
                if (!attached || wasAttached) return
                onSurfaceAttached(this@Slot)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (boundUri() == null) return
                onSlotError(this@Slot, error.errorCode)
            }
        }
    }

    // ─── Window ─────────────────────────────────────────────────────────────────────────

    override fun setWindow(items: List<MediaItem>, current: Int, playCurrent: Boolean) {
        if (released) return
        val previousUri = currentUri
        val moved = current != currentIndex || items !== this.items
        this.items = items
        currentIndex = current
        currentUri = items.getOrNull(current)?.takeIf { it.isVideo }?.uri

        if (moved) {
            failedNeighbors.clear()
            busyTrim = false
            neighborsMayPrepare = false
            handler.removeCallbacks(warmUp)
        }

        // A player that stops being current is paused and restarts from the beginning next time.
        if (previousUri != null && previousUri != currentUri) {
            slotFor(previousUri)?.player?.let { p ->
                p.playWhenReady = false
                p.seekTo(0)
            }
        }

        // A page that failed earlier gets a fresh attempt when it becomes current again.
        currentUri?.let { uri ->
            val slot = slotFor(uri)
            if (slot != null && states[uri]?.failure != null && previousUri != uri) {
                slot.autoRetried = false
                update(uri) { it.copy(failure = null, firstFrameRendered = false) }
                slot.player?.prepare()
            }
        }

        applyPlan()

        for (slot in slots) {
            val p = slot.player ?: continue
            if (slot.uri != null && slot.uri == currentUri) {
                if (playCurrent) {
                    if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                    p.playWhenReady = true
                } else {
                    p.playWhenReady = false
                }
            } else {
                p.playWhenReady = false
            }
        }

        if (moved || !neighborsMayPrepare) armWarmUp()
    }

    /** Opens the neighbor gate now if the current page is ready, else after [WARM_UP_DELAY_MS]. */
    private fun armWarmUp() {
        handler.removeCallbacks(warmUp)
        val uri = currentUri
        if (uri == null || states[uri]?.firstFrameRendered == true) {
            openNeighbors()
        } else {
            handler.postDelayed(warmUp, WARM_UP_DELAY_MS)
        }
    }

    private fun openNeighbors() {
        if (released) return
        handler.removeCallbacks(warmUp)
        neighborsMayPrepare = true
        for (slot in slots) {
            if (slot.uri != null && !slot.prepared) prepare(slot)
        }
    }

    /** Binds the slots to the planned pages: kept pages are untouched, others are unbound or newly bound. */
    private fun applyPlan() {
        if (released) return
        val planned = SlotPlanner.plan(items, currentIndex, capacity, slots.map { it.uri }, exclude = failedNeighbors)
            .map { uri ->
                when {
                    uri == null -> null
                    uri == currentUri -> uri
                    trimmed || busyTrim || uri in failedNeighbors -> null
                    else -> uri
                }
            }
        // Unbind first so a URI moving between slots never has two players.
        slots.forEachIndexed { i, slot -> if (slot.uri != null && slot.uri != planned[i]) unbind(slot) }
        slots.forEachIndexed { i, slot ->
            val target = planned[i]
            if (target != null && slot.uri == null) bind(slot, target)
        }
        publish()
    }

    private fun bind(slot: Slot, uri: Uri) {
        val player = slot.player ?: playerFactory().also { p ->
            p.addListener(slot.listener)
            p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            slot.player = p
        }
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.volume = if (muted) 0f else 1f
        player.playWhenReady = false
        slot.uri = uri
        slot.prepared = false
        slot.autoRetried = false
        player.setMediaItem(Media3Item.Builder().setUri(uri).setMediaId(uri.toString()).build())
        states[uri] = PageState(player, videoSize = null, firstFrameRendered = false, failure = null)
        if (uri == currentUri || neighborsMayPrepare) prepare(slot)
    }

    private fun prepare(slot: Slot) {
        val p = slot.player ?: return
        slot.prepared = true
        p.prepare()
    }

    private fun unbind(slot: Slot) {
        val uri = slot.uri ?: return
        slot.player?.let { p ->
            p.playWhenReady = false
            p.stop()
            p.clearMediaItems()
        }
        slot.uri = null
        slot.prepared = false
        slot.autoRetried = false
        states.remove(uri)
    }

    // ─── Events ─────────────────────────────────────────────────────────────────────────

    private fun onSurfaceAttached(slot: Slot) {
        if (released) return
        val uri = slot.uri ?: return
        update(uri) { it.copy(firstFrameRendered = false) }
        // A paused player only draws on the new surface after a seek; playing ones redraw on their own.
        val p = slot.player ?: return
        if (!p.playWhenReady && p.playbackState == Player.STATE_READY) p.seekTo(p.currentPosition)
    }

    private fun onSlotError(slot: Slot, errorCode: Int) {
        if (released) return
        val uri = slot.uri ?: return
        val failure = PlaybackErrorPolicy.classify(errorCode)
        if (uri == currentUri) {
            if (failure.transient && !slot.autoRetried) {
                slot.autoRetried = true
                if (failure.kind == PlaybackFailureKind.DECODER_BUSY) {
                    busyTrim = true
                    applyPlan()
                }
                slot.player?.prepare()
            } else {
                update(uri) { it.copy(failure = failure) }
            }
        } else {
            // Neighbor failures never reach the UI: drop the binding; the page retries when it becomes current.
            failedNeighbors += uri
            if (failure.kind == PlaybackFailureKind.DECODER_BUSY) capacity = (capacity - 1).coerceAtLeast(1)
            unbind(slot)
            applyPlan()
        }
    }

    // ─── Transport ──────────────────────────────────────────────────────────────────────

    private fun currentPlayer(): ExoPlayer? = currentUri?.let { slotFor(it)?.player }

    override fun play() {
        if (released) return
        val p = currentPlayer() ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.playWhenReady = true
    }

    override fun pause() {
        if (released) return
        currentPlayer()?.playWhenReady = false
    }

    override fun seekTo(ms: Long) {
        if (released) return
        currentPlayer()?.seekTo(ms.coerceAtLeast(0))
    }

    override fun seekBy(deltaMs: Long) {
        if (released) return
        val p = currentPlayer() ?: return
        val target = if (deltaMs >= 0) {
            (p.currentPosition + deltaMs).coerceAtMost(p.duration.coerceAtLeast(0))
        } else {
            (p.currentPosition + deltaMs).coerceAtLeast(0)
        }
        p.seekTo(target)
    }

    override fun setMuted(muted: Boolean) {
        if (released) return
        this.muted = muted
        slots.forEach { it.player?.volume = if (muted) 0f else 1f }
    }

    override fun setLoop(loop: Boolean) {
        if (released) return
        this.loop = loop
        val mode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        slots.forEach { it.player?.repeatMode = mode }
    }

    override fun positionMs(): Long = if (released) 0L else currentPlayer()?.currentPosition?.coerceAtLeast(0) ?: 0L

    override fun durationMs(): Long = if (released) 0L else currentPlayer()?.duration?.coerceAtLeast(0) ?: 0L

    override fun retry(uri: Uri) {
        if (released) return
        val slot = slotFor(uri)
        if (slot == null) {
            applyPlan()
        } else {
            update(uri) { it.copy(failure = null, firstFrameRendered = false) }
            prepare(slot)
        }
        if (uri == currentUri) play()
    }

    override fun trimToCurrent() {
        if (released) return
        trimmed = true
        applyPlan()
    }

    override fun restoreNeighbors() {
        if (released || !trimmed) return
        trimmed = false
        applyPlan()
        armWarmUp()
    }

    override fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(warmUp)
        for (slot in slots) {
            slot.player?.let { p ->
                p.removeListener(slot.listener)
                p.release()
            }
            slot.player = null
            slot.uri = null
        }
        states.clear()
        _pages.value = emptyMap()
    }

    // ─── State ──────────────────────────────────────────────────────────────────────────

    private fun slotFor(uri: Uri): Slot? = slots.firstOrNull { it.uri == uri }

    private inline fun update(uri: Uri, change: (PageState) -> PageState) {
        val old = states[uri] ?: return
        val new = change(old)
        if (new != old) {
            states[uri] = new
            publish()
        }
    }

    private fun publish() {
        if (!released) _pages.value = LinkedHashMap(states)
    }

    /** Creates pools for the viewer ViewModels. Open so tests can hand out pools with fake players. */
    open class Factory @Inject constructor(@ApplicationContext private val context: Context) {
        open fun create(): PlayerPool = ReelPlayerPool(context)
    }

    companion object {
        /** Neighbors wait this long for the current page's first frame before preparing anyway. */
        const val WARM_UP_DELAY_MS = 300L

        /** The production player: short buffers for local files, 16 MiB target, decoder fallback. */
        fun buildDefaultPlayer(context: Context): ExoPlayer {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1_000, // min buffer
                    5_000, // max buffer
                    50, // buffer for playback
                    150, // buffer for playback after rebuffer
                )
                .setTargetBufferBytes(16 * 1024 * 1024)
                .build()
            val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
            return ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setRenderersFactory(renderersFactory)
                .build()
        }
    }
}
