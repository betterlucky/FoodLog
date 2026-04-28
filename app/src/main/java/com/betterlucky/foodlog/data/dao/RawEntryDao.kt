package com.betterlucky.foodlog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import com.betterlucky.foodlog.data.entities.RawEntryStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime

@Dao
interface RawEntryDao {
    @Insert
    suspend fun insert(entry: RawEntryEntity): Long

    @Query("SELECT * FROM raw_entries WHERE id = :id")
    suspend fun getById(id: Long): RawEntryEntity?

    @Query("UPDATE raw_entries SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: RawEntryStatus)

    @Query(
        """
        UPDATE raw_entries
        SET logDate = :logDate, rawText = :rawText, consumedTime = :consumedTime, notes = :notes
        WHERE id = :id
        """,
    )
    suspend fun updatePendingDetails(
        id: Long,
        logDate: LocalDate,
        rawText: String,
        consumedTime: LocalTime?,
        notes: String?,
    )

    @Query("DELETE FROM raw_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM raw_entries WHERE logDate = :date ORDER BY createdAt ASC")
    fun observeRawEntriesForDate(date: LocalDate): Flow<List<RawEntryEntity>>

    @Query("SELECT * FROM raw_entries WHERE status = :status ORDER BY createdAt DESC")
    fun observeByStatus(status: RawEntryStatus): Flow<List<RawEntryEntity>>

    @Query(
        """
        SELECT * FROM raw_entries
        WHERE status = :status AND logDate = :date
        ORDER BY createdAt DESC
        """,
    )
    fun observeByStatusForDate(
        status: RawEntryStatus,
        date: LocalDate,
    ): Flow<List<RawEntryEntity>>
}
