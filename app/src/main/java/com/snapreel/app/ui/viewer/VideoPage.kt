package com.snapreel.app.ui.viewer

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.player.ReelPlayerManager
import com.snapreel.app.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    mediaItem: MediaItem,
    playerManager: ReelPlayerManager,
    isCurrentPage: Boolean,
    isPlaying: Boolean,
    isMuted: Boolean,
    showControls: Boolean,
    showFileName: Boolean,
    onTap: () -> Unit,
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit,
    onMuteToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSliderDragStart: () -> Unit,
    onSliderDragEnd: (Long) -> Unit,
    onControlsTimeout: () -> Unit
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp

    // rememberUpdatedState ensures the gesture detector always calls the
    // LATEST version of these callbacks, even though pointerInput(Unit)
    // only creates the detector once.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDoubleTapLeft by rememberUpdatedState(onDoubleTapLeft)
    val currentOnDoubleTapRight by rememberUpdatedState(onDoubleTapRight)

    val showSeekLeft = remember { mutableStateOf(false) }
    val showSeekRight = remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    val currentTime = remember { mutableLongStateOf(0L) }
    val totalTime = remember { mutableLongStateOf(0L) }

    // Auto-hide controls timer
    // Only ticks when controls are visible AND video is playing (State B)
    LaunchedEffect(showControls, isPlaying, isDraggingSlider) {
        if (showControls && isPlaying && !isDraggingSlider) {
            delay(3000)
            onControlsTimeout()
        }
    }

    // Update progress bar from player position
    LaunchedEffect(isCurrentPage, isDraggingSlider) {
        if (isCurrentPage && !isDraggingSlider) {
            while (true) {
                val p = playerManager.getPlayerForUri(mediaItem.uri)
                currentTime.longValue = p.currentPosition
                totalTime.longValue = p.duration.coerceAtLeast(0)
                if (totalTime.longValue > 0) {
                    progress = (currentTime.longValue.toFloat() / totalTime.longValue.toFloat()).coerceIn(0f, 1f)
                }
                delay(100)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // Uses rememberUpdatedState — always fresh
                        currentOnTap()
                    },
                    onDoubleTap = { offset ->
                        val tapX = offset.x
                        val screenWidthPx = screenWidth * density
                        if (tapX < screenWidthPx / 2) {
                            showSeekLeft.value = true
                            currentOnDoubleTapLeft()
                        } else {
                            showSeekRight.value = true
                            currentOnDoubleTapRight()
                        }
                    }
                )
            }
    ) {
        // Video Player
        if (isCurrentPage) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setKeepContentOnPlayerReset(true)
                    }
                },
                update = { playerView ->
                    playerView.player = playerManager.getPlayerForUri(mediaItem.uri)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Center play icon — visible when video is paused (State C)
        AnimatedVisibility(
            visible = !isPlaying,
            enter = scaleIn(initialScale = 0.5f, animationSpec = tween(200)) + fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        // Double-tap seek left indicator
        LaunchedEffect(showSeekLeft.value) {
            if (showSeekLeft.value) {
                delay(500)
                showSeekLeft.value = false
            }
        }
        AnimatedVisibility(
            visible = showSeekLeft.value,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(300)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 40.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.FastRewind,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
                Text("10s", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Double-tap seek right indicator
        LaunchedEffect(showSeekRight.value) {
            if (showSeekRight.value) {
                delay(500)
                showSeekRight.value = false
            }
        }
        AnimatedVisibility(
            visible = showSeekRight.value,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(300)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 40.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
                Text("10s", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Bottom overlay gradient
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
            )
        }

        // Bottom controls (slider, time, filename)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                // File name
                if (showFileName) {
                    Text(
                        text = mediaItem.name,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                // Time display
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(currentTime.longValue),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                    Text(
                        text = formatTime(totalTime.longValue),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }

                // Progress Slider
                Slider(
                    value = progress,
                    onValueChange = { newValue ->
                        if (!isDraggingSlider) {
                            isDraggingSlider = true
                            onSliderDragStart()
                        }
                        progress = newValue
                    },
                    onValueChangeFinished = {
                        isDraggingSlider = false
                        if (totalTime.longValue > 0) {
                            val seekTime = (progress * totalTime.longValue).toLong()
                            onSliderDragEnd(seekTime)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Violet400,
                        activeTrackColor = Violet500,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
            }
        }

        // Mute button
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 80.dp, end = 12.dp)
        ) {
            IconButton(
                onClick = onMuteToggle,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (isMuted) "Unmute" else "Mute",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
