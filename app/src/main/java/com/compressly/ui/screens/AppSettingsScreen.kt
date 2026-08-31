package com.compressly.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.siliksama.hajmino.BuildConfig
import ir.siliksama.hajmino.R
import com.compressly.core.data.ThemeMode
import com.compressly.ui.components.ChipSelector
import com.compressly.ui.components.PresetPicker
import com.compressly.ui.components.RotatingGear
import com.compressly.ui.components.SectionHeader
import com.compressly.ui.components.SelectableOptionList
import com.compressly.ui.components.ToggleRow
import com.compressly.ui.viewmodels.AppSettingsViewModel

@Composable
fun AppSettingsScreen(
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit = {},
    viewModel: AppSettingsViewModel = viewModel(factory = AppSettingsViewModel.Factory)
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val defaultPreset by viewModel.defaultPreset.collectAsStateWithLifecycle()
    val preserveMetadata by viewModel.preserveMetadataDefault.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val soundEnabled by viewModel.soundEnabled.collectAsStateWithLifecycle()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Text(
                    text = stringResource(R.string.app_settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                RotatingGear(
                    tint = MaterialTheme.colorScheme.primary,
                    size = 26.dp,
                    infinite = true
                )
                Spacer(Modifier.width(20.dp))
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                // ---- Appearance ----
                SectionHeader(stringResource(R.string.settings_section_appearance))
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SelectableOptionList(
                            options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                            selected = themeMode,
                            titleOf = { mode ->
                                stringResource(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> R.string.theme_system
                                        ThemeMode.LIGHT -> R.string.theme_light
                                        ThemeMode.DARK -> R.string.theme_dark
                                    }
                                )
                            },
                            descriptionOf = { null },
                            onSelect = viewModel::setThemeMode
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = stringResource(R.string.settings_language),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(10.dp))
                        ChipSelector(
                            options = listOf("fa", "en"),
                            selected = language,
                            labelOf = { lang ->
                                stringResource(if (lang == "fa") R.string.language_fa else R.string.language_en)
                            },
                            onSelect = viewModel::setLanguage
                        )
                        Spacer(Modifier.height(18.dp))
                        ToggleRow(
                            title = stringResource(R.string.sound_effects),
                            description = stringResource(R.string.sound_effects_desc),
                            checked = soundEnabled,
                            onCheckedChange = viewModel::setSoundEnabled
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ---- Defaults ----
                SectionHeader(stringResource(R.string.settings_section_defaults))
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.default_preset),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.default_preset_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        // Same picker as the compression screen, minus the
                        // saving badge - there is no file in context here.
                        PresetPicker(
                            selected = defaultPreset,
                            onSelect = viewModel::setDefaultPreset,
                            showSmart = true
                        )
                        Spacer(Modifier.height(16.dp))
                        ToggleRow(
                            title = stringResource(R.string.default_metadata),
                            description = stringResource(R.string.default_metadata_desc),
                            checked = preserveMetadata,
                            onCheckedChange = viewModel::setPreserveMetadataDefault
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ---- About ----
                SectionHeader(stringResource(R.string.settings_section_about))
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(R.drawable.ic_splash_logo),
                                contentDescription = null,
                                modifier = Modifier.size(46.dp)
                            )
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.about_offline_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.about_offline_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.about_build),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.about_licenses),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.about_licenses_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))

                // ---- Other Apps ----
                SectionHeader(stringResource(R.string.other_apps_title))
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        
                        com.compressly.ui.components.InfoRow(
                            title = stringResource(R.string.app_factor),
                            onClick = {
                                runCatching {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("bazaar://details?id=com.siliksama.factor_hesabdari")))
                                }.onFailure {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://cafebazaar.ir/app/?id=com.siliksama.factor_hesabdari")))
                                }
                            }
                        )
                        com.compressly.ui.components.InfoRow(
                            title = stringResource(R.string.app_konkoorify),
                            onClick = {
                                runCatching {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("bazaar://details?id=ir.konkoorify.app")))
                                }.onFailure {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://cafebazaar.ir/app/?id=ir.konkoorify.app")))
                                }
                            }
                        )
                        com.compressly.ui.components.InfoRow(
                            title = stringResource(R.string.app_fal),
                            onClick = {
                                runCatching {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("bazaar://details?id=ir.siliksama.falhafez")))
                                }.onFailure {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://cafebazaar.ir/app/?id=ir.siliksama.falhafez")))
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))

                // ---- Policies ----
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        com.compressly.ui.components.InfoRow(
                            title = stringResource(R.string.app_policy),
                            onClick = onOpenPrivacy
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
