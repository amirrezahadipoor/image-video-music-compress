package com.compressly.core.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * App-level locale handling. Persian is the app default; the user can switch
 * to English in Settings (persisted, applied instantly via recreate()).
 */
object LocaleHelper {

    const val DEFAULT_LANGUAGE = "fa"

    @Volatile
    var lastApplied: String = DEFAULT_LANGUAGE

    private const val PREFS = "lang_prefs"
    private const val KEY = "app_lang"

    fun persistedLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE

    fun persistLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, lang).apply()
    }

    /** Wraps [context] with the given locale configuration (API 17+). */
    fun apply(context: Context, lang: String): Context {
        // Locale(lang) is deprecated; forLanguageTag is the non-deprecated
        // equivalent and handles BCP-47 tags ("fa", "en", "fa-IR", ...).
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        lastApplied = lang
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config)
    }
}
