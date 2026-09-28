package com.snapreel.app.ui.common

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapreel.app.data.repository.ACCESS_LOST_MESSAGE
import com.snapreel.app.ui.theme.TextSecondary
import com.snapreel.app.ui.theme.Violet500

object FolderAccessLostTags {
    const val ROOT = "folder_access_lost"
    const val PICK_AGAIN = "folder_access_lost_pick_again"
}

/**
 * Shown when a folder can no longer be read (revoked grant, a Recent entry restored by Auto Backup
 * without its grant, or a moved/renamed/deleted folder). "Pick folder again" opens the system
 * folder picker at the lost folder and hands the result to [onRePicked].
 *
 * @param onBack when set, a "Go Back" button is shown too (the viewers).
 */
@Composable
fun FolderAccessLost(
    folderUri: Uri,
    onRePicked: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked: Uri? ->
        picked?.let(onRePicked)
    }

    Box(modifier = modifier.fillMaxSize().testTag(FolderAccessLostTags.ROOT), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.FolderOff,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = ACCESS_LOST_MESSAGE,
                color = TextSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                // The lost folder as the picker's initial location.
                onClick = { picker.launch(folderUri) },
                colors = ButtonDefaults.buttonColors(containerColor = Violet500),
                modifier = Modifier.testTag(FolderAccessLostTags.PICK_AGAIN)
            ) {
                Text("Pick folder again")
            }
            if (onBack != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onBack) {
                    Text("Go Back")
                }
            }
        }
    }
}
