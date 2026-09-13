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
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.snapreel.app.ui.theme.*

import androidx.compose.foundation.lazy.grid.rememberLazyGridState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderMediaGridScreen(
    folderUri: Uri,
    returnedIndex: Int? = null,
    onBack: () -> Unit,
    onMediaClick: (Int, Boolean) -> Unit,
    viewModel: FolderGridViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(folderUri) {
        viewModel.loadMedia(folderUri)
    }

    val isLandscapeMode = settings.landscapeVideoMode
    val displayedItems = remember(uiState.mediaItems, isLandscapeMode) {
        if (isLandscapeMode) uiState.mediaItems.filter { it.isVideo } else uiState.mediaItems
    }

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
                                val originalIndex = uiState.mediaItems.indexOfFirst { it.uri == item.uri }
                                onMediaClick(if (originalIndex >= 0) originalIndex else index, isLandscapeMode)
                            }
                    ) {
                        AsyncImage(
                            model = coil3.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                .data(item.uri)
                                .size(300, 300)
                                .crossfade(true)
                                .build(),
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
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
