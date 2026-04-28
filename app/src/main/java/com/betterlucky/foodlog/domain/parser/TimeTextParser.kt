package com.betterlucky.foodlog.domain.parser

import java.time.LocalTime
import java.time.format.DateTimeParseException

object TimeTextParser {
    const val PATTERN: String = "(?:[01]?\\d|2[0-3]):[0-5]\\d|(?:0?[1-9]|1[0-2])(?::[0-5]\\d)?\\s*(?:am|pm)"

    fun parseOrNull(value: String): LocalTime? {
        val compact = value.trim().lowercase().replace(" ", "")
        if (compact.isBlank()) return null

        return try {
            when {
                compact.endsWith("am") || compact.endsWith("pm") -> parseMeridiemTime(compact)
                else -> LocalTime.parse(compact)
            }
        } catch (_: DateTimeParseException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun parse(value: String): LocalTime =
        checkNotNull(parseOrNull(value)) { "Unsupported time value: $value" }

    private fun parseMeridiemTime(value: String): LocalTime {
        val meridiem = value.takeLast(2)
        val timeText = value.dropLast(2)
        val parts = timeText.split(":")
        val hour = parts[0].toInt()
        val minute = parts.getOrNull(1)?.toInt() ?: 0
        val resolvedHour = when (meridiem) {
            "am" -> if (hour == 12) 0 else hour
            "pm" -> if (hour == 12) 12 else hour + 12
            else -> hour
        }
        return LocalTime.of(resolvedHour, minute)
    }
}
