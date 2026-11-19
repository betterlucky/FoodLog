import Foundation
import SQLite3

public enum SQLiteError: Error, CustomStringConvertible {
    case open(String)
    case prepare(String, sql: String)
    case step(String, sql: String)
    case bind(String)

    public var description: String {
        switch self {
        case .open(let message): return "SQLite open failed: \(message)"
        case .prepare(let message, let sql): return "SQLite prepare failed: \(message) in \(sql)"
        case .step(let message, let sql): return "SQLite step failed: \(message) in \(sql)"
        case .bind(let message): return "SQLite bind failed: \(message)"
        }
    }
}

public enum SQLValue: Equatable, Sendable {
    case null
    case integer(Int64)
    case real(Double)
    case text(String)
}

public protocol SQLBindable {
    var sqlValue: SQLValue { get }
}

extension Int64: SQLBindable { public var sqlValue: SQLValue { .integer(self) } }
extension Int: SQLBindable { public var sqlValue: SQLValue { .integer(Int64(self)) } }
extension Double: SQLBindable { public var sqlValue: SQLValue { .real(self) } }
extension String: SQLBindable { public var sqlValue: SQLValue { .text(self) } }
extension Bool: SQLBindable { public var sqlValue: SQLValue { .integer(self ? 1 : 0) } }
extension LocalDate: SQLBindable { public var sqlValue: SQLValue { .text(iso) } }
extension LocalTime: SQLBindable { public var sqlValue: SQLValue { .text(iso) } }
extension Date: SQLBindable { public var sqlValue: SQLValue { .text(InstantFormat.string(from: self)) } }
extension Optional: SQLBindable where Wrapped: SQLBindable {
    public var sqlValue: SQLValue { self?.sqlValue ?? .null }
}

/// ISO-8601 instants matching java.time.Instant.toString closely enough for round trips.
public enum InstantFormat {
    public static func string(from date: Date) -> String {
        let hasFraction = date.timeIntervalSince1970.truncatingRemainder(dividingBy: 1) != 0
        return (hasFraction ? fractional : whole).string(from: date)
    }

    public static func date(from string: String) -> Date? {
        whole.date(from: string) ?? fractional.date(from: string)
    }

    private static let whole: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    private static let fractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}

public struct SQLRow {
    fileprivate let values: [String: SQLValue]

    public func int64(_ column: String) -> Int64? {
        if case .integer(let value) = values[column] { return value }
        return nil
    }

    public func int(_ column: String) -> Int? { int64(column).map(Int.init) }

    public func double(_ column: String) -> Double? {
        switch values[column] {
        case .real(let value): return value
        case .integer(let value): return Double(value)
        default: return nil
        }
    }

    public func string(_ column: String) -> String? {
        if case .text(let value) = values[column] { return value }
        return nil
    }

    public func bool(_ column: String) -> Bool { (int64(column) ?? 0) != 0 }
    public func date(_ column: String) -> LocalDate? { string(column).flatMap(LocalDate.init(iso:)) }
    public func time(_ column: String) -> LocalTime? { string(column).flatMap(LocalTime.init(iso:)) }
    public func instant(_ column: String) -> Date? { string(column).flatMap(InstantFormat.date(from:)) }
}

/// A small synchronous SQLite connection. Not thread-safe: use from one actor/queue.
public final class SQLiteConnection {
    private var handle: OpaquePointer?

    public init(path: String) throws {
        let flags = SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(path, &handle, flags, nil) == SQLITE_OK else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unknown"
            sqlite3_close(handle)
            throw SQLiteError.open(message)
        }
        try execute("PRAGMA foreign_keys = ON")
        try execute("PRAGMA journal_mode = WAL")
    }

    deinit { sqlite3_close(handle) }

    public var userVersion: Int {
        get { (try? query("PRAGMA user_version").first?.int("user_version")) ?? 0 }
    }

    public func setUserVersion(_ version: Int) throws { try execute("PRAGMA user_version = \(version)") }

    public var lastInsertRowID: Int64 { sqlite3_last_insert_rowid(handle) }
    public var changes: Int { Int(sqlite3_changes(handle)) }

    public func execute(_ sql: String, _ arguments: [SQLBindable] = []) throws {
        let statement = try prepare(sql, arguments)
        defer { sqlite3_finalize(statement) }
        while true {
            let result = sqlite3_step(statement)
            if result == SQLITE_DONE { return }
            if result != SQLITE_ROW { throw SQLiteError.step(errorMessage, sql: sql) }
        }
    }

    /// Runs a script of several statements with no arguments.
    public func executeScript(_ sql: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(handle, sql, nil, nil, &error) == SQLITE_OK else {
            let message = error.map { String(cString: $0) } ?? errorMessage
            sqlite3_free(error)
            throw SQLiteError.step(message, sql: sql)
        }
    }

    public func query(_ sql: String, _ arguments: [SQLBindable] = []) throws -> [SQLRow] {
        let statement = try prepare(sql, arguments)
        defer { sqlite3_finalize(statement) }
        var rows: [SQLRow] = []
        while true {
            let result = sqlite3_step(statement)
            if result == SQLITE_DONE { break }
            guard result == SQLITE_ROW else { throw SQLiteError.step(errorMessage, sql: sql) }
            var values: [String: SQLValue] = [:]
            for index in 0..<sqlite3_column_count(statement) {
                let name = String(cString: sqlite3_column_name(statement, index))
                switch sqlite3_column_type(statement, index) {
                case SQLITE_INTEGER: values[name] = .integer(sqlite3_column_int64(statement, index))
                case SQLITE_FLOAT: values[name] = .real(sqlite3_column_double(statement, index))
                case SQLITE_TEXT: values[name] = .text(String(cString: sqlite3_column_text(statement, index)))
                default: values[name] = .null
                }
            }
            rows.append(SQLRow(values: values))
        }
        return rows
    }

    /// Runs `body` in a transaction, rolling back if it throws.
    public func transaction<T>(_ body: () throws -> T) throws -> T {
        try execute("BEGIN IMMEDIATE")
        do {
            let result = try body()
            try execute("COMMIT")
            return result
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    private var errorMessage: String { String(cString: sqlite3_errmsg(handle)) }

    private func prepare(_ sql: String, _ arguments: [SQLBindable]) throws -> OpaquePointer? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK else {
            throw SQLiteError.prepare(errorMessage, sql: sql)
        }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (offset, argument) in arguments.enumerated() {
            let index = Int32(offset + 1)
            let result: Int32
            switch argument.sqlValue {
            case .null: result = sqlite3_bind_null(statement, index)
            case .integer(let value): result = sqlite3_bind_int64(statement, index, value)
            case .real(let value): result = sqlite3_bind_double(statement, index, value)
            case .text(let value): result = sqlite3_bind_text(statement, index, value, -1, transient)
            }
            guard result == SQLITE_OK else {
                sqlite3_finalize(statement)
                throw SQLiteError.bind(errorMessage)
            }
        }
        return statement
    }
}
