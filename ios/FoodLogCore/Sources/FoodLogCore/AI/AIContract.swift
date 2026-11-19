import Foundation

/// Swift side of docs/ai-contract.md (version 1). The app only ever talks to FoodLog's Worker.
public enum AIContract {
    public static let version = 1
}

public struct AIImage: Codable, Hashable, Sendable {
    public var mediaType: String
    /// Base64, no newlines.
    public var data: String

    public init(mediaType: String, data: String) {
        self.mediaType = mediaType
        self.data = data
    }

    public init(jpeg: Data) { self.init(mediaType: "image/jpeg", data: jpeg.base64EncodedString()) }
}

public struct EstimateHints: Codable, Hashable, Sendable {
    public var portion: String?
    public var batchWeightGrams: Double?
    public var servings: Double?

    public init(portion: String? = nil, batchWeightGrams: Double? = nil, servings: Double? = nil) {
        self.portion = portion
        self.batchWeightGrams = batchWeightGrams
        self.servings = servings
    }

    var isEmpty: Bool { portion == nil && batchWeightGrams == nil && servings == nil }
}

struct EstimateRequest: Encodable {
    var contractVersion = AIContract.version
    var text: String?
    var image: AIImage?
    var hints: EstimateHints?
}

struct LabelRequest: Encodable {
    var contractVersion = AIContract.version
    var image: AIImage
}

public struct AIProvenanceResponse: Codable, Hashable, Sendable {
    public var provider: String
    public var model: String
    public var latencyMs: Double?
}

public struct EstimatedItem: Codable, Hashable, Sendable {
    public var name: String
    public var amount: Double?
    public var unit: String?
    public var grams: Double?
    public var kcal: Double
    public var proteinG: Double?
    public var fibreG: Double?
    public var confidence: ConfidenceLevel
    public var notes: String?

    public init(name: String, amount: Double? = nil, unit: String? = nil, grams: Double? = nil, kcal: Double,
                proteinG: Double? = nil, fibreG: Double? = nil, confidence: ConfidenceLevel, notes: String? = nil) {
        self.name = name
        self.amount = amount
        self.unit = unit
        self.grams = grams
        self.kcal = kcal
        self.proteinG = proteinG
        self.fibreG = fibreG
        self.confidence = confidence
        self.notes = notes
    }
}

public struct NutrientsPer100: Codable, Hashable, Sendable {
    public var kcal: Double?
    public var proteinG: Double?
    public var fibreG: Double?
    public var carbsG: Double?
    public var fatG: Double?
    public var sugarsG: Double?
    public var saltG: Double?
}

public struct EstimateResponse: Codable, Hashable, Sendable {
    public var contractVersion: Int
    public var items: [EstimatedItem]
    public var per100g: NutrientsPer100?
    public var notes: String?
    public var provenance: AIProvenanceResponse

    public var aiProvenance: AIProvenance {
        AIProvenance(contractVersion: contractVersion, provider: provenance.provider, model: provenance.model)
    }

    /// Checks the contract's ranges again on the phone, so a misbehaving Worker can't log nonsense.
    func validated() throws -> EstimateResponse {
        guard contractVersion == AIContract.version else { throw AIError.unsupportedVersion(contractVersion) }
        guard (1...12).contains(items.count) else { throw AIError.invalidResponse("item count \(items.count)") }
        for item in items {
            guard !item.name.trimmingCharacters(in: .whitespaces).isEmpty, item.name.count <= 120,
                  (0...5000).contains(item.kcal),
                  item.amount.map({ $0 > 0 }) ?? true,
                  item.grams.map({ $0 > 0 && $0 <= 5000 }) ?? true,
                  item.proteinG.map({ (0...500).contains($0) }) ?? true,
                  item.fibreG.map({ (0...500).contains($0) }) ?? true
            else { throw AIError.invalidResponse("item \(item.name) out of range") }
        }
        return self
    }
}

public struct ServingFacts: Codable, Hashable, Sendable {
    public var kcal: Double?
    public var servingGrams: Double?
    public var servingAmount: Double?
    public var servingUnit: String?
}

public struct LabelReadResponse: Codable, Hashable, Sendable {
    public var contractVersion: Int
    public var productName: String?
    public var per100g: NutrientsPer100?
    public var per100ml: NutrientsPer100?
    public var perServing: ServingFacts?
    public var packageGrams: Double?
    public var packageMilliliters: Double?
    public var packageItemCount: Double?
    public var packageItemUnit: String?
    public var prepared: Bool?
    public var confidence: ConfidenceLevel
    public var notes: String?
    public var provenance: AIProvenanceResponse

    public var aiProvenance: AIProvenance {
        AIProvenance(contractVersion: contractVersion, provider: provenance.provider, model: provenance.model)
    }

    func validated() throws -> LabelReadResponse {
        guard contractVersion == AIContract.version else { throw AIError.unsupportedVersion(contractVersion) }
        for per100 in [per100g, per100ml].compactMap({ $0 }) {
            guard per100.kcal.map({ (0...1000).contains($0) }) ?? true,
                  [per100.proteinG, per100.fibreG, per100.carbsG, per100.fatG, per100.sugarsG, per100.saltG]
                    .allSatisfy({ $0.map { (0...100).contains($0) } ?? true })
            else { throw AIError.invalidResponse("per-100 values out of range") }
        }
        guard perServing?.kcal.map({ (0...3000).contains($0) }) ?? true else {
            throw AIError.invalidResponse("per-serving calories out of range")
        }
        return self
    }

    /// The same facts the on-device OCR parser produces, so the existing label review,
    /// portion maths and shortcut builder apply unchanged.
    public var labelFacts: LabelNutritionFacts {
        let serving = perServing
        let unit = serving?.servingUnit?.trimmingCharacters(in: .whitespaces).nilIfEmpty
        return LabelNutritionFacts(
            rawText: "AI label read (\(provenance.provider) \(provenance.model))"
                + (notes.map { "\n\($0)" } ?? ""),
            kcalPer100g: per100g?.kcal,
            kcalPer100ml: per100ml?.kcal,
            kcalPerServing: serving?.kcal,
            servingUnit: unit,
            servingAmount: serving?.servingAmount,
            servingItemUnit: unit.flatMap { isGenericServingUnit($0) ? nil : $0 },
            servingSizeGrams: serving?.servingGrams,
            packageSizeGrams: packageGrams,
            packageSizeMilliliters: packageMilliliters,
            packageItemCount: packageItemCount,
            packageItemUnit: packageItemUnit,
            proteinPer100g: per100g?.proteinG ?? per100ml?.proteinG,
            fiberPer100g: per100g?.fibreG ?? per100ml?.fibreG,
            carbsPer100g: per100g?.carbsG ?? per100ml?.carbsG,
            fatPer100g: per100g?.fatG ?? per100ml?.fatG,
            sugarsPer100g: per100g?.sugarsG ?? per100ml?.sugarsG,
            saltPer100g: per100g?.saltG ?? per100ml?.saltG,
            prepared: prepared ?? false)
    }
}

public enum AIError: Error, Equatable, LocalizedError {
    case notConfigured
    case unauthorized
    case server(status: Int, message: String)
    case invalidResponse(String)
    case unsupportedVersion(Int)
    case network(String)

    public var errorDescription: String? {
        switch self {
        case .notConfigured: return "Add the AI service address and token in Settings first."
        case .unauthorized: return "The AI service rejected the app token. Check it in Settings."
        case .server(_, let message): return "The AI service couldn't estimate that: \(message)"
        case .invalidResponse: return "The AI service sent an answer FoodLog couldn't use. Nothing was logged."
        case .unsupportedVersion(let version): return "The AI service speaks contract v\(version); this app needs v1."
        case .network(let message): return "Couldn't reach the AI service (\(message)). Manual and label logging still work."
        }
    }
}

extension EstimatedItem {
    /// A reviewable draft: AI calories are estimates with confidence and provenance.
    public func draft(provenance: AIProvenance, extraNotes: String? = nil) -> FoodDraft {
        let combinedNotes = [notes, extraNotes].compactMap { $0?.nilIfEmpty }.joined(separator: " · ").nilIfEmpty
        return FoodDraft(
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            amount: amount ?? (grams != nil && unit == nil ? grams : nil),
            unit: unit?.nilIfEmpty ?? (grams != nil && amount == nil ? "g" : nil),
            grams: grams, calories: kcal, proteinG: proteinG, fibreG: fibreG, notes: combinedNotes,
            source: .estimate, confidence: confidence,
            nutrientSource: proteinG != nil || fibreG != nil ? .estimate : nil,
            aiProvenance: provenance)
    }
}

extension EstimateResponse {
    public var drafts: [FoodDraft] {
        items.map { $0.draft(provenance: aiProvenance, extraNotes: items.count == 1 ? notes : nil) }
    }

    /// One combined draft for a single typed part (e.g. "curry and rice" → one row).
    public func combinedDraft(named name: String) -> FoodDraft {
        guard items.count > 1 else { return drafts[0] }
        let protein = items.compactMap(\.proteinG)
        let fibre = items.compactMap(\.fibreG)
        let lowest = items.map(\.confidence).max { $0.rank < $1.rank } ?? .low
        return FoodDraft(
            name: name, amount: 1, unit: "portion", calories: items.map(\.kcal).reduce(0, +),
            proteinG: protein.count == items.count ? protein.reduce(0, +) : nil,
            fibreG: fibre.count == items.count ? fibre.reduce(0, +) : nil,
            notes: items.map { "\($0.name) \(Int($0.kcal.rounded())) kcal" }.joined(separator: ", "),
            source: .estimate, confidence: lowest,
            nutrientSource: protein.count == items.count || fibre.count == items.count ? .estimate : nil,
            aiProvenance: aiProvenance)
    }
}

private extension ConfidenceLevel {
    /// Higher is less certain.
    var rank: Int {
        switch self {
        case .high: return 0
        case .medium: return 1
        case .low: return 2
        }
    }
}

/// Minimal HTTP client for the Worker.
public struct AIClient: Sendable {
    public var baseURL: URL
    public var token: String
    public var session: URLSession

    public init(baseURL: URL, token: String, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.token = token
        self.session = session
    }

    public func estimate(text: String?, image: AIImage?, hints: EstimateHints? = nil) async throws -> EstimateResponse {
        let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let body = EstimateRequest(text: trimmed, image: image, hints: hints?.isEmpty == false ? hints : nil)
        return try await post("v1/estimate", body, as: EstimateResponse.self).validated()
    }

    public func readLabel(image: AIImage) async throws -> LabelReadResponse {
        try await post("v1/label", LabelRequest(image: image), as: LabelReadResponse.self).validated()
    }

    public struct Health: Decodable, Sendable {
        public var contractVersion: Int
        public var routes: [String: [String]]
    }

    public func health() async throws -> Health {
        var request = URLRequest(url: baseURL.appendingPathComponent("v1/health"))
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return try await send(request, as: Health.self)
    }

    private func post<Body: Encodable, Response: Decodable>(_ path: String, _ body: Body,
                                                            as type: Response.Type) async throws -> Response {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.timeoutInterval = 90
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONEncoder().encode(body)
        return try await send(request, as: type)
    }

    private func send<Response: Decodable>(_ request: URLRequest, as type: Response.Type) async throws -> Response {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw AIError.network(error.localizedDescription)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status != 401 else { throw AIError.unauthorized }
        guard (200..<300).contains(status) else {
            let message = (try? JSONDecoder().decode(WorkerFailure.self, from: data))?.error.message ?? "HTTP \(status)"
            throw AIError.server(status: status, message: message)
        }
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw AIError.invalidResponse(String(describing: error))
        }
    }
}

private struct WorkerFailure: Decodable {
    struct Detail: Decodable { var message: String }
    var error: Detail
}
