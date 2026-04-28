package com.betterlucky.foodlog.domain.parser

import java.time.LocalDate

data class ParsedSubmission(
    val rawText: String,
    val normalizedFoodText: String,
    val logDate: LocalDate,
    val parts: List<ParsedFoodPart>,
) {
    val shortcutTrigger: String?
        get() = parts.singleOrNull()?.shortcutTrigger

    val quantity: Double
        get() = parts.singleOrNull()?.quantity ?: 1.0
}

data class ParsedFoodPart(
    val normalizedFoodText: String,
    val shortcutTrigger: String?,
    val quantity: Double = 1.0,
)

class DeterministicParser {
    fun parse(
        input: String,
        today: LocalDate,
        defaultLogDate: LocalDate = today,
    ): ParsedSubmission {
        val normalized = normalize(input)
        val dated = extractDatePrefix(
            normalized = normalized,
            today = today,
            defaultLogDate = defaultLogDate,
        )
        return ParsedSubmission(
            rawText = input,
            normalizedFoodText = dated.foodText,
            logDate = dated.logDate,
            parts = foodPartsFor(dated.foodText),
        )
    }

    fun normalize(input: String): String =
        input
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")

    private fun extractDatePrefix(
        normalized: String,
        today: LocalDate,
        defaultLogDate: LocalDate,
    ): DatedFoodText {
        val yesterdayPrefix = "yesterday "
        val todayPrefix = "today "
        val isoDateMatch = Regex("^(\\d{4}-\\d{2}-\\d{2})\\s+(.+)$").matchEntire(normalized)

        return when {
            normalized.startsWith(yesterdayPrefix) ->
                DatedFoodText(
                    foodText = normalized.removePrefix(yesterdayPrefix),
                    logDate = today.minusDays(1),
                )

            normalized.startsWith(todayPrefix) ->
                DatedFoodText(
                    foodText = normalized.removePrefix(todayPrefix),
                    logDate = today,
                )

            isoDateMatch != null ->
                DatedFoodText(
                    foodText = isoDateMatch.groupValues[2],
                    logDate = LocalDate.parse(isoDateMatch.groupValues[1]),
                )

            else -> DatedFoodText(foodText = normalized, logDate = defaultLogDate)
        }
    }

    private fun foodPartsFor(foodText: String): List<ParsedFoodPart> =
        foodText
            .split(Regex("\\s*(?:,|\\band\\b)\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { part ->
                val shortcutTrigger = shortcutTriggerFor(part)
                ParsedFoodPart(
                    normalizedFoodText = part,
                    shortcutTrigger = shortcutTrigger?.trigger,
                    quantity = shortcutTrigger?.quantity ?: 1.0,
                )
            }

    private fun shortcutTriggerFor(foodText: String): ShortcutMatch? {
        if (foodText.isBlank()) return null

        val numericMatch = Regex("^(\\d+(?:\\.\\d+)?)\\s+(.+)$").matchEntire(foodText)
        if (numericMatch != null) {
            return ShortcutMatch(
                trigger = singularizeShortcut(numericMatch.groupValues[2]),
                quantity = numericMatch.groupValues[1].toDouble(),
            )
        }

        val wordQuantityMatch = Regex("^(two|three|four|five)\\s+(.+)$").matchEntire(foodText)
        if (wordQuantityMatch != null) {
            return ShortcutMatch(
                trigger = singularizeShortcut(wordQuantityMatch.groupValues[2]),
                quantity = wordQuantityMatch.groupValues[1].wordQuantity(),
            )
        }

        val trigger = when {
            foodText.startsWith("a ") -> foodText.removePrefix("a ")
            foodText.startsWith("cup of ") -> foodText.removePrefix("cup of ")
            else -> foodText
        }
        return ShortcutMatch(trigger = singularizeShortcut(trigger), quantity = 1.0)
            .takeIf { it.trigger.isNotBlank() }
    }

    private fun singularizeShortcut(trigger: String): String =
        when (trigger) {
            "teas", "cups of tea" -> "tea"
            else -> trigger
        }

    private fun String.wordQuantity(): Double =
        when (this) {
            "two" -> 2.0
            "three" -> 3.0
            "four" -> 4.0
            "five" -> 5.0
            else -> 1.0
        }

    private data class ShortcutMatch(
        val trigger: String,
        val quantity: Double,
    )

    private data class DatedFoodText(
        val foodText: String,
        val logDate: LocalDate,
    )
}
