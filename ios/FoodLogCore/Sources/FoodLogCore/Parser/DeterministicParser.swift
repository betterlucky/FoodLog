import Foundation

public struct ParsedSubmission: Equatable, Sendable {
    public let rawText: String
    public let normalizedFoodText: String
    public let logDate: LocalDate
    public let consumedTime: LocalTime?
    public let parts: [ParsedFoodPart]

    public init(
        rawText: String,
        normalizedFoodText: String,
        logDate: LocalDate,
        consumedTime: LocalTime?,
        parts: [ParsedFoodPart]
    ) {
        self.rawText = rawText
        self.normalizedFoodText = normalizedFoodText
        self.logDate = logDate
        self.consumedTime = consumedTime
        self.parts = parts
    }

    /// The single part's shortcut trigger, or nil when there is not exactly one part.
    public var shortcutTrigger: String? { parts.count == 1 ? parts[0].shortcutTrigger : nil }

    public var quantity: Double { parts.count == 1 ? parts[0].quantity : 1.0 }
}

public struct ParsedFoodPart: Equatable, Sendable {
    public let normalizedFoodText: String
    public let shortcutTrigger: String?
    public let quantity: Double
    public let quantityUnit: String?

    public init(
        normalizedFoodText: String,
        shortcutTrigger: String?,
        quantity: Double = 1.0,
        quantityUnit: String? = nil
    ) {
        self.normalizedFoodText = normalizedFoodText
        self.shortcutTrigger = shortcutTrigger
        self.quantity = quantity
        self.quantityUnit = quantityUnit
    }
}

public struct DeterministicParser: Sendable {
    public init() {}

    public func parse(input: String, today: LocalDate, defaultLogDate: LocalDate? = nil) -> ParsedSubmission {
        let normalized = normalize(input)
        let dated = extractDatePrefix(normalized: normalized, today: today, defaultLogDate: defaultLogDate ?? today)
        let timed = extractTime(dated.foodText)
        return ParsedSubmission(
            rawText: input,
            normalizedFoodText: timed.foodText,
            logDate: dated.logDate,
            consumedTime: timed.consumedTime,
            parts: foodParts(for: timed.foodText)
        )
    }

    public func normalize(_ input: String) -> String {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return Self.whitespaceRun.stringByReplacingMatches(
            in: trimmed, options: [], range: NSRange(location: 0, length: (trimmed as NSString).length),
            withTemplate: " "
        )
    }

    // MARK: - Date prefix

    private struct DatedFoodText { let foodText: String; let logDate: LocalDate }
    private struct TimedFoodText { let foodText: String; let consumedTime: LocalTime? }
    private struct ShortcutMatch { let trigger: String; let quantity: Double; let unit: String? }

    private func extractDatePrefix(normalized: String, today: LocalDate, defaultLogDate: LocalDate) -> DatedFoodText {
        let yesterdayPrefix = "yesterday "
        let todayPrefix = "today "

        if normalized.hasPrefix(yesterdayPrefix) {
            return DatedFoodText(foodText: String(normalized.dropFirst(yesterdayPrefix.count)), logDate: today.minusDays(1))
        }
        if normalized.hasPrefix(todayPrefix) {
            return DatedFoodText(foodText: String(normalized.dropFirst(todayPrefix.count)), logDate: today)
        }
        if let groups = JavaRegex.matchEntire(Self.isoDatePrefix, in: normalized) {
            if let parsed = LocalDate(iso: groups[1]) {
                return DatedFoodText(foodText: groups[2], logDate: parsed)
            }
            return DatedFoodText(foodText: normalized, logDate: defaultLogDate)
        }
        return DatedFoodText(foodText: normalized, logDate: defaultLogDate)
    }

    // MARK: - Time

    private func extractTime(_ foodText: String) -> TimedFoodText {
        // Kotlin's TimeTextParser.parse throws for matches like "8:00" (single-digit hour in 24h form);
        // here an unparseable time is left in the food text instead of crashing.
        if let groups = JavaRegex.matchEntire(Self.timePrefix, in: foodText),
           let time = TimeTextParser.parseOrNull(groups[1]) {
            return TimedFoodText(foodText: groups[2], consumedTime: time)
        }
        if let groups = JavaRegex.matchEntire(Self.timeSuffix, in: foodText),
           let time = TimeTextParser.parseOrNull(groups[2]) {
            return TimedFoodText(foodText: groups[1], consumedTime: time)
        }
        return TimedFoodText(foodText: foodText, consumedTime: nil)
    }

    // MARK: - Food parts

    private func foodParts(for foodText: String) -> [ParsedFoodPart] {
        splitFoodText(foodText)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .map { part in
                let match = shortcutMatch(for: part)
                return ParsedFoodPart(
                    normalizedFoodText: part,
                    shortcutTrigger: match?.trigger,
                    quantity: match?.quantity ?? 1.0,
                    quantityUnit: match?.unit
                )
            }
    }

    private func shortcutMatch(for foodText: String) -> ShortcutMatch? {
        if foodText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }

        if let g = JavaRegex.matchEntire(Self.gramQuantity, in: foodText) {
            return ShortcutMatch(trigger: singularizeShortcut(g[2]), quantity: Double(g[1]) ?? 1.0, unit: "g")
        }
        if let g = JavaRegex.matchEntire(Self.unitQuantity, in: foodText) {
            return ShortcutMatch(
                trigger: singularizeShortcut(g[3]), quantity: Double(g[1]) ?? 1.0, unit: singularizeUnit(g[2])
            )
        }
        if let g = JavaRegex.matchEntire(Self.numericQuantity, in: foodText) {
            return ShortcutMatch(trigger: singularizeShortcut(g[2]), quantity: Double(g[1]) ?? 1.0, unit: nil)
        }
        if let g = JavaRegex.matchEntire(Self.fractionQuantityRegex, in: foodText) {
            return ShortcutMatch(trigger: singularizeShortcut(g[2]), quantity: fractionQuantity(g[1]), unit: nil)
        }
        if let g = JavaRegex.matchEntire(Self.wordQuantityRegex, in: foodText) {
            return ShortcutMatch(trigger: singularizeShortcut(g[2]), quantity: wordQuantity(g[1]), unit: nil)
        }

        let trigger: String
        if foodText.hasPrefix("a ") {
            trigger = String(foodText.dropFirst("a ".count))
        } else if foodText.hasPrefix("cup of ") {
            trigger = String(foodText.dropFirst("cup of ".count))
        } else {
            trigger = foodText
        }
        let singular = singularizeShortcut(trigger)
        if singular.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }
        return ShortcutMatch(trigger: singular, quantity: 1.0, unit: nil)
    }

    private func singularizeShortcut(_ trigger: String) -> String {
        switch trigger {
        case "teas", "cups of tea": return "tea"
        case "bananas": return "banana"
        case "satsumas": return "satsuma"
        case "apples": return "apple"
        case "roast dinners": return "roast dinner"
        case "yoghurts": return "yoghurt"
        case "yogurt pots": return "yogurt pot"
        case "yoghurt pots": return "yoghurt pot"
        default: return trigger
        }
    }

    private func splitFoodText(_ foodText: String) -> [String] {
        let protectedText = Self.protectedAndPhrases.reduce(foodText) { text, phrase in
            text.replacingOccurrences(of: phrase, with: phrase.replacingOccurrences(of: " and ", with: Self.protectedAndToken))
        }
        return JavaRegex.split(protectedText, by: Self.separator)
            .flatMap { segment -> [String] in
                segment.contains(" with ") ? [segment] : JavaRegex.split(segment, by: Self.andWord)
            }
            .map { $0.replacingOccurrences(of: Self.protectedAndToken, with: " and ") }
    }

    private func singularizeUnit(_ unit: String) -> String {
        switch unit {
        case "slices": return "slice"
        case "pieces": return "piece"
        case "servings": return "serving"
        case "portions": return "portion"
        case "pots": return "pot"
        case "cups": return "cup"
        case "crackers": return "cracker"
        case "biscuits": return "biscuit"
        case "bars": return "bar"
        case "bowls": return "bowl"
        case "spoons": return "spoon"
        case "tablespoons": return "tablespoon"
        case "teaspoons": return "teaspoon"
        default: return unit
        }
    }

    private func wordQuantity(_ word: String) -> Double {
        switch word.lowercased() {
        case "two": return 2.0
        case "three": return 3.0
        case "four": return 4.0
        case "five": return 5.0
        case "half", "a half": return 0.5
        default: return 1.0
        }
    }

    private func fractionQuantity(_ word: String) -> Double {
        switch word.lowercased().trimmingCharacters(in: .whitespacesAndNewlines) {
        case "1/2", "a half", "half": return 0.5
        case "1/3", "a third", "one third": return 1.0 / 3.0
        case "2/3", "two thirds", "two third": return 2.0 / 3.0
        case "3/4", "three quarters", "three quarter": return 0.75
        case "1/4", "a quarter", "one quarter": return 0.25
        default: return 1.0
        }
    }

    // MARK: - Patterns (Java `\s` / `\d` semantics via JavaRegex)

    private static let s = JavaRegex.s
    private static let d = JavaRegex.d
    private static let timePattern = TimeTextParser.pattern
    private static let unitPattern =
        "slices?|pieces?|servings?|portions?|pots?|cups?|crackers?|biscuits?|bars?|bowls?|spoons?|tablespoons?|teaspoons?"
    private static let protectedAndToken = "__and__"
    private static let protectedAndPhrases = ["fruit and nut mix"]
    private static let number = "(\(d)+(?:\\.\(d)+)?)"

    private static let whitespaceRun = JavaRegex.compile("\(s)+")
    private static let isoDatePrefix = JavaRegex.entire("^(\(d){4}-\(d){2}-\(d){2})\(s)+(.+)$")
    private static let timePrefix = JavaRegex.entire("^(?:at\(s)+)?(\(timePattern))\(s)+(.+)$")
    private static let timeSuffix = JavaRegex.entire("^(.+?)\(s)+(?:at\(s)+)?(\(timePattern))$")
    private static let gramQuantity = JavaRegex.entire("^\(number)\(s)*g\(s)+(.+)$")
    private static let unitQuantity = JavaRegex.entire("^\(number)\(s)+(\(unitPattern))\(s)+(?:of\(s)+)?(.+)$")
    private static let numericQuantity = JavaRegex.entire("^\(number)\(s)+(.+)$")
    private static let fractionQuantityRegex = JavaRegex.entire(
        "^(1/2|a half|half|1/3|a third|one third|2/3|two thirds?|3/4|three quarters?|1/4|a quarter|one quarter)\(s)+(.+)$",
        ignoreCase: true
    )
    private static let wordQuantityRegex = JavaRegex.entire(
        "^(two|three|four|five|half|a half)\(s)+(.+)$", ignoreCase: true
    )
    private static let separator = JavaRegex.compile("\(s)*(?:,|\\+|(?<!\(d))/(?!\(d))|&|;)\(s)*")
    private static let andWord = JavaRegex.compile("\(s)+and\(s)+")
}
