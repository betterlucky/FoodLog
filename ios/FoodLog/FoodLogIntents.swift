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

// MARK: Intents

struct LogFoodIntent: AppIntent {
    static let title: LocalizedStringResource = "Log food"
    static let description = IntentDescription(
        "Logs what you ate, like “2 tea” or “toast and jam”. Anything FoodLog doesn't know is saved for you to add details later.")

    @Parameter(title: "Food", requestValueDialog: "What did you have?")
    var text: String

    static var parameterSummary: some ParameterSummary { Summary("Log \(\.$text)") }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let result = try FoodLogServices.shared().logging.quickLogSpoken(text)
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
        // "FoodLog" on its own asks "What did you have?"; the answer is tidied and parsed like typed text.
        AppShortcut(intent: LogFoodIntent(),
                    phrases: ["\(.applicationName)",
                              "Log food in \(.applicationName)",
                              "\(.applicationName) log"],
                    shortTitle: "Log food", systemImageName: "fork.knife")
        AppShortcut(intent: TodayTotalIntent(),
                    phrases: ["How much have I eaten in \(.applicationName)",
                              "\(.applicationName) total",
                              "What's my \(.applicationName) total"],
                    shortTitle: "Today's total", systemImageName: "chart.bar")
    }
}
