import XCTest
@testable import FoodLogCore

/// Port of Kotlin `LabelNutritionParserTest`.
final class LabelNutritionParserTests: XCTestCase {
    private let parser = LabelNutritionParser()
    private let accuracy = 0.001

    func testTomatoSoupLabelExtractsPreparedCupCalories() {
        let facts = parser.parse("""
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
        """)

        XCTAssertEqual(facts.kcalPer100g ?? 0.0, 33.0, accuracy: accuracy)
        XCTAssertEqual(facts.kcalPerServing ?? 0.0, 83.0, accuracy: accuracy)
        XCTAssertEqual(facts.servingUnit, "cup")
        XCTAssertEqual(facts.fatPer100g ?? 0.0, 0.7, accuracy: accuracy)
        XCTAssertEqual(facts.carbsPer100g ?? 0.0, 6.0, accuracy: accuracy)
        XCTAssertEqual(facts.fiberPer100g ?? 0.0, 0.6, accuracy: accuracy)
        XCTAssertEqual(facts.proteinPer100g ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual(facts.saltPer100g ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertTrue(facts.prepared)
    }

    func testServingSizeWithGramsIsExtracted() {
        let facts = parser.parse("Energy 95 kcal per serving\n1 cup (255g)")

        XCTAssertEqual(facts.kcalPerServing ?? 0.0, 95.0, accuracy: accuracy)
        XCTAssertEqual(facts.servingUnit, "cup")
        XCTAssertEqual(facts.servingSizeGrams ?? 0.0, 255.0, accuracy: accuracy)
    }

    func testGenericServingCaloriesWithoutWeightRemainPartial() {
        let facts = parser.parse("Energy 95 kcal per serving")

        XCTAssertEqual(facts.kcalPerServing ?? 0.0, 95.0, accuracy: accuracy)
        XCTAssertEqual(facts.servingUnit, "serving")
        XCTAssertEqual(facts.servingAmount ?? 0.0, 1.0, accuracy: accuracy)
        XCTAssertEqual(facts.servingItemUnit, "serving")
        XCTAssertNil(facts.servingSizeGrams)
        XCTAssertTrue(facts.isPartial)
    }

    func testHalfCanLabelExtractsServingCaloriesAndSize() {
        let facts = parser.parse("""
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
        """)

        XCTAssertEqual(facts.kcalPer100g ?? 0.0, 135.0, accuracy: accuracy)
        XCTAssertEqual(facts.kcalPerServing ?? 0.0, 265.0, accuracy: accuracy)
        XCTAssertEqual(facts.servingUnit, "1/2 can")
        XCTAssertEqual(facts.servingAmount ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual(facts.servingItemUnit, "can")
        XCTAssertEqual(facts.servingSizeGrams ?? 0.0, 196.0, accuracy: accuracy)
        XCTAssertNil(facts.packageSizeGrams)
        XCTAssertEqual(facts.fatPer100g ?? 0.0, 6.7, accuracy: accuracy)
        XCTAssertEqual(facts.carbsPer100g ?? 0.0, 3.2, accuracy: accuracy)
        XCTAssertEqual(facts.proteinPer100g ?? 0.0, 15.4, accuracy: accuracy)
        XCTAssertEqual(facts.saltPer100g ?? 0.0, 0.51, accuracy: accuracy)
    }

    func testHalfCanOcrTextWithFusedSpacingExtractsCalories() {
        let facts = parser.parse("""
        Nutrition
        Typical values per100g pert/2can pert/2can
        (as consumed) (196g) - (196g) %RI
        Energy 567kJ 1111kd
        135kcal 265kcal 13%
        Fat 6.79 13.19
        Carbohydrate 3.2 6.30
        Protein 15.49 30.19
        Salt 0.519 0.999
        """)

        XCTAssertEqual(facts.kcalPer100g ?? 0.0, 135.0, accuracy: accuracy)
        XCTAssertEqual(facts.kcalPerServing ?? 0.0, 265.0, accuracy: accuracy)
        XCTAssertEqual(facts.servingUnit, "1/2 can")
        XCTAssertEqual(facts.servingAmount ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual(facts.servingItemUnit, "can")
        XCTAssertEqual(facts.servingSizeGrams ?? 0.0, 196.0, accuracy: accuracy)
    }

    func testMultipackQuantityExtractsItemUnit() {
        let facts = parser.parse("""
        Ready salted crisps
        6 x 25g bags
        Typical values per 100g
        Energy 2140kJ 512kcal
        """)

        XCTAssertEqual(facts.packageSizeGrams ?? 0.0, 150.0, accuracy: accuracy)
        XCTAssertEqual(facts.packageItemCount ?? 0.0, 6.0, accuracy: accuracy)
        XCTAssertEqual(facts.packageItemUnit, "bag")
        XCTAssertEqual(facts.kcalPer100g ?? 0.0, 512.0, accuracy: accuracy)
    }

    func testDrinkLabelExtractsKcalPer100mlAndPackageMilliliters() {
        let facts = parser.parse("""
        Orange juice
        250ml bottle
        Typical values per 100ml
        Energy 190kJ 45kcal
        """)

        XCTAssertEqual(facts.kcalPer100ml ?? 0.0, 45.0, accuracy: accuracy)
        XCTAssertNil(facts.kcalPer100g)
        XCTAssertEqual(facts.packageSizeMilliliters ?? 0.0, 250.0, accuracy: accuracy)
    }

    func testMultiplierQuantityExtractsPackGramsAndCount() {
        let facts = parser.parse("6 x 50g sausages\nEnergy 240kcal per 100g")

        XCTAssertEqual(facts.packageSizeGrams ?? 0.0, 300.0, accuracy: accuracy)
        XCTAssertEqual(facts.packageItemCount ?? 0.0, 6.0, accuracy: accuracy)
        XCTAssertEqual(facts.kcalPer100g ?? 0.0, 240.0, accuracy: accuracy)
    }

    func testServingFirstLayoutAssignsKcalCorrectly() {
        let facts = parser.parse("""
        Per serving (30g): 135kcal
        Per 100g: 450kcal
        """)

        XCTAssertEqual(facts.kcalPer100g ?? 0.0, 450.0, accuracy: accuracy)
        XCTAssertEqual(facts.kcalPerServing ?? 0.0, 135.0, accuracy: accuracy)
        XCTAssertEqual(facts.servingSizeGrams ?? 0.0, 30.0, accuracy: accuracy)
    }

    func testServingSizeFormatsAreExtracted() {
        XCTAssertEqual(
            parser.parse("Energy 450kcal per 100g\nServing size: 30g").servingSizeGrams ?? 0.0, 30.0, accuracy: accuracy)
        XCTAssertEqual(
            parser.parse("Energy 135kcal per 30g serving").servingSizeGrams ?? 0.0, 30.0, accuracy: accuracy)
        XCTAssertEqual(
            parser.parse("Energy 112kcal per 100g\nper slice (approx. 9g)\n10kcal").servingSizeGrams ?? 0.0,
            9.0, accuracy: accuracy)
    }

    func testNoKcalValuesLeavesCaloriesNull() {
        let facts = parser.parse("Protein 5g\nCarbohydrate 10g\nFat 2g")

        XCTAssertNil(facts.kcalPer100g)
        XCTAssertNil(facts.kcalPerServing)
        XCTAssertTrue(!facts.hasRequiredCalories)
    }

    func testReferenceIntakeCaloriesAreIgnoredAsServingCalories() {
        let facts = parser.parse("""
        Nutrition
        per 100g
        482kcal
        per 40g portion %RI
        193kcal
        Reference intake of an average adult (8400kJ/2000kcal)
        """)

        XCTAssertEqual(facts.kcalPer100g ?? 0.0, 482.0, accuracy: accuracy)
        XCTAssertEqual(facts.kcalPerServing ?? 0.0, 193.0, accuracy: accuracy)
    }

    func testHighCalorieServingIsPreserved() {
        let facts = parser.parse("""
        Per 100g 262kcal
        Per pack (400g) 1050kcal
        """)

        XCTAssertEqual(facts.kcalPer100g ?? 0.0, 262.0, accuracy: accuracy)
        XCTAssertEqual(facts.kcalPerServing ?? 0.0, 1050.0, accuracy: accuracy)
        XCTAssertEqual(facts.servingSizeGrams ?? 0.0, 400.0, accuracy: accuracy)
    }

    func testPackageSizeIgnoresNutritionTableGrams() {
        let facts = parser.parse("""
        Typical values per 100g per 1/2 can
        Energy 567kJ
        135kcal 265kcal
        Fat 6.7g 13.1g
        of which saturates 1.9g 3.6g
        Carbohydrate 3.2g 6.3g
        """)

        XCTAssertNil(facts.packageSizeGrams)
        XCTAssertNil(facts.packageItemCount)
    }

    func testOcrConfusedHundredAndCaloriesAreNormalized() {
        let facts = parser.parse("Per 10Og: 1060kJ /25Okcal.")

        XCTAssertEqual(facts.kcalPer100g ?? 0.0, 250.0, accuracy: accuracy)
    }

    func testRepeatedOcrOCharactersInAmountsAreNormalized() {
        let weightFacts = parser.parse("Per 1OOg: 1883kJ / 450kcal.")
        let volumeFacts = parser.parse("Per 10Oml: 190kJ / 45kcal.")

        XCTAssertEqual(weightFacts.kcalPer100g ?? 0.0, 450.0, accuracy: accuracy)
        XCTAssertEqual(volumeFacts.kcalPer100ml ?? 0.0, 45.0, accuracy: accuracy)
    }

    func testOcrKealTypoIsAcceptedAsKcal() {
        let facts = parser.parse("""
        per 100g
        310kcal
        per serving
        30g
        93keal
        """)

        XCTAssertEqual(facts.kcalPer100g ?? 0.0, 310.0, accuracy: accuracy)
        XCTAssertEqual(facts.kcalPerServing ?? 0.0, 93.0, accuracy: accuracy)
    }

    func testVisiblePackWeightIsStillExtracted() {
        let facts = parser.parse("""
        6 Pork & Bramley Apple Sausages
        400ge
        Nutrition Typical values per 100g Energy 978kJ 235kcal
        """)

        XCTAssertEqual(facts.packageSizeGrams ?? 0.0, 400.0, accuracy: accuracy)
    }

    func testPackageWeightCanAppearAfterReorderedNutrientLine() {
        let facts = parser.parse("""
        Fat 5g
        400g pack
        Nutrition
        Typical values per 100g
        Energy 978kJ 235kcal
        """)

        XCTAssertEqual(facts.packageSizeGrams ?? 0.0, 400.0, accuracy: accuracy)
    }

    // MARK: - Extra coverage for the Swift port

    func testCommaDecimalsAndUnicodeFractionServing() {
        let facts = parser.parse("Per 100g: 12,5g protein\nPer ½ can\n135kcal 265kcal")
        XCTAssertEqual(facts.servingAmount ?? 0.0, 0.5, accuracy: accuracy)
        XCTAssertEqual(facts.servingUnit, "1/2 can")
        XCTAssertEqual(facts.kcalPer100g ?? 0.0, 135.0, accuracy: accuracy)
    }

    func testCrLfLineEndingsAreSplitLikeKotlinLines() {
        let facts = parser.parse("Per serving (30g): 135kcal\r\nPer 100g: 450kcal")
        XCTAssertEqual(facts.kcalPer100g ?? 0.0, 450.0, accuracy: accuracy)
        XCTAssertEqual(facts.kcalPerServing ?? 0.0, 135.0, accuracy: accuracy)
    }

    func testIsGenericServingUnit() {
        XCTAssertTrue(isGenericServingUnit("Servings"))
        XCTAssertTrue(isGenericServingUnit(" portion "))
        XCTAssertFalse(isGenericServingUnit("can"))
    }
}
