import FoodLogCore
import Foundation
import Security
import UIKit

/// Where the app finds its own Worker. The address is a preference; the token lives in the Keychain.
enum AISettings {
    private static let urlKey = "aiWorkerURL"
    private static let tokenAccount = "worker-app-token"

    static var workerURL: String? {
        get { UserDefaults.standard.string(forKey: urlKey) }
        set { UserDefaults.standard.set(newValue, forKey: urlKey) }
    }

    static var token: String? {
        get { Keychain.read(tokenAccount) }
        set { Keychain.write(newValue, account: tokenAccount) }
    }

    /// A client for the saved settings, or nil until both are set.
    static func client() -> AIClient? {
        #if DEBUG
        if CommandLine.arguments.contains("-uiTestAIStub") { return UITestAIStub.client }
        #endif
        guard let text = workerURL, let url = URL(string: text), url.scheme == "https" || url.host == "localhost",
              let token, !token.isEmpty
        else { return nil }
        return AIClient(baseURL: url, token: token)
    }
}

enum Keychain {
    private static let service = "org.lodestone.foodlog.ai"

    static func read(_ account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: account, kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func write(_ value: String?, account: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        guard let value, !value.isEmpty else { return }
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
}

extension AIImage {
    /// JPEG, at most 1600 px on the long edge, as the contract asks.
    init?(image: UIImage, maxDimension: CGFloat = 1600) {
        let size = image.size
        guard size.width > 0, size.height > 0 else { return nil }
        let scale = min(1, maxDimension / max(size.width, size.height))
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let resized = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        guard let data = resized.jpegData(compressionQuality: 0.7) else { return nil }
        self.init(jpeg: data)
    }
}

extension ConfidenceLevel {
    var label: String {
        switch self {
        case .high: return "High confidence"
        case .medium: return "Medium confidence"
        case .low: return "Low confidence"
        }
    }
}

#if DEBUG
/// Canned Worker answers for UI tests, so the AI flows are exercised without a network.
final class UITestAIStub: URLProtocol {
    static let client: AIClient = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UITestAIStub.self]
        return AIClient(baseURL: URL(string: "https://stub.invalid/")!, token: "stub",
                        session: URLSession(configuration: configuration))
    }()

    private static let estimate = #"""
    {"contractVersion":1,"items":[{"name":"Beef chilli","amount":1,"unit":"bowl","grams":350,"kcal":420,
     "proteinG":32,"fibreG":9,"confidence":"MEDIUM","notes":"Assumed lean mince"}],
     "per100g":null,"notes":null,"provenance":{"provider":"stub","model":"stub-model","latencyMs":5}}
    """#
    private static let label = #"""
    {"contractVersion":1,"productName":"Stub granola","per100g":{"kcal":420,"proteinG":10,"fibreG":6,
     "carbsG":null,"fatG":null,"sugarsG":null,"saltG":null},"per100ml":null,
     "perServing":{"kcal":168,"servingGrams":40,"servingAmount":1,"servingUnit":"serving"},
     "packageGrams":500,"packageMilliliters":null,"packageItemCount":null,"packageItemUnit":null,
     "prepared":false,"confidence":"HIGH","notes":null,"provenance":{"provider":"stub","model":"stub-model","latencyMs":5}}
    """#
    private static let health = #"{"contractVersion":1,"routes":{"estimate":["stub:stub-model"],"label":["stub:stub-model"]}}"#

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url?.path ?? ""
        let body = path.hasSuffix("estimate") ? Self.estimate : path.hasSuffix("label") ? Self.label : Self.health
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
                                                              headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
#endif
