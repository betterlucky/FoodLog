public enum EntryIntent: String, CaseIterable, Sendable {
    case foodLog = "FOOD_LOG"
    case query = "QUERY"
    case correction = "CORRECTION"
    case exportCommand = "EXPORT_COMMAND"
    case note = "NOTE"
    case unknown = "UNKNOWN"
}
