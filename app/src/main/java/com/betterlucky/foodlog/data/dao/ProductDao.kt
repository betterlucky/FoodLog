package com.betterlucky.foodlog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.betterlucky.foodlog.data.entities.ProductEntity

@Dao
interface ProductDao {
    @Insert
    suspend fun insert(product: ProductEntity): Long

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getById(id: Long): ProductEntity?
}
