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
    override fun isAvailable(): Boolean = true

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
        AndroidView(
            modifier = modifier.fillMaxWidth().wrapContentHeight(),
            factory = { context ->
                AdiveryBannerAdView(context).apply {
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
