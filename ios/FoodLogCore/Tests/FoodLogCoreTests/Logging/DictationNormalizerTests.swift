import XCTest
@testable import FoodLogCore

final class DictationNormalizerTests: XCTestCase {
    private let shortcuts = [
        Shortcut(lookupKey: "tea", name: "Tea", calories: 25, unit: "cup"),
        Shortcut(lookupKey: "toast", name: "Toast", calories: 90, unit: "slice"),
        Shortcut(lookupKey: "banana", name: "Banana", calories: 105, unit: "medium"),
    ]

    private func check(_ spoken: String, _ expected: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(DictationNormalizer.normalize(spoken, shortcuts: shortcuts), expected, file: file, line: line)
    }

    func testDictationBecomesParserText() {
        check("Tea.", "tea")
        check("Two teas.", "2 teas")
        check("A cup of tea.", "1 cup of tea")
        check("I had a tea", "1 tea")
        check("So I just had a coffee.", "1 coffee")
        check("Two bananas and a tea.", "2 bananas, 1 tea")
        check("Yesterday, two teas.", "yesterday 2 teas")
        check("tea yesterday", "yesterday tea")
        check("Half a banana", "0.5 banana")
        check("A couple of teas", "2 teas")
        check("Cup of tea please", "cup of tea")
        check("1.5 bananas", "1.5 bananas")
        check("T", "tea")
    }

    func testDictatedTimes() {
        check("Tea at 7 AM.", "tea at 7am")
        check("Tea at 7:30 p.m.", "tea at 7:30pm")
        check("Tea at seven", "tea at 07:00")
        check("Two slices of toast and a cup of tea at 8:30 a.m.", "2 slices of toast, 1 cup of tea at 8:30am")
    }

    func testSpokenLogsAndKeepsWhatWasSaid() throws {
        let store = try FoodLogStore.inMemory(clock: FixedClock("2026-11-19T08:30:00"))
        try store.seedDefaults()
        let logging = LoggingService(store: store)
        XCTAssertEqual(try logging.quickLogSpoken("Two teas.").message, "Logged Tea 50 kcal. Today: 50 kcal.")
        let pending = try logging.quickLogSpoken("So I just had a coffee.")
        XCTAssertTrue(pending.savedForDetails)
        let entry = try XCTUnwrap(store.pendingEntries(for: store.currentFoodDate()).first)
        XCTAssertEqual(entry.rawText, "1 coffee")
        XCTAssertEqual(entry.notes, "Dictated: “So I just had a coffee.”")
    }
}
