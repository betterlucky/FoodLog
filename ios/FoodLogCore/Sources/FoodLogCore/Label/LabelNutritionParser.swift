import Foundation

/// Facts parsed from OCR text of a nutrition label. Port of Kotlin `LabelNutritionFacts`.
public struct LabelNutritionFacts: Equatable, Sendable {
    public var rawText: String
    public var kcalPer100g: Double?
    public var kcalPer100ml: Double?
    public var kcalPerServing: Double?
    public var servingUnit: String?
    public var servingAmount: Double?
    public var servingItemUnit: String?
    public var servingSizeGrams: Double?
    public var packageSizeGrams: Double?
    public var packageSizeMilliliters: Double?
    public var packageItemCount: Double?
    public var packageItemUnit: String?
    public var proteinPer100g: Double?
    public var fiberPer100g: Double?
    public var carbsPer100g: Double?
    public var fatPer100g: Double?
    public var sugarsPer100g: Double?
    public var saltPer100g: Double?
    public var prepared: Bool

    public init(rawText: String, kcalPer100g: Double? = nil, kcalPer100ml: Double? = nil,
                kcalPerServing: Double? = nil, servingUnit: String? = nil, servingAmount: Double? = nil,
                servingItemUnit: String? = nil, servingSizeGrams: Double? = nil, packageSizeGrams: Double? = nil,
                packageSizeMilliliters: Double? = nil, packageItemCount: Double? = nil,
                packageItemUnit: String? = nil, proteinPer100g: Double? = nil, fiberPer100g: Double? = nil,
                carbsPer100g: Double? = nil, fatPer100g: Double? = nil, sugarsPer100g: Double? = nil,
                saltPer100g: Double? = nil, prepared: Bool = false) {
        self.rawText = rawText
        self.kcalPer100g = kcalPer100g
        self.kcalPer100ml = kcalPer100ml
        self.kcalPerServing = kcalPerServing
        self.servingUnit = servingUnit
        self.servingAmount = servingAmount
        self.servingItemUnit = servingItemUnit
        self.servingSizeGrams = servingSizeGrams
        self.packageSizeGrams = packageSizeGrams
        self.packageSizeMilliliters = packageSizeMilliliters
        self.packageItemCount = packageItemCount
        self.packageItemUnit = packageItemUnit
        self.proteinPer100g = proteinPer100g
        self.fiberPer100g = fiberPer100g
        self.carbsPer100g = carbsPer100g
        self.fatPer100g = fatPer100g
        self.sugarsPer100g = sugarsPer100g
        self.saltPer100g = saltPer100g
        self.prepared = prepared
    }

    public var hasRequiredCalories: Bool {
        kcalPer100g != nil || kcalPer100ml != nil || kcalPerServing != nil
    }

    public var isPartial: Bool {
        !hasRequiredCalories || (
            kcalPerServing != nil
                && servingSizeGrams == nil
                && servingItemUnit.map(isGenericServingUnit) != false
        )
    }
}

/// Kotlin `isGenericServingUnit`: "serving" and "portion" (optionally plural) carry no concrete size.
public func isGenericServingUnit(_ unit: String) -> Bool {
    var normalized = labelTrim(unit).lowercased()
    if normalized.hasSuffix("s") { normalized.removeLast() }
    return normalized == "serving" || normalized == "portion"
}

/// Conservative OCR nutrition-label text parser. Port of Kotlin `LabelNutritionParser`.
public struct LabelNutritionParser: Sendable {
    public init() {}

    // Note: `servingUnitPattern` is deliberately NOT wrapped in a group, exactly like the Kotlin source;
    // some patterns below interpolate it bare, which changes how the alternation binds.
    private static let servingUnitPattern = "cup|serving|sachet|slice|item|bar|portion|can|bag|pack|bottle|pot|tub|fillet|finger|sausage"
    private static let servingAmountPattern = #"(?:\d+(?:[.,]\d+)?|\d+\s*/\s*\d+|[ilt]\s*/\s*\d+|[½⅓⅔¼¾])"#
    private static let per100g = LabelRegex(#"\bper\s*100\s*[gqao](?=\b|per|:)"#)
    private static let per100ml = LabelRegex(#"\bper\s*100\s*m[l1i](?=\b|per|:)"#)
    private static let kcal = LabelRegex(#"(\d+(?:[.,]\d+)?)\s*k[ce]al"#, ignoreCase: true)

    public func parse(_ rawText: String) -> LabelNutritionFacts {
        let spaced = rawText.replacingOccurrences(of: "\u{00A0}", with: " ")
        let normalizedUnits = Self.normalizeOcrUnitCharacters(spaced)
        let normalized = labelTrim(LabelRegex(#"[ \t]+"#).replace(normalizedUnits, with: " "))
        let lower = normalized.lowercased()
        let kcalValues = extractKcalValues(normalized: normalized, lower: lower)
        let serving = extractServing(lower)
        let servingSizeGrams = extractServingSizeGrams(lower)
        let packageQuantity = parsePackageQuantity(lower)
        let prepared = ["made up", "prepared", "as consumed", "per cup"].contains { lower.contains($0) }

        return LabelNutritionFacts(
            rawText: rawText,
            kcalPer100g: kcalValues.per100g,
            kcalPer100ml: kcalValues.per100ml,
            kcalPerServing: kcalValues.perServing,
            servingUnit: serving?.label,
            servingAmount: serving?.amount,
            servingItemUnit: serving?.unit,
            servingSizeGrams: servingSizeGrams,
            packageSizeGrams: packageQuantity.grams,
            packageSizeMilliliters: packageQuantity.milliliters,
            packageItemCount: packageQuantity.itemCount,
            packageItemUnit: packageQuantity.itemUnit,
            proteinPer100g: extractPer100gNutrient(lower, "protein"),
            fiberPer100g: extractPer100gNutrient(lower, "fibre") ?? extractPer100gNutrient(lower, "fiber"),
            carbsPer100g: extractPer100gNutrient(lower, "carbohydrate"),
            fatPer100g: extractPer100gNutrient(lower, "fat"),
            sugarsPer100g: extractPer100gNutrient(lower, "sugars"),
            saltPer100g: extractPer100gNutrient(lower, "salt"),
            prepared: prepared
        )
    }

    // MARK: - Calories

    private struct KcalValues {
        var per100g: Double?
        var per100ml: Double?
        var perServing: Double?
        init(per100g: Double? = nil, per100ml: Double? = nil, perServing: Double? = nil) {
            self.per100g = per100g
            self.per100ml = per100ml
            self.perServing = perServing
        }
    }

    private func extractKcalValues(normalized: String, lower: String) -> KcalValues {
        // Line-contextual approach: find a line that mentions 100g/100ml and contains kcal. This handles
        // labels where serving and 100g rows appear in any order.
        let lines = labelLines(normalized)
        let per100g = Self.per100g
        let per100ml = Self.per100ml
        let kcal = Self.kcal
        let per100Index = lines.firstIndex { line in
            let ll = line.lowercased()
            return (per100g.containsMatch(ll) || per100ml.containsMatch(ll)) && kcal.containsMatch(line)
        }

        if let per100Index {
            let per100Line = lines[per100Index]
            let lineLower = per100Line.lowercased()
            let per100 = kcal.find(per100Line).flatMap { labelToDoubleValue($0.groupValues[1]) }
            // Per-serving: look for kcal on a line that does NOT mention 100g
            let servingIndex = lines.indices.first { index in
                if index == per100Index { return false }
                let line = lines[index]
                let ll = line.lowercased()
                return !per100g.containsMatch(ll)
                    && !per100ml.containsMatch(ll)
                    && !nonReferenceKcalValues(line).isEmpty
            }
            var perServing: Double?
            if let servingIndex {
                perServing = nonReferenceKcalValues(lines[servingIndex]).first
            }
            if perServing == nil {
                // Both values may be on the same 100g line (e.g. "450kcal 135kcal per 100g per serving")
                perServing = nonReferenceKcalValues(per100Line).dropFirst().first
            }
            let has100g = per100g.containsMatch(lineLower)
            if !has100g && per100ml.containsMatch(lineLower) {
                return KcalValues(per100ml: per100, perServing: perServing)
            }
            return KcalValues(per100g: per100, perServing: perServing)
        }

        // Fallback: positional approach - first kcal is per 100g if the document mentions it
        let allKcal = lines.flatMap { nonReferenceKcalValues($0) }
        let first: Double? = allKcal.indices.contains(0) ? allKcal[0] : nil
        let second: Double? = allKcal.indices.contains(1) ? allKcal[1] : nil
        if per100ml.containsMatch(lower) { return KcalValues(per100ml: first, perServing: second) }
        if per100g.containsMatch(lower) { return KcalValues(per100g: first, perServing: second) }
        return KcalValues(perServing: first)
    }

    private func nonReferenceKcalValues(_ line: String) -> [Double] {
        let lowerLine = line.lowercased()
        return Self.kcal.findAll(line).compactMap { match -> Double? in
            guard let value = labelToDoubleValue(match.groupValues[1]) else { return nil }
            return isReferenceIntakeKcal(lowerLine, value) ? nil : value
        }
    }

    private func isReferenceIntakeKcal(_ line: String, _ value: Double) -> Bool {
        line.contains("adults need")
            || (
                value == 2000.0
                    && (
                        line.contains("reference intake")
                            || line.contains("average adult")
                            || line.contains("your ri")
                            || line.contains("%ri")
                            || LabelRegex(#"\b8400\s*k[j3]\b"#).containsMatch(line)
                    )
            )
    }

    // MARK: - Serving

    private struct ServingDescriptor {
        let amount: Double
        let unit: String
        let label: String
    }

    private func extractServing(_ lower: String) -> ServingDescriptor? {
        let amountPattern = Self.servingAmountPattern
        let unitPattern = Self.servingUnitPattern

        if let match = LabelRegex(#"\bper\s*(\#(amountPattern))\s*(\#(unitPattern))\b"#).find(lower),
           let amount = Self.toServingAmount(match.groupValues[1]) {
            let unit = match.groupValues[2]
            return ServingDescriptor(amount: amount, unit: unit, label: Self.formatServingUnit(amount, unit))
        }

        if let match = LabelRegex(#"\b(\#(amountPattern))\s+(\#(unitPattern))\b"#).find(lower),
           let amount = Self.toServingAmount(match.groupValues[1]) {
            let unit = match.groupValues[2]
            return ServingDescriptor(amount: amount, unit: unit, label: Self.formatServingUnit(amount, unit))
        }

        if let match = LabelRegex(#"\bper\s+(cup|slice|item|bar|can|pack|bottle|pot|tub|serving|portion)\b"#).find(lower) {
            let unit = match.groupValues[1]
            return ServingDescriptor(amount: 1.0, unit: unit, label: unit)
        }

        if let match = LabelRegex(#"\b(\d+(?:[.,]\d+)?)\s+(fingers?|fillets?|sausages?)\s*=\s*1\s*serving\b"#).find(lower),
           let amount = labelToDoubleValue(match.groupValues[1]),
           let unit = Self.singularItemUnit(match.groupValues[2]) {
            return ServingDescriptor(amount: amount, unit: unit, label: Self.formatServingUnit(amount, unit))
        }

        return nil
    }

    private func extractServingSizeGrams(_ lower: String) -> Double? {
        let amountPattern = Self.servingAmountPattern
        let unitPattern = Self.servingUnitPattern
        let number = #"(\d+(?:[.,]\d+)?)"#

        // "1 serving (30g)" / "1 cup (255g)" / "per 1/2 can (196g)" / "per serving (30g)"
        if let value = LabelRegex(#"\b(?:per\s+)?(?:\#(amountPattern)\s+)?(?:\#(unitPattern))\s*\(\#(number)\s*g\)"#)
            .find(lower).flatMap({ labelToDoubleValue($0.groupValues[1]) }) {
            return value
        }
        // OCR can split the serving weight onto the next header line:
        // "per100g pert/2can" then "(as consumed) (196g) - (196g)".
        // (Kotlin interpolates the unit alternation ungrouped here; kept as-is.)
        if LabelRegex(#"\bper\s*\#(amountPattern)\s*\#(unitPattern)\b"#).containsMatch(lower) {
            if let value = LabelRegex(#"\(\#(number)\s*g\)"#)
                .find(lower).flatMap({ labelToDoubleValue($0.groupValues[1]) }) {
                return value
            }
        }
        // "serving size: 30g" / "serving size 30g"
        if let value = LabelRegex(#"\bserving size:?\s*\#(number)\s*g\b"#)
            .find(lower).flatMap({ labelToDoubleValue($0.groupValues[1]) }) {
            return value
        }
        // "per 30g serving" / "per 30 g serving"
        if let value = LabelRegex(#"\bper\s+\#(number)\s*g\s+(?:serving|portion)\b"#)
            .find(lower).flatMap({ labelToDoubleValue($0.groupValues[1]) }) {
            return value
        }
        // "per serving 30g" / "per slice approx. 9g" / "per slice (approx. 9g)"
        if let value = LabelRegex(#"\bper\s+(?:\#(unitPattern))\s*\(?\s*(?:approx\.?\s*)?\#(number)\s*g\)?"#)
            .find(lower).flatMap({ labelToDoubleValue($0.groupValues[1]) }) {
            return value
        }
        // "per serving 30g"
        if let value = LabelRegex(#"\bper\s+serving\s+\#(number)\s*g\b"#)
            .find(lower).flatMap({ labelToDoubleValue($0.groupValues[1]) }) {
            return value
        }
        return nil
    }

    private func extractPer100gNutrient(_ lower: String, _ label: String) -> Double? {
        guard let line = labelLines(lower).first(where: { labelTrimStart($0).hasPrefix(label) }) else { return nil }
        return LabelRegex(#"(\d+(?:[.,]\d+)?)\s*g"#).find(line).flatMap { labelToDoubleValue($0.groupValues[1]) }
    }

    // MARK: - Package quantity

    private struct PackageQuantity {
        var grams: Double?
        var milliliters: Double?
        var itemCount: Double?
        var itemUnit: String?
    }

    private func parsePackageQuantity(_ lower: String) -> PackageQuantity {
        let lines = labelLines(lower)
        let nutritionStart = lines.firstIndex(where: { isNutritionSectionStart($0) }) ?? lines.count
        let packageLines = lines.enumerated()
            .filter { index, line in index < nutritionStart || hasExplicitPackageQuantityCue(line) }
            .map { $0.element }

        let multiplierPattern = #"(\d+(?:[.,]\d+)?)\s*[x×*]\s*(\d+(?:[.,]\d+)?)\s*(kg|g|ml|l)\b\s*(bags?|packs?|bars?|bottles?|pots?|cans?)?"#
        if let multiplier = LabelRegex(multiplierPattern).find(packageLines.joined(separator: "\n")) {
            let count = labelToDoubleValue(multiplier.groupValues[1])
            let itemAmount = labelToDoubleValue(multiplier.groupValues[2])
            let unit = multiplier.groupValues[3]
            let grams = itemAmount.flatMap { Self.toGrams($0, unit) }
            let milliliters = itemAmount.flatMap { Self.toMilliliters($0, unit) }
            let itemUnit = Self.singularItemUnit(multiplier.groupValues[4])
            return PackageQuantity(
                grams: count != nil && grams != nil ? count! * grams! : nil,
                milliliters: count != nil && milliliters != nil ? count! * milliliters! : nil,
                itemCount: count,
                itemUnit: itemUnit
            )
        }

        let packageItemCount = extractPackageItemCount(packageLines)
        let per100g = Self.per100g
        let per100ml = Self.per100ml
        var grams: Double?
        for line in packageLines {
            if per100g.containsMatch(line) || (isNutritionTableLine(line) && !hasExplicitPackageQuantityCue(line)) {
                continue
            }
            if let value = extractPackageWeightGrams(line) {
                grams = value
                break
            }
        }
        var milliliters: Double?
        for line in packageLines {
            if per100ml.containsMatch(line) || (isNutritionTableLine(line) && !hasExplicitPackageQuantityCue(line)) {
                continue
            }
            if let value = extractPackageVolumeMilliliters(line) {
                milliliters = value
                break
            }
        }
        return PackageQuantity(
            grams: grams,
            milliliters: milliliters,
            itemCount: packageItemCount?.0,
            itemUnit: packageItemCount?.1
        )
    }

    private func extractPackageItemCount(_ lines: [String]) -> (Double, String?)? {
        let pattern = LabelRegex(#"\bcontains\s+(\d+(?:[.,]\d+)?)\s*(bags?|packs?|bars?|bottles?|pots?|cans?)\b"#)
        for line in lines {
            if isNutritionTableLine(line) { continue }
            if let match = pattern.find(line), let count = labelToDoubleValue(match.groupValues[1]) {
                return (count, Self.singularItemUnit(match.groupValues[2]))
            }
        }
        return nil
    }

    private func extractPackageWeightGrams(_ line: String) -> Double? {
        if let match = LabelRegex(#"\b(?:net\s*(?:weight|wt)?|weight|contents?)[:\s]*(\d+(?:[.,]\d+)?)\s*(kg|g)\b"#).find(line) {
            return labelToDoubleValue(match.groupValues[1]).flatMap { Self.toGrams($0, match.groupValues[2]) }
        }
        if let match = LabelRegex(#"^\s*(\d{2,4}(?:[.,]\d+)?)\s*(kg|g)\s*e\s*$"#).find(line) {
            return labelToDoubleValue(match.groupValues[1]).flatMap { Self.toGrams($0, match.groupValues[2]) }
        }
        if let match = LabelRegex(#"^\s*(\d{2,4}(?:[.,]\d+)?)\s*(kg|g)\s*(?:pack|pouch|bag|bottle|pot|tub|can|tray|box)\b"#).find(line) {
            return labelToDoubleValue(match.groupValues[1]).flatMap { Self.toGrams($0, match.groupValues[2]) }
        }
        return nil
    }

    private func extractPackageVolumeMilliliters(_ line: String) -> Double? {
        if let match = LabelRegex(#"\b(?:net\s*(?:volume|vol)?|volume|contents?)[:\s]*(\d+(?:[.,]\d+)?)\s*(ml|l)\b"#).find(line) {
            return labelToDoubleValue(match.groupValues[1]).flatMap { Self.toMilliliters($0, match.groupValues[2]) }
        }
        if let match = LabelRegex(#"\b(\d{2,4}(?:[.,]\d+)?)\s*(ml|l)\s*(?:bottle|carton|can|pot|tub)\b"#).find(line) {
            return labelToDoubleValue(match.groupValues[1]).flatMap { Self.toMilliliters($0, match.groupValues[2]) }
        }
        if let match = LabelRegex(#"^\s*(\d{2,4}(?:[.,]\d+)?)\s*(ml|l)\s*e?\s*$"#).find(line) {
            return labelToDoubleValue(match.groupValues[1]).flatMap { Self.toMilliliters($0, match.groupValues[2]) }
        }
        return nil
    }

    private func isNutritionTableLine(_ line: String) -> Bool {
        let trimmed = labelTrim(line)
        let nutrientLinePattern = LabelRegex(
            #"^(?:of\s+)?(?:which\s+)?(?:saturates?|salt|sugars?|fat|protein|fibre|fiber|carbohydrate|carbs|energy)\b"#
        )
        return nutrientLinePattern.containsMatch(trimmed)
            || LabelRegex(#"^\d+(?:[.,]\d+)?\s*(?:g|kcal|kj)\b"#).containsMatch(trimmed)
            || trimmed.contains("reference intake")
            || trimmed.contains("your ri")
    }

    private func isNutritionSectionStart(_ line: String) -> Bool {
        line.contains("nutrition")
            || line.contains("nutition")
            || line.contains("typical values")
            || line.contains("okal values")
            || Self.per100g.containsMatch(line)
            || Self.per100ml.containsMatch(line)
    }

    private func hasExplicitPackageQuantityCue(_ line: String) -> Bool {
        LabelRegex(#"\b(?:net|weight|contents?|pack|pouch|bag|bottle|pot|tub|can|tray|box|carton)\b"#).containsMatch(line)
    }

    // MARK: - Helpers

    private static func formatServingUnit(_ amount: Double, _ unit: String) -> String {
        func near(_ target: Double) -> Bool { abs(amount - target) < 1e-6 }
        let formattedAmount: String
        if near(0.5) {
            formattedAmount = "1/2"
        } else if near(1.0 / 3.0) {
            formattedAmount = "1/3"
        } else if near(2.0 / 3.0) {
            formattedAmount = "2/3"
        } else if near(0.25) {
            formattedAmount = "1/4"
        } else if near(0.75) {
            formattedAmount = "3/4"
        } else {
            formattedAmount = formatClean(amount)
        }
        return near(1.0) ? unit : "\(formattedAmount) \(unit)"
    }

    private static func toServingAmount(_ text: String) -> Double? {
        let stripped = labelTrim(text).replacingOccurrences(of: " ", with: "")
        let value = LabelRegex(#"^[ilt]/"#).replace(stripped, with: "1/")
        switch value {
        case "½": return 0.5
        case "⅓": return 1.0 / 3.0
        case "⅔": return 2.0 / 3.0
        case "¼": return 0.25
        case "¾": return 0.75
        default:
            if let fraction = LabelRegex.entire(#"(\d+(?:[.,]\d+)?)/(\d+(?:[.,]\d+)?)"#).find(value) {
                let numerator = labelToDoubleValue(fraction.groupValues[1])
                let denominator = labelToDoubleValue(fraction.groupValues[2])
                if let numerator, let denominator, denominator > 0.0 {
                    return numerator / denominator
                }
                return nil
            }
            return labelToDoubleValue(value)
        }
    }

    private static func normalizeOcrUnitCharacters(_ text: String) -> String {
        LabelRegex(#"\b(\d[\dOo]*)\s*(g|ml|k[ce]al)\b"#, ignoreCase: true).replace(text) { match in
            let digits = match.groupValues[1]
                .replacingOccurrences(of: "O", with: "0")
                .replacingOccurrences(of: "o", with: "0")
            return digits + match.groupValues[2]
        }
    }

    private static func toGrams(_ value: Double, _ unit: String) -> Double? {
        switch unit {
        case "kg": return value * 1000.0
        case "g": return value
        default: return nil
        }
    }

    private static func toMilliliters(_ value: Double, _ unit: String) -> Double? {
        switch unit {
        case "l": return value * 1000.0
        case "ml": return value
        default: return nil
        }
    }

    private static func singularItemUnit(_ text: String) -> String? {
        var trimmed = labelTrim(text)
        if trimmed.hasSuffix("s") { trimmed.removeLast() }
        return labelTrim(trimmed).isEmpty ? nil : trimmed
    }

    private static func formatClean(_ value: Double) -> String {
        if labelIsWhole(value) { return labelIntegerString(value) }
        return labelTrimEnd(labelTrimEnd(labelDoubleString(value), "0"), ".")
    }
}
