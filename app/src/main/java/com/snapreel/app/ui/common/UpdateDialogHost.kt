package com.snapreel.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snapreel.app.util.UpdateManager
import com.snapreel.app.util.update.UpdateCoordinator
import com.snapreel.app.util.update.UpdateUiState

/**
 * The update dialog wired to the [UpdateCoordinator], shared by Home and Settings. It owns the
 * "Install unknown apps" round trip: the result (and every `ON_RESUME`, in case the result is
 * lost) continues the install once the permission is granted.
 */
@Composable
fun UpdateDialogHost(state: UpdateUiState, coordinator: UpdateCoordinator) {
    if (state == UpdateUiState.Hidden) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentState by rememberUpdatedState(state)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { coordinator.onInstallPermissionResult() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && currentState is UpdateUiState.NeedsInstallPermission) {
                coordinator.onInstallPermissionResult()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    UpdateDialog(
        state = state,
        onUpdateNow = coordinator::startUpdate,
        onAllowInstalls = {
            try {
                permissionLauncher.launch(coordinator.installPermissionIntent())
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "Open Settings › Apps › SnapReel › Install unknown apps", Toast.LENGTH_LONG).show()
            }
        },
        onInstall = coordinator::install,
        onRetry = coordinator::retry,
        onOpenReleasePage = {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManager.RELEASES_PAGE))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, UpdateManager.RELEASES_PAGE, Toast.LENGTH_LONG).show()
            }
        },
        onDismiss = coordinator::dismiss,
    )
}
