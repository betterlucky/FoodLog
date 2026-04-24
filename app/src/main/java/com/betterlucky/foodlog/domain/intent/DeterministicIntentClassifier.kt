package com.betterlucky.foodlog.domain.intent

import com.betterlucky.foodlog.domain.parser.DeterministicParser

class DeterministicIntentClassifier(
    private val normalizer: DeterministicParser = DeterministicParser(),
) {
    fun classify(input: String): EntryIntent {
        val normalized = normalizer.normalize(input)
        if (normalized.isBlank()) return EntryIntent.UNKNOWN

        return when {
            normalized.startsWith("actually ") ||
                normalized.startsWith("delete ") ||
                normalized.startsWith("remove ") ||
                normalized.startsWith("change ") ||
                normalized.startsWith("correct ") -> EntryIntent.CORRECTION

            normalized == "end of day" ||
                normalized == "export today" ||
                normalized == "export day" ||
                normalized == "daily report" -> EntryIntent.EXPORT_COMMAND

            normalized.endsWith("?") ||
                normalized.startsWith("how am i doing") ||
                normalized.startsWith("what have i eaten") ||
                normalized.startsWith("what did i eat") ||
                normalized.startsWith("summary") ||
                normalized.startsWith("summarise") ||
                normalized.startsWith("summarize") -> EntryIntent.QUERY

            normalized.startsWith("note ") ||
                normalized.startsWith("note:") -> EntryIntent.NOTE

            else -> EntryIntent.FOOD_LOG
        }
    }
}

