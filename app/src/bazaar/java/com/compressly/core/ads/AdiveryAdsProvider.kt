package com.compressly.core.ads

import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.adivery.sdk.Adivery
import com.adivery.sdk.AdiveryBannerAdView
import com.adivery.sdk.AdiveryAdListener
import ir.siliksama.hajmino.BuildConfig

class AdiveryAdsProvider : AdsProvider {

    /**
     * D3: this used to be a hardcoded `true`, so AdSlot always took the
     * "real banner" branch and rendered an empty AndroidView (no placeholder,
     * no ad) whenever the ids were not configured. Availability now means the
     * SDK has ids to work with.
     */
    override fun isAvailable(): Boolean =
        BuildConfig.ADIVERY_APP_ID.isNotBlank() && BuildConfig.ADIVERY_BANNER_ID.isNotBlank()

    override fun initialize(app: Application) {
        try {
            // ADIDS-FIX: app id comes from BuildConfig (set per-flavor in
            // build.gradle.kts, overridable via env / gradle.properties)
            // instead of being hardcoded in this class.
            val appId = BuildConfig.ADIVERY_APP_ID
            if (appId.isBlank()) {
                Log.e("Ads", "Adivery app id missing in BuildConfig — banner disabled.")
                return
            }
            Adivery.configure(app, appId)
        } catch (e: Exception) {
            Log.e("Ads", "Adivery config failed", e)
        }
    }

    @Composable
    override fun AdBanner(modifier: Modifier) {
        // No debug logging on the hot composition path; errors are reported
        // through the listener below.
        val bannerId = BuildConfig.ADIVERY_BANNER_ID
        if (bannerId.isBlank()) return
        // D4: the banner view kept its listener/loader alive after the slot left
        // the composition. Destroy it so a recycled screen does not leak it.
        val banner = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<AdiveryBannerAdView?>(null) }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { runCatching { banner.value?.destroy() }; banner.value = null }
        }
        AndroidView(
            modifier = modifier.fillMaxWidth().wrapContentHeight(),
            factory = { context ->
                AdiveryBannerAdView(context).apply {
                    banner.value = this
                    setBannerAdListener(object : AdiveryAdListener() {
                        // Override present for interface compatibility; no
                        // logging on the hot path (the old Log.d fired on
                        // every banner load).
                        override fun onAdLoaded() = Unit
                        override fun onError(reason: String) {
                            Log.e("Ads", "Adivery banner failed: $reason")
                        }
                    })
                    loadAd(bannerId)
                }
            }
        )
    }
}
