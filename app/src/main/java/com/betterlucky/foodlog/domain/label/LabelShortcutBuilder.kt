package com.betterlucky.foodlog.domain.label

import com.betterlucky.foodlog.data.entities.ShortcutPortionMode

data class LabelShortcutDraft(
    val caloriesPerUnit: Double,
    val unit: String,
    val defaultAmount: Double?,
    val portionMode: ShortcutPortionMode,
    val itemUnit: String?,
    val itemSizeAmount: Double?,
    val itemSizeUnit: String?,
    val kcalPer100g: Double?,
    val kcalPer100ml: Double?,
)

data class LabelShortcutItemSize(
    val amount: Double,
    val unit: String,
)

object LabelShortcutBuilder {
    fun build(
        facts: LabelNutritionFacts,
        inputMode: LabelInputMode,
        resolvedPortion: LabelPortionResolution,
        calories: Double,
        userProvidedItemSize: LabelShortcutItemSize? = null,
    ): LabelShortcutDraft {
        val itemSize = userProvidedItemSize ?: itemSizeFor(facts, inputMode, resolvedPortion)
        val mode = shortcutMode(inputMode, itemSize, facts)
        val defaultAmount = defaultAmount(mode, resolvedPortion, itemSize)
        val resolvedItemUnit = LabelPortionResolver.itemUnit(facts)
        return LabelShortcutDraft(
            caloriesPerUnit = caloriesPerUnit(
                mode = mode,
                resolvedPortion = resolvedPortion,
                calories = calories,
                itemSize = itemSize,
                facts = facts,
            ),
            unit = unit(mode, resolvedPortion.unit, itemSize?.unit, resolvedItemUnit),
            defaultAmount = defaultAmount,
            portionMode = mode,
            itemUnit = resolvedItemUnit.takeIf { mode == ShortcutPortionMode.ITEM },
            itemSizeAmount = itemSize?.amount,
            itemSizeUnit = itemSize?.unit,
            kcalPer100g = facts.kcalPer100g,
            kcalPer100ml = facts.kcalPer100ml,
        )
    }

    fun inferredItemSize(
        facts: LabelNutritionFacts,
        inputMode: LabelInputMode,
        resolvedPortion: LabelPortionResolution,
    ): LabelShortcutItemSize? =
        itemSizeFor(facts, inputMode, resolvedPortion)

    private fun shortcutMode(
        inputMode: LabelInputMode,
        itemSize: LabelShortcutItemSize?,
        facts: LabelNutritionFacts,
    ): ShortcutPortionMode =
        when {
            itemSize != null && (facts.kcalPer100g != null || facts.kcalPer100ml != null) -> ShortcutPortionMode.ITEM
            inputMode == LabelInputMode.MEASURE -> ShortcutPortionMode.MEASURE
            else -> ShortcutPortionMode.PLAIN
        }

    private fun defaultAmount(
        mode: ShortcutPortionMode,
        resolvedPortion: LabelPortionResolution,
        itemSize: LabelShortcutItemSize?,
    ): Double? =
        when (mode) {
            ShortcutPortionMode.ITEM -> {
                val size = itemSize ?: return resolvedPortion.amount
                when (size.unit) {
                    "g" -> resolvedPortion.grams?.takeIf { it > 0.0 }?.let { it / size.amount }
                    "ml" -> resolvedPortion.milliliters?.takeIf { it > 0.0 }?.let { it / size.amount }
                    else -> null
                } ?: resolvedPortion.amount
            }
            ShortcutPortionMode.MEASURE,
            ShortcutPortionMode.PLAIN -> resolvedPortion.amount
        }

    private fun caloriesPerUnit(
        mode: ShortcutPortionMode,
        resolvedPortion: LabelPortionResolution,
        calories: Double,
        itemSize: LabelShortcutItemSize?,
        facts: LabelNutritionFacts,
    ): Double =
        when (mode) {
            ShortcutPortionMode.ITEM -> {
                val size = itemSize
                when {
                    size?.unit == "g" && facts.kcalPer100g != null -> facts.kcalPer100g * size.amount / 100.0
                    size?.unit == "ml" && facts.kcalPer100ml != null -> facts.kcalPer100ml * size.amount / 100.0
                    else -> resolvedPortion.amount?.takeIf { it > 0.0 }?.let { calories / it } ?: calories
                }
            }
            ShortcutPortionMode.MEASURE ->
                resolvedPortion.amount?.takeIf { it > 0.0 }?.let { calories / it } ?: calories
            ShortcutPortionMode.PLAIN -> calories
        }

    private fun unit(
        mode: ShortcutPortionMode,
        resolvedUnit: String?,
        itemSizeUnit: String?,
        itemUnit: String,
    ): String =
        when (mode) {
            ShortcutPortionMode.ITEM -> itemUnit
            ShortcutPortionMode.MEASURE -> itemSizeUnit ?: resolvedUnit ?: "g"
            ShortcutPortionMode.PLAIN -> resolvedUnit ?: "serving"
        }

    private fun itemSizeFor(
        facts: LabelNutritionFacts,
        inputMode: LabelInputMode,
        resolvedPortion: LabelPortionResolution,
    ): LabelShortcutItemSize? {
        if (inputMode == LabelInputMode.ITEMS) {
            resolvedPortion.grams
                ?.takeIf { it > 0.0 }
                ?.let { grams ->
                    resolvedPortion.amount
                        ?.takeIf { it > 0.0 }
                        ?.let { return LabelShortcutItemSize(amount = grams / it, unit = "g") }
                }
            resolvedPortion.milliliters
                ?.takeIf { it > 0.0 }
                ?.let { milliliters ->
                    resolvedPortion.amount
                        ?.takeIf { it > 0.0 }
                        ?.let { return LabelShortcutItemSize(amount = milliliters / it, unit = "ml") }
                }
        }

        val servingAmount = facts.servingAmount?.takeIf { it > 0.0 }
        if (servingAmount != null && facts.servingItemUnit != null) {
            facts.servingSizeGrams?.takeIf { it > 0.0 }?.let {
                return LabelShortcutItemSize(amount = it / servingAmount, unit = "g")
            }
        }

        val packageItems = facts.packageItemCount?.takeIf { it > 0.0 }
        if (packageItems != null && facts.packageItemUnit != null) {
            facts.packageSizeGrams?.takeIf { it > 0.0 }?.let {
                return LabelShortcutItemSize(amount = it / packageItems, unit = "g")
            }
            facts.packageSizeMilliliters?.takeIf { it > 0.0 }?.let {
                return LabelShortcutItemSize(amount = it / packageItems, unit = "ml")
            }
        }
        return null
    }
}
