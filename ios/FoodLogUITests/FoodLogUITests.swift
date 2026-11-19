import XCTest

/// End-to-end checks of the daily flow against an in-memory store (no data on disk is touched).
final class FoodLogUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-uiTestInMemory"]
        app.launch()
    }

    private var total: String { app.staticTexts["dayTotal"].label }

    private func enter(_ text: String) {
        let field = app.textFields["entryField"]
        field.tap()
        field.typeText(text)
        app.buttons["submitEntry"].tap()
    }

    func testShortcutChipLogsAndUndoRemoves() {
        XCTAssertEqual(total, "0 kcal")
        app.buttons["Tea"].tap()
        XCTAssertTrue(app.staticTexts["Logged Tea 25 kcal"].waitForExistence(timeout: 3))
        XCTAssertEqual(total, "25 kcal")
        app.buttons["Undo"].tap()
        XCTAssertTrue(app.staticTexts["Nothing logged yet today."].waitForExistence(timeout: 3))
        XCTAssertEqual(total, "0 kcal")
    }

    func testTypedShortcutUnknownFoodResolveAndDelete() {
        enter("2 tea")
        XCTAssertTrue(app.staticTexts["50 kcal"].waitForExistence(timeout: 3))

        enter("mystery stew")
        XCTAssertTrue(app.navigationBars["Add details"].waitForExistence(timeout: 3))
        let calories = app.textFields["Calories"]
        calories.tap()
        calories.typeText("350")
        app.buttons["Log"].tap()
        XCTAssertTrue(app.staticTexts["Mystery stew"].waitForExistence(timeout: 3))
        XCTAssertEqual(total, "400 kcal")

        // Saved as a shortcut by default, so it now has a chip and logs in one tap.
        XCTAssertTrue(app.buttons["Mystery stew"].exists)

        app.staticTexts["Mystery stew"].tap()
        XCTAssertTrue(app.navigationBars["Edit"].waitForExistence(timeout: 3))
        app.buttons["Delete"].tap()
        XCTAssertTrue(app.staticTexts["dayTotal"].waitForExistence(timeout: 3))
        XCTAssertEqual(total, "50 kcal")
    }

    func testManualAddAndWeight() {
        app.buttons["More"].tap()
        app.buttons["Add manually"].tap()
        XCTAssertTrue(app.navigationBars["Add food"].waitForExistence(timeout: 3))
        let name = app.textFields["Name"]
        name.tap()
        name.typeText("Apple")
        let calories = app.textFields["Calories"]
        calories.tap()
        calories.typeText("80")
        app.buttons["Log"].tap()
        XCTAssertTrue(app.staticTexts["Apple"].waitForExistence(timeout: 3))

        app.buttons["More"].tap()
        app.buttons["Weight"].tap()
        let weight = app.textFields["Weight"]
        XCTAssertTrue(weight.waitForExistence(timeout: 3))
        weight.tap()
        weight.typeText("92.4")
        app.buttons["Save"].tap()
        XCTAssertTrue(app.staticTexts["92.4 kg"].waitForExistence(timeout: 3))
    }

    func testDayNavigation() {
        app.buttons["Previous day"].tap()
        XCTAssertTrue(app.navigationBars["Yesterday"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["Today"].exists)
        app.buttons["Today"].tap()
        XCTAssertTrue(app.navigationBars["Today"].waitForExistence(timeout: 3))
    }
}

/// Opt-in: imports an export staged in the simulator app's Documents/Import folder into the real
/// on-disk store. Run with TEST_RUNNER_FOODLOG_IMPORT_UI_TEST=1 on a fresh simulator install.
final class FoodLogImportUITests: XCTestCase {
    func testImportStagedAndroidExport() throws {
        guard ProcessInfo.processInfo.environment["FOODLOG_IMPORT_UI_TEST"] != nil else {
            throw XCTSkip("Set TEST_RUNNER_FOODLOG_IMPORT_UI_TEST=1 to run")
        }
        let app = XCUIApplication()
        app.launch()
        app.buttons["More"].tap()
        app.buttons["Settings"].tap()
        let importButton = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Import foodlog-export'")).firstMatch
        XCTAssertTrue(importButton.waitForExistence(timeout: 3))
        importButton.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Imported '")).firstMatch
            .waitForExistence(timeout: 5))
        app.buttons["Done"].tap()
        app.buttons["More"].tap()
        app.buttons["Pick a day"].tap()
    }
}

/// The AI estimate flow against canned Worker answers (DEBUG-only stub, no network).
final class FoodLogAIUITests: XCTestCase {
    func testEstimateFillsReviewedDraftAndLogsAsEstimate() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestInMemory", "-uiTestAIStub"]
        app.launch()

        let field = app.textFields["entryField"]
        field.tap()
        field.typeText("friend's chilli")
        app.buttons["submitEntry"].tap()
        XCTAssertTrue(app.navigationBars["Add details"].waitForExistence(timeout: 3))

        app.buttons["Estimate with AI"].tap()
        XCTAssertTrue(app.staticTexts["estimateSummary"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.textFields["Calories"].value as? String, "420")
        app.buttons["Log"].tap()

        XCTAssertTrue(app.staticTexts["Beef chilli"].waitForExistence(timeout: 3))
        XCTAssertEqual(app.staticTexts["dayTotal"].label, "420 kcal")
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'estimate'")).firstMatch.exists)
    }
}

final class FoodLogBackfillUITests: XCTestCase {
    func testFillInAddsProteinFromOneEstimatePerFood() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestInMemory", "-uiTestAIStub"]
        app.launch()

        let field = app.textFields["entryField"]
        field.tap()
        field.typeText("2 tea")
        app.buttons["submitEntry"].tap()
        XCTAssertTrue(app.buttons["fillNutrients"].waitForExistence(timeout: 3))
        app.buttons["fillNutrients"].tap()
        // The stub estimates 32 g protein for 420 kcal; two teas are 50 kcal.
        XCTAssertTrue(app.staticTexts["3.8 g protein"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["fillNutrients"].exists)
    }
}

final class FoodLogIntentUITests: XCTestCase {
    func testDictatedTextIsTidiedAndUnresolvedEntryCanBeDiscarded() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestInMemory", "-uiTestIntentText", "Two teas and a mystery pie."]
        app.launch()
        let pending = app.staticTexts["2 teas, 1 mystery pie"]
        XCTAssertTrue(pending.waitForExistence(timeout: 5))
        pending.tap()
        XCTAssertTrue(app.navigationBars["Add details"].waitForExistence(timeout: 3))
        app.buttons["Discard entry"].tap()
        XCTAssertFalse(pending.waitForExistence(timeout: 2))
    }

    func testLogFoodIntentUpdatesTheOpenApp() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestInMemory", "-uiTestIntentText", "2 tea"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Tea"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["dayTotal"].label, "50 kcal")
    }
}
