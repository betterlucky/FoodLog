import Foundation

// Swift mirrors of the Android Room entities. Raw values match the Android enum names so the
// Android export imports unchanged. Android `UserDefaultEntity` is `Shortcut` here.

public enum EntryKind: String, Codable, CaseIterable, Sendable {
    case text = "TEXT"
    case photo = "PHOTO"
    case correction = "CORRECTION"
    case query = "QUERY"
    case exportCommand = "EXPORT_COMMAND"
    case note = "NOTE"
    case system = "SYSTEM"
}

public enum RawEntryStatus: String, Codable, CaseIterable, Sendable {
    case pending = "PENDING"
    case parsed = "PARSED"
    case needsReview = "NEEDS_REVIEW"
    case failed = "FAILED"
    case ignored = "IGNORED"
}

public enum ProductSource: String, Codable, CaseIterable, Sendable {
    case packagingPhoto = "PACKAGING_PHOTO"
    case manual = "MANUAL"
    case estimate = "ESTIMATE"
}

public enum FoodItemSource: String, Codable, CaseIterable, Sendable {
    case savedLabel = "SAVED_LABEL"
    case activeLeftover = "ACTIVE_LEFTOVER"
    case recentProduct = "RECENT_PRODUCT"
    case userDefault = "USER_DEFAULT"
    case manualOverride = "MANUAL_OVERRIDE"
    case estimate = "ESTIMATE"
}

public enum ShortcutPortionMode: String, Codable, CaseIterable, Sendable {
    case plain = "PLAIN"
    case item = "ITEM"
    case measure = "MEASURE"
}

public enum ConfidenceLevel: String, Codable, CaseIterable, Sendable {
    case high = "HIGH"
    case medium = "MEDIUM"
    case low = "LOW"
}

/// Where a food row's protein/fibre values came from. New on iOS; Android rows have none.
public enum NutrientSource: String, Codable, CaseIterable, Sendable {
    case label = "LABEL"
    case shortcut = "SHORTCUT"
    case estimate = "ESTIMATE"
    case manual = "MANUAL"
}

public struct RawEntry: Hashable, Codable, Identifiable, Sendable {
    public var id: Int64
    public var createdAt: Date
    public var logDate: LocalDate
    public var consumedTime: LocalTime?
    public var rawText: String
    public var entryKind: EntryKind
    public var status: RawEntryStatus
    public var parseJson: String?
    public var notes: String?

    public init(id: Int64 = 0, createdAt: Date, logDate: LocalDate, consumedTime: LocalTime?, rawText: String,
                entryKind: EntryKind, status: RawEntryStatus, parseJson: String? = nil, notes: String? = nil) {
        self.id = id
        self.createdAt = createdAt
        self.logDate = logDate
        self.consumedTime = consumedTime
        self.rawText = rawText
        self.entryKind = entryKind
        self.status = status
        self.parseJson = parseJson
        self.notes = notes
    }
}

/// AI provenance recorded with estimate-derived rows.
public struct AIProvenance: Hashable, Codable, Sendable {
    public var contractVersion: Int
    public var provider: String
    public var model: String

    public init(contractVersion: Int, provider: String, model: String) {
        self.contractVersion = contractVersion
        self.provider = provider
        self.model = model
    }
}

public struct FoodItem: Hashable, Codable, Identifiable, Sendable {
    public var id: Int64
    public var rawEntryId: Int64
    public var logDate: LocalDate
    public var consumedTime: LocalTime?
    public var name: String
    public var productId: Int64?
    /// The shortcut that produced this row, when it came from one. New on iOS.
    public var shortcutKey: String?
    public var amount: Double?
    public var unit: String?
    public var grams: Double?
    /// New on iOS: ml where known, alongside grams.
    public var milliliters: Double?
    public var calories: Double
    public var proteinG: Double?
    public var fibreG: Double?
    public var nutrientSource: NutrientSource?
    public var source: FoodItemSource
    public var confidence: ConfidenceLevel
    public var notes: String?
    public var aiProvenance: AIProvenance?
    public var createdAt: Date
    public var voided: Bool

    public init(id: Int64 = 0, rawEntryId: Int64, logDate: LocalDate, consumedTime: LocalTime?, name: String,
                productId: Int64? = nil, shortcutKey: String? = nil, amount: Double? = nil, unit: String? = nil,
                grams: Double? = nil, milliliters: Double? = nil, calories: Double, proteinG: Double? = nil,
                fibreG: Double? = nil, nutrientSource: NutrientSource? = nil, source: FoodItemSource,
                confidence: ConfidenceLevel, notes: String? = nil, aiProvenance: AIProvenance? = nil,
                createdAt: Date, voided: Bool = false) {
        self.id = id
        self.rawEntryId = rawEntryId
        self.logDate = logDate
        self.consumedTime = consumedTime
        self.name = name
        self.productId = productId
        self.shortcutKey = shortcutKey
        self.amount = amount
        self.unit = unit
        self.grams = grams
        self.milliliters = milliliters
        self.calories = calories
        self.proteinG = proteinG
        self.fibreG = fibreG
        self.nutrientSource = nutrientSource
        self.source = source
        self.confidence = confidence
        self.notes = notes
        self.aiProvenance = aiProvenance
        self.createdAt = createdAt
        self.voided = voided
    }
}

/// A saved, portion-first shortcut (Android `UserDefaultEntity`).
public struct Shortcut: Hashable, Codable, Identifiable, Sendable {
    public var id: String { lookupKey }
    public var lookupKey: String
    public var name: String
    public var calories: Double
    public var unit: String
    public var notes: String?
    public var source: FoodItemSource
    public var confidence: ConfidenceLevel
    public var active: Bool
    public var defaultAmount: Double?
    public var portionMode: ShortcutPortionMode
    public var itemUnit: String?
    public var itemSizeAmount: Double?
    public var itemSizeUnit: String?
    public var kcalPer100g: Double?
    public var kcalPer100ml: Double?
    public var nutritionBasisName: String?
    /// New on iOS. Per 100 g or 100 ml, on the same basis as `kcalPer100g`/`kcalPer100ml`.
    public var proteinPer100: Double?
    public var fibrePer100: Double?
    /// New on iOS. Per one `unit` portion, used for PLAIN shortcuts.
    public var proteinPerPortion: Double?
    public var fibrePerPortion: Double?

    public init(lookupKey: String, name: String, calories: Double, unit: String, notes: String? = nil,
                source: FoodItemSource = .userDefault, confidence: ConfidenceLevel = .high, active: Bool = true,
                defaultAmount: Double? = nil, portionMode: ShortcutPortionMode = .plain, itemUnit: String? = nil,
                itemSizeAmount: Double? = nil, itemSizeUnit: String? = nil, kcalPer100g: Double? = nil,
                kcalPer100ml: Double? = nil, nutritionBasisName: String? = nil, proteinPer100: Double? = nil,
                fibrePer100: Double? = nil, proteinPerPortion: Double? = nil, fibrePerPortion: Double? = nil) {
        self.lookupKey = lookupKey
        self.name = name
        self.calories = calories
        self.unit = unit
        self.notes = notes
        self.source = source
        self.confidence = confidence
        self.active = active
        self.defaultAmount = defaultAmount
        self.portionMode = portionMode
        self.itemUnit = itemUnit
        self.itemSizeAmount = itemSizeAmount
        self.itemSizeUnit = itemSizeUnit
        self.kcalPer100g = kcalPer100g
        self.kcalPer100ml = kcalPer100ml
        self.nutritionBasisName = nutritionBasisName
        self.proteinPer100 = proteinPer100
        self.fibrePer100 = fibrePer100
        self.proteinPerPortion = proteinPerPortion
        self.fibrePerPortion = fibrePerPortion
    }

    /// The seeded tea default from PLAN.md.
    public static let seededTea = Shortcut(
        lookupKey: "tea", name: "Tea", calories: 25, unit: "cup",
        notes: "English tea with skimmed milk and half a teaspoon of sugar")
}

public struct Product: Hashable, Codable, Identifiable, Sendable {
    public var id: Int64
    public var name: String
    public var brand: String?
    public var description: String?
    public var packageSizeGrams: Double?
    public var packageSizeMilliliters: Double?
    public var packageItemCount: Double?
    public var servingSizeGrams: Double?
    public var servingUnit: String?
    public var kcalPer100g: Double?
    public var kcalPer100ml: Double?
    public var kcalPerServing: Double?
    public var proteinPer100g: Double?
    public var carbsPer100g: Double?
    public var fatPer100g: Double?
    public var fiberPer100g: Double?
    public var sugarsPer100g: Double?
    public var saltPer100g: Double?
    public var source: ProductSource
    public var confidence: ConfidenceLevel
    public var lastLoggedGrams: Double?
    public var createdAt: Date
    public var archived: Bool

    public init(id: Int64 = 0, name: String, brand: String? = nil, description: String? = nil,
                packageSizeGrams: Double? = nil, packageSizeMilliliters: Double? = nil,
                packageItemCount: Double? = nil, servingSizeGrams: Double? = nil, servingUnit: String? = nil,
                kcalPer100g: Double? = nil, kcalPer100ml: Double? = nil, kcalPerServing: Double? = nil,
                proteinPer100g: Double? = nil, carbsPer100g: Double? = nil, fatPer100g: Double? = nil,
                fiberPer100g: Double? = nil, sugarsPer100g: Double? = nil, saltPer100g: Double? = nil,
                source: ProductSource, confidence: ConfidenceLevel, lastLoggedGrams: Double? = nil,
                createdAt: Date, archived: Bool = false) {
        self.id = id
        self.name = name
        self.brand = brand
        self.description = description
        self.packageSizeGrams = packageSizeGrams
        self.packageSizeMilliliters = packageSizeMilliliters
        self.packageItemCount = packageItemCount
        self.servingSizeGrams = servingSizeGrams
        self.servingUnit = servingUnit
        self.kcalPer100g = kcalPer100g
        self.kcalPer100ml = kcalPer100ml
        self.kcalPerServing = kcalPerServing
        self.proteinPer100g = proteinPer100g
        self.carbsPer100g = carbsPer100g
        self.fatPer100g = fatPer100g
        self.fiberPer100g = fiberPer100g
        self.sugarsPer100g = sugarsPer100g
        self.saltPer100g = saltPer100g
        self.source = source
        self.confidence = confidence
        self.lastLoggedGrams = lastLoggedGrams
        self.createdAt = createdAt
        self.archived = archived
    }
}

public struct DailyWeight: Hashable, Codable, Sendable {
    public var logDate: LocalDate
    public var weightKg: Double
    public var measuredTime: LocalTime
    public var createdAt: Date
    public var updatedAt: Date

    public init(logDate: LocalDate, weightKg: Double, measuredTime: LocalTime, createdAt: Date, updatedAt: Date) {
        self.logDate = logDate
        self.weightKg = weightKg
        self.measuredTime = measuredTime
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

public struct AppSettings: Hashable, Codable, Sendable {
    public var dayBoundaryTime: LocalTime?
    public var lastLabelInputMode: String
    public var journalIncludeWeight: Bool

    public init(dayBoundaryTime: LocalTime? = nil, lastLabelInputMode: String = "ITEMS",
                journalIncludeWeight: Bool = false) {
        self.dayBoundaryTime = dayBoundaryTime
        self.lastLabelInputMode = lastLabelInputMode
        self.journalIncludeWeight = journalIncludeWeight
    }
}
