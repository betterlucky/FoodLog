import FoodLogCore
import Foundation

enum Format {
    static func kcal(_ value: Double) -> String { "\(Int(value.rounded())) kcal" }

    static func grams(_ value: Double) -> String {
        value < 10 ? String(format: "%.1f g", value) : "\(Int(value.rounded())) g"
    }

    static func number(_ value: Double) -> String {
        value.truncatingRemainder(dividingBy: 1) == 0 ? String(Int64(value)) : String(format: "%g", value)
    }

    static func quantity(amount: Double?, unit: String?) -> String {
        switch (amount, unit) {
        case let (amount?, unit?): return "\(number(amount)) \(amount != 1 && unit == "cup" ? "cups" : unit)"
        case let (amount?, nil): return number(amount)
        case let (nil, unit?): return unit
        default: return ""
        }
    }

    static func day(_ date: LocalDate, today: LocalDate) -> String {
        if date == today { return "Today" }
        if date == today.minusDays(1) { return "Yesterday" }
        if date == today.plusDays(1) { return "Tomorrow" }
        var style = Date.FormatStyle().weekday(.abbreviated).day().month(.abbreviated)
        style.timeZone = TimeZone(identifier: "UTC")!
        return date.midnightUTC.formatted(style)
    }

    static func time(_ time: LocalTime?) -> String { time?.truncatedToMinutes.iso ?? "" }

    /// Parses user-typed numbers, accepting a comma decimal separator.
    static func parseNumber(_ text: String) -> Double? {
        Double(text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: "."))
    }
}

extension LocalTime {
    var asDate: Date {
        Calendar.current.date(bySettingHour: hour, minute: minute, second: 0, of: Date()) ?? Date()
    }

    init(dateValue: Date) { self.init(date: dateValue) }
}
