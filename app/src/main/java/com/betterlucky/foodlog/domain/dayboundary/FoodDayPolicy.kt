package com.betterlucky.foodlog.domain.dayboundary

import java.time.LocalDate
import java.time.LocalTime

class FoodDayPolicy {
    fun defaultLogDate(
        calendarDate: LocalDate,
        localTime: LocalTime,
        dayBoundaryTime: LocalTime?,
    ): LocalDate =
        if (dayBoundaryTime != null && localTime.isBefore(dayBoundaryTime)) {
            calendarDate.minusDays(1)
        } else {
            calendarDate
        }
}
