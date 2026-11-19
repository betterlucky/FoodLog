import XCTest
@testable import FoodLogCore

final class DeterministicParserTests: XCTestCase {
    private let parser = DeterministicParser()
    private let today = LocalDate(iso: "2026-04-24")!

    private func time(_ iso: String) -> LocalTime { LocalTime(iso: iso)! }
    private func date(_ iso: String) -> LocalDate { LocalDate(iso: iso)! }

    func testTeaPhrasesResolveToTeaShortcut() {
        for input in ["tea", "a tea", "cup of tea", "1 tea", "  CUP   OF   TEA  "] {
            let parsed = parser.parse(input: input, today: today)

            XCTAssertEqual(parsed.shortcutTrigger, "tea", input)
            XCTAssertEqual(parsed.quantity, 1.0, accuracy: 0.001, input)
            XCTAssertEqual(parsed.logDate, today, input)
        }
    }

    func testTeaQuantitiesResolveToTeaShortcutWithAmount() {
        let cases: [(String, Double)] = [("2 tea", 2.0), ("2 teas", 2.0), ("two teas", 2.0), ("three tea", 3.0)]
        for (input, quantity) in cases {
            let parsed = parser.parse(input: input, today: today)

            XCTAssertEqual(parsed.shortcutTrigger, "tea", input)
            XCTAssertEqual(parsed.quantity, quantity, accuracy: 0.001, input)
        }
    }

    func testCompoundEntriesSplitOnCommasAndAnd() {
        let parsed = parser.parse(input: "banana, satsuma and 2 teas", today: today)

        XCTAssertEqual(parsed.parts.map(\.normalizedFoodText), ["banana", "satsuma", "2 teas"])
        XCTAssertEqual(parsed.parts.map(\.shortcutTrigger), ["banana", "satsuma", "tea"])
        XCTAssertEqual(parsed.parts.map(\.quantity), [1.0, 1.0, 2.0])
        XCTAssertNil(parsed.shortcutTrigger)
    }

    func testFruitAndNutMixKeepsAndInsideItemName() {
        let parsed = parser.parse(input: "13:45 10g fruit and nut mix", today: today)

        XCTAssertEqual(parsed.parts.map(\.normalizedFoodText), ["10g fruit and nut mix"])
        XCTAssertEqual(parsed.parts.map(\.shortcutTrigger), ["fruit and nut mix"])
        XCTAssertEqual(parsed.parts.map(\.quantity), [10.0])
        XCTAssertEqual(parsed.consumedTime, time("13:45"))
    }

    func testWeakAndSeparatorDoesNotSplitWithinWithPhrases() {
        let parsed = parser.parse(input: "10pm yoghurt with chia and pumpkin seeds", today: today)

        XCTAssertEqual(parsed.consumedTime, time("22:00"))
        XCTAssertEqual(parsed.normalizedFoodText, "yoghurt with chia and pumpkin seeds")
        XCTAssertEqual(parsed.parts.map(\.normalizedFoodText), ["yoghurt with chia and pumpkin seeds"])
    }

    func testStrongSeparatorsStillSplitWithPhrases() {
        let parsed = parser.parse(input: "1pm 150g sourdough with thin butter + tea", today: today)

        XCTAssertEqual(parsed.consumedTime, time("13:00"))
        XCTAssertEqual(parsed.normalizedFoodText, "150g sourdough with thin butter + tea")
        XCTAssertEqual(parsed.parts.map(\.normalizedFoodText), ["150g sourdough with thin butter", "tea"])
        XCTAssertEqual(parsed.parts.map(\.shortcutTrigger), ["sourdough with thin butter", "tea"])
        XCTAssertEqual(parsed.parts.map(\.quantity), [150.0, 1.0])
        XCTAssertEqual(parsed.parts.map(\.quantityUnit), ["g", nil])
    }

    func testQuantityPrefixesSingularizeCommonFoodPlurals() {
        let parsed = parser.parse(input: "2 bananas and 3 satsumas", today: today)

        XCTAssertEqual(parsed.parts.map(\.shortcutTrigger), ["banana", "satsuma"])
        XCTAssertEqual(parsed.parts.map(\.quantity), [2.0, 3.0])
    }

    func testSharedTimeParserSupportsLogAndEditTimeFormats() {
        XCTAssertEqual(TimeTextParser.parseOrNull("1pm"), time("13:00"))
        XCTAssertEqual(TimeTextParser.parseOrNull("13:45"), time("13:45"))
        XCTAssertEqual(TimeTextParser.parseOrNull("8:05am"), time("08:05"))
    }

    func testPmPrefixDoesNotNeedQuantity() {
        let parsed = parser.parse(input: "10pm yoghurt with chia and pumpkin seeds", today: today)

        XCTAssertEqual(parsed.consumedTime, time("22:00"))
        XCTAssertEqual(parsed.normalizedFoodText, "yoghurt with chia and pumpkin seeds")
    }

    func testAtPrefixAlsoExtractsTime() {
        let parsed = parser.parse(input: "at 10pm tea", today: today)

        XCTAssertEqual(parsed.consumedTime, time("22:00"))
        XCTAssertEqual(parsed.normalizedFoodText, "tea")
    }

    func testSupportedDatePrefixesSetLogDate() {
        XCTAssertEqual(parser.parse(input: "today tea", today: today).logDate, today)
        XCTAssertEqual(parser.parse(input: "yesterday tea", today: today).logDate, today.minusDays(1))
        XCTAssertEqual(parser.parse(input: "2026-04-23 tea", today: today).logDate, date("2026-04-23"))
    }

    func testUnprefixedTextUsesDefaultLogDateButExplicitPrefixesUseCalendarToday() {
        let defaultLogDate = today.minusDays(1)

        XCTAssertEqual(parser.parse(input: "tea", today: today, defaultLogDate: defaultLogDate).logDate, defaultLogDate)
        XCTAssertEqual(parser.parse(input: "today tea", today: today, defaultLogDate: defaultLogDate).logDate, today)
        XCTAssertEqual(parser.parse(input: "yesterday tea", today: today, defaultLogDate: defaultLogDate).logDate, today.minusDays(1))
        XCTAssertEqual(parser.parse(input: "2026-04-23 tea", today: today, defaultLogDate: defaultLogDate).logDate, date("2026-04-23"))
    }

    func testDatePrefixLeavesUnsupportedPhraseForPendingEntry() {
        let parsed = parser.parse(input: "yesterday curry", today: today)

        XCTAssertEqual(parsed.logDate, today.minusDays(1))
        XCTAssertEqual(parsed.normalizedFoodText, "curry")
        XCTAssertEqual(parsed.shortcutTrigger, "curry")
    }

    func testInvalidIsoLikeDatePrefixDoesNotCrash() {
        let parsed = parser.parse(input: "2026-99-99 tea", today: today)

        XCTAssertEqual(parsed.logDate, today)
        XCTAssertEqual(parsed.normalizedFoodText, "2026-99-99 tea")
    }

    func testFractionWordPrefixesResolveToCorrectQuantity() {
        let cases: [(String, Double, String)] = [
            ("half ham", 0.5, "ham"),
            ("a half ham", 0.5, "ham"),
            ("1/2 ham", 0.5, "ham"),
            ("1/3 lasagne", 1.0 / 3.0, "lasagne"),
            ("a third lasagne", 1.0 / 3.0, "lasagne"),
            ("2/3 pack", 2.0 / 3.0, "pack"),
            ("two thirds pack", 2.0 / 3.0, "pack"),
            ("3/4 pizza", 0.75, "pizza"),
            ("1/4 pizza", 0.25, "pizza"),
            ("a quarter pizza", 0.25, "pizza"),
        ]
        for (input, expectedQuantity, expectedTrigger) in cases {
            let parsed = parser.parse(input: input, today: today)
            XCTAssertEqual(parsed.shortcutTrigger, expectedTrigger, "trigger for '\(input)'")
            XCTAssertEqual(parsed.quantity, expectedQuantity, accuracy: 0.001, "quantity for '\(input)'")
        }
    }

    // MARK: - Port-risk additions

    func testSlashSplitsButFractionSlashDoesNot() {
        let parsed = parser.parse(input: "tea/coffee, 1/2 ham", today: today)
        XCTAssertEqual(parsed.parts.map(\.normalizedFoodText), ["tea", "coffee", "1/2 ham"])
        XCTAssertEqual(parsed.parts.map(\.quantity), [1.0, 1.0, 0.5])
    }

    func testUnitQuantitySingularizesUnitAndSkipsOf() {
        let parsed = parser.parse(input: "2 slices of toast", today: today)
        XCTAssertEqual(parsed.parts.first?.shortcutTrigger, "toast")
        XCTAssertEqual(parsed.parts.first?.quantity, 2.0)
        XCTAssertEqual(parsed.parts.first?.quantityUnit, "slice")
    }

    func testDecimalGramQuantityWithSpace() {
        let parsed = parser.parse(input: "12.5 g oats", today: today)
        XCTAssertEqual(parsed.parts.first?.shortcutTrigger, "oats")
        XCTAssertEqual(parsed.parts.first?.quantity, 12.5)
        XCTAssertEqual(parsed.parts.first?.quantityUnit, "g")
    }

    func testTrailingTimeSuffixIsExtracted() {
        let parsed = parser.parse(input: "tea at 8:05am", today: today)
        XCTAssertEqual(parsed.consumedTime, time("08:05"))
        XCTAssertEqual(parsed.normalizedFoodText, "tea")

        let plain = parser.parse(input: "porridge 13:45", today: today)
        XCTAssertEqual(plain.consumedTime, time("13:45"))
        XCTAssertEqual(plain.normalizedFoodText, "porridge")
    }

    func testTimeParserEdgeCases() {
        XCTAssertEqual(TimeTextParser.parseOrNull("12am"), time("00:00"))
        XCTAssertEqual(TimeTextParser.parseOrNull("12:30 PM"), time("12:30"))
        XCTAssertEqual(TimeTextParser.parseOrNull("08:00"), time("08:00"))
        XCTAssertNil(TimeTextParser.parseOrNull("8:00"))
        XCTAssertNil(TimeTextParser.parseOrNull("  "))
        XCTAssertNil(TimeTextParser.parseOrNull("am"))
        XCTAssertNil(TimeTextParser.parseOrNull("nonsense"))
        XCTAssertNil(TimeTextParser.parseOrNull("25:00"))
    }

    func testUppercaseInputIsNormalisedAndRawTextPreserved() {
        let parsed = parser.parse(input: "  YESTERDAY   10PM  Tea ", today: today)
        XCTAssertEqual(parsed.rawText, "  YESTERDAY   10PM  Tea ")
        XCTAssertEqual(parsed.logDate, today.minusDays(1))
        XCTAssertEqual(parsed.consumedTime, time("22:00"))
        XCTAssertEqual(parsed.normalizedFoodText, "tea")
    }
}
