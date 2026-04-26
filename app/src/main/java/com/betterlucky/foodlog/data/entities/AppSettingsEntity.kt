package com.betterlucky.foodlog.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalTime

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey
    val id: Int = FOOD_LOG_SETTINGS_ID,
    val dayBoundaryTime: LocalTime? = null,
) {
    companion object {
        const val FOOD_LOG_SETTINGS_ID = 1
    }
}
