package com.compressly.core.billing

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform-agnostic billing interface.
 * The bazaar flavor provides [PoolakeyBillingManager]; the play flavor can
 * plug in Google Play Billing. The UI only talks to this interface.
 */
interface BillingManager {

    /** true once a valid purchase is confirmed (persisted across sessions). */
    val isPremium: StateFlow<Boolean>

    /** Connection state so the UI can show a "store unavailable" message. */
    val connectionState: StateFlow<BillingConnectionState>

    /** Call from Activity.onCreate to open the billing service connection. */
    fun connect(activity: Activity)

    /**
     * Launch the Bazaar / Play purchase flow for the premium SKU.
     * [activity] must be the currently visible Activity.
     */
    fun purchasePremium(activity: Activity)

    /** Call from Activity.onDestroy to release the billing service. */
    fun disconnect()

    /** Re-verify any existing purchase (e.g. on app resume). */
    fun queryExistingPurchases()

    /**
     * Seed the in-memory flag from local storage so a paying user is still
     * treated as premium before the store connection comes back.
     */
    fun restoreLocalPremium(value: Boolean)
}

enum class BillingConnectionState { IDLE, CONNECTING, CONNECTED, FAILED }

/** No-op implementation used in the play build and in tests. */
class NoopBillingManager : BillingManager {
    private val _premium = MutableStateFlow(false)
    override val isPremium: StateFlow<Boolean> = _premium.asStateFlow()

    private val _conn = MutableStateFlow(BillingConnectionState.IDLE)
    override val connectionState: StateFlow<BillingConnectionState> = _conn.asStateFlow()

    override fun connect(activity: Activity) { _conn.value = BillingConnectionState.CONNECTED }
    override fun purchasePremium(activity: Activity) { /* no-op in play build */ }
    override fun disconnect() { _conn.value = BillingConnectionState.IDLE }
    override fun queryExistingPurchases() { /* no-op */ }

    override fun restoreLocalPremium(value: Boolean) {
        if (value) _premium.value = true
    }

    fun simulatePurchase() { _premium.value = true }
}
