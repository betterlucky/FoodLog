import XCTest
@testable import FoodLogCore

/// Port of Kotlin `LabelPortionResolverTest`, plus protein/fibre coverage (new on iOS).
final class LabelPortionResolverTests: XCTestCase {
    private let accuracy = 0.001

    func testHalfCanResolvesAgainstParsedServingAmount() {
        let facts = LabelNutritionFacts(
            rawText: "", kcalPer100g: 135.0, kcalPerServing: 265.0, servingUnit: "1/2 can", servingAmount: 0.5,
            servingItemUnit: "can", servingSizeGrams: 196.0)

        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "half")

        XCTAssertTrue(resolved.isValidAmount)
        XCTAssertEqual(resolved.amount ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual(resolved.unit, "can")
        XCTAssertEqual(resolved.grams ?? 0.0, 196.0, accuracy: accuracy)
        XCTAssertEqual(resolved.calories ?? 0.0, 265.0, accuracy: accuracy)
        XCTAssertEqual(LabelPortionResolver.displayAmount(resolved.amount ?? 0.0, unit: resolved.unit ?? ""), "half a can")
    }

    func testOneBagMultipackResolvesFromPackageWeightAndPer100g() {
        let facts = LabelNutritionFacts(
            rawText: "", kcalPer100g: 512.0, packageSizeGrams: 150.0, packageItemCount: 6.0, packageItemUnit: "bag")

        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "1")

        XCTAssertTrue(resolved.isValidAmount)
        XCTAssertEqual(resolved.amount ?? 0.0, 1.0, accuracy: accuracy)
        XCTAssertEqual(resolved.unit, "bag")
        XCTAssertEqual(resolved.grams ?? 0.0, 25.0, accuracy: accuracy)
        XCTAssertEqual(resolved.calories ?? 0.0, 128.0, accuracy: accuracy)
    }

    func testCheeseMeasureResolvesFromPer100g() {
        let facts = LabelNutritionFacts(rawText: "", kcalPer100g: 402.0)

        let resolved = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "40g")

        XCTAssertTrue(resolved.isValidAmount)
        XCTAssertEqual(resolved.amount ?? 0.0, 40.0, accuracy: accuracy)
        XCTAssertEqual(resolved.unit, "g")
        XCTAssertEqual(resolved.grams ?? 0.0, 40.0, accuracy: accuracy)
        XCTAssertEqual(resolved.calories ?? 0.0, 160.8, accuracy: accuracy)
    }

    func testMeasureResolvesFromServingCaloriesAndServingWeightWhenPer100gMissing() {
        let facts = LabelNutritionFacts(rawText: "", kcalPerServing: 135.0, servingSizeGrams: 30.0)

        let resolved = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "15g")

        XCTAssertTrue(resolved.isValidAmount)
        XCTAssertEqual(resolved.amount ?? 0.0, 15.0, accuracy: accuracy)
        XCTAssertEqual(resolved.unit, "g")
        XCTAssertEqual(resolved.grams ?? 0.0, 15.0, accuracy: accuracy)
        XCTAssertEqual(resolved.calories ?? 0.0, 67.5, accuracy: accuracy)
    }

    func testItemModeDoesNotTreatAmbiguousServingCaloriesAsOneItem() {
        let facts = LabelNutritionFacts(rawText: "", kcalPerServing: 135.0)

        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "1")

        XCTAssertTrue(resolved.isValidAmount)
        XCTAssertEqual(resolved.unit, "item")
        XCTAssertNil(resolved.calories)
    }

    func testItemModeDoesNotTreatGenericServingCaloriesAsOneItem() {
        let facts = LabelNutritionFacts(
            rawText: "", kcalPerServing: 135.0, servingUnit: "serving", servingAmount: 1.0, servingItemUnit: "serving")

        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "1")

        XCTAssertTrue(resolved.isValidAmount)
        XCTAssertEqual(resolved.unit, "serving")
        XCTAssertNil(resolved.calories)
    }

    func testParsedGenericServingCaloriesDoNotPrefillItemCalories() {
        let facts = LabelNutritionParser().parse("Energy 135 kcal per serving")

        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "1")

        XCTAssertTrue(facts.isPartial)
        XCTAssertTrue(resolved.isValidAmount)
        XCTAssertEqual(resolved.unit, "serving")
        XCTAssertNil(resolved.calories)
    }

    func testItemModeDoesNotTreatGenericPortionCaloriesAsOneItem() {
        let facts = LabelNutritionFacts(
            rawText: "", kcalPerServing: 135.0, servingUnit: "2 portions", servingAmount: 2.0, servingItemUnit: "Portions")

        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "1")

        XCTAssertTrue(resolved.isValidAmount)
        XCTAssertEqual(resolved.unit, "Portions")
        XCTAssertNil(resolved.calories)
    }

    func testItemModeCanUseGenericServingCaloriesWhenServingWeightIsKnown() {
        let facts = LabelNutritionFacts(
            rawText: "", kcalPerServing: 135.0, servingUnit: "serving", servingAmount: 1.0,
            servingItemUnit: "serving", servingSizeGrams: 30.0)

        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "1")

        XCTAssertTrue(resolved.isValidAmount)
        XCTAssertEqual(resolved.unit, "serving")
        XCTAssertEqual(resolved.grams ?? 0.0, 30.0, accuracy: accuracy)
        XCTAssertEqual(resolved.calories ?? 0.0, 135.0, accuracy: accuracy)
    }

    func testDrinkMeasureResolvesOnlyFromPer100ml() {
        let facts = LabelNutritionFacts(rawText: "", kcalPer100g: 100.0, kcalPer100ml: 45.0)

        let resolved = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "250ml")

        XCTAssertTrue(resolved.isValidAmount)
        XCTAssertEqual(resolved.amount ?? 0.0, 250.0, accuracy: accuracy)
        XCTAssertEqual(resolved.unit, "ml")
        XCTAssertNil(resolved.grams)
        XCTAssertEqual(resolved.calories ?? 0.0, 112.5, accuracy: accuracy)
    }

    func testOneBottleMultipackResolvesMillilitersFromPackageVolumeAndPer100ml() {
        let facts = LabelNutritionFacts(
            rawText: "", kcalPer100ml: 45.0, packageSizeMilliliters: 1000.0, packageItemCount: 4.0,
            packageItemUnit: "bottle")

        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "1")

        XCTAssertTrue(resolved.isValidAmount)
        XCTAssertEqual(resolved.amount ?? 0.0, 1.0, accuracy: accuracy)
        XCTAssertEqual(resolved.unit, "bottle")
        XCTAssertEqual(resolved.milliliters ?? 0.0, 250.0, accuracy: accuracy)
        XCTAssertEqual(resolved.calories ?? 0.0, 112.5, accuracy: accuracy)
    }

    func testMeasureRejectsBareNumbers() {
        let facts = LabelNutritionFacts(rawText: "", kcalPer100g: 402.0)

        let resolved = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "40")

        XCTAssertFalse(resolved.isValidAmount)
        XCTAssertNil(resolved.calories)
    }

    func testBlankAmountIsInvalidWithoutCalories() {
        let facts = LabelNutritionFacts(rawText: "", kcalPerServing: 100.0, servingItemUnit: "item")

        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "")

        XCTAssertFalse(resolved.isValidAmount)
        XCTAssertNil(resolved.calories)
    }

    func testItemAmountAcceptsPhrasesFractionsAndDecimals() {
        XCTAssertEqual("half".toItemAmount() ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual("1/2".toItemAmount() ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual("0.5".toItemAmount() ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual("2.5".toItemAmount() ?? 0.0, 2.5, accuracy: accuracy)
        XCTAssertEqual("two thirds".toItemAmount() ?? 0.0, 2.0 / 3.0, accuracy: accuracy)
        XCTAssertEqual("three quarters".toItemAmount() ?? 0.0, 0.75, accuracy: accuracy)
        XCTAssertEqual("third can".toItemAmount() ?? 0.0, 1.0 / 3.0, accuracy: accuracy)
        XCTAssertEqual("two thirds of a can".toItemAmount() ?? 0.0, 2.0 / 3.0, accuracy: accuracy)
        XCTAssertEqual(LabelPortionResolver.displayAmount(1.0 / 3.0, unit: "can"), "third of a can")
        XCTAssertEqual(LabelPortionResolver.displayAmount(2.0 / 3.0, unit: "can"), "two thirds of a can")
    }

    // MARK: - Extra coverage for the Swift port

    func testItemAmountUnicodeFractionsCommasAndRejects() {
        XCTAssertEqual("½".toItemAmount() ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual("¾".toItemAmount() ?? 0.0, 0.75, accuracy: accuracy)
        XCTAssertEqual("1,5".toItemAmount() ?? 0.0, 1.5, accuracy: accuracy)
        XCTAssertEqual("a".toItemAmount() ?? 0.0, 1.0, accuracy: accuracy)
        XCTAssertEqual("2 cans".toItemAmount(), nil)
        XCTAssertNil("0".toItemAmount())
        XCTAssertNil("1/0".toItemAmount())
        XCTAssertNil("abc".toItemAmount())
        XCTAssertNil("inf".toItemAmount())
    }

    func testDisplayAmountPluralisesAndFormats() {
        XCTAssertEqual(LabelPortionResolver.displayAmount(2.0, unit: "pot"), "2 pots")
        XCTAssertEqual(LabelPortionResolver.displayAmount(2.5, unit: "bars"), "2.5 bars")
        XCTAssertEqual(LabelPortionResolver.displayAmount(1.0, unit: "pot"), "one pot")
        XCTAssertEqual(LabelPortionResolver.displayAmount(0.5, unit: "egg"), "half an egg")
        XCTAssertEqual(LabelPortionResolver.displayAmount(0.1, unit: "bar"), "0.1 bar")
        // Java's %.2f rounds half up on the shortest decimal digits.
        XCTAssertEqual(LabelPortionResolver.displayAmount(2.125, unit: "bar"), "2.13 bars")
        XCTAssertEqual(LabelPortionResolver.displayAmount(1.005, unit: "bar"), "1.01 bars")
    }

    func testInputModeStorage() {
        XCTAssertEqual(LabelInputMode.fromStorage("MEASURE"), .measure)
        XCTAssertEqual(LabelInputMode.fromStorage("ITEMS"), .items)
        XCTAssertEqual(LabelInputMode.fromStorage("nonsense"), .items)
        XCTAssertEqual(LabelInputMode.fromStorage(nil), .items)
        XCTAssertEqual(LabelInputMode.measure.storageValue, "MEASURE")
    }

    func testMeasureIsCaseInsensitiveAndAcceptsCommaDecimal() {
        let facts = LabelNutritionFacts(rawText: "", kcalPer100g: 400.0)
        let resolved = LabelPortionResolver.resolve(facts, mode: .measure, amountText: " 12,5 G ")
        XCTAssertTrue(resolved.isValidAmount)
        XCTAssertEqual(resolved.amount ?? 0.0, 12.5, accuracy: accuracy)
        XCTAssertEqual(resolved.unit, "g")
        XCTAssertEqual(resolved.calories ?? 0.0, 50.0, accuracy: accuracy)
    }

    // MARK: - Protein and fibre (new on iOS)

    func testMeasureResolvesProteinAndFibreFromPer100g() {
        let facts = LabelNutritionFacts(rawText: "", kcalPer100g: 402.0, proteinPer100g: 25.0, fiberPer100g: 2.0)
        let resolved = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "40g")
        XCTAssertEqual(resolved.proteinG ?? 0.0, 10.0, accuracy: accuracy)
        XCTAssertEqual(resolved.fibreG ?? 0.0, 0.8, accuracy: accuracy)
    }

    func testItemResolvesProteinFromPackageGrams() {
        let facts = LabelNutritionFacts(
            rawText: "", kcalPer100g: 512.0, packageSizeGrams: 150.0, packageItemCount: 6.0, packageItemUnit: "bag",
            proteinPer100g: 8.0)
        let resolved = LabelPortionResolver.resolve(facts, mode: .items, amountText: "2")
        XCTAssertEqual(resolved.proteinG ?? 0.0, 4.0, accuracy: accuracy)
        XCTAssertNil(resolved.fibreG)
    }

    func testDrinkNutrientsUseMillilitres() {
        let facts = LabelNutritionFacts(rawText: "", kcalPer100ml: 45.0, proteinPer100g: 3.4)
        let ml = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "250ml")
        XCTAssertEqual(ml.proteinG ?? 0.0, 8.5, accuracy: accuracy)
        let grams = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "250g")
        XCTAssertNil(grams.proteinG)
    }

    func testNutrientsAreNilWithoutPer100CalorieBasisOrWithoutLabelValue() {
        let servingOnly = LabelNutritionFacts(rawText: "", kcalPerServing: 135.0, servingSizeGrams: 30.0, proteinPer100g: 5.0)
        XCTAssertNil(LabelPortionResolver.resolve(servingOnly, mode: .measure, amountText: "15g").proteinG)
        let noProtein = LabelNutritionFacts(rawText: "", kcalPer100g: 400.0)
        let resolved = LabelPortionResolver.resolve(noProtein, mode: .measure, amountText: "50g")
        XCTAssertNil(resolved.proteinG)
        XCTAssertNil(resolved.fibreG)
    }

    func testParsedLabelProteinFlowsThroughResolver() {
        let facts = LabelNutritionParser().parse("""
        Typical values per 100g
        Energy 1700kJ 402kcal
        Fibre 2.0g
        Protein 25.0g
        """)
        let resolved = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "40g")
        XCTAssertEqual(resolved.proteinG ?? 0.0, 10.0, accuracy: accuracy)
        XCTAssertEqual(resolved.fibreG ?? 0.0, 0.8, accuracy: accuracy)
    }
}
