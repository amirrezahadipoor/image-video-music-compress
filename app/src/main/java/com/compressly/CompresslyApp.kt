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
import com.compressly.core.data.StorageRepository
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
import kotlinx.coroutines.flow.first
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
        // Plain app-lifetime scope (NOT ProcessLifecycleOwner — there is no
        // lifecycle dependency here). The SupervisorJob keeps the process
        // alive for the duration of the app, which is exactly the lifetime
        // these collectors need; nothing to cancel on process death.
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

        // Custom output folder (SAF tree) — mirror the persisted value into
        // OutputStore so background jobs see it without touching DataStore.
        appScope.launch {
            container.settingsRepository.outputTreeUri.collect { uri ->
                com.compressly.core.data.OutputStore.setCustomTreeUri(uri)
            }
        }
        appScope.launch {
            val premium = container.settingsRepository.isPremium.first()
            container.billingManager.restoreLocalPremium(premium)
        }
    }
}

/** Lightweight manual DI container (no framework needed for one screen tree). */
class AppContainer(app: Application) {

    private val context: Context = app.applicationContext

    val database: AppDatabase by lazy { AppDatabase.get(context) }
    val historyRepository: HistoryRepository by lazy { HistoryRepository(database.historyDao()) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(context) }
    val storageRepository: StorageRepository by lazy { StorageRepository(context) }
    val jobCoordinator: JobCoordinator by lazy { JobCoordinator(context, historyRepository) }
    val navigationBus: NavigationBus = NavigationBus()
    val selection: SelectionHolder = SelectionHolder()
    /**
     * Billing. The bazaar flavor ships [com.compressly.core.billing.PoolakeyBillingManager]
     * in its own source set, so main cannot reference it directly - it is resolved
     * by name, the same way Ads resolves its provider.
     *
     * This used to be a hardcoded NoopBillingManager with a comment claiming the
     * bazaar flavor "overrides" it. A `val by lazy` with an initializer cannot be
     * overridden by an extension, so PoolakeyBillingManager was never constructed
     * anywhere in the app and the real purchase flow was unreachable in every
     * build. Falls back to Noop when the flavor provides nothing.
     */
    val billingManager: BillingManager by lazy {
        runCatching {
            val clazz = Class.forName("com.compressly.core.billing.PoolakeyBillingManager")
            val repo = settingsRepository
            // suspend (Boolean) -> Unit erases to kotlin.jvm.functions.Function2.
            val persist: suspend (Boolean) -> Unit = { value -> repo.setPremium(value) }
            val ctor = clazz.getDeclaredConstructor(kotlin.jvm.functions.Function2::class.java)
            ctor.newInstance(persist) as BillingManager
        }.getOrDefault(NoopBillingManager())
    }
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
