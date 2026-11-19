import Foundation

public struct LabelShortcutDraft: Equatable, Sendable {
    public var caloriesPerUnit: Double
    public var unit: String
    public var defaultAmount: Double?
    public var portionMode: ShortcutPortionMode
    public var itemUnit: String?
    public var itemSizeAmount: Double?
    public var itemSizeUnit: String?
    public var kcalPer100g: Double?
    public var kcalPer100ml: Double?
    /// New on iOS. Per 100 g or 100 ml, on the same basis as `kcalPer100g`/`kcalPer100ml`.
    public var proteinPer100: Double?
    public var fibrePer100: Double?
    /// New on iOS. For PLAIN shortcuts only: nutrients in one default portion (matching `caloriesPerUnit`).
    public var proteinPerPortion: Double?
    public var fibrePerPortion: Double?

    public init(caloriesPerUnit: Double, unit: String, defaultAmount: Double?, portionMode: ShortcutPortionMode,
                itemUnit: String?, itemSizeAmount: Double?, itemSizeUnit: String?, kcalPer100g: Double?,
                kcalPer100ml: Double?, proteinPer100: Double? = nil, fibrePer100: Double? = nil,
                proteinPerPortion: Double? = nil, fibrePerPortion: Double? = nil) {
        self.caloriesPerUnit = caloriesPerUnit
        self.unit = unit
        self.defaultAmount = defaultAmount
        self.portionMode = portionMode
        self.itemUnit = itemUnit
        self.itemSizeAmount = itemSizeAmount
        self.itemSizeUnit = itemSizeUnit
        self.kcalPer100g = kcalPer100g
        self.kcalPer100ml = kcalPer100ml
        self.proteinPer100 = proteinPer100
        self.fibrePer100 = fibrePer100
        self.proteinPerPortion = proteinPerPortion
        self.fibrePerPortion = fibrePerPortion
    }
}

public struct LabelShortcutItemSize: Equatable, Sendable {
    public var amount: Double
    public var unit: String

    public init(amount: Double, unit: String) {
        self.amount = amount
        self.unit = unit
    }
}

public enum LabelShortcutBuilder {
    public static func build(facts: LabelNutritionFacts, inputMode: LabelInputMode,
                             resolvedPortion: LabelPortionResolution, calories: Double,
                             userProvidedItemSize: LabelShortcutItemSize? = nil) -> LabelShortcutDraft {
        let itemSize = userProvidedItemSize ?? itemSizeFor(facts, inputMode, resolvedPortion)
        let kcalPer100g = effectiveKcalPer100g(facts)
        let kcalPer100ml = facts.kcalPer100ml
        let mode = shortcutMode(inputMode, itemSize, kcalPer100g, kcalPer100ml)
        let defaultAmount = defaultAmount(mode, resolvedPortion, itemSize)
        let resolvedItemUnit = LabelPortionResolver.itemUnit(facts)

        // New on iOS: keep the label's protein and fibre. Only a genuine per-100g/per-100ml label basis is
        // used; a per-100g figure derived from per-serving calories would not share a basis with them.
        let hasPer100Basis = facts.kcalPer100g != nil || facts.kcalPer100ml != nil
        return LabelShortcutDraft(
            caloriesPerUnit: caloriesPerUnit(mode, resolvedPortion, calories, itemSize, kcalPer100g, kcalPer100ml),
            unit: unit(mode, resolvedPortion.unit, itemSize?.unit, resolvedItemUnit),
            defaultAmount: defaultAmount,
            portionMode: mode,
            itemUnit: mode == .item ? resolvedItemUnit : nil,
            itemSizeAmount: itemSize?.amount,
            itemSizeUnit: itemSize?.unit,
            kcalPer100g: kcalPer100g,
            kcalPer100ml: kcalPer100ml,
            proteinPer100: hasPer100Basis ? facts.proteinPer100g : nil,
            fibrePer100: hasPer100Basis ? facts.fiberPer100g : nil,
            proteinPerPortion: mode == .plain ? resolvedPortion.proteinG : nil,
            fibrePerPortion: mode == .plain ? resolvedPortion.fibreG : nil
        )
    }

    public static func inferredItemSize(facts: LabelNutritionFacts, inputMode: LabelInputMode,
                                        resolvedPortion: LabelPortionResolution) -> LabelShortcutItemSize? {
        itemSizeFor(facts, inputMode, resolvedPortion)
    }

    private static func shortcutMode(_ inputMode: LabelInputMode, _ itemSize: LabelShortcutItemSize?,
                                     _ kcalPer100g: Double?, _ kcalPer100ml: Double?) -> ShortcutPortionMode {
        if itemSize != nil && (kcalPer100g != nil || kcalPer100ml != nil) { return .item }
        if inputMode == .measure { return .measure }
        return .plain
    }

    private static func defaultAmount(_ mode: ShortcutPortionMode, _ resolvedPortion: LabelPortionResolution,
                                      _ itemSize: LabelShortcutItemSize?) -> Double? {
        switch mode {
        case .item:
            guard let size = itemSize else { return resolvedPortion.amount }
            let derived: Double?
            switch size.unit {
            case "g": derived = resolvedPortion.grams.flatMap { $0 > 0.0 ? $0 / size.amount : nil }
            case "ml": derived = resolvedPortion.milliliters.flatMap { $0 > 0.0 ? $0 / size.amount : nil }
            default: derived = nil
            }
            return derived ?? resolvedPortion.amount
        case .measure, .plain:
            return resolvedPortion.amount
        }
    }

    private static func caloriesPerUnit(_ mode: ShortcutPortionMode, _ resolvedPortion: LabelPortionResolution,
                                        _ calories: Double, _ itemSize: LabelShortcutItemSize?,
                                        _ kcalPer100g: Double?, _ kcalPer100ml: Double?) -> Double {
        switch mode {
        case .item:
            if let size = itemSize, size.unit == "g", let kcalPer100g {
                return kcalPer100g * size.amount / 100.0
            }
            if let size = itemSize, size.unit == "ml", let kcalPer100ml {
                return kcalPer100ml * size.amount / 100.0
            }
            if let amount = resolvedPortion.amount, amount > 0.0 { return calories / amount }
            return calories
        case .measure:
            if let amount = resolvedPortion.amount, amount > 0.0 { return calories / amount }
            return calories
        case .plain:
            return calories
        }
    }

    private static func unit(_ mode: ShortcutPortionMode, _ resolvedUnit: String?, _ itemSizeUnit: String?,
                             _ itemUnit: String) -> String {
        switch mode {
        case .item: return itemUnit
        case .measure: return itemSizeUnit ?? resolvedUnit ?? "g"
        case .plain: return resolvedUnit ?? "serving"
        }
    }

    private static func itemSizeFor(_ facts: LabelNutritionFacts, _ inputMode: LabelInputMode,
                                    _ resolvedPortion: LabelPortionResolution) -> LabelShortcutItemSize? {
        if inputMode == .items {
            if let grams = resolvedPortion.grams, grams > 0.0,
               let amount = resolvedPortion.amount, amount > 0.0 {
                return LabelShortcutItemSize(amount: grams / amount, unit: "g")
            }
            if let milliliters = resolvedPortion.milliliters, milliliters > 0.0,
               let amount = resolvedPortion.amount, amount > 0.0 {
                return LabelShortcutItemSize(amount: milliliters / amount, unit: "ml")
            }
        }

        if let servingAmount = facts.servingAmount, servingAmount > 0.0, facts.servingItemUnit != nil {
            if let servingGrams = facts.servingSizeGrams, servingGrams > 0.0 {
                return LabelShortcutItemSize(amount: servingGrams / servingAmount, unit: "g")
            }
        }

        if let packageItems = facts.packageItemCount, packageItems > 0.0, facts.packageItemUnit != nil {
            if let packageGrams = facts.packageSizeGrams, packageGrams > 0.0 {
                return LabelShortcutItemSize(amount: packageGrams / packageItems, unit: "g")
            }
            if let packageMilliliters = facts.packageSizeMilliliters, packageMilliliters > 0.0 {
                return LabelShortcutItemSize(amount: packageMilliliters / packageItems, unit: "ml")
            }
        }
        return nil
    }

    private static func effectiveKcalPer100g(_ facts: LabelNutritionFacts) -> Double? {
        if let per100g = facts.kcalPer100g { return per100g }
        if let servingGrams = facts.servingSizeGrams, servingGrams > 0.0, let perServing = facts.kcalPerServing {
            return perServing * 100.0 / servingGrams
        }
        return nil
    }
}
