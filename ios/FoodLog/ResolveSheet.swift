import FoodLogCore
import SwiftUI

/// Editable text for one food part.
struct PartForm: Identifiable {
    let id: Int
    var inputText: String
    var fromShortcut: FoodDraft?
    var include = true
    var name = ""
    var amount = ""
    var unit = ""
    var calories = ""
    var protein = ""
    var fibre = ""
    var notes = ""
    var saveAsShortcut = false
    var suggestions: [FoodHistorySuggestion] = []

    init(part: PartDraft, saveByDefault: Bool) {
        id = part.id
        inputText = part.inputText
        fromShortcut = part.draft
        suggestions = part.historySuggestions
        saveAsShortcut = saveByDefault && part.draft == nil && part.lookupKey != nil
        if let draft = part.draft {
            fill(from: draft)
        } else {
            name = part.inputText.prefix(1).uppercased() + part.inputText.dropFirst()
        }
    }

    mutating func fill(from draft: FoodDraft) {
        name = draft.name
        amount = draft.amount.map(Format.number) ?? ""
        unit = draft.unit ?? ""
        calories = draft.calories > 0 ? Format.number(draft.calories) : ""
        protein = draft.proteinG.map(Format.number) ?? ""
        fibre = draft.fibreG.map(Format.number) ?? ""
        notes = draft.notes ?? ""
    }

    mutating func apply(_ suggestion: FoodHistorySuggestion) {
        name = suggestion.name
        amount = suggestion.amount.map(Format.number) ?? ""
        unit = suggestion.unit ?? ""
        calories = Format.number(suggestion.calories)
        notes = suggestion.notes ?? ""
    }

    /// The draft to save, or nil when skipped. Shortcut-resolved parts keep their link and
    /// source unless the user changed what was resolved.
    var draft: FoodDraft? {
        guard include else { return nil }
        var draft = fromShortcut ?? FoodDraft()
        let edited = FoodDraft(
            name: name, amount: Format.parseNumber(amount), unit: unit, calories: Format.parseNumber(calories) ?? 0,
            proteinG: Format.parseNumber(protein), fibreG: Format.parseNumber(fibre), notes: notes)
        if let original = fromShortcut,
           original.name == edited.name, original.amount == edited.amount, (original.unit ?? "") == unit,
           original.calories == edited.calories, original.proteinG == edited.proteinG, original.fibreG == edited.fibreG {
            draft.notes = edited.notes
            return draft
        }
        draft.name = edited.name
        draft.amount = edited.amount
        draft.unit = edited.unit
        draft.grams = nil
        draft.milliliters = nil
        draft.calories = edited.calories
        draft.proteinG = edited.proteinG
        draft.fibreG = edited.fibreG
        draft.notes = edited.notes
        draft.nutrientSource = nil
        if fromShortcut != nil { draft.source = .manualOverride }
        return draft
    }

    var isValid: Bool {
        !include || (!name.trimmingCharacters(in: .whitespaces).isEmpty && (Format.parseNumber(calories) ?? 0) > 0)
    }
}

/// Review screen for a pending entry or a manual add. Unknown parts need a name and calories;
/// protein and fibre are optional ("log now, enrich later").
struct ResolveSheet: View {
    let model: AppModel
    let request: ResolutionContext
    @State private var forms: [PartForm]
    @State private var time: Date
    @Environment(\.dismiss) private var dismiss

    init(model: AppModel, request: ResolutionContext) {
        self.model = model
        self.request = request
        _forms = State(initialValue: request.parts.map { PartForm(part: $0, saveByDefault: request.rawEntryId != nil) })
        _time = State(initialValue: (request.consumedTime ?? LocalTime(date: Date())).asDate)
    }

    var body: some View {
        NavigationStack {
            Form {
                if !request.rawText.isEmpty {
                    Section { Text("“\(request.rawText)”").foregroundStyle(.secondary) }
                }
                ForEach($forms) { $form in
                    PartSection(model: model, form: $form, isManual: request.rawEntryId == nil,
                                showsInclude: forms.count > 1)
                }
                Section {
                    DatePicker("Time", selection: $time, displayedComponents: .hourAndMinute)
                }
            }
            .navigationTitle(request.rawEntryId == nil ? "Add food" : "Add details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Log", action: save)
                        .disabled(!forms.allSatisfy(\.isValid) || forms.allSatisfy { !$0.include })
                }
            }
        }
    }

    private func save() {
        let drafts = forms.map(\.draft)
        let saves = Set(forms.filter { $0.include && $0.saveAsShortcut && $0.fromShortcut == nil }.map(\.id))
        if model.resolve(request, drafts: drafts, time: LocalTime(dateValue: time).truncatedToMinutes, saveShortcuts: saves) {
            dismiss()
        }
    }
}

private struct PartSection: View {
    let model: AppModel
    @Binding var form: PartForm
    let isManual: Bool
    let showsInclude: Bool
    @State private var showNutrients = false

    var body: some View {
        Section {
            if showsInclude {
                Toggle(isOn: $form.include) {
                    Text(form.inputText).font(.headline)
                }
            }
            if form.include {
                if let shortcut = form.fromShortcut, !showNutrients, form.draft == shortcut {
                    HStack {
                        Label(shortcut.name, systemImage: "bolt.fill").foregroundStyle(.primary)
                        Spacer()
                        Text(Format.kcal(shortcut.calories)).monospacedDigit()
                    }
                    Button("Change") { showNutrients = true }.font(.caption)
                } else {
                    fields
                }
            }
        } footer: {
            if form.include, form.fromShortcut == nil, !form.suggestions.isEmpty {
                Text("Tap a previous log to reuse it.")
            }
        }
    }

    @ViewBuilder private var fields: some View {
        if form.fromShortcut == nil, !form.suggestions.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(form.suggestions, id: \.foodItemId) { suggestion in
                        Button {
                            form.apply(suggestion)
                        } label: {
                            VStack(alignment: .leading) {
                                Text(suggestion.name).font(.subheadline)
                                Text("\(Format.quantity(amount: suggestion.amount, unit: suggestion.unit)) · \(Format.kcal(suggestion.calories))")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            .padding(8)
                            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 8))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        TextField("Name", text: $form.name)
            .onChange(of: form.name) { _, name in
                if isManual, form.suggestions.isEmpty || name.count > 2 {
                    form.suggestions = model.suggestions(for: name)
                }
            }
        HStack {
            TextField("Amount", text: $form.amount).keyboardType(.decimalPad)
            TextField("Unit (g, slice, bowl…)", text: $form.unit)
        }
        HStack {
            TextField("Calories", text: $form.calories).keyboardType(.decimalPad)
            Text("kcal").foregroundStyle(.secondary)
        }
        DisclosureGroup("Protein, fibre and notes", isExpanded: $showNutrients) {
            HStack {
                TextField("Protein", text: $form.protein).keyboardType(.decimalPad)
                Text("g").foregroundStyle(.secondary)
                TextField("Fibre", text: $form.fibre).keyboardType(.decimalPad)
                Text("g").foregroundStyle(.secondary)
            }
            TextField("Notes", text: $form.notes, axis: .vertical)
        }
        if form.fromShortcut == nil {
            Toggle("Save as shortcut", isOn: $form.saveAsShortcut)
        }
    }
}
