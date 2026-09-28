package com.snapreel.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.snapreel.app.BuildConfig
import com.snapreel.app.ui.theme.*
import com.snapreel.app.util.update.REINSTALL_REQUIRED_MESSAGE
import com.snapreel.app.util.update.UpdateError
import com.snapreel.app.util.update.UpdateUiState

object UpdateDialogTags {
    const val MESSAGE = "update_dialog_message"
    const val PRIMARY = "update_dialog_primary"
}

/**
 * The in-app update dialog. The Offer and Downloading layouts are the ones users know (version,
 * release notes, Later / Update Now, progress); the later steps render in the same dialog, which
 * stays open with a specific message until it is dismissed or the install succeeds.
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onUpdateNow: () -> Unit,
    onAllowInstalls: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val info = when (state) {
        UpdateUiState.Hidden -> return
        is UpdateUiState.Offer -> state.info
        is UpdateUiState.Downloading -> state.info
        is UpdateUiState.Verifying -> state.info
        is UpdateUiState.NeedsInstallPermission -> state.info
        is UpdateUiState.ReadyToInstall -> state.info
        is UpdateUiState.Installing -> state.info
        is UpdateUiState.Failed -> state.info
        is UpdateUiState.ReinstallRequired -> state.info
    }
    val busy = state is UpdateUiState.Downloading || state is UpdateUiState.Verifying

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Header(
                    title = if (state is UpdateUiState.ReinstallRequired) "Reinstall Needed" else "Update Available",
                    versionName = info.versionName,
                )
                Spacer(modifier = Modifier.height(16.dp))

                val showNotes = state is UpdateUiState.Offer || state is UpdateUiState.Downloading ||
                    state is UpdateUiState.ReadyToInstall
                if (showNotes && info.releaseNotes.isNotBlank()) {
                    Text(
                        text = "What's New:",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.3f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = info.releaseNotes.trim(),
                            fontSize = 12.sp,
                            color = TextPrimary.copy(alpha = 0.85f),
                            lineHeight = 18.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                when (state) {
                    is UpdateUiState.Downloading -> Progress(
                        label = "Downloading update...",
                        progress = state.progress,
                    )
                    is UpdateUiState.Verifying -> Progress(label = "Checking the download...", progress = null)
                    is UpdateUiState.Offer -> Actions(onDismiss, "Update Now", onUpdateNow, showIcon = true)
                    is UpdateUiState.NeedsInstallPermission -> {
                        Message(UpdateError.NeedsPermission.message, isError = false)
                        Actions(onDismiss, "Allow", onAllowInstalls)
                    }
                    is UpdateUiState.ReadyToInstall -> {
                        Message("The update is downloaded and verified.", isError = false)
                        Actions(onDismiss, "Install", onInstall)
                    }
                    is UpdateUiState.Installing -> {
                        Progress(label = "Finish the installation in the system installer.", progress = null)
                        Spacer(modifier = Modifier.height(8.dp))
                        Actions(onDismiss, primaryLabel = null, onPrimary = {})
                    }
                    is UpdateUiState.Failed -> {
                        Message(state.error.message, isError = true)
                        if (state.error == UpdateError.LaunchFailed) {
                            Actions(onDismiss, "Open download page", onOpenReleasePage)
                        } else {
                            Actions(onDismiss, "Try Again", onRetry)
                        }
                    }
                    is UpdateUiState.ReinstallRequired -> {
                        Message(REINSTALL_REQUIRED_MESSAGE, isError = true)
                        Actions(onDismiss, "Open download page", onOpenReleasePage)
                    }
                    UpdateUiState.Hidden -> Unit
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, versionName: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(brush = Brush.linearGradient(colors = listOf(Violet600, Violet400))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (versionName.isNotBlank()) {
                    Text(
                        text = "v$versionName",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Violet400
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(text = "(Current: v${BuildConfig.VERSION_NAME})", fontSize = 11.sp, color = TextMuted)
            }
        }
    }
}

@Composable
private fun Progress(label: String, progress: Float?) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val barModifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = barModifier,
                color = Violet500,
                trackColor = SurfaceElevated
            )
        } else {
            LinearProgressIndicator(modifier = barModifier, color = Violet500, trackColor = SurfaceElevated)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = TextMuted,
                modifier = Modifier.weight(1f).testTag(UpdateDialogTags.MESSAGE)
            )
            if (progress != null) {
                Text(
                    text = "${(progress * 100).toInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Violet400
                )
            }
        }
    }
}

@Composable
private fun Message(text: String, isError: Boolean) {
    Text(
        text = text,
        color = if (isError) ErrorRed else TextSecondary,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier
            .padding(bottom = 12.dp)
            .testTag(UpdateDialogTags.MESSAGE)
    )
}

@Composable
private fun Actions(onDismiss: () -> Unit, primaryLabel: String?, onPrimary: () -> Unit, showIcon: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onDismiss) {
            Text(if (primaryLabel == null) "Close" else "Later", color = TextMuted)
        }
        if (primaryLabel == null) return@Row
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onPrimary,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            contentPadding = PaddingValues(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.testTag(UpdateDialogTags.PRIMARY)
        ) {
            Box(
                modifier = Modifier
                    .background(
                        brush = Brush.horizontalGradient(colors = listOf(Violet600, Violet500)),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showIcon) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(text = primaryLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                }
            }
        }
    }
}
