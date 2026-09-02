package com.compressly

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.siliksama.hajmino.R
import androidx.test.platform.app.InstrumentationRegistry
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
            val faValue = context.getString(id)
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
    fun default_configuration_is_persian() {
        val default = context.getString(R.string.history_title)
        assertTrue(
            "app default language must be FA (got \"$default\")",
            default.contains("تاریخچه")
        )
    }
}
