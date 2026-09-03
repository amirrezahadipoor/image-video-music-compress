package com.compressly

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.siliksama.hajmino.R
import androidx.test.platform.app.InstrumentationRegistry
import com.compressly.core.util.LocaleHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import java.util.Locale

/**
 * i18n RUNTIME sanity test.
 *
 * The full key-parity audit (every fa key has an en counterpart and vice
 * versa, no blanks, no duplicates) is a static XML check that runs as a
 * plain CI step (faster, sees the real source files). This instrumented
 * test covers what only a running process can cover:
 *
 *   - the EN configuration actually resolves (createConfigurationContext),
 *   - the key EN strings are really EN (not silent fallback to fa),
 *   - the default configuration really is FA (the product's default
 *     language — a regression to "English default" would be a brand bug).
 */
@RunWith(AndroidJUnit4::class)
class I18nSanityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun enResources() = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
    ).resources

    /** The FA (default product language) resources. */
    private fun faResources() = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale("fa")) }
    ).resources

    @Test
    fun en_configuration_resolves_real_english_strings() {
        val en = enResources()
        val samples = mapOf(
            R.string.action_compress to "Compress",
            R.string.history_title to "History",
            R.string.app_name to "Hajmino",
        )
        samples.forEach { (id, expectedHint) ->
            val value = en.getString(id)
            assertFalse("EN string for id=$id must not be blank", value.isBlank())
            // The EN value must differ from the FA default (else the EN
            // entry is missing and we are silently reading Persian).
            // NOTE: read the FA value from the fa configuration explicitly.
            // The test PROCESS runs under the device locale (en-US on CI) —
            // it does not go through MainActivity.attachBaseContext, so
            // context.getString() would resolve ENGLISH, not Persian, and the
            // test would compare EN==EN and always fail.
            val faValue = faResources().getString(id)
            assertNotEquals(
                "string id=$id has no distinct EN translation (fell back to FA)",
                faValue, value
            )
            // And must actually contain the expected latin hint.
            assertTrue(
                "EN value for id=$id should contain \"$expectedHint\" but was \"$value\"",
                value.contains(expectedHint, ignoreCase = true)
            )
        }
    }

    @Test
    fun default_language_is_persian() {
        // The product's default language is Persian: with no persisted
        // choice the app applies "fa" (MainActivity's attachBaseContext
        // does the rest). This must be asserted on the app's own default —
        // NOT on the device locale's resource resolution: the CI emulator
        // runs en-US, so context.getString() there would resolve English
        // and a "is the default fa" assertion on it would be wrong.
        org.junit.Assert.assertEquals(LocaleHelper.DEFAULT_LANGUAGE, "fa")
        org.junit.Assert.assertEquals(
            "no persisted choice must yield FA",
            "fa",
            LocaleHelper.persistedLanguage(context)
        )
    }
}
