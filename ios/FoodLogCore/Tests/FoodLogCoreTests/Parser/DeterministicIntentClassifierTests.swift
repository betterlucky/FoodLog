import XCTest
@testable import FoodLogCore

final class DeterministicIntentClassifierTests: XCTestCase {
    private let classifier = DeterministicIntentClassifier()

    func testClassifiesFoodLogs() {
        XCTAssertEqual(classifier.classify("tea"), .foodLog)
        XCTAssertEqual(classifier.classify("apple"), .foodLog)
        XCTAssertEqual(classifier.classify("yesterday curry"), .foodLog)
    }

    func testClassifiesQueries() {
        XCTAssertEqual(classifier.classify("how am I doing today?"), .query)
        XCTAssertEqual(classifier.classify("what have I eaten"), .query)
    }

    func testClassifiesCorrectionsAndExports() {
        XCTAssertEqual(classifier.classify("actually that was 50g"), .correction)
        XCTAssertEqual(classifier.classify("delete the banana"), .correction)
        XCTAssertEqual(classifier.classify("end of day"), .exportCommand)
        XCTAssertEqual(classifier.classify("export today"), .exportCommand)
    }

    func testClassifiesNotesAndUnknown() {
        XCTAssertEqual(classifier.classify("note: felt tired"), .note)
        XCTAssertEqual(classifier.classify("Note bloated"), .note)
        XCTAssertEqual(classifier.classify("   "), .unknown)
        XCTAssertEqual(classifier.classify(""), .unknown)
    }
}
