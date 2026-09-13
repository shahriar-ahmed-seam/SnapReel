package com.snapreel.app.navigation

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    const val VIEWER = "viewer/{folderUri}/{startIndex}"
    const val LANDSCAPE_VIEWER = "landscape_viewer/{folderUri}/{startIndex}"
    const val GRID = "grid/{folderUri}"
    const val SETTINGS = "settings"

    fun viewer(folderUri: String, startIndex: Int = 0) = "viewer/${Uri.encode(folderUri)}/$startIndex"
    fun landscapeViewer(folderUri: String, startIndex: Int = 0) = "landscape_viewer/${Uri.encode(folderUri)}/$startIndex"
    fun grid(folderUri: String) = "grid/${Uri.encode(folderUri)}"
}

@Composable
fun SnapReelNavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally(initialOffsetX = { it / 4 }) },
        exitTransition = { fadeOut(animationSpec = tween(300)) },
        popEnterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally(initialOffsetX = { -it / 4 }) },
        popExitTransition = { fadeOut(animationSpec = tween(300)) + slideOutHorizontally(targetOffsetX = { it / 4 }) }
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onFolderSelected = { uri ->
                    navController.navigate(Routes.grid(uri.toString()))
                },
                onPlaySelected = { uri, index, isLandscape ->
                    if (isLandscape) {
                        navController.navigate(Routes.landscapeViewer(uri.toString(), index))
                    } else {
                        navController.navigate(Routes.viewer(uri.toString(), index))
                    }
                },
                onSettingsClick = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(
            route = Routes.GRID,
            arguments = listOf(navArgument("folderUri") { type = NavType.StringType })
        ) { backStackEntry ->
            val folderUri = backStackEntry.arguments?.getString("folderUri")?.let {
                Uri.parse(it)
            }
            // Read the saved index reactively if we just returned from Viewer
            val lastViewedIndex by backStackEntry.savedStateHandle
                .getStateFlow<Int?>("lastViewedIndex", null)
                .collectAsState()

            if (folderUri != null) {
                FolderMediaGridScreen(
                    folderUri = folderUri,
                    returnedIndex = lastViewedIndex,
                    onBack = { navController.popBackStack() },
                    onMediaClick = { index, isLandscape ->
                        // Clear the returned index when moving forward
                        backStackEntry.savedStateHandle.remove<Int>("lastViewedIndex")
                        if (isLandscape) {
                            navController.navigate(Routes.landscapeViewer(folderUri.toString(), index))
                        } else {
                            navController.navigate(Routes.viewer(folderUri.toString(), index))
                        }
                    }
                )
            }
        }

        composable(
            route = Routes.VIEWER,
            arguments = listOf(
                navArgument("folderUri") { type = NavType.StringType },
                navArgument("startIndex") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val folderUri = backStackEntry.arguments?.getString("folderUri")?.let {
                Uri.parse(it)
            }
            val startIndex = backStackEntry.arguments?.getInt("startIndex") ?: 0
            if (folderUri != null) {
                ReelsViewerScreen(
                    folderUri = folderUri,
                    startIndex = startIndex,
                    onBack = { currentIndex -> 
                        navController.previousBackStackEntry?.savedStateHandle?.set("lastViewedIndex", currentIndex)
                        navController.popBackStack() 
                    }
                )
            }
        }

        composable(
            route = Routes.LANDSCAPE_VIEWER,
            arguments = listOf(
                navArgument("folderUri") { type = NavType.StringType },
                navArgument("startIndex") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val folderUri = backStackEntry.arguments?.getString("folderUri")?.let {
                Uri.parse(it)
            }
            val startIndex = backStackEntry.arguments?.getInt("startIndex") ?: 0
            if (folderUri != null) {
                LandscapeVideoViewerScreen(
                    folderUri = folderUri,
                    startIndex = startIndex,
                    onBack = { currentIndex -> 
                        navController.previousBackStackEntry?.savedStateHandle?.set("lastViewedIndex", currentIndex)
                        navController.popBackStack() 
                    }
                )
            }
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
