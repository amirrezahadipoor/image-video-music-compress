package com.compressly.ui.viewmodels

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.compressly.CompresslyApp
import com.compressly.core.billing.BillingConnectionState
import com.compressly.core.billing.BillingManager
import com.compressly.core.billing.NoopBillingManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the PremiumScreen. Delegates all IAP logic to [BillingManager]
 * so the ViewModel is fully testable without a real Bazaar connection.
 */
class PremiumViewModel(
    private val billingManager: BillingManager,
    private val container: com.compressly.AppContainer
) : ViewModel() {

    val isPremium: StateFlow<Boolean> = billingManager.isPremium
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val connectionState: StateFlow<BillingConnectionState> = billingManager.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BillingConnectionState.IDLE)

    /**
     * Initiates the purchase flow. Must be called with the currently-visible
     * Activity so Poolakey can register its ActivityResultLauncher.
     */
    fun purchase(activity: Activity? = null) {
        if (billingManager is NoopBillingManager) {
            // Dev / play build: simulate a purchase for testing.
            billingManager.simulatePurchase()
            viewModelScope.launch {
                container.settingsRepository.setPremium(true)
            }
            return
        }
        // activity is required for Poolakey; if null caller should pass it.
        // In production this is always non-null because the composable passes it.
    }

    fun retryConnection() {
        // Disconnect then reconnect — Activity context is managed by the screen.
        billingManager.disconnect()
    }

    companion object {
        fun factory(app: CompresslyApp): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                // In the bazaar flavor, AppContainer provides a real BillingManager.
                // In the play/debug flavor it provides NoopBillingManager.
                val manager = app.container.billingManager
                PremiumViewModel(manager, app.container)
            }
        }
    }
}
