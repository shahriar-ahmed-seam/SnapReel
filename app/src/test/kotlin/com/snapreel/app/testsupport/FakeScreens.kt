package com.snapreel.app.testsupport

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.snapreel.app.navigation.SnapReelScreens

/**
 * Lightweight screens for `SnapReelNavGraph(testNavController, screens)`: each screen is a column of
 * buttons that invoke the graph's callbacks. One click fires the callback [burst] times, which models
 * a burst of taps that all land before the next frame (the source screen is still composed).
 */
class FakeScreens(
    private val folder: Uri,
    private val otherFolder: Uri,
    var burst: Int = 1,
) : SnapReelScreens {

    object Tags {
        const val HOME_OPEN = "fake_home_open"
        const val HOME_PLAY = "fake_home_play"
        const val HOME_SETTINGS = "fake_home_settings"
        const val GRID_TILE = "fake_grid_tile"
        const val GRID_BACK = "fake_grid_back"
        const val GRID_OTHER = "fake_grid_other"
        const val VIEWER_BACK = "fake_viewer_back"
        const val VIEWER_OTHER = "fake_viewer_other"
        const val SETTINGS_BACK = "fake_settings_back"
    }

    /** The index a fake viewer reports on back. */
    var viewerReturnIndex: Int = 3

    private fun burstOf(action: () -> Unit): () -> Unit = { repeat(burst) { action() } }

    @Composable
    override fun Home(
        onFolderSelected: (Uri) -> Unit,
        onPlaySelected: (Uri, Int, Boolean) -> Unit,
        onSettingsClick: () -> Unit,
    ) {
        Column {
            Button(onClick = burstOf { onFolderSelected(folder) }, modifier = Modifier.testTag(Tags.HOME_OPEN)) { Text("open") }
            Button(onClick = burstOf { onPlaySelected(folder, 0, false) }, modifier = Modifier.testTag(Tags.HOME_PLAY)) { Text("play") }
            Button(onClick = burstOf(onSettingsClick), modifier = Modifier.testTag(Tags.HOME_SETTINGS)) { Text("settings") }
        }
    }

    @Composable
    override fun Grid(
        folderUri: Uri,
        returnedIndex: Int?,
        onBack: () -> Unit,
        onMediaClick: (Int, Boolean) -> Unit,
        onOpenOtherFolder: (Uri) -> Unit,
    ) {
        Column {
            Button(onClick = burstOf { onMediaClick(0, false) }, modifier = Modifier.testTag(Tags.GRID_TILE)) { Text("tile") }
            Button(onClick = burstOf(onBack), modifier = Modifier.testTag(Tags.GRID_BACK)) { Text("back") }
            Button(onClick = burstOf { onOpenOtherFolder(otherFolder) }, modifier = Modifier.testTag(Tags.GRID_OTHER)) { Text("other") }
        }
    }

    @Composable
    override fun Viewer(folderUri: Uri, startIndex: Int, fresh: Boolean, onBack: (Int) -> Unit, onOpenOtherFolder: (Uri) -> Unit) =
        FakeViewer(onBack, onOpenOtherFolder)

    @Composable
    override fun LandscapeViewer(folderUri: Uri, startIndex: Int, fresh: Boolean, onBack: (Int) -> Unit, onOpenOtherFolder: (Uri) -> Unit) =
        FakeViewer(onBack, onOpenOtherFolder)

    @Composable
    private fun FakeViewer(onBack: (Int) -> Unit, onOpenOtherFolder: (Uri) -> Unit) {
        Column {
            Button(onClick = burstOf { onBack(viewerReturnIndex) }, modifier = Modifier.testTag(Tags.VIEWER_BACK)) { Text("back") }
            Button(onClick = burstOf { onOpenOtherFolder(otherFolder) }, modifier = Modifier.testTag(Tags.VIEWER_OTHER)) { Text("other") }
        }
    }

    @Composable
    override fun Settings(onBack: () -> Unit) {
        Button(onClick = burstOf(onBack), modifier = Modifier.testTag(Tags.SETTINGS_BACK)) { Text("back") }
    }
}
