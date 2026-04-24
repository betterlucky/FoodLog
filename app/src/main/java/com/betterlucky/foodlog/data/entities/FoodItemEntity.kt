package com.betterlucky.foodlog.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity(
    tableName = "food_items",
    foreignKeys = [
        ForeignKey(
            entity = RawEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["rawEntryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ContainerEntity::class,
            parentColumns = ["id"],
            childColumns = ["containerId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("rawEntryId"),
        Index("productId"),
        Index("containerId"),
        Index("logDate"),
    ],
)
data class FoodItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val rawEntryId: Long,
    val logDate: LocalDate,
    val consumedTime: LocalTime?,
    val name: String,
    val productId: Long? = null,
    val containerId: Long? = null,
    val amount: Double? = null,
    val unit: String? = null,
    val grams: Double? = null,
    val calories: Double,
    val source: FoodItemSource,
    val confidence: ConfidenceLevel,
    val notes: String? = null,
    val createdAt: Instant,
    val voided: Boolean = false,
)
