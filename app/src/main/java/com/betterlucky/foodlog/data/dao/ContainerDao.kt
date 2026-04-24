package com.betterlucky.foodlog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.betterlucky.foodlog.data.entities.ContainerEntity

@Dao
interface ContainerDao {
    @Insert
    suspend fun insert(container: ContainerEntity): Long

    @Query("SELECT * FROM containers WHERE id = :id")
    suspend fun getById(id: Long): ContainerEntity?
}
