import Foundation

func csvLine(_ values: [String?]) -> String {
    values.map { escapeCsv($0 ?? "") }.joined(separator: ",")
}

private func escapeCsv(_ value: String) -> String {
    guard value.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) else { return value }
    return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
}

/// Kotlin `Double.toString` for whole numbers prints `18.0`; the exporters print `18` instead.
func formatPlain(_ value: Double) -> String {
    value.truncatingRemainder(dividingBy: 1) == 0 && abs(value) < 1e15 ? String(Int64(value)) : "\(value)"
}

public struct JournalExportOptions: Hashable, Sendable {
    public var includeWeight: Bool
    public init(includeWeight: Bool = false) { self.includeWeight = includeWeight }
}

/// Full-history journal CSV built from database rows only. Matches the Android columns, then
/// appends protein, fibre, and nutrient provenance.
public struct JournalCsvExporter {
    public static let header =
        "date,time_local,entry_type,item,quantity,calories_kcal,weight_kg,notes,source,food_item_id,product_id,created_at,protein_g,fibre_g,nutrient_source"

    public init() {}

    public func export(items: [FoodItem], dailyWeights: [DailyWeight],
                       options: JournalExportOptions = JournalExportOptions()) -> String {
        struct Row { let date: LocalDate; let time: LocalTime?; let createdAt: Date; let values: [String?] }

        var rows: [Row] = items.filter { !$0.voided }.map { item in
            Row(date: item.logDate, time: item.consumedTime, createdAt: item.createdAt, values: [
                item.logDate.iso, item.consumedTime?.iso ?? "", "food", item.name, quantity(for: item),
                formatPlain(item.calories), "", item.notes ?? "", item.source.journalName, String(item.id),
                item.productId.map(String.init) ?? "", InstantFormat.string(from: item.createdAt),
                item.proteinG.map(formatNutrient) ?? "", item.fibreG.map(formatNutrient) ?? "",
                item.nutrientSource?.rawValue.lowercased() ?? "",
            ])
        }
        if options.includeWeight {
            rows += dailyWeights.map { weight in
                Row(date: weight.logDate, time: weight.measuredTime, createdAt: weight.createdAt, values: [
                    weight.logDate.iso, weight.measuredTime.iso, "weight", "weight", "", "",
                    String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), weight.weightKg),
                    "Recorded weight", "daily weight", "", "", InstantFormat.string(from: weight.createdAt), "", "", "",
                ])
            }
        }
        // Kotlin compareBy puts null times first.
        rows.sort { lhs, rhs in
            if lhs.date != rhs.date { return lhs.date < rhs.date }
            if lhs.time != rhs.time {
                guard let left = lhs.time else { return true }
                guard let right = rhs.time else { return false }
                return left < right
            }
            return lhs.createdAt < rhs.createdAt
        }
        return ([Self.header] + rows.map { csvLine($0.values) }).joined(separator: "\n")
    }

    private func quantity(for item: FoodItem) -> String {
        switch (item.amount, item.unit) {
        case let (amount?, unit?):
            return "\(formatPlain(amount)) \(amount != 1.0 && unit == "cup" ? "cups" : unit)"
        case let (amount?, nil): return formatPlain(amount)
        case let (nil, unit?): return unit
        default: return ""
        }
    }

    private func formatNutrient(_ value: Double) -> String {
        String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), value)
    }
}

extension FoodItemSource {
    var journalName: String {
        switch self {
        case .savedLabel: return "label scan"
        case .activeLeftover: return "leftover"
        case .recentProduct: return "product"
        case .userDefault: return "shortcut"
        case .manualOverride: return "manual entry"
        case .estimate: return "estimate"
        }
    }
}

extension FoodLogStore {
    public func buildJournalCsv(options: JournalExportOptions) throws -> String {
        JournalCsvExporter().export(items: try allActiveFoodItems(), dailyWeights: try allDailyWeights(),
                                    options: options)
    }
}
