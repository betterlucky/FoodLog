package com.betterlucky.foodlog.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

interface DateTimeProvider {
    fun nowInstant(): Instant
    fun today(): LocalDate
    fun localTime(): LocalTime
}

class SystemDateTimeProvider(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : DateTimeProvider {
    override fun nowInstant(): Instant = Instant.now()

    override fun today(): LocalDate = LocalDate.now(zoneId)

    override fun localTime(): LocalTime = LocalTime.now(zoneId).withSecond(0).withNano(0)
}
