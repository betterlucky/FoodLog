package com.betterlucky.foodlog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.betterlucky.foodlog.data.entities.ConfidenceLevel
import com.betterlucky.foodlog.data.entities.FoodItemSource
import com.betterlucky.foodlog.data.entities.ShortcutPortionMode
import com.betterlucky.foodlog.data.entities.UserDefaultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDefaultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(default: UserDefaultEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(default: UserDefaultEntity): Long

    @Query(
        """
        UPDATE user_defaults
        SET name = :name,
            calories = :calories,
            unit = :unit,
            notes = :notes,
            source = :source,
            confidence = :confidence,
            active = 1,
            defaultAmount = :defaultAmount,
            portionMode = :portionMode,
            itemUnit = :itemUnit,
            itemSizeAmount = :itemSizeAmount,
            itemSizeUnit = :itemSizeUnit,
            kcalPer100g = :kcalPer100g,
            kcalPer100ml = :kcalPer100ml,
            nutritionBasisName = :nutritionBasisName
        WHERE lookupKey = :lookupKey AND active = 0
        """,
    )
    suspend fun reactivateInactive(
        lookupKey: String,
        name: String,
        calories: Double,
        unit: String,
        notes: String?,
        source: FoodItemSource,
        confidence: ConfidenceLevel,
        defaultAmount: Double?,
        portionMode: ShortcutPortionMode,
        itemUnit: String?,
        itemSizeAmount: Double?,
        itemSizeUnit: String?,
        kcalPer100g: Double?,
        kcalPer100ml: Double?,
        nutritionBasisName: String?,
    ): Int

    @Query("SELECT * FROM user_defaults WHERE lookupKey = :lookupKey AND active = 1")
    suspend fun getActiveDefault(lookupKey: String): UserDefaultEntity?

    @Query("SELECT * FROM user_defaults WHERE active = 1 ORDER BY name ASC")
    fun observeActiveDefaults(): Flow<List<UserDefaultEntity>>

    @Query("UPDATE user_defaults SET active = 0 WHERE lookupKey = :lookupKey")
    suspend fun deactivate(lookupKey: String)

    @Query("SELECT COUNT(*) FROM user_defaults WHERE lookupKey = :lookupKey")
    suspend fun countByLookupKey(lookupKey: String): Int
}
