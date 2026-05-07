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

    @Query("SELECT * FROM user_defaults WHERE lookupKey = :lookupKey AND active = 1")
    suspend fun getActiveDefault(lookupKey: String): UserDefaultEntity?

    @Query("SELECT * FROM user_defaults WHERE active = 1 ORDER BY name ASC")
    fun observeActiveDefaults(): Flow<List<UserDefaultEntity>>

    @Query("UPDATE user_defaults SET active = 0 WHERE lookupKey = :lookupKey")
    suspend fun deactivate(lookupKey: String)

    @Query("SELECT COUNT(*) FROM user_defaults WHERE lookupKey = :lookupKey")
    suspend fun countByLookupKey(lookupKey: String): Int
}
