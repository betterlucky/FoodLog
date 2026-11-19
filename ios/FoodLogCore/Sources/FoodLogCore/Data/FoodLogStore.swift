import Foundation

public enum FoodLogStoreError: Error, Equatable, CustomStringConvertible {
    case newerSchema(found: Int, supported: Int)
    case notFound
    case invalidInput(String)

    public var description: String {
        switch self {
        case .newerSchema(let found, let supported):
            return "Database schema \(found) is newer than this app supports (\(supported))."
        case .notFound: return "Not found."
        case .invalidInput(let message): return message
        }
    }
}

/// Supplies the current instant and zone so date logic is testable.
public protocol Clock: Sendable {
    var now: Date { get }
    var timeZone: TimeZone { get }
}

public struct SystemClock: Clock {
    public init() {}
    public var now: Date { Date() }
    public var timeZone: TimeZone { .current }
}

public extension Clock {
    var today: LocalDate { LocalDate(date: now, timeZone: timeZone) }
    var localTime: LocalTime { LocalTime(date: now, timeZone: timeZone).truncatedToMinutes }
}

/// A previously logged food offered as a reviewable candidate for an unresolved part.
public struct FoodHistorySuggestion: Hashable, Sendable {
    public var foodItemId: Int64
    public var rawText: String
    public var logDate: LocalDate
    public var name: String
    public var amount: Double?
    public var unit: String?
    public var calories: Double
    public var source: FoodItemSource
    public var notes: String?
}

/// The local database. Room's role on Android: the single source of truth.
/// Synchronous and not thread-safe; the app owns one instance on the main actor.
public final class FoodLogStore {
    public let connection: SQLiteConnection
    public let clock: Clock

    public static let historySuggestionQueryLimit = 20
    public static let historySuggestionLimit = 3

    public init(path: String, clock: Clock = SystemClock()) throws {
        connection = try SQLiteConnection(path: path)
        self.clock = clock
        try Schema.migrate(connection)
    }

    /// An in-memory store for tests and previews.
    public static func inMemory(clock: Clock = SystemClock()) throws -> FoodLogStore {
        try FoodLogStore(path: ":memory:", clock: clock)
    }

    public func seedDefaults() throws {
        try connection.transaction {
            try connection.execute("INSERT OR IGNORE INTO app_settings (id) VALUES (1)")
            if try shortcut(lookupKey: Shortcut.seededTea.lookupKey) == nil {
                try upsertShortcut(Shortcut.seededTea)
            }
        }
    }

    // MARK: Settings

    public func settings() throws -> AppSettings {
        guard let row = try connection.query("SELECT * FROM app_settings WHERE id = 1").first else {
            return AppSettings()
        }
        return AppSettings(
            dayBoundaryTime: row.time("dayBoundaryTime"),
            lastLabelInputMode: row.string("lastLabelInputMode") ?? "ITEMS",
            journalIncludeWeight: row.bool("journalIncludeWeight"))
    }

    public func saveSettings(_ settings: AppSettings) throws {
        try connection.execute(
            """
            INSERT INTO app_settings (id, dayBoundaryTime, lastLabelInputMode, journalIncludeWeight)
            VALUES (1, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET dayBoundaryTime = excluded.dayBoundaryTime,
                lastLabelInputMode = excluded.lastLabelInputMode,
                journalIncludeWeight = excluded.journalIncludeWeight
            """,
            [settings.dayBoundaryTime, settings.lastLabelInputMode, settings.journalIncludeWeight])
    }

    /// The food day for "now", honouring the day boundary (e.g. 01:00 before a 06:00 boundary
    /// still belongs to yesterday).
    public func currentFoodDate() throws -> LocalDate {
        let boundary = try settings().dayBoundaryTime
        let time = LocalTime(date: clock.now, timeZone: clock.timeZone)
        if let boundary, time < boundary { return clock.today.minusDays(1) }
        return clock.today
    }

    // MARK: Raw entries

    @discardableResult
    public func insertRawEntry(_ entry: RawEntry) throws -> Int64 {
        try connection.execute(
            """
            INSERT INTO raw_entries (createdAt, logDate, consumedTime, rawText, entryKind, status, parseJson, notes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [entry.createdAt, entry.logDate, entry.consumedTime, entry.rawText, entry.entryKind.rawValue,
             entry.status.rawValue, entry.parseJson, entry.notes])
        return connection.lastInsertRowID
    }

    public func rawEntry(id: Int64) throws -> RawEntry? {
        try connection.query("SELECT * FROM raw_entries WHERE id = ?", [id]).first.map(Self.rawEntry)
    }

    public func updateRawEntryStatus(id: Int64, status: RawEntryStatus) throws {
        try connection.execute("UPDATE raw_entries SET status = ? WHERE id = ?", [status.rawValue, id])
    }

    public func updatePendingRawEntry(id: Int64, rawText: String, logDate: LocalDate, consumedTime: LocalTime?) throws {
        try connection.execute(
            "UPDATE raw_entries SET rawText = ?, logDate = ?, consumedTime = ? WHERE id = ? AND status = 'PENDING'",
            [rawText, logDate, consumedTime, id])
    }

    public func pendingEntries(for date: LocalDate) throws -> [RawEntry] {
        try connection.query(
            "SELECT * FROM raw_entries WHERE logDate = ? AND status = 'PENDING' ORDER BY createdAt ASC, id ASC",
            [date]).map(Self.rawEntry)
    }

    /// Hard-deletes an unresolved pending entry the user chose to discard.
    @discardableResult
    public func removePendingEntry(id: Int64) throws -> Bool {
        try connection.execute("DELETE FROM raw_entries WHERE id = ? AND status = 'PENDING'", [id])
        return connection.changes > 0
    }

    // MARK: Food items

    @discardableResult
    public func insertFoodItem(_ item: FoodItem) throws -> Int64 {
        try connection.execute(
            """
            INSERT INTO food_items (rawEntryId, logDate, consumedTime, name, productId, shortcutKey, amount, unit,
                grams, milliliters, calories, proteinG, fibreG, nutrientSource, source, confidence, notes,
                aiContractVersion, aiProvider, aiModel, createdAt, voided)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            Self.foodItemArguments(item))
        return connection.lastInsertRowID
    }

    public func updateFoodItem(_ item: FoodItem) throws {
        var arguments = Self.foodItemArguments(item)
        arguments.append(item.id)
        try connection.execute(
            """
            UPDATE food_items SET rawEntryId = ?, logDate = ?, consumedTime = ?, name = ?, productId = ?,
                shortcutKey = ?, amount = ?, unit = ?, grams = ?, milliliters = ?, calories = ?, proteinG = ?,
                fibreG = ?, nutrientSource = ?, source = ?, confidence = ?, notes = ?, aiContractVersion = ?,
                aiProvider = ?, aiModel = ?, createdAt = ?, voided = ?
            WHERE id = ?
            """,
            arguments)
    }

    public func foodItem(id: Int64) throws -> FoodItem? {
        try connection.query("SELECT * FROM food_items WHERE id = ?", [id]).first.map(Self.foodItem)
    }

    /// Deletes a logged row. Its raw audit entry is kept.
    @discardableResult
    public func removeFoodItem(id: Int64) throws -> Bool {
        try connection.execute("DELETE FROM food_items WHERE id = ?", [id])
        return connection.changes > 0
    }

    public func foodItems(for date: LocalDate) throws -> [FoodItem] {
        try connection.query(
            "SELECT * FROM food_items WHERE logDate = ? AND voided = 0 ORDER BY consumedTime ASC, createdAt ASC",
            [date]).map(Self.foodItem)
    }

    public func allActiveFoodItems() throws -> [FoodItem] {
        try connection.query(
            "SELECT * FROM food_items WHERE voided = 0 ORDER BY logDate ASC, consumedTime ASC, createdAt ASC")
            .map(Self.foodItem)
    }

    public func dayTotals(for date: LocalDate) throws -> DayTotals {
        let row = try connection.query(
            """
            SELECT COALESCE(SUM(calories), 0) AS calories, SUM(proteinG) AS protein, SUM(fibreG) AS fibre,
                COUNT(*) AS items, COUNT(proteinG) AS proteinItems, COUNT(fibreG) AS fibreItems
            FROM food_items WHERE logDate = ? AND voided = 0
            """,
            [date]).first
        return DayTotals(
            calories: row?.double("calories") ?? 0,
            proteinG: row?.double("protein"),
            fibreG: row?.double("fibre"),
            itemCount: row?.int("items") ?? 0,
            itemsWithProtein: row?.int("proteinItems") ?? 0,
            itemsWithFibre: row?.int("fibreItems") ?? 0)
    }

    /// Room-backed history candidates for an unresolved food part. Mirrors the Android query:
    /// exact or substring match on item name or raw text, most recent first, de-duplicated by
    /// name/amount/unit/calories.
    public func historySuggestions(for query: String) throws -> [FoodHistorySuggestion] {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !normalized.isEmpty else { return [] }
        let like = normalized
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "%", with: "\\%")
            .replacingOccurrences(of: "_", with: "\\_")
        let rows = try connection.query(
            """
            SELECT food_items.id AS foodItemId, raw_entries.rawText AS rawText, food_items.logDate AS logDate,
                food_items.name AS name, food_items.amount AS amount, food_items.unit AS unit,
                food_items.calories AS calories, food_items.source AS source, food_items.notes AS notes
            FROM food_items
            INNER JOIN raw_entries ON raw_entries.id = food_items.rawEntryId
            WHERE food_items.voided = 0 AND (
                lower(food_items.name) = ?1 OR lower(raw_entries.rawText) = ?1
                OR lower(food_items.name) LIKE '%' || ?2 || '%' ESCAPE '\\'
                OR lower(raw_entries.rawText) LIKE '%' || ?2 || '%' ESCAPE '\\')
            ORDER BY food_items.logDate DESC, food_items.createdAt DESC
            LIMIT ?3
            """,
            [normalized, like, Self.historySuggestionQueryLimit])
        var seen = Set<[String]>()
        var suggestions: [FoodHistorySuggestion] = []
        for row in rows {
            let amount = row.double("amount")
            let calories = row.double("calories") ?? 0
            let name = row.string("name") ?? ""
            let unit = row.string("unit")
            let key = [name.trimmingCharacters(in: .whitespaces).lowercased(),
                       amount.map { "\($0)" } ?? "",
                       (unit ?? "").trimmingCharacters(in: .whitespaces).lowercased(),
                       "\(calories)"]
            guard seen.insert(key).inserted else { continue }
            suggestions.append(FoodHistorySuggestion(
                foodItemId: row.int64("foodItemId") ?? 0,
                rawText: row.string("rawText") ?? "",
                logDate: row.date("logDate") ?? clock.today,
                name: name, amount: amount, unit: unit, calories: calories,
                source: row.string("source").flatMap(FoodItemSource.init(rawValue:)) ?? .manualOverride,
                notes: row.string("notes")))
            if suggestions.count == Self.historySuggestionLimit { break }
        }
        return suggestions
    }

    // MARK: Shortcuts

    public func upsertShortcut(_ shortcut: Shortcut) throws {
        try connection.execute(
            """
            INSERT OR REPLACE INTO shortcuts (lookupKey, name, calories, unit, notes, source, confidence, active,
                defaultAmount, portionMode, itemUnit, itemSizeAmount, itemSizeUnit, kcalPer100g, kcalPer100ml,
                nutritionBasisName, proteinPer100, fibrePer100, proteinPerPortion, fibrePerPortion)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [shortcut.lookupKey, shortcut.name, shortcut.calories, shortcut.unit, shortcut.notes,
             shortcut.source.rawValue, shortcut.confidence.rawValue, shortcut.active, shortcut.defaultAmount,
             shortcut.portionMode.rawValue, shortcut.itemUnit, shortcut.itemSizeAmount, shortcut.itemSizeUnit,
             shortcut.kcalPer100g, shortcut.kcalPer100ml, shortcut.nutritionBasisName, shortcut.proteinPer100,
             shortcut.fibrePer100, shortcut.proteinPerPortion, shortcut.fibrePerPortion])
    }

    public func shortcut(lookupKey: String) throws -> Shortcut? {
        try connection.query("SELECT * FROM shortcuts WHERE lookupKey = ?", [lookupKey]).first.map(Self.shortcut)
    }

    public func activeShortcut(lookupKey: String) throws -> Shortcut? {
        try connection.query("SELECT * FROM shortcuts WHERE lookupKey = ? AND active = 1", [lookupKey])
            .first.map(Self.shortcut)
    }

    /// Active shortcuts, most-used first, then by name. Usage counts come from linked food rows
    /// and, for imported Android rows without a link, from matching names.
    public func activeShortcuts() throws -> [Shortcut] {
        try connection.query(
            """
            SELECT shortcuts.*, (
                SELECT COUNT(*) FROM food_items
                WHERE food_items.voided = 0 AND (food_items.shortcutKey = shortcuts.lookupKey
                    OR (food_items.shortcutKey IS NULL AND lower(food_items.name) = lower(shortcuts.name)))
            ) AS uses
            FROM shortcuts WHERE active = 1
            ORDER BY uses DESC, lower(name) ASC
            """).map(Self.shortcut)
    }

    @discardableResult
    public func deactivateShortcut(lookupKey: String) throws -> Bool {
        try connection.execute("UPDATE shortcuts SET active = 0 WHERE lookupKey = ? AND active = 1", [lookupKey])
        return connection.changes > 0
    }

    // MARK: Products

    @discardableResult
    public func insertProduct(_ product: Product) throws -> Int64 {
        try connection.execute(
            """
            INSERT INTO products (name, brand, description, packageSizeGrams, packageSizeMilliliters,
                packageItemCount, servingSizeGrams, servingUnit, kcalPer100g, kcalPer100ml, kcalPerServing,
                proteinPer100g, carbsPer100g, fatPer100g, fiberPer100g, sugarsPer100g, saltPer100g, source,
                confidence, lastLoggedGrams, createdAt, archived)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            Self.productArguments(product))
        return connection.lastInsertRowID
    }

    public func product(named name: String) throws -> Product? {
        try connection.query("SELECT * FROM products WHERE name = ? AND archived = 0 ORDER BY id DESC LIMIT 1", [name])
            .first.map(Self.product)
    }

    public func product(id: Int64) throws -> Product? {
        try connection.query("SELECT * FROM products WHERE id = ?", [id]).first.map(Self.product)
    }

    // MARK: Daily weight

    public func dailyWeight(for date: LocalDate) throws -> DailyWeight? {
        try connection.query("SELECT * FROM daily_weights WHERE logDate = ?", [date]).first.map(Self.dailyWeight)
    }

    public func allDailyWeights() throws -> [DailyWeight] {
        try connection.query("SELECT * FROM daily_weights ORDER BY logDate ASC").map(Self.dailyWeight)
    }

    public func upsertDailyWeight(logDate: LocalDate, weightKg: Double, measuredTime: LocalTime) throws {
        guard weightKg > 0 else { throw FoodLogStoreError.invalidInput("Weight must be positive.") }
        let now = clock.now
        try connection.execute(
            """
            INSERT INTO daily_weights (logDate, weightKg, measuredTime, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(logDate) DO UPDATE SET weightKg = excluded.weightKg, measuredTime = excluded.measuredTime,
                updatedAt = excluded.updatedAt
            """,
            [logDate, weightKg, measuredTime, now, now])
    }

    @discardableResult
    public func removeDailyWeight(logDate: LocalDate) throws -> Bool {
        try connection.execute("DELETE FROM daily_weights WHERE logDate = ?", [logDate])
        return connection.changes > 0
    }
}

public struct DayTotals: Hashable, Sendable {
    public var calories: Double
    /// Sum over rows that have a value; nil when no row has one.
    public var proteinG: Double?
    public var fibreG: Double?
    public var itemCount: Int
    public var itemsWithProtein: Int
    public var itemsWithFibre: Int
}

// MARK: Row mapping

extension FoodLogStore {
    static func rawEntry(_ row: SQLRow) -> RawEntry {
        RawEntry(
            id: row.int64("id") ?? 0,
            createdAt: row.instant("createdAt") ?? Date(timeIntervalSince1970: 0),
            logDate: row.date("logDate")!,
            consumedTime: row.time("consumedTime"),
            rawText: row.string("rawText") ?? "",
            entryKind: row.string("entryKind").flatMap(EntryKind.init(rawValue:)) ?? .text,
            status: row.string("status").flatMap(RawEntryStatus.init(rawValue:)) ?? .pending,
            parseJson: row.string("parseJson"),
            notes: row.string("notes"))
    }

    static func foodItem(_ row: SQLRow) -> FoodItem {
        let provenance: AIProvenance? = row.int("aiContractVersion").map {
            AIProvenance(contractVersion: $0, provider: row.string("aiProvider") ?? "", model: row.string("aiModel") ?? "")
        }
        return FoodItem(
            id: row.int64("id") ?? 0,
            rawEntryId: row.int64("rawEntryId") ?? 0,
            logDate: row.date("logDate")!,
            consumedTime: row.time("consumedTime"),
            name: row.string("name") ?? "",
            productId: row.int64("productId"),
            shortcutKey: row.string("shortcutKey"),
            amount: row.double("amount"),
            unit: row.string("unit"),
            grams: row.double("grams"),
            milliliters: row.double("milliliters"),
            calories: row.double("calories") ?? 0,
            proteinG: row.double("proteinG"),
            fibreG: row.double("fibreG"),
            nutrientSource: row.string("nutrientSource").flatMap(NutrientSource.init(rawValue:)),
            source: row.string("source").flatMap(FoodItemSource.init(rawValue:)) ?? .manualOverride,
            confidence: row.string("confidence").flatMap(ConfidenceLevel.init(rawValue:)) ?? .high,
            notes: row.string("notes"),
            aiProvenance: provenance,
            createdAt: row.instant("createdAt") ?? Date(timeIntervalSince1970: 0),
            voided: row.bool("voided"))
    }

    static func foodItemArguments(_ item: FoodItem) -> [SQLBindable] {
        [item.rawEntryId, item.logDate, item.consumedTime, item.name, item.productId, item.shortcutKey, item.amount,
         item.unit, item.grams, item.milliliters, item.calories, item.proteinG, item.fibreG,
         item.nutrientSource?.rawValue, item.source.rawValue, item.confidence.rawValue, item.notes,
         item.aiProvenance?.contractVersion, item.aiProvenance?.provider, item.aiProvenance?.model,
         item.createdAt, item.voided]
    }

    static func shortcut(_ row: SQLRow) -> Shortcut {
        Shortcut(
            lookupKey: row.string("lookupKey") ?? "",
            name: row.string("name") ?? "",
            calories: row.double("calories") ?? 0,
            unit: row.string("unit") ?? "",
            notes: row.string("notes"),
            source: row.string("source").flatMap(FoodItemSource.init(rawValue:)) ?? .userDefault,
            confidence: row.string("confidence").flatMap(ConfidenceLevel.init(rawValue:)) ?? .high,
            active: row.bool("active"),
            defaultAmount: row.double("defaultAmount"),
            portionMode: row.string("portionMode").flatMap(ShortcutPortionMode.init(rawValue:)) ?? .plain,
            itemUnit: row.string("itemUnit"),
            itemSizeAmount: row.double("itemSizeAmount"),
            itemSizeUnit: row.string("itemSizeUnit"),
            kcalPer100g: row.double("kcalPer100g"),
            kcalPer100ml: row.double("kcalPer100ml"),
            nutritionBasisName: row.string("nutritionBasisName"),
            proteinPer100: row.double("proteinPer100"),
            fibrePer100: row.double("fibrePer100"),
            proteinPerPortion: row.double("proteinPerPortion"),
            fibrePerPortion: row.double("fibrePerPortion"))
    }

    static func product(_ row: SQLRow) -> Product {
        Product(
            id: row.int64("id") ?? 0,
            name: row.string("name") ?? "",
            brand: row.string("brand"),
            description: row.string("description"),
            packageSizeGrams: row.double("packageSizeGrams"),
            packageSizeMilliliters: row.double("packageSizeMilliliters"),
            packageItemCount: row.double("packageItemCount"),
            servingSizeGrams: row.double("servingSizeGrams"),
            servingUnit: row.string("servingUnit"),
            kcalPer100g: row.double("kcalPer100g"),
            kcalPer100ml: row.double("kcalPer100ml"),
            kcalPerServing: row.double("kcalPerServing"),
            proteinPer100g: row.double("proteinPer100g"),
            carbsPer100g: row.double("carbsPer100g"),
            fatPer100g: row.double("fatPer100g"),
            fiberPer100g: row.double("fiberPer100g"),
            sugarsPer100g: row.double("sugarsPer100g"),
            saltPer100g: row.double("saltPer100g"),
            source: row.string("source").flatMap(ProductSource.init(rawValue:)) ?? .manual,
            confidence: row.string("confidence").flatMap(ConfidenceLevel.init(rawValue:)) ?? .high,
            lastLoggedGrams: row.double("lastLoggedGrams"),
            createdAt: row.instant("createdAt") ?? Date(timeIntervalSince1970: 0),
            archived: row.bool("archived"))
    }

    static func productArguments(_ product: Product) -> [SQLBindable] {
        [product.name, product.brand, product.description, product.packageSizeGrams, product.packageSizeMilliliters,
         product.packageItemCount, product.servingSizeGrams, product.servingUnit, product.kcalPer100g,
         product.kcalPer100ml, product.kcalPerServing, product.proteinPer100g, product.carbsPer100g,
         product.fatPer100g, product.fiberPer100g, product.sugarsPer100g, product.saltPer100g,
         product.source.rawValue, product.confidence.rawValue, product.lastLoggedGrams, product.createdAt,
         product.archived]
    }

    static func dailyWeight(_ row: SQLRow) -> DailyWeight {
        DailyWeight(
            logDate: row.date("logDate")!,
            weightKg: row.double("weightKg") ?? 0,
            measuredTime: row.time("measuredTime") ?? LocalTime(hour: 0, minute: 0)!,
            createdAt: row.instant("createdAt") ?? Date(timeIntervalSince1970: 0),
            updatedAt: row.instant("updatedAt") ?? Date(timeIntervalSince1970: 0))
    }
}
