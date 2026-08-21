package com.compressly.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.compressly.core.engine.model.MediaType
import com.compressly.ui.screens.AppSettingsScreen
import com.compressly.ui.screens.CompressionSettingsScreen
import com.compressly.ui.screens.HistoryScreen
import com.compressly.ui.screens.HomeScreen
import com.compressly.ui.screens.ProgressScreen
import com.compressly.ui.screens.ResultScreen

/**
 * App navigation. The core flow is:
 * Home -> (pick files) -> Settings -> Progress -> Result.
 * History and App Settings are top-level destinations.
 */
@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = {
            fadeIn(tween(220)) + slideInHorizontally(tween(280)) { it / 5 }
        },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = {
            fadeOut(tween(160)) + slideOutHorizontally(tween(280)) { it / 5 }
        }
    ) {

        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { type -> navController.navigate(Routes.settings(type.name)) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenAppSettings = { navController.navigate(Routes.APP_SETTINGS) },
                onOpenJob = { jobId ->
                    navController.navigate(Routes.progress(jobId)) {
                        popUpTo(Routes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onOpenEntry = { entryId -> navController.navigate(Routes.result(entryId)) }
            )
        }

        composable(
            route = Routes.SETTINGS_PATTERN,
            arguments = listOf(navArgument("type") { type = NavType.StringType })
        ) { backStackEntry ->
            val type = MediaType.fromName(backStackEntry.arguments?.getString("type") ?: "PHOTO")
            CompressionSettingsScreen(
                mediaType = type,
                onBack = { navController.popBackStack() },
                onJobStarted = { jobId ->
                    navController.navigate(Routes.progress(jobId)) {
                        popUpTo(Routes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(
            route = Routes.PROGRESS_PATTERN,
            arguments = listOf(navArgument("jobId") { type = NavType.LongType })
        ) { backStackEntry ->
            val jobId = backStackEntry.arguments?.getLong("jobId") ?: return@composable
            ProgressScreen(
                jobId = jobId,
                onBack = { navController.popBackStack(Routes.HOME, false) },
                onResult = { entryId ->
                    navController.navigate(Routes.result(entryId)) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                },
                onHistory = {
                    navController.navigate(Routes.HISTORY) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                }
            )
        }

        composable(
            route = Routes.RESULT_PATTERN,
            arguments = listOf(navArgument("entryId") { type = NavType.LongType })
        ) { backStackEntry ->
            val entryId = backStackEntry.arguments?.getLong("entryId") ?: return@composable
            ResultScreen(
                entryId = entryId,
                onBack = { navController.popBackStack(Routes.HOME, false) },
                onCompressAnother = { navController.popBackStack(Routes.HOME, false) },
                onHistory = {
                    navController.navigate(Routes.HISTORY) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                }
            )
        }

        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenEntry = { id -> navController.navigate(Routes.result(id)) }
            )
        }

        composable(Routes.APP_SETTINGS) {
            AppSettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
