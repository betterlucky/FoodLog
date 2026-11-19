import Foundation

/// The versioned Android export described in docs/android-data-export.md.
public struct AndroidExport: Decodable {
    public struct Source: Decodable { public var platform: String; public var roomSchemaVersion: Int }
    public struct Settings: Decodable { public var dayBoundaryTime: String? }

    public struct ShortcutRow: Decodable {
        var lookupKey: String
        var name: String
        var calories: Double
        var unit: String
        var notes: String?
        var source: FoodItemSource
        var confidence: ConfidenceLevel
        var active: Bool
        var defaultAmount: Double?
        var portionMode: ShortcutPortionMode
        var itemUnit: String?
        var itemSizeAmount: Double?
        var itemSizeUnit: String?
        var kcalPer100g: Double?
        var kcalPer100ml: Double?
        var nutritionBasisName: String?
    }

    public struct ProductRow: Decodable {
        var id: Int64
        var name: String
        var brand: String?
        var description: String?
        var packageSizeGrams: Double?
        var packageItemCount: Double?
        var servingSizeGrams: Double?
        var servingUnit: String?
        var kcalPer100g: Double?
        var kcalPerServing: Double?
        var proteinPer100g: Double?
        var carbsPer100g: Double?
        var fatPer100g: Double?
        var fiberPer100g: Double?
        var sugarsPer100g: Double?
        var saltPer100g: Double?
        var source: ProductSource
        var confidence: ConfidenceLevel
        var lastLoggedGrams: Double?
        var createdAt: String
        var archived: Bool
    }

    public struct RawEntryRow: Decodable {
        var id: Int64
        var createdAt: String
        var logDate: LocalDate
        var consumedTime: LocalTime?
        var rawText: String
        var entryKind: EntryKind
        var status: RawEntryStatus
        var parseJson: String?
        var notes: String?
    }

    public struct FoodItemRow: Decodable {
        var id: Int64
        var rawEntryId: Int64
        var logDate: LocalDate
        var consumedTime: LocalTime?
        var name: String
        var productId: Int64?
        var amount: Double?
        var unit: String?
        var grams: Double?
        var calories: Double
        var source: FoodItemSource
        var confidence: ConfidenceLevel
        var notes: String?
        var createdAt: String
        var voided: Bool
    }

    public struct DailyWeightRow: Decodable {
        var logDate: LocalDate
        var weightKg: Double
        var measuredTime: LocalTime
        var createdAt: String
        var updatedAt: String
    }

    public var format: String
    public var version: Int
    public var exportedAt: String
    public var source: Source
    public var settings: Settings
    public var shortcuts: [ShortcutRow]
    public var products: [ProductRow]
    public var rawEntries: [RawEntryRow]
    public var foodItems: [FoodItemRow]
    public var dailyWeights: [DailyWeightRow]
}

public struct ImportSummary: Hashable, Sendable, CustomStringConvertible {
    public var shortcuts: Int
    public var products: Int
    public var rawEntries: Int
    public var foodItems: Int
    public var dailyWeights: Int
    public var firstDate: LocalDate?
    public var lastDate: LocalDate?

    public var description: String {
        let range = [firstDate, lastDate].compactMap { $0?.iso }.joined(separator: " to ")
        return "\(foodItems) food rows\(range.isEmpty ? "" : " (\(range))"), \(shortcuts) shortcuts, "
            + "\(dailyWeights) weights, \(products) products, \(rawEntries) raw entries"
    }
}

public enum ImportError: Error, Equatable, CustomStringConvertible {
    case unsupportedFormat(String, Int)
    case notEmpty
    case invalidInstant(String)

    public var description: String {
        switch self {
        case .unsupportedFormat(let format, let version): return "Unsupported export \(format) v\(version)."
        case .notEmpty: return "This phone already has logged food, so the Android history was not imported."
        case .invalidInstant(let value): return "Invalid timestamp in export: \(value)"
        }
    }
}

extension FoodLogStore {
    public var hasUserData: Bool {
        get throws {
            let row = try connection.query(
                "SELECT (SELECT COUNT(*) FROM raw_entries) + (SELECT COUNT(*) FROM daily_weights) AS count").first
            return (row?.int("count") ?? 0) > 0
        }
    }

    public var hasImportedAndroidData: Bool {
        get throws {
            try !connection.query("SELECT id FROM imports WHERE sourcePlatform = 'android' LIMIT 1").isEmpty
        }
    }

    /// Imports a version 1 Android export into an empty store in one transaction, preserving
    /// row ids so food rows keep their raw entries and products. Imported shortcuts replace the
    /// seeded defaults with the same keys.
    @discardableResult
    public func importAndroidExport(_ data: Data) throws -> ImportSummary {
        let export = try JSONDecoder().decode(AndroidExport.self, from: data)
        guard export.format == "foodlog-export", export.version == 1, export.source.platform == "android" else {
            throw ImportError.unsupportedFormat(export.format, export.version)
        }
        func instant(_ value: String) throws -> Date {
            guard let date = InstantFormat.date(from: value) else { throw ImportError.invalidInstant(value) }
            return date
        }

        return try connection.transaction {
            guard try !hasUserData else { throw ImportError.notEmpty }

            for row in export.shortcuts {
                try upsertShortcut(Shortcut(
                    lookupKey: row.lookupKey, name: row.name, calories: row.calories, unit: row.unit,
                    notes: row.notes, source: row.source, confidence: row.confidence, active: row.active,
                    defaultAmount: row.defaultAmount, portionMode: row.portionMode, itemUnit: row.itemUnit,
                    itemSizeAmount: row.itemSizeAmount, itemSizeUnit: row.itemSizeUnit,
                    kcalPer100g: row.kcalPer100g, kcalPer100ml: row.kcalPer100ml,
                    nutritionBasisName: row.nutritionBasisName))
            }
            for row in export.products {
                var product = Product(
                    name: row.name, brand: row.brand, description: row.description,
                    packageSizeGrams: row.packageSizeGrams, packageItemCount: row.packageItemCount,
                    servingSizeGrams: row.servingSizeGrams, servingUnit: row.servingUnit,
                    kcalPer100g: row.kcalPer100g, kcalPerServing: row.kcalPerServing,
                    proteinPer100g: row.proteinPer100g, carbsPer100g: row.carbsPer100g,
                    fatPer100g: row.fatPer100g, fiberPer100g: row.fiberPer100g,
                    sugarsPer100g: row.sugarsPer100g, saltPer100g: row.saltPer100g, source: row.source,
                    confidence: row.confidence, lastLoggedGrams: row.lastLoggedGrams,
                    createdAt: try instant(row.createdAt), archived: row.archived)
                product.id = row.id
                try connection.execute(
                    """
                    INSERT INTO products (id, name, brand, description, packageSizeGrams, packageSizeMilliliters,
                        packageItemCount, servingSizeGrams, servingUnit, kcalPer100g, kcalPer100ml, kcalPerServing,
                        proteinPer100g, carbsPer100g, fatPer100g, fiberPer100g, sugarsPer100g, saltPer100g, source,
                        confidence, lastLoggedGrams, createdAt, archived)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    [product.id] + Self.productArguments(product))
            }
            for row in export.rawEntries {
                try connection.execute(
                    """
                    INSERT INTO raw_entries (id, createdAt, logDate, consumedTime, rawText, entryKind, status,
                        parseJson, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    [row.id, try instant(row.createdAt), row.logDate, row.consumedTime, row.rawText,
                     row.entryKind.rawValue, row.status.rawValue, row.parseJson, row.notes])
            }
            for row in export.foodItems {
                let item = FoodItem(
                    rawEntryId: row.rawEntryId, logDate: row.logDate, consumedTime: row.consumedTime,
                    name: row.name, productId: row.productId, amount: row.amount, unit: row.unit,
                    grams: row.grams, calories: row.calories, source: row.source, confidence: row.confidence,
                    notes: row.notes, createdAt: try instant(row.createdAt), voided: row.voided)
                try connection.execute(
                    """
                    INSERT INTO food_items (id, rawEntryId, logDate, consumedTime, name, productId, shortcutKey,
                        amount, unit, grams, milliliters, calories, proteinG, fibreG, nutrientSource, source,
                        confidence, notes, aiContractVersion, aiProvider, aiModel, createdAt, voided)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    [row.id] + Self.foodItemArguments(item))
            }
            for row in export.dailyWeights {
                try connection.execute(
                    "INSERT INTO daily_weights (logDate, weightKg, measuredTime, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?)",
                    [row.logDate, row.weightKg, row.measuredTime, try instant(row.createdAt), try instant(row.updatedAt)])
            }
            var settings = try self.settings()
            if let boundary = export.settings.dayBoundaryTime {
                settings.dayBoundaryTime = LocalTime(iso: boundary)
            }
            try saveSettings(settings)

            let dates = export.foodItems.map(\.logDate)
            let summary = ImportSummary(
                shortcuts: export.shortcuts.count, products: export.products.count,
                rawEntries: export.rawEntries.count, foodItems: export.foodItems.count,
                dailyWeights: export.dailyWeights.count, firstDate: dates.min(), lastDate: dates.max())
            try connection.execute(
                """
                INSERT INTO imports (importedAt, format, version, sourcePlatform, exportedAt, summary)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                [clock.now, export.format, export.version, export.source.platform, export.exportedAt,
                 summary.description])
            return summary
        }
    }
}
