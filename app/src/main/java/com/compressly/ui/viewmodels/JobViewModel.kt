package com.compressly.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.compressly.CompresslyApp
import com.compressly.AppContainer
import com.compressly.core.engine.model.JobState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Drives the Progress screen for one job. */
class JobViewModel(container: AppContainer, private val jobId: Long) : ViewModel() {

    private val coordinator = container.jobCoordinator

    val job: StateFlow<JobState?> = coordinator.jobs
        .map { it[jobId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun pause() = coordinator.pause(jobId)
    fun resume() = coordinator.resume(jobId)
    fun cancel() = coordinator.cancel(jobId)
    fun cancelItem(itemId: Long) = coordinator.cancelItem(jobId, itemId)

    companion object {
        fun factory(app: CompresslyApp, jobId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                JobViewModel(app.container, jobId) as T
        }
    }
}
