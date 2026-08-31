package com.compressly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compressly.CompresslyApp
import com.compressly.core.ads.Ads
import ir.siliksama.hajmino.R
import kotlinx.coroutines.flow.flowOf

/**
 * Advertising slot. Hidden on the Play/offline build, and hidden for
 * premium users. On the Bazaar build the real banner loads when the SDK
 * is available; otherwise a quiet placeholder keeps the layout stable.
 */
@Composable
fun AdSlot(modifier: Modifier = Modifier, isPremium: Boolean = false) {
    val context = LocalContext.current
    val storedPremium by run {
        val repo = (context.applicationContext as? CompresslyApp)?.container?.settingsRepository
        (repo?.isPremium ?: flowOf(false)).let { flow ->
            flow.collectAsStateWithLifecycle(initialValue = false)
        }
    }
    if (isPremium || storedPremium || !Ads.isSlotVisible()) return
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (Ads.provider.isAvailable()) {
            Ads.provider.AdBanner(Modifier.padding(4.dp))
        } else {
            Row(modifier = Modifier.padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ads_placeholder),
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ads_slot_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
