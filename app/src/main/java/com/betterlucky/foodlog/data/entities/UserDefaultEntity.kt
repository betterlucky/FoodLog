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
)
