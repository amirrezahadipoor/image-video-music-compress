package com.compressly

import android.app.Application
import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.compressly.core.billing.BillingManager
import com.compressly.core.billing.NoopBillingManager
import com.compressly.core.data.HistoryRepository
import com.compressly.core.data.SettingsRepository
import com.compressly.core.data.db.AppDatabase
import com.compressly.core.engine.model.InputItem
import com.compressly.core.engine.model.MediaType
import com.compressly.core.service.JobCoordinator
import com.compressly.core.service.NotificationHelper
import com.compressly.ui.navigation.NavRequest
import com.compressly.core.util.CrashGuard
import com.compressly.core.util.SoundEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CompresslyApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        // Apply the persisted language (Persian default) before any UI is built.
        super.attachBaseContext(
            com.compressly.core.util.LocaleHelper.apply(base, com.compressly.core.util.LocaleHelper.persistedLanguage(base))
        )
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.createChannels(this)
        CrashGuard.install(this)

        com.compressly.core.ads.Ads.provider.initialize(this)

        // Coil image loader with offline video-frame decoding for thumbnails.
        val imageLoader = ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(180)
            .build()
        Coil.setImageLoader(imageLoader)

        // Apply sound preference (default on) to the sound engine.
        // Use ProcessLifecycleOwner so the scope is tied to the app lifecycle
        // and gets cancelled when the process dies (no coroutine leak).
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        appScope.launch {
            container.settingsRepository.soundEnabled.collect { enabled ->
                SoundEffects.enabled = enabled
            }
        }

        // Any job that was mid-flight when the process died is marked
        // interrupted with a clear message instead of leaving a mystery.
        appScope.launch {
            container.historyRepository.markInterruptedOnStartup()
        }
    }
}

/** Lightweight manual DI container (no framework needed for one screen tree). */
class AppContainer(app: Application) {

    private val context: Context = app.applicationContext

    val database: AppDatabase by lazy { AppDatabase.get(context) }
    val historyRepository: HistoryRepository by lazy { HistoryRepository(database.historyDao()) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(context) }
    val jobCoordinator: JobCoordinator by lazy { JobCoordinator(context, historyRepository) }
    val navigationBus: NavigationBus = NavigationBus()
    val selection: SelectionHolder = SelectionHolder()
    // Billing: NoopBillingManager in play/debug; the bazaar flavor overrides this
    // by providing PoolakeyBillingManager via a flavor-specific AppContainer extension.
    val billingManager: BillingManager by lazy { NoopBillingManager() }
}

/** Files chosen in the picker, held until the settings screen consumes them. */
class SelectionHolder {
    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    fun set(selection: Selection?) {
        _selection.value = selection
    }
}

data class Selection(
    val mediaType: MediaType,
    val items: List<InputItem>
)

/** Cross-cutting navigation requests (e.g. notification taps). */
class NavigationBus {
    private val _requests = MutableSharedFlow<NavRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<NavRequest> = _requests.asSharedFlow()

    fun navigate(request: NavRequest) {
        _requests.tryEmit(request)
    }
}
