package com.betterlucky.foodlog.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(tableName = "daily_statuses")
data class DailyStatusEntity(
    @PrimaryKey
    val logDate: LocalDate,
    val legacyExportedAt: Instant? = null,
    val auditExportedAt: Instant? = null,
    val lastFoodChangedAt: Instant? = null,
    val legacyExportFileName: String? = null,
    val auditExportFileName: String? = null,
)
