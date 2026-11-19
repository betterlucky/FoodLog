import XCTest
@testable import FoodLogCore

final class NutrientBackfillTests: XCTestCase {
    private let day = LocalDate(iso: "2026-11-19")!
    private var store: FoodLogStore!
    private var logging: LoggingService!

    override func setUpWithError() throws {
        store = try FoodLogStore.inMemory(clock: FixedClock("2026-11-19T08:30:00"))
        try store.seedDefaults()
        logging = LoggingService(store: store)
    }

    @discardableResult
    private func logManual(_ draft: FoodDraft) throws -> Int64 {
        try logging.resolve(logging.manualContext(on: day), drafts: [draft], consumedTime: nil, saveShortcuts: []).itemIds[0]
    }

    func testShortcutRowsShareOneEstimateWhichAlsoEnrichesTheShortcut() throws {
        guard case .logged(let ids, _) = try logging.submit("2 tea", selectedDate: day) else { return XCTFail() }
        let plan = try logging.backfillNutrientsLocally()
        XCTAssertEqual(plan.filledLocally, 0)
        let tea = try XCTUnwrap(plan.targets.first { $0.shortcutKey == "tea" })
        XCTAssertEqual(tea.itemIds, ids)
        XCTAssertTrue(tea.description.hasSuffix("Tea, about 25 kcal"), tea.description)

        XCTAssertEqual(try logging.applyNutrientEstimate(tea, proteinG: 1, fibreG: 0, forKcal: 25), 1)
        let item = try XCTUnwrap(store.foodItem(id: ids[0]))
        XCTAssertEqual(item.proteinG, 2)
        XCTAssertEqual(item.fibreG, 0)
        XCTAssertEqual(item.nutrientSource, .estimate)
        XCTAssertEqual(item.calories, 50, "calories are never changed")
        XCTAssertEqual(try store.shortcut(lookupKey: "tea")?.proteinPerPortion, 1)

        // The next one-tap log carries nutrients from the shortcut.
        let next = try logging.logShortcut("tea", on: day)
        XCTAssertEqual(next.draft.proteinG, 1)
        XCTAssertEqual(next.draft.nutrientSource, .shortcut)
        // And nothing is left to ask about tea.
        XCTAssertNil(try logging.backfillNutrientsLocally().targets.first { $0.shortcutKey == "tea" })
    }

    func testImportedRowsMatchingAShortcutWithNutrientsFillLocally() throws {
        try store.upsertShortcut(Shortcut(lookupKey: "porridge", name: "Porridge", calories: 200, unit: "bowl",
                                          proteinPerPortion: 8, fibrePerPortion: 4))
        let id = try logManual(FoodDraft(name: "Porridge", calories: 300))
        let plan = try logging.backfillNutrientsLocally()
        XCTAssertEqual(plan.filledLocally, 1)
        let item = try XCTUnwrap(store.foodItem(id: id))
        XCTAssertEqual(item.proteinG, 12)
        XCTAssertEqual(item.fibreG, 6)
        XCTAssertEqual(item.nutrientSource, .shortcut)
    }

    func testUnknownFoodsGroupByNameAndScaleToEachRowsCalories() throws {
        let small = try logManual(FoodDraft(name: "Toast", amount: 1, unit: "slice", calories: 100))
        let large = try logManual(FoodDraft(name: "toast", amount: 2, unit: "slice", calories: 200))
        let target = try XCTUnwrap(logging.backfillNutrientsLocally().targets.first { $0.id == "name:toast" })
        XCTAssertEqual(Set(target.itemIds), [small, large])
        XCTAssertEqual(target.description, "1 slice Toast, about 100 kcal")
        try logging.applyNutrientEstimate(target, proteinG: 3.5, fibreG: 1.2, forKcal: 90)
        XCTAssertEqual(try store.foodItem(id: small)?.proteinG, 3.9)
        XCTAssertEqual(try store.foodItem(id: large)?.proteinG, 7.8)
        XCTAssertEqual(try store.foodItem(id: large)?.fibreG, 2.7)
    }

    func testOnlyMissingValuesAreFilledAndKnownSourceIsKept() throws {
        let id = try logManual(FoodDraft(name: "Yoghurt", calories: 150, proteinG: 10))
        let target = try XCTUnwrap(logging.backfillNutrientsLocally().targets.first { $0.id == "name:yoghurt" })
        try logging.applyNutrientEstimate(target, proteinG: 99, fibreG: 1, forKcal: 150)
        let item = try XCTUnwrap(store.foodItem(id: id))
        XCTAssertEqual(item.proteinG, 10)
        XCTAssertEqual(item.fibreG, 1)
        XCTAssertEqual(item.nutrientSource, .manual)
    }

    func testZeroCalorieEstimatesOnlyRecordNone() {
        XCTAssertEqual(NutrientRatio(proteinG: 0, fibreG: 0, forKcal: 0), NutrientRatio(proteinPerKcal: 0, fibrePerKcal: 0))
        XCTAssertNil(NutrientRatio(proteinG: 5, fibreG: 0, forKcal: 0))
        XCTAssertNil(NutrientRatio(proteinG: nil, fibreG: nil, forKcal: 100))
    }
}
