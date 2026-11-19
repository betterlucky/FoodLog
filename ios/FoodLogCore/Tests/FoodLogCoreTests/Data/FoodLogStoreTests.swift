import XCTest
@testable import FoodLogCore

struct FixedClock: Clock {
    var now: Date
    var timeZone: TimeZone = TimeZone(identifier: "Europe/London")!

    /// `iso` is a local wall-clock time in Europe/London, e.g. "2026-11-19T08:30:00".
    init(_ iso: String) {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Europe/London")!
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        now = formatter.date(from: iso)!
    }
}

final class FoodLogStoreTests: XCTestCase {
    private let day = LocalDate(iso: "2026-11-19")!

    private func makeStore(_ clock: String = "2026-11-19T08:30:00") throws -> FoodLogStore {
        let store = try FoodLogStore.inMemory(clock: FixedClock(clock))
        try store.seedDefaults()
        return store
    }

    func testMigrationReachesCurrentVersionAndSeedsTeaOnce() throws {
        let store = try makeStore()
        XCTAssertEqual(store.connection.userVersion, Schema.currentVersion)
        try store.seedDefaults()
        XCTAssertEqual(try store.activeShortcuts().map(\.lookupKey), ["tea"])
        XCTAssertEqual(try store.shortcut(lookupKey: "tea")?.calories, 25)
    }

    func testReopeningAFileDatabaseKeepsData() throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent("foodlog-\(UUID()).sqlite").path
        defer { try? FileManager.default.removeItem(atPath: path) }
        do {
            let store = try FoodLogStore(path: path, clock: FixedClock("2026-11-19T08:30:00"))
            try store.seedDefaults()
            try store.upsertDailyWeight(logDate: day, weightKg: 92.5, measuredTime: LocalTime(iso: "08:00")!)
        }
        let reopened = try FoodLogStore(path: path)
        XCTAssertEqual(try reopened.dailyWeight(for: day)?.weightKg, 92.5)
    }

    func testFoodDayHonoursBoundary() throws {
        let store = try makeStore("2026-11-19T05:30:00")
        XCTAssertEqual(try store.currentFoodDate(), day)
        var settings = try store.settings()
        settings.dayBoundaryTime = LocalTime(iso: "06:00")
        try store.saveSettings(settings)
        XCTAssertEqual(try store.currentFoodDate(), day.minusDays(1))
    }

    func testFoodItemRoundTripTotalsAndRemovalKeepsRawEntry() throws {
        let store = try makeStore()
        let now = store.clock.now
        let rawId = try store.insertRawEntry(RawEntry(createdAt: now, logDate: day, consumedTime: LocalTime(iso: "08:00"),
                                                      rawText: "porridge", entryKind: .text, status: .parsed))
        let item = FoodItem(rawEntryId: rawId, logDate: day, consumedTime: LocalTime(iso: "08:00"), name: "Porridge",
                            shortcutKey: "porridge", amount: 1, unit: "bowl", grams: 250, calories: 310.5,
                            proteinG: 11, fibreG: 7.5, nutrientSource: .label, source: .estimate, confidence: .medium,
                            aiProvenance: AIProvenance(contractVersion: 1, provider: "p", model: "m"), createdAt: now)
        let id = try store.insertFoodItem(item)
        try store.insertFoodItem(FoodItem(rawEntryId: rawId, logDate: day, consumedTime: nil, name: "Tea",
                                          calories: 25, source: .userDefault, confidence: .high, createdAt: now))

        var stored = try XCTUnwrap(store.foodItem(id: id))
        XCTAssertEqual(stored.shortcutKey, "porridge")
        XCTAssertEqual(stored.aiProvenance?.model, "m")
        XCTAssertEqual(stored.nutrientSource, .label)

        let totals = try store.dayTotals(for: day)
        XCTAssertEqual(totals.calories, 335.5)
        XCTAssertEqual(totals.proteinG, 11)
        XCTAssertEqual(totals.itemCount, 2)
        XCTAssertEqual(totals.itemsWithProtein, 1)
        // Null consumed time sorts first, as in Room.
        XCTAssertEqual(try store.foodItems(for: day).map(\.name), ["Tea", "Porridge"])

        stored.calories = 300
        try store.updateFoodItem(stored)
        XCTAssertEqual(try store.foodItem(id: id)?.calories, 300)

        XCTAssertTrue(try store.removeFoodItem(id: id))
        XCTAssertNotNil(try store.rawEntry(id: rawId))
    }

    func testRawEntryWithFoodRowsCannotBeDeleted() throws {
        let store = try makeStore()
        let rawId = try store.insertRawEntry(RawEntry(createdAt: store.clock.now, logDate: day, consumedTime: nil,
                                                      rawText: "x", entryKind: .text, status: .parsed))
        try store.insertFoodItem(FoodItem(rawEntryId: rawId, logDate: day, consumedTime: nil, name: "x", calories: 1,
                                          source: .manualOverride, confidence: .high, createdAt: store.clock.now))
        XCTAssertThrowsError(try store.connection.execute("DELETE FROM raw_entries WHERE id = ?", [rawId]))
    }

    func testPendingEntriesAndDiscard() throws {
        let store = try makeStore()
        let id = try store.insertRawEntry(RawEntry(createdAt: store.clock.now, logDate: day, consumedTime: nil,
                                                   rawText: "mystery stew", entryKind: .text, status: .pending))
        XCTAssertEqual(try store.pendingEntries(for: day).map(\.id), [id])
        try store.updateRawEntryStatus(id: id, status: .parsed)
        XCTAssertFalse(try store.removePendingEntry(id: id), "Only pending entries may be discarded")
    }

    func testHistorySuggestionsMatchDeduplicateAndEscapeWildcards() throws {
        let store = try makeStore()
        func log(_ name: String, _ calories: Double, date: String, raw: String? = nil) throws {
            let logDate = LocalDate(iso: date)!
            let rawId = try store.insertRawEntry(RawEntry(createdAt: store.clock.now, logDate: logDate,
                                                          consumedTime: nil, rawText: raw ?? name,
                                                          entryKind: .text, status: .parsed))
            try store.insertFoodItem(FoodItem(rawEntryId: rawId, logDate: logDate, consumedTime: nil, name: name,
                                              amount: 1, unit: "bowl", calories: calories, source: .manualOverride,
                                              confidence: .high, createdAt: store.clock.now))
        }
        try log("Chicken soup", 220, date: "2026-11-10")
        try log("Chicken soup", 220, date: "2026-11-12")
        try log("Soup of the day", 180, date: "2026-11-15")
        try log("100% juice", 90, date: "2026-11-16")
        try log("Bread", 80, date: "2026-11-17", raw: "bread and soup")

        let suggestions = try store.historySuggestions(for: " Soup ")
        XCTAssertEqual(suggestions.map(\.name), ["Bread", "Soup of the day", "Chicken soup"])
        XCTAssertEqual(suggestions.last?.logDate.iso, "2026-11-12", "Most recent duplicate wins")
        XCTAssertEqual(try store.historySuggestions(for: "%").map(\.name), ["100% juice"])
        XCTAssertEqual(try store.historySuggestions(for: "   "), [])
    }

    func testActiveShortcutsOrderedByUseAndDeactivation() throws {
        let store = try makeStore()
        try store.upsertShortcut(Shortcut(lookupKey: "banana", name: "banana", calories: 105, unit: "serving"))
        let rawId = try store.insertRawEntry(RawEntry(createdAt: store.clock.now, logDate: day, consumedTime: nil,
                                                      rawText: "banana", entryKind: .text, status: .parsed))
        try store.insertFoodItem(FoodItem(rawEntryId: rawId, logDate: day, consumedTime: nil, name: "Banana",
                                          calories: 105, source: .userDefault, confidence: .high,
                                          createdAt: store.clock.now))
        XCTAssertEqual(try store.activeShortcuts().map(\.lookupKey), ["banana", "tea"])
        XCTAssertTrue(try store.deactivateShortcut(lookupKey: "banana"))
        XCTAssertNil(try store.activeShortcut(lookupKey: "banana"))
        XCTAssertNotNil(try store.shortcut(lookupKey: "banana"))
    }

    func testDailyWeightUpsertAndValidation() throws {
        let store = try makeStore()
        try store.upsertDailyWeight(logDate: day, weightKg: 93, measuredTime: LocalTime(iso: "07:00")!)
        try store.upsertDailyWeight(logDate: day, weightKg: 92.4, measuredTime: LocalTime(iso: "07:30")!)
        XCTAssertEqual(try store.allDailyWeights().count, 1)
        XCTAssertEqual(try store.dailyWeight(for: day)?.measuredTime.iso, "07:30")
        XCTAssertThrowsError(try store.upsertDailyWeight(logDate: day, weightKg: 0, measuredTime: LocalTime(iso: "07:00")!))
    }

    func testFailedTransactionRollsBack() throws {
        let store = try makeStore()
        struct Boom: Error {}
        XCTAssertThrowsError(try store.connection.transaction {
            try store.upsertDailyWeight(logDate: day, weightKg: 90, measuredTime: LocalTime(iso: "07:00")!)
            throw Boom()
        })
        XCTAssertNil(try store.dailyWeight(for: day))
    }
}
