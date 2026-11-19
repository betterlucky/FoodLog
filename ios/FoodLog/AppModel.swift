import FoodLogCore
import Foundation
import Observation

/// Screen state for the app. Every value shown is re-read from the database after each change,
/// so the UI never becomes a second source of truth.
@MainActor
@Observable
final class AppModel {
    let store: FoodLogStore
    let logging: LoggingService

    private(set) var selectedDate: LocalDate
    private(set) var items: [FoodItem] = []
    private(set) var pending: [RawEntry] = []
    private(set) var totals = DayTotals.empty
    private(set) var weight: DailyWeight?
    private(set) var shortcuts: [Shortcut] = []
    private(set) var settings = AppSettings()
    private(set) var canImportAndroidData = false

    /// A short confirmation with an optional undo, shown after a one-tap log.
    var toast: Toast?
    var errorMessage: String?

    struct Toast: Identifiable, Equatable {
        let id = UUID()
        var message: String
        var undoFoodItemIds: [Int64] = []
    }

    init(store: FoodLogStore) {
        self.store = store
        self.logging = LoggingService(store: store)
        self.selectedDate = (try? store.currentFoodDate()) ?? store.clock.today
        refresh()
    }

    typealias ResolveRequest = ResolutionContext

    static func live() throws -> AppModel {
        #if DEBUG
        if CommandLine.arguments.contains("-uiTestInMemory") {
            let store = try FoodLogStore.inMemory()
            try store.seedDefaults()
            return AppModel(store: store)
        }
        #endif
        let documents = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask,
                                                    appropriateFor: nil, create: true)
        let store = try FoodLogStore(path: documents.appendingPathComponent("foodlog.sqlite").path)
        try store.seedDefaults()
        let model = AppModel(store: store)
        #if DEBUG
        if let index = CommandLine.arguments.firstIndex(of: "-openDate"), index + 1 < CommandLine.arguments.count,
           let date = LocalDate(iso: CommandLine.arguments[index + 1]) {
            model.select(date)
        }
        #endif
        return model
    }

    var isToday: Bool { selectedDate == ((try? store.currentFoodDate()) ?? store.clock.today) }

    func refresh() {
        perform {
            items = try store.foodItems(for: selectedDate)
            pending = try store.pendingEntries(for: selectedDate)
            totals = try store.dayTotals(for: selectedDate)
            weight = try store.dailyWeight(for: selectedDate)
            shortcuts = try store.activeShortcuts()
            settings = try store.settings()
            canImportAndroidData = try !store.hasUserData
        }
    }

    func select(_ date: LocalDate) {
        selectedDate = date
        refresh()
    }

    func moveDay(by days: Int) { select(selectedDate.plusDays(days)) }

    func goToToday() { select((try? store.currentFoodDate()) ?? store.clock.today) }

    /// Re-anchors on the current food day when the app returns to the foreground on a new day.
    func appBecameActive(previousFoodDay: LocalDate?) {
        let today = (try? store.currentFoodDate()) ?? store.clock.today
        if let previousFoodDay, previousFoodDay != today, selectedDate == previousFoodDay {
            selectedDate = today
        }
        refresh()
    }

    // MARK: Logging

    enum SubmitResult { case logged, saved, needsDetails(ResolutionContext), kept }

    func submit(_ text: String) -> SubmitResult {
        var result = SubmitResult.kept
        mutate {
            var outcome = try logging.submit(text, selectedDate: selectedDate)
            var movedTo: LocalDate?
            if case .dateMismatch(let requested) = outcome {
                // Follow the text's own date openly rather than logging to a day the user isn't viewing.
                selectedDate = requested
                movedTo = requested
                outcome = try logging.submit(text, selectedDate: requested)
            }
            let prefix = movedTo.map { "\(Format.day($0, today: (try? store.currentFoodDate()) ?? $0)): " } ?? ""
            switch outcome {
            case .logged(let ids, let summary):
                toast = Toast(message: "Logged \(prefix)\(summary)", undoFoodItemIds: ids)
                result = .logged
            case .needsDetails(let context):
                result = .needsDetails(context)
            case .nonFood:
                toast = Toast(message: "Saved as a note. FoodLog only logs food for now.")
                result = .saved
            case .dateMismatch, .invalid:
                result = .kept
            }
        }
        return result
    }

    func log(_ shortcut: Shortcut) {
        mutate {
            let logged = try logging.logShortcut(shortcut.lookupKey, on: selectedDate)
            toast = Toast(message: "Logged \(logged.draft.name) \(Format.kcal(logged.draft.calories))",
                          undoFoodItemIds: [logged.itemId])
        }
    }

    func undo(_ toast: Toast) {
        mutate { try logging.removeItems(toast.undoFoodItemIds) }
        self.toast = nil
    }

    func removeItem(_ id: Int64) { mutate { try logging.removeItems([id]) } }

    func removePending(_ id: Int64) { mutate { try store.removePendingEntry(id: id) } }

    func resolveRequest(forPending id: Int64) -> ResolutionContext? {
        var context: ResolutionContext?
        perform { context = try logging.context(forPending: id) }
        return context
    }

    func manualRequest() -> ResolutionContext { logging.manualContext(on: selectedDate) }

    func suggestions(for text: String) -> [FoodHistorySuggestion] {
        (try? logging.historySuggestions(for: text)) ?? []
    }

    func resolve(_ context: ResolutionContext, drafts: [FoodDraft?], time: LocalTime?, saveShortcuts: Set<Int>) -> Bool {
        mutate {
            let result = try logging.resolve(context, drafts: drafts, consumedTime: time, saveShortcuts: saveShortcuts)
            var message = "Logged \(result.itemIds.count == 1 ? "1 item" : "\(result.itemIds.count) items")"
            if !result.savedShortcuts.isEmpty { message += " · saved shortcut" }
            if !result.skippedShortcuts.isEmpty { message += " · kept existing shortcut" }
            toast = Toast(message: message)
        }
    }

    func shortcutCandidate(for item: FoodItem) -> Shortcut? { try? logging.shortcutCandidate(for: item) }

    func update(_ item: FoodItem, with draft: FoodDraft, time: LocalTime?, updateShortcut: Bool) -> Bool {
        mutate { try logging.update(itemId: item.id, with: draft, consumedTime: time, updateShortcut: updateShortcut) }
    }

    func logLabel(_ input: LabelLogInput) -> Bool {
        mutate {
            let result = try logging.logLabel(input)
            toast = Toast(message: "Logged \(input.name) \(Format.kcal(input.calories))"
                          + (result.savedShortcut != nil ? " · saved shortcut" : ""),
                          undoFoodItemIds: [result.itemId])
        }
    }

    func saveShortcut(_ shortcut: Shortcut) -> Bool { mutate { try logging.saveShortcut(shortcut) } }

    func forgetShortcut(_ lookupKey: String) {
        mutate { try logging.forgetShortcut(lookupKey) }
        toast = Toast(message: "Shortcut forgotten. Logged items are kept.")
    }

    // MARK: Weight and settings

    func saveWeight(_ kilograms: Double, at time: LocalTime) {
        mutate { try store.upsertDailyWeight(logDate: selectedDate, weightKg: kilograms, measuredTime: time) }
    }

    func removeWeight() {
        mutate { try store.removeDailyWeight(logDate: selectedDate) }
    }

    func setDayBoundary(_ time: LocalTime?) {
        mutate {
            var updated = try store.settings()
            updated.dayBoundaryTime = time
            try store.saveSettings(updated)
        }
    }

    func setJournalIncludeWeight(_ include: Bool) {
        mutate {
            var updated = try store.settings()
            updated.journalIncludeWeight = include
            try store.saveSettings(updated)
        }
    }

    // MARK: Import and export

    /// An Android export dropped into the app's Documents/Import folder (Files app or the installer).
    var pendingImportFile: URL? {
        guard canImportAndroidData,
              let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first,
              let files = try? FileManager.default.contentsOfDirectory(
                at: documents.appendingPathComponent("Import"), includingPropertiesForKeys: nil)
        else { return nil }
        return files.filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent > $1.lastPathComponent }.first
    }

    @discardableResult
    func importAndroidExport(from url: URL) -> ImportSummary? {
        var summary: ImportSummary?
        perform {
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            summary = try store.importAndroidExport(Data(contentsOf: url))
        }
        if summary != nil { goToToday() }
        return summary
    }

    /// Writes the full-history journal CSV to a temporary file for the share sheet.
    func journalFile() -> URL? {
        var url: URL?
        perform {
            let csv = try store.buildJournalCsv(options: JournalExportOptions(includeWeight: settings.journalIncludeWeight))
            let file = FileManager.default.temporaryDirectory.appendingPathComponent("foodlog-journal.csv")
            try csv.write(to: file, atomically: true, encoding: .utf8)
            url = file
        }
        return url
    }

    // MARK: Helpers

    /// Runs a read, reporting any error.
    func perform(_ body: () throws -> Void) {
        do {
            try body()
        } catch {
            errorMessage = String(describing: error)
        }
    }

    /// Runs a change, then re-reads screen state from the database. Returns whether it succeeded.
    @discardableResult
    func mutate(_ body: () throws -> Void) -> Bool {
        var succeeded = false
        perform {
            try body()
            succeeded = true
        }
        refresh()
        return succeeded
    }
}
