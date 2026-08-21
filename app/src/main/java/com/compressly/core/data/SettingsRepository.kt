package com.compressly.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.compressly.core.engine.model.CompressionPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "compressly_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * App preferences (DataStore). Everything is local; no network, no accounts.
 */
class SettingsRepository(private val context: Context) {

    private val keyTheme = stringPreferencesKey("theme_mode")
    private val keyDefaultPreset = stringPreferencesKey("default_preset")
    private val keyPreserveMetadata = booleanPreferencesKey("preserve_metadata_default")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[keyTheme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }

    val defaultPreset: Flow<CompressionPreset> = context.dataStore.data.map { prefs ->
        CompressionPreset.fromName(prefs[keyDefaultPreset] ?: CompressionPreset.DEFAULT.name)
    }

    val preserveMetadataDefault: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[keyPreserveMetadata] ?: true }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[keyTheme] = mode.name }
    }

    suspend fun setDefaultPreset(preset: CompressionPreset) {
        context.dataStore.edit { it[keyDefaultPreset] = preset.name }
    }

    suspend fun setPreserveMetadataDefault(value: Boolean) {
        context.dataStore.edit { it[keyPreserveMetadata] = value }
    }
}
