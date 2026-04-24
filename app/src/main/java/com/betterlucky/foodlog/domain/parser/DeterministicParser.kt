package com.betterlucky.foodlog.domain.parser

import java.time.LocalDate

data class ParsedSubmission(
    val rawText: String,
    val normalizedFoodText: String,
    val logDate: LocalDate,
    val shortcutTrigger: String?,
)

class DeterministicParser {
    fun parse(input: String, today: LocalDate): ParsedSubmission {
        val normalized = normalize(input)
        val dated = extractDatePrefix(normalized, today)
        val shortcutTrigger = shortcutTriggerFor(dated.foodText)

        return ParsedSubmission(
            rawText = input,
            normalizedFoodText = dated.foodText,
            logDate = dated.logDate,
            shortcutTrigger = shortcutTrigger,
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

            else -> DatedFoodText(foodText = normalized, logDate = today)
        }
    }

    private fun shortcutTriggerFor(foodText: String): String? {
        val trigger = when {
            foodText.isBlank() -> null
            foodText.startsWith("a ") -> foodText.removePrefix("a ")
            foodText.startsWith("1 ") -> foodText.removePrefix("1 ")
            foodText.startsWith("cup of ") -> foodText.removePrefix("cup of ")
            else -> foodText
        }
        return trigger?.takeIf { it.isNotBlank() }
    }

    private data class DatedFoodText(
        val foodText: String,
        val logDate: LocalDate,
    )
}
