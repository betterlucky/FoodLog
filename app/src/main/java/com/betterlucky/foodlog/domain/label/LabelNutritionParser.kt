package com.betterlucky.foodlog.domain.label

data class LabelNutritionFacts(
    val rawText: String,
    val kcalPer100g: Double? = null,
    val kcalPerServing: Double? = null,
    val servingUnit: String? = null,
    val servingSizeGrams: Double? = null,
    val packageSizeGrams: Double? = null,
    val packageItemCount: Double? = null,
    val proteinPer100g: Double? = null,
    val fiberPer100g: Double? = null,
    val carbsPer100g: Double? = null,
    val fatPer100g: Double? = null,
    val sugarsPer100g: Double? = null,
    val saltPer100g: Double? = null,
    val prepared: Boolean = false,
) {
    val hasRequiredCalories: Boolean = kcalPer100g != null || kcalPerServing != null
    val isPartial: Boolean = !hasRequiredCalories || (kcalPerServing != null && servingUnit == null)
}

class LabelNutritionParser {
    fun parse(rawText: String): LabelNutritionFacts {
        val normalized = rawText
            .replace('\u00A0', ' ')
            .replace(Regex("""[ \t]+"""), " ")
            .trim()
        val lower = normalized.lowercase()
        val kcalValues = Regex("""(\d+(?:[.,]\d+)?)\s*kcal""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { it.groupValues[1].toDoubleValue() }
            .toList()
        val servingUnit = extractServingUnit(lower)
        val servingSizeGrams = extractServingSizeGrams(lower)
        val packageQuantity = parsePackageQuantity(lower)
        val prepared = listOf("made up", "prepared", "as consumed", "per cup").any(lower::contains)

        return LabelNutritionFacts(
            rawText = rawText,
            kcalPer100g = if (lower.contains("per 100g") || lower.contains("per 100 g")) kcalValues.firstOrNull() else null,
            kcalPerServing = kcalValues.drop(if (lower.contains("per 100g") || lower.contains("per 100 g")) 1 else 0).firstOrNull(),
            servingUnit = servingUnit,
            servingSizeGrams = servingSizeGrams,
            packageSizeGrams = packageQuantity.grams,
            packageItemCount = packageQuantity.itemCount,
            proteinPer100g = extractPer100gNutrient(lower, "protein"),
            fiberPer100g = extractPer100gNutrient(lower, "fibre") ?: extractPer100gNutrient(lower, "fiber"),
            carbsPer100g = extractPer100gNutrient(lower, "carbohydrate"),
            fatPer100g = extractPer100gNutrient(lower, "fat"),
            sugarsPer100g = extractPer100gNutrient(lower, "sugars"),
            saltPer100g = extractPer100gNutrient(lower, "salt"),
            prepared = prepared,
        )
    }

    private fun extractServingUnit(lower: String): String? {
        Regex("""\bper\s+(cup|slice|item|bar)\b""")
            .find(lower)
            ?.groupValues
            ?.get(1)
            ?.let { return it }

        val explicitServing = Regex("""\b1\s+(cup|serving|sachet|slice|item|bar|portion)\b""")
            .find(lower)
            ?.groupValues
            ?.get(1)
        if (explicitServing != null) return explicitServing

        return Regex("""\bper\s+(serving|portion|sachet)\b""")
            .find(lower)
            ?.groupValues
            ?.get(1)
    }

    private fun extractServingSizeGrams(lower: String): Double? =
        Regex("""\b(?:1\s+)?(?:cup|serving|sachet|slice|item|bar|portion)\s*\((\d+(?:[.,]\d+)?)\s*g\)""")
            .find(lower)
            ?.groupValues
            ?.get(1)
            ?.toDoubleValue()

    private fun extractPer100gNutrient(
        lower: String,
        label: String,
    ): Double? {
        val line = lower.lineSequence().firstOrNull { line ->
            line.trimStart().startsWith(label)
        } ?: return null
        return Regex("""(\d+(?:[.,]\d+)?)\s*g""")
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.toDoubleValue()
    }

    private fun parsePackageQuantity(lower: String): PackageQuantity {
        val multiplier = Regex("""(\d+(?:[.,]\d+)?)\s*[x×*]\s*(\d+(?:[.,]\d+)?)\s*(kg|g|ml|l)\b""")
            .find(lower)
        if (multiplier != null) {
            val count = multiplier.groupValues[1].toDoubleValue()
            val itemAmount = multiplier.groupValues[2].toDoubleValue()
            val grams = itemAmount?.toGrams(multiplier.groupValues[3])
            return PackageQuantity(
                grams = if (count != null && grams != null) count * grams else null,
                itemCount = count,
            )
        }

        return PackageQuantity(
            grams = Regex("""\((\d+(?:[.,]\d+)?)\s*g\)""")
                .find(lower)
                ?.groupValues
                ?.get(1)
                ?.toDoubleValue(),
            itemCount = null,
        )
    }
}

private data class PackageQuantity(
    val grams: Double?,
    val itemCount: Double?,
)

private fun String.toDoubleValue(): Double? =
    replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }

private fun Double.toGrams(unit: String): Double? =
    when (unit) {
        "kg", "l" -> this * 1000.0
        "g", "ml" -> this
        else -> null
    }
