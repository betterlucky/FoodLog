public struct FoodDayPolicy: Sendable {
    public init() {}

    public func defaultLogDate(
        calendarDate: LocalDate,
        localTime: LocalTime,
        dayBoundaryTime: LocalTime?
    ) -> LocalDate {
        if let dayBoundaryTime, localTime < dayBoundaryTime {
            return calendarDate.minusDays(1)
        }
        return calendarDate
    }
}
