package com.compressly.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.compressly.ui.components.ActionButton
import com.compressly.ui.components.BeatingHeart
import com.compressly.ui.theme.GradientPrimary
import ir.siliksama.hajmino.BuildConfig
import ir.siliksama.hajmino.R

/**
 * Optional financial support. Deliberately separate from the premium page:
 * premium is a purchase (removes ads), this page is a pure donation — the
 * app is free and every feature stays free; nothing here is ever required.
 *
 * Two honest channels:
 *  1. Café Bazaar — the store page's own "Support developer" button
 *     (opened via the bazaar:// scheme with an https fallback).
 *  2. Card-to-card — shown only when the developer configured a card number
 *     at build time (SUPPORT_CARD_NUMBER / SUPPORT_CARD_HOLDER). If it is
 *     not configured, the section simply does not appear; no placeholder
 *     card is ever displayed.
 */
@Composable
fun SupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cardNumber = BuildConfig.SUPPORT_CARD_NUMBER
    val cardHolder = BuildConfig.SUPPORT_CARD_HOLDER
    val cardConfigured = cardNumber.isNotBlank()
    // LINT-RESOURCE-FIX: resolved during composition so the copy callback never
    // queries resources through LocalContext.
    val cardCopiedMsg = stringResource(R.string.support_card_copied)

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
                Text(
                    text = stringResource(R.string.support_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(8.dp))

            // Beating heart hero — same beat as the dashboard icon, so the
            // connection between the two screens is instant.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(96.dp)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFFE5487B), Color(0xFFB22A5C))),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                BeatingHeart(tint = Color.White, size = 46.dp)
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.support_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))

            // ── 1. Café Bazaar support ───────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Storefront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = stringResource(R.string.support_bazaar_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.support_bazaar_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    ActionButton(
                        text = stringResource(R.string.support_bazaar_open),
                        onClick = { openBazaar(context) },
                        icon = Icons.Outlined.Storefront
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 2. Card-to-card (only when actually configured) ──────────
            AnimatedVisibility(
                visible = cardConfigured,
                enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 4 },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 4 }
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.CreditCard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                text = stringResource(R.string.support_card_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        val holderSuffix = if (cardHolder.isNotBlank())
                            stringResource(R.string.support_card_holder, cardHolder)
                        else stringResource(R.string.support_card_body)
                        Text(
                            text = holderSuffix,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        // The card number itself: monospaced-looking tabular
                        // figures, grouped in fours, tappable to copy.
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                        ) {
                            Text(
                                text = formatCard(cardNumber),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp, horizontal = 8.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        ActionButton(
                            text = stringResource(R.string.support_card_copy),
                            onClick = {
                                copyCard(context, cardNumber)
                                Toast.makeText(context, cardCopiedMsg, Toast.LENGTH_SHORT).show()
                            },
                            icon = Icons.Outlined.CreditCard,
                            gradient = listOf(Color(0xFFB22A5C), Color(0xFFE5487B))
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.support_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))
            // X6 / SUPPORT-AD-FIX: no ad slot on the support/donation page —
            // asking for money and flashing an ad in the same breath was the
            // recorded product decision, and the slot brought no value here.
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Opens the Café Bazaar app page (store support button), browser as fallback. */
private fun openBazaar(context: Context) {
    val pkg = context.packageName
    val bazaar = Uri.parse("bazaar://details?id=$pkg")
    val web = Uri.parse("https://cafebazaar.ir/app/$pkg")
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, bazaar))
    }.isSuccess
    if (!opened) {
        val fallback = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, web))
        }.isSuccess
        if (!fallback) {
            Toast.makeText(
                context,
                context.getString(R.string.support_bazaar_error),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

private fun copyCard(context: Context, card: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("support_card", card))
}

/** Groups digits in fours: 6104331234567890 -> 6104 3312 3456 7890. */
private fun formatCard(card: String): String {
    val digits = card.filter { it.isDigit() }
    return digits.chunked(4).joinToString(" ")
}
