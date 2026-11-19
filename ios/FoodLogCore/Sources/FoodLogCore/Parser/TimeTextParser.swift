import Foundation

public enum TimeTextParser {
    public static let pattern: String =
        "(?:[01]?\(JavaRegex.d)|2[0-3]):[0-5]\(JavaRegex.d)|(?:0?[1-9]|1[0-2])(?::[0-5]\(JavaRegex.d))?\(JavaRegex.s)*(?:am|pm)"

    /// Parses `HH:mm[:ss]` (two-digit hour, like java `LocalTime.parse`) or `h[:mm]am/pm`.
    /// Returns nil for anything unsupported.
    public static func parseOrNull(_ value: String) -> LocalTime? {
        let compact = value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: " ", with: "")
        if compact.isEmpty { return nil }

        if compact.hasSuffix("am") || compact.hasSuffix("pm") {
            return parseMeridiemTime(compact)
        }
        return LocalTime(iso: compact)
    }

    private static func parseMeridiemTime(_ value: String) -> LocalTime? {
        let meridiem = String(value.suffix(2))
        let timeText = String(value.dropLast(2))
        let parts = timeText.split(separator: ":", omittingEmptySubsequences: false)
        guard let first = parts.first, let hour = Int(first) else { return nil }
        var minute = 0
        if parts.count > 1 {
            guard let parsed = Int(parts[1]) else { return nil }
            minute = parsed
        }
        let resolvedHour: Int
        switch meridiem {
        case "am": resolvedHour = hour == 12 ? 0 : hour
        case "pm": resolvedHour = hour == 12 ? 12 : hour + 12
        default: resolvedHour = hour
        }
        // Out-of-range values make LocalTime.of throw in Kotlin; Swift returns nil.
        return LocalTime(hour: resolvedHour, minute: minute)
    }
}
