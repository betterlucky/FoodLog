package com.betterlucky.foodlog.domain.label

data class LabelNutritionFacts(
    val rawText: String,
    val kcalPer100g: Double? = null,
    val kcalPer100ml: Double? = null,
    val kcalPerServing: Double? = null,
    val servingUnit: String? = null,
    val servingAmount: Double? = null,
    val servingItemUnit: String? = null,
    val servingSizeGrams: Double? = null,
    val packageSizeGrams: Double? = null,
    val packageSizeMilliliters: Double? = null,
    val packageItemCount: Double? = null,
    val packageItemUnit: String? = null,
    val proteinPer100g: Double? = null,
    val fiberPer100g: Double? = null,
    val carbsPer100g: Double? = null,
    val fatPer100g: Double? = null,
    val sugarsPer100g: Double? = null,
    val saltPer100g: Double? = null,
    val prepared: Boolean = false,
) {
    val hasRequiredCalories: Boolean = kcalPer100g != null || kcalPer100ml != null || kcalPerServing != null
    val isPartial: Boolean = !hasRequiredCalories ||
        (
            kcalPerServing != null &&
                servingSizeGrams == null &&
                servingItemUnit?.let(::isGenericServingUnit) != false
            )
}

class LabelNutritionParser {
    private val servingUnitPattern = """cup|serving|sachet|slice|item|bar|portion|can|bag|pack|bottle|pot|tub|fillet|finger|sausage"""
    private val servingAmountPattern = """(?:\d+(?:[.,]\d+)?|\d+\s*/\s*\d+|[ilt]\s*/\s*\d+|[½⅓⅔¼¾])"""
    private val per100gPattern = Regex("""\bper\s*100\s*[gqao](?=\b|per|:)""")
    private val per100mlPattern = Regex("""\bper\s*100\s*m[l1i](?=\b|per|:)""")

    fun parse(rawText: String): LabelNutritionFacts {
        val normalized = rawText
            .replace('\u00A0', ' ')
            .normalizeOcrUnitCharacters()
            .replace(Regex("""[ \t]+"""), " ")
            .trim()
        val lower = normalized.lowercase()
        val kcalPattern = Regex("""(\d+(?:[.,]\d+)?)\s*k[ce]al""", RegexOption.IGNORE_CASE)
        val kcalValues = extractKcalValues(normalized, lower, kcalPattern)
        val serving = extractServing(lower)
        val servingSizeGrams = extractServingSizeGrams(lower)
        val packageQuantity = parsePackageQuantity(lower)
        val prepared = listOf("made up", "prepared", "as consumed", "per cup").any(lower::contains)

        return LabelNutritionFacts(
            rawText = rawText,
            kcalPer100g = kcalValues.per100g,
            kcalPer100ml = kcalValues.per100ml,
            kcalPerServing = kcalValues.perServing,
            servingUnit = serving?.label,
            servingAmount = serving?.amount,
            servingItemUnit = serving?.unit,
            servingSizeGrams = servingSizeGrams,
            packageSizeGrams = packageQuantity.grams,
            packageSizeMilliliters = packageQuantity.milliliters,
            packageItemCount = packageQuantity.itemCount,
            packageItemUnit = packageQuantity.itemUnit,
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
    ): KcalValues {
        // Try line-contextual approach: find a line that mentions 100g and contains kcal.
        // This handles labels where serving and 100g rows appear in any order.
        val lines = normalized.lines()
        val lineWith100UnitKcal = lines.firstOrNull { line ->
            val ll = line.lowercase()
            (per100gPattern.containsMatchIn(ll) || per100mlPattern.containsMatchIn(ll)) &&
                kcalPattern.containsMatchIn(line)
        }

        if (lineWith100UnitKcal != null) {
            val lineLower = lineWith100UnitKcal.lowercase()
            val per100 = kcalPattern.find(lineWith100UnitKcal)?.groupValues?.get(1)?.toDoubleValue()
            // Per-serving: look for kcal on a line that does NOT mention 100g
            val servingLine = lines.firstOrNull { line ->
                if (line === lineWith100UnitKcal) return@firstOrNull false
                val ll = line.lowercase()
                !per100gPattern.containsMatchIn(ll) &&
                    !per100mlPattern.containsMatchIn(ll) &&
                    nonReferenceKcalValues(line, kcalPattern).isNotEmpty()
            }
            val perServing = servingLine?.let { nonReferenceKcalValues(it, kcalPattern).firstOrNull() }
                ?: run {
                    // Both values may be on the same 100g line (e.g. "450kcal 135kcal per 100g per serving")
                    nonReferenceKcalValues(lineWith100UnitKcal, kcalPattern).drop(1).firstOrNull()
                }
            val has100g = per100gPattern.containsMatchIn(lineLower)
            return if (!has100g && per100mlPattern.containsMatchIn(lineLower)) {
                KcalValues(per100ml = per100, perServing = perServing)
            } else {
                KcalValues(per100g = per100, perServing = perServing)
            }
        }

        // Fallback: positional approach \u2014 first kcal is per 100g if the document mentions it
        val allKcal = lines
            .flatMap { line -> nonReferenceKcalValues(line, kcalPattern) }
        val has100gContext = per100gPattern.containsMatchIn(lower)
        val has100mlContext = per100mlPattern.containsMatchIn(lower)
        return when {
            has100mlContext -> KcalValues(per100ml = allKcal.getOrNull(0), perServing = allKcal.getOrNull(1))
            has100gContext -> KcalValues(per100g = allKcal.getOrNull(0), perServing = allKcal.getOrNull(1))
            else -> KcalValues(perServing = allKcal.firstOrNull())
        }
    }

    private fun extractServing(lower: String): ServingDescriptor? {
        Regex("""\bper\s*($servingAmountPattern)\s*($servingUnitPattern)\b""")
            .find(lower)
            ?.let { match ->
                val amount = match.groupValues[1].trim().toServingAmount() ?: return@let null
                val unit = match.groupValues[2]
                return ServingDescriptor(amount = amount, unit = unit, label = formatServingUnit(amount, unit))
            }

        val explicitServing = Regex("""\b($servingAmountPattern)\s+($servingUnitPattern)\b""")
            .find(lower)
            ?.let { match ->
                val amount = match.groupValues[1].trim().toServingAmount() ?: return@let null
                val unit = match.groupValues[2]
                ServingDescriptor(amount = amount, unit = unit, label = formatServingUnit(amount, unit))
            }
        if (explicitServing != null) return explicitServing

        Regex("""\bper\s+(cup|slice|item|bar|can|pack|bottle|pot|tub|serving|portion)\b""")
            .find(lower)
            ?.groupValues?.get(1)
            ?.let { return ServingDescriptor(amount = 1.0, unit = it, label = it) }

        Regex("""\b(\d+(?:[.,]\d+)?)\s+(fingers?|fillets?|sausages?)\s*=\s*1\s*serving\b""")
            .find(lower)
            ?.let { match ->
                val amount = match.groupValues[1].toDoubleValue() ?: return@let null
                val unit = match.groupValues[2].singularItemUnit() ?: return@let null
                return ServingDescriptor(amount = amount, unit = unit, label = formatServingUnit(amount, unit))
            }

        return null
    }

    private fun extractServingSizeGrams(lower: String): Double? {
        // "1 serving (30g)" / "1 cup (255g)" / "per 1/2 can (196g)" / "per serving (30g)"
        Regex("""\b(?:per\s+)?(?:$servingAmountPattern\s+)?(?:$servingUnitPattern)\s*\((\d+(?:[.,]\d+)?)\s*g\)""")
            .find(lower)?.groupValues?.get(1)?.toDoubleValue()?.let { return it }
        // OCR can split the serving weight onto the next header line:
        // "per100g pert/2can" then "(as consumed) (196g) - (196g)".
        if (Regex("""\bper\s*$servingAmountPattern\s*$servingUnitPattern\b""").containsMatchIn(lower)) {
            Regex("""\((\d+(?:[.,]\d+)?)\s*g\)""")
                .find(lower)?.groupValues?.get(1)?.toDoubleValue()?.let { return it }
        }
        // "serving size: 30g" / "serving size 30g"
        Regex("""\bserving size:?\s*(\d+(?:[.,]\d+)?)\s*g\b""")
            .find(lower)?.groupValues?.get(1)?.toDoubleValue()?.let { return it }
        // "per 30g serving" / "per 30 g serving"
        Regex("""\bper\s+(\d+(?:[.,]\d+)?)\s*g\s+(?:serving|portion)\b""")
            .find(lower)?.groupValues?.get(1)?.toDoubleValue()?.let { return it }
        // "per serving 30g" / "per slice approx. 9g" / "per slice (approx. 9g)"
        Regex("""\bper\s+(?:$servingUnitPattern)\s*\(?\s*(?:approx\.?\s*)?(\d+(?:[.,]\d+)?)\s*g\)?""")
            .find(lower)?.groupValues?.get(1)?.toDoubleValue()?.let { return it }
        // "per serving 30g"
        Regex("""\bper\s+serving\s+(\d+(?:[.,]\d+)?)\s*g\b""")
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
        val lines = lower.lines()
        val nutritionStart = lines.indexOfFirst { line -> isNutritionSectionStart(line) }
            .takeUnless { it == -1 }
            ?: lines.size
        val packageLines = lines.filterIndexed { index, line ->
            index < nutritionStart || hasExplicitPackageQuantityCue(line)
        }
        val multiplier = Regex("""(\d+(?:[.,]\d+)?)\s*[x×*]\s*(\d+(?:[.,]\d+)?)\s*(kg|g|ml|l)\b\s*(bags?|packs?|bars?|bottles?|pots?|cans?)?""")
            .find(packageLines.joinToString("\n"))
        if (multiplier != null) {
            val count = multiplier.groupValues[1].toDoubleValue()
            val itemAmount = multiplier.groupValues[2].toDoubleValue()
            val unit = multiplier.groupValues[3]
            val grams = itemAmount?.toGrams(unit)
            val milliliters = itemAmount?.toMilliliters(unit)
            val itemUnit = multiplier.groupValues.getOrNull(4).orEmpty().singularItemUnit()
            return PackageQuantity(
                grams = if (count != null && grams != null) count * grams else null,
                milliliters = if (count != null && milliliters != null) count * milliliters else null,
                itemCount = count,
                itemUnit = itemUnit,
            )
        }

        val packageItemCount = extractPackageItemCount(packageLines)
        return PackageQuantity(
            grams = packageLines.asSequence()
                .firstNotNullOfOrNull { line ->
                    if (per100gPattern.containsMatchIn(line) || (isNutritionTableLine(line) && !hasExplicitPackageQuantityCue(line))) {
                        null
                    } else {
                        extractPackageWeightGrams(line)
                    }
                },
            milliliters = packageLines.asSequence()
                .firstNotNullOfOrNull { line ->
                    if (per100mlPattern.containsMatchIn(line) || (isNutritionTableLine(line) && !hasExplicitPackageQuantityCue(line))) {
                        null
                    } else {
                        extractPackageVolumeMilliliters(line)
                    }
                },
            itemCount = packageItemCount?.first,
            itemUnit = packageItemCount?.second,
        )
    }

    private fun extractPackageItemCount(lines: List<String>): Pair<Double, String?>? =
        lines.firstNotNullOfOrNull { line ->
            if (isNutritionTableLine(line)) {
                null
            } else {
                Regex("""\bcontains\s+(\d+(?:[.,]\d+)?)\s*(bags?|packs?|bars?|bottles?|pots?|cans?)\b""")
                    .find(line)
                    ?.let { match -> match.groupValues[1].toDoubleValue()?.let { it to match.groupValues[2].singularItemUnit() } }
            }
        }

    private fun extractPackageWeightGrams(line: String): Double? {
        Regex("""\b(?:net\s*(?:weight|wt)?|weight|contents?)[:\s]*(\d+(?:[.,]\d+)?)\s*(kg|g)\b""")
            .find(line)
            ?.let { return it.groupValues[1].toDoubleValue()?.toGrams(it.groupValues[2]) }
        Regex("""^\s*(\d{2,4}(?:[.,]\d+)?)\s*(kg|g)\s*e\s*$""")
            .find(line)
            ?.let { return it.groupValues[1].toDoubleValue()?.toGrams(it.groupValues[2]) }
        return Regex("""^\s*(\d{2,4}(?:[.,]\d+)?)\s*(kg|g)\s*(?:pack|pouch|bag|bottle|pot|tub|can|tray|box)\b""")
            .find(line)
            ?.let { it.groupValues[1].toDoubleValue()?.toGrams(it.groupValues[2]) }
    }

    private fun extractPackageVolumeMilliliters(line: String): Double? {
        Regex("""\b(?:net\s*(?:volume|vol)?|volume|contents?)[:\s]*(\d+(?:[.,]\d+)?)\s*(ml|l)\b""")
            .find(line)
            ?.let { return it.groupValues[1].toDoubleValue()?.toMilliliters(it.groupValues[2]) }
        Regex("""\b(\d{2,4}(?:[.,]\d+)?)\s*(ml|l)\s*(?:bottle|carton|can|pot|tub)\b""")
            .find(line)
            ?.let { return it.groupValues[1].toDoubleValue()?.toMilliliters(it.groupValues[2]) }
        return Regex("""^\s*(\d{2,4}(?:[.,]\d+)?)\s*(ml|l)\s*e?\s*$""")
            .find(line)
            ?.let { it.groupValues[1].toDoubleValue()?.toMilliliters(it.groupValues[2]) }
    }

    private fun isNutritionTableLine(line: String): Boolean {
        val trimmed = line.trim()
        val nutrientLinePattern = Regex(
            """^(?:of\s+)?(?:which\s+)?(?:saturates?|salt|sugars?|fat|protein|fibre|fiber|carbohydrate|carbs|energy)\b""",
        )
        return nutrientLinePattern.containsMatchIn(trimmed) ||
            Regex("""^\d+(?:[.,]\d+)?\s*(?:g|kcal|kj)\b""").containsMatchIn(trimmed) ||
            trimmed.contains("reference intake") ||
            trimmed.contains("your ri")
    }

    private fun isNutritionSectionStart(line: String): Boolean =
        line.contains("nutrition") ||
            line.contains("nutition") ||
            line.contains("typical values") ||
            line.contains("okal values") ||
            per100gPattern.containsMatchIn(line) ||
            per100mlPattern.containsMatchIn(line)

    private fun hasExplicitPackageQuantityCue(line: String): Boolean =
        Regex("""\b(?:net|weight|contents?|pack|pouch|bag|bottle|pot|tub|can|tray|box|carton)\b""")
            .containsMatchIn(line)

    private fun nonReferenceKcalValues(
        line: String,
        kcalPattern: Regex,
    ): List<Double> {
        val lowerLine = line.lowercase()
        return kcalPattern.findAll(line)
            .mapNotNull { match ->
                val value = match.groupValues[1].toDoubleValue() ?: return@mapNotNull null
                value.takeUnless { isReferenceIntakeKcal(lowerLine, it) }
            }
            .toList()
    }

    private fun isReferenceIntakeKcal(
        line: String,
        value: Double,
    ): Boolean =
        line.contains("adults need") ||
            (
                value == 2000.0 &&
                    (
                        line.contains("reference intake") ||
                            line.contains("average adult") ||
                            line.contains("your ri") ||
                            line.contains("%ri") ||
                            Regex("""\b8400\s*k[j3]\b""").containsMatchIn(line)
                        )
                )
}

private data class KcalValues(
    val per100g: Double? = null,
    val per100ml: Double? = null,
    val perServing: Double? = null,
)

private data class ServingDescriptor(
    val amount: Double,
    val unit: String,
    val label: String,
)

internal fun isGenericServingUnit(unit: String): Boolean =
    unit.trim().lowercase().removeSuffix("s") in setOf("serving", "portion")

private data class PackageQuantity(
    val grams: Double?,
    val milliliters: Double?,
    val itemCount: Double?,
    val itemUnit: String?,
)

private fun formatServingUnit(
    amount: Double,
    unit: String,
): String {
    fun near(target: Double) = kotlin.math.abs(amount - target) < 1e-6
    val formattedAmount = when {
        near(0.5) -> "1/2"
        near(1.0 / 3.0) -> "1/3"
        near(2.0 / 3.0) -> "2/3"
        near(0.25) -> "1/4"
        near(0.75) -> "3/4"
        else -> amount.formatClean()
    }
    return if (near(1.0)) unit else "$formattedAmount $unit"
}

private fun String.toServingAmount(): Double? =
    trim()
        .replace(" ", "")
        .replace(Regex("""^[ilt]/"""), "1/")
        .let { value ->
            when (value) {
                "½" -> 0.5
                "⅓" -> 1.0 / 3.0
                "⅔" -> 2.0 / 3.0
                "¼" -> 0.25
                "¾" -> 0.75
                else -> {
                    val fraction = Regex("""(\d+(?:[.,]\d+)?)/(\d+(?:[.,]\d+)?)""").matchEntire(value)
                    if (fraction != null) {
                        val numerator = fraction.groupValues[1].toDoubleValue()
                        val denominator = fraction.groupValues[2].toDoubleValue()
                        if (numerator != null && denominator != null && denominator > 0.0) {
                            numerator / denominator
                        } else {
                            null
                        }
                    } else {
                        value.toDoubleValue()
                    }
                }
            }
        }

private fun String.normalizeOcrUnitCharacters(): String =
    Regex("""\b(\d[\dOo]*)\s*(g|ml|k[ce]al)\b""", RegexOption.IGNORE_CASE)
        .replace(this) { match ->
            match.groupValues[1].replace('O', '0').replace('o', '0') + match.groupValues[2]
        }

private fun String.toDoubleValue(): Double? =
    replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }

private fun Double.toGrams(unit: String): Double? =
    when (unit) {
        "kg" -> this * 1000.0
        "g" -> this
        else -> null
    }

private fun Double.toMilliliters(unit: String): Double? =
    when (unit) {
        "l" -> this * 1000.0
        "ml" -> this
        else -> null
    }

private fun String.singularItemUnit(): String? =
    trim().removeSuffix("s").takeIf { it.isNotBlank() }

private fun Double.formatClean(): String =
    if (rem(1.0) == 0.0) toInt().toString() else toString().trimEnd('0').trimEnd('.')
