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
        val kcalPattern = Regex("""(\d+(?:[.,]\d+)?)\s*kcal""", RegexOption.IGNORE_CASE)
        val (kcalPer100g, kcalPerServing) = extractKcalValues(normalized, lower, kcalPattern)
        val servingUnit = extractServingUnit(lower)
        val servingSizeGrams = extractServingSizeGrams(lower)
        val packageQuantity = parsePackageQuantity(lower)
        val prepared = listOf("made up", "prepared", "as consumed", "per cup").any(lower::contains)

        return LabelNutritionFacts(
            rawText = rawText,
            kcalPer100g = kcalPer100g,
            kcalPerServing = kcalPerServing,
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

    private fun extractKcalValues(
        normalized: String,
        lower: String,
        kcalPattern: Regex,
    ): Pair<Double?, Double?> {
        // Try line-contextual approach: find a line that mentions 100g and contains kcal.
        // This handles labels where serving and 100g rows appear in any order.
        val lines = normalized.lines()
        val lineWith100gKcal = lines.firstOrNull { line ->
            val ll = line.lowercase()
            (ll.contains("100g") || ll.contains("100 g")) && kcalPattern.containsMatchIn(line)
        }

        if (lineWith100gKcal != null) {
            val per100g = kcalPattern.find(lineWith100gKcal)?.groupValues?.get(1)?.toDoubleValue()
            // Per-serving: look for kcal on a line that does NOT mention 100g
            val servingLine = lines.firstOrNull { line ->
                if (line === lineWith100gKcal) return@firstOrNull false
                val ll = line.lowercase()
                !(ll.contains("100g") || ll.contains("100 g")) && kcalPattern.containsMatchIn(line)
            }
            val perServing = servingLine?.let { kcalPattern.find(it)?.groupValues?.get(1)?.toDoubleValue() }
                ?: run {
                    // Both values may be on the same 100g line (e.g. "450kcal 135kcal per 100g per serving")
                    kcalPattern.findAll(lineWith100gKcal).mapNotNull { it.groupValues[1].toDoubleValue() }.drop(1).firstOrNull()
                }
            return per100g to perServing
        }

        // Fallback: positional approach \u2014 first kcal is per 100g if the document mentions it
        val allKcal = kcalPattern.findAll(normalized).mapNotNull { it.groupValues[1].toDoubleValue() }.toList()
        val has100gContext = lower.contains("per 100g") || lower.contains("per 100 g")
        return if (has100gContext) {
            allKcal.getOrNull(0) to allKcal.getOrNull(1)
        } else {
            null to allKcal.firstOrNull()
        }
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

    private fun extractServingSizeGrams(lower: String): Double? {
        // "1 serving (30g)" / "1 cup (255g)" / "per serving (30g)"
        Regex("""\b(?:1\s+)?(?:cup|serving|sachet|slice|item|bar|portion)\s*\((\d+(?:[.,]\d+)?)\s*g\)""")
            .find(lower)?.groupValues?.get(1)?.toDoubleValue()?.let { return it }
        // "serving size: 30g" / "serving size 30g"
        Regex("""\bserving size:?\s*(\d+(?:[.,]\d+)?)\s*g\b""")
            .find(lower)?.groupValues?.get(1)?.toDoubleValue()?.let { return it }
        // "per 30g serving" / "per 30 g serving"
        Regex("""\bper\s+(\d+(?:[.,]\d+)?)\s*g\s+(?:serving|portion)\b""")
            .find(lower)?.groupValues?.get(1)?.toDoubleValue()?.let { return it }
        return null
    }

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
