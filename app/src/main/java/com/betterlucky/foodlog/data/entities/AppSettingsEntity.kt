package com.betterlucky.foodlog.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalTime

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey
    val id: Int = FOOD_LOG_SETTINGS_ID,
    val dayBoundaryTime: LocalTime? = null,
    @ColumnInfo(defaultValue = "ITEMS")
    val lastLabelInputMode: String = LAST_LABEL_INPUT_MODE_ITEMS,
    val journalExportUri: String? = null,
    val journalExportDisplayName: String? = null,
    @ColumnInfo(defaultValue = "0")
    val journalIncludeWeight: Boolean = false,
) {
    companion object {
        const val FOOD_LOG_SETTINGS_ID = 1
        const val LAST_LABEL_INPUT_MODE_ITEMS = "ITEMS"
        const val LAST_LABEL_INPUT_MODE_MEASURE = "MEASURE"
    }
}
