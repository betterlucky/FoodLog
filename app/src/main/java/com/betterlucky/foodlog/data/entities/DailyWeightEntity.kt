package com.betterlucky.foodlog.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "daily_weights")
data class DailyWeightEntity(
    @PrimaryKey
    val logDate: LocalDate,
    val weightKg: Double,
    val measuredTime: LocalTime,
    val createdAt: Instant,
    val updatedAt: Instant,
)
