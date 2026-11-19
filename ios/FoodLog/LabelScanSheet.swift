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
    @State private var image: UIImage?
    @State private var aiProvenance: AIProvenance?
    @State private var readingWithAI = false
    @Environment(\.dismiss) private var dismiss

    enum Stage: Equatable { case choose, reading, review, failed(String) }

    var body: some View {
        NavigationStack {
            Group {
                switch stage {
                case .choose: chooser
                case .reading: ProgressView(readingWithAI ? "Reading label with AI…" : "Reading label…")
                case .review: review
                case .failed(let message):
                    ContentUnavailableView {
                        Label("Couldn't read that label", systemImage: "text.viewfinder")
                    } description: {
                        Text(message)
                    } actions: {
                        if model.aiAvailable, image != nil, aiProvenance == nil {
                            Button("Read with AI") { readWithAI() }.buttonStyle(.borderedProminent)
                        }
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

    /// On-device text recognition first: free, offline and private. AI is offered when it fails.
    private func read(_ image: UIImage) {
        self.image = image
        aiProvenance = nil
        readingWithAI = false
        stage = .reading
        Task {
            do {
                let text = try await LabelTextReader.read(image)
                let parsed = LabelNutritionParser().parse(text)
                guard parsed.hasRequiredCalories else {
                    facts = parsed
                    stage = .failed("No calories were found. Try a sharper photo of the table"
                                    + (model.aiAvailable ? ", read it with AI," : "") + " or add it manually.")
                    return
                }
                present(parsed)
            } catch {
                stage = .failed(error.localizedDescription)
            }
        }
    }

    private func readWithAI() {
        guard let image, let aiImage = AIImage(image: image) else { return }
        readingWithAI = true
        stage = .reading
        Task {
            defer { readingWithAI = false }
            do {
                let response = try await model.readLabel(aiImage)
                let parsed = response.labelFacts
                aiProvenance = response.aiProvenance
                if name.trimmingCharacters(in: .whitespaces).isEmpty, let product = response.productName {
                    name = product
                }
                guard parsed.hasRequiredCalories else {
                    facts = parsed
                    stage = .failed("The AI couldn't read any calories either. Add it manually.")
                    return
                }
                present(parsed)
            } catch {
                stage = .failed(error.localizedDescription)
            }
        }
    }

    private func present(_ parsed: LabelNutritionFacts) {
        facts = parsed
        mode = LabelInputMode.fromStorage(model.settings.lastLabelInputMode)
        if mode == .items && parsed.kcalPerServing == nil && parsed.packageItemCount == nil { mode = .measure }
        amountText = mode == .measure ? "100" : "1"
        caloriesEdited = false
        stage = .review
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
                    ForEach(Nutrient.allCases) { nutrient in
                        if let value = facts[keyPath: nutrient.labelPer100] {
                            LabeledContent("\(nutrient.name) per 100", value: nutrient.format(value))
                        }
                    }
                    if facts.isPartial {
                        Text("Some details were unclear. Check the portion and calories.")
                            .font(.footnote).foregroundStyle(.orange)
                    }
                    if aiProvenance != nil {
                        Text("Read by AI. Check the numbers against the label.")
                            .font(.footnote).foregroundStyle(.secondary)
                    } else if model.aiAvailable, image != nil {
                        Button("Wrong numbers? Read with AI", systemImage: "sparkles") { readWithAI() }
                            .font(.footnote)
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
                    TextRow(label: "Amount", text: $amountText, numeric: true)
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
                    ForEach(Nutrient.allCases) { nutrient in
                        if let value = resolution?[keyPath: nutrient.labelPortion] {
                            LabeledContent(nutrient.name, value: nutrient.format(value))
                        }
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
            saveAsShortcut: saveAsShortcut, aiProvenance: aiProvenance)
        if model.logLabel(input) {
            var settings = model.settings
            settings.lastLabelInputMode = mode.storageValue
            try? model.store.saveSettings(settings)
            dismiss()
        }
    }
}
