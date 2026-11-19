import XCTest
@testable import FoodLogCore

final class FoodDayPolicyTests: XCTestCase {
    private let policy = FoodDayPolicy()
    private let date = LocalDate(iso: "2026-04-24")!

    func testNullBoundaryUsesCalendarDate() {
        XCTAssertEqual(
            policy.defaultLogDate(
                calendarDate: date,
                localTime: LocalTime(iso: "02:30")!,
                dayBoundaryTime: nil
            ),
            date
        )
    }

    func testTimeBeforeBoundaryUsesPreviousDate() {
        XCTAssertEqual(
            policy.defaultLogDate(
                calendarDate: date,
                localTime: LocalTime(iso: "02:30")!,
                dayBoundaryTime: LocalTime(iso: "03:00")!
            ),
            date.minusDays(1)
        )
    }

    func testBoundaryTimeAndLaterUseCalendarDate() {
        let boundary = LocalTime(iso: "03:00")!

        XCTAssertEqual(policy.defaultLogDate(calendarDate: date, localTime: boundary, dayBoundaryTime: boundary), date)
        XCTAssertEqual(
            policy.defaultLogDate(calendarDate: date, localTime: LocalTime(iso: "03:01")!, dayBoundaryTime: boundary),
            date
        )
    }
}
