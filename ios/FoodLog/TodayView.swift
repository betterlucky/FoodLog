import FoodLogCore
import SwiftUI

/// The main daily surface: one text field and one-tap shortcuts at the bottom, the day's rows above.
struct TodayView: View {
    @Bindable var model: AppModel
    @State private var entryText = ""
    @State private var sheet: Sheet?
    @State private var showDatePicker = false
    @FocusState private var entryFocused: Bool

    enum Sheet: Identifiable {
        case resolve(ResolutionContext)
        case edit(FoodItem)
        case weight
        case settings
        case label
        case editShortcut(Shortcut)

        var id: String {
            switch self {
            case .resolve(let request): return "resolve-\(request.id)"
            case .edit(let item): return "edit-\(item.id)"
            case .weight: return "weight"
            case .settings: return "settings"
            case .label: return "label"
            case .editShortcut(let shortcut): return "shortcut-\(shortcut.lookupKey)"
            }
        }
    }

    var body: some View {
        NavigationStack {
            List {
                summarySection
                if !model.pending.isEmpty { pendingSection }
                loggedSection
            }
            .listStyle(.insetGrouped)
            .navigationTitle(Format.day(model.selectedDate, today: (try? model.store.currentFoodDate()) ?? model.selectedDate))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
            .safeAreaInset(edge: .bottom) { entryBar }
            .overlay(alignment: .bottom) { toastView }
            .sheet(item: $sheet, onDismiss: model.refresh) { sheet in sheetView(sheet) }
            .sheet(isPresented: $showDatePicker) { datePicker }
            .alert("Something went wrong", isPresented: Binding(
                get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.errorMessage ?? "")
            }
        }
    }

    // MARK: Sections

    private var summarySection: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text(Format.kcal(model.totals.calories))
                    .font(.system(.largeTitle, design: .rounded).weight(.semibold))
                    .contentTransition(.numericText())
                    .accessibilityIdentifier("dayTotal")
                HStack(spacing: 14) {
                    if let protein = model.totals.proteinG {
                        Label("\(Format.grams(protein)) protein", systemImage: "p.circle")
                    }
                    if let fibre = model.totals.fibreG {
                        Label("\(Format.grams(fibre)) fibre", systemImage: "leaf")
                    }
                    if let weight = model.weight {
                        Label(String(format: "%.1f kg", weight.weightKg), systemImage: "scalemass")
                    }
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
                if let backfill = model.backfill, backfill.running {
                    ProgressView(value: Double(backfill.done), total: Double(max(backfill.total, 1))) {
                        Text("Filling in protein and fibre… \(backfill.done) of \(backfill.total)").font(.caption)
                    }
                } else if model.totals.itemCount > 0, model.totals.itemsWithProtein < model.totals.itemCount,
                          model.aiAvailable || model.totals.proteinG != nil {
                    HStack {
                        Text("Protein known for \(model.totals.itemsWithProtein) of \(model.totals.itemCount) items")
                            .foregroundStyle(.tertiary)
                        if model.aiAvailable {
                            Button("Fill in") { model.startBackfill() }
                                .buttonStyle(.borderless)
                                .accessibilityIdentifier("fillNutrients")
                        }
                    }
                    .font(.caption)
                }
            }
            .padding(.vertical, 4)
        }
    }

    private var pendingSection: some View {
        Section("Needs details") {
            ForEach(model.pending) { entry in
                Button {
                    if let request = model.resolveRequest(forPending: entry.id) { sheet = .resolve(request) }
                } label: {
                    HStack {
                        Image(systemName: "questionmark.circle.fill").foregroundStyle(.orange)
                        Text(entry.rawText).foregroundStyle(.primary)
                        Spacer()
                        Text(Format.time(entry.consumedTime)).foregroundStyle(.secondary).font(.caption)
                    }
                }
                .tint(.primary)
                .swipeActions {
                    Button("Discard", role: .destructive) { model.removePending(entry.id) }
                }
            }
        }
    }

    private var loggedSection: some View {
        Section {
            if model.items.isEmpty {
                Text(model.isToday ? "Nothing logged yet today." : "Nothing logged on this day.")
                    .foregroundStyle(.secondary)
            }
            ForEach(model.items) { item in
                Button { sheet = .edit(item) } label: { FoodRow(item: item) }
                    .tint(.primary)
                    .swipeActions {
                        Button("Delete", role: .destructive) { model.removeItem(item.id) }
                    }
            }
        }
    }

    // MARK: Entry bar

    private var entryBar: some View {
        VStack(spacing: 8) {
            if !model.shortcuts.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(model.shortcuts) { shortcut in
                            Button {
                                model.log(shortcut)
                            } label: {
                                Text(shortcut.name)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 7)
                                    .background(.thinMaterial, in: Capsule())
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Button("Edit shortcut", systemImage: "pencil") { sheet = .editShortcut(shortcut) }
                                Button("Forget shortcut", systemImage: "trash", role: .destructive) {
                                    model.forgetShortcut(shortcut.lookupKey)
                                }
                            }
                        }
                    }
                    .padding(.horizontal)
                }
            }
            HStack(spacing: 10) {
                Button { sheet = .label } label: {
                    Image(systemName: "text.viewfinder").font(.title2)
                }
                .accessibilityLabel("Scan a label")
                TextField("What did you eat?", text: $entryText)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.done)
                    .focused($entryFocused)
                    .accessibilityIdentifier("entryField")
                    .onSubmit(submit)
                Button(action: submit) {
                    Image(systemName: "arrow.up.circle.fill").font(.title)
                }
                .disabled(entryText.trimmingCharacters(in: .whitespaces).isEmpty)
                .accessibilityLabel("Log entry")
                .accessibilityIdentifier("submitEntry")
            }
            .padding(.horizontal)
        }
        .padding(.vertical, 10)
        .background(.bar)
    }

    private func submit() {
        let text = entryText
        guard !text.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        switch model.submit(text) {
        case .logged, .saved:
            entryText = ""
        case .needsDetails(let request):
            entryText = ""
            entryFocused = false
            sheet = .resolve(request)
        case .kept:
            break
        }
    }

    // MARK: Toast

    @ViewBuilder private var toastView: some View {
        if let toast = model.toast {
            HStack {
                Text(toast.message).lineLimit(2)
                Spacer()
                if !toast.undoFoodItemIds.isEmpty {
                    Button("Undo") { model.undo(toast) }.bold()
                }
            }
            .padding()
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            .padding(.horizontal)
            .padding(.bottom, 120)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .task(id: toast.id) {
                try? await Task.sleep(for: .seconds(4))
                withAnimation { if model.toast?.id == toast.id { model.toast = nil } }
            }
        }
    }

    // MARK: Toolbar and sheets

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarLeading) {
            Button { model.moveDay(by: -1) } label: { Image(systemName: "chevron.left") }
                .accessibilityLabel("Previous day")
            Button { model.moveDay(by: 1) } label: { Image(systemName: "chevron.right") }
                .accessibilityLabel("Next day")
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            if !model.isToday {
                Button("Today") { model.goToToday() }
            }
            Menu {
                Button("Pick a day", systemImage: "calendar") { showDatePicker = true }
                Button("Add manually", systemImage: "square.and.pencil") {
                    sheet = .resolve(model.manualRequest())
                }
                Button("Weight", systemImage: "scalemass") { sheet = .weight }
                Button("Settings", systemImage: "gearshape") { sheet = .settings }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .accessibilityLabel("More")
        }
    }

    @ViewBuilder private func sheetView(_ sheet: Sheet) -> some View {
        switch sheet {
        case .resolve(let request): ResolveSheet(model: model, request: request)
        case .edit(let item): EditFoodSheet(model: model, item: item)
        case .weight: WeightSheet(model: model)
        case .settings: SettingsSheet(model: model)
        case .label: LabelScanSheet(model: model)
        case .editShortcut(let shortcut): ShortcutSheet(model: model, shortcut: shortcut)
        }
    }

    private var datePicker: some View {
        NavigationStack {
            DatePicker("Day", selection: Binding(
                get: { model.selectedDate.midnightUTC },
                set: { model.select(LocalDate(date: $0, timeZone: TimeZone(identifier: "UTC")!)) }),
                displayedComponents: .date)
                .datePickerStyle(.graphical)
                .environment(\.timeZone, TimeZone(identifier: "UTC")!)
                .padding()
                .toolbar { Button("Done") { showDatePicker = false } }
        }
        .presentationDetents([.medium])
    }
}

struct FoodRow: View {
    let item: FoodItem

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).foregroundStyle(.primary)
                let detail = [Format.time(item.consumedTime), Format.quantity(amount: item.amount, unit: item.unit),
                              item.source == .estimate ? "estimate" : ""]
                    .filter { !$0.isEmpty }.joined(separator: " · ")
                if !detail.isEmpty {
                    Text(detail).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text(Format.kcal(item.calories)).monospacedDigit().foregroundStyle(.primary)
        }
    }
}
