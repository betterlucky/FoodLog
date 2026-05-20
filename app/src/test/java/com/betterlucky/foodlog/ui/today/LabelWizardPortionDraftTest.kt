package com.betterlucky.foodlog.ui.today

import com.betterlucky.foodlog.domain.label.LabelInputMode
import com.betterlucky.foodlog.domain.label.LabelNutritionFacts
import com.betterlucky.foodlog.domain.label.LabelPortionResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelWizardPortionDraftTest {
    @Test
    fun ambiguousPortionChangeClearsDerivedCalories() {
        val facts = LabelNutritionFacts(rawText = "", kcalPer100g = 400.0)
        val measured = LabelPortionResolver.resolve(facts, LabelInputMode.MEASURE, "1g")
        val draft = labelDraft(calories = measured.calories?.formatLabelNumber().orEmpty())

        val item = LabelPortionResolver.resolve(facts, LabelInputMode.ITEMS, "1")
        val updated = draft.withResolvedLabelPortion(
            amount = "1",
            unit = item.unit ?: "item",
            resolvedPortion = item,
        )

        assertEquals("", updated.calories)
        assertFalse(updated.caloriesEditedByUser)
    }

    @Test
    fun ambiguousPortionChangeKeepsUserEnteredCalories() {
        val facts = LabelNutritionFacts(rawText = "", kcalPer100g = 400.0)
        val draft = labelDraft(calories = "123", caloriesEditedByUser = true)

        val item = LabelPortionResolver.resolve(facts, LabelInputMode.ITEMS, "1")
        val updated = draft.withResolvedLabelPortion(
            amount = "1",
            unit = item.unit ?: "item",
            resolvedPortion = item,
        )

        assertEquals("123", updated.calories)
        assertTrue(updated.caloriesEditedByUser)
    }

    @Test
    fun derivedPortionChangeKeepsUserEnteredCalories() {
        val facts = LabelNutritionFacts(rawText = "", kcalPer100g = 400.0)
        val draft = labelDraft(calories = "123", caloriesEditedByUser = true)

        val measured = LabelPortionResolver.resolve(facts, LabelInputMode.MEASURE, "20g")
        val updated = draft.withResolvedLabelPortion(
            amount = "20g",
            unit = measured.unit ?: "g",
            resolvedPortion = measured,
        )

        assertEquals("123", updated.calories)
        assertTrue(updated.caloriesEditedByUser)
    }

    @Test
    fun ambiguousPortionChangeKeepsUserClearedCaloriesEmpty() {
        val facts = LabelNutritionFacts(rawText = "", kcalPer100g = 400.0)
        val draft = labelDraft(calories = "", caloriesEditedByUser = true)

        val item = LabelPortionResolver.resolve(facts, LabelInputMode.ITEMS, "1")
        val updated = draft.withResolvedLabelPortion(
            amount = "1",
            unit = item.unit ?: "item",
            resolvedPortion = item,
        )

        assertEquals("", updated.calories)
        assertTrue(updated.caloriesEditedByUser)
    }

    @Test
    fun ambiguousPortionChangeKeepsUserCaloriesAfterDerivablePortionChange() {
        val facts = LabelNutritionFacts(rawText = "", kcalPer100g = 400.0)
        val draft = labelDraft(calories = "123", caloriesEditedByUser = true)
        val measured = LabelPortionResolver.resolve(facts, LabelInputMode.MEASURE, "20g")
        val derived = draft.withResolvedLabelPortion(
            amount = "20g",
            unit = measured.unit ?: "g",
            resolvedPortion = measured,
        )

        val item = LabelPortionResolver.resolve(facts, LabelInputMode.ITEMS, "1")
        val ambiguous = derived.withResolvedLabelPortion(
            amount = "1",
            unit = item.unit ?: "item",
            resolvedPortion = item,
        )

        assertEquals("123", ambiguous.calories)
        assertTrue(ambiguous.caloriesEditedByUser)
    }

    private fun labelDraft(
        calories: String,
        caloriesEditedByUser: Boolean = false,
    ): LoggingWizardPartDraft =
        LoggingWizardPartDraft(
            inputText = "Label scan",
            lookupKey = null,
            resolvedByDefault = false,
            name = "Test label",
            amount = "1g",
            unit = "g",
            calories = calories,
            caloriesEditedByUser = caloriesEditedByUser,
        )
}
