import XCTest
@testable import FoodLogCore

/// Focused tests for `ShortcutResolver`, derived from the shortcut cases in Android's
/// `FoodLogRepositoryInstrumentedTest`, plus protein/fibre coverage (new on iOS).
final class ShortcutResolverTests: XCTestCase {
    private let accuracy = 0.001

    private func part(_ text: String, trigger: String?, quantity: Double = 1.0, unit: String? = nil) -> ParsedFoodPart {
        ParsedFoodPart(normalizedFoodText: text, shortcutTrigger: trigger, quantity: quantity, quantityUnit: unit)
    }

    private var yoghurt: Shortcut {
        Shortcut(
            lookupKey: "yoghurt pot", name: "Yoghurt pot", calories: 80.0, unit: "pot", notes: "From label",
            defaultAmount: 0.5, portionMode: .item, itemUnit: "pot", itemSizeAmount: 125.0, itemSizeUnit: "g",
            kcalPer100g: 64.0)
    }

    private var cheese: Shortcut {
        Shortcut(
            lookupKey: "cheese", name: "Cheese", calories: 4.0, unit: "g", defaultAmount: 50.0,
            portionMode: .measure, kcalPer100g: 400.0)
    }

    // MARK: - shortcutTrigger / hasExplicitQuantity

    func testShortcutTriggerTrimsLowercasesAndCollapsesWhitespace() {
        XCTAssertEqual(ShortcutResolver.shortcutTrigger("  Sourdough   With\tThin Butter "), "sourdough with thin butter")
    }

    func testHasExplicitQuantity() {
        XCTAssertFalse(ShortcutResolver.hasExplicitQuantity(part("Tea ", trigger: "tea")))
        XCTAssertTrue(ShortcutResolver.hasExplicitQuantity(part("tea", trigger: "tea", quantity: 2)))
        XCTAssertTrue(ShortcutResolver.hasExplicitQuantity(part("g tea", trigger: "tea", unit: "g")))
        XCTAssertTrue(ShortcutResolver.hasExplicitQuantity(part("large tea", trigger: "tea")))
        XCTAssertTrue(ShortcutResolver.hasExplicitQuantity(part("tea", trigger: nil)))
    }

    // MARK: - PLAIN

    func testPlainShortcutUsedOnceWithoutQuantity() {
        let resolved = ShortcutResolver.resolve(.seededTea, part: part("tea", trigger: "tea"))
        XCTAssertEqual(resolved.name, "Tea")
        XCTAssertEqual(resolved.amount ?? 0.0, 1.0, accuracy: accuracy)
        XCTAssertEqual(resolved.unit, "cup")
        XCTAssertNil(resolved.grams)
        XCTAssertEqual(resolved.calories, 25.0, accuracy: accuracy)
    }

    func testPlainShortcutQuantityMultipliesAmountAndCalories() {
        let resolved = ShortcutResolver.resolve(.seededTea, part: part("2 teas", trigger: "tea", quantity: 2))
        XCTAssertEqual(resolved.amount ?? 0.0, 2.0, accuracy: accuracy)
        XCTAssertEqual(resolved.calories, 50.0, accuracy: accuracy)
    }

    func testGramPlainShortcutScalesCaloriesByParsedWeight() {
        let shortcut = Shortcut(lookupKey: "sourdough with thin butter", name: "Sourdough with thin butter",
                                calories: 2.4, unit: "g")
        let resolved = ShortcutResolver.resolve(
            shortcut, part: part("100g sourdough with thin butter", trigger: "sourdough with thin butter",
                                 quantity: 100, unit: "g"))
        XCTAssertEqual(resolved.name, "Sourdough with thin butter")
        XCTAssertEqual(resolved.amount ?? 0.0, 100.0, accuracy: accuracy)
        XCTAssertEqual(resolved.unit, "g")
        XCTAssertEqual(resolved.grams ?? 0.0, 100.0, accuracy: accuracy)
        XCTAssertEqual(resolved.calories, 240.0, accuracy: accuracy)
    }

    func testUnitQuantityShortcutScalesCaloriesByParsedAmount() {
        let shortcut = Shortcut(lookupKey: "sourdough", name: "Sourdough", calories: 90.0, unit: "slice")
        let resolved = ShortcutResolver.resolve(
            shortcut, part: part("2 slices sourdough", trigger: "sourdough", quantity: 2, unit: "slice"))
        XCTAssertEqual(resolved.amount ?? 0.0, 2.0, accuracy: accuracy)
        XCTAssertEqual(resolved.unit, "slice")
        XCTAssertEqual(resolved.calories, 180.0, accuracy: accuracy)
    }

    func testPlainShortcutDefaultAmountScalesAmountButNotCalories() {
        let shortcut = Shortcut(lookupKey: "toast", name: "Toast", calories: 90.0, unit: "slice", defaultAmount: 2.0)
        let usual = ShortcutResolver.resolve(shortcut, part: part("toast", trigger: "toast"))
        XCTAssertEqual(usual.amount ?? 0.0, 2.0, accuracy: accuracy)
        XCTAssertEqual(usual.calories, 90.0, accuracy: accuracy)
        let three = ShortcutResolver.resolve(shortcut, part: part("3 toast", trigger: "toast", quantity: 3))
        XCTAssertEqual(three.amount ?? 0.0, 6.0, accuracy: accuracy)
        XCTAssertEqual(three.calories, 270.0, accuracy: accuracy)
    }

    func testPlainShortcutIgnoresNonPositiveDefaultAmount() {
        let shortcut = Shortcut(lookupKey: "toast", name: "Toast", calories: 90.0, unit: "slice", defaultAmount: 0.0)
        XCTAssertEqual(ShortcutResolver.resolve(shortcut, part: part("toast", trigger: "toast")).amount ?? 0.0,
                       1.0, accuracy: accuracy)
    }

    // MARK: - ITEM

    func testItemShortcutUsesUsualAmountButTypedItemQuantityIsAbsolute() {
        let usual = ShortcutResolver.resolve(yoghurt, part: part("yoghurt pot", trigger: "yoghurt pot"))
        XCTAssertEqual(usual.name, "Yoghurt pot")
        XCTAssertEqual(usual.amount ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual(usual.unit, "pot")
        XCTAssertEqual(usual.grams ?? 0.0, 62.5, accuracy: accuracy)
        XCTAssertEqual(usual.calories, 40.0, accuracy: accuracy)

        let explicit = ShortcutResolver.resolve(
            yoghurt, part: part("two yoghurt pots", trigger: "yoghurt pot", quantity: 2, unit: "pot"))
        XCTAssertEqual(explicit.amount ?? 0.0, 2.0, accuracy: accuracy)
        XCTAssertEqual(explicit.unit, "pot")
        XCTAssertEqual(explicit.calories, 160.0, accuracy: accuracy)
    }

    func testItemShortcutByMillilitres() {
        let bottle = Shortcut(
            lookupKey: "juice", name: "Juice", calories: 225.0, unit: "bottle", defaultAmount: 0.5,
            portionMode: .item, itemUnit: "bottle", itemSizeAmount: 500.0, itemSizeUnit: "ml", kcalPer100ml: 45.0)
        let resolved = ShortcutResolver.resolve(bottle, part: part("juice", trigger: "juice"))
        XCTAssertEqual(resolved.amount ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual(resolved.milliliters ?? 0.0, 250.0, accuracy: accuracy)
        XCTAssertNil(resolved.grams)
        XCTAssertEqual(resolved.calories, 112.5, accuracy: accuracy)
    }

    func testItemShortcutWithoutNutritionBasisFallsBackToPerUnitCalories() {
        let shortcut = Shortcut(
            lookupKey: "bar", name: "Bar", calories: 200.0, unit: "bar", defaultAmount: 0.5, portionMode: .item,
            itemUnit: "bar")
        let resolved = ShortcutResolver.resolve(shortcut, part: part("bar", trigger: "bar"))
        XCTAssertEqual(resolved.amount ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual(resolved.unit, "bar")
        XCTAssertNil(resolved.grams)
        XCTAssertEqual(resolved.calories, 100.0, accuracy: accuracy)
        XCTAssertNil(resolved.proteinG)
    }

    // MARK: - MEASURE

    func testMeasureShortcutUsesUsualAmountButTypedMeasureIsAbsolute() {
        let usual = ShortcutResolver.resolve(cheese, part: part("cheese", trigger: "cheese"))
        XCTAssertEqual(usual.amount ?? 0.0, 50.0, accuracy: accuracy)
        XCTAssertEqual(usual.unit, "g")
        XCTAssertEqual(usual.grams ?? 0.0, 50.0, accuracy: accuracy)
        XCTAssertEqual(usual.calories, 200.0, accuracy: accuracy)

        let explicit = ShortcutResolver.resolve(cheese, part: part("100g cheese", trigger: "cheese", quantity: 100, unit: "g"))
        XCTAssertEqual(explicit.amount ?? 0.0, 100.0, accuracy: accuracy)
        XCTAssertEqual(explicit.unit, "g")
        XCTAssertEqual(explicit.calories, 400.0, accuracy: accuracy)
    }

    func testMeasureShortcutNonMeasureQuantityMultipliesUsualAmount() {
        let resolved = ShortcutResolver.resolve(cheese, part: part("2 cheese", trigger: "cheese", quantity: 2))
        XCTAssertEqual(resolved.amount ?? 0.0, 100.0, accuracy: accuracy)
        XCTAssertEqual(resolved.calories, 400.0, accuracy: accuracy)
    }

    func testMeasureShortcutByMillilitres() {
        let drink = Shortcut(
            lookupKey: "juice", name: "Juice", calories: 0.45, unit: "ml", defaultAmount: 250.0,
            portionMode: .measure, kcalPer100ml: 45.0)
        let resolved = ShortcutResolver.resolve(drink, part: part("juice", trigger: "juice"))
        XCTAssertEqual(resolved.unit, "ml")
        XCTAssertEqual(resolved.milliliters ?? 0.0, 250.0, accuracy: accuracy)
        XCTAssertNil(resolved.grams)
        XCTAssertEqual(resolved.calories, 112.5, accuracy: accuracy)
    }

    func testMeasureShortcutWithMismatchedBasisFallsBackToPerUnitCalories() {
        // A gram shortcut asked for millilitres has no per-100ml basis, so the plain per-unit maths applies.
        let resolved = ShortcutResolver.resolve(cheese, part: part("100ml cheese", trigger: "cheese", quantity: 100, unit: "ml"))
        XCTAssertEqual(resolved.unit, "ml")
        XCTAssertEqual(resolved.amount ?? 0.0, 100.0, accuracy: accuracy)
        XCTAssertEqual(resolved.calories, 400.0, accuracy: accuracy)
    }

    func testMeasureShortcutUsesItemSizeUnitWhenShortcutUnitIsNotAMeasure() {
        let shortcut = Shortcut(
            lookupKey: "mix", name: "Mix", calories: 4.0, unit: "scoop", defaultAmount: 30.0, portionMode: .measure,
            itemSizeUnit: "g", kcalPer100g: 400.0)
        let resolved = ShortcutResolver.resolve(shortcut, part: part("mix", trigger: "mix"))
        XCTAssertEqual(resolved.unit, "g")
        XCTAssertEqual(resolved.calories, 120.0, accuracy: accuracy)
    }

    // MARK: - Protein and fibre (new on iOS)

    func testPlainShortcutScalesPerPortionNutrientsByMultiplier() {
        let shortcut = Shortcut(
            lookupKey: "shake", name: "Shake", calories: 150.0, unit: "bottle", proteinPerPortion: 25.0,
            fibrePerPortion: 3.0)
        let one = ShortcutResolver.resolve(shortcut, part: part("shake", trigger: "shake"))
        XCTAssertEqual(one.proteinG ?? 0.0, 25.0, accuracy: accuracy)
        XCTAssertEqual(one.fibreG ?? 0.0, 3.0, accuracy: accuracy)
        let two = ShortcutResolver.resolve(shortcut, part: part("2 shakes", trigger: "shake", quantity: 2))
        XCTAssertEqual(two.proteinG ?? 0.0, 50.0, accuracy: accuracy)
        XCTAssertEqual(two.fibreG ?? 0.0, 6.0, accuracy: accuracy)
        XCTAssertEqual(two.calories, 300.0, accuracy: accuracy)
    }

    func testNutrientsAreNilWhenShortcutHasNone() {
        let resolved = ShortcutResolver.resolve(.seededTea, part: part("tea", trigger: "tea"))
        XCTAssertNil(resolved.proteinG)
        XCTAssertNil(resolved.fibreG)
    }

    func testItemShortcutNutrientsScaleWithResolvedPortion() {
        var shortcut = yoghurt
        shortcut.proteinPer100 = 4.0
        shortcut.fibrePer100 = 0.4
        let usual = ShortcutResolver.resolve(shortcut, part: part("yoghurt pot", trigger: "yoghurt pot"))
        XCTAssertEqual(usual.proteinG ?? 0.0, 2.5, accuracy: accuracy)
        XCTAssertEqual(usual.fibreG ?? 0.0, 0.25, accuracy: accuracy)
        let two = ShortcutResolver.resolve(
            shortcut, part: part("2 yoghurt pots", trigger: "yoghurt pot", quantity: 2, unit: "pot"))
        XCTAssertEqual(two.proteinG ?? 0.0, 10.0, accuracy: accuracy)
        XCTAssertEqual(two.fibreG ?? 0.0, 1.0, accuracy: accuracy)
    }

    func testItemShortcutByMillilitresNutrientsUseMillilitreBasis() {
        let bottle = Shortcut(
            lookupKey: "milk", name: "Milk", calories: 225.0, unit: "bottle", defaultAmount: 0.5,
            portionMode: .item, itemUnit: "bottle", itemSizeAmount: 500.0, itemSizeUnit: "ml", kcalPer100ml: 45.0,
            proteinPer100: 3.4)
        let resolved = ShortcutResolver.resolve(bottle, part: part("milk", trigger: "milk"))
        XCTAssertEqual(resolved.proteinG ?? 0.0, 8.5, accuracy: accuracy)
        XCTAssertNil(resolved.fibreG)
    }

    func testMeasureShortcutNutrientsScaleWithTypedMeasure() {
        var shortcut = cheese
        shortcut.proteinPer100 = 25.0
        let usual = ShortcutResolver.resolve(shortcut, part: part("cheese", trigger: "cheese"))
        XCTAssertEqual(usual.proteinG ?? 0.0, 12.5, accuracy: accuracy)
        let explicit = ShortcutResolver.resolve(shortcut, part: part("100g cheese", trigger: "cheese", quantity: 100, unit: "g"))
        XCTAssertEqual(explicit.proteinG ?? 0.0, 25.0, accuracy: accuracy)
        XCTAssertNil(explicit.fibreG)
    }

    func testRoundTripFromLabelBuilderKeepsNutrients() {
        let facts = LabelNutritionParser().parse("""
        Greek yoghurt 125g pot
        Typical values per 100g
        Energy 270kJ 64kcal
        Fibre 0.3g
        Protein 4.5g
        """)
        let resolvedPortion = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "62.5g")
        let draft = LabelShortcutBuilder.build(
            facts: facts, inputMode: .measure, resolvedPortion: resolvedPortion, calories: 40.0,
            userProvidedItemSize: LabelShortcutItemSize(amount: 125.0, unit: "g"))
        let shortcut = Shortcut(
            lookupKey: "greek yoghurt", name: "Greek yoghurt", calories: draft.caloriesPerUnit, unit: draft.unit,
            defaultAmount: draft.defaultAmount, portionMode: draft.portionMode, itemUnit: draft.itemUnit,
            itemSizeAmount: draft.itemSizeAmount, itemSizeUnit: draft.itemSizeUnit, kcalPer100g: draft.kcalPer100g,
            kcalPer100ml: draft.kcalPer100ml, proteinPer100: draft.proteinPer100, fibrePer100: draft.fibrePer100)

        let resolved = ShortcutResolver.resolve(shortcut, part: part("greek yoghurt", trigger: "greek yoghurt"))
        XCTAssertEqual(resolved.calories, 40.0, accuracy: accuracy)
        XCTAssertEqual(resolved.proteinG ?? 0.0, 2.8125, accuracy: accuracy)
        XCTAssertEqual(resolved.fibreG ?? 0.0, 0.1875, accuracy: accuracy)
    }

    // MARK: - Updated-shortcut helpers

    func testCaloriesForUpdatedShortcut() {
        XCTAssertEqual(ShortcutResolver.caloriesForUpdatedShortcut(yoghurt, amount: 0.5, calories: 40.0), 80.0, accuracy: accuracy)
        XCTAssertEqual(ShortcutResolver.caloriesForUpdatedShortcut(yoghurt, amount: nil, calories: 40.0), 40.0, accuracy: accuracy)
        XCTAssertEqual(ShortcutResolver.caloriesForUpdatedShortcut(yoghurt, amount: 0.0, calories: 40.0), 40.0, accuracy: accuracy)
        XCTAssertEqual(ShortcutResolver.caloriesForUpdatedShortcut(.seededTea, amount: 2.0, calories: 50.0), 50.0, accuracy: accuracy)
    }

    func testUpdatedKcalPer100gAndMl() {
        // ITEM by grams: 100 / size scaling, ml basis untouched.
        XCTAssertEqual(ShortcutResolver.updatedKcalPer100g(yoghurt, updatedCalories: 100.0, unit: "pot") ?? 0.0, 80.0, accuracy: accuracy)
        XCTAssertNil(ShortcutResolver.updatedKcalPer100ml(yoghurt, updatedCalories: 100.0, unit: "pot"))
        // ITEM by grams with no size amount keeps the stored basis.
        var noSize = yoghurt
        noSize.itemSizeAmount = nil
        XCTAssertEqual(ShortcutResolver.updatedKcalPer100g(noSize, updatedCalories: 100.0, unit: nil) ?? 0.0, 64.0, accuracy: accuracy)
        // MEASURE flips the basis for the logged unit and clears the opposite one.
        XCTAssertEqual(ShortcutResolver.updatedKcalPer100g(cheese, updatedCalories: 5.0, unit: "g") ?? 0.0, 500.0, accuracy: accuracy)
        XCTAssertNil(ShortcutResolver.updatedKcalPer100ml(cheese, updatedCalories: 5.0, unit: "g"))
        XCTAssertNil(ShortcutResolver.updatedKcalPer100g(cheese, updatedCalories: 0.5, unit: "ml"))
        XCTAssertEqual(ShortcutResolver.updatedKcalPer100ml(cheese, updatedCalories: 0.5, unit: "ml") ?? 0.0, 50.0, accuracy: accuracy)
        XCTAssertEqual(ShortcutResolver.updatedKcalPer100g(cheese, updatedCalories: 5.0, unit: "slice") ?? 0.0, 400.0, accuracy: accuracy)
        // PLAIN keeps whatever it had.
        XCTAssertNil(ShortcutResolver.updatedKcalPer100g(.seededTea, updatedCalories: 30.0, unit: "cup"))
    }

    func testToLabelFactsOnlyForShortcutsWithABasis() {
        XCTAssertEqual(ShortcutResolver.toLabelFacts(yoghurt)?.packageSizeGrams, 125.0)
        XCTAssertEqual(ShortcutResolver.toLabelFacts(yoghurt)?.packageItemUnit, "pot")
        XCTAssertEqual(ShortcutResolver.toLabelFacts(cheese)?.kcalPer100g, 400.0)
        XCTAssertNil(ShortcutResolver.toLabelFacts(.seededTea))
        var noBasis = yoghurt
        noBasis.kcalPer100g = nil
        XCTAssertNil(ShortcutResolver.toLabelFacts(noBasis))
    }

    func testFormatPlainNumber() {
        XCTAssertEqual(ShortcutResolver.formatPlainNumber(50.0), "50")
        XCTAssertEqual(ShortcutResolver.formatPlainNumber(62.5), "62.5")
    }

    func testShortcutConvenienceMethod() {
        XCTAssertEqual(Shortcut.seededTea.resolve(part: part("tea", trigger: "tea")).calories, 25.0, accuracy: accuracy)
    }
}
