package com.betterlucky.foodlog.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "raw_entries")
data class RawEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val createdAt: Instant,
    val logDate: LocalDate,
    val consumedTime: LocalTime?,
    val rawText: String,
    val entryKind: EntryKind,
    val status: RawEntryStatus,
    val parseJson: String? = null,
    val notes: String? = null,
)
