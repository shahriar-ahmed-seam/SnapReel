package com.snapreel.app.navigation

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.snapreel.app.ui.home.HomeScreen
import com.snapreel.app.ui.settings.SettingsScreen
import com.snapreel.app.ui.viewer.FolderMediaGridScreen
import com.snapreel.app.ui.viewer.ReelsViewerScreen
import com.snapreel.app.ui.viewer.landscape.LandscapeVideoViewerScreen

object Routes {
    const val HOME = "home"
    const val VIEWER = "viewer/{folderUri}/{startIndex}?fresh={fresh}"
    const val LANDSCAPE_VIEWER = "landscape_viewer/{folderUri}/{startIndex}?fresh={fresh}"
    const val GRID = "grid/{folderUri}"
    const val SETTINGS = "settings"

    /**
     * [fresh] = true rescans the folder and resolves the start item by its saved URI (Home's play
     * button). false reads the snapshot the grid just scanned (grid taps).
     */
    fun viewer(folderUri: String, startIndex: Int = 0, fresh: Boolean = false) =
        "viewer/${Uri.encode(folderUri)}/$startIndex?fresh=$fresh"
    fun landscapeViewer(folderUri: String, startIndex: Int = 0, fresh: Boolean = false) =
        "landscape_viewer/${Uri.encode(folderUri)}/$startIndex?fresh=$fresh"
    fun grid(folderUri: String) = "grid/${Uri.encode(folderUri)}"
}

/** The screen composables the graph hosts. A test seam: tests swap in lightweight screens. */
interface SnapReelScreens {
    @Composable
    fun Home(
        onFolderSelected: (Uri) -> Unit,
        onPlaySelected: (Uri, Int, Boolean) -> Unit,
        onSettingsClick: () -> Unit,
    )

    @Composable
    fun Grid(
        folderUri: Uri,
        returnedIndex: Int?,
        onBack: () -> Unit,
        onMediaClick: (Int, Boolean) -> Unit,
        onOpenOtherFolder: (Uri) -> Unit,
    )

    @Composable
    fun Viewer(folderUri: Uri, startIndex: Int, fresh: Boolean, onBack: (Int) -> Unit, onOpenOtherFolder: (Uri) -> Unit)

    @Composable
    fun LandscapeViewer(folderUri: Uri, startIndex: Int, fresh: Boolean, onBack: (Int) -> Unit, onOpenOtherFolder: (Uri) -> Unit)

    @Composable
    fun Settings(onBack: () -> Unit)
}

/** The app's real screens. */
object AppScreens : SnapReelScreens {
    @Composable
    override fun Home(
        onFolderSelected: (Uri) -> Unit,
        onPlaySelected: (Uri, Int, Boolean) -> Unit,
        onSettingsClick: () -> Unit,
    ) = HomeScreen(onFolderSelected = onFolderSelected, onPlaySelected = onPlaySelected, onSettingsClick = onSettingsClick)

    @Composable
    override fun Grid(
        folderUri: Uri,
        returnedIndex: Int?,
        onBack: () -> Unit,
        onMediaClick: (Int, Boolean) -> Unit,
        onOpenOtherFolder: (Uri) -> Unit,
    ) = FolderMediaGridScreen(
        folderUri = folderUri,
        returnedIndex = returnedIndex,
        onBack = onBack,
        onMediaClick = onMediaClick,
        onOpenOtherFolder = onOpenOtherFolder,
    )

    @Composable
    override fun Viewer(folderUri: Uri, startIndex: Int, fresh: Boolean, onBack: (Int) -> Unit, onOpenOtherFolder: (Uri) -> Unit) =
        ReelsViewerScreen(
            folderUri = folderUri,
            startIndex = startIndex,
            fresh = fresh,
            onBack = onBack,
            onOpenOtherFolder = onOpenOtherFolder,
        )

    @Composable
    override fun LandscapeViewer(folderUri: Uri, startIndex: Int, fresh: Boolean, onBack: (Int) -> Unit, onOpenOtherFolder: (Uri) -> Unit) =
        LandscapeVideoViewerScreen(
            folderUri = folderUri,
            startIndex = startIndex,
            fresh = fresh,
            onBack = onBack,
            onOpenOtherFolder = onOpenOtherFolder,
        )

    @Composable
    override fun Settings(onBack: () -> Unit) = SettingsScreen(onBack = onBack)
}

private const val LAST_VIEWED_INDEX = "lastViewedIndex"

private fun NavBackStackEntry.folderUriArg(): Uri? = arguments?.getString("folderUri")?.let { Uri.parse(it) }

/**
 * Every navigate/pop goes through the guard with the composable's own back-stack entry, so a burst
 * of taps before the next frame acts once, and Home is never popped.
 *
 * @param navController a test seam (`TestNavHostController`); the app uses the default.
 * @param screens a test seam; the app uses [AppScreens].
 */
@Composable
fun SnapReelNavGraph(
    navController: NavHostController = rememberNavController(),
    screens: SnapReelScreens = AppScreens,
) {
    // Re-pick of a different folder: open its grid directly above Home.
    fun openOtherFolder(from: NavBackStackEntry, uri: Uri) {
        navController.navigateFrom(from, Routes.grid(uri.toString())) {
            popUpTo(Routes.HOME)
        }
    }

    // A viewer's back: the returned index goes to the entry below, only if the pop will happen.
    fun viewerBack(from: NavBackStackEntry, currentIndex: Int) {
        navController.popFrom(from) { previous ->
            previous.savedStateHandle[LAST_VIEWED_INDEX] = currentIndex
        }
    }

    val viewerArguments = listOf(
        navArgument("folderUri") { type = NavType.StringType },
        navArgument("startIndex") { type = NavType.IntType },
        navArgument("fresh") {
            type = NavType.BoolType
            defaultValue = false
        },
    )

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally(initialOffsetX = { it / 4 }) },
        exitTransition = { fadeOut(animationSpec = tween(300)) },
        popEnterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally(initialOffsetX = { -it / 4 }) },
        popExitTransition = { fadeOut(animationSpec = tween(300)) + slideOutHorizontally(targetOffsetX = { it / 4 }) }
    ) {
        composable(Routes.HOME) { entry ->
            screens.Home(
                onFolderSelected = { uri ->
                    navController.navigateFrom(entry, Routes.grid(uri.toString()))
                },
                onPlaySelected = { uri, index, isLandscape ->
                    val route = if (isLandscape) {
                        Routes.landscapeViewer(uri.toString(), index, fresh = true)
                    } else {
                        Routes.viewer(uri.toString(), index, fresh = true)
                    }
                    navController.navigateFrom(entry, route)
                },
                onSettingsClick = {
                    navController.navigateFrom(entry, Routes.SETTINGS)
                }
            )
        }

        composable(
            route = Routes.GRID,
            arguments = listOf(navArgument("folderUri") { type = NavType.StringType })
        ) { entry ->
            val folderUri = entry.folderUriArg()
            // Read the saved index reactively if we just returned from Viewer
            val lastViewedIndex by entry.savedStateHandle
                .getStateFlow<Int?>(LAST_VIEWED_INDEX, null)
                .collectAsState()

            if (folderUri != null) {
                screens.Grid(
                    folderUri = folderUri,
                    returnedIndex = lastViewedIndex,
                    onBack = { navController.popFrom(entry) },
                    onMediaClick = { index, isLandscape ->
                        if (navController.canNavigateFrom(entry)) {
                            // Clear the returned index when moving forward
                            entry.savedStateHandle.remove<Int>(LAST_VIEWED_INDEX)
                            val route = if (isLandscape) {
                                Routes.landscapeViewer(folderUri.toString(), index, fresh = false)
                            } else {
                                Routes.viewer(folderUri.toString(), index, fresh = false)
                            }
                            navController.navigateFrom(entry, route)
                        }
                    },
                    onOpenOtherFolder = { uri -> openOtherFolder(entry, uri) }
                )
            }
        }

        composable(route = Routes.VIEWER, arguments = viewerArguments) { entry ->
            val folderUri = entry.folderUriArg()
            val startIndex = entry.arguments?.getInt("startIndex") ?: 0
            val fresh = entry.arguments?.getBoolean("fresh") ?: false
            if (folderUri != null) {
                screens.Viewer(
                    folderUri = folderUri,
                    startIndex = startIndex,
                    fresh = fresh,
                    onBack = { currentIndex -> viewerBack(entry, currentIndex) },
                    onOpenOtherFolder = { uri -> openOtherFolder(entry, uri) }
                )
            }
        }

        composable(route = Routes.LANDSCAPE_VIEWER, arguments = viewerArguments) { entry ->
            val folderUri = entry.folderUriArg()
            val startIndex = entry.arguments?.getInt("startIndex") ?: 0
            val fresh = entry.arguments?.getBoolean("fresh") ?: false
            if (folderUri != null) {
                screens.LandscapeViewer(
                    folderUri = folderUri,
                    startIndex = startIndex,
                    fresh = fresh,
                    onBack = { currentIndex -> viewerBack(entry, currentIndex) },
                    onOpenOtherFolder = { uri -> openOtherFolder(entry, uri) }
                )
            }
        }

        composable(Routes.SETTINGS) { entry ->
            screens.Settings(
                onBack = { navController.popFrom(entry) }
            )
        }
    }
}
