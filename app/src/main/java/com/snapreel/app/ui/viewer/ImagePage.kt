package com.snapreel.app.ui.viewer

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun ImagePage(
    mediaItem: MediaItem,
    isCurrentPage: Boolean,
    showControls: Boolean,
    showFileName: Boolean,
    autoAdvance: Boolean,
    autoAdvanceDelay: Int,
    fillScreen: Boolean = true,
    onTap: () -> Unit,
    onAutoAdvance: () -> Unit
) {
    // Pinch-to-zoom state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Auto-advance countdown
    var countdown by remember { mutableIntStateOf(autoAdvanceDelay) }

    // Use rememberUpdatedState so the tap callback is always fresh
    val currentOnTap by rememberUpdatedState(onTap)

    LaunchedEffect(isCurrentPage, autoAdvance) {
        if (isCurrentPage && autoAdvance) {
            countdown = autoAdvanceDelay
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
            onAutoAdvance()
        }
    }

    // Reset zoom when leaving page
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Tap gesture for immersive mode toggle
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnTap() }
                )
            }
            // Pinch-to-zoom gesture (separate pointerInput so they don't conflict)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // Only process multi-finger gestures for zoom/pan
                        if (event.changes.size > 1) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()

                            scale = (scale * zoom).coerceIn(1f, 5f)

                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
        AsyncImage(
            model = mediaItem.uri,
            contentDescription = mediaItem.name,
            contentScale = if (fillScreen) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
            onError = {
                // Will show fallback below
            }
        )

        // Image type indicator (top-left area, below status bar) — hides in immersive
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 56.dp, start = 16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.4f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        tint = Violet400,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Photo",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Bottom info — hides in immersive
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            Column {
                if (showFileName) {
                    Text(
                        text = mediaItem.name,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Auto-advance indicator
                if (autoAdvance && isCurrentPage && countdown > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { countdown.toFloat() / autoAdvanceDelay.toFloat() },
                            modifier = Modifier
                                .weight(1f)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp)),
                            color = Violet400,
                            trackColor = Color.White.copy(alpha = 0.15f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${countdown}s",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
