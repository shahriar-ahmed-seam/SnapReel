package com.snapreel.app.ui.viewer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.snapreel.app.data.repository.FolderLoadError
import com.snapreel.app.data.repository.displayMessage
import com.snapreel.app.ui.common.FolderAccessLost
import com.snapreel.app.ui.theme.*
import com.snapreel.app.util.thumbnail.videoThumbnailRequest

import androidx.compose.foundation.lazy.grid.rememberLazyGridState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderMediaGridScreen(
    folderUri: Uri,
    returnedIndex: Int? = null,
    onBack: () -> Unit,
    onMediaClick: (Int, Boolean) -> Unit,
    onOpenOtherFolder: (Uri) -> Unit = {},
    viewModel: FolderGridViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val gridState = rememberLazyGridState()
    val currentOnOpenOtherFolder by rememberUpdatedState(onOpenOtherFolder)

    LaunchedEffect(folderUri) {
        viewModel.loadMedia(folderUri)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FolderEvent.OpenOtherFolder -> currentOnOpenOtherFolder(event.uri)
            }
        }
    }

    val isLandscapeMode = settings.landscapeVideoMode
    val displayedItems = remember(uiState.mediaItems, isLandscapeMode) {
        MediaIndexMapping.gridDisplayedItems(uiState.mediaItems, isLandscapeMode)
    }
    val loadError = uiState.error

    LaunchedEffect(returnedIndex, displayedItems.size) {
        if (displayedItems.isNotEmpty()) {
            val targetIndex = returnedIndex ?: viewModel.getSavedLastIndex(folderUri)
            val scrollIndex = if (isLandscapeMode) {
                val targetUri = uiState.mediaItems.getOrNull(targetIndex)?.uri
                val found = displayedItems.indexOfFirst { it.uri == targetUri }
                if (found >= 0) found else 0
            } else {
                targetIndex
            }
            if (scrollIndex in displayedItems.indices) {
                gridState.scrollToItem(scrollIndex)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            "Folder Media", 
                            maxLines = 1, 
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = if (isLandscapeMode) "Landscape Mode (Videos Only)" else "Reels Mode (All Media)",
                            color = if (isLandscapeMode) Violet400 else Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    // Quick Toggle for Landscape Video Mode vs Reels Mode
                    IconButton(
                        onClick = { viewModel.toggleLandscapeMode() }
                    ) {
                        Icon(
                            imageVector = if (isLandscapeMode) Icons.Filled.StayCurrentLandscape else Icons.Filled.StayCurrentPortrait,
                            contentDescription = if (isLandscapeMode) "Switch to Reels Mode" else "Switch to Landscape Mode",
                            tint = if (isLandscapeMode) Violet400 else Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E1E)
                )
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Violet500)
            }
        } else if (loadError is FolderLoadError.AccessLost) {
            FolderAccessLost(
                folderUri = folderUri,
                onRePicked = { picked -> viewModel.onFolderRePicked(folderUri, picked) },
                modifier = Modifier.padding(paddingValues)
            )
        } else if (loadError is FolderLoadError.Failed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = loadError.displayMessage,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.retry(folderUri) }) {
                        Text("Retry")
                    }
                }
            }
        } else if (displayedItems.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(displayedItems) { index, item ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(Color.DarkGray)
                            .clickable {
                                onMediaClick(
                                    MediaIndexMapping.gridTapToFullIndex(uiState.mediaItems, isLandscapeMode, index),
                                    isLandscapeMode
                                )
                            }
                    ) {
                        if (item.isVideo) {
                            // Placeholder under the thumbnail: visible while it loads and if it fails.
                            Icon(
                                imageVector = Icons.Outlined.Movie,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(32.dp)
                            )
                            val context = LocalContext.current
                            val request = remember(item.uri, item.size, item.dateModified, item.supportsThumbnail) {
                                videoThumbnailRequest(context, item, cacheOnly = false, crossfade = true)
                            }
                            AsyncImage(
                                model = request,
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            AsyncImage(
                                model = coil3.request.ImageRequest.Builder(LocalContext.current)
                                    .data(item.uri)
                                    .size(300, 300)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (item.isVideo) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(20.dp)
                            )
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = if (isLandscapeMode) "No videos found in this folder." else "No media found in this folder.",
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    if (isLandscapeMode && uiState.mediaItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = { viewModel.toggleLandscapeMode() }) {
                            Text("Switch to Reels Mode to view images")
                        }
                    }
                }
            }
        }
    }
}
