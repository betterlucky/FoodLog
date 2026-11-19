import Foundation

/// Every tracked nutrient, in display order. Screens, totals, backfill, exports and spoken summaries
/// loop over `Nutrient.allCases`, so they pick up a new nutrient without their own changes.
///
/// Adding one (say saturated fat):
/// 1. Add a `case` here. The exhaustive switches below then stop compiling until each says where the
///    value lives: the name, unit and icon, plus a `Double?` property on each model type.
/// 2. Add those properties, and a `food_items` column (plus shortcut columns) in a new schema migration.
/// 3. Teach the sources that can supply it: the label parser, and a new AI contract version and Worker
///    schema if AI estimates should include it.
public enum Nutrient: String, CaseIterable, Codable, Sendable, Identifiable {
    case protein
    case fibre

    public var id: String { rawValue }

    public var name: String {
        switch self {
        case .protein: return "Protein"
        case .fibre: return "Fibre"
        }
    }

    public var unit: String {
        switch self {
        case .protein, .fibre: return "g"
        }
    }

    /// SF Symbol for summaries.
    public var systemImage: String {
        switch self {
        case .protein: return "p.circle"
        case .fibre: return "leaf"
        }
    }

    /// `food_items` column, also used for day totals.
    var column: String {
        switch self {
        case .protein: return "proteinG"
        case .fibre: return "fibreG"
        }
    }

    /// Journal CSV column.
    var csvColumn: String {
        switch self {
        case .protein: return "protein_g"
        case .fibre: return "fibre_g"
        }
    }

    public var draft: WritableKeyPath<FoodDraft, Double?> {
        switch self {
        case .protein: return \.proteinG
        case .fibre: return \.fibreG
        }
    }

    public var item: WritableKeyPath<FoodItem, Double?> {
        switch self {
        case .protein: return \.proteinG
        case .fibre: return \.fibreG
        }
    }

    public var shortcutPerPortion: WritableKeyPath<Shortcut, Double?> {
        switch self {
        case .protein: return \.proteinPerPortion
        case .fibre: return \.fibrePerPortion
        }
    }

    public var shortcutPer100: WritableKeyPath<Shortcut, Double?> {
        switch self {
        case .protein: return \.proteinPer100
        case .fibre: return \.fibrePer100
        }
    }

    public var productPer100: KeyPath<Product, Double?> {
        switch self {
        case .protein: return \.proteinPer100g
        case .fibre: return \.fiberPer100g
        }
    }

    public var labelPer100: KeyPath<LabelNutritionFacts, Double?> {
        switch self {
        case .protein: return \.proteinPer100g
        case .fibre: return \.fiberPer100g
        }
    }

    public var labelPortion: KeyPath<LabelPortionResolution, Double?> {
        switch self {
        case .protein: return \.proteinG
        case .fibre: return \.fibreG
        }
    }

    public var resolvedShortcut: KeyPath<ResolvedShortcutFood, Double?> {
        switch self {
        case .protein: return \.proteinG
        case .fibre: return \.fibreG
        }
    }

    public var estimated: KeyPath<EstimatedItem, Double?> {
        switch self {
        case .protein: return \.proteinG
        case .fibre: return \.fibreG
        }
    }

    /// "1.7 g"
    public func format(_ value: Double) -> String {
        let number = value < 10 ? String(format: "%.1f", value) : String(Int(value.rounded()))
        return "\(number) \(unit)"
    }

    /// "Protein and fibre"
    public static var listName: String { listName(adding: []) }

    /// "Protein, fibre and notes"
    public static func listName(adding extras: [String]) -> String {
        let names = allCases.enumerated().map { $0.offset == 0 ? $0.element.name : $0.element.name.lowercased() } + extras
        guard names.count > 1 else { return names.first ?? "" }
        return names.dropLast().joined(separator: ", ") + " and " + names.last!
    }
}

/// Reading and copying nutrients by `Nutrient` rather than by named property.
public protocol NutrientCarrying {
    subscript(nutrient: Nutrient) -> Double? { get set }
}

extension NutrientCarrying {
    public var hasAnyNutrient: Bool { Nutrient.allCases.contains { self[$0] != nil } }
    public var isMissingAnyNutrient: Bool { Nutrient.allCases.contains { self[$0] == nil } }

    /// Copies every nutrient from a source through the given key path.
    public mutating func copyNutrients<Source>(from source: Source, _ path: (Nutrient) -> KeyPath<Source, Double?>) {
        for nutrient in Nutrient.allCases { self[nutrient] = source[keyPath: path(nutrient)] }
    }
}

extension FoodDraft: NutrientCarrying {
    public subscript(nutrient: Nutrient) -> Double? {
        get { self[keyPath: nutrient.draft] }
        set { self[keyPath: nutrient.draft] = newValue }
    }
}

extension FoodItem: NutrientCarrying {
    public subscript(nutrient: Nutrient) -> Double? {
        get { self[keyPath: nutrient.item] }
        set { self[keyPath: nutrient.item] = newValue }
    }
}
