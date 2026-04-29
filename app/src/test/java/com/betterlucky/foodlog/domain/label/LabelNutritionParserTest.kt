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
    fun multiplierQuantityExtractsPackGramsAndCount() {
        val facts = parser.parse("6 x 50g sausages\nEnergy 240kcal per 100g")

        assertEquals(300.0, facts.packageSizeGrams ?: 0.0, 0.001)
        assertEquals(6.0, facts.packageItemCount ?: 0.0, 0.001)
        assertEquals(240.0, facts.kcalPer100g ?: 0.0, 0.001)
    }
}
