package com.betterlucky.foodlog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.betterlucky.foodlog.data.entities.ProductEntity

@Dao
interface ProductDao {
    @Insert
    suspend fun insert(product: ProductEntity): Long

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getById(id: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ProductEntity?

    @Update
    suspend fun update(product: ProductEntity)
}
