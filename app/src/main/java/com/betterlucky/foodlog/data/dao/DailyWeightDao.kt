package com.betterlucky.foodlog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.betterlucky.foodlog.data.entities.DailyWeightEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface DailyWeightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(weight: DailyWeightEntity)

    @Query("SELECT * FROM daily_weights WHERE logDate = :date")
    fun observeByDate(date: LocalDate): Flow<DailyWeightEntity?>

    @Query("SELECT * FROM daily_weights WHERE logDate = :date")
    suspend fun getByDate(date: LocalDate): DailyWeightEntity?
}
