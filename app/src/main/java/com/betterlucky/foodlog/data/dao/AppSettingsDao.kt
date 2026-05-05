package com.betterlucky.foodlog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.betterlucky.foodlog.data.entities.AppSettingsEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalTime

@Dao
interface AppSettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AppSettingsEntity)

    @Query("SELECT * FROM app_settings WHERE id = :id")
    suspend fun getById(id: Int = AppSettingsEntity.FOOD_LOG_SETTINGS_ID): AppSettingsEntity?

    @Query("SELECT * FROM app_settings WHERE id = :id")
    fun observeById(id: Int = AppSettingsEntity.FOOD_LOG_SETTINGS_ID): Flow<AppSettingsEntity?>

    @Query("UPDATE app_settings SET dayBoundaryTime = :dayBoundaryTime WHERE id = :id")
    suspend fun updateDayBoundaryTime(
        dayBoundaryTime: LocalTime?,
        id: Int = AppSettingsEntity.FOOD_LOG_SETTINGS_ID,
    )

    @Query("UPDATE app_settings SET lastLabelInputMode = :lastLabelInputMode WHERE id = :id")
    suspend fun updateLastLabelInputMode(
        lastLabelInputMode: String,
        id: Int = AppSettingsEntity.FOOD_LOG_SETTINGS_ID,
    )
}
