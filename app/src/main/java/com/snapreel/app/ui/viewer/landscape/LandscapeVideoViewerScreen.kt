package com.snapreel.app.ui.viewer.landscape

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.AspectRatioMode
import com.snapreel.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@OptIn(UnstableApi::class)
@Composable
fun LandscapeVideoViewerScreen(
    folderUri: Uri,
    startIndex: Int = 0,
    onBack: (Int) -> Unit,
    viewModel: LandscapeVideoViewerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    // 1. Force Sensor Landscape Orientation while in this screen
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    // 2. Keep Screen On and Observe Lifecycle
    DisposableEffect(lifecycleOwner, view) {
        view.keepScreenOn = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.onAppPaused()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
        }
    }

    // 3. Resolve return index to save on back
    val onExit: () -> Unit = {
        val currentVideo = uiState.videos.getOrNull(uiState.currentIndex)
        val returnIndex = if (currentVideo != null) {
            val idx = uiState.originalItems.indexOfFirst { it.uri == currentVideo.uri }
            if (idx >= 0) idx else uiState.currentIndex
        } else {
            0
        }
        onBack(returnIndex)
    }

    BackHandler {
        onExit()
    }

    LaunchedEffect(folderUri, startIndex) {
        viewModel.loadVideos(folderUri, startIndex)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Violet500, strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading videos in Landscape...", color = TextSecondary, fontSize = 14.sp)
                }
            }
        } else if (uiState.error != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = uiState.error ?: "Unknown error", color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = onExit) {
                        Text("Go Back")
                    }
                }
            }
        } else if (uiState.videos.isNotEmpty()) {
            LandscapePagerContent(
                uiState = uiState,
                viewModel = viewModel,
                folderUri = folderUri,
                onExit = onExit
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LandscapePagerContent(
    uiState: LandscapeViewerUiState,
    viewModel: LandscapeVideoViewerViewModel,
    folderUri: Uri,
    onExit: () -> Unit
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = uiState.currentIndex,
        pageCount = { uiState.videos.size }
    )

    // Seek states
    val showSeekLeft = remember { mutableStateOf(false) }
    val showSeekRight = remember { mutableStateOf(false) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderProgress by remember { mutableFloatStateOf(0f) }
    val currentTime = remember { mutableLongStateOf(0L) }
    val totalTime = remember { mutableLongStateOf(0L) }
    var isFillScreen by remember { mutableStateOf(false) }

    // React to horizontal page changes
    LaunchedEffect(pagerState.settledPage) {
        viewModel.onPageSettled(pagerState.settledPage)
        viewModel.saveLastViewedIndex(folderUri, pagerState.settledPage)
    }

    // Auto-hide controls timer
    LaunchedEffect(uiState.showControls, uiState.isPlaying, isDraggingSlider) {
        if (uiState.showControls && uiState.isPlaying && !isDraggingSlider) {
            delay(3500)
            viewModel.onControlsTimeout()
        }
    }

    // Playback time tracking
    LaunchedEffect(pagerState.settledPage, isDraggingSlider) {
        if (!isDraggingSlider) {
            while (true) {
                val p = viewModel.playerManager.player
                currentTime.longValue = p.currentPosition
                totalTime.longValue = p.duration.coerceAtLeast(0)
                if (totalTime.longValue > 0) {
                    sliderProgress = (currentTime.longValue.toFloat() / totalTime.longValue.toFloat()).coerceIn(0f, 1f)
                }
                delay(100)
            }
        }
    }

    val currentVideo = uiState.videos.getOrNull(pagerState.settledPage)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { viewModel.onVideoTap() },
                    onDoubleTap = { offset ->
                        val tapX = offset.x
                        val screenWidthPx = screenWidth * density
                        if (tapX < screenWidthPx / 2) {
                            showSeekLeft.value = true
                            viewModel.seekBackward(10_000)
                        } else {
                            showSeekRight.value = true
                            viewModel.seekForward(10_000)
                        }
                    }
                )
            }
    ) {
        // Horizontal Video Pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { uiState.videos[it].uri.toString() }
        ) { pageIndex ->
            val videoItem = uiState.videos[pageIndex]
            val isCurrentPage = pagerState.settledPage == pageIndex

            Box(modifier = Modifier.fillMaxSize()) {
                if (isCurrentPage) {
                    AndroidView(
                        factory = { context ->
                            PlayerView(context).apply {
                                useController = false
                                resizeMode = if (isFillScreen) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                setKeepContentOnPlayerReset(true)
                                player = viewModel.playerManager.player
                            }
                        },
                        update = { playerView ->
                            val targetResizeMode = if (isFillScreen) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                            if (playerView.resizeMode != targetResizeMode) {
                                playerView.resizeMode = targetResizeMode
                            }
                            if (playerView.player != viewModel.playerManager.player) {
                                playerView.player = viewModel.playerManager.player
                            }
                        },
                        onRelease = { playerView ->
                            playerView.player = null
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(videoItem.uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = videoItem.name,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Double Tap Indicators
        LaunchedEffect(showSeekLeft.value) {
            if (showSeekLeft.value) {
                delay(600)
                showSeekLeft.value = false
            }
        }
        AnimatedVisibility(
            visible = showSeekLeft.value,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 64.dp)
        ) {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.6f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(imageVector = Icons.Filled.FastRewind, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("10s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        LaunchedEffect(showSeekRight.value) {
            if (showSeekRight.value) {
                delay(600)
                showSeekRight.value = false
            }
        }
        AnimatedVisibility(
            visible = showSeekRight.value,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 64.dp)
        ) {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.6f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("10s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(imageVector = Icons.Filled.FastForward, contentDescription = null, tint = Color.White)
                }
            }
        }

        // Center Controls (Previous, Large Play/Pause, Next)
        AnimatedVisibility(
            visible = uiState.showControls || !uiState.isPlaying,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Previous Video
                IconButton(
                    onClick = {
                        if (pagerState.currentPage > 0) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        }
                    },
                    enabled = pagerState.currentPage > 0,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = if (pagerState.currentPage > 0) 0.5f else 0.2f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous Video",
                        tint = if (pagerState.currentPage > 0) Color.White else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Center Play/Pause Button
                IconButton(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Violet600.copy(alpha = 0.85f))
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                // Next Video
                IconButton(
                    onClick = {
                        if (pagerState.currentPage < uiState.videos.size - 1) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    },
                    enabled = pagerState.currentPage < uiState.videos.size - 1,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = if (pagerState.currentPage < uiState.videos.size - 1) 0.5f else 0.2f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next Video",
                        tint = if (pagerState.currentPage < uiState.videos.size - 1) Color.White else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Top Bar Overlay
        AnimatedVisibility(
            visible = uiState.showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onExit) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = currentVideo?.name ?: "",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // Counter Pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Black.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${uiState.videos.size}",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Fill / Fit Toggle
                    IconButton(onClick = { isFillScreen = !isFillScreen }) {
                        Icon(
                            imageVector = if (isFillScreen) Icons.Filled.FitScreen else Icons.Filled.Fullscreen,
                            contentDescription = if (isFillScreen) "Fit original" else "Fill screen",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // Bottom Controls Overlay
        AnimatedVisibility(
            visible = uiState.showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Time and Scrubber
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(currentTime.longValue),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Slider(
                            value = sliderProgress,
                            onValueChange = { newProgress ->
                                if (!isDraggingSlider) {
                                    isDraggingSlider = true
                                    viewModel.playerManager.pause()
                                }
                                sliderProgress = newProgress
                            },
                            onValueChangeFinished = {
                                isDraggingSlider = false
                                if (totalTime.longValue > 0) {
                                    val targetMs = (sliderProgress * totalTime.longValue).toLong()
                                    viewModel.seekTo(targetMs)
                                }
                                if (uiState.isPlaying) {
                                    viewModel.playerManager.resume()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                                .height(22.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Violet400,
                                activeTrackColor = Violet500,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )

                        Text(
                            text = formatTime(totalTime.longValue),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Secondary Bottom Actions (Rewind 10s, Forward 10s, Mute, Loop)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            IconButton(
                                onClick = { viewModel.seekBackward(10_000) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Replay10,
                                    contentDescription = "Rewind 10s",
                                    tint = Color.White
                                )
                            }

                            IconButton(
                                onClick = { viewModel.seekForward(10_000) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Forward10,
                                    contentDescription = "Forward 10s",
                                    tint = Color.White
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Loop Toggle
                            IconButton(
                                onClick = { viewModel.toggleLoop() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (uiState.isLooping) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                    contentDescription = "Loop Toggle",
                                    tint = if (uiState.isLooping) Violet400 else Color.White.copy(alpha = 0.6f)
                                )
                            }

                            // Mute Toggle
                            IconButton(
                                onClick = { viewModel.toggleMute() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (uiState.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = if (uiState.isMuted) "Unmute" else "Mute",
                                    tint = if (uiState.isMuted) ErrorRed else Color.White
                                )
                            }
                        }
                    }
                }
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
