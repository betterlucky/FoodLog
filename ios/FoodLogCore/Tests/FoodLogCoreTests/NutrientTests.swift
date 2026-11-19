import XCTest
@testable import FoodLogCore

/// Guards the "add a case and everything follows" promise: each nutrient must round-trip through
/// every model it is declared on, reach day totals and exports, and be stored.
final class NutrientTests: XCTestCase {
    func testEveryNutrientFlowsFromDraftToStoreTotalsAndExport() throws {
        let store = try FoodLogStore.inMemory(clock: FixedClock("2026-11-19T08:30:00"))
        try store.seedDefaults()
        let logging = LoggingService(store: store)
        var draft = FoodDraft(name: "Test food", calories: 100)
        for (index, nutrient) in Nutrient.allCases.enumerated() { draft[nutrient] = Double(index + 1) }
        let id = try logging.resolve(logging.manualContext(on: store.currentFoodDate()), drafts: [draft],
                                     consumedTime: nil, saveShortcuts: [0]).itemIds[0]
        let item = try XCTUnwrap(store.foodItem(id: id))
        let totals = try store.dayTotals(for: store.currentFoodDate())
        let shortcut = try XCTUnwrap(store.shortcut(lookupKey: "test food"))
        let csv = try store.buildJournalCsv(options: .init())
        for (index, nutrient) in Nutrient.allCases.enumerated() {
            let value = Double(index + 1)
            XCTAssertEqual(item[nutrient], value, nutrient.name)
            XCTAssertEqual(totals[nutrient], value, nutrient.name)
            XCTAssertEqual(totals.itemsWith[nutrient], 1, nutrient.name)
            XCTAssertEqual(shortcut[keyPath: nutrient.shortcutPerPortion], value, nutrient.name)
            XCTAssertTrue(JournalCsvExporter.header.contains(nutrient.csvColumn), nutrient.name)
            XCTAssertFalse(nutrient.name.isEmpty)
        }
        let lines = csv.split(separator: "\n").map { $0.split(separator: ",", omittingEmptySubsequences: false).map(String.init) }
        XCTAssertEqual(lines[0].count, lines[1].count)
        for (index, nutrient) in Nutrient.allCases.enumerated() {
            let column = try XCTUnwrap(lines[0].firstIndex(of: nutrient.csvColumn))
            XCTAssertEqual(Double(lines[1][column]), Double(index + 1), nutrient.csvColumn)
        }
        XCTAssertEqual(item.nutrientSource, .manual)
    }

    func testFormattingAndListName() {
        XCTAssertEqual(Nutrient.protein.format(1.7), "1.7 g")
        XCTAssertEqual(Nutrient.protein.format(32.4), "32 g")
        XCTAssertEqual(Nutrient.listName, "Protein and fibre")
        XCTAssertEqual(Nutrient.listName(adding: ["notes"]), "Protein, fibre and notes")
    }
}
