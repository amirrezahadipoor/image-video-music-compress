package com.compressly.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.compressly.CompresslyApp
import com.compressly.core.billing.BillingConnectionState
import ir.siliksama.hajmino.R
import com.compressly.ui.components.ActionButton
import com.compressly.ui.components.GhostButton
import com.compressly.ui.theme.GradientHero
import com.compressly.ui.theme.GradientSuccess
import com.compressly.ui.viewmodels.PremiumViewModel
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext

/**
 * Premium / حمایت مالی screen.
 *
 * Shows the benefits of the premium tier and initiates the Bazaar IAP flow.
 * Works with both the real Poolakey connection (bazaar flavor) and the
 * no-op fallback (play flavor / dev). On success the UI updates immediately
 * via StateFlow without requiring a restart.
 */
@Composable
fun PremiumScreen(
    onBack: () -> Unit,
    viewModel: PremiumViewModel = viewModel(
        factory = PremiumViewModel.factory(
            LocalContext.current.applicationContext as CompresslyApp
        )
    )
) {
    val context = LocalContext.current
    val isPremium    by viewModel.isPremium.collectAsStateWithLifecycle()
    val connState    by viewModel.connectionState.collectAsStateWithLifecycle()
    val isConnecting  = connState == BillingConnectionState.CONNECTING
    val isConnected   = connState == BillingConnectionState.CONNECTED
    val storeFailed   = connState == BillingConnectionState.FAILED

    LaunchedEffect(Unit) {
        context.findActivity()?.let { viewModel.connect(it) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Top bar ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
                Text(
                    text = stringResource(R.string.premium_screen_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Hero card ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(GradientHero))
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPremium) Icons.Outlined.Check else Icons.Outlined.Star,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (isPremium) stringResource(R.string.premium_already_title)
                               else stringResource(R.string.premium_hero_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (isPremium) stringResource(R.string.premium_already_desc)
                               else stringResource(R.string.premium_hero_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Benefits list ────────────────────────────────────────────────
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.premium_benefits_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                BenefitRow(Icons.Outlined.Block,      stringResource(R.string.premium_benefit_noads))
                BenefitRow(Icons.Outlined.AutoAwesome, stringResource(R.string.premium_benefit_smart))
                BenefitRow(Icons.Outlined.Speed,       stringResource(R.string.premium_benefit_batch))
                BenefitRow(Icons.Outlined.Lock,        stringResource(R.string.premium_benefit_offline))
            }

            Spacer(Modifier.height(32.dp))

            // ── CTA ──────────────────────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                when {
                    isPremium -> {
                        // Already premium — show a success state
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.premium_active_badge),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    isConnecting -> {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    storeFailed -> {
                        Text(
                            text = stringResource(R.string.premium_store_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        GhostButton(
                            text = stringResource(R.string.action_retry),
                            onClick = { viewModel.retryConnection(context.findActivity()) }
                        )
                    }
                    else -> {
                        ActionButton(
                            text = stringResource(R.string.premium_cta),
                            onClick = { viewModel.purchase(context.findActivity()) },
                            enabled = isConnected,
                            gradient = GradientHero
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.premium_one_time_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Walks a ContextWrapper chain up to the hosting Activity (Poolakey needs one). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
