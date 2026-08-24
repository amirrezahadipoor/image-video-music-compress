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

class AdiveryAdsProvider : AdsProvider {
    override fun isAvailable(): Boolean = true

    override fun initialize(app: Application) {
        try {
            Adivery.configure(app, "4d3dfc77-e8aa-409b-aa24-8f0b1bad9fe3")
            Log.d("Ads", "Adivery SDK configured.")
        } catch (e: Exception) {
            Log.e("Ads", "Adivery config failed", e)
        }
    }

    @Composable
    override fun AdBanner(modifier: Modifier) {
        AndroidView(
            modifier = modifier.fillMaxWidth().wrapContentHeight(),
            factory = { context ->
                AdiveryBannerAdView(context).apply {
                    setBannerAdListener(object : AdiveryAdListener() {
                        override fun onAdLoaded() {
                            Log.d("Ads", "Adivery banner loaded.")
                        }
                        override fun onError(reason: String) {
                            Log.e("Ads", "Adivery banner failed: $reason")
                        }
                    })
                    loadAd("28f7964a-6cbf-4f7b-897c-96465a4a72bb")
                }
            }
        )
    }
}
