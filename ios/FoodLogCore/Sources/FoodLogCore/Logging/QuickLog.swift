import Foundation

/// What Siri, Shortcuts or the Action Button says back after logging without opening the app.
public struct QuickLogResult: Hashable, Sendable {
    public var message: String
    public var loggedItemIds: [Int64]
    /// True when the text was saved for later because FoodLog couldn't resolve it on its own.
    public var savedForDetails: Bool
}

extension LoggingService {
    /// Logs typed or spoken food on the current food day. Anything FoodLog can't resolve is kept as
    /// a "needs details" entry (log now, enrich later); nothing is estimated or guessed silently.
    public func quickLog(_ text: String) throws -> QuickLogResult {
        var day = try store.currentFoodDate()
        var outcome = try submit(text, selectedDate: day)
        if case .dateMismatch(let requested) = outcome {
            day = requested
            outcome = try submit(text, selectedDate: requested)
        }
        let today = try store.currentFoodDate()
        let dayPrefix = day == today ? "" : "for \(day == today.minusDays(1) ? "yesterday" : day.iso) "
        switch outcome {
        case .logged(let ids, let summary):
            return QuickLogResult(message: "Logged \(dayPrefix)\(summary). \(try totalLine(for: day))",
                                  loggedItemIds: ids, savedForDetails: false)
        case .needsDetails:
            let quoted = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return QuickLogResult(message: "Saved “\(quoted)”. Add the details in FoodLog when you can.",
                                  loggedItemIds: [], savedForDetails: true)
        case .nonFood:
            return QuickLogResult(message: "Saved as a note. FoodLog only logs food for now.",
                                  loggedItemIds: [], savedForDetails: false)
        case .dateMismatch, .invalid:
            return QuickLogResult(message: "There was nothing to log.", loggedItemIds: [], savedForDetails: false)
        }
    }

    /// Logs one of the user's shortcuts, optionally several portions of it.
    public func quickLog(shortcutKey: String, quantity: Double? = nil) throws -> QuickLogResult {
        if let quantity, quantity != 1 {
            return try quickLog("\(ShortcutResolver.formatPlainNumber(quantity)) \(shortcutKey)")
        }
        let day = try store.currentFoodDate()
        let logged = try logShortcut(shortcutKey, on: day)
        return QuickLogResult(
            message: "Logged \(logged.draft.name) \(Int(logged.draft.calories.rounded())) kcal. \(try totalLine(for: day))",
            loggedItemIds: [logged.itemId], savedForDetails: false)
    }

    /// "Today: 1,240 kcal, 52 g protein, 14 g fibre."
    public func todaySummary() throws -> String {
        try totalLine(for: store.currentFoodDate())
    }

    private func totalLine(for day: LocalDate) throws -> String {
        let totals = try store.dayTotals(for: day)
        let label = day == (try store.currentFoodDate()) ? "Today" : "That day"
        var parts = ["\(totals.calories.rounded().formatted(.number.grouping(.automatic))) kcal"]
        if let protein = totals.proteinG { parts.append("\(Int(protein.rounded())) g protein") }
        if let fibre = totals.fibreG { parts.append("\(Int(fibre.rounded())) g fibre") }
        return "\(label): \(parts.joined(separator: ", "))."
    }
}
