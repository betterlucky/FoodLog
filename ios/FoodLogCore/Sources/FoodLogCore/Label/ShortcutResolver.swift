import Foundation

/// A shortcut resolved against a parsed food part: the portion, calories and (new on iOS) nutrients to log.
public struct ResolvedShortcutFood: Equatable, Sendable {
    public var name: String
    public var amount: Double?
    public var unit: String?
    public var grams: Double?
    /// New on iOS: ml where known, alongside grams.
    public var milliliters: Double?
    public var calories: Double
    /// New on iOS: nil when the shortcut carries no protein/fibre data for this portion.
    public var proteinG: Double?
    public var fibreG: Double?

    public init(name: String, amount: Double?, unit: String?, grams: Double?, milliliters: Double? = nil,
                calories: Double, proteinG: Double? = nil, fibreG: Double? = nil) {
        self.name = name
        self.amount = amount
        self.unit = unit
        self.grams = grams
        self.milliliters = milliliters
        self.calories = calories
        self.proteinG = proteinG
        self.fibreG = fibreG
    }
}

/// Shortcut portion resolution, ported from the private helpers in Android `FoodLogRepository`.
public enum ShortcutResolver {
    /// Kotlin `String.shortcutTrigger()`: trimmed, lowercased, whitespace collapsed to single spaces.
    public static func shortcutTrigger(_ text: String) -> String {
        LabelRegex(#"\s+"#).replace(labelTrim(text).lowercased(), with: " ")
    }

    /// Resolves `shortcut` for a parsed food part. An explicit quantity ("2 tea", "250g ham") is absolute for
    /// ITEM/MEASURE shortcuts; otherwise the shortcut's usual default amount applies.
    public static func resolve(_ shortcut: Shortcut, part: ParsedFoodPart) -> ResolvedShortcutFood {
        let explicitQuantity = hasExplicitQuantity(part)
        let defaultAmount = shortcut.defaultAmount.flatMap { $0 > 0.0 ? $0 : nil }
        var resolved: ResolvedShortcutFood
        switch shortcut.portionMode {
        case .item: resolved = resolveItem(shortcut, part, explicitQuantity, defaultAmount)
        case .measure: resolved = resolveMeasure(shortcut, part, explicitQuantity, defaultAmount)
        case .plain: resolved = resolvePlain(shortcut, part, explicitQuantity, defaultAmount)
        }
        resolved.name = shortcut.name
        return resolved
    }

    public static func hasExplicitQuantity(_ part: ParsedFoodPart) -> Bool {
        part.quantityUnit != nil
            || part.quantity != 1.0
            || shortcutTrigger(part.normalizedFoodText) != part.shortcutTrigger
    }

    public static func caloriesForUpdatedShortcut(_ shortcut: Shortcut, amount: Double?, calories: Double) -> Double {
        switch shortcut.portionMode {
        case .item, .measure:
            if let amount, amount > 0.0 { return calories / amount }
            return calories
        case .plain:
            return calories
        }
    }

    public static func updatedKcalPer100g(_ shortcut: Shortcut, updatedCalories: Double, unit: String?) -> Double? {
        switch shortcut.portionMode {
        case .item:
            if shortcut.itemSizeUnit == "g" {
                if let size = shortcut.itemSizeAmount, size > 0.0 { return updatedCalories * 100.0 / size }
                return shortcut.kcalPer100g
            }
            return shortcut.kcalPer100g
        case .measure:
            switch unit ?? shortcut.unit {
            case "g": return updatedCalories * 100.0
            case "ml": return nil
            default: return shortcut.kcalPer100g
            }
        case .plain:
            return shortcut.kcalPer100g
        }
    }

    public static func updatedKcalPer100ml(_ shortcut: Shortcut, updatedCalories: Double, unit: String?) -> Double? {
        switch shortcut.portionMode {
        case .item:
            if shortcut.itemSizeUnit == "ml" {
                if let size = shortcut.itemSizeAmount, size > 0.0 { return updatedCalories * 100.0 / size }
                return shortcut.kcalPer100ml
            }
            return shortcut.kcalPer100ml
        case .measure:
            switch unit ?? shortcut.unit {
            case "ml": return updatedCalories * 100.0
            case "g": return nil
            default: return shortcut.kcalPer100ml
            }
        case .plain:
            return shortcut.kcalPer100ml
        }
    }

    /// The label facts a stored ITEM/MEASURE shortcut can be resolved through, or nil when the shortcut
    /// lacks the nutrition basis. New on iOS: the shortcut's protein/fibre per 100 ride along.
    public static func toLabelFacts(_ shortcut: Shortcut) -> LabelNutritionFacts? {
        switch shortcut.portionMode {
        case .item:
            guard let itemUnit = shortcut.itemUnit, let size = shortcut.itemSizeAmount else { break }
            if shortcut.itemSizeUnit == "g", let kcalPer100g = shortcut.kcalPer100g {
                return LabelNutritionFacts(
                    rawText: "", kcalPer100g: kcalPer100g, packageSizeGrams: size, packageItemCount: 1.0,
                    packageItemUnit: itemUnit, proteinPer100g: shortcut.proteinPer100, fiberPer100g: shortcut.fibrePer100
                )
            }
            if shortcut.itemSizeUnit == "ml", let kcalPer100ml = shortcut.kcalPer100ml {
                return LabelNutritionFacts(
                    rawText: "", kcalPer100ml: kcalPer100ml, packageSizeMilliliters: size, packageItemCount: 1.0,
                    packageItemUnit: itemUnit, proteinPer100g: shortcut.proteinPer100, fiberPer100g: shortcut.fibrePer100
                )
            }
        case .measure:
            if let kcalPer100g = shortcut.kcalPer100g {
                return LabelNutritionFacts(
                    rawText: "", kcalPer100g: kcalPer100g,
                    proteinPer100g: shortcut.proteinPer100, fiberPer100g: shortcut.fibrePer100
                )
            }
            if let kcalPer100ml = shortcut.kcalPer100ml {
                return LabelNutritionFacts(
                    rawText: "", kcalPer100ml: kcalPer100ml,
                    proteinPer100g: shortcut.proteinPer100, fiberPer100g: shortcut.fibrePer100
                )
            }
        case .plain:
            break
        }
        return nil
    }

    /// Kotlin `Double.formatPlainNumber()`: whole numbers without ".0", otherwise `Double.toString()`.
    public static func formatPlainNumber(_ value: Double) -> String {
        labelIsWhole(value) ? labelIntegerString(value) : labelDoubleString(value)
    }

    // MARK: - Modes

    private static func resolvePlain(_ shortcut: Shortcut, _ part: ParsedFoodPart, _ explicitQuantity: Bool,
                                     _ defaultAmount: Double?) -> ResolvedShortcutFood {
        let multiplier = explicitQuantity ? part.quantity : 1.0
        let amount = (defaultAmount ?? 1.0) * multiplier
        return ResolvedShortcutFood(
            name: shortcut.name,
            amount: amount,
            unit: shortcut.unit,
            grams: shortcut.unit == "g" ? amount : nil,
            calories: shortcut.calories * multiplier,
            proteinG: shortcut.proteinPerPortion.map { $0 * multiplier },
            fibreG: shortcut.fibrePerPortion.map { $0 * multiplier }
        )
    }

    private static func resolveItem(_ shortcut: Shortcut, _ part: ParsedFoodPart, _ explicitQuantity: Bool,
                                    _ defaultAmount: Double?) -> ResolvedShortcutFood {
        let amount = explicitQuantity ? part.quantity : (defaultAmount ?? 1.0)
        let resolution = toLabelFacts(shortcut).map {
            LabelPortionResolver.resolve($0, mode: .items, amountText: labelDoubleString(amount))
        }
        return ResolvedShortcutFood(
            name: shortcut.name,
            amount: resolution?.amount ?? amount,
            unit: resolution?.unit ?? shortcut.itemUnit ?? shortcut.unit,
            grams: resolution?.grams,
            milliliters: resolution?.milliliters,
            calories: resolution?.calories ?? shortcut.calories * amount,
            proteinG: resolution?.proteinG,
            fibreG: resolution?.fibreG
        )
    }

    private static func resolveMeasure(_ shortcut: Shortcut, _ part: ParsedFoodPart, _ explicitQuantity: Bool,
                                       _ defaultAmount: Double?) -> ResolvedShortcutFood {
        let measureUnits = ["g", "ml"]
        let explicitMeasure = explicitQuantity && part.quantityUnit.map(measureUnits.contains) == true
        let amount: Double
        if explicitMeasure {
            amount = part.quantity
        } else if explicitQuantity {
            amount = (defaultAmount ?? 1.0) * part.quantity
        } else {
            amount = defaultAmount ?? 1.0
        }
        let resolvedUnit: String
        if explicitMeasure {
            resolvedUnit = part.quantityUnit ?? shortcut.unit
        } else if measureUnits.contains(shortcut.unit) {
            resolvedUnit = shortcut.unit
        } else if let sizeUnit = shortcut.itemSizeUnit, measureUnits.contains(sizeUnit) {
            resolvedUnit = sizeUnit
        } else {
            resolvedUnit = shortcut.unit
        }
        let resolution = toLabelFacts(shortcut).map {
            LabelPortionResolver.resolve($0, mode: .measure, amountText: "\(formatPlainNumber(amount))\(resolvedUnit)")
        }
        return ResolvedShortcutFood(
            name: shortcut.name,
            amount: resolution?.amount ?? amount,
            unit: resolution?.unit ?? resolvedUnit,
            grams: resolution?.grams ?? (resolvedUnit == "g" ? amount : nil),
            milliliters: resolution?.milliliters,
            calories: resolution?.calories ?? shortcut.calories * amount,
            proteinG: resolution?.proteinG,
            fibreG: resolution?.fibreG
        )
    }
}

extension Shortcut {
    /// Convenience for `ShortcutResolver.resolve(_:part:)`.
    public func resolve(part: ParsedFoodPart) -> ResolvedShortcutFood {
        ShortcutResolver.resolve(self, part: part)
    }
}
