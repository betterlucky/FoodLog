import AppIntents
import FoodLogCore
import Foundation

/// One store for the app and for intents run by Siri, Shortcuts or the Action Button
/// (which may launch the app in the background), so the database is never opened twice.
@MainActor
final class FoodLogServices {
    private static var cached: FoodLogServices?

    let store: FoodLogStore
    let logging: LoggingService

    private init(store: FoodLogStore) {
        self.store = store
        self.logging = LoggingService(store: store)
    }

    static func shared() throws -> FoodLogServices {
        if let cached { return cached }
        let store: FoodLogStore
        #if DEBUG
        if CommandLine.arguments.contains("-uiTestInMemory") {
            store = try FoodLogStore.inMemory()
            try store.seedDefaults()
            cached = FoodLogServices(store: store)
            return cached!
        }
        #endif
        let documents = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask,
                                                    appropriateFor: nil, create: true)
        store = try FoodLogStore(path: documents.appendingPathComponent("foodlog.sqlite").path)
        try store.seedDefaults()
        cached = FoodLogServices(store: store)
        return cached!
    }

    /// Tells an open Today screen to re-read after an intent changed data.
    static func dataChanged() {
        NotificationCenter.default.post(name: .foodLogDataChanged, object: nil)
    }
}

extension Notification.Name {
    static let foodLogDataChanged = Notification.Name("FoodLogDataChanged")
}

// MARK: Shortcut entity

struct ShortcutEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Food shortcut"
    static let defaultQuery = ShortcutEntityQuery()

    var id: String
    var name: String
    var calories: Double

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: "\(Int(calories.rounded())) kcal")
    }

    init(_ shortcut: Shortcut) {
        id = shortcut.lookupKey
        name = shortcut.name
        calories = shortcut.calories
    }
}

struct ShortcutEntityQuery: EntityStringQuery {
    @MainActor private func all() throws -> [ShortcutEntity] {
        try FoodLogServices.shared().store.activeShortcuts().map(ShortcutEntity.init)
    }

    @MainActor func entities(for identifiers: [String]) async throws -> [ShortcutEntity] {
        try all().filter { identifiers.contains($0.id) }
    }

    @MainActor func entities(matching string: String) async throws -> [ShortcutEntity] {
        let query = string.lowercased()
        return try all().filter { $0.name.lowercased().contains(query) || $0.id.contains(query) }
    }

    @MainActor func suggestedEntities() async throws -> [ShortcutEntity] { try all() }
}

// MARK: Intents

struct LogShortcutIntent: AppIntent {
    static let title: LocalizedStringResource = "Log a food shortcut"
    static let description = IntentDescription("Logs one of your FoodLog shortcuts without opening the app.")

    @Parameter(title: "Shortcut")
    var shortcut: ShortcutEntity

    @Parameter(title: "Portions")
    var portions: Double?

    static var parameterSummary: some ParameterSummary {
        Summary("Log \(\.$shortcut)") { \.$portions }
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let result = try FoodLogServices.shared().logging.quickLog(shortcutKey: shortcut.id, quantity: portions)
        FoodLogServices.dataChanged()
        return .result(dialog: "\(result.message)")
    }
}

struct LogFoodIntent: AppIntent {
    static let title: LocalizedStringResource = "Log food"
    static let description = IntentDescription(
        "Logs what you ate, like “2 tea” or “toast and jam”. Anything FoodLog doesn't know is saved for you to add details later.")

    @Parameter(title: "Food", requestValueDialog: "What did you have?")
    var text: String

    static var parameterSummary: some ParameterSummary { Summary("Log \(\.$text)") }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let result = try FoodLogServices.shared().logging.quickLog(text)
        FoodLogServices.dataChanged()
        return .result(dialog: "\(result.message)")
    }
}

struct TodayTotalIntent: AppIntent {
    static let title: LocalizedStringResource = "Today's food total"
    static let description = IntentDescription("Says today's calories, and protein and fibre where known.")

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        let summary = try FoodLogServices.shared().logging.todaySummary()
        return .result(value: summary, dialog: "\(summary)")
    }
}

struct FoodLogAppShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: LogShortcutIntent(),
                    phrases: ["Log \(\.$shortcut) in \(.applicationName)",
                              "Add \(\.$shortcut) to \(.applicationName)",
                              "\(.applicationName) \(\.$shortcut)"],
                    shortTitle: "Log shortcut", systemImageName: "bolt.fill")
        AppShortcut(intent: LogFoodIntent(),
                    phrases: ["Log food in \(.applicationName)",
                              "Add food to \(.applicationName)",
                              "Log in \(.applicationName)"],
                    shortTitle: "Log food", systemImageName: "fork.knife")
        AppShortcut(intent: TodayTotalIntent(),
                    phrases: ["How much have I eaten in \(.applicationName)",
                              "\(.applicationName) total",
                              "What's my \(.applicationName) total"],
                    shortTitle: "Today's total", systemImageName: "chart.bar")
    }
}
