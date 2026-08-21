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
    private val keyLanguage = stringPreferencesKey("language")
    private val keySound = booleanPreferencesKey("sound_enabled")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[keyTheme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }

    val defaultPreset: Flow<CompressionPreset> = context.dataStore.data.map { prefs ->
        CompressionPreset.fromName(prefs[keyDefaultPreset] ?: CompressionPreset.DEFAULT.name)
    }

    val preserveMetadataDefault: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[keyPreserveMetadata] ?: true }

    /** Current language ("fa" or "en"), mirroring LocaleHelper prefs for instant startup. */
    val language: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyLanguage] ?: com.compressly.core.util.LocaleHelper.persistedLanguage(context)
    }

    val soundEnabled: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[keySound] ?: true }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[keyTheme] = mode.name }
    }

    suspend fun setDefaultPreset(preset: CompressionPreset) {
        context.dataStore.edit { it[keyDefaultPreset] = preset.name }
    }

    suspend fun setPreserveMetadataDefault(value: Boolean) {
        context.dataStore.edit { it[keyPreserveMetadata] = value }
    }

    suspend fun setLanguage(lang: String) {
        val safe = if (lang == "en") "en" else "fa"
        context.dataStore.edit { it[keyLanguage] = safe }
        com.compressly.core.util.LocaleHelper.persistLanguage(context, safe)
    }

    suspend fun setSoundEnabled(value: Boolean) {
        context.dataStore.edit { it[keySound] = value }
    }
}
