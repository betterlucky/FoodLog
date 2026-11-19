import Foundation

/// Protein and fibre per kcal for a food. Logged calories stay authoritative: backfilled
/// nutrients are scaled onto each row's own calories, so amounts and portions never need re-reading.
public struct NutrientRatio: Hashable, Sendable {
    public var proteinPerKcal: Double?
    public var fibrePerKcal: Double?

    public init(proteinPerKcal: Double?, fibrePerKcal: Double?) {
        self.proteinPerKcal = proteinPerKcal
        self.fibrePerKcal = fibrePerKcal
    }

    /// From nutrients measured for a known number of kcal. Nil when there is nothing to scale by.
    public init?(proteinG: Double?, fibreG: Double?, forKcal kcal: Double?) {
        guard proteinG != nil || fibreG != nil, let kcal else { return nil }
        if kcal < 1 {
            // Water, black coffee, diet drinks: only "none" is meaningful without calories to scale.
            guard (proteinG ?? 0) < 0.5, (fibreG ?? 0) < 0.5 else { return nil }
            self.init(proteinPerKcal: proteinG.map { _ in 0 }, fibrePerKcal: fibreG.map { _ in 0 })
            return
        }
        self.init(proteinPerKcal: proteinG.map { $0 / kcal }, fibrePerKcal: fibreG.map { $0 / kcal })
    }

    static func rounded(_ value: Double) -> Double { (value * 10).rounded() / 10 }
}

/// A food the AI is asked about once, covering every row (and shortcut) that shares it.
public struct BackfillTarget: Hashable, Sendable, Identifiable {
    public var id: String
    /// What is sent to the estimate service: name, portion and the logged calories.
    public var description: String
    public var itemIds: [Int64]
    public var shortcutKey: String?
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

            for item in try store.allActiveFoodItems() where item.missingNutrients {
                if let productId = item.productId {
                    if products[productId] == nil { products[productId] = try store.product(id: productId) }
                    if let product = products[productId] ?? nil, let ratio = product.nutrientRatio,
                       try fill(item, with: ratio, source: .label) {
                        filled += 1
                        continue
                    }
                }
                let shortcut = item.shortcutKey.flatMap { byKey[$0] } ?? byName[item.name.lowercased()]
                if let shortcut, let ratio = shortcut.nutrientRatio, try fill(item, with: ratio, source: .shortcut) {
                    filled += 1
                    continue
                }
                let key = shortcut.map { "shortcut:\($0.lookupKey)" } ?? "name:\(item.name.lowercased())"
                if groups[key] == nil {
                    order.append(key)
                    groups[key] = BackfillTarget(
                        id: key, description: shortcut?.backfillDescription ?? item.backfillDescription,
                        itemIds: [], shortcutKey: shortcut?.lookupKey)
                }
                groups[key]?.itemIds.append(item.id)
            }
            // Shortcuts without nutrients get them too, so future one-tap logs carry protein and fibre.
            for shortcut in shortcuts where shortcut.nutrientRatio == nil && groups["shortcut:\(shortcut.lookupKey)"] == nil {
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
        guard let ratio = NutrientRatio(proteinG: proteinG, fibreG: fibreG, forKcal: kcal) else { return 0 }
        return try connection.transaction {
            var changed = 0
            for id in target.itemIds {
                if let item = try store.foodItem(id: id), !item.voided, try fill(item, with: ratio, source: .estimate) {
                    changed += 1
                }
            }
            if let key = target.shortcutKey, var shortcut = try store.shortcut(lookupKey: key),
               shortcut.proteinPer100 == nil, shortcut.fibrePer100 == nil,
               shortcut.proteinPerPortion == nil, shortcut.fibrePerPortion == nil {
                if let per100 = ShortcutResolver.toLabelFacts(shortcut).flatMap({ $0.kcalPer100g ?? $0.kcalPer100ml }) {
                    shortcut.proteinPer100 = ratio.proteinPerKcal.map { NutrientRatio.rounded($0 * per100) }
                    shortcut.fibrePer100 = ratio.fibrePerKcal.map { NutrientRatio.rounded($0 * per100) }
                } else {
                    shortcut.proteinPerPortion = ratio.proteinPerKcal.map { NutrientRatio.rounded($0 * shortcut.calories) }
                    shortcut.fibrePerPortion = ratio.fibrePerKcal.map { NutrientRatio.rounded($0 * shortcut.calories) }
                }
                try store.upsertShortcut(shortcut)
            }
            return changed
        }
    }

    /// Fills only the missing values. Returns whether anything changed.
    private func fill(_ item: FoodItem, with ratio: NutrientRatio, source: NutrientSource) throws -> Bool {
        var updated = item
        if updated.proteinG == nil, let perKcal = ratio.proteinPerKcal {
            updated.proteinG = NutrientRatio.rounded(perKcal * item.calories)
        }
        if updated.fibreG == nil, let perKcal = ratio.fibrePerKcal {
            updated.fibreG = NutrientRatio.rounded(perKcal * item.calories)
        }
        guard updated != item else { return false }
        if item.proteinG == nil && item.fibreG == nil { updated.nutrientSource = source }
        try store.updateFoodItem(updated)
        return true
    }
}

extension FoodItem {
    var missingNutrients: Bool { proteinG == nil || fibreG == nil }

    var backfillDescription: String {
        let quantity = [amount.map(ShortcutResolver.formatPlainNumber), unit].compactMap { $0 }.joined(separator: " ")
        return [quantity.isEmpty ? name : "\(quantity) \(name)", "about \(Int(calories.rounded())) kcal",
                notes.flatMap { $0.count <= 80 ? $0 : nil }]
            .compactMap { $0 }.joined(separator: ", ")
    }
}

extension Shortcut {
    var nutrientRatio: NutrientRatio? {
        if proteinPerPortion != nil || fibrePerPortion != nil {
            return NutrientRatio(proteinG: proteinPerPortion, fibreG: fibrePerPortion, forKcal: calories)
        }
        let per100 = ShortcutResolver.toLabelFacts(self).flatMap { $0.kcalPer100g ?? $0.kcalPer100ml }
        return NutrientRatio(proteinG: proteinPer100, fibreG: fibrePer100, forKcal: per100)
    }

    var backfillDescription: String {
        let amount = ShortcutResolver.formatPlainNumber(defaultAmount ?? 1)
        return "\(amount) \(unit) \(name), about \(Int(calories.rounded())) kcal"
    }
}

extension Product {
    var nutrientRatio: NutrientRatio? {
        NutrientRatio(proteinG: proteinPer100g, fibreG: fiberPer100g, forKcal: kcalPer100g ?? kcalPer100ml)
    }
}
