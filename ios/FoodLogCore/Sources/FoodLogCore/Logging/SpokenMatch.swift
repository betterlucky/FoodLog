import Foundation

/// Repairs dictation that mishears a short food name ("tea" → "T", "tee") by matching it to the one
/// shortcut it sounds like. Only a single food word (with an optional leading quantity) is ever
/// rewritten, and only when exactly one shortcut matches, so ordinary text passes through untouched.
public enum SpokenMatch {
    public static func correct(_ text: String, shortcuts: [Shortcut]) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines.union(.punctuationCharacters))
        var words = trimmed.split(separator: " ").map(String.init)
        guard !words.isEmpty, words.count <= 2 else { return text }
        let quantity = words.count == 2 && Double(words[0]) != nil ? words.removeFirst() : nil
        guard words.count == 1 else { return text }
        let word = words[0].lowercased()
        if shortcuts.contains(where: { $0.lookupKey == word || $0.name.lowercased() == word }) { return text }
        let code = soundex(word)
        guard !code.isEmpty else { return text }
        let matches = shortcuts.filter { soundex($0.lookupKey) == code || soundex($0.name) == code }
        guard matches.count == 1 else { return text }
        return [quantity, matches[0].lookupKey].compactMap { $0 }.joined(separator: " ")
    }

    /// American Soundex: first letter plus up to three consonant-group digits ("tea" and "t" → "T000").
    public static func soundex(_ text: String) -> String {
        let letters = text.uppercased().filter { $0.isASCII && $0.isLetter }
        guard let first = letters.first else { return "" }
        func digit(_ c: Character) -> Character? {
            switch c {
            case "B", "F", "P", "V": return "1"
            case "C", "G", "J", "K", "Q", "S", "X", "Z": return "2"
            case "D", "T": return "3"
            case "L": return "4"
            case "M", "N": return "5"
            case "R": return "6"
            default: return nil
            }
        }
        var result = String(first)
        var previous = digit(first)
        for c in letters.dropFirst() {
            let d = digit(c)
            if let d, d != previous { result.append(d) }
            if c != "H" && c != "W" { previous = d }
            if result.count == 4 { break }
        }
        return result.padding(toLength: 4, withPad: "0", startingAt: 0)
    }
}

extension LoggingService {
    /// Quick log for dictated text: tidies dictation (punctuation, number words, openers, misheard
    /// shortcut names), then logs it like typed text.
    public func quickLogSpoken(_ text: String) throws -> QuickLogResult {
        try quickLog(DictationNormalizer.normalize(text, shortcuts: store.activeShortcuts()), dictated: text)
    }
}
