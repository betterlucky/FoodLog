package com.betterlucky.foodlog.domain.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelNutritionParserTest {
    private val parser = LabelNutritionParser()

    @Test
    fun tomatoSoupLabelExtractsPreparedCupCalories() {
        val facts = parser.parse(
            """
            CREAM OF TOMATO
            nutrition when made up as instructed
            Servings per sachet - 1
            Typical values Per 100g Per cup
            Energy 138kJ 348kJ
            33kcal 83kcal 4%
            Fat 0.7g 1.7g
            Carbohydrate 6.0g 15.0g
            of which sugars 3.9g 9.8g
            Fibre 0.6g 1.4g
            Protein 0.5g 1.2g
            Salt 0.5g 1.1g
            """.trimIndent(),
        )

        assertEquals(33.0, facts.kcalPer100g ?: 0.0, 0.001)
        assertEquals(83.0, facts.kcalPerServing ?: 0.0, 0.001)
        assertEquals("cup", facts.servingUnit)
        assertEquals(0.7, facts.fatPer100g ?: 0.0, 0.001)
        assertEquals(6.0, facts.carbsPer100g ?: 0.0, 0.001)
        assertEquals(0.6, facts.fiberPer100g ?: 0.0, 0.001)
        assertEquals(0.5, facts.proteinPer100g ?: 0.0, 0.001)
        assertEquals(0.5, facts.saltPer100g ?: 0.0, 0.001)
        assertTrue(facts.prepared)
    }

    @Test
    fun servingSizeWithGramsIsExtracted() {
        val facts = parser.parse("Energy 95 kcal per serving\n1 cup (255g)")

        assertEquals(95.0, facts.kcalPerServing ?: 0.0, 0.001)
        assertEquals("cup", facts.servingUnit)
        assertEquals(255.0, facts.servingSizeGrams ?: 0.0, 0.001)
    }

    @Test
    fun halfCanLabelExtractsServingCaloriesAndSize() {
        val facts = parser.parse(
            """
            Chicken in White Sauce
            Typical values per 100g per 1/2 can (196g)
            Energy 567kJ 1111kJ
            135kcal 265kcal 13%
            Fat 6.7g 13.1g
            of which saturates 1.9g 3.6g
            Carbohydrate 3.2g 6.3g
            of which sugars 0.3g 0.5g
            Fibre 0.4g 0.8g
            Protein 15.4g 30.1g
            Salt 0.51g 0.99g
            """.trimIndent(),
        )

        assertEquals(135.0, facts.kcalPer100g ?: 0.0, 0.001)
        assertEquals(265.0, facts.kcalPerServing ?: 0.0, 0.001)
        assertEquals("1/2 can", facts.servingUnit)
        assertEquals(0.5, facts.servingAmount ?: 0.0, 0.001)
        assertEquals("can", facts.servingItemUnit)
        assertEquals(196.0, facts.servingSizeGrams ?: 0.0, 0.001)
        assertEquals(6.7, facts.fatPer100g ?: 0.0, 0.001)
        assertEquals(3.2, facts.carbsPer100g ?: 0.0, 0.001)
        assertEquals(15.4, facts.proteinPer100g ?: 0.0, 0.001)
        assertEquals(0.51, facts.saltPer100g ?: 0.0, 0.001)
    }

    @Test
    fun halfCanOcrTextWithFusedSpacingExtractsCalories() {
        val facts = parser.parse(
            """
            Nutrition
            Typical values per100g pert/2can pert/2can
            (as consumed) (196g) - (196g) %RI
            Energy 567kJ 1111kd
            135kcal 265kcal 13%
            Fat 6.79 13.19
            Carbohydrate 3.2 6.30
            Protein 15.49 30.19
            Salt 0.519 0.999
            """.trimIndent(),
        )

        assertEquals(135.0, facts.kcalPer100g ?: 0.0, 0.001)
        assertEquals(265.0, facts.kcalPerServing ?: 0.0, 0.001)
        assertEquals("1/2 can", facts.servingUnit)
        assertEquals(0.5, facts.servingAmount ?: 0.0, 0.001)
        assertEquals("can", facts.servingItemUnit)
        assertEquals(196.0, facts.servingSizeGrams ?: 0.0, 0.001)
    }

    @Test
    fun multipackQuantityExtractsItemUnit() {
        val facts = parser.parse(
            """
            Ready salted crisps
            6 x 25g bags
            Typical values per 100g
            Energy 2140kJ 512kcal
            """.trimIndent(),
        )

        assertEquals(150.0, facts.packageSizeGrams ?: 0.0, 0.001)
        assertEquals(6.0, facts.packageItemCount ?: 0.0, 0.001)
        assertEquals("bag", facts.packageItemUnit)
        assertEquals(512.0, facts.kcalPer100g ?: 0.0, 0.001)
    }

    @Test
    fun drinkLabelExtractsKcalPer100mlAndPackageMilliliters() {
        val facts = parser.parse(
            """
            Orange juice
            250ml bottle
            Typical values per 100ml
            Energy 190kJ 45kcal
            """.trimIndent(),
        )

        assertEquals(45.0, facts.kcalPer100ml ?: 0.0, 0.001)
        assertEquals(null, facts.kcalPer100g)
        assertEquals(250.0, facts.packageSizeMilliliters ?: 0.0, 0.001)
    }

    @Test
    fun multiplierQuantityExtractsPackGramsAndCount() {
        val facts = parser.parse("6 x 50g sausages\nEnergy 240kcal per 100g")

        assertEquals(300.0, facts.packageSizeGrams ?: 0.0, 0.001)
        assertEquals(6.0, facts.packageItemCount ?: 0.0, 0.001)
        assertEquals(240.0, facts.kcalPer100g ?: 0.0, 0.001)
    }

    @Test
    fun servingFirstLayoutAssignsKcalCorrectly() {
        val facts = parser.parse(
            """
            Per serving (30g): 135kcal
            Per 100g: 450kcal
            """.trimIndent(),
        )

        assertEquals(450.0, facts.kcalPer100g ?: 0.0, 0.001)
        assertEquals(135.0, facts.kcalPerServing ?: 0.0, 0.001)
        assertEquals(30.0, facts.servingSizeGrams ?: 0.0, 0.001)
    }

    @Test
    fun servingSizeFormatsAreExtracted() {
        assertEquals(
            30.0,
            parser.parse("Energy 450kcal per 100g\nServing size: 30g").servingSizeGrams ?: 0.0,
            0.001,
        )
        assertEquals(
            30.0,
            parser.parse("Energy 135kcal per 30g serving").servingSizeGrams ?: 0.0,
            0.001,
        )
    }

    @Test
    fun noKcalValuesLeavesCaloriesNull() {
        val facts = parser.parse("Protein 5g\nCarbohydrate 10g\nFat 2g")

        assertEquals(null, facts.kcalPer100g)
        assertEquals(null, facts.kcalPerServing)
        assertTrue(!facts.hasRequiredCalories)
    }
}
