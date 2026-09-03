package com.compressly.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.compressly.CompresslyApp
import com.compressly.core.engine.model.MediaType
import com.compressly.ui.screens.AppSettingsScreen
import com.compressly.ui.screens.CompressionSettingsScreen
import com.compressly.ui.screens.HistoryScreen
import com.compressly.ui.screens.HomeScreen
import com.compressly.ui.screens.OnboardingScreen
import com.compressly.ui.screens.ProgressScreen
import com.compressly.ui.screens.PremiumScreen
import com.compressly.ui.screens.PrivacyPolicyScreen
import com.compressly.ui.screens.ResultScreen
import com.compressly.ui.screens.SupportScreen
import kotlinx.coroutines.launch

/**
 * App navigation. Core flow:
 * (Onboarding on first run) → Home → Settings → Progress → Result.
 * History and AppSettings are top-level destinations.
 *
 * Onboarding logic:
 * - DataStore emits false initially for new installs (never seen before).
 * - We use initialValue = null to distinguish "loading" from "done/not-done",
 *   preventing both the flash-to-onboarding for returning users and the
 *   skip-onboarding-entirely for new users.
 */
@Composable
fun AppNavHost(navController: NavHostController, initialSnapRoute: String? = null) {
    val context = LocalContext.current
    val container = (context.applicationContext as CompresslyApp).container
    val scope = rememberCoroutineScope()

    // null = still loading from DataStore; false = not done; true = done
    val onboardingDone by container.settingsRepository.onboardingDone
        .collectAsStateWithLifecycle(initialValue = null)

    // Don't render NavHost until we know the onboarding state.
    // This avoids a visible flash from HOME → ONBOARDING on first launch.
    if (onboardingDone == null) return

    // Debug/snapshot CI: once the NavHost is composed, jump straight to the
    // requested top-level screen (the capture runs without driving any
    // accessibility nodes). Uses the composition scope so the request is not
    // lost to the SharedFlow's replay=0 when it is emitted before the
    // collector subscribes.
    val snapDone = remember { mutableStateOf(false) }
    LaunchedEffect(initialSnapRoute, onboardingDone) {
        if (initialSnapRoute != null && !snapDone.value) {
            snapDone.value = true
            // Allow the start destination (and its transitions) to settle.
            kotlinx.coroutines.delay(350)
            navController.navigate(initialSnapRoute) {
                launchSingleTop = true
                popUpTo(navController.graph.startDestinationId) { inclusive = false }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (onboardingDone == true) Routes.HOME else Routes.ONBOARDING,
        enterTransition = {
            fadeIn(tween(220)) + slideInHorizontally(tween(280)) { it / 5 }
        },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = {
            fadeOut(tween(160)) + slideOutHorizontally(tween(280)) { it / 5 }
        }
    ) {

        // Onboarding — shown once on first launch, then never again.
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onDone = {
                    // Use the composition-scoped coroutine — no leak.
                    scope.launch {
                        container.settingsRepository.markOnboardingDone()
                    }
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { type -> navController.navigate(Routes.settings(type.name)) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenAppSettings = { navController.navigate(Routes.APP_SETTINGS) },
                onOpenPremium = { navController.navigate(Routes.PREMIUM) },
                onOpenSupport = { navController.navigate(Routes.SUPPORT) },
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
            AppSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenPrivacy = { navController.navigate(Routes.PRIVACY) }
            )
        }

        composable(Routes.SUPPORT) {
            SupportScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.PRIVACY) {
            PrivacyPolicyScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.PREMIUM) {
            PremiumScreen(onBack = { navController.popBackStack() })
        }
    }
}
