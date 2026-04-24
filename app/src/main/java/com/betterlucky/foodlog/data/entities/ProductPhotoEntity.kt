package com.betterlucky.foodlog.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "product_photos",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("productId")],
)
data class ProductPhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val productId: Long? = null,
    val localUri: String,
    val uploadedAt: Instant,
    val extractedJson: String? = null,
    val status: ProductPhotoStatus,
)
