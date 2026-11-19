import Foundation

public struct DeterministicIntentClassifier: Sendable {
    private let normalizer: DeterministicParser

    public init(normalizer: DeterministicParser = DeterministicParser()) {
        self.normalizer = normalizer
    }

    public func classify(_ input: String) -> EntryIntent {
        let normalized = normalizer.normalize(input)
        if normalized.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .unknown }

        func starts(_ prefixes: String...) -> Bool { prefixes.contains { normalized.hasPrefix($0) } }

        if starts("actually ", "delete ", "remove ", "change ", "correct ") {
            return .correction
        }
        if ["end of day", "export today", "export day", "daily report"].contains(normalized) {
            return .exportCommand
        }
        if normalized.hasSuffix("?")
            || starts("how am i doing", "what have i eaten", "what did i eat", "summary", "summarise", "summarize") {
            return .query
        }
        if starts("note ", "note:") {
            return .note
        }
        return .foodLog
    }
}
