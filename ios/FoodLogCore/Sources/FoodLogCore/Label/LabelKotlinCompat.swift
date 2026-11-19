import Foundation

// Helpers that reproduce the Kotlin/Java string, regex and number semantics the label code relies on.
// Everything here is internal and prefixed `Label` so it cannot collide with other ports.

struct LabelRegexMatch {
    /// Index 0 is the whole match; unmatched groups are nil.
    let groups: [String?]
    /// Kotlin `groupValues`: unmatched groups read as "".
    var groupValues: [String] { groups.map { $0 ?? "" } }
}

final class LabelRegex {
    private static var cache: [String: NSRegularExpression] = [:]
    private static let lock = NSLock()

    private let regex: NSRegularExpression

    /// Equivalent of Kotlin `Regex(pattern)` (find / containsMatchIn / replace semantics).
    init(_ pattern: String, ignoreCase: Bool = false) {
        regex = LabelRegex.compile(pattern, ignoreCase: ignoreCase)
    }

    /// Equivalent of Kotlin `Regex(pattern).matchEntire(...)`: the whole input must match.
    static func entire(_ pattern: String, ignoreCase: Bool = false) -> LabelRegex {
        LabelRegex(#"\A(?:"# + pattern + #")\z"#, ignoreCase: ignoreCase)
    }

    private static func compile(_ pattern: String, ignoreCase: Bool) -> NSRegularExpression {
        let key = (ignoreCase ? "i:" : "s:") + pattern
        lock.lock()
        defer { lock.unlock() }
        if let cached = cache[key] { return cached }
        let compiled = try! NSRegularExpression(pattern: pattern, options: ignoreCase ? [.caseInsensitive] : [])
        cache[key] = compiled
        return compiled
    }

    private func build(_ result: NSTextCheckingResult, in ns: NSString) -> LabelRegexMatch {
        var groups: [String?] = []
        for index in 0..<result.numberOfRanges {
            let range = result.range(at: index)
            groups.append(range.location == NSNotFound ? nil : ns.substring(with: range))
        }
        return LabelRegexMatch(groups: groups)
    }

    func find(_ input: String) -> LabelRegexMatch? {
        let ns = input as NSString
        guard let result = regex.firstMatch(in: input, options: [], range: NSRange(location: 0, length: ns.length)) else {
            return nil
        }
        return build(result, in: ns)
    }

    func findAll(_ input: String) -> [LabelRegexMatch] {
        let ns = input as NSString
        return regex.matches(in: input, options: [], range: NSRange(location: 0, length: ns.length))
            .map { build($0, in: ns) }
    }

    func containsMatch(_ input: String) -> Bool {
        regex.firstMatch(in: input, options: [], range: NSRange(location: 0, length: (input as NSString).length)) != nil
    }

    func replace(_ input: String, _ transform: (LabelRegexMatch) -> String) -> String {
        let ns = input as NSString
        var output = ""
        var cursor = 0
        for result in regex.matches(in: input, options: [], range: NSRange(location: 0, length: ns.length)) {
            output += ns.substring(with: NSRange(location: cursor, length: result.range.location - cursor))
            output += transform(build(result, in: ns))
            cursor = result.range.location + result.range.length
        }
        output += ns.substring(from: cursor)
        return output
    }

    func replace(_ input: String, with literal: String) -> String {
        replace(input) { _ in literal }
    }
}

/// Kotlin `String.lines()`: splits on CRLF, LF and CR, keeping empty lines.
func labelLines(_ text: String) -> [String] {
    var lines: [String] = []
    var current = String.UnicodeScalarView()
    let scalars = Array(text.unicodeScalars)
    var index = 0
    while index < scalars.count {
        let scalar = scalars[index]
        if scalar == "\r" {
            lines.append(String(current))
            current = String.UnicodeScalarView()
            if index + 1 < scalars.count, scalars[index + 1] == "\n" { index += 1 }
        } else if scalar == "\n" {
            lines.append(String(current))
            current = String.UnicodeScalarView()
        } else {
            current.append(scalar)
        }
        index += 1
    }
    lines.append(String(current))
    return lines
}

/// Kotlin `String.trim()` (whitespace on both ends).
func labelTrim(_ text: String) -> String {
    text.trimmingCharacters(in: .whitespacesAndNewlines)
}

/// Kotlin `String.trimStart()`.
func labelTrimStart(_ text: String) -> String {
    String(text.drop(while: { $0.isWhitespace }))
}

private let labelDoublePattern = LabelRegex.entire(#"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?"#)

/// Kotlin `String.toDoubleOrNull()` for the plain decimal forms the app parses. Rejects hex, inf and nan.
func labelParseDouble(_ text: String) -> Double? {
    guard labelDoublePattern.containsMatch(text) else { return nil }
    guard let value = Double(text), value.isFinite else { return nil }
    return value
}

/// The parser's `toDoubleValue`: comma decimal separator, positive values only.
func labelToDoubleValue(_ text: String) -> Double? {
    labelParseDouble(text.replacingOccurrences(of: ",", with: "."))
        .flatMap { $0 > 0.0 ? $0 : nil }
}

/// Kotlin `Double.toInt().toString()` for integral values.
func labelIntegerString(_ value: Double) -> String {
    if abs(value) < 9.0e18 { return String(Int64(value)) }
    return String(format: "%.0f", value)
}

/// Kotlin `Double.rem(1.0) == 0.0`.
func labelIsWhole(_ value: Double) -> Bool {
    value.truncatingRemainder(dividingBy: 1.0) == 0.0
}

/// Kotlin `Double.toString()`-style text for ordinary magnitudes ("2.5", "0.3333333333333333").
func labelDoubleString(_ value: Double) -> String {
    "\(value)"
}

/// Kotlin `String.format(Locale.US, "%.2f", x)`: HALF_UP on the shortest decimal digits.
func labelFormatFixed2(_ value: Double) -> String {
    guard value.isFinite else { return "\(value)" }
    let magnitude = abs(value)
    guard var decimal = Decimal(string: "\(magnitude)") else { return String(format: "%.2f", value) }
    var rounded = Decimal()
    NSDecimalRound(&rounded, &decimal, 2, .plain)
    let text = String(format: "%.2f", NSDecimalNumber(decimal: rounded).doubleValue)
    return value < 0 && text != "0.00" ? "-" + text : text
}

func labelTrimEnd(_ text: String, _ character: Character) -> String {
    var result = Substring(text)
    while result.last == character { result = result.dropLast() }
    return String(result)
}
