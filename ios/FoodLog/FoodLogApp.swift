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
