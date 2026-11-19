import XCTest
@testable import FoodLogCore

final class SpokenMatchTests: XCTestCase {
    private let shortcuts = [
        Shortcut(lookupKey: "tea", name: "Tea", calories: 25, unit: "cup"),
        Shortcut(lookupKey: "toast", name: "Toast", calories: 90, unit: "slice"),
        Shortcut(lookupKey: "banana", name: "Banana", calories: 105, unit: "medium"),
    ]

    func testSoundex() {
        XCTAssertEqual(SpokenMatch.soundex("Robert"), "R163")
        XCTAssertEqual(SpokenMatch.soundex("Tymczak"), "T522")
        XCTAssertEqual(SpokenMatch.soundex("T"), SpokenMatch.soundex("tea"))
        XCTAssertNotEqual(SpokenMatch.soundex("T"), SpokenMatch.soundex("toast"))
    }

    func testMisheardShortNamesResolveToTheOneShortcutTheySoundLike() {
        XCTAssertEqual(SpokenMatch.correct("T", shortcuts: shortcuts), "tea")
        XCTAssertEqual(SpokenMatch.correct("Tee.", shortcuts: shortcuts), "tea")
        XCTAssertEqual(SpokenMatch.correct("2 T", shortcuts: shortcuts), "2 tea")
        XCTAssertEqual(SpokenMatch.correct("bananna", shortcuts: shortcuts), "banana")
    }

    func testOrdinaryTextIsLeftAlone() {
        XCTAssertEqual(SpokenMatch.correct("tea", shortcuts: shortcuts), "tea")
        XCTAssertEqual(SpokenMatch.correct("toast and jam", shortcuts: shortcuts), "toast and jam")
        XCTAssertEqual(SpokenMatch.correct("lasagne", shortcuts: shortcuts), "lasagne")
        XCTAssertEqual(SpokenMatch.correct("Log in Food Log", shortcuts: shortcuts), "Log in Food Log")
    }

    func testSpokenQuickLogUsesTheShortcut() throws {
        let store = try FoodLogStore.inMemory(clock: FixedClock("2026-11-19T08:30:00"))
        try store.seedDefaults()
        let result = try LoggingService(store: store).quickLogSpoken("T")
        XCTAssertEqual(result.message, "Logged Tea 25 kcal. Today: 25 kcal.")
    }
}
