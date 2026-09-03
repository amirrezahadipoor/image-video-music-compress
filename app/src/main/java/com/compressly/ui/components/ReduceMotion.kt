package com.compressly.ui.components

import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Respect the user's system "reduce motion" preference (animator scale == 0 or
 * the accessibility "remove animations" toggle on Android 12+). Long-lived
 * infinite animations (background blobs, shimmer sweep, heartbeat, pulsing
 * dots) are the ones that can be genuinely unpleasant for motion-sensitive
 * users, so they check this and degrade to a static, non-animated state.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return runCatching {
        // Android 12+: accessibility setting exists a dedicated switch.
        val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && am != null && am.isEnabled) {
            val accel = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            // When animations are off system-wide, animator scale is 0.
            if (accel == 0f) return@runCatching true
        }
        false
    }.getOrDefault(false)
}
