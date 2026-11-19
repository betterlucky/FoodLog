import Foundation

/// Protein and fibre per kcal for a food. Logged calories stay authoritative: backfilled
/// nutrients are scaled onto each row's own calories, so amounts and portions never need re-reading.
public struct NutrientRatio: Hashable, Sendable {
    public var perKcal: [Nutrient: Double]

    public init(perKcal: [Nutrient: Double]) { self.perKcal = perKcal }

    /// From nutrients measured for a known number of kcal. Nil when there is nothing to scale by.
    public init?(values: [Nutrient: Double], forKcal kcal: Double?) {
        guard !values.isEmpty, let kcal else { return nil }
        if kcal < 1 {
            // Water, black coffee, diet drinks: only "none" is meaningful without calories to scale.
            guard values.values.allSatisfy({ $0 < 0.5 }) else { return nil }
            self.init(perKcal: values.mapValues { _ in 0 })
            return
        }
        self.init(perKcal: values.mapValues { $0 / kcal })
    }

    /// Values read from any source through a per-nutrient key path.
    public init?<Source>(_ source: Source, _ path: (Nutrient) -> KeyPath<Source, Double?>, forKcal kcal: Double?) {
        var values: [Nutrient: Double] = [:]
        for nutrient in Nutrient.allCases { values[nutrient] = source[keyPath: path(nutrient)] }
        self.init(values: values, forKcal: kcal)
    }

    public init?(proteinG: Double?, fibreG: Double?, forKcal kcal: Double?) {
        var values: [Nutrient: Double] = [:]
        values[.protein] = proteinG
        values[.fibre] = fibreG
        self.init(values: values, forKcal: kcal)
    }

    public init(proteinPerKcal: Double?, fibrePerKcal: Double?) {
        var perKcal: [Nutrient: Double] = [:]
        perKcal[.protein] = proteinPerKcal
        perKcal[.fibre] = fibrePerKcal
        self.init(perKcal: perKcal)
    }

    public var proteinPerKcal: Double? { perKcal[.protein] }
    public var fibrePerKcal: Double? { perKcal[.fibre] }

    static func rounded(_ value: Double) -> Double { (value * 10).rounded() / 10 }
}

/// A food the AI is asked about once, covering every row (and shortcut) that shares it.
public struct BackfillTarget: Hashable, Sendable, Identifiable {
    public var id: String
    /// What is sent to the estimate service: name, portion and the logged calories.
    public var description: String
    public var itemIds: [Int64]
    public var shortcutKey: String?
    /// Each row's name when planned, so a row edited into a different food meanwhile is skipped.
    public var itemNames: [Int64: String] = [:]
}

public struct BackfillPlan: Hashable, Sendable {
    /// Rows filled from products and shortcuts without any network call.
    public var filledLocally: Int
    public var targets: [BackfillTarget]

    public static let empty = BackfillPlan(filledLocally: 0, targets: [])
}

extension LoggingService {
    /// Rows still missing protein or fibre, for the settings screen.
    public func rowsMissingNutrients() throws -> Int {
        try store.allActiveFoodItems().filter(\.missingNutrients).count
    }

    /// Fills what products and shortcuts already know, then lists the foods that still need an estimate.
    /// Never overwrites a value that is present.
    public func backfillNutrientsLocally() throws -> BackfillPlan {
        try connection.transaction {
            let shortcuts = try store.activeShortcuts()
            let byKey = Dictionary(shortcuts.map { ($0.lookupKey, $0) }, uniquingKeysWith: { first, _ in first })
            let byName = Dictionary(shortcuts.map { ($0.name.lowercased(), $0) }, uniquingKeysWith: { first, _ in first })
            var products: [Int64: Product?] = [:]
            var filled = 0
            var groups: [String: BackfillTarget] = [:]
            var order: [String] = []

            for original in try store.allActiveFoodItems() where original.missingNutrients {
                var item = original
                // Label data first, then the shortcut; whatever is still missing goes to the AI.
                if let productId = item.productId {
                    if products[productId] == nil { products[productId] = try store.product(id: productId) }
                    if let product = products[productId] ?? nil, let ratio = product.nutrientRatio {
                        item = try fill(item, with: ratio, source: .label)
                    }
                }
                let shortcut = item.shortcutKey.flatMap { byKey[$0] } ?? byName[item.name.lowercased()]
                if item.missingNutrients, let shortcut, let ratio = shortcut.nutrientRatio {
                    item = try fill(item, with: ratio, source: .shortcut)
                }
                if item != original { filled += 1 }
                guard item.missingNutrients else { continue }
                let key = shortcut.map { "shortcut:\($0.lookupKey)" } ?? "name:\(item.name.lowercased())"
                if groups[key] == nil {
                    order.append(key)
                    groups[key] = BackfillTarget(
                        id: key, description: shortcut?.backfillDescription ?? item.backfillDescription,
                        itemIds: [], shortcutKey: shortcut?.lookupKey)
                }
                groups[key]?.itemIds.append(item.id)
                groups[key]?.itemNames[item.id] = item.name
            }
            // Shortcuts missing a nutrient get it too, so future one-tap logs carry it.
            for shortcut in shortcuts where shortcut.isMissingNutrientData && groups["shortcut:\(shortcut.lookupKey)"] == nil {
                let key = "shortcut:\(shortcut.lookupKey)"
                order.append(key)
                groups[key] = BackfillTarget(id: key, description: shortcut.backfillDescription, itemIds: [],
                                             shortcutKey: shortcut.lookupKey)
            }
            return BackfillPlan(filledLocally: filled, targets: order.compactMap { groups[$0] })
        }
    }

    /// Applies an estimate (protein and fibre for `kcal`) to a target's rows and, when it has none,
    /// its shortcut. Returns the number of rows changed.
    @discardableResult
    public func applyNutrientEstimate(_ target: BackfillTarget, proteinG: Double?, fibreG: Double?,
                                      forKcal kcal: Double) throws -> Int {
        var values: [Nutrient: Double] = [:]
        values[.protein] = proteinG
        values[.fibre] = fibreG
        return try applyNutrientEstimate(target, values: values, forKcal: kcal)
    }

    @discardableResult
    public func applyNutrientEstimate(_ target: BackfillTarget, values: [Nutrient: Double],
                                      forKcal kcal: Double) throws -> Int {
        guard let ratio = NutrientRatio(values: values, forKcal: kcal) else { return 0 }
        return try connection.transaction {
            var changed = 0
            for id in target.itemIds {
                guard let item = try store.foodItem(id: id), !item.voided else { continue }
                // Skip a row that was edited into a different food while the estimate was fetched.
                if let planned = target.itemNames[id], planned.lowercased() != item.name.lowercased() { continue }
                if try fill(item, with: ratio, source: .estimate) != item { changed += 1 }
            }
            if let key = target.shortcutKey, var shortcut = try store.shortcut(lookupKey: key) {
                let per100 = ShortcutResolver.toLabelFacts(shortcut).flatMap { $0.kcalPer100g ?? $0.kcalPer100ml }
                var updated = false
                // Only nutrients the shortcut has no value for; known values and their basis are kept.
                for (nutrient, perKcal) in ratio.perKcal where shortcut.lacks(nutrient) {
                    if let per100 {
                        shortcut[keyPath: nutrient.shortcutPer100] = NutrientRatio.rounded(perKcal * per100)
                    } else {
                        shortcut[keyPath: nutrient.shortcutPerPortion] = NutrientRatio.rounded(perKcal * shortcut.calories)
                    }
                    updated = true
                }
                if updated { try store.upsertShortcut(shortcut) }
            }
            return changed
        }
    }

    /// Fills only the missing values, scaled to the row's current calories. Returns the row as saved.
    private func fill(_ item: FoodItem, with ratio: NutrientRatio, source: NutrientSource) throws -> FoodItem {
        var updated = item
        for (nutrient, perKcal) in ratio.perKcal where updated[nutrient] == nil {
            updated[nutrient] = NutrientRatio.rounded(perKcal * item.calories)
        }
        guard updated != item else { return item }
        if !item.hasAnyNutrient { updated.nutrientSource = source }
        try store.updateFoodItem(updated)
        return updated
    }
}

extension FoodItem {
    var missingNutrients: Bool { isMissingAnyNutrient }

    var backfillDescription: String {
        let quantity = [amount.map(ShortcutResolver.formatPlainNumber), unit].compactMap { $0 }.joined(separator: " ")
        return [quantity.isEmpty ? name : "\(quantity) \(name)", "about \(Int(calories.rounded())) kcal",
                notes.flatMap { $0.count <= 80 ? $0 : nil }]
            .compactMap { $0 }.joined(separator: ", ")
    }
}

extension Shortcut {
    func lacks(_ nutrient: Nutrient) -> Bool {
        self[keyPath: nutrient.shortcutPerPortion] == nil && self[keyPath: nutrient.shortcutPer100] == nil
    }

    var isMissingNutrientData: Bool { Nutrient.allCases.contains(where: lacks) }

    var nutrientRatio: NutrientRatio? {
        if Nutrient.allCases.contains(where: { self[keyPath: $0.shortcutPerPortion] != nil }) {
            return NutrientRatio(self, \.shortcutPerPortion, forKcal: calories)
        }
        let per100 = ShortcutResolver.toLabelFacts(self).flatMap { $0.kcalPer100g ?? $0.kcalPer100ml }
        return NutrientRatio(self, \.shortcutPer100, forKcal: per100)
    }

    /// The portion a one-tap log of this shortcut produces (item and measure shortcuts store
    /// calories per unit, so the resolved default portion is described, not the stored figure).
    var backfillDescription: String {
        let resolved = ShortcutResolver.resolve(self, part: ParsedFoodPart(
            normalizedFoodText: lookupKey, shortcutTrigger: lookupKey, quantity: 1, quantityUnit: nil))
        let quantity = [resolved.amount.map(ShortcutResolver.formatPlainNumber), resolved.unit]
            .compactMap { $0 }.joined(separator: " ")
        return "\(quantity.isEmpty ? "" : quantity + " ")\(name), about \(Int(resolved.calories.rounded())) kcal"
    }
}

extension Product {
    var nutrientRatio: NutrientRatio? {
        NutrientRatio(self, \.productPer100, forKcal: kcalPer100g ?? kcalPer100ml)
    }
}
