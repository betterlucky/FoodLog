package com.betterlucky.foodlog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.betterlucky.foodlog.data.entities.DailyStatusEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface DailyStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(status: DailyStatusEntity)

    @Query("SELECT * FROM daily_statuses WHERE logDate = :date")
    fun observeByDate(date: LocalDate): Flow<DailyStatusEntity?>

    @Query("SELECT * FROM daily_statuses WHERE logDate = :date")
    suspend fun getByDate(date: LocalDate): DailyStatusEntity?
}
