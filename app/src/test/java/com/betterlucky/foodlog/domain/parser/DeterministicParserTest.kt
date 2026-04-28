package com.betterlucky.foodlog.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

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
    fun compoundEntriesSplitOnCommasAndAnd() {
        val parsed = parser.parse("banana, satsuma and 2 teas", today)

        assertEquals(listOf("banana", "satsuma", "2 teas"), parsed.parts.map { it.normalizedFoodText })
        assertEquals(listOf("banana", "satsuma", "tea"), parsed.parts.map { it.shortcutTrigger })
        assertEquals(listOf(1.0, 1.0, 2.0), parsed.parts.map { it.quantity })
        assertEquals(null, parsed.shortcutTrigger)
    }

    @Test
    fun fruitAndNutMixKeepsAndInsideItemName() {
        val parsed = parser.parse("13:45 10g fruit and nut mix", today)

        assertEquals(listOf("10g fruit and nut mix"), parsed.parts.map { it.normalizedFoodText })
        assertEquals(listOf("fruit and nut mix"), parsed.parts.map { it.shortcutTrigger })
        assertEquals(listOf(10.0), parsed.parts.map { it.quantity })
    }

    @Test
    fun sharedTimeParserSupportsLogAndEditTimeFormats() {
        assertEquals(LocalTime.parse("13:00"), TimeTextParser.parseOrNull("1pm"))
        assertEquals(LocalTime.parse("13:45"), TimeTextParser.parseOrNull("13:45"))
        assertEquals(LocalTime.parse("08:05"), TimeTextParser.parseOrNull("8:05am"))
    }

    @Test
    fun pmPrefixDoesNotNeedQuantity() {
        val parsed = parser.parse("10pm yoghurt with chia and pumpkin seeds", today)

        assertEquals(LocalTime.parse("22:00"), parsed.consumedTime)
        assertEquals("yoghurt with chia and pumpkin seeds", parsed.normalizedFoodText)
    }

    @Test
    fun supportedDatePrefixesSetLogDate() {
        assertEquals(today, parser.parse("today tea", today).logDate)
        assertEquals(today.minusDays(1), parser.parse("yesterday tea", today).logDate)
        assertEquals(LocalDate.parse("2026-04-23"), parser.parse("2026-04-23 tea", today).logDate)
    }

    @Test
    fun unprefixedTextUsesDefaultLogDateButExplicitPrefixesUseCalendarToday() {
        val defaultLogDate = today.minusDays(1)

        assertEquals(defaultLogDate, parser.parse("tea", today, defaultLogDate).logDate)
        assertEquals(today, parser.parse("today tea", today, defaultLogDate).logDate)
        assertEquals(today.minusDays(1), parser.parse("yesterday tea", today, defaultLogDate).logDate)
        assertEquals(LocalDate.parse("2026-04-23"), parser.parse("2026-04-23 tea", today, defaultLogDate).logDate)
    }

    @Test
    fun datePrefixLeavesUnsupportedPhraseForPendingEntry() {
        val parsed = parser.parse("yesterday curry", today)

        assertEquals(today.minusDays(1), parsed.logDate)
        assertEquals("curry", parsed.normalizedFoodText)
        assertEquals("curry", parsed.shortcutTrigger)
    }
}
