package com.betterlucky.foodlog.domain.label

import java.util.Locale

enum class LabelInputMode(val storageValue: String) {
    ITEMS("ITEMS"),
    MEASURE("MEASURE"),
    ;

    companion object {
        fun fromStorage(value: String?): LabelInputMode =
            when (value) {
                MEASURE.storageValue -> MEASURE
                else -> ITEMS
            }
    }
}

data class LabelPortionResolution(
    val amount: Double?,
    val unit: String?,
    val grams: Double?,
    val milliliters: Double? = null,
    val calories: Double?,
    val isValidAmount: Boolean,
)

object LabelPortionResolver {
    fun resolve(
        facts: LabelNutritionFacts,
        mode: LabelInputMode,
        amountText: String,
    ): LabelPortionResolution {
        val trimmedAmount = amountText.trim()
        if (trimmedAmount.isBlank()) {
            return LabelPortionResolution(
                amount = null,
                unit = null,
                grams = null,
                milliliters = null,
                calories = null,
                isValidAmount = false,
            )
        }

        return when (mode) {
            LabelInputMode.ITEMS -> resolveItems(facts, trimmedAmount)
            LabelInputMode.MEASURE -> resolveMeasure(facts, trimmedAmount)
        }
    }

    fun itemUnit(facts: LabelNutritionFacts): String =
        facts.servingItemUnit ?: facts.packageItemUnit ?: "item"

    fun displayAmount(
        amount: Double,
        unit: String,
    ): String {
        return when {
            nearly(amount, 1.0) -> "one $unit"
            nearly(amount, 0.5) -> "half ${articleFor(unit)} $unit"
            nearly(amount, 1.0 / 3.0) -> "third of ${articleFor(unit)} $unit"
            nearly(amount, 2.0 / 3.0) -> "two thirds of ${articleFor(unit)} $unit"
            nearly(amount, 0.25) -> "quarter of ${articleFor(unit)} $unit"
            nearly(amount, 0.75) -> "three quarters of ${articleFor(unit)} $unit"
            else -> "${amount.formatClean()} ${pluralizedUnit(unit, amount)}"
        }
    }

    private fun resolveItems(
        facts: LabelNutritionFacts,
        amountText: String,
    ): LabelPortionResolution {
        val amount = amountText.toItemAmount()
            ?: return LabelPortionResolution(null, null, null, null, null, isValidAmount = false)
        val unit = itemUnit(facts)
        val grams = gramsForItemAmount(facts, amount)
        val milliliters = millilitersForItemAmount(facts, amount)
        val calories = caloriesForItemAmount(facts, amount, grams, milliliters)
        return LabelPortionResolution(
            amount = amount,
            unit = unit,
            grams = grams,
            milliliters = milliliters,
            calories = calories,
            isValidAmount = true,
        )
    }

    private fun resolveMeasure(
        facts: LabelNutritionFacts,
        amountText: String,
    ): LabelPortionResolution {
        val match = Regex("""^(\d+(?:[.,]\d+)?)\s*(g|ml)$""", RegexOption.IGNORE_CASE)
            .matchEntire(amountText.replace(" ", ""))
            ?: return LabelPortionResolution(null, null, null, null, null, isValidAmount = false)
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
            ?: return LabelPortionResolution(null, null, null, null, null, isValidAmount = false)
        val unit = match.groupValues[2].lowercase(Locale.US)
        val grams = if (unit == "g") amount else null
        val milliliters = if (unit == "ml") amount else null
        val calories = when (unit) {
            "g" -> facts.kcalPer100g?.let { it * amount / 100.0 }
            "ml" -> facts.kcalPer100ml?.let { it * amount / 100.0 }
            else -> null
        }
        return LabelPortionResolution(
            amount = amount,
            unit = unit,
            grams = grams,
            milliliters = milliliters,
            calories = calories,
            isValidAmount = true,
        )
    }

    private fun gramsForItemAmount(
        facts: LabelNutritionFacts,
        amount: Double,
    ): Double? {
        val servingAmount = facts.servingAmount?.takeIf { it > 0.0 }
        val servingGrams = facts.servingSizeGrams
        if (servingAmount != null && servingGrams != null && facts.servingItemUnit != null) {
            return amount / servingAmount * servingGrams
        }

        val packageGrams = facts.packageSizeGrams
        val packageCount = facts.packageItemCount?.takeIf { it > 0.0 }
        if (packageGrams != null && packageCount != null) {
            return amount * packageGrams / packageCount
        }

        return null
    }

    private fun caloriesForItemAmount(
        facts: LabelNutritionFacts,
        amount: Double,
        grams: Double?,
        milliliters: Double?,
    ): Double? {
        val servingAmount = facts.servingAmount?.takeIf { it > 0.0 }
        if (servingAmount != null && facts.kcalPerServing != null && facts.servingItemUnit != null) {
            return amount / servingAmount * facts.kcalPerServing
        }

        if (grams != null && facts.kcalPer100g != null) {
            return facts.kcalPer100g * grams / 100.0
        }

        if (milliliters != null && facts.kcalPer100ml != null) {
            return facts.kcalPer100ml * milliliters / 100.0
        }

        if (nearly(amount, 1.0) && facts.kcalPerServing != null) {
            return facts.kcalPerServing
        }

        return null
    }

    private fun millilitersForItemAmount(
        facts: LabelNutritionFacts,
        amount: Double,
    ): Double? {
        val packageMilliliters = facts.packageSizeMilliliters
        val packageCount = facts.packageItemCount?.takeIf { it > 0.0 }
        if (packageMilliliters != null && packageCount != null) {
            return amount * packageMilliliters / packageCount
        }

        return null
    }
}

fun String.toItemAmount(): Double? {
    val normalized = trim()
        .lowercase(Locale.US)
        .replace("-", " ")
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""\s+of\s+(?:a|an|the)\s+\w+$"""), "")
        .replace(Regex("""\s+(?:can|bag|pack|bar|bottle|pot|tub|item|serving|portion)$"""), "")
    return when (normalized) {
        "one", "a", "an" -> 1.0
        "two thirds", "two third" -> 2.0 / 3.0
        "half", "a half" -> 0.5
        "third", "one third", "a third" -> 1.0 / 3.0
        "quarter", "one quarter", "a quarter" -> 0.25
        "three quarters", "three quarter" -> 0.75
        else -> normalized.replace(" ", "").let { compact ->
            when (compact) {
                "½" -> 0.5
                "⅓" -> 1.0 / 3.0
                "⅔" -> 2.0 / 3.0
                "¼" -> 0.25
                "¾" -> 0.75
                else -> {
                    val fraction = Regex("""^(\d+(?:[.,]\d+)?)/(\d+(?:[.,]\d+)?)$""").matchEntire(compact)
                    if (fraction != null) {
                        val numerator = fraction.groupValues[1].replace(',', '.').toDoubleOrNull()
                        val denominator = fraction.groupValues[2].replace(',', '.').toDoubleOrNull()
                        if (numerator != null && denominator != null && denominator > 0.0) {
                            numerator / denominator
                        } else {
                            null
                        }
                    } else {
                        compact.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
                    }
                }
            }
        }
    }
}

private fun pluralizedUnit(
    unit: String,
    amount: Double,
): String =
    when {
        amount <= 1.0 || nearly(amount, 1.0) -> unit
        unit.endsWith("s") -> unit
        else -> "${unit}s"
    }

private fun articleFor(unit: String): String =
    if (unit.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"

private fun nearly(
    left: Double,
    right: Double,
): Boolean =
    kotlin.math.abs(left - right) < 0.0001

private fun Double.formatClean(): String =
    if (rem(1.0) == 0.0) toInt().toString() else String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')
