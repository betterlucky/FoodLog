package com.betterlucky.foodlog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.FoodItemSource
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

data class FoodHistorySuggestionRow(
    val foodItemId: Long,
    val rawText: String,
    val logDate: LocalDate,
    val name: String,
    val amount: Double?,
    val unit: String?,
    val calories: Double,
    val source: FoodItemSource,
    val notes: String?,
)

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
        SELECT * FROM food_items
        WHERE voided = 0
        ORDER BY logDate ASC, consumedTime ASC, createdAt ASC
        """,
    )
    suspend fun getAllActiveFoodItems(): List<FoodItemEntity>

    @Query(
        """
        SELECT
            food_items.id AS foodItemId,
            raw_entries.rawText AS rawText,
            food_items.logDate AS logDate,
            food_items.name AS name,
            food_items.amount AS amount,
            food_items.unit AS unit,
            food_items.calories AS calories,
            food_items.source AS source,
            food_items.notes AS notes
        FROM food_items
        INNER JOIN raw_entries ON raw_entries.id = food_items.rawEntryId
        WHERE food_items.voided = 0
            AND (
                lower(food_items.name) = :query
                OR lower(raw_entries.rawText) = :query
                OR lower(food_items.name) LIKE '%' || :query || '%'
                OR lower(raw_entries.rawText) LIKE '%' || :query || '%'
            )
        ORDER BY food_items.logDate DESC, food_items.createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun findHistorySuggestions(
        query: String,
        limit: Int,
    ): List<FoodHistorySuggestionRow>

    @Query(
        """
        SELECT COALESCE(SUM(calories), 0)
        FROM food_items
        WHERE logDate = :date AND voided = 0
        """,
    )
    fun observeCaloriesForDate(date: LocalDate): Flow<Double>
}
