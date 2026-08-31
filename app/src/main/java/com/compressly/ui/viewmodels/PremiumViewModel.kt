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
import kotlinx.coroutines.flow.combine
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

    val isPremium: StateFlow<Boolean> = combine(
        billingManager.isPremium,
        container.settingsRepository.isPremium
    ) { billed, stored -> billed || stored }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val connectionState: StateFlow<BillingConnectionState> = billingManager.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BillingConnectionState.IDLE)

    /**
     * True when this build carries a real store billing implementation
     * (Poolakey in the bazaar flavor). The play/offline flavor must not offer
     * a purchase button that can never complete.
     */
    val storeBilling: Boolean = billingManager !is NoopBillingManager

    /**
     * Initiates the purchase flow. Must be called with the currently-visible
     * Activity so Poolakey can register its ActivityResultLauncher.
     */
    fun purchase(activity: Activity? = null) {
        if (billingManager is NoopBillingManager) {
            // Debug builds only. This used to run in every build, so the
            // published app handed out premium for free the moment anyone tapped
            // "buy" - no store call, no payment, straight to unlocked.
            if (ir.siliksama.hajmino.BuildConfig.DEBUG) {
                billingManager.simulatePurchase()
                viewModelScope.launch {
                    container.settingsRepository.setPremium(true)
                }
            }
            return
        }
        // A real store needs the foreground Activity to launch its purchase UI.
        // Without one there is nothing to launch, and silently doing nothing is
        // how this path went unnoticed.
        if (activity != null) {
            billingManager.purchasePremium(activity)
        } else {
            billingManager.queryExistingPurchases()
        }
    }

    fun connect(activity: Activity) {
        billingManager.connect(activity)
    }

    fun retryConnection(activity: Activity? = null) {
        billingManager.disconnect()
        if (activity != null) billingManager.connect(activity)
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
