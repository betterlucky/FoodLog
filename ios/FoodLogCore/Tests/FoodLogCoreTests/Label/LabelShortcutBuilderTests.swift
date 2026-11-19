import XCTest
@testable import FoodLogCore

/// Port of Kotlin `LabelShortcutBuilderTest`, plus protein/fibre coverage (new on iOS).
final class LabelShortcutBuilderTests: XCTestCase {
    private let accuracy = 0.001

    func testMeasuredAmountWithKnownItemSizeSavesPortionBackedShortcut() {
        let facts = LabelNutritionFacts(
            rawText: "", kcalPer100g: 64.0, packageSizeGrams: 125.0, packageItemCount: 1.0, packageItemUnit: "pot")
        let resolved = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "62.5g")

        let shortcut = LabelShortcutBuilder.build(
            facts: facts, inputMode: .measure, resolvedPortion: resolved, calories: 40.0)

        XCTAssertEqual(shortcut.portionMode, .item)
        XCTAssertEqual(shortcut.unit, "pot")
        XCTAssertEqual(shortcut.itemUnit, "pot")
        XCTAssertEqual(shortcut.defaultAmount ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual(shortcut.itemSizeAmount ?? 0.0, 125.0, accuracy: accuracy)
        XCTAssertEqual(shortcut.itemSizeUnit, "g")
        XCTAssertEqual(shortcut.caloriesPerUnit, 80.0, accuracy: accuracy)
        XCTAssertEqual(shortcut.kcalPer100g ?? 0.0, 64.0, accuracy: accuracy)
    }

    func testMeasuredAmountWithoutKnownItemSizeFallsBackToMeasureShortcut() {
        let facts = LabelNutritionFacts(rawText: "", kcalPer100g: 400.0)
        let resolved = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "50g")

        let shortcut = LabelShortcutBuilder.build(
            facts: facts, inputMode: .measure, resolvedPortion: resolved, calories: 200.0)

        XCTAssertEqual(shortcut.portionMode, .measure)
        XCTAssertEqual(shortcut.unit, "g")
        XCTAssertEqual(shortcut.defaultAmount ?? 0.0, 50.0, accuracy: accuracy)
        XCTAssertEqual(shortcut.caloriesPerUnit, 4.0, accuracy: accuracy)
    }

    func testItemAmountKeepsPortionBackedShortcutWithFullItemCalories() {
        let facts = LabelNutritionFacts(
            rawText: "", kcalPer100ml: 45.0, packageSizeMilliliters: 500.0, packageItemCount: 1.0,
            packageItemUnit: "bottle")
        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "0.5")

        let shortcut = LabelShortcutBuilder.build(
            facts: facts, inputMode: .items, resolvedPortion: resolved, calories: 112.5)

        XCTAssertEqual(shortcut.portionMode, .item)
        XCTAssertEqual(shortcut.unit, "bottle")
        XCTAssertEqual(shortcut.defaultAmount ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual(shortcut.itemSizeAmount ?? 0.0, 500.0, accuracy: accuracy)
        XCTAssertEqual(shortcut.itemSizeUnit, "ml")
        XCTAssertEqual(shortcut.caloriesPerUnit, 225.0, accuracy: accuracy)
    }

    func testServingCaloriesAndServingWeightCreateItemBackedShortcutWhenItemIsKnown() {
        let facts = LabelNutritionFacts(
            rawText: "", kcalPerServing: 95.0, servingUnit: "pot", servingAmount: 1.0, servingItemUnit: "pot",
            servingSizeGrams: 125.0)
        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "0.5")

        let shortcut = LabelShortcutBuilder.build(
            facts: facts, inputMode: .items, resolvedPortion: resolved, calories: 47.5)

        XCTAssertEqual(shortcut.portionMode, .item)
        XCTAssertEqual(shortcut.unit, "pot")
        XCTAssertEqual(shortcut.itemUnit, "pot")
        XCTAssertEqual(shortcut.defaultAmount ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual(shortcut.itemSizeAmount ?? 0.0, 125.0, accuracy: accuracy)
        XCTAssertEqual(shortcut.itemSizeUnit, "g")
        XCTAssertEqual(shortcut.caloriesPerUnit, 95.0, accuracy: accuracy)
        XCTAssertEqual(shortcut.kcalPer100g ?? 0.0, 76.0, accuracy: accuracy)
    }

    // MARK: - Extra coverage for the Swift port

    func testUserProvidedItemSizeOverridesInferredSize() {
        let facts = LabelNutritionFacts(rawText: "", kcalPer100g: 400.0)
        let resolved = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "50g")

        let shortcut = LabelShortcutBuilder.build(
            facts: facts, inputMode: .measure, resolvedPortion: resolved, calories: 200.0,
            userProvidedItemSize: LabelShortcutItemSize(amount: 100.0, unit: "g"))

        XCTAssertEqual(shortcut.portionMode, .item)
        XCTAssertEqual(shortcut.defaultAmount ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual(shortcut.caloriesPerUnit, 400.0, accuracy: accuracy)
        XCTAssertEqual(shortcut.itemUnit, "item")
    }

    func testPlainShortcutWhenNothingIsKnown() {
        let facts = LabelNutritionFacts(rawText: "", kcalPerServing: 135.0)
        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "1")

        let shortcut = LabelShortcutBuilder.build(
            facts: facts, inputMode: .items, resolvedPortion: resolved, calories: 135.0)

        XCTAssertEqual(shortcut.portionMode, .plain)
        XCTAssertEqual(shortcut.unit, "item")
        XCTAssertNil(shortcut.itemUnit)
        XCTAssertEqual(shortcut.caloriesPerUnit, 135.0, accuracy: accuracy)
        XCTAssertNil(LabelShortcutBuilder.inferredItemSize(facts: facts, inputMode: .items, resolvedPortion: resolved))
    }

    // MARK: - Protein and fibre (new on iOS)

    func testItemShortcutKeepsLabelProteinAndFibrePer100() {
        let facts = LabelNutritionFacts(
            rawText: "", kcalPer100g: 64.0, packageSizeGrams: 125.0, packageItemCount: 1.0, packageItemUnit: "pot",
            proteinPer100g: 4.5, fiberPer100g: 0.3)
        let resolved = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "62.5g")

        let shortcut = LabelShortcutBuilder.build(
            facts: facts, inputMode: .measure, resolvedPortion: resolved, calories: 40.0)

        XCTAssertEqual(shortcut.proteinPer100 ?? 0.0, 4.5, accuracy: accuracy)
        XCTAssertEqual(shortcut.fibrePer100 ?? 0.0, 0.3, accuracy: accuracy)
        XCTAssertNil(shortcut.proteinPerPortion)
    }

    func testMeasureShortcutKeepsLabelProteinAndFibrePer100() {
        let facts = LabelNutritionFacts(rawText: "", kcalPer100g: 400.0, proteinPer100g: 25.0, fiberPer100g: 2.0)
        let resolved = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "50g")

        let shortcut = LabelShortcutBuilder.build(
            facts: facts, inputMode: .measure, resolvedPortion: resolved, calories: 200.0)

        XCTAssertEqual(shortcut.portionMode, .measure)
        XCTAssertEqual(shortcut.proteinPer100 ?? 0.0, 25.0, accuracy: accuracy)
        XCTAssertEqual(shortcut.fibrePer100 ?? 0.0, 2.0, accuracy: accuracy)
    }

    func testPer100mlLabelKeepsProteinOnTheMillilitreBasis() {
        let facts = LabelNutritionFacts(
            rawText: "", kcalPer100ml: 45.0, packageSizeMilliliters: 500.0, packageItemCount: 1.0,
            packageItemUnit: "bottle", proteinPer100g: 3.4)
        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "0.5")

        let shortcut = LabelShortcutBuilder.build(
            facts: facts, inputMode: .items, resolvedPortion: resolved, calories: 112.5)

        XCTAssertEqual(shortcut.kcalPer100ml ?? 0.0, 45.0, accuracy: accuracy)
        XCTAssertEqual(shortcut.proteinPer100 ?? 0.0, 3.4, accuracy: accuracy)
        XCTAssertNil(shortcut.fibrePer100)
    }

    func testNutrientsNotKeptWhenCaloriesOnlyComeFromServingBasis() {
        let facts = LabelNutritionFacts(
            rawText: "", kcalPerServing: 95.0, servingUnit: "pot", servingAmount: 1.0, servingItemUnit: "pot",
            servingSizeGrams: 125.0, proteinPer100g: 5.0)
        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "0.5")

        let shortcut = LabelShortcutBuilder.build(
            facts: facts, inputMode: .items, resolvedPortion: resolved, calories: 47.5)

        XCTAssertEqual(shortcut.kcalPer100g ?? 0.0, 76.0, accuracy: accuracy)
        XCTAssertNil(shortcut.proteinPer100)
    }

    func testPlainShortcutKeepsPerPortionNutrients() {
        let facts = LabelNutritionFacts(rawText: "", kcalPer100g: 400.0, proteinPer100g: 10.0, fiberPer100g: 4.0)
        // ITEMS mode with no item size and a resolved gram portion that cannot be sized -> plain shortcut.
        let resolved = LabelPortionResolution(
            amount: 1.0, unit: "serving", grams: nil, calories: 120.0, isValidAmount: true,
            proteinG: 3.0, fibreG: 1.2)

        let shortcut = LabelShortcutBuilder.build(
            facts: facts, inputMode: .items, resolvedPortion: resolved, calories: 120.0)

        XCTAssertEqual(shortcut.portionMode, .plain)
        XCTAssertEqual(shortcut.proteinPerPortion ?? 0.0, 3.0, accuracy: accuracy)
        XCTAssertEqual(shortcut.fibrePerPortion ?? 0.0, 1.2, accuracy: accuracy)
    }
}
