package com.compressly.core.ads

/**
 * Tapsell (تپسل) provider for the Cafe Bazaar build.
 *
 * This is the integration point for the Tapsell Plus SDK:
 *   1. Add the SDK dependency to the "bazaar" flavor in app/build.gradle.kts
 *      (ir.tapsell.plus / ir.tapsell.plus:tapsell-plus or your ad unit SDK).
 *   2. Add the Tapsell zone/ad-unit IDs for the three slots in the app.
 *   3. Implement [isAvailable] and load/render banners into the AdSlot
 *      containers (Home, History, Result) from this class.
 *
 * Until the SDK is wired, the build stays 100% offline and slots show the
 * labeled placeholder.
 */
class TapsellAdsProvider : AdsProvider {
    override fun isAvailable(): Boolean = false
}
