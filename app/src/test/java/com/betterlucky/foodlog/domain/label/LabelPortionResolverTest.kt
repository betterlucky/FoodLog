package com.betterlucky.foodlog.domain.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelPortionResolverTest {
    @Test
    fun halfCanResolvesAgainstParsedServingAmount() {
        val facts = LabelNutritionFacts(
            rawText = "",
            kcalPer100g = 135.0,
            kcalPerServing = 265.0,
            servingUnit = "1/2 can",
            servingAmount = 0.5,
            servingItemUnit = "can",
            servingSizeGrams = 196.0,
        )

        val resolved = LabelPortionResolver.resolve(facts, LabelInputMode.ITEMS, "half")

        assertTrue(resolved.isValidAmount)
        assertEquals(0.5, resolved.amount ?: 0.0, 0.001)
        assertEquals("can", resolved.unit)
        assertEquals(196.0, resolved.grams ?: 0.0, 0.001)
        assertEquals(265.0, resolved.calories ?: 0.0, 0.001)
        assertEquals("half a can", LabelPortionResolver.displayAmount(resolved.amount ?: 0.0, resolved.unit.orEmpty()))
    }

    @Test
    fun oneBagMultipackResolvesFromPackageWeightAndPer100g() {
        val facts = LabelNutritionFacts(
            rawText = "",
            kcalPer100g = 512.0,
            packageSizeGrams = 150.0,
            packageItemCount = 6.0,
            packageItemUnit = "bag",
        )

        val resolved = LabelPortionResolver.resolve(facts, LabelInputMode.ITEMS, "1")

        assertTrue(resolved.isValidAmount)
        assertEquals(1.0, resolved.amount ?: 0.0, 0.001)
        assertEquals("bag", resolved.unit)
        assertEquals(25.0, resolved.grams ?: 0.0, 0.001)
        assertEquals(128.0, resolved.calories ?: 0.0, 0.001)
    }

    @Test
    fun cheeseMeasureResolvesFromPer100g() {
        val facts = LabelNutritionFacts(rawText = "", kcalPer100g = 402.0)

        val resolved = LabelPortionResolver.resolve(facts, LabelInputMode.MEASURE, "40g")

        assertTrue(resolved.isValidAmount)
        assertEquals(40.0, resolved.amount ?: 0.0, 0.001)
        assertEquals("g", resolved.unit)
        assertEquals(40.0, resolved.grams ?: 0.0, 0.001)
        assertEquals(160.8, resolved.calories ?: 0.0, 0.001)
    }

    @Test
    fun drinkMeasureResolvesOnlyFromPer100ml() {
        val facts = LabelNutritionFacts(rawText = "", kcalPer100ml = 45.0, kcalPer100g = 100.0)

        val resolved = LabelPortionResolver.resolve(facts, LabelInputMode.MEASURE, "250ml")

        assertTrue(resolved.isValidAmount)
        assertEquals(250.0, resolved.amount ?: 0.0, 0.001)
        assertEquals("ml", resolved.unit)
        assertEquals(null, resolved.grams)
        assertEquals(112.5, resolved.calories ?: 0.0, 0.001)
    }

    @Test
    fun oneBottleMultipackResolvesMillilitersFromPackageVolumeAndPer100ml() {
        val facts = LabelNutritionFacts(
            rawText = "",
            kcalPer100ml = 45.0,
            packageSizeMilliliters = 1000.0,
            packageItemCount = 4.0,
            packageItemUnit = "bottle",
        )

        val resolved = LabelPortionResolver.resolve(facts, LabelInputMode.ITEMS, "1")

        assertTrue(resolved.isValidAmount)
        assertEquals(1.0, resolved.amount ?: 0.0, 0.001)
        assertEquals("bottle", resolved.unit)
        assertEquals(250.0, resolved.milliliters ?: 0.0, 0.001)
        assertEquals(112.5, resolved.calories ?: 0.0, 0.001)
    }

    @Test
    fun measureRejectsBareNumbers() {
        val facts = LabelNutritionFacts(rawText = "", kcalPer100g = 402.0)

        val resolved = LabelPortionResolver.resolve(facts, LabelInputMode.MEASURE, "40")

        assertFalse(resolved.isValidAmount)
        assertEquals(null, resolved.calories)
    }

    @Test
    fun blankAmountIsInvalidWithoutCalories() {
        val facts = LabelNutritionFacts(rawText = "", kcalPerServing = 100.0, servingItemUnit = "item")

        val resolved = LabelPortionResolver.resolve(facts, LabelInputMode.ITEMS, "")

        assertFalse(resolved.isValidAmount)
        assertEquals(null, resolved.calories)
    }

    @Test
    fun itemAmountAcceptsPhrasesFractionsAndDecimals() {
        assertEquals(0.5, "half".toItemAmount() ?: 0.0, 0.001)
        assertEquals(0.5, "1/2".toItemAmount() ?: 0.0, 0.001)
        assertEquals(0.5, "0.5".toItemAmount() ?: 0.0, 0.001)
        assertEquals(2.5, "2.5".toItemAmount() ?: 0.0, 0.001)
        assertEquals(2.0 / 3.0, "two thirds".toItemAmount() ?: 0.0, 0.001)
        assertEquals(0.75, "three quarters".toItemAmount() ?: 0.0, 0.001)
        assertEquals(1.0 / 3.0, "third can".toItemAmount() ?: 0.0, 0.001)
        assertEquals(2.0 / 3.0, "two thirds of a can".toItemAmount() ?: 0.0, 0.001)
        assertEquals("third of a can", LabelPortionResolver.displayAmount(1.0 / 3.0, "can"))
        assertEquals("two thirds of a can", LabelPortionResolver.displayAmount(2.0 / 3.0, "can"))
    }
}
