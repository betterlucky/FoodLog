import FoodLogCore
import SwiftUI

@main
struct FoodLogApp: App {
    @State private var model: AppModel?
    @State private var startupError: String?
    @State private var lastActiveFoodDay: LocalDate?
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            Group {
                if let model {
                    TodayView(model: model)
                } else if let startupError {
                    ContentUnavailableView("FoodLog couldn't open its data", systemImage: "exclamationmark.triangle",
                                           description: Text(startupError))
                } else {
                    ProgressView()
                }
            }
            .task {
                guard model == nil, startupError == nil else { return }
                do {
                    model = try AppModel.live()
                    #if DEBUG
                    // UI tests run the Log food intent's real perform() to check it reaches the open app.
                    if let index = CommandLine.arguments.firstIndex(of: "-uiTestIntentText"),
                       index + 1 < CommandLine.arguments.count {
                        var intent = LogFoodIntent()
                        intent.text = CommandLine.arguments[index + 1]
                        _ = try await intent.perform()
                    }
                    #endif
                } catch {
                    startupError = String(describing: error)
                }
            }
            .onChange(of: scenePhase) { _, phase in
                guard let model else { return }
                switch phase {
                case .active: model.appBecameActive(previousFoodDay: lastActiveFoodDay)
                case .background: lastActiveFoodDay = try? model.store.currentFoodDate()
                default: break
                }
            }
        }
    }
}
