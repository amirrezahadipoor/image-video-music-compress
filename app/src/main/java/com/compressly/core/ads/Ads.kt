package com.compressly.core.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ir.siliksama.hajmino.BuildConfig

/**
 * Abstraction over the ad provider (Adivery for the Bazaar build).
 * The app itself stays fully offline-capable: when no provider is available
 * or there is no network, slots render as a subtle placeholder or nothing.
 */
interface AdsProvider {
    /** True once a real ad network is integrated and can serve banners. */
    fun isAvailable(): Boolean
    fun initialize(app: android.app.Application) {}
    
    @Composable
    fun AdBanner(modifier: Modifier) {}
}

/** Default provider: no network, no ads — the offline-first behavior. */
class NoopAdsProvider : AdsProvider {
    override fun isAvailable(): Boolean = false
    
    @Composable
    override fun AdBanner(modifier: Modifier) {}
}

object Ads {

    val provider: AdsProvider by lazy { create() }

    private fun create(): AdsProvider {
        if (!BuildConfig.ADS_ENABLED) return NoopAdsProvider()
        // The Adivery provider lives only in the "bazaar" flavor source set.
        return runCatching {
            val clazz = Class.forName("com.compressly.core.ads.AdiveryAdsProvider")
            clazz.getDeclaredConstructor().newInstance() as AdsProvider
        }.getOrDefault(NoopAdsProvider())
    }

    /**
     * True when this build is prepared for ads (Bazaar flavor). The slot is
     * rendered so the advertising space is visible; the real banner loads
     * from the network only when the Adivery SDK is integrated.
     */
    fun isSlotVisible(): Boolean = BuildConfig.ADS_ENABLED
}
