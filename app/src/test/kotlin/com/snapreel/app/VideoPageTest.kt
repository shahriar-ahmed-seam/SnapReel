package com.snapreel.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.AspectRatioMode
import com.snapreel.app.player.PageState
import com.snapreel.app.player.PlaybackErrorPolicy
import com.snapreel.app.ui.viewer.VideoPage
import com.snapreel.app.ui.viewer.VideoPageTags
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `VideoPage` (Compose + Robolectric): the preview stays until the page's first frame, the
 * failure overlay's Retry calls `onRetry`, and each page takes its orientation from its own
 * player's video size (a neighbor never follows the current page).
 *
 * **Validates: Requirements 2.5, 2.6, 2.7, 2.8**
 */
@RunWith(AndroidJUnit4::class)
class VideoPageTest {

    @get:Rule
    val compose = createComposeRule()

    private fun item(i: Int) = MediaItem(
        uri = Uri.parse("content://com.snapreel.test/item/$i"),
        name = "clip$i.mp4",
        mimeType = "video/mp4",
        size = 1_000L + i,
        dateModified = 1_700_000_000_000L,
        isVideo = true,
    )

    @Composable
    private fun Page(
        state: PageState?,
        current: Boolean,
        onRetry: () -> Unit = {},
        modifier: Modifier = Modifier.fillMaxSize(),
        tag: String? = null,
    ) {
            Box(modifier.let { m -> tag?.let { m.testTag(it) } ?: m }) {
                VideoPage(
                    mediaItem = item(if (current) 0 else 1),
                    pageState = state,
                    onRetry = onRetry,
                    isCurrentPage = current,
                    isPlaying = current && state?.failure == null,
                    isMuted = false,
                    showControls = false,
                    showFileName = true,
                    aspectRatioMode = AspectRatioMode.SMART,
                    onTap = {},
                    onDoubleTapLeft = {},
                    onDoubleTapRight = {},
                    onMuteToggle = {},
                    onSeekTo = {},
                    onSliderDragStart = {},
                    onSliderDragEnd = {},
                    onControlsTimeout = {},
                )
            }
    }

    @Test
    fun preview_visibleUntilFirstFrameRendered() {
        var state by mutableStateOf(PageState(player = null, videoSize = null, firstFrameRendered = false, failure = null))
        compose.setContent { Page(state, current = true) }

        compose.onNodeWithTag(VideoPageTags.PREVIEW).assertExists()

        // The player reports a size but no frame yet: the preview stays.
        state = state.copy(videoSize = VideoSize(1080, 1920))
        compose.waitForIdle()
        compose.onNodeWithTag(VideoPageTags.PREVIEW).assertExists()

        state = state.copy(firstFrameRendered = true)
        compose.waitForIdle()
        compose.onNodeWithTag(VideoPageTags.PREVIEW).assertDoesNotExist()

        // A new surface (revisited page) resets the flag: the preview comes back, never black.
        state = state.copy(firstFrameRendered = false)
        compose.waitForIdle()
        compose.onNodeWithTag(VideoPageTags.PREVIEW).assertExists()
    }

    @Test
    fun preview_shownWhenPageHasNoPlayerYet() {
        compose.setContent { Page(null, current = false) }
        compose.onNodeWithTag(VideoPageTags.PREVIEW).assertExists()
        compose.onNodeWithTag(VideoPageTags.FAILURE).assertDoesNotExist()
    }

    @Test
    fun failureOverlay_retryCallsOnRetry() {
        var retries = 0
        val failure = PlaybackErrorPolicy.classify(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        var state by mutableStateOf(PageState(player = null, videoSize = null, firstFrameRendered = false, failure = failure))
        compose.setContent { Page(state, current = true, onRetry = { retries++ }) }

        compose.onNodeWithTag(VideoPageTags.FAILURE).assertExists()
        compose.onNodeWithText("This video was moved or deleted").assertExists()
        compose.onNodeWithTag(VideoPageTags.RETRY, useUnmergedTree = true)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, retries)

        // After a successful retry the overlay goes away.
        state = state.copy(failure = null)
        compose.waitForIdle()
        compose.onNodeWithTag(VideoPageTags.FAILURE).assertDoesNotExist()
    }

    @Test
    fun neighborPages_useTheirOwnOrientation() {
        val landscape = PageState(player = null, videoSize = VideoSize(1920, 1080), firstFrameRendered = true, failure = null)
        val portrait = PageState(player = null, videoSize = VideoSize(1080, 1920), firstFrameRendered = false, failure = null)
        compose.setContent {
            Column {
                Row {
                    Page(landscape, current = true, modifier = Modifier.size(200.dp), tag = "current")
                    Page(portrait, current = false, modifier = Modifier.size(200.dp), tag = "neighbor")
                }
                Page(null, current = false, modifier = Modifier.size(200.dp), tag = "unknown")
            }
        }

        fun root(parent: String) = compose.onNode(
            hasTestTag(VideoPageTags.ROOT) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag(parent)),
        )
        // SMART: landscape video fits (letterboxed), portrait fills; unknown size fills.
        root("current").assert(SemanticsMatcher.expectValue(VideoPageTags.Fill, false))
        root("neighbor").assert(SemanticsMatcher.expectValue(VideoPageTags.Fill, true))
        root("unknown").assert(SemanticsMatcher.expectValue(VideoPageTags.Fill, true))
    }
}
