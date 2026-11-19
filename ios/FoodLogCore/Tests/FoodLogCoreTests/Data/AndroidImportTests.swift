import XCTest
@testable import FoodLogCore

final class AndroidImportTests: XCTestCase {
    /// Synthetic export in the docs/android-data-export.md v1 shape. No real food history.
    static let fixture = #"""
    {
      "format": "foodlog-export",
      "version": 1,
      "exportedAt": "2026-11-19T15:00:00Z",
      "source": {"platform": "android", "roomSchemaVersion": 17},
      "settings": {"dayBoundaryTime": "06:00"},
      "shortcuts": [
        {"lookupKey": "tea", "name": "Tea", "calories": 18.0, "unit": "cup", "notes": "skimmed", "source": "USER_DEFAULT",
         "confidence": "HIGH", "active": true, "defaultAmount": null, "portionMode": "PLAIN", "itemUnit": null,
         "itemSizeAmount": null, "itemSizeUnit": null, "kcalPer100g": null, "kcalPer100ml": null, "nutritionBasisName": null},
        {"lookupKey": "ham", "name": "ham", "calories": 1.12, "unit": "g", "notes": null, "source": "USER_DEFAULT",
         "confidence": "HIGH", "active": false, "defaultAmount": 100.0, "portionMode": "MEASURE", "itemUnit": null,
         "itemSizeAmount": null, "itemSizeUnit": null, "kcalPer100g": 112.0, "kcalPer100ml": null, "nutritionBasisName": null}
      ],
      "products": [
        {"id": 7, "name": "Oat bar", "brand": null, "description": null, "containerType": null, "containerSizeGrams": null,
         "packageSizeGrams": 210.0, "packageItemCount": 6.0, "servingSizeGrams": 35.0, "servingUnit": "bar",
         "kcalPer100g": 420.0, "kcalPerServing": 147.0, "proteinPer100g": null, "carbsPer100g": null, "fatPer100g": null,
         "fiberPer100g": null, "sugarsPer100g": null, "saltPer100g": null, "source": "PACKAGING_PHOTO",
         "confidence": "HIGH", "lastLoggedGrams": 35.0, "createdAt": "2026-05-01T12:00:00.123Z", "archived": false}
      ],
      "rawEntries": [
        {"id": 1, "createdAt": "2026-04-23T07:00:01Z", "logDate": "2026-04-23", "consumedTime": "08:00",
         "rawText": "tea", "entryKind": "TEXT", "status": "PARSED", "parseJson": null, "notes": null},
        {"id": 5, "createdAt": "2026-05-01T12:00:00Z", "logDate": "2026-05-01", "consumedTime": "12:00",
         "rawText": "Label scan: Oat bar", "entryKind": "TEXT", "status": "PARSED", "parseJson": null, "notes": null},
        {"id": 6, "createdAt": "2026-05-02T09:00:00Z", "logDate": "2026-05-02", "consumedTime": null,
         "rawText": "mystery", "entryKind": "TEXT", "status": "PENDING", "parseJson": null, "notes": null}
      ],
      "foodItems": [
        {"id": 10, "rawEntryId": 1, "logDate": "2026-04-23", "consumedTime": "08:00", "name": "Tea", "productId": null,
         "containerId": null, "amount": 1.0, "unit": "cup", "grams": null, "calories": 18.0, "source": "USER_DEFAULT",
         "confidence": "HIGH", "notes": "skimmed", "createdAt": "2026-04-23T07:00:01Z", "voided": false},
        {"id": 11, "rawEntryId": 5, "logDate": "2026-05-01", "consumedTime": "12:00", "name": "Oat bar", "productId": 7,
         "containerId": null, "amount": 1.0, "unit": "bar", "grams": 35.0, "calories": 147.0, "source": "SAVED_LABEL",
         "confidence": "HIGH", "notes": null, "createdAt": "2026-05-01T12:00:00Z", "voided": false}
      ],
      "dailyWeights": [
        {"logDate": "2026-04-28", "weightKg": 92.9, "measuredTime": "10:00", "createdAt": "2026-04-28T09:00:04Z",
         "updatedAt": "2026-04-28T09:00:04Z"}
      ]
    }
    """#

    private func makeStore() throws -> FoodLogStore {
        let store = try FoodLogStore.inMemory(clock: FixedClock("2026-11-19T15:00:00"))
        try store.seedDefaults()
        return store
    }

    func testImportPreservesRowsReferencesAndSettings() throws {
        let store = try makeStore()
        let summary = try store.importAndroidExport(Data(Self.fixture.utf8))
        XCTAssertEqual(summary.foodItems, 2)
        XCTAssertEqual(summary.firstDate?.iso, "2026-04-23")
        XCTAssertEqual(summary.lastDate?.iso, "2026-05-01")

        XCTAssertEqual(try store.shortcut(lookupKey: "tea")?.calories, 18, "Imported tea replaces the seeded default")
        XCTAssertEqual(try store.shortcut(lookupKey: "ham")?.active, false)
        XCTAssertEqual(try store.activeShortcuts().map(\.lookupKey), ["tea"])

        let bar = try XCTUnwrap(store.foodItem(id: 11))
        XCTAssertEqual(bar.productId, 7)
        XCTAssertEqual(bar.rawEntryId, 5)
        XCTAssertEqual(try store.product(id: 7)?.servingUnit, "bar")
        XCTAssertNil(bar.shortcutKey)
        XCTAssertNil(bar.proteinG)

        XCTAssertEqual(try store.pendingEntries(for: LocalDate(iso: "2026-05-02")!).map(\.rawText), ["mystery"])
        XCTAssertEqual(try store.dailyWeight(for: LocalDate(iso: "2026-04-28")!)?.weightKg, 92.9)
        XCTAssertEqual(try store.settings().dayBoundaryTime?.iso, "06:00")
        XCTAssertTrue(try store.hasImportedAndroidData)

        // New rows continue after the imported ids.
        let rawId = try store.insertRawEntry(RawEntry(createdAt: store.clock.now, logDate: LocalDate(iso: "2026-11-19")!,
                                                      consumedTime: nil, rawText: "x", entryKind: .text, status: .parsed))
        XCTAssertGreaterThan(rawId, 6)
    }

    func testImportRefusesWhenPhoneAlreadyHasData() throws {
        let store = try makeStore()
        try store.upsertDailyWeight(logDate: LocalDate(iso: "2026-11-19")!, weightKg: 90, measuredTime: LocalTime(iso: "07:00")!)
        XCTAssertThrowsError(try store.importAndroidExport(Data(Self.fixture.utf8))) { error in
            XCTAssertEqual(error as? ImportError, .notEmpty)
        }
        XCTAssertEqual(try store.allActiveFoodItems().count, 0)
    }

    func testImportRejectsUnknownVersionAndRollsBackOnBadRow() throws {
        let store = try makeStore()
        let newer = Self.fixture.replacingOccurrences(of: #""version": 1"#, with: #""version": 2"#)
        XCTAssertThrowsError(try store.importAndroidExport(Data(newer.utf8)))

        let broken = Self.fixture.replacingOccurrences(of: #""rawEntryId": 5"#, with: #""rawEntryId": 99"#)
        XCTAssertThrowsError(try store.importAndroidExport(Data(broken.utf8)))
        XCTAssertFalse(try store.hasUserData, "A failed import leaves nothing behind")
        XCTAssertEqual(try store.shortcut(lookupKey: "tea")?.calories, 25)
    }

    func testJournalExportFromImportedRows() throws {
        let store = try makeStore()
        try store.importAndroidExport(Data(Self.fixture.utf8))
        let csv = try store.buildJournalCsv(options: JournalExportOptions(includeWeight: true)).split(separator: "\n")
        XCTAssertEqual(String(csv[0]), JournalCsvExporter.header)
        XCTAssertEqual(String(csv[1]), "2026-04-23,08:00,food,Tea,1 cup,18,,skimmed,shortcut,10,,2026-04-23T07:00:01Z,,,")
        XCTAssertEqual(String(csv[2]), "2026-04-28,10:00,weight,weight,,,92.9,Recorded weight,daily weight,,,2026-04-28T09:00:04Z,,,")
        XCTAssertEqual(String(csv[3]), "2026-05-01,12:00,food,Oat bar,1 bar,147,,,label scan,11,7,2026-05-01T12:00:00Z,,,")
    }
}

/// Opt-in check against a real export. Set FOODLOG_REAL_EXPORT to its path; never commit the file.
final class RealAndroidExportTests: XCTestCase {
    func testRealExportImportsAndExports() throws {
        guard let path = ProcessInfo.processInfo.environment["FOODLOG_REAL_EXPORT"] else {
            throw XCTSkip("FOODLOG_REAL_EXPORT not set")
        }
        let data = try Data(contentsOf: URL(fileURLWithPath: path))
        let raw = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let store = try FoodLogStore.inMemory()
        try store.seedDefaults()
        let summary = try store.importAndroidExport(data)
        XCTAssertEqual(summary.foodItems, (raw["foodItems"] as! [Any]).count)
        XCTAssertEqual(summary.rawEntries, (raw["rawEntries"] as! [Any]).count)
        XCTAssertEqual(try store.allActiveFoodItems().count,
                       (raw["foodItems"] as! [[String: Any]]).filter { !($0["voided"] as! Bool) }.count)
        let csv = try store.buildJournalCsv(options: JournalExportOptions(includeWeight: true))
        XCTAssertEqual(csv.split(separator: "\n").count, 1 + summary.dailyWeights
                       + (try store.allActiveFoodItems().count))
        print("Real export: \(summary)")
    }
}
