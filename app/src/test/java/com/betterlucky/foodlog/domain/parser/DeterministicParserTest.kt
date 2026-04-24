package com.betterlucky.foodlog.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DeterministicParserTest {
    private val parser = DeterministicParser()
    private val today = LocalDate.parse("2026-04-24")

    @Test
    fun teaPhrasesResolveToTeaShortcut() {
        listOf("tea", "a tea", "cup of tea", "1 tea", "  CUP   OF   TEA  ").forEach { input ->
            val parsed = parser.parse(input, today)

            assertEquals("tea", parsed.shortcutTrigger)
            assertEquals(1.0, parsed.quantity, 0.001)
            assertEquals(today, parsed.logDate)
        }
    }

    @Test
    fun teaQuantitiesResolveToTeaShortcutWithAmount() {
        mapOf(
            "2 tea" to 2.0,
            "2 teas" to 2.0,
            "two teas" to 2.0,
            "three tea" to 3.0,
        ).forEach { (input, quantity) ->
            val parsed = parser.parse(input, today)

            assertEquals("tea", parsed.shortcutTrigger)
            assertEquals(quantity, parsed.quantity, 0.001)
        }
    }

    @Test
    fun supportedDatePrefixesSetLogDate() {
        assertEquals(today, parser.parse("today tea", today).logDate)
        assertEquals(today.minusDays(1), parser.parse("yesterday tea", today).logDate)
        assertEquals(LocalDate.parse("2026-04-23"), parser.parse("2026-04-23 tea", today).logDate)
    }

    @Test
    fun datePrefixLeavesUnsupportedPhraseForPendingEntry() {
        val parsed = parser.parse("yesterday curry", today)

        assertEquals(today.minusDays(1), parsed.logDate)
        assertEquals("curry", parsed.normalizedFoodText)
        assertEquals("curry", parsed.shortcutTrigger)
    }
}
