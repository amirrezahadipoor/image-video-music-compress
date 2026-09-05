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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import ir.siliksama.hajmino.BuildConfig
import ir.siliksama.hajmino.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        // SNAP extra is a DEBUG-only CI/screenshot hook; inert in release.
        val snapScreen = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_SNAP_SCREEN) else null

        setContent {
            val themeMode by container.settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dynamicColor by container.settingsRepository.dynamicColor
                .collectAsStateWithLifecycle(initialValue = true)
            val language by container.settingsRepository.language
                .collectAsStateWithLifecycle(initialValue = LocaleHelper.lastApplied)

            // Apply language changes instantly by recreating the activity.
            androidx.compose.runtime.LaunchedEffect(language) {
                if (language != LocaleHelper.lastApplied) {
                    LocaleHelper.lastApplied = language
                    recreate()
                }
            }

            CompresslyTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                var showCrash by remember {
                    mutableStateOf(CrashGuard.consumeCrash(this@MainActivity))
                }
                if (showCrash) {
                    CrashRecoveryDialog(onDismiss = { showCrash = false })
                }
                val navController = rememberNavController()
                HandleNavRequests(container, navController)
                AppNavHost(navController = navController, initialSnapRoute = snapScreen)
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
        // SNAP_SCREEN is consumed in setContent (via `snapScreen`) so the
        // navigation happens AFTER the NavHost exists. Marking onboarding done
        // here is safe either way — a returning/new user without the flag is
        // unaffected and this only runs when the debug extra is present.
        //
        // DEBUG-GATE: these extras are a CI/screenshot test hook only. In a
        // RELEASE build they must be inert: MainActivity is exported (launcher),
        // so without this gate any other app could fire the intent and silently
        // mark onboarding as done / deep-navigate the user. The gate only
        // being in DEBUG keeps the release surface clean.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_SNAP_SKIP_ONBOARDING, false) == true) {
            val app = application as CompresslyApp
            lifecycleScope.launch { app.container.settingsRepository.markOnboardingDone() }
        }
        if (intent?.action == CompressionJobService.ACTION_OPEN_JOB) {
            val jobId = intent.getLongExtra(CompressionJobService.EXTRA_JOB_ID, -1L)
            if (jobId != -1L) {
                // NOTIF-DEAD-END-FIX: the RESULT notification used to deep-link
                // into the Progress screen unconditionally, but a finished job
                // is pruned from the coordinator's memory 3 minutes after it
                // ends — a later tap then landed on "job not found" with no way
                // back. Route by reality: a still-live job opens its progress
                // screen; a terminal (or already pruned) job opens its result
                // row from history; only when neither exists does the tap fall
                // back to the history list.
                lifecycleScope.launch {
                    val app = application as CompresslyApp
                    val job = withContext(Dispatchers.Default) {
                        app.container.jobCoordinator.job(jobId)
                    }
                    val terminal = job == null ||
                        job.status in terminalNotificationStatuses
                    val request = if (!terminal) {
                        NavRequest.OpenJob(jobId)
                    } else {
                        val entry = withContext(Dispatchers.IO) {
                            app.container.historyRepository.getFirstDoneByJob(jobId)
                        }
                        when {
                            entry != null -> NavRequest.OpenEntry(entry.id)
                            job != null -> NavRequest.OpenJob(jobId)
                            else -> NavRequest.OpenHistory
                        }
                    }
                    app.container.navigationBus.navigate(request)
                }
            }
            return
        }

        // SHARE-TARGET: the app can be the destination of a share. The user
        // picks us from any app's share sheet with a big media file / multiple
        // files; we compress them (and strip EXIF metadata) and hand back to a
        // fresh share. No new screen — it reuses the compression settings flow.
        handleSharedMedia(intent)
    }

    /**
     * Handles an incoming ACTION_SEND / ACTION_SEND_MULTIPLE by building a
     * selection from the shared URIs and routing into the compression flow.
     * Nothing happens if there is nothing shareable (never a crash).
     */
    private fun handleSharedMedia(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        @Suppress("DEPRECATION")
        val streams: List<android.net.Uri> = when (action) {
            Intent.ACTION_SEND ->
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                    ?.let { listOf(it) } ?: emptyList()
            else ->
                intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        }
        if (streams.isEmpty()) return

        val app = application as CompresslyApp
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                // ITEMID-FIX: the id used to be nanoTime + uri.hashCode(), a
                // Long that can go negative and is needlessly fragile; the
                // picker path already uses nanoTime + index — align it here.
                streams.mapIndexedNotNull { index, uri ->
                    val type = mediaTypeOf(app, uri)
                    if (type == null) return@mapIndexedNotNull null
                    com.compressly.core.engine.model.InputItem(
                        itemId = System.nanoTime() + index,
                        uri = uri,
                        displayName = com.compressly.core.util.Uris.displayNameOf(app, uri),
                        sizeBytes = com.compressly.core.util.Uris.sizeOf(app, uri),
                        mediaType = type
                    )
                }
            }
            // MIXED-SHARE-FIX: a share can carry mixed media types (photos next
            // to a video), but one compression run drives exactly one engine.
            // Sending the whole list under the FIRST item's type made every
            // other type fail inside that engine (a video item in a photo run
            // dies with a decode error). Keep only the items matching the first
            // item's type and say plainly how many were skipped.
            val type = items.firstOrNull()?.mediaType ?: return@launch
            val sameType = items.filter { it.mediaType == type }
            val skippedMixed = items.size - sameType.size
            if (skippedMixed > 0) {
                android.widget.Toast.makeText(
                    app,
                    app.getString(R.string.share_mixed_skipped, skippedMixed),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            app.container.selection.set(com.compressly.Selection(type, sameType))
            app.container.navigationBus.navigate(NavRequest.OpenSettings(type))
        }
    }

    /** Best-effort media-type classification from MIME or contentResolver. */
    private fun mediaTypeOf(context: Context, uri: android.net.Uri): com.compressly.core.engine.model.MediaType? {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: return null
        return when {
            mime.startsWith("image/") -> com.compressly.core.engine.model.MediaType.PHOTO
            mime.startsWith("video/") -> com.compressly.core.engine.model.MediaType.VIDEO
            mime.startsWith("audio/") -> com.compressly.core.engine.model.MediaType.AUDIO
            else -> null
        }
    }

    companion object {
        /** Debug-only intent extras used by the CI visual-regression pass. */
        const val EXTRA_SNAP_SCREEN = "com.compressly.extra.SNAP_SCREEN"
        const val EXTRA_SNAP_SKIP_ONBOARDING = "com.compressly.extra.SNAP_SKIP_ONBOARDING"

        /**
         * Statuses after which a job notification tap must never return to the
         * progress screen (the job is over; show its result instead).
         */
        private val terminalNotificationStatuses = setOf(
            com.compressly.core.engine.model.JobStatus.COMPLETED,
            com.compressly.core.engine.model.JobStatus.PARTIAL,
            com.compressly.core.engine.model.JobStatus.FAILED,
            com.compressly.core.engine.model.JobStatus.CANCELLED
        )
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
        container.navigationBus.pending.collect { request ->
            val req = request ?: return@collect
            // The NavHost may not have installed its graph yet on a cold start;
            // give it one frame, the same settle the screenshot hook waits for.
            if (navController.currentBackStackEntry == null) kotlinx.coroutines.delay(350)
            when (req) {
                is NavRequest.OpenJob -> navController.navigate(Routes.progress(req.jobId)) {
                    launchSingleTop = true
                    popUpTo(Routes.HOME) { inclusive = false }
                }
                is NavRequest.OpenEntry -> navController.navigate(Routes.result(req.entryId)) {
                    launchSingleTop = true
                }
                is NavRequest.OpenSettings -> navController.navigate(Routes.settings(req.mediaType.name)) {
                    launchSingleTop = true
                    popUpTo(Routes.HOME) { inclusive = false }
                }
                is NavRequest.OpenHistory -> navController.navigate(Routes.HISTORY) {
                    launchSingleTop = true
                }
            }
            container.navigationBus.consume(req)
        }
    }
}
