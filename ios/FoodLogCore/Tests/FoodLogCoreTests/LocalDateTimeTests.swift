import XCTest
@testable import FoodLogCore

final class LocalDateTimeTests: XCTestCase {
    func testDateParsingAndArithmetic() {
        let date = LocalDate(iso: "2026-02-28")!
        XCTAssertEqual(date.plusDays(1).iso, "2026-03-01")
        XCTAssertEqual(LocalDate(iso: "2024-03-01")!.minusDays(1).iso, "2024-02-29")
        XCTAssertNil(LocalDate(iso: "2026-02-30"))
        XCTAssertNil(LocalDate(iso: "2026-2-03"))
        XCTAssertTrue(LocalDate(iso: "2026-01-31")! < LocalDate(iso: "2026-02-01")!)
    }

    func testTimeParsingAndFormatting() {
        XCTAssertEqual(LocalTime(iso: "08:05")?.iso, "08:05")
        XCTAssertEqual(LocalTime(iso: "23:59:30")?.iso, "23:59:30")
        XCTAssertNil(LocalTime(iso: "24:00"))
        XCTAssertNil(LocalTime(iso: "8:00"))
        XCTAssertTrue(LocalTime(iso: "05:59")! < LocalTime(iso: "06:00")!)
    }

    func testCodableRoundTrip() throws {
        struct Box: Codable, Equatable { let d: LocalDate; let t: LocalTime? }
        let box = Box(d: LocalDate(iso: "2026-04-23")!, t: LocalTime(iso: "16:00"))
        let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
        let data = try encoder.encode(box)
        XCTAssertEqual(String(data: data, encoding: .utf8), #"{"d":"2026-04-23","t":"16:00"}"#)
        XCTAssertEqual(try JSONDecoder().decode(Box.self, from: data), box)
    }
}
