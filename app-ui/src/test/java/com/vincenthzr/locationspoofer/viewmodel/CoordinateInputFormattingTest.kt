package com.vincenthzr.locationspoofer.viewmodel

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CoordinateInputFormattingTest {
    @Test
    fun `coordinate input uses parseable ASCII decimals in every supported number locale`() {
        // Locale is process-global. Keep these cases together and always restore both defaults.
        synchronized(Locale::class.java) {
            val original = Locale.getDefault()
            val originalFormat = Locale.getDefault(Locale.Category.FORMAT)
            val originalDisplay = Locale.getDefault(Locale.Category.DISPLAY)
            try {
                for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar"))) {
                    Locale.setDefault(locale)
                    val formatted = formatCoordinateInput(39.9042)
                    assertEquals(locale.toLanguageTag(), "39.904200", formatted)
                    assertNotNull(locale.toLanguageTag(), formatted.toDoubleOrNull())
                    assertEquals(39.9042, formatted.toDouble(), 0.000001)
                }
            } finally {
                Locale.setDefault(original)
                Locale.setDefault(Locale.Category.FORMAT, originalFormat)
                Locale.setDefault(Locale.Category.DISPLAY, originalDisplay)
            }
        }
    }

    @Test
    fun `coordinate input preserves signs boundaries and six decimal precision`() {
        val cases = mapOf(
            -33.865143 to "-33.865143",
            0.0 to "0.000000",
            -90.0 to "-90.000000",
            90.0 to "90.000000",
            -180.0 to "-180.000000",
            180.0 to "180.000000",
            1.2345678 to "1.234568",
        )
        cases.forEach { (coordinate, expected) ->
            assertEquals(expected, formatCoordinateInput(coordinate))
        }
    }
}
