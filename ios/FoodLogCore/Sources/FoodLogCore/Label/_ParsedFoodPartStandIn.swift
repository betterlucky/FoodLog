// TEMPORARY stand-in for the parser port's `ParsedFoodPart`; delete this file at merge time.
// Mirrors the Kotlin data class in DeterministicParser.kt.
public struct ParsedFoodPart: Hashable, Sendable {
    public var normalizedFoodText: String
    public var shortcutTrigger: String?
    public var quantity: Double
    public var quantityUnit: String?

    public init(normalizedFoodText: String, shortcutTrigger: String?, quantity: Double = 1.0, quantityUnit: String? = nil) {
        self.normalizedFoodText = normalizedFoodText
        self.shortcutTrigger = shortcutTrigger
        self.quantity = quantity
        self.quantityUnit = quantityUnit
    }
}
