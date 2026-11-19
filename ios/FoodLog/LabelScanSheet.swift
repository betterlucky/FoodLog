import FoodLogCore
import PhotosUI
import SwiftUI

/// Photo → on-device text recognition → conservative label parse → confirm a portion.
/// Nothing is logged until the user taps Log, and a failed read creates no food row.
struct LabelScanSheet: View {
    let model: AppModel
    @State private var stage = Stage.choose
    @State private var showCamera = false
    @State private var photo: PhotosPickerItem?
    @State private var facts: LabelNutritionFacts?
    @State private var name = ""
    @State private var mode = LabelInputMode.items
    @State private var amountText = "1"
    @State private var caloriesText = ""
    @State private var caloriesEdited = false
    @State private var saveAsShortcut = true
    @State private var showText = false
    @Environment(\.dismiss) private var dismiss

    enum Stage: Equatable { case choose, reading, review, failed(String) }

    var body: some View {
        NavigationStack {
            Group {
                switch stage {
                case .choose: chooser
                case .reading: ProgressView("Reading label…")
                case .review: review
                case .failed(let message):
                    ContentUnavailableView {
                        Label("Couldn't read that label", systemImage: "text.viewfinder")
                    } description: {
                        Text(message)
                    } actions: {
                        Button("Try again") { stage = .choose }
                    }
                }
            }
            .navigationTitle("Scan label")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                if stage == .review {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Log", action: log).disabled(!canLog)
                    }
                }
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { image in read(image) }.ignoresSafeArea()
            }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                        read(image)
                    } else {
                        stage = .failed("That photo couldn't be opened.")
                    }
                }
            }
        }
    }

    private var chooser: some View {
        VStack(spacing: 16) {
            Text("Photograph the nutrition table, as flat and close as you can.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            if CameraPicker.isAvailable {
                Button { showCamera = true } label: {
                    Label("Take photo", systemImage: "camera").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }
            PhotosPicker(selection: $photo, matching: .images) {
                Label("Choose photo", systemImage: "photo").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
        .padding()
    }

    private func read(_ image: UIImage) {
        stage = .reading
        Task {
            do {
                let text = try await LabelTextReader.read(image)
                let parsed = LabelNutritionParser().parse(text)
                guard parsed.hasRequiredCalories else {
                    facts = parsed
                    stage = .failed("No calories were found. Try a sharper photo of the table, or add it manually.")
                    return
                }
                facts = parsed
                mode = LabelInputMode.fromStorage(model.settings.lastLabelInputMode)
                if mode == .items && parsed.kcalPerServing == nil && parsed.packageItemCount == nil { mode = .measure }
                amountText = mode == .measure ? "100" : "1"
                caloriesEdited = false
                stage = .review
            } catch {
                stage = .failed(error.localizedDescription)
            }
        }
    }

    private var resolution: LabelPortionResolution? {
        guard let facts else { return nil }
        let text = mode == .measure && Double(amountText) != nil
            ? amountText + (facts.kcalPer100ml != nil && facts.kcalPer100g == nil ? "ml" : "g")
            : amountText
        return LabelPortionResolver.resolve(facts, mode: mode, amountText: text)
    }

    private var calories: Double? {
        caloriesEdited ? Format.parseNumber(caloriesText) : resolution?.calories
    }

    private var canLog: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && (calories ?? 0) > 0 && resolution?.isValidAmount == true
    }

    @ViewBuilder private var review: some View {
        if let facts {
            Form {
                Section {
                    TextField("Product name", text: $name)
                }
                Section("From the label") {
                    if let value = facts.kcalPer100g { LabeledContent("Per 100 g", value: Format.kcal(value)) }
                    if let value = facts.kcalPer100ml { LabeledContent("Per 100 ml", value: Format.kcal(value)) }
                    if let value = facts.kcalPerServing {
                        LabeledContent("Per \(facts.servingUnit ?? "serving")", value: Format.kcal(value))
                    }
                    if let value = facts.proteinPer100g { LabeledContent("Protein per 100", value: Format.grams(value)) }
                    if let value = facts.fiberPer100g { LabeledContent("Fibre per 100", value: Format.grams(value)) }
                    if facts.isPartial {
                        Text("Some details were unclear. Check the portion and calories.")
                            .font(.footnote).foregroundStyle(.orange)
                    }
                }
                Section("Portion") {
                    Picker("Measure by", selection: $mode) {
                        Text(LabelPortionResolver.itemUnit(facts).capitalized).tag(LabelInputMode.items)
                        Text(facts.kcalPer100ml != nil && facts.kcalPer100g == nil ? "ml" : "Grams").tag(LabelInputMode.measure)
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: mode) { _, newMode in
                        amountText = newMode == .measure ? "100" : "1"
                        caloriesEdited = false
                    }
                    TextField("Amount", text: $amountText).keyboardType(.decimalPad)
                    HStack {
                        TextField("Calories", text: Binding(
                            get: { caloriesEdited ? caloriesText : (resolution?.calories).map(Format.number) ?? "" },
                            set: { caloriesText = $0; caloriesEdited = true }))
                            .keyboardType(.decimalPad)
                        Text("kcal").foregroundStyle(.secondary)
                    }
                    if resolution?.calories == nil && !caloriesEdited {
                        Text("The label doesn't say enough to work this portion out. Enter the calories.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if let protein = resolution?.proteinG {
                        LabeledContent("Protein", value: Format.grams(protein))
                    }
                    if let fibre = resolution?.fibreG {
                        LabeledContent("Fibre", value: Format.grams(fibre))
                    }
                }
                Section {
                    Toggle("Save as shortcut", isOn: $saveAsShortcut)
                }
                Section {
                    DisclosureGroup("Recognised text", isExpanded: $showText) {
                        Text(facts.rawText).font(.caption.monospaced()).textSelection(.enabled)
                    }
                }
            }
        }
    }

    private func log() {
        guard let facts, let resolution, let calories else { return }
        let input = LabelLogInput(
            name: name, facts: facts, inputMode: mode, resolution: resolution, calories: calories,
            logDate: model.selectedDate, consumedTime: LocalTime(date: Date()).truncatedToMinutes,
            saveAsShortcut: saveAsShortcut)
        if model.logLabel(input) {
            var settings = model.settings
            settings.lastLabelInputMode = mode.storageValue
            try? model.store.saveSettings(settings)
            dismiss()
        }
    }
}
