import XCTest
@testable import FoodLogCore

final class QuickLogTests: XCTestCase {
    private var store: FoodLogStore!
    private var logging: LoggingService!

    override func setUpWithError() throws {
        store = try FoodLogStore.inMemory(clock: FixedClock("2026-11-19T08:30:00"))
        try store.seedDefaults()
        logging = LoggingService(store: store)
    }

    func testShortcutTextLogsAndReportsTheDay() throws {
        let result = try logging.quickLog("2 tea")
        XCTAssertEqual(result.message, "Logged Tea 50 kcal. Today: 50 kcal.")
        XCTAssertEqual(result.loggedItemIds.count, 1)
    }

    func testShortcutEntityWithAndWithoutQuantity() throws {
        XCTAssertEqual(try logging.quickLog(shortcutKey: "tea").message, "Logged Tea 25 kcal. Today: 25 kcal.")
        XCTAssertEqual(try logging.quickLog(shortcutKey: "tea", quantity: 2).message, "Logged Tea 50 kcal. Today: 75 kcal.")
    }

    func testUnknownFoodIsSavedForDetailsNotGuessed() throws {
        let result = try logging.quickLog("friend's lasagne")
        XCTAssertTrue(result.savedForDetails)
        XCTAssertEqual(result.message, "Saved “friend's lasagne”. Add the details in FoodLog when you can.")
        XCTAssertEqual(try store.pendingEntries(for: store.currentFoodDate()).count, 1)
        XCTAssertEqual(try store.dayTotals(for: store.currentFoodDate()).itemCount, 0)
    }

    func testYesterdayFollowsTheText() throws {
        let result = try logging.quickLog("yesterday tea")
        XCTAssertTrue(result.message.hasPrefix("Logged for yesterday"), result.message)
        XCTAssertTrue(result.message.hasSuffix("That day: 25 kcal."), result.message)
    }

    func testSummaryIncludesKnownNutrients() throws {
        _ = try logging.resolve(logging.manualContext(on: store.currentFoodDate()),
                                drafts: [FoodDraft(name: "Skyr", calories: 1234, proteinG: 20)],
                                consumedTime: nil, saveShortcuts: [])
        XCTAssertEqual(try logging.todaySummary(), "Today: 1,234 kcal, 20 g protein.")
    }
}
