import Foundation

/// Turns dictated speech ("Yesterday, I had two teas and a slice of toast.") into the compact text the
/// deterministic parser already understands ("yesterday 2 teas, 1 slice of toast"). Used only for spoken
/// input; typed entries go to the parser unchanged.
public enum DictationNormalizer {
    private static let numberWords: [String: String] = [
        "a": "1", "an": "1", "one": "1", "two": "2", "three": "3", "four": "4", "five": "5", "six": "6",
        "seven": "7", "eight": "8", "nine": "9", "ten": "10", "eleven": "11", "twelve": "12",
        "half": "0.5", "couple": "2",
    ]
    private static let fillerPrefixes = [
        "ok", "okay", "so", "um", "uh", "er", "log", "add", "just", "and", "then", "also",
        "i had", "i have had", "i've had", "i ate", "i've eaten", "i drank", "i've drunk", "i've just had",
        "i just had", "i've got", "have", "had",
    ]
    private static let dayWords = ["yesterday", "today"]

    public static func normalize(_ text: String, shortcuts: [Shortcut] = []) -> String {
        var s = " " + text.lowercased() + " "
        // "7 A.M.", "7 am", "7:30 p.m." → "7am", "7:30pm"
        s = s.replacing(#/(\d{1,2}(?::\d{2})?)\s*([ap])\.?\s*m\.?(?=\W)/#) { "\($0.output.1)\($0.output.2)m" }
        // Sentence punctuation, but not decimal points or time colons.
        s = s.replacing(#/[!?;"“”]/#, with: " ")
        s = String(s.enumerated().map { index, character -> Character in
            guard character == "." else { return character }
            let chars = Array(s)
            let digitBefore = index > 0 && chars[index - 1].isNumber
            let digitAfter = index + 1 < chars.count && chars[index + 1].isNumber
            return digitBefore && digitAfter ? "." : " "
        })
        s = s.replacingOccurrences(of: "’", with: "'")
        // "at seven" → "at 7"
        s = s.replacing(#/\bat (one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\b/#) {
            "at \(numberWords[String($0.output.1)]!)"
        }
        // "at 7" / "at 7:30" (no am/pm) → "at 07:00" / "at 07:30", the 24-hour form the parser accepts.
        s = s.replacing(#/\bat (\d{1,2})(?::(\d{2}))?(?=[\s,])/#) { match in
            let hour = String(match.output.1)
            return "at \(hour.count == 1 ? "0" + hour : hour):\(match.output.2.map(String.init) ?? "00")"
        }
        var words = s.split(whereSeparator: \.isWhitespace).map(String.init)

        // Pull a day word out so it can lead, where the parser expects it.
        var day: String?
        if let index = words.firstIndex(where: { dayWords.contains($0.trimmingCharacters(in: .punctuationCharacters)) }) {
            day = words.remove(at: index).trimmingCharacters(in: .punctuationCharacters)
            if day == "today" { day = nil }
        }

        // Split into foods on commas and "and"/"then", then tidy each part.
        let joined = words.joined(separator: " ")
        let rawParts = joined
            .replacing(#/\s*,\s*|\s+(?:and then|and|then|plus)\s+/#, with: "|")
            .split(separator: "|").map { String($0) }
        let parts = rawParts.compactMap { tidy($0, shortcuts: shortcuts) }
        guard !parts.isEmpty else { return text.trimmingCharacters(in: .whitespacesAndNewlines) }
        return ([day].compactMap { $0 } + [parts.joined(separator: ", ")]).joined(separator: " ")
    }

    private static func tidy(_ part: String, shortcuts: [Shortcut]) -> String? {
        var words = part.split(separator: " ").map(String.init).filter { !$0.isEmpty }
        // Strip openers like "I had", "just", "log" (repeatedly: "so I just had").
        var stripped = true
        while stripped, !words.isEmpty {
            stripped = false
            for filler in fillerPrefixes.sorted(by: { $0.count > $1.count }) {
                let fillerWords = filler.split(separator: " ").map(String.init)
                if words.count > fillerWords.count, Array(words.prefix(fillerWords.count)) == fillerWords {
                    words.removeFirst(fillerWords.count)
                    stripped = true
                }
            }
        }
        if words.last == "please" { words.removeLast() }
        guard !words.isEmpty else { return nil }
        // "half a banana" → "0.5 banana"; "a couple of teas" → "2 teas"; "two teas" → "2 teas".
        if words.count > 2, words[0] == "half", words[1] == "a" || words[1] == "an" {
            words = ["0.5"] + words.dropFirst(2)
        } else if words.count > 2, words[0] == "a" || words[0] == "an", words[1] == "couple" || words[1] == "half" {
            words = [numberWords[words[1]]!] + words.dropFirst(words.count > 3 && words[2] == "of" ? 3 : 2)
        } else if words.count > 1, let number = numberWords[words[0]] {
            words[0] = number
        }
        return SpokenMatch.correct(words.joined(separator: " "), shortcuts: shortcuts)
    }
}
