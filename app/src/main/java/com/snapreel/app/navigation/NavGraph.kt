package com.snapreel.app.navigation

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.snapreel.app.ui.home.HomeScreen
import com.snapreel.app.ui.settings.SettingsScreen
import com.snapreel.app.ui.viewer.FolderMediaGridScreen
import com.snapreel.app.ui.viewer.ReelsViewerScreen

object Routes {
    const val HOME = "home"
    const val VIEWER = "viewer/{folderUri}/{startIndex}"
    const val GRID = "grid/{folderUri}"
    const val SETTINGS = "settings"

    fun viewer(folderUri: String, startIndex: Int = 0) = "viewer/${Uri.encode(folderUri)}/$startIndex"
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
                onPlaySelected = { uri, index ->
                    navController.navigate(Routes.viewer(uri.toString(), index))
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
            if (folderUri != null) {
                FolderMediaGridScreen(
                    folderUri = folderUri,
                    onBack = { navController.popBackStack() },
                    onMediaClick = { index ->
                        navController.navigate(Routes.viewer(folderUri.toString(), index))
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
                    onBack = { navController.popBackStack() }
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
