package com.compressly

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import ir.siliksama.hajmino.R
import com.compressly.core.data.ThemeMode
import com.compressly.core.service.CompressionJobService
import com.compressly.core.util.CrashGuard
import com.compressly.core.util.LocaleHelper
import com.compressly.ui.navigation.AppNavHost
import com.compressly.ui.navigation.NavRequest
import com.compressly.ui.navigation.Routes
import com.compressly.ui.theme.CompresslyTheme

class MainActivity : ComponentActivity() {

    /**
     * CRITICAL for the Persian-by-default requirement: Activities build their
     * own Context and ignore the Application's locale. Re-applying the
     * persisted language here (default "fa") guarantees the UI is Persian
     * regardless of the system language, until the user picks English.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            LocaleHelper.apply(newBase, LocaleHelper.persistedLanguage(newBase))
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CompresslyApp).container

        setContent {
            val themeMode by container.settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val language by container.settingsRepository.language
                .collectAsStateWithLifecycle(initialValue = LocaleHelper.lastApplied)

            // Apply language changes instantly by recreating the activity.
            androidx.compose.runtime.LaunchedEffect(language) {
                if (language != LocaleHelper.lastApplied) {
                    LocaleHelper.lastApplied = language
                    recreate()
                }
            }

            CompresslyTheme(themeMode = themeMode) {
                var showCrash by remember {
                    mutableStateOf(CrashGuard.consumeCrash(this@MainActivity))
                }
                if (showCrash) {
                    CrashRecoveryDialog(onDismiss = { showCrash = false })
                }
                val navController = rememberNavController()
                HandleNavRequests(container, navController)
                AppNavHost(navController = navController)
            }
        }

        handleIntent(intent)
        container.billingManager.connect(this)
    }

    override fun onResume() {
        super.onResume()
        (application as CompresslyApp).container.billingManager.queryExistingPurchases()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        // BILLING-LEAK-FIX: the Poolakey Payment object is constructed with
        // this Activity and stored in the app-lifetime billing manager. The
        // interface contract says to release it from onDestroy — without this
        // the first MainActivity instance leaks for the whole process
        // (bazaar flavor). connect() in onCreate re-establishes it on
        // recreation, and restoreLocalPremium keeps premium state in memory.
        runCatching { (application as CompresslyApp).container.billingManager.disconnect() }
        super.onDestroy()
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

/** Shown once after an unexpected crash: explains and lets the user continue. */
@Composable
private fun CrashRecoveryDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restart_after_error_title)) },
        text = { Text(stringResource(R.string.restart_after_error_desc)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        }
    )
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
