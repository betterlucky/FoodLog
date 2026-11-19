import FoodLogCore
import SwiftUI

/// A number with its name always visible on the left and its unit on the right ("Protein … 1.7 g").
struct NumberField: View {
    let label: String
    @Binding var text: String
    let unit: String

    var body: some View {
        LabeledContent(label) {
            HStack(spacing: 4) {
                TextField("–", text: $text)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .accessibilityIdentifier(label)
                Text(unit).foregroundStyle(.secondary)
            }
        }
    }
}

/// One labelled row per tracked nutrient, driven by `Nutrient.allCases`.
struct NutrientFields: View {
    @Binding var values: [Nutrient: String]
    var suffix = ""

    var body: some View {
        ForEach(Nutrient.allCases) { nutrient in
            NumberField(label: nutrient.name + suffix,
                        text: Binding(get: { values[nutrient] ?? "" }, set: { values[nutrient] = $0 }),
                        unit: nutrient.unit)
        }
    }
}

/// A text field with its name always visible on the left.
struct TextRow: View {
    let label: String
    @Binding var text: String
    var placeholder = ""
    var numeric = false

    var body: some View {
        LabeledContent(label) {
            TextField(placeholder, text: $text)
                .keyboardType(numeric ? .decimalPad : .default)
                .multilineTextAlignment(.trailing)
                .accessibilityIdentifier(label)
        }
    }
}
