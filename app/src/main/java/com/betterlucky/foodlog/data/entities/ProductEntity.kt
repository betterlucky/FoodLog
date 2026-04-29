package com.betterlucky.foodlog.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["barcode"], unique = true),
    ],
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val barcode: String? = null,
    val name: String,
    val brand: String? = null,
    val description: String? = null,
    val containerType: String? = null,
    val containerSizeGrams: Double? = null,
    val packageSizeGrams: Double? = null,
    val servingSizeGrams: Double? = null,
    val kcalPer100g: Double? = null,
    val kcalPerServing: Double? = null,
    val proteinPer100g: Double? = null,
    val carbsPer100g: Double? = null,
    val fatPer100g: Double? = null,
    val source: ProductSource,
    val confidence: ConfidenceLevel,
    val externalUrl: String? = null,
    val lastSyncedAt: Instant? = null,
    val lastLoggedGrams: Double? = null,
    val createdAt: Instant,
    val archived: Boolean = false,
)
