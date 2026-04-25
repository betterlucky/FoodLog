package com.betterlucky.foodlog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface FoodItemDao {
    @Insert
    suspend fun insert(item: FoodItemEntity): Long

    @Update
    suspend fun update(item: FoodItemEntity)

    @Query("SELECT * FROM food_items WHERE id = :id")
    suspend fun getById(id: Long): FoodItemEntity?

    @Query("DELETE FROM food_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        SELECT * FROM food_items
        WHERE logDate = :date AND voided = 0
        ORDER BY consumedTime ASC, createdAt ASC
        """,
    )
    fun observeFoodItemsForDate(date: LocalDate): Flow<List<FoodItemEntity>>

    @Query(
        """
        SELECT * FROM food_items
        WHERE logDate BETWEEN :startDate AND :endDate AND voided = 0
        ORDER BY logDate ASC, consumedTime ASC, createdAt ASC
        """,
    )
    suspend fun getActiveFoodItemsBetween(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<FoodItemEntity>

    @Query(
        """
        SELECT COALESCE(SUM(calories), 0)
        FROM food_items
        WHERE logDate = :date AND voided = 0
        """,
    )
    fun observeCaloriesForDate(date: LocalDate): Flow<Double>
}
