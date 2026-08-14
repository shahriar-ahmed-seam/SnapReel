package com.snapreel.app.ui.settings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.snapreel.app.data.preferences.SortOrder
import com.snapreel.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    var showSortDialog by remember { mutableStateOf(false) }
    var showDelayDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // Playback Section
            SettingsSectionHeader("Playback")

            SettingsCard {
                SettingsToggleItem(
                    icon = Icons.Filled.Loop,
                    title = "Loop Videos",
                    subtitle = "Repeat videos continuously",
                    checked = settings.loopVideos,
                    onCheckedChange = { viewModel.setLoopVideos(it) }
                )

                HorizontalDivider(color = SurfaceElevated, thickness = 0.5.dp)

                SettingsToggleItem(
                    icon = Icons.Filled.Shuffle,
                    title = "Shuffle Media",
                    subtitle = "Randomize playback order",
                    checked = settings.shuffleMedia,
                    onCheckedChange = { viewModel.setShuffleMedia(it) }
                )

                HorizontalDivider(color = SurfaceElevated, thickness = 0.5.dp)

                SettingsClickItem(
                    icon = Icons.Filled.Sort,
                    title = "Sort Order",
                    subtitle = formatSortOrder(settings.sortOrder),
                    onClick = { showSortDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Images Section
            SettingsSectionHeader("Images")

            SettingsCard {
                SettingsToggleItem(
                    icon = Icons.Filled.Timer,
                    title = "Auto-Advance Images",
                    subtitle = "Automatically swipe to next image",
                    checked = settings.autoAdvanceImages,
                    onCheckedChange = { viewModel.setAutoAdvanceImages(it) }
                )

                AnimatedVisibility(visible = settings.autoAdvanceImages) {
                    Column {
                        HorizontalDivider(color = SurfaceElevated, thickness = 0.5.dp)

                        SettingsClickItem(
                            icon = Icons.Filled.Timelapse,
                            title = "Auto-Advance Delay",
                            subtitle = "${settings.autoAdvanceDelaySeconds} seconds",
                            onClick = { showDelayDialog = true }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Display Section
            SettingsSectionHeader("Display")

            SettingsCard {
                SettingsToggleItem(
                    icon = Icons.Filled.TextFields,
                    title = "Show File Name",
                    subtitle = "Display file name on media",
                    checked = settings.showFileName,
                    onCheckedChange = { viewModel.setShowFileName(it) }
                )

                HorizontalDivider(color = SurfaceElevated, thickness = 0.5.dp)

                SettingsToggleItem(
                    icon = Icons.Filled.Vibration,
                    title = "Haptic Feedback",
                    subtitle = "Vibrate on page swipe",
                    checked = settings.hapticFeedback,
                    onCheckedChange = { viewModel.setHapticFeedback(it) }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Updates Section
            SettingsSectionHeader("Updates & About")

            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !updateState.isChecking) { viewModel.checkForUpdates() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = Violet400,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Check for Updates",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = if (updateState.isUpToDate) "You're on the latest version (v${com.snapreel.app.BuildConfig.VERSION_NAME})" 
                                   else "Installed: v${com.snapreel.app.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (updateState.isUpToDate) Violet400 else TextMuted
                        )
                    }
                    if (updateState.isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Violet500,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = TextDisabled,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // App info
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "SnapReel",
                        color = Violet500,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "v${com.snapreel.app.BuildConfig.VERSION_NAME}",
                        color = TextDisabled,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // Show Update Dialog if update is available
        if (updateState.availableUpdate != null) {
            com.snapreel.app.ui.common.UpdateDialog(
                updateInfo = updateState.availableUpdate!!,
                isDownloading = updateState.isDownloading,
                downloadProgress = updateState.downloadProgress,
                downloadedBytes = updateState.downloadedBytes,
                totalBytes = updateState.totalBytes,
                error = updateState.error,
                onUpdateClick = { viewModel.startUpdate() },
                onDismiss = { viewModel.dismissUpdateDialog() }
            )
        }
    }

    // Sort order dialog
    if (showSortDialog) {
        SortOrderDialog(
            currentOrder = settings.sortOrder,
            onOrderSelected = {
                viewModel.setSortOrder(it)
                showSortDialog = false
            },
            onDismiss = { showSortDialog = false }
        )
    }

    // Delay dialog
    if (showDelayDialog) {
        DelayDialog(
            currentDelay = settings.autoAdvanceDelaySeconds,
            onDelaySelected = {
                viewModel.setAutoAdvanceDelay(it)
                showDelayDialog = false
            },
            onDismiss = { showDelayDialog = false }
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = Violet400,
        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Violet400,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Violet500,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = SurfaceElevated
            )
        )
    }
}

@Composable
private fun SettingsClickItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Violet400,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = TextDisabled,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SortOrderDialog(
    currentOrder: SortOrder,
    onOrderSelected: (SortOrder) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceVariant,
        title = {
            Text("Sort Order", color = TextPrimary)
        },
        text = {
            Column {
                SortOrder.entries.forEach { order ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOrderSelected(order) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentOrder == order,
                            onClick = { onOrderSelected(order) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Violet500,
                                unselectedColor = TextMuted
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatSortOrder(order),
                            color = if (currentOrder == order) TextPrimary else TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Violet400)
            }
        }
    )
}

@Composable
private fun DelayDialog(
    currentDelay: Int,
    onDelaySelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(2, 3, 5, 8, 10, 15)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceVariant,
        title = {
            Text("Auto-Advance Delay", color = TextPrimary)
        },
        text = {
            Column {
                options.forEach { seconds ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDelaySelected(seconds) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentDelay == seconds,
                            onClick = { onDelaySelected(seconds) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Violet500,
                                unselectedColor = TextMuted
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$seconds seconds",
                            color = if (currentDelay == seconds) TextPrimary else TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Violet400)
            }
        }
    )
}

private fun formatSortOrder(order: SortOrder): String = when (order) {
    SortOrder.NAME_ASC -> "Name (A → Z)"
    SortOrder.NAME_DESC -> "Name (Z → A)"
    SortOrder.DATE_NEWEST -> "Date (Newest first)"
    SortOrder.DATE_OLDEST -> "Date (Oldest first)"
    SortOrder.SIZE_LARGEST -> "Size (Largest first)"
    SortOrder.SIZE_SMALLEST -> "Size (Smallest first)"
    SortOrder.TYPE_VIDEO_FIRST -> "Type (Videos first)"
    SortOrder.TYPE_IMAGE_FIRST -> "Type (Images first)"
}
