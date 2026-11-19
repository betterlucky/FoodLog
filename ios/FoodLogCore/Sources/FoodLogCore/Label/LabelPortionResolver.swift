import Foundation

public enum LabelInputMode: String, Codable, CaseIterable, Sendable {
    case items = "ITEMS"
    case measure = "MEASURE"

    public var storageValue: String { rawValue }

    /// Anything other than "MEASURE" falls back to ITEMS, like the Kotlin `fromStorage`.
    public static func fromStorage(_ value: String?) -> LabelInputMode {
        value == LabelInputMode.measure.storageValue ? .measure : .items
    }
}

public struct LabelPortionResolution: Equatable, Sendable {
    public var amount: Double?
    public var unit: String?
    public var grams: Double?
    public var milliliters: Double?
    public var calories: Double?
    public var isValidAmount: Bool
    /// New on iOS: protein and fibre for this portion, from the label's per-100 values. Nil when unknown.
    public var proteinG: Double?
    public var fibreG: Double?

    public init(amount: Double?, unit: String?, grams: Double?, milliliters: Double? = nil, calories: Double?,
                isValidAmount: Bool, proteinG: Double? = nil, fibreG: Double? = nil) {
        self.amount = amount
        self.unit = unit
        self.grams = grams
        self.milliliters = milliliters
        self.calories = calories
        self.isValidAmount = isValidAmount
        self.proteinG = proteinG
        self.fibreG = fibreG
    }

    fileprivate static let invalid = LabelPortionResolution(
        amount: nil, unit: nil, grams: nil, milliliters: nil, calories: nil, isValidAmount: false
    )
}

public enum LabelPortionResolver {
    public static func resolve(_ facts: LabelNutritionFacts, mode: LabelInputMode, amountText: String) -> LabelPortionResolution {
        let trimmedAmount = labelTrim(amountText)
        if trimmedAmount.isEmpty { return .invalid }
        switch mode {
        case .items: return resolveItems(facts, trimmedAmount)
        case .measure: return resolveMeasure(facts, trimmedAmount)
        }
    }

    public static func itemUnit(_ facts: LabelNutritionFacts) -> String {
        facts.servingItemUnit ?? facts.packageItemUnit ?? "item"
    }

    public static func displayAmount(_ amount: Double, unit: String) -> String {
        if nearly(amount, 1.0) { return "one \(unit)" }
        if nearly(amount, 0.5) { return "half \(articleFor(unit)) \(unit)" }
        if nearly(amount, 1.0 / 3.0) { return "third of \(articleFor(unit)) \(unit)" }
        if nearly(amount, 2.0 / 3.0) { return "two thirds of \(articleFor(unit)) \(unit)" }
        if nearly(amount, 0.25) { return "quarter of \(articleFor(unit)) \(unit)" }
        if nearly(amount, 0.75) { return "three quarters of \(articleFor(unit)) \(unit)" }
        return "\(formatClean(amount)) \(pluralizedUnit(unit, amount))"
    }

    // MARK: - Items

    private static func resolveItems(_ facts: LabelNutritionFacts, _ amountText: String) -> LabelPortionResolution {
        guard let amount = amountText.toItemAmount() else { return .invalid }
        let unit = itemUnit(facts)
        let grams = gramsForItemAmount(facts, amount)
        let milliliters = millilitersForItemAmount(facts, amount)
        let calories = caloriesForItemAmount(facts, amount, grams, milliliters)
        let nutrients = nutrients(facts, grams: grams, milliliters: milliliters)
        return LabelPortionResolution(
            amount: amount, unit: unit, grams: grams, milliliters: milliliters, calories: calories,
            isValidAmount: true, proteinG: nutrients.protein, fibreG: nutrients.fibre
        )
    }

    private static let measurePattern = LabelRegex.entire(#"(\d+(?:[.,]\d+)?)\s*(g|ml)"#, ignoreCase: true)

    private static func resolveMeasure(_ facts: LabelNutritionFacts, _ amountText: String) -> LabelPortionResolution {
        guard let match = measurePattern.find(amountText.replacingOccurrences(of: " ", with: "")),
              let amount = labelParseDouble(match.groupValues[1].replacingOccurrences(of: ",", with: ".")).flatMap({ $0 > 0.0 ? $0 : nil })
        else { return .invalid }
        let unit = match.groupValues[2].lowercased()
        let grams: Double? = unit == "g" ? amount : nil
        let milliliters: Double? = unit == "ml" ? amount : nil
        var calories: Double?
        switch unit {
        case "g":
            if let per100g = facts.kcalPer100g {
                calories = per100g * amount / 100.0
            } else if let servingGrams = facts.servingSizeGrams, servingGrams > 0.0, let perServing = facts.kcalPerServing {
                calories = perServing * amount / servingGrams
            }
        case "ml":
            calories = facts.kcalPer100ml.map { $0 * amount / 100.0 }
        default:
            calories = nil
        }
        let nutrients = nutrients(facts, grams: grams, milliliters: milliliters)
        return LabelPortionResolution(
            amount: amount, unit: unit, grams: grams, milliliters: milliliters, calories: calories,
            isValidAmount: true, proteinG: nutrients.protein, fibreG: nutrients.fibre
        )
    }

    private static func gramsForItemAmount(_ facts: LabelNutritionFacts, _ amount: Double) -> Double? {
        let servingAmount = facts.servingAmount.flatMap { $0 > 0.0 ? $0 : nil }
        if let servingAmount, let servingGrams = facts.servingSizeGrams, facts.servingItemUnit != nil {
            return amount / servingAmount * servingGrams
        }
        let packageCount = facts.packageItemCount.flatMap { $0 > 0.0 ? $0 : nil }
        if let packageGrams = facts.packageSizeGrams, let packageCount {
            return amount * packageGrams / packageCount
        }
        return nil
    }

    private static func caloriesForItemAmount(_ facts: LabelNutritionFacts, _ amount: Double,
                                              _ grams: Double?, _ milliliters: Double?) -> Double? {
        let servingAmount = facts.servingAmount.flatMap { $0 > 0.0 ? $0 : nil }
        let concreteServing = facts.servingSizeGrams != nil
            || (facts.servingItemUnit.map { !isGenericServingUnit($0) } ?? false)
        if let servingAmount, let perServing = facts.kcalPerServing, concreteServing {
            return amount / servingAmount * perServing
        }
        if let grams, let per100g = facts.kcalPer100g {
            return per100g * grams / 100.0
        }
        if let milliliters, let per100ml = facts.kcalPer100ml {
            return per100ml * milliliters / 100.0
        }
        return nil
    }

    private static func millilitersForItemAmount(_ facts: LabelNutritionFacts, _ amount: Double) -> Double? {
        let packageCount = facts.packageItemCount.flatMap { $0 > 0.0 ? $0 : nil }
        if let packageMilliliters = facts.packageSizeMilliliters, let packageCount {
            return amount * packageMilliliters / packageCount
        }
        return nil
    }

    // MARK: - Protein and fibre (new on iOS)

    /// Protein and fibre for a resolved portion. The label's per-100 values are only trusted when the label
    /// has a real per-100g or per-100ml calorie basis, because without one the first number on a nutrient
    /// line may be a per-serving figure. The basis is millilitres when the label is per-100ml only.
    private static func nutrients(_ facts: LabelNutritionFacts, grams: Double?, milliliters: Double?)
        -> (protein: Double?, fibre: Double?) {
        guard facts.kcalPer100g != nil || facts.kcalPer100ml != nil else { return (nil, nil) }
        let basisIsMilliliters = facts.kcalPer100g == nil
        let basisAmount = basisIsMilliliters ? milliliters : grams
        guard let basisAmount else { return (nil, nil) }
        return (
            facts.proteinPer100g.map { $0 * basisAmount / 100.0 },
            facts.fiberPer100g.map { $0 * basisAmount / 100.0 }
        )
    }

    // MARK: - Formatting helpers

    private static func pluralizedUnit(_ unit: String, _ amount: Double) -> String {
        if amount <= 1.0 || nearly(amount, 1.0) { return unit }
        if unit.hasSuffix("s") { return unit }
        return unit + "s"
    }

    private static func articleFor(_ unit: String) -> String {
        guard let first = unit.first?.lowercased() else { return "a" }
        return ["a", "e", "i", "o", "u"].contains(first) ? "an" : "a"
    }

    private static func nearly(_ left: Double, _ right: Double) -> Bool {
        abs(left - right) < 0.0001
    }

    private static func formatClean(_ value: Double) -> String {
        if labelIsWhole(value) { return labelIntegerString(value) }
        return labelTrimEnd(labelTrimEnd(labelFormatFixed2(value), "0"), ".")
    }
}

extension String {
    /// Kotlin `String.toItemAmount()`: "half", "1/2", "2.5", "two thirds of a can", unicode fractions, etc.
    public func toItemAmount() -> Double? {
        var normalized = labelTrim(self).lowercased()
        normalized = normalized.replacingOccurrences(of: "-", with: " ")
        normalized = LabelRegex(#"\s+"#).replace(normalized, with: " ")
        normalized = LabelRegex(#"\s+of\s+(?:a|an|the)\s+\w+$"#).replace(normalized, with: "")
        normalized = LabelRegex(#"\s+(?:can|bag|pack|bar|bottle|pot|tub|item|serving|portion)$"#).replace(normalized, with: "")
        switch normalized {
        case "one", "a", "an": return 1.0
        case "two thirds", "two third": return 2.0 / 3.0
        case "half", "a half": return 0.5
        case "third", "one third", "a third": return 1.0 / 3.0
        case "quarter", "one quarter", "a quarter": return 0.25
        case "three quarters", "three quarter": return 0.75
        default:
            let compact = normalized.replacingOccurrences(of: " ", with: "")
            switch compact {
            case "½": return 0.5
            case "⅓": return 1.0 / 3.0
            case "⅔": return 2.0 / 3.0
            case "¼": return 0.25
            case "¾": return 0.75
            default:
                if let fraction = LabelRegex.entire(#"(\d+(?:[.,]\d+)?)/(\d+(?:[.,]\d+)?)"#).find(compact) {
                    let numerator = labelParseDouble(fraction.groupValues[1].replacingOccurrences(of: ",", with: "."))
                    let denominator = labelParseDouble(fraction.groupValues[2].replacingOccurrences(of: ",", with: "."))
                    if let numerator, let denominator, denominator > 0.0 {
                        return numerator / denominator
                    }
                    return nil
                }
                return labelParseDouble(compact.replacingOccurrences(of: ",", with: "."))
                    .flatMap { $0 > 0.0 ? $0 : nil }
            }
        }
    }
}
