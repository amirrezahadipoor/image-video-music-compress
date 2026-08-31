package com.compressly.core.billing

import android.app.Activity
import androidx.activity.ComponentActivity
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.ConnectionState
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.request.PurchaseRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Cafe Bazaar in-app billing via Poolakey 2.2.0.
 *
 * HOW TO SET UP:
 * 1. Open your app page in the Cafe Bazaar developer panel.
 * 2. Go to "In-App Billing" → copy your RSA public key.
 * 3. Create a product with SKU = PREMIUM_SKU (see constant below).
 * 4. Replace BAZAAR_RSA_PUBLIC_KEY placeholder with your real key.
 *
 * This implementation handles:
 * - Connection lifecycle (connect / disconnect)
 * - One-time premium purchase (non-consumable)
 * - Purchase query on startup (so reinstalls restore premium)
 * - All error paths (cancelled, failed, store not installed)
 */
class PoolakeyBillingManager(
    private val persistPremium: suspend (Boolean) -> Unit
) : BillingManager {

    companion object {
        /**
         * SKU identifier defined in the Bazaar developer panel.
         * Change this to match the product ID you created there.
         */
        const val PREMIUM_SKU = "premium_lifetime"
    }

    private val _premium = MutableStateFlow(false)
    override val isPremium: StateFlow<Boolean> = _premium.asStateFlow()

    private val _conn = MutableStateFlow(BillingConnectionState.IDLE)
    override val connectionState: StateFlow<BillingConnectionState> = _conn.asStateFlow()

    // Use a stable, supervised scope instead of MainScope() to avoid leaks.
    // This scope lives as long as the BillingManager object (app lifetime).
    private val billingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var payment: Payment? = null
    private var connection: Connection? = null

    override fun connect(activity: Activity) {
        if (_conn.value == BillingConnectionState.CONNECTED) return
        _conn.value = BillingConnectionState.CONNECTING

        val rsa = ir.siliksama.hajmino.BuildConfig.BAZAAR_RSA_KEY
        val config = PaymentConfiguration(
            localSecurityCheck = if (rsa.isNotBlank())
                SecurityCheck.Enable(rsaPublicKey = rsa)
            else
                SecurityCheck.Disable
        )

        val p = Payment(context = activity, config = config)
        payment = p

        connection = p.connect {
            connectionSucceed {
                _conn.value = BillingConnectionState.CONNECTED
                // Verify existing purchases immediately so reinstalls restore premium.
                queryExistingPurchasesInternal(p)
            }
            connectionFailed {
                _conn.value = BillingConnectionState.FAILED
            }
            disconnected {
                _conn.value = BillingConnectionState.IDLE
            }
        }
    }

    override fun purchasePremium(activity: Activity) {
        val p = payment ?: return
        if (_conn.value != BillingConnectionState.CONNECTED) return
        // activityResultRegistry is only available on ComponentActivity (AppCompat/Compose).
        val componentActivity = activity as? ComponentActivity ?: return

        p.purchaseProduct(
            registry = componentActivity.activityResultRegistry,
            request = PurchaseRequest(
                productId = PREMIUM_SKU,
                payload = "premium_payload_${System.currentTimeMillis()}"
            )
        ) {
            purchaseFlowBegan { /* Bazaar bottom sheet is now showing */ }
            failedToBeginFlow {
                _conn.value = BillingConnectionState.FAILED
            }
            purchaseSucceed { _ ->
                _premium.value = true
                billingScope.launch { persistPremium(true) }
            }
            purchaseCanceled { /* user tapped back — no action */ }
            purchaseFailed { /* network error — state unchanged */ }
        }
    }

    override fun queryExistingPurchases() {
        payment?.let { queryExistingPurchasesInternal(it) }
    }

    private fun queryExistingPurchasesInternal(p: Payment) {
        p.getPurchasedProducts {
            querySucceed { purchasedItems ->
                val hasPremium = purchasedItems.any { it.productId == PREMIUM_SKU }
                if (hasPremium && !_premium.value) {
                    _premium.value = true
                    billingScope.launch { persistPremium(true) }
                }
            }
            queryFailed { /* non-fatal — DataStore state still valid */ }
        }
    }

    override fun restoreLocalPremium(value: Boolean) {
        if (value) _premium.value = true
    }

    override fun disconnect() {
        runCatching { connection?.disconnect() }
        connection = null
        payment = null
        _conn.value = BillingConnectionState.IDLE
    }
}
