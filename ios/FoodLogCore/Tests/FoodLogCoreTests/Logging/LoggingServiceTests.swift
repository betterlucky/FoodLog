import XCTest
@testable import FoodLogCore

final class LoggingServiceTests: XCTestCase {
    private let day = LocalDate(iso: "2026-11-19")!
    private var store: FoodLogStore!
    private var logging: LoggingService!

    override func setUpWithError() throws {
        store = try FoodLogStore.inMemory(clock: FixedClock("2026-11-19T08:30:00"))
        try store.seedDefaults()
        logging = LoggingService(store: store)
    }

    func testShortcutTextLogsImmediatelyWithLinkAndParsedRawEntry() throws {
        guard case .logged(let ids, let summary) = try logging.submit("tea", selectedDate: day) else {
            return XCTFail("tea should log from the seeded shortcut")
        }
        XCTAssertEqual(summary, "Tea 25 kcal")
        let item = try XCTUnwrap(store.foodItem(id: ids[0]))
        XCTAssertEqual(item.shortcutKey, "tea")
        XCTAssertEqual(item.consumedTime?.iso, "08:30")
        XCTAssertEqual(item.source, .userDefault)
        XCTAssertEqual(try store.rawEntry(id: item.rawEntryId)?.status, .parsed)
        XCTAssertEqual(try store.rawEntry(id: item.rawEntryId)?.rawText, "tea")
    }

    func testQuantityAndExplicitTime() throws {
        guard case .logged(let ids, _) = try logging.submit("2 tea at 7am", selectedDate: day) else {
            return XCTFail("expected logged")
        }
        let item = try XCTUnwrap(store.foodItem(id: ids[0]))
        XCTAssertEqual(item.calories, 50)
        XCTAssertEqual(item.consumedTime?.iso, "07:00")
    }

    func testUnknownFoodStaysPendingWithRawTextSavedFirst() throws {
        guard case .needsDetails(let context) = try logging.submit("mystery stew", selectedDate: day) else {
            return XCTFail("expected pending")
        }
        let rawId = try XCTUnwrap(context.rawEntryId)
        XCTAssertEqual(try store.rawEntry(id: rawId)?.status, .pending)
        XCTAssertEqual(try store.pendingEntries(for: day).map(\.rawText), ["mystery stew"])
        XCTAssertTrue(try store.foodItems(for: day).isEmpty, "Unsupported input creates no food rows")
    }

    func testMixedSubmissionPrefillsKnownPartAndResolvesWithShortcutSave() throws {
        guard case .needsDetails(let context) = try logging.submit("tea and flapjack", selectedDate: day) else {
            return XCTFail("expected pending")
        }
        XCTAssertEqual(context.parts.count, 2)
        XCTAssertEqual(context.parts[0].draft?.calories, 25)
        XCTAssertNil(context.parts[1].draft)
        XCTAssertEqual(context.parts[1].lookupKey, "flapjack")

        let flapjack = FoodDraft(name: "Flapjack", amount: 1, unit: "bar", calories: 240, proteinG: 4)
        let result = try logging.resolve(context, drafts: [context.parts[0].draft, flapjack],
                                         consumedTime: LocalTime(iso: "10:00"), saveShortcuts: [1])
        XCTAssertEqual(result.savedShortcuts, ["flapjack"])
        XCTAssertEqual(try store.foodItems(for: day).map(\.name), ["Tea", "Flapjack"])
        XCTAssertEqual(try store.rawEntry(id: context.rawEntryId!)?.status, .parsed)
        XCTAssertEqual(try store.rawEntry(id: context.rawEntryId!)?.consumedTime?.iso, "10:00")
        let logged = try store.foodItems(for: day)[1]
        XCTAssertEqual(logged.shortcutKey, "flapjack")
        XCTAssertEqual(logged.nutrientSource, .manual)

        // Next time it's one step, with protein carried by the shortcut.
        guard case .logged(let ids, _) = try logging.submit("flapjack", selectedDate: day) else {
            return XCTFail("flapjack should now be a shortcut")
        }
        let again = try XCTUnwrap(store.foodItem(id: ids[0]))
        XCTAssertEqual(again.calories, 240)
        XCTAssertEqual(again.proteinG, 4)
        XCTAssertEqual(again.nutrientSource, .shortcut)
    }

    func testSavingShortcutNeverOverwritesAnActiveOne() throws {
        let context = logging.manualContext(on: day)
        let result = try logging.resolve(context, drafts: [FoodDraft(name: "Tea", amount: 1, unit: "mug", calories: 60)],
                                         consumedTime: nil, saveShortcuts: [0])
        XCTAssertEqual(result.skippedShortcuts, ["tea"])
        XCTAssertEqual(try store.activeShortcut(lookupKey: "tea")?.calories, 25)
        XCTAssertEqual(try store.rawEntry(id: store.foodItem(id: result.itemIds[0])!.rawEntryId)?.rawText,
                       "Manual entry: Tea")
    }

    func testResolveRejectsMissingCaloriesAndLeavesPendingUntouched() throws {
        guard case .needsDetails(let context) = try logging.submit("mystery", selectedDate: day) else {
            return XCTFail("expected pending")
        }
        XCTAssertThrowsError(try logging.resolve(context, drafts: [FoodDraft(name: "Mystery", calories: 0)],
                                                 consumedTime: nil, saveShortcuts: []))
        XCTAssertEqual(try store.pendingEntries(for: day).count, 1)
    }

    func testDateMismatchDoesNotSaveAnything() throws {
        XCTAssertEqual(try logging.submit("yesterday tea", selectedDate: day),
                       .dateMismatch(requested: day.minusDays(1)))
        XCTAssertFalse(try store.hasUserData)
        guard case .logged = try logging.submit("yesterday tea", selectedDate: day.minusDays(1)) else {
            return XCTFail("logs once the matching day is selected")
        }
    }

    func testNonFoodIsKeptAsAuditOnly() throws {
        guard case .nonFood(let intent) = try logging.submit("how many calories today?", selectedDate: day) else {
            return XCTFail("expected non-food")
        }
        XCTAssertEqual(intent, .query)
        XCTAssertTrue(try store.foodItems(for: day).isEmpty)
        XCTAssertTrue(try store.pendingEntries(for: day).isEmpty)
    }

    func testOneTapShortcutAndUndoKeepsAudit() throws {
        let logged = try logging.logShortcut("tea", on: day)
        XCTAssertEqual(logged.draft.calories, 25)
        let rawId = try XCTUnwrap(store.foodItem(id: logged.itemId)).rawEntryId
        try logging.removeItems([logged.itemId])
        XCTAssertNil(try store.foodItem(id: logged.itemId))
        XCTAssertNotNil(try store.rawEntry(id: rawId))
        XCTAssertThrowsError(try logging.logShortcut("unknown", on: day))
    }

    func testEditWithUpdateShortcutLearnsNewNormal() throws {
        let logged = try logging.logShortcut("tea", on: day)
        var draft = FoodDraft(item: try XCTUnwrap(store.foodItem(id: logged.itemId)))
        draft.calories = 40
        draft.unit = "mug"
        try logging.update(itemId: logged.itemId, with: draft, consumedTime: LocalTime(iso: "09:00"), updateShortcut: true)
        XCTAssertEqual(try store.foodItem(id: logged.itemId)?.calories, 40)
        XCTAssertEqual(try store.activeShortcut(lookupKey: "tea")?.calories, 40)
        XCTAssertEqual(try store.activeShortcut(lookupKey: "tea")?.unit, "mug")

        draft.calories = 30
        try logging.update(itemId: logged.itemId, with: draft, consumedTime: nil, updateShortcut: false)
        XCTAssertEqual(try store.activeShortcut(lookupKey: "tea")?.calories, 40, "Only on request")
    }

    func testShortcutCandidateForImportedRowWithoutLink() throws {
        let rawId = try store.insertRawEntry(RawEntry(createdAt: store.clock.now, logDate: day, consumedTime: nil,
                                                      rawText: "tea", entryKind: .text, status: .parsed))
        let id = try store.insertFoodItem(FoodItem(rawEntryId: rawId, logDate: day, consumedTime: nil, name: "Tea",
                                                   calories: 18, source: .userDefault, confidence: .high,
                                                   createdAt: store.clock.now))
        XCTAssertEqual(try logging.shortcutCandidate(for: XCTUnwrap(store.foodItem(id: id)))?.lookupKey, "tea")
    }

    func testLabelLogKeepsNutrientsOnProductRowAndShortcut() throws {
        let facts = LabelNutritionParser().parse("""
            NUTRITION
            Typical values per 100g
            Energy 1760kJ / 420kcal
            Fibre 6.0g
            Protein 10.0g
            """)
        XCTAssertEqual(facts.kcalPer100g, 420)
        let resolution = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "40g")
        let calories = try XCTUnwrap(resolution.calories)
        let logged = try logging.logLabel(LabelLogInput(
            name: "Granola", facts: facts, inputMode: .measure, resolution: resolution, calories: calories,
            logDate: day, consumedTime: nil, saveAsShortcut: true))
        XCTAssertEqual(logged.savedShortcut, "granola")

        let item = try XCTUnwrap(store.foodItem(id: logged.itemId))
        XCTAssertEqual(item.calories, 168, accuracy: 0.01)
        XCTAssertEqual(try XCTUnwrap(item.proteinG), 4, accuracy: 0.01)
        XCTAssertEqual(try XCTUnwrap(item.fibreG), 2.4, accuracy: 0.01)
        XCTAssertEqual(item.nutrientSource, .label)
        XCTAssertEqual(item.source, .savedLabel)
        XCTAssertEqual(item.shortcutKey, "granola")
        XCTAssertEqual(try store.product(id: XCTUnwrap(item.productId))?.proteinPer100g, 10)

        guard case .logged(let ids, _) = try logging.submit("granola", selectedDate: day) else {
            return XCTFail("label shortcut should log in one step")
        }
        let repeatItem = try XCTUnwrap(store.foodItem(id: ids[0]))
        XCTAssertEqual(repeatItem.calories, 168, accuracy: 0.01)
        XCTAssertEqual(try XCTUnwrap(repeatItem.proteinG), 4, accuracy: 0.01)
        XCTAssertEqual(try store.dayTotals(for: day).proteinG ?? 0, 8, accuracy: 0.01)
    }
}
