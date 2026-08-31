package com.compressly.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.compressly.CompresslyApp
import com.compressly.AppContainer
import com.compressly.core.data.db.HistoryEntry
import com.compressly.core.engine.model.JobState
import com.compressly.core.engine.model.JobStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(container: AppContainer) : ViewModel() {

    private val historyRepository = container.historyRepository
    private val coordinator = container.jobCoordinator

    val totalSaved: StateFlow<Long> = historyRepository.totalSaved
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val recent: StateFlow<List<HistoryEntry>> = historyRepository.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeJobs: StateFlow<List<JobState>> = coordinator.jobs
        .map { jobs ->
            jobs.values.filter {
                it.status == JobStatus.RUNNING ||
                    it.status == JobStatus.PAUSED ||
                    it.status == JobStatus.CANCELLING
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isPremium: StateFlow<Boolean> = container.settingsRepository.isPremium
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CompresslyApp
                HomeViewModel(app.container)
            }
        }
    }
}
