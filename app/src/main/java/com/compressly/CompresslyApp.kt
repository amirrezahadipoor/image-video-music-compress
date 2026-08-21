package com.compressly

import android.app.Application
import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.compressly.core.data.HistoryRepository
import com.compressly.core.data.SettingsRepository
import com.compressly.core.data.db.AppDatabase
import com.compressly.core.engine.model.InputItem
import com.compressly.core.engine.model.MediaType
import com.compressly.core.service.JobCoordinator
import com.compressly.core.service.NotificationHelper
import com.compressly.ui.navigation.NavRequest
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

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.createChannels(this)

        // Coil image loader with offline video-frame decoding for thumbnails.
        val imageLoader = ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(180)
            .build()
        Coil.setImageLoader(imageLoader)

        // Any job that was mid-flight when the process died is marked
        // interrupted with a clear message instead of leaving a mystery.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
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
