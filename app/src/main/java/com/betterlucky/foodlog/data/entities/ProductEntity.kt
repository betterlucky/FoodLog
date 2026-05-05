package com.betterlucky.foodlog.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val brand: String? = null,
    val description: String? = null,
    val containerType: String? = null,
    val containerSizeGrams: Double? = null,
    val packageSizeGrams: Double? = null,
    val packageItemCount: Double? = null,
    val servingSizeGrams: Double? = null,
    val servingUnit: String? = null,
    val kcalPer100g: Double? = null,
    val kcalPerServing: Double? = null,
    val proteinPer100g: Double? = null,
    val carbsPer100g: Double? = null,
    val fatPer100g: Double? = null,
    val fiberPer100g: Double? = null,
    val sugarsPer100g: Double? = null,
    val saltPer100g: Double? = null,
    val source: ProductSource,
    val confidence: ConfidenceLevel,
    val lastLoggedGrams: Double? = null,
    val createdAt: Instant,
    val archived: Boolean = false,
)
