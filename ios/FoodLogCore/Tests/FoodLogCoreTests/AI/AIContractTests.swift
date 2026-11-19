import XCTest
@testable import FoodLogCore

/// Serves canned responses so the client is tested without a network.
final class StubProtocol: URLProtocol {
    nonisolated(unsafe) static var handler: ((URLRequest) -> (Int, Data))?
    nonisolated(unsafe) static var lastRequest: URLRequest?
    nonisolated(unsafe) static var lastBody: Data?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lastRequest = request
        Self.lastBody = request.httpBody ?? request.httpBodyStream.map { stream in
            stream.open(); defer { stream.close() }
            var data = Data(); var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable { let n = stream.read(&buffer, maxLength: 4096); if n <= 0 { break }; data.append(buffer, count: n) }
            return data
        }
        let (status, data) = Self.handler?(request) ?? (500, Data())
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                                              headerFields: ["Content-Type": "application/json"])!,
                            cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class AIContractTests: XCTestCase {
    /// The contract document's example estimate, verbatim apart from the placeholder model.
    static let estimateJSON = #"""
    {"contractVersion":1,"items":[{"name":"Beef chilli with kidney beans","amount":1,"unit":"bowl","grams":350,
     "kcal":420,"proteinG":32,"fibreG":9,"confidence":"MEDIUM","notes":"Assumed lean mince and no rice"}],
     "per100g":{"kcal":120,"proteinG":9.1,"fibreG":2.6},"notes":"Estimate from description only",
     "provenance":{"provider":"anthropic","model":"test-model","latencyMs":2140}}
    """#

    static let labelJSON = #"""
    {"contractVersion":1,"productName":"Oat granola",
     "per100g":{"kcal":420,"proteinG":10,"fibreG":6,"carbsG":60,"fatG":14,"sugarsG":18,"saltG":0.1},
     "per100ml":null,"perServing":{"kcal":168,"servingGrams":40,"servingAmount":1,"servingUnit":"serving"},
     "packageGrams":500,"packageMilliliters":null,"packageItemCount":null,"packageItemUnit":null,
     "prepared":false,"confidence":"HIGH","notes":null,
     "provenance":{"provider":"openai","model":"test-model","latencyMs":1830}}
    """#

    private var client: AIClient!

    override func setUp() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubProtocol.self]
        client = AIClient(baseURL: URL(string: "https://worker.example/")!, token: "test-token",
                          session: URLSession(configuration: configuration))
    }

    func testEstimateRequestShapeAndDraftMapping() async throws {
        StubProtocol.handler = { _ in (200, Data(Self.estimateJSON.utf8)) }
        let response = try await client.estimate(text: "  homemade chilli, a bowl ", image: nil,
                                                 hints: EstimateHints(batchWeightGrams: 1800, servings: 6))
        let request = try XCTUnwrap(StubProtocol.lastRequest)
        XCTAssertEqual(request.url?.path, "/v1/estimate")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token")
        let body = try JSONSerialization.jsonObject(with: XCTUnwrap(StubProtocol.lastBody)) as! [String: Any]
        XCTAssertEqual(body["contractVersion"] as? Int, 1)
        XCTAssertEqual(body["text"] as? String, "homemade chilli, a bowl")
        XCTAssertNil(body["image"])
        XCTAssertEqual((body["hints"] as? [String: Any])?["servings"] as? Double, 6)

        let draft = try XCTUnwrap(response.drafts.first)
        XCTAssertEqual(draft.calories, 420)
        XCTAssertEqual(draft.grams, 350)
        XCTAssertEqual(draft.source, .estimate)
        XCTAssertEqual(draft.confidence, .medium)
        XCTAssertEqual(draft.nutrientSource, .estimate)
        XCTAssertEqual(draft.aiProvenance, AIProvenance(contractVersion: 1, provider: "anthropic", model: "test-model"))
        XCTAssertEqual(draft.notes, "Assumed lean mince and no rice · Estimate from description only")
    }

    func testEstimatedRowIsLoggedWithProvenance() async throws {
        StubProtocol.handler = { _ in (200, Data(Self.estimateJSON.utf8)) }
        let response = try await client.estimate(text: "chilli", image: nil)
        let store = try FoodLogStore.inMemory(clock: FixedClock("2026-11-19T19:00:00"))
        try store.seedDefaults()
        let logging = LoggingService(store: store)
        let day = LocalDate(iso: "2026-11-19")!
        guard case .needsDetails(let context) = try logging.submit("friend's chilli", selectedDate: day) else {
            return XCTFail("expected pending")
        }
        let result = try logging.resolve(context, drafts: [response.combinedDraft(named: "Friend's chilli")],
                                         consumedTime: nil, saveShortcuts: [])
        let item = try XCTUnwrap(store.foodItem(id: result.itemIds[0]))
        XCTAssertEqual(item.source, .estimate)
        XCTAssertEqual(item.confidence, .medium)
        XCTAssertEqual(item.aiProvenance?.provider, "anthropic")
        XCTAssertEqual(item.nutrientSource, .estimate)
        XCTAssertEqual(item.proteinG, 32)
        XCTAssertTrue(try store.buildJournalCsv(options: .init()).contains(",estimate,"))
    }

    func testCombinedDraftSumsItemsAndKeepsLowestConfidence() throws {
        let response = EstimateResponse(
            contractVersion: 1,
            items: [EstimatedItem(name: "Curry", kcal: 600, proteinG: 30, confidence: .medium),
                    EstimatedItem(name: "Rice", kcal: 250, proteinG: 5, confidence: .low)],
            per100g: nil, notes: nil,
            provenance: AIProvenanceResponse(provider: "p", model: "m", latencyMs: nil))
        let draft = response.combinedDraft(named: "Takeaway curry")
        XCTAssertEqual(draft.calories, 850)
        XCTAssertEqual(draft.proteinG, 35)
        XCTAssertNil(draft.fibreG, "Only summed when every item has it")
        XCTAssertEqual(draft.confidence, .low)
        XCTAssertEqual(draft.notes, "Curry 600 kcal, Rice 250 kcal")
    }

    func testLabelReadMapsToParserFactsAndPortionMaths() async throws {
        StubProtocol.handler = { _ in (200, Data(Self.labelJSON.utf8)) }
        let response = try await client.readLabel(image: AIImage(jpeg: Data([0xFF, 0xD8])))
        XCTAssertEqual(StubProtocol.lastRequest?.url?.path, "/v1/label")
        let body = try JSONSerialization.jsonObject(with: XCTUnwrap(StubProtocol.lastBody)) as! [String: Any]
        XCTAssertEqual((body["image"] as? [String: Any])?["mediaType"] as? String, "image/jpeg")

        let facts = response.labelFacts
        XCTAssertEqual(facts.kcalPer100g, 420)
        XCTAssertEqual(facts.kcalPerServing, 168)
        XCTAssertEqual(facts.servingSizeGrams, 40)
        XCTAssertEqual(facts.proteinPer100g, 10)
        XCTAssertTrue(facts.hasRequiredCalories)
        let portion = LabelPortionResolver.resolve(facts, mode: .measure, amountText: "40g")
        XCTAssertEqual(try XCTUnwrap(portion.calories), 168, accuracy: 0.01)
        XCTAssertEqual(try XCTUnwrap(portion.proteinG), 4, accuracy: 0.01)
    }

    func testRejectsWrongVersionOutOfRangeAndErrors() async throws {
        StubProtocol.handler = { _ in (200, Data(Self.estimateJSON.replacingOccurrences(of: #""contractVersion":1"#, with: #""contractVersion":2"#).utf8)) }
        await assertThrows(.unsupportedVersion(2)) { _ = try await self.client.estimate(text: "x", image: nil) }

        StubProtocol.handler = { _ in (200, Data(Self.estimateJSON.replacingOccurrences(of: #""kcal":420"#, with: #""kcal":9000"#).utf8)) }
        await assertThrows(nil) { _ = try await self.client.estimate(text: "x", image: nil) }

        StubProtocol.handler = { _ in (401, Data()) }
        await assertThrows(.unauthorized) { _ = try await self.client.estimate(text: "x", image: nil) }

        StubProtocol.handler = { _ in (502, Data(#"{"error":{"code":"estimate_failed","message":"all providers failed","attempts":[]}}"#.utf8)) }
        await assertThrows(.server(status: 502, message: "all providers failed")) {
            _ = try await self.client.estimate(text: "x", image: nil)
        }

        StubProtocol.handler = { _ in (200, Data("not json".utf8)) }
        await assertThrows(nil) { _ = try await self.client.readLabel(image: AIImage(jpeg: Data())) }
    }

    private func assertThrows(_ expected: AIError?, _ body: @escaping () async throws -> Void,
                              file: StaticString = #filePath, line: UInt = #line) async {
        do {
            try await body()
            XCTFail("expected an error", file: file, line: line)
        } catch let error as AIError {
            if let expected { XCTAssertEqual(error, expected, file: file, line: line) }
        } catch {
            XCTFail("unexpected \(error)", file: file, line: line)
        }
    }
}
