import Foundation

/// Helpers that give `NSRegularExpression` the Kotlin/Java semantics the parser was written against.
/// Java's `\s` and `\d` are ASCII-only, while ICU's are Unicode-aware, so patterns use these classes.
enum JavaRegex {
    /// Java `\s`.
    static let s = "[ \\t\\n\\u000B\\f\\r]"
    /// Java `\d`.
    static let d = "[0-9]"

    /// Compiles `pattern` so that it matches only the entire input (Kotlin `Regex.matchEntire`).
    static func entire(_ pattern: String, ignoreCase: Bool = false) -> NSRegularExpression {
        try! NSRegularExpression(
            pattern: "\\A(?:\(pattern))\\z",
            options: ignoreCase ? [.caseInsensitive] : []
        )
    }

    static func compile(_ pattern: String) -> NSRegularExpression {
        try! NSRegularExpression(pattern: pattern, options: [])
    }

    /// Capture groups of an entire-string match as `groupValues` (index 0 is the whole match), or nil.
    static func matchEntire(_ regex: NSRegularExpression, in text: String) -> [String]? {
        let ns = text as NSString
        guard let match = regex.firstMatch(in: text, options: [], range: NSRange(location: 0, length: ns.length))
        else { return nil }
        return (0..<match.numberOfRanges).map { index in
            let range = match.range(at: index)
            return range.location == NSNotFound ? "" : ns.substring(with: range)
        }
    }

    /// Kotlin `CharSequence.split(Regex)` for regexes that never match the empty string.
    static func split(_ text: String, by regex: NSRegularExpression) -> [String] {
        let ns = text as NSString
        var pieces: [String] = []
        var last = 0
        for match in regex.matches(in: text, options: [], range: NSRange(location: 0, length: ns.length)) {
            pieces.append(ns.substring(with: NSRange(location: last, length: match.range.location - last)))
            last = match.range.location + match.range.length
        }
        pieces.append(ns.substring(from: last))
        return pieces
    }
}
