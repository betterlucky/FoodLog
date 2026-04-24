package com.betterlucky.foodlog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.betterlucky.foodlog.data.entities.ProductPhotoEntity

@Dao
interface ProductPhotoDao {
    @Insert
    suspend fun insert(photo: ProductPhotoEntity): Long

    @Query("SELECT * FROM product_photos WHERE productId = :productId ORDER BY uploadedAt DESC")
    suspend fun getForProduct(productId: Long): List<ProductPhotoEntity>
}
