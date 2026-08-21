package com.compressly

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.compressly.core.data.ThemeMode
import com.compressly.core.service.CompressionJobService
import com.compressly.ui.navigation.AppNavHost
import com.compressly.ui.navigation.NavRequest
import com.compressly.ui.navigation.Routes
import com.compressly.ui.theme.CompresslyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CompresslyApp).container

        setContent {
            val themeMode by container.settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            CompresslyTheme(themeMode = themeMode) {
                val navController = rememberNavController()
                HandleNavRequests(container, navController)
                AppNavHost(navController = navController)
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Notification taps route into the app via the navigation bus. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == CompressionJobService.ACTION_OPEN_JOB) {
            val jobId = intent.getLongExtra(CompressionJobService.EXTRA_JOB_ID, -1L)
            if (jobId != -1L) {
                (application as CompresslyApp).container.navigationBus.navigate(NavRequest.OpenJob(jobId))
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun HandleNavRequests(container: AppContainer, navController: NavHostController) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        container.navigationBus.requests.collect { request ->
            when (request) {
                is NavRequest.OpenJob -> navController.navigate(Routes.progress(request.jobId)) {
                    launchSingleTop = true
                    popUpTo(Routes.HOME) { inclusive = false }
                }
                is NavRequest.OpenEntry -> navController.navigate(Routes.result(request.entryId)) {
                    launchSingleTop = true
                }
            }
        }
    }
}
