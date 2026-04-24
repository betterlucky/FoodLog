package com.betterlucky.foodlog.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.betterlucky.foodlog.data.db.FoodLogDatabase
import com.betterlucky.foodlog.data.entities.ConfidenceLevel
import com.betterlucky.foodlog.data.entities.EntryKind
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.FoodItemSource
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import com.betterlucky.foodlog.data.entities.RawEntryStatus
import com.betterlucky.foodlog.domain.parser.DeterministicParser
import com.betterlucky.foodlog.util.DateTimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class FoodLogRepositoryInstrumentedTest {
    private lateinit var database: FoodLogDatabase
    private lateinit var repository: FoodLogRepository

    private val today = LocalDate.parse("2026-04-24")
    private val now = Instant.parse("2026-04-24T11:30:00Z")
    private val localTime = LocalTime.parse("12:30")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FoodLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FoodLogRepository(
            database = database,
            parser = DeterministicParser(),
            dateTimeProvider = FakeDateTimeProvider(
                now = now,
                today = today,
                localTime = localTime,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun seedDefaultsCreatesTeaShortcut() = runTest {
        repository.seedDefaults()

        val tea = database.userDefaultDao().getActiveDefault("tea")

        assertEquals("Tea", tea?.name)
        assertEquals(25.0, tea?.calories)
        assertEquals(FoodItemSource.USER_DEFAULT, tea?.source)
        assertEquals(ConfidenceLevel.HIGH, tea?.confidence)
    }

    @Test
    fun teaSubmissionCreatesParsedRawEntryAndFoodItem() = runTest {
        repository.seedDefaults()

        val result = repository.submitText("tea")
        val foodItems = repository.observeFoodItemsForDate(today).first()

        assertTrue(result is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(1, foodItems.size)
        assertEquals("Tea", foodItems.single().name)
        assertEquals(25.0, foodItems.single().calories, 0.001)
        assertEquals(localTime, foodItems.single().consumedTime)
        assertEquals(emptyList<RawEntryEntity>(), repository.observePendingEntries().first())
    }

    @Test
    fun unsupportedDatedSubmissionStaysPendingWithParsedLogDate() = runTest {
        repository.seedDefaults()

        val result = repository.submitText("yesterday curry")
        val pendingEntries = repository.observePendingEntries().first()
        val yesterdayFoodItems = repository.observeFoodItemsForDate(today.minusDays(1)).first()

        assertTrue(result is FoodLogRepository.SubmitResult.Pending)
        assertEquals(1, pendingEntries.size)
        assertEquals(today.minusDays(1), pendingEntries.single().logDate)
        assertEquals("yesterday curry", pendingEntries.single().rawText)
        assertEquals(emptyList<FoodItemEntity>(), yesterdayFoodItems)
    }

    @Test
    fun dailyTotalExcludesVoidedRows() = runTest {
        val rawEntryId = database.rawEntryDao().insert(
            RawEntryEntity(
                createdAt = now,
                logDate = today,
                consumedTime = localTime,
                rawText = "manual test",
                entryKind = EntryKind.TEXT,
                status = RawEntryStatus.PARSED,
            ),
        )
        database.foodItemDao().insert(foodItem(rawEntryId = rawEntryId, calories = 25.0))
        database.foodItemDao().insert(foodItem(rawEntryId = rawEntryId, calories = 999.0, voided = true))

        val total = repository.observeCaloriesForDate(today).first()

        assertEquals(25.0, total, 0.001)
    }

    @Test
    fun legacyExportUsesRoomRowsOnlyAndExcludesVoidedRows() = runTest {
        val rawEntryId = database.rawEntryDao().insert(
            RawEntryEntity(
                createdAt = now,
                logDate = today,
                consumedTime = localTime,
                rawText = "manual test",
                entryKind = EntryKind.TEXT,
                status = RawEntryStatus.PARSED,
            ),
        )
        database.foodItemDao().insert(foodItem(rawEntryId = rawEntryId, name = "Tea"))
        database.foodItemDao().insert(foodItem(rawEntryId = rawEntryId, name = "Old tea", voided = true))

        val csv = repository.exportLegacyHealthCsv(today)

        assertTrue(csv.startsWith("date,time_local,item,quantity,calories_kcal,notes"))
        assertTrue(csv.contains("Tea"))
        assertTrue(!csv.contains("Old tea"))
    }

    private fun foodItem(
        rawEntryId: Long,
        name: String = "Tea",
        calories: Double = 25.0,
        voided: Boolean = false,
    ): FoodItemEntity =
        FoodItemEntity(
            rawEntryId = rawEntryId,
            logDate = today,
            consumedTime = localTime,
            name = name,
            amount = 1.0,
            unit = "cup",
            calories = calories,
            source = FoodItemSource.USER_DEFAULT,
            confidence = ConfidenceLevel.HIGH,
            notes = "test",
            createdAt = now,
            voided = voided,
        )

    private class FakeDateTimeProvider(
        private val now: Instant,
        private val today: LocalDate,
        private val localTime: LocalTime,
    ) : DateTimeProvider {
        override fun nowInstant(): Instant = now

        override fun today(): LocalDate = today

        override fun localTime(): LocalTime = localTime
    }
}
