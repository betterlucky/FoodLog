import Foundation

/// A calendar date with no time zone, mirroring java.time.LocalDate. Stored as `YYYY-MM-DD`.
public struct LocalDate: Hashable, Comparable, Codable, CustomStringConvertible, Sendable {
    public let year: Int
    public let month: Int
    public let day: Int

    public init?(year: Int, month: Int, day: Int) {
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        guard let date = LocalDate.calendar.date(from: components) else { return nil }
        let normalized = LocalDate.calendar.dateComponents([.year, .month, .day], from: date)
        guard normalized.year == year, normalized.month == month, normalized.day == day else { return nil }
        self.year = year
        self.month = month
        self.day = day
    }

    /// Parses strict ISO `YYYY-MM-DD`.
    public init?(iso: String) {
        let parts = iso.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2])
        else { return nil }
        self.init(year: year, month: month, day: day)
    }

    /// The local calendar date of `date` in `timeZone`.
    public init(date: Date, timeZone: TimeZone = .current) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        self.year = components.year!
        self.month = components.month!
        self.day = components.day!
    }

    public var iso: String { String(format: "%04d-%02d-%02d", year, month, day) }
    public var description: String { iso }

    public func plusDays(_ days: Int) -> LocalDate {
        let date = LocalDate.calendar.date(byAdding: .day, value: days, to: midnightUTC)!
        let components = LocalDate.calendar.dateComponents([.year, .month, .day], from: date)
        return LocalDate(year: components.year!, month: components.month!, day: components.day!)!
    }

    public func minusDays(_ days: Int) -> LocalDate { plusDays(-days) }

    /// Midnight UTC on this date, used only for calendar arithmetic and formatting.
    public var midnightUTC: Date {
        LocalDate.calendar.date(from: DateComponents(year: year, month: month, day: day))!
    }

    public static func < (lhs: LocalDate, rhs: LocalDate) -> Bool {
        (lhs.year, lhs.month, lhs.day) < (rhs.year, rhs.month, rhs.day)
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let value = try container.decode(String.self)
        guard let date = LocalDate(iso: value) else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Invalid date \(value)")
        }
        self = date
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(iso)
    }

    private static let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()
}

/// A wall-clock time with no date or zone, mirroring java.time.LocalTime at minute precision.
/// Stored as `HH:mm`, or `HH:mm:ss` when seconds are non-zero.
public struct LocalTime: Hashable, Comparable, Codable, CustomStringConvertible, Sendable {
    public let hour: Int
    public let minute: Int
    public let second: Int

    public init?(hour: Int, minute: Int, second: Int = 0) {
        guard (0...23).contains(hour), (0...59).contains(minute), (0...59).contains(second) else { return nil }
        self.hour = hour
        self.minute = minute
        self.second = second
    }

    /// Parses `H:mm`, `HH:mm`, or `HH:mm:ss` (24-hour), like LocalTime.parse for the forms the app stores.
    public init?(iso: String) {
        let parts = iso.split(separator: ":", omittingEmptySubsequences: false)
        guard (2...3).contains(parts.count), parts[0].count == 2, parts[1].count == 2,
              parts.count == 2 || parts[2].count == 2,
              let hour = Int(parts[0]), let minute = Int(parts[1])
        else { return nil }
        let second = parts.count == 3 ? Int(parts[2]) : 0
        guard let second else { return nil }
        self.init(hour: hour, minute: minute, second: second)
    }

    public init(date: Date, timeZone: TimeZone = .current) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let components = calendar.dateComponents([.hour, .minute, .second], from: date)
        self.hour = components.hour!
        self.minute = components.minute!
        self.second = components.second!
    }

    public var iso: String {
        second == 0
            ? String(format: "%02d:%02d", hour, minute)
            : String(format: "%02d:%02d:%02d", hour, minute, second)
    }

    public var description: String { iso }

    /// The time truncated to whole minutes.
    public var truncatedToMinutes: LocalTime { LocalTime(hour: hour, minute: minute)! }

    public static func < (lhs: LocalTime, rhs: LocalTime) -> Bool {
        (lhs.hour, lhs.minute, lhs.second) < (rhs.hour, rhs.minute, rhs.second)
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let value = try container.decode(String.self)
        guard let time = LocalTime(iso: value) else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Invalid time \(value)")
        }
        self = time
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(iso)
    }
}
