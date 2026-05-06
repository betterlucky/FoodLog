package com.betterlucky.foodlog.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_defaults")
data class UserDefaultEntity(
    @PrimaryKey
    val trigger: String,
    val name: String,
    val calories: Double,
    val unit: String,
    val notes: String? = null,
    val source: FoodItemSource = FoodItemSource.USER_DEFAULT,
    val confidence: ConfidenceLevel = ConfidenceLevel.HIGH,
    val active: Boolean = true,
    val defaultAmount: Double? = null,
    val portionMode: ShortcutPortionMode = ShortcutPortionMode.PLAIN,
    val itemUnit: String? = null,
    val itemSizeAmount: Double? = null,
    val itemSizeUnit: String? = null,
    val kcalPer100g: Double? = null,
    val kcalPer100ml: Double? = null,
    val nutritionBasisName: String? = null,
)
