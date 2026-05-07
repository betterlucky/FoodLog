package com.betterlucky.foodlog.domain.parser

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

data class ParsedSubmission(
    val rawText: String,
    val normalizedFoodText: String,
    val logDate: LocalDate,
    val consumedTime: LocalTime?,
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
    val quantityUnit: String? = null,
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
        val timed = extractTime(dated.foodText)
        return ParsedSubmission(
            rawText = input,
            normalizedFoodText = timed.foodText,
            logDate = dated.logDate,
            consumedTime = timed.consumedTime,
            parts = foodPartsFor(timed.foodText),
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

            isoDateMatch != null -> {
                val parsedDate = isoDateMatch.groupValues[1].toLocalDateOrNull()
                if (parsedDate == null) {
                    DatedFoodText(foodText = normalized, logDate = defaultLogDate)
                } else {
                    DatedFoodText(
                        foodText = isoDateMatch.groupValues[2],
                        logDate = parsedDate,
                    )
                }
            }

            else -> DatedFoodText(foodText = normalized, logDate = defaultLogDate)
        }
    }

    private fun extractTime(foodText: String): TimedFoodText {
        val prefixMatch = Regex("^(?:at\\s+)?(${TIME_PATTERN})\\s+(.+)$").matchEntire(foodText)
        if (prefixMatch != null) {
            return TimedFoodText(
                foodText = prefixMatch.groupValues[2],
                consumedTime = TimeTextParser.parse(prefixMatch.groupValues[1]),
            )
        }

        val suffixMatch = Regex("^(.+?)\\s+(?:at\\s+)?(${TIME_PATTERN})$").matchEntire(foodText)
        if (suffixMatch != null) {
            return TimedFoodText(
                foodText = suffixMatch.groupValues[1],
                consumedTime = TimeTextParser.parse(suffixMatch.groupValues[2]),
            )
        }

        return TimedFoodText(foodText = foodText, consumedTime = null)
    }

    private fun foodPartsFor(foodText: String): List<ParsedFoodPart> {
        return splitFoodText(foodText)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { part ->
                val shortcutTrigger = shortcutTriggerFor(part)
                ParsedFoodPart(
                    normalizedFoodText = part,
                    shortcutTrigger = shortcutTrigger?.trigger,
                    quantity = shortcutTrigger?.quantity ?: 1.0,
                    quantityUnit = shortcutTrigger?.unit,
                )
            }
    }

    private fun shortcutTriggerFor(foodText: String): ShortcutMatch? {
        if (foodText.isBlank()) return null

        val gramMatch = Regex("^(\\d+(?:\\.\\d+)?)\\s*g\\s+(.+)$").matchEntire(foodText)
        if (gramMatch != null) {
            return ShortcutMatch(
                trigger = singularizeShortcut(gramMatch.groupValues[2]),
                quantity = gramMatch.groupValues[1].toDouble(),
                unit = "g",
            )
        }

        val unitQuantityMatch = Regex("^(\\d+(?:\\.\\d+)?)\\s+(${UNIT_PATTERN})\\s+(?:of\\s+)?(.+)$").matchEntire(foodText)
        if (unitQuantityMatch != null) {
            return ShortcutMatch(
                trigger = singularizeShortcut(unitQuantityMatch.groupValues[3]),
                quantity = unitQuantityMatch.groupValues[1].toDouble(),
                unit = singularizeUnit(unitQuantityMatch.groupValues[2]),
            )
        }

        val numericMatch = Regex("^(\\d+(?:\\.\\d+)?)\\s+(.+)$").matchEntire(foodText)
        if (numericMatch != null) {
            return ShortcutMatch(
                trigger = singularizeShortcut(numericMatch.groupValues[2]),
                quantity = numericMatch.groupValues[1].toDouble(),
                unit = null,
            )
        }

        val fractionMatch = Regex(
            "^(1/2|a half|half|1/3|a third|one third|2/3|two thirds?|3/4|three quarters?|1/4|a quarter|one quarter)\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(foodText)
        if (fractionMatch != null) {
            return ShortcutMatch(
                trigger = singularizeShortcut(fractionMatch.groupValues[2]),
                quantity = fractionMatch.groupValues[1].fractionQuantity(),
                unit = null,
            )
        }

        val wordQuantityMatch = Regex("^(two|three|four|five|half|a half)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(foodText)
        if (wordQuantityMatch != null) {
            return ShortcutMatch(
                trigger = singularizeShortcut(wordQuantityMatch.groupValues[2]),
                quantity = wordQuantityMatch.groupValues[1].wordQuantity(),
                unit = null,
            )
        }

        val trigger = when {
            foodText.startsWith("a ") -> foodText.removePrefix("a ")
            foodText.startsWith("cup of ") -> foodText.removePrefix("cup of ")
            else -> foodText
        }
        return ShortcutMatch(trigger = singularizeShortcut(trigger), quantity = 1.0, unit = null)
            .takeIf { it.trigger.isNotBlank() }
    }

    private fun singularizeShortcut(trigger: String): String =
        when (trigger) {
            "teas", "cups of tea" -> "tea"
            "bananas" -> "banana"
            "satsumas" -> "satsuma"
            "apples" -> "apple"
            "roast dinners" -> "roast dinner"
            "yoghurts" -> "yoghurt"
            "yogurt pots" -> "yogurt pot"
            "yoghurt pots" -> "yoghurt pot"
            else -> trigger
        }

    private fun splitFoodText(foodText: String): List<String> {
        val protectedFoodText = PROTECTED_AND_PHRASES.fold(foodText) { text, phrase ->
            text.replace(phrase, phrase.replace(" and ", PROTECTED_AND_TOKEN))
        }
        return protectedFoodText
            .split(Regex("\\s*(?:,|\\+|(?<!\\d)/(?!\\d)|&|;)\\s*"))
            .flatMap { segment ->
                if (segment.contains(" with ")) {
                    listOf(segment)
                } else {
                    segment.split(Regex("\\s+and\\s+"))
                }
            }
            .map { it.replace(PROTECTED_AND_TOKEN, " and ") }
    }

    private fun singularizeUnit(unit: String): String =
        when (unit) {
            "slices" -> "slice"
            "pieces" -> "piece"
            "servings" -> "serving"
            "portions" -> "portion"
            "pots" -> "pot"
            "cups" -> "cup"
            "crackers" -> "cracker"
            "biscuits" -> "biscuit"
            "bars" -> "bar"
            "bowls" -> "bowl"
            "spoons" -> "spoon"
            "tablespoons" -> "tablespoon"
            "teaspoons" -> "teaspoon"
            else -> unit
        }

    private fun String.wordQuantity(): Double =
        when (lowercase()) {
            "two" -> 2.0
            "three" -> 3.0
            "four" -> 4.0
            "five" -> 5.0
            "half", "a half" -> 0.5
            else -> 1.0
        }

    private fun String.fractionQuantity(): Double =
        when (lowercase().trim()) {
            "1/2", "a half", "half" -> 0.5
            "1/3", "a third", "one third" -> 1.0 / 3.0
            "2/3", "two thirds", "two third" -> 2.0 / 3.0
            "3/4", "three quarters", "three quarter" -> 0.75
            "1/4", "a quarter", "one quarter" -> 0.25
            else -> 1.0
        }

    private data class ShortcutMatch(
        val trigger: String,
        val quantity: Double,
        val unit: String?,
    )

    private data class DatedFoodText(
        val foodText: String,
        val logDate: LocalDate,
    )

    private data class TimedFoodText(
        val foodText: String,
        val consumedTime: LocalTime?,
    )

    private companion object {
        private const val TIME_PATTERN = TimeTextParser.PATTERN
        private const val UNIT_PATTERN = "slices?|pieces?|servings?|portions?|pots?|cups?|crackers?|biscuits?|bars?|bowls?|spoons?|tablespoons?|teaspoons?"
        private const val PROTECTED_AND_TOKEN = "__and__"
        private val PROTECTED_AND_PHRASES = listOf(
            "fruit and nut mix",
        )
    }
}

private fun String.toLocalDateOrNull(): LocalDate? =
    try {
        LocalDate.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }
