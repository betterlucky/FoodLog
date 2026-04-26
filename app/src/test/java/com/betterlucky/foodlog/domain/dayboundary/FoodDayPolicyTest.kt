package com.betterlucky.foodlog.domain.dayboundary

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class FoodDayPolicyTest {
    private val policy = FoodDayPolicy()
    private val date = LocalDate.parse("2026-04-24")

    @Test
    fun nullBoundaryUsesCalendarDate() {
        assertEquals(
            date,
            policy.defaultLogDate(
                calendarDate = date,
                localTime = LocalTime.parse("02:30"),
                dayBoundaryTime = null,
            ),
        )
    }

    @Test
    fun timeBeforeBoundaryUsesPreviousDate() {
        assertEquals(
            date.minusDays(1),
            policy.defaultLogDate(
                calendarDate = date,
                localTime = LocalTime.parse("02:30"),
                dayBoundaryTime = LocalTime.parse("03:00"),
            ),
        )
    }

    @Test
    fun boundaryTimeAndLaterUseCalendarDate() {
        val boundary = LocalTime.parse("03:00")

        assertEquals(date, policy.defaultLogDate(date, boundary, boundary))
        assertEquals(date, policy.defaultLogDate(date, LocalTime.parse("03:01"), boundary))
    }
}
