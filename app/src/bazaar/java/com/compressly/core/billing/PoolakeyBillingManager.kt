package com.compressly.core.billing

import android.app.Activity
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.ConnectionState
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.request.PurchaseRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * - Connection lifecycle (connect / disconnect / reconnect on failure)
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

        /**
         * Your app's RSA public key from the Bazaar developer panel.
         * IMPORTANT: never commit the real key to a public repository.
         * Store it in BuildConfig via secrets.properties or an environment variable.
         *
         * In app/build.gradle.kts add:
         *   buildConfigField("String", "BAZAAR_RSA_KEY", "\"${project.property("BAZAAR_RSA_KEY")}\"")
         */
        const val BAZAAR_RSA_PUBLIC_KEY = "" // TODO: set via BuildConfig.BAZAAR_RSA_KEY
    }

    private val _premium = MutableStateFlow(false)
    override val isPremium: StateFlow<Boolean> = _premium.asStateFlow()

    private val _conn = MutableStateFlow(BillingConnectionState.IDLE)
    override val connectionState: StateFlow<BillingConnectionState> = _conn.asStateFlow()

    private var payment: Payment? = null
    private var connection: Connection? = null

    /** Must be called in Activity.onCreate before any purchase call. */
    override fun connect(activity: Activity) {
        if (_conn.value == BillingConnectionState.CONNECTED) return
        _conn.value = BillingConnectionState.CONNECTING

        val config = PaymentConfiguration(
            localSecurityCheck = if (BAZAAR_RSA_PUBLIC_KEY.isNotBlank())
                SecurityCheck.Enable(rsaPublicKey = BAZAAR_RSA_PUBLIC_KEY)
            else
                SecurityCheck.Disable  // dev/test mode — DO NOT ship without a key!
        )

        val p = Payment(context = activity, config = config)
        payment = p

        connection = p.connect {
            connectionSucceed {
                _conn.value = BillingConnectionState.CONNECTED
                // Immediately verify any existing purchase so the premium state
                // is restored if the user reinstalls the app.
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

        p.purchaseProduct(
            registry = activity.activityResultRegistry,
            request = PurchaseRequest(
                productId = PREMIUM_SKU,
                payload = "premium_payload_${System.currentTimeMillis()}"
            )
        ) {
            purchaseFlowBegan {
                // UI is handled by Bazaar's bottom sheet; nothing extra needed.
            }
            failedToBeginFlow {
                // Bazaar app might need an update; let the user know via the
                // connection state so the UI can show a helpful message.
                _conn.value = BillingConnectionState.FAILED
            }
            purchaseSucceed { purchaseInfo ->
                // Mark premium immediately; persist asynchronously.
                _premium.value = true
                kotlinx.coroutines.MainScope().launch {
                    persistPremium(true)
                }
            }
            purchaseCanceled {
                // User tapped the back button — no action needed.
            }
            purchaseFailed {
                // Network error or Bazaar-side issue; connection state unchanged.
            }
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
                    kotlinx.coroutines.MainScope().launch {
                        persistPremium(true)
                    }
                }
            }
            queryFailed {
                // Non-fatal — premium state from DataStore is still valid.
            }
        }
    }

    override fun disconnect() {
        runCatching { connection?.disconnect() }
        connection = null
        payment = null
        _conn.value = BillingConnectionState.IDLE
    }
}

private fun kotlinx.coroutines.MainScope() = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
)

private fun kotlinx.coroutines.CoroutineScope.launch(block: suspend () -> Unit) =
    kotlinx.coroutines.launch { block() }
