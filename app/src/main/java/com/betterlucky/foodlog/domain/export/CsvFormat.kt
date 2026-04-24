package com.betterlucky.foodlog.domain.export

internal fun csvLine(values: List<String?>): String =
    values.joinToString(",") { value -> escapeCsv(value.orEmpty()) }

private fun escapeCsv(value: String): String {
    val needsEscaping = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsEscaping) return value

    return buildString {
        append('"')
        value.forEach { char ->
            if (char == '"') append("\"\"") else append(char)
        }
        append('"')
    }
}

internal fun Double.formatCalories(): String =
    if (rem(1.0) == 0.0) toInt().toString() else toString()

internal fun Double.formatAmount(): String =
    if (rem(1.0) == 0.0) toInt().toString() else toString()
