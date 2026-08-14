package com.snapreel.app.ui.viewer

import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.snapreel.app.ui.theme.*
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
@Composable
fun ReelsViewerScreen(
    folderUri: Uri,
    startIndex: Int = 0,
    onBack: (Int) -> Unit,
    viewModel: ReelsViewerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    // Intercept system back button / back gesture
    BackHandler {
        onBack(uiState.currentIndex)
    }

    // Load media on first composition
    LaunchedEffect(folderUri, startIndex) {
        viewModel.loadMedia(folderUri, startIndex)
    }

    // Lifecycle-aware pause and Screen Keep-On
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (uiState.isLoading) {
            LoadingContent()
        } else if (uiState.error != null) {
            ErrorContent(
                error = uiState.error ?: "Unknown error",
                onBack = { onBack(uiState.currentIndex) }
            )
        } else if (uiState.mediaItems.isNotEmpty()) {
            ReelsContent(
                folderUri = folderUri,
                uiState = uiState,
                viewModel = viewModel,
                view = view,
                scope = scope
            )
        }

        // Back button (hide in immersive)
        AnimatedVisibility(
            visible = uiState.showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 8.dp, start = 4.dp)
                .align(Alignment.TopStart)
        ) {
            IconButton(
                onClick = { onBack(uiState.currentIndex) }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Violet500,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Scanning media...",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun ErrorContent(error: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = ErrorRed,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error,
                color = TextSecondary,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = onBack) {
                Text("Go Back")
            }
        }
    }
}

@Composable
private fun ReelsContent(
    folderUri: android.net.Uri,
    uiState: ViewerUiState,
    viewModel: ReelsViewerViewModel,
    view: android.view.View,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val pagerState = rememberPagerState(
        initialPage = uiState.currentIndex,
        pageCount = { uiState.mediaItems.size }
    )

    // React to page changes
    LaunchedEffect(pagerState.settledPage) {
        viewModel.onPageSettled(pagerState.settledPage)
        viewModel.saveLastViewedIndex(folderUri, pagerState.settledPage)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { uiState.mediaItems[it].uri.toString() }
        ) { pageIndex ->
            val mediaItem = uiState.mediaItems[pageIndex]
            val isCurrentPage = pagerState.settledPage == pageIndex

            if (mediaItem.isVideo) {
                VideoPage(
                    mediaItem = mediaItem,
                    playerManager = viewModel.playerManager,
                    isCurrentPage = isCurrentPage,
                    isPlaying = uiState.isPlaying,
                    isMuted = uiState.isMuted,
                    showControls = uiState.showControls,
                    showFileName = uiState.settings.showFileName,
                    // Zero logic in lambdas — just call ViewModel
                    onTap = { viewModel.onVideoTap() },
                    onDoubleTapLeft = { viewModel.seekBackward() },
                    onDoubleTapRight = { viewModel.seekForward() },
                    onMuteToggle = { viewModel.toggleMute() },
                    onSeekTo = { pos -> viewModel.seekTo(pos) },
                    onSliderDragStart = {
                        // Pause while dragging so seek is smooth
                        viewModel.playerManager.pause()
                    },
                    onSliderDragEnd = { seekPos ->
                        viewModel.seekTo(seekPos)
                        // Resume if was playing before drag
                        if (uiState.isPlaying) {
                            viewModel.playerManager.resume()
                        }
                    },
                    onControlsTimeout = { viewModel.onControlsTimeout() }
                )
            } else {
                ImagePage(
                    mediaItem = mediaItem,
                    isCurrentPage = isCurrentPage,
                    showControls = uiState.showControls,
                    showFileName = uiState.settings.showFileName,
                    autoAdvance = uiState.settings.autoAdvanceImages,
                    autoAdvanceDelay = uiState.settings.autoAdvanceDelaySeconds,
                    onTap = { viewModel.onImageTap() },
                    onAutoAdvance = {
                        if (pageIndex < uiState.mediaItems.size - 1) {
                            scope.launch {
                                pagerState.animateScrollToPage(pageIndex + 1)
                            }
                        }
                    }
                )
            }
        }

        // Media counter (hide in immersive)
        AnimatedVisibility(
            visible = uiState.showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 12.dp, end = 16.dp),
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = Color.Black.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = "${uiState.currentIndex + 1} / ${uiState.mediaItems.size}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
