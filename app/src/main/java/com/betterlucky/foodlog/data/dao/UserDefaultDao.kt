package com.betterlucky.foodlog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.betterlucky.foodlog.data.entities.UserDefaultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDefaultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(default: UserDefaultEntity)

    @Query("SELECT * FROM user_defaults WHERE trigger = :trigger AND active = 1")
    suspend fun getActiveDefault(trigger: String): UserDefaultEntity?

    @Query("SELECT * FROM user_defaults WHERE active = 1 ORDER BY trigger ASC")
    fun observeActiveDefaults(): Flow<List<UserDefaultEntity>>

    @Query("UPDATE user_defaults SET active = 0 WHERE trigger = :trigger")
    suspend fun deactivate(trigger: String)

    @Query("SELECT COUNT(*) FROM user_defaults WHERE trigger = :trigger")
    suspend fun countByTrigger(trigger: String): Int
}
