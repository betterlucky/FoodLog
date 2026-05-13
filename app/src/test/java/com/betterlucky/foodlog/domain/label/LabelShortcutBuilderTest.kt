package com.betterlucky.foodlog.domain.label

import com.betterlucky.foodlog.data.entities.ShortcutPortionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LabelShortcutBuilderTest {
    @Test
    fun measuredAmountWithKnownItemSizeSavesPortionBackedShortcut() {
        val facts = LabelNutritionFacts(
            rawText = "",
            kcalPer100g = 64.0,
            packageSizeGrams = 125.0,
            packageItemCount = 1.0,
            packageItemUnit = "pot",
        )
        val resolved = LabelPortionResolver.resolve(facts, LabelInputMode.MEASURE, "62.5g")

        val shortcut = LabelShortcutBuilder.build(
            facts = facts,
            inputMode = LabelInputMode.MEASURE,
            resolvedPortion = resolved,
            calories = 40.0,
        )

        assertEquals(ShortcutPortionMode.ITEM, shortcut.portionMode)
        assertEquals("pot", shortcut.unit)
        assertEquals("pot", shortcut.itemUnit)
        assertEquals(0.5, shortcut.defaultAmount ?: 0.0, 0.001)
        assertEquals(125.0, shortcut.itemSizeAmount ?: 0.0, 0.001)
        assertEquals("g", shortcut.itemSizeUnit)
        assertEquals(80.0, shortcut.caloriesPerUnit, 0.001)
        assertEquals(64.0, shortcut.kcalPer100g ?: 0.0, 0.001)
    }

    @Test
    fun measuredAmountWithoutKnownItemSizeFallsBackToMeasureShortcut() {
        val facts = LabelNutritionFacts(rawText = "", kcalPer100g = 400.0)
        val resolved = LabelPortionResolver.resolve(facts, LabelInputMode.MEASURE, "50g")

        val shortcut = LabelShortcutBuilder.build(
            facts = facts,
            inputMode = LabelInputMode.MEASURE,
            resolvedPortion = resolved,
            calories = 200.0,
        )

        assertEquals(ShortcutPortionMode.MEASURE, shortcut.portionMode)
        assertEquals("g", shortcut.unit)
        assertEquals(50.0, shortcut.defaultAmount ?: 0.0, 0.001)
        assertEquals(4.0, shortcut.caloriesPerUnit, 0.001)
    }

    @Test
    fun itemAmountKeepsPortionBackedShortcutWithFullItemCalories() {
        val facts = LabelNutritionFacts(
            rawText = "",
            kcalPer100ml = 45.0,
            packageSizeMilliliters = 500.0,
            packageItemCount = 1.0,
            packageItemUnit = "bottle",
        )
        val resolved = LabelPortionResolver.resolve(facts, LabelInputMode.ITEMS, "0.5")

        val shortcut = LabelShortcutBuilder.build(
            facts = facts,
            inputMode = LabelInputMode.ITEMS,
            resolvedPortion = resolved,
            calories = 112.5,
        )

        assertEquals(ShortcutPortionMode.ITEM, shortcut.portionMode)
        assertEquals("bottle", shortcut.unit)
        assertEquals(0.5, shortcut.defaultAmount ?: 0.0, 0.001)
        assertEquals(500.0, shortcut.itemSizeAmount ?: 0.0, 0.001)
        assertEquals("ml", shortcut.itemSizeUnit)
        assertEquals(225.0, shortcut.caloriesPerUnit, 0.001)
    }
}
