import FoodLogCore
import SwiftUI
import UniformTypeIdentifiers

struct EditFoodSheet: View {
    let model: AppModel
    let item: FoodItem
    @State private var form: PartForm
    @State private var time: Date
    @State private var hasTime: Bool
    @State private var updateShortcut = false
    @State private var shortcut: Shortcut?
    @Environment(\.dismiss) private var dismiss

    init(model: AppModel, item: FoodItem) {
        self.model = model
        self.item = item
        var form = PartForm(part: PartDraft(id: 0, inputText: item.name, lookupKey: nil, shortcut: nil, draft: nil,
                                            historySuggestions: []), saveByDefault: false)
        form.fill(from: FoodDraft(item: item))
        _form = State(initialValue: form)
        _time = State(initialValue: (item.consumedTime ?? LocalTime(date: Date())).asDate)
        _hasTime = State(initialValue: item.consumedTime != nil)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $form.name)
                    HStack {
                        TextField("Amount", text: $form.amount).keyboardType(.decimalPad)
                        TextField("Unit", text: $form.unit)
                    }
                    HStack {
                        TextField("Calories", text: $form.calories).keyboardType(.decimalPad)
                        Text("kcal").foregroundStyle(.secondary)
                    }
                    HStack {
                        TextField("Protein", text: $form.protein).keyboardType(.decimalPad)
                        Text("g").foregroundStyle(.secondary)
                        TextField("Fibre", text: $form.fibre).keyboardType(.decimalPad)
                        Text("g").foregroundStyle(.secondary)
                    }
                    TextField("Notes", text: $form.notes, axis: .vertical)
                }
                Section {
                    Toggle("Time", isOn: $hasTime)
                    if hasTime { DatePicker("At", selection: $time, displayedComponents: .hourAndMinute) }
                }
                if let shortcut {
                    Section {
                        Toggle("Update “\(shortcut.name)” shortcut", isOn: $updateShortcut)
                    } footer: {
                        Text("Make this portion the new normal next time you log it.")
                    }
                }
                Section {
                    LabeledContent("Source", value: sourceText)
                    Button("Delete", role: .destructive) {
                        model.removeItem(item.id)
                        dismiss()
                    }
                }
            }
            .navigationTitle("Edit")
            .navigationBarTitleDisplayMode(.inline)
            .task { shortcut = model.shortcutCandidate(for: item) }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save).disabled(!form.isValid)
                }
            }
        }
    }

    private var sourceText: String {
        var parts = [item.source.rawValue.replacingOccurrences(of: "_", with: " ").lowercased()]
        if let nutrientSource = item.nutrientSource { parts.append("nutrients: \(nutrientSource.rawValue.lowercased())") }
        if let ai = item.aiProvenance { parts.append("\(ai.provider) \(ai.model)") }
        return parts.joined(separator: " · ")
    }

    private func save() {
        var draft = FoodDraft(item: item)
        draft.name = form.name
        draft.amount = Format.parseNumber(form.amount)
        draft.unit = form.unit
        draft.calories = Format.parseNumber(form.calories) ?? 0
        let protein = Format.parseNumber(form.protein)
        let fibre = Format.parseNumber(form.fibre)
        if protein != item.proteinG || fibre != item.fibreG { draft.nutrientSource = nil }
        draft.proteinG = protein
        draft.fibreG = fibre
        draft.notes = form.notes
        let consumed = hasTime ? LocalTime(dateValue: time).truncatedToMinutes : nil
        if model.update(item, with: draft, time: consumed, updateShortcut: updateShortcut) { dismiss() }
    }
}

struct ShortcutSheet: View {
    let model: AppModel
    @State var shortcut: Shortcut
    @State private var calories: String
    @State private var amount: String
    @State private var protein: String
    @State private var fibre: String
    @Environment(\.dismiss) private var dismiss

    init(model: AppModel, shortcut: Shortcut) {
        self.model = model
        _shortcut = State(initialValue: shortcut)
        _calories = State(initialValue: Format.number(shortcut.calories))
        _amount = State(initialValue: shortcut.defaultAmount.map(Format.number) ?? "")
        _protein = State(initialValue: shortcut.proteinPerPortion.map(Format.number) ?? "")
        _fibre = State(initialValue: shortcut.fibrePerPortion.map(Format.number) ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $shortcut.name)
                    HStack {
                        TextField("Amount", text: $amount).keyboardType(.decimalPad)
                        TextField("Unit", text: $shortcut.unit)
                    }
                    HStack {
                        TextField("Calories", text: $calories).keyboardType(.decimalPad)
                        Text(shortcut.portionMode == .plain ? "kcal" : "kcal per \(shortcut.unit)")
                            .foregroundStyle(.secondary)
                    }
                    if shortcut.portionMode == .plain {
                        HStack {
                            TextField("Protein", text: $protein).keyboardType(.decimalPad)
                            Text("g").foregroundStyle(.secondary)
                            TextField("Fibre", text: $fibre).keyboardType(.decimalPad)
                            Text("g").foregroundStyle(.secondary)
                        }
                    }
                    TextField("Notes", text: Binding($shortcut.notes, default: ""), axis: .vertical)
                } footer: {
                    Text("Typing “\(shortcut.lookupKey)” or tapping the chip logs this portion.")
                }
                Section {
                    Button("Forget shortcut", role: .destructive) {
                        model.forgetShortcut(shortcut.lookupKey)
                        dismiss()
                    }
                }
            }
            .navigationTitle("Shortcut")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        var updated = shortcut
                        updated.calories = Format.parseNumber(calories) ?? 0
                        updated.defaultAmount = Format.parseNumber(amount)
                        if updated.portionMode == .plain {
                            updated.proteinPerPortion = Format.parseNumber(protein)
                            updated.fibrePerPortion = Format.parseNumber(fibre)
                        }
                        if model.saveShortcut(updated) { dismiss() }
                    }
                }
            }
        }
    }
}

struct WeightSheet: View {
    let model: AppModel
    @State private var kilograms = ""
    @State private var time = Date()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        TextField("Weight", text: $kilograms).keyboardType(.decimalPad)
                        Text("kg").foregroundStyle(.secondary)
                    }
                    DatePicker("Measured", selection: $time, displayedComponents: .hourAndMinute)
                } footer: {
                    Text("For \(Format.day(model.selectedDate, today: (try? model.store.currentFoodDate()) ?? model.selectedDate)). Weight never affects calories.")
                }
                if model.weight != nil {
                    Button("Remove weight", role: .destructive) {
                        model.removeWeight()
                        dismiss()
                    }
                }
            }
            .navigationTitle("Weight")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                if let weight = model.weight {
                    kilograms = String(format: "%.1f", weight.weightKg)
                    time = weight.measuredTime.asDate
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard let value = Format.parseNumber(kilograms), value > 0 else { return }
                        model.saveWeight(value, at: LocalTime(dateValue: time).truncatedToMinutes)
                        dismiss()
                    }
                    .disabled((Format.parseNumber(kilograms) ?? 0) <= 0)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

struct SettingsSheet: View {
    let model: AppModel
    @State private var useBoundary = false
    @State private var boundary = Date()
    @State private var importing = false
    @State private var importMessage: String?
    @State private var aiURL = ""
    @State private var aiToken = ""
    @State private var aiStatus: String?
    @State private var checkingAI = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Late-night food counts for the day before", isOn: $useBoundary)
                    if useBoundary {
                        DatePicker("Day starts at", selection: $boundary, displayedComponents: .hourAndMinute)
                    }
                } header: {
                    Text("Food day")
                } footer: {
                    Text("For example, with 06:00, a 1am snack counts for the previous day.")
                }
                .onChange(of: useBoundary) { saveBoundary() }
                .onChange(of: boundary) { saveBoundary() }

                Section {
                    TextField("Worker address (https://…)", text: $aiURL)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("App token", text: $aiToken)
                    Button(checkingAI ? "Checking…" : "Save and test") {
                        checkingAI = true
                        Task {
                            aiStatus = await model.configureAI(url: aiURL, token: aiToken)
                            checkingAI = false
                        }
                    }
                    .disabled(checkingAI)
                    if let aiStatus { Text(aiStatus).font(.footnote) }
                } header: {
                    Text("AI estimates")
                } footer: {
                    Text("Sends only food descriptions and food or label photos, to your own FoodLog Worker. "
                         + "Every estimate is a draft you check before it's logged.")
                }

                Section {
                    if let backfill = model.backfill, backfill.running {
                        ProgressView(value: Double(backfill.done), total: Double(max(backfill.total, 1))) {
                            Text("Estimating \(backfill.done) of \(backfill.total) foods…")
                        }
                        Button("Stop", role: .destructive) { model.stopBackfill() }
                    } else {
                        Text(model.rowsMissingNutrients == 0
                             ? "Every logged item has protein and fibre."
                             : "\(model.rowsMissingNutrients) logged items have no protein or fibre yet.")
                        Button("Fill in protein and fibre") { model.startBackfill() }
                    }
                    if let message = model.backfill?.message, model.backfill?.running == false {
                        Text(message).font(.footnote)
                    }
                } header: {
                    Text("Protein and fibre")
                } footer: {
                    Text(model.aiAvailable
                         ? "Uses labels and shortcuts first, then one AI estimate per food, scaled to the calories you logged. "
                           + "Calories never change, estimates are marked, and shortcuts learn the values for next time."
                         : "Without AI estimates, only foods with label or shortcut data can be filled.")
                }

                Section("Journal export") {
                    Toggle("Include weight", isOn: Binding(
                        get: { model.settings.journalIncludeWeight },
                        set: { model.setJournalIncludeWeight($0) }))
                    if let file = model.journalFile() {
                        ShareLink("Export full journal (CSV)", item: file)
                    }
                }

                if model.canImportAndroidData || importMessage != nil {
                    Section {
                        if let file = model.pendingImportFile {
                            Button("Import \(file.lastPathComponent)") { runImport(file) }
                        }
                        Button("Choose Android export…") { importing = true }
                        if let importMessage { Text(importMessage).font(.footnote) }
                    } header: {
                        Text("Android history")
                    } footer: {
                        Text("Imports only into an empty FoodLog, so nothing is merged or duplicated.")
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                useBoundary = model.settings.dayBoundaryTime != nil
                boundary = (model.settings.dayBoundaryTime ?? LocalTime(hour: 6, minute: 0)!).asDate
                aiURL = AISettings.workerURL ?? ""
                aiToken = AISettings.token ?? ""
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
                if case .success(let url) = result { runImport(url) }
            }
            .toolbar { Button("Done") { dismiss() } }
        }
    }

    private func saveBoundary() {
        let value = useBoundary ? LocalTime(dateValue: boundary).truncatedToMinutes : nil
        if value != model.settings.dayBoundaryTime { model.setDayBoundary(value) }
    }

    private func runImport(_ url: URL) {
        if let summary = model.importAndroidExport(from: url) {
            importMessage = "Imported \(summary)."
        }
    }
}

extension Binding where Value == String {
    init(_ source: Binding<String?>, default defaultValue: String) {
        self.init(get: { source.wrappedValue ?? defaultValue },
                  set: { source.wrappedValue = $0.isEmpty ? nil : $0 })
    }
}
