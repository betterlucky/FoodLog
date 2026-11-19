import Foundation

/// A food row the user is about to confirm.
public struct FoodDraft: Hashable, Sendable {
    public var name: String
    public var amount: Double?
    public var unit: String?
    public var grams: Double?
    public var milliliters: Double?
    public var calories: Double
    public var proteinG: Double?
    public var fibreG: Double?
    public var notes: String?
    public var shortcutKey: String?
    public var productId: Int64?
    public var source: FoodItemSource
    public var confidence: ConfidenceLevel
    public var nutrientSource: NutrientSource?
    public var aiProvenance: AIProvenance?

    public init(name: String = "", amount: Double? = nil, unit: String? = nil, grams: Double? = nil,
                milliliters: Double? = nil, calories: Double = 0, proteinG: Double? = nil, fibreG: Double? = nil,
                notes: String? = nil, shortcutKey: String? = nil, productId: Int64? = nil,
                source: FoodItemSource = .manualOverride, confidence: ConfidenceLevel = .high,
                nutrientSource: NutrientSource? = nil, aiProvenance: AIProvenance? = nil) {
        self.name = name
        self.amount = amount
        self.unit = unit
        self.grams = grams
        self.milliliters = milliliters
        self.calories = calories
        self.proteinG = proteinG
        self.fibreG = fibreG
        self.notes = notes
        self.shortcutKey = shortcutKey
        self.productId = productId
        self.source = source
        self.confidence = confidence
        self.nutrientSource = nutrientSource
        self.aiProvenance = aiProvenance
    }

    public init(item: FoodItem) {
        self.init(name: item.name, amount: item.amount, unit: item.unit, grams: item.grams,
                  milliliters: item.milliliters, calories: item.calories, proteinG: item.proteinG,
                  fibreG: item.fibreG, notes: item.notes, shortcutKey: item.shortcutKey, productId: item.productId,
                  source: item.source, confidence: item.confidence, nutrientSource: item.nutrientSource,
                  aiProvenance: item.aiProvenance)
    }

    /// Trimmed, with blank text as nil and non-positive amounts dropped.
    var normalized: FoodDraft {
        var copy = self
        copy.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        copy.unit = unit?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        copy.notes = notes?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        copy.amount = amount.flatMap { $0 > 0 ? $0 : nil }
        if copy.nutrientSource == nil, copy.proteinG != nil || copy.fibreG != nil { copy.nutrientSource = .manual }
        if copy.proteinG == nil, copy.fibreG == nil { copy.nutrientSource = nil }
        return copy
    }

    var isValid: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty && calories > 0 }

    init(resolved: ResolvedShortcutFood, shortcut: Shortcut) {
        self.init(name: resolved.name, amount: resolved.amount, unit: resolved.unit, grams: resolved.grams,
                  milliliters: resolved.milliliters, calories: resolved.calories, proteinG: resolved.proteinG,
                  fibreG: resolved.fibreG, notes: shortcut.notes, shortcutKey: shortcut.lookupKey,
                  source: shortcut.source, confidence: shortcut.confidence,
                  nutrientSource: resolved.proteinG != nil || resolved.fibreG != nil ? .shortcut : nil)
    }
}

/// One part of a submission (e.g. "tea" in "tea and toast") with what is known about it.
public struct PartDraft: Hashable, Sendable, Identifiable {
    public var id: Int
    public var inputText: String
    /// The key a new shortcut for this part would be saved under.
    public var lookupKey: String?
    public var shortcut: Shortcut?
    /// Prefilled from the shortcut, when there is one.
    public var draft: FoodDraft?
    public var historySuggestions: [FoodHistorySuggestion]

    public init(id: Int, inputText: String, lookupKey: String?, shortcut: Shortcut?, draft: FoodDraft?,
                historySuggestions: [FoodHistorySuggestion]) {
        self.id = id
        self.inputText = inputText
        self.lookupKey = lookupKey
        self.shortcut = shortcut
        self.draft = draft
        self.historySuggestions = historySuggestions
    }
}

/// Everything needed to review and confirm one submission.
public struct ResolutionContext: Hashable, Sendable, Identifiable {
    public var id: String
    /// The pending raw entry being resolved, or nil for a fresh manual entry.
    public var rawEntryId: Int64?
    public var rawText: String
    public var logDate: LocalDate
    public var consumedTime: LocalTime?
    public var parts: [PartDraft]
}

public enum SubmitOutcome: Equatable, Sendable {
    case logged(itemIds: [Int64], summary: String)
    case needsDetails(ResolutionContext)
    case nonFood(EntryIntent)
    case dateMismatch(requested: LocalDate)
    case invalid
}

public struct ResolveResult: Equatable, Sendable {
    public var itemIds: [Int64]
    public var savedShortcuts: [String]
    public var skippedShortcuts: [String]
}

/// A confirmed label log, built by the label review screen.
public struct LabelLogInput: Sendable {
    public var name: String
    public var facts: LabelNutritionFacts
    public var inputMode: LabelInputMode
    public var resolution: LabelPortionResolution
    public var calories: Double
    public var logDate: LocalDate
    public var consumedTime: LocalTime?
    public var notes: String?
    public var saveAsShortcut: Bool
    public var itemSize: LabelShortcutItemSize?
    /// Set when the label was read by the AI service rather than on-device OCR.
    public var aiProvenance: AIProvenance?

    public init(name: String, facts: LabelNutritionFacts, inputMode: LabelInputMode,
                resolution: LabelPortionResolution, calories: Double, logDate: LocalDate,
                consumedTime: LocalTime?, notes: String? = nil, saveAsShortcut: Bool,
                itemSize: LabelShortcutItemSize? = nil, aiProvenance: AIProvenance? = nil) {
        self.name = name
        self.facts = facts
        self.inputMode = inputMode
        self.resolution = resolution
        self.calories = calories
        self.logDate = logDate
        self.consumedTime = consumedTime
        self.notes = notes
        self.saveAsShortcut = saveAsShortcut
        self.itemSize = itemSize
        self.aiProvenance = aiProvenance
    }
}

/// The logging workflows, each in one database transaction. Port of FoodLogRepository's
/// submission, shortcut, pending-resolution, manual, edit and label flows.
public final class LoggingService {
    public let store: FoodLogStore
    private let parser = DeterministicParser()
    private let classifier = DeterministicIntentClassifier()

    public init(store: FoodLogStore) { self.store = store }

    private var clock: Clock { store.clock }
    var connection: SQLiteConnection { store.connection }

    // MARK: Text submission

    /// Saves the raw text first, then logs it at once if every part matches an active shortcut.
    /// Anything else stays a pending entry and comes back for review.
    public func submit(_ input: String, selectedDate: LocalDate) throws -> SubmitOutcome {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return .invalid }
        let intent = classifier.classify(text)
        let parsed = parser.parse(input: text, today: clock.today, defaultLogDate: selectedDate)
        guard parsed.logDate == selectedDate else { return .dateMismatch(requested: parsed.logDate) }
        let now = clock.now
        let consumedTime = parsed.consumedTime ?? clock.localTime

        return try connection.transaction {
            guard intent == .foodLog else {
                try store.insertRawEntry(RawEntry(
                    createdAt: now, logDate: parsed.logDate, consumedTime: consumedTime, rawText: text,
                    entryKind: intent.entryKind, status: intent.rawEntryStatus, notes: intent.placeholderNotes))
                return .nonFood(intent)
            }
            let rawEntryId = try store.insertRawEntry(RawEntry(
                createdAt: now, logDate: parsed.logDate, consumedTime: consumedTime, rawText: text,
                entryKind: .text, status: .pending))
            let matches = try parsed.parts.map { part in
                (part, try part.shortcutTrigger.flatMap { try store.activeShortcut(lookupKey: $0) })
            }
            guard !matches.isEmpty, matches.allSatisfy({ $0.1 != nil }) else {
                return .needsDetails(try context(forPending: rawEntryId))
            }
            var ids: [Int64] = []
            var names: [String] = []
            for (part, shortcut) in matches {
                let draft = FoodDraft(resolved: ShortcutResolver.resolve(shortcut!, part: part), shortcut: shortcut!)
                ids.append(try insert(draft, rawEntryId: rawEntryId, logDate: parsed.logDate,
                                      consumedTime: consumedTime, createdAt: now))
                names.append("\(draft.name) \(Int(draft.calories.rounded())) kcal")
            }
            try store.updateRawEntryStatus(id: rawEntryId, status: .parsed)
            return .logged(itemIds: ids, summary: names.joined(separator: ", "))
        }
    }

    /// One-tap shortcut logging.
    public func logShortcut(_ lookupKey: String, on logDate: LocalDate) throws -> (itemId: Int64, draft: FoodDraft) {
        let key = ShortcutResolver.shortcutTrigger(lookupKey)
        guard !key.isEmpty, let shortcut = try store.activeShortcut(lookupKey: key) else {
            throw FoodLogStoreError.notFound
        }
        let now = clock.now
        let time = clock.localTime
        return try connection.transaction {
            let rawEntryId = try store.insertRawEntry(RawEntry(
                createdAt: now, logDate: logDate, consumedTime: time, rawText: key, entryKind: .text, status: .parsed))
            let part = ParsedFoodPart(normalizedFoodText: key, shortcutTrigger: key)
            let draft = FoodDraft(resolved: ShortcutResolver.resolve(shortcut, part: part), shortcut: shortcut)
            let id = try insert(draft, rawEntryId: rawEntryId, logDate: logDate, consumedTime: time, createdAt: now)
            return (id, draft)
        }
    }

    // MARK: Review and resolution

    public func context(forPending rawEntryId: Int64) throws -> ResolutionContext {
        guard let entry = try store.rawEntry(id: rawEntryId), entry.status == .pending else {
            throw FoodLogStoreError.notFound
        }
        let parsed = parser.parse(input: entry.rawText, today: clock.today, defaultLogDate: entry.logDate)
        let parts = try parsed.parts.enumerated().map { index, part in try partDraft(part, index: index) }
        return ResolutionContext(
            id: "pending-\(rawEntryId)", rawEntryId: rawEntryId, rawText: entry.rawText, logDate: entry.logDate,
            consumedTime: entry.consumedTime, parts: parts.isEmpty ? [emptyPart(entry.rawText)] : parts)
    }

    /// A blank single-part form for "add manually".
    public func manualContext(on logDate: LocalDate) -> ResolutionContext {
        ResolutionContext(id: "manual-\(UUID().uuidString)", rawEntryId: nil, rawText: "", logDate: logDate,
                          consumedTime: clock.localTime, parts: [emptyPart("")])
    }

    /// Suggestions for a name the user is typing in a manual form.
    public func historySuggestions(for text: String) throws -> [FoodHistorySuggestion] {
        try store.historySuggestions(for: text)
    }

    /// Confirms a reviewed submission. `drafts` align with `context.parts`; nil skips a part.
    /// `saveShortcuts` holds the part ids to save as new shortcuts (existing active ones are kept).
    public func resolve(_ context: ResolutionContext, drafts: [FoodDraft?], consumedTime: LocalTime?,
                        saveShortcuts: Set<Int>) throws -> ResolveResult {
        let confirmed = zip(context.parts, drafts).compactMap { part, draft in draft.map { (part, $0.normalized) } }
        guard !confirmed.isEmpty, confirmed.allSatisfy({ $0.1.isValid }) else {
            throw FoodLogStoreError.invalidInput("Each item needs a name and calories.")
        }
        let now = clock.now
        return try connection.transaction {
            let rawEntryId: Int64
            if let pendingId = context.rawEntryId {
                guard let entry = try store.rawEntry(id: pendingId), entry.status == .pending else {
                    throw FoodLogStoreError.notFound
                }
                rawEntryId = pendingId
                try store.updatePendingRawEntry(id: pendingId, rawText: entry.rawText, logDate: entry.logDate,
                                                consumedTime: consumedTime)
            } else {
                rawEntryId = try store.insertRawEntry(RawEntry(
                    createdAt: now, logDate: context.logDate, consumedTime: consumedTime,
                    rawText: "Manual entry: " + confirmed.map(\.1.name).joined(separator: ", "),
                    entryKind: .text, status: .parsed, notes: "Created from manual add form."))
            }
            var result = ResolveResult(itemIds: [], savedShortcuts: [], skippedShortcuts: [])
            for (part, var draft) in confirmed {
                if saveShortcuts.contains(part.id) {
                    let key = part.lookupKey.flatMap { $0.isEmpty ? nil : $0 } ?? ShortcutResolver.shortcutTrigger(draft.name)
                    if try saveShortcutIfAbsent(plainShortcut(from: draft, lookupKey: key)) {
                        result.savedShortcuts.append(key)
                        draft.shortcutKey = key
                    } else {
                        result.skippedShortcuts.append(key)
                    }
                }
                result.itemIds.append(try insert(draft, rawEntryId: rawEntryId, logDate: context.logDate,
                                                 consumedTime: consumedTime, createdAt: now))
            }
            if context.rawEntryId != nil { try store.updateRawEntryStatus(id: rawEntryId, status: .parsed) }
            return result
        }
    }

    // MARK: Editing

    /// The active shortcut a logged row came from, if any: its link, or for imported rows the
    /// shortcut its raw text resolves to.
    public func shortcutCandidate(for item: FoodItem) throws -> Shortcut? {
        if let key = item.shortcutKey { return try store.activeShortcut(lookupKey: key) }
        guard let entry = try store.rawEntry(id: item.rawEntryId) else { return nil }
        let parsed = parser.parse(input: entry.rawText, today: clock.today, defaultLogDate: entry.logDate)
        guard parsed.parts.count == 1, let key = parsed.parts[0].shortcutTrigger, !key.isEmpty else { return nil }
        return try store.activeShortcut(lookupKey: key)
    }

    /// Saves an edited row. With `updateShortcut`, its shortcut learns this portion as the new normal.
    public func update(itemId: Int64, with edited: FoodDraft, consumedTime: LocalTime?, updateShortcut: Bool) throws {
        let draft = edited.normalized
        guard draft.isValid else { throw FoodLogStoreError.invalidInput("An item needs a name and calories.") }
        try connection.transaction {
            guard var item = try store.foodItem(id: itemId) else { throw FoodLogStoreError.notFound }
            let shortcut = updateShortcut ? try shortcutCandidate(for: item) : nil
            let portionChanged = item.amount != draft.amount || item.unit != draft.unit
            item.name = draft.name
            item.amount = draft.amount
            item.unit = draft.unit
            if portionChanged {
                // A changed portion invalidates stored weights unless the unit itself is g/ml.
                item.grams = draft.unit == "g" ? draft.amount : nil
                item.milliliters = draft.unit == "ml" ? draft.amount : nil
            }
            item.calories = draft.calories
            item.proteinG = draft.proteinG
            item.fibreG = draft.fibreG
            item.nutrientSource = draft.nutrientSource
            item.notes = draft.notes
            item.consumedTime = consumedTime
            if let shortcut {
                item.shortcutKey = shortcut.lookupKey
                try store.upsertShortcut(learned(shortcut, from: draft))
            }
            try store.updateFoodItem(item)
        }
    }

    public func removeItems(_ ids: [Int64]) throws {
        try connection.transaction { for id in ids { try store.removeFoodItem(id: id) } }
    }

    // MARK: Shortcuts

    /// Saves an edited shortcut. Renaming keeps the lookup key; name and calories are required.
    public func saveShortcut(_ shortcut: Shortcut) throws {
        var copy = shortcut
        copy.name = shortcut.name.trimmingCharacters(in: .whitespacesAndNewlines)
        copy.unit = shortcut.unit.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "serving"
        copy.notes = shortcut.notes?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        guard !copy.name.isEmpty, copy.calories > 0, !copy.lookupKey.isEmpty else {
            throw FoodLogStoreError.invalidInput("A shortcut needs a name and calories.")
        }
        try store.upsertShortcut(copy)
    }

    public func forgetShortcut(_ lookupKey: String) throws {
        try store.deactivateShortcut(lookupKey: lookupKey)
    }

    // MARK: Labels

    /// Logs a confirmed label portion, keeping the label's nutrients on the product, the row, and
    /// (optionally) a portion-first shortcut for next time.
    @discardableResult
    public func logLabel(_ input: LabelLogInput) throws -> (itemId: Int64, savedShortcut: String?) {
        let name = input.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, input.calories > 0 else {
            throw FoodLogStoreError.invalidInput("A label log needs a name and calories.")
        }
        let now = clock.now
        let facts = input.facts
        return try connection.transaction {
            let productId = try store.product(named: name)?.id ?? store.insertProduct(Product(
                name: name, packageSizeGrams: facts.packageSizeGrams,
                packageSizeMilliliters: facts.packageSizeMilliliters, packageItemCount: facts.packageItemCount,
                servingSizeGrams: facts.servingSizeGrams, servingUnit: facts.servingUnit,
                kcalPer100g: facts.kcalPer100g, kcalPer100ml: facts.kcalPer100ml, kcalPerServing: facts.kcalPerServing,
                proteinPer100g: facts.proteinPer100g, carbsPer100g: facts.carbsPer100g, fatPer100g: facts.fatPer100g,
                fiberPer100g: facts.fiberPer100g, sugarsPer100g: facts.sugarsPer100g, saltPer100g: facts.saltPer100g,
                source: .packagingPhoto, confidence: .high, lastLoggedGrams: input.resolution.grams, createdAt: now))

            var savedKey: String?
            if input.saveAsShortcut {
                let built = LabelShortcutBuilder.build(facts: facts, inputMode: input.inputMode,
                                                       resolvedPortion: input.resolution, calories: input.calories,
                                                       userProvidedItemSize: input.itemSize)
                let key = ShortcutResolver.shortcutTrigger(name)
                let shortcut = Shortcut(
                    lookupKey: key, name: name, calories: built.caloriesPerUnit, unit: built.unit.nilIfEmpty ?? "serving",
                    notes: input.notes?.nilIfEmpty, source: .savedLabel, confidence: .high,
                    defaultAmount: built.defaultAmount, portionMode: built.portionMode, itemUnit: built.itemUnit,
                    itemSizeAmount: built.itemSizeAmount.flatMap { $0 > 0 ? $0 : nil }, itemSizeUnit: built.itemSizeUnit,
                    kcalPer100g: built.kcalPer100g, kcalPer100ml: built.kcalPer100ml, nutritionBasisName: name,
                    proteinPer100: built.proteinPer100, fibrePer100: built.fibrePer100,
                    proteinPerPortion: built.proteinPerPortion, fibrePerPortion: built.fibrePerPortion)
                if !key.isEmpty, try saveShortcutIfAbsent(shortcut) { savedKey = key }
            }

            let rawEntryId = try store.insertRawEntry(RawEntry(
                createdAt: now, logDate: input.logDate, consumedTime: input.consumedTime ?? clock.localTime,
                rawText: "Label scan: \(name)", entryKind: .text, status: .parsed))
            let resolution = input.resolution
            let existingKey = try store.activeShortcut(lookupKey: ShortcutResolver.shortcutTrigger(name))?.lookupKey
            let draft = FoodDraft(
                name: name, amount: resolution.amount ?? resolution.grams ?? 1,
                unit: resolution.unit ?? (resolution.grams != nil ? "g" : (facts.servingUnit ?? "serving")),
                grams: resolution.grams, milliliters: resolution.milliliters, calories: input.calories,
                proteinG: resolution.proteinG, fibreG: resolution.fibreG, notes: input.notes?.nilIfEmpty,
                shortcutKey: savedKey ?? existingKey,
                productId: productId, source: .savedLabel, confidence: .high,
                nutrientSource: resolution.proteinG != nil || resolution.fibreG != nil ? .label : nil,
                aiProvenance: input.aiProvenance)
            let id = try insert(draft, rawEntryId: rawEntryId, logDate: input.logDate,
                                consumedTime: input.consumedTime ?? clock.localTime, createdAt: now)
            return (id, savedKey)
        }
    }

    // MARK: Helpers

    private func insert(_ draft: FoodDraft, rawEntryId: Int64, logDate: LocalDate, consumedTime: LocalTime?,
                        createdAt: Date) throws -> Int64 {
        try store.insertFoodItem(FoodItem(
            rawEntryId: rawEntryId, logDate: logDate, consumedTime: consumedTime, name: draft.name,
            productId: draft.productId, shortcutKey: draft.shortcutKey, amount: draft.amount, unit: draft.unit,
            grams: draft.grams ?? (draft.unit == "g" ? draft.amount : nil),
            milliliters: draft.milliliters ?? (draft.unit == "ml" ? draft.amount : nil),
            calories: draft.calories, proteinG: draft.proteinG, fibreG: draft.fibreG,
            nutrientSource: draft.nutrientSource, source: draft.source, confidence: draft.confidence,
            notes: draft.notes, aiProvenance: draft.aiProvenance, createdAt: createdAt))
    }

    private func partDraft(_ part: ParsedFoodPart, index: Int) throws -> PartDraft {
        let shortcut = try part.shortcutTrigger.flatMap { try store.activeShortcut(lookupKey: $0) }
        let draft = shortcut.map { FoodDraft(resolved: ShortcutResolver.resolve($0, part: part), shortcut: $0) }
        let suggestions = shortcut == nil
            ? try store.historySuggestions(for: part.shortcutTrigger ?? part.normalizedFoodText) : []
        return PartDraft(id: index, inputText: part.normalizedFoodText, lookupKey: part.shortcutTrigger,
                         shortcut: shortcut, draft: draft, historySuggestions: suggestions)
    }

    private func emptyPart(_ text: String) -> PartDraft {
        PartDraft(id: 0, inputText: text, lookupKey: text.isEmpty ? nil : ShortcutResolver.shortcutTrigger(text),
                  shortcut: nil, draft: nil, historySuggestions: [])
    }

    /// A portion-first PLAIN shortcut: the calories for exactly the portion the user logged.
    private func plainShortcut(from draft: FoodDraft, lookupKey: String) -> Shortcut {
        Shortcut(lookupKey: lookupKey, name: draft.name, calories: draft.calories, unit: draft.unit ?? "serving",
                 notes: draft.notes, defaultAmount: draft.amount, proteinPerPortion: draft.proteinG,
                 fibrePerPortion: draft.fibreG)
    }

    /// Never overwrites an active shortcut; a forgotten one with the same key is replaced.
    private func saveShortcutIfAbsent(_ shortcut: Shortcut) throws -> Bool {
        guard try store.activeShortcut(lookupKey: shortcut.lookupKey) == nil else { return false }
        try store.upsertShortcut(shortcut)
        return true
    }

    /// The shortcut updated so that logging it again reproduces `draft`.
    private func learned(_ shortcut: Shortcut, from draft: FoodDraft) -> Shortcut {
        var updated = shortcut
        updated.defaultAmount = draft.amount
        switch shortcut.portionMode {
        case .plain:
            updated.calories = draft.calories
            updated.unit = draft.unit ?? shortcut.unit
            updated.proteinPerPortion = draft.proteinG ?? shortcut.proteinPerPortion
            updated.fibrePerPortion = draft.fibreG ?? shortcut.fibrePerPortion
        case .item, .measure:
            updated.calories = ShortcutResolver.caloriesForUpdatedShortcut(shortcut, amount: draft.amount,
                                                                           calories: draft.calories)
            updated.kcalPer100g = ShortcutResolver.updatedKcalPer100g(shortcut, updatedCalories: updated.calories,
                                                                      unit: draft.unit)
            updated.kcalPer100ml = ShortcutResolver.updatedKcalPer100ml(shortcut, updatedCalories: updated.calories,
                                                                        unit: draft.unit)
        }
        return updated
    }
}

extension EntryIntent {
    var entryKind: EntryKind {
        switch self {
        case .query: return .query
        case .correction: return .correction
        case .exportCommand: return .exportCommand
        case .note: return .note
        case .unknown, .foodLog: return .text
        }
    }

    var rawEntryStatus: RawEntryStatus {
        switch self {
        case .correction, .unknown: return .needsReview
        case .query, .exportCommand, .note, .foodLog: return .parsed
        }
    }

    var placeholderNotes: String? {
        switch self {
        case .query: return "Conversational responses will be handled by a later phase."
        case .exportCommand: return "Daily export commands will be handled by a later phase."
        case .correction: return "Correction handling will be handled by a later phase."
        case .note: return "Notes will be handled by a later phase."
        case .unknown: return "Input was not confidently classified."
        case .foodLog: return nil
        }
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
