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
import com.betterlucky.foodlog.data.entities.UserDefaultEntity
import com.betterlucky.foodlog.domain.intent.DeterministicIntentClassifier
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
    private lateinit var dateTimeProvider: MutableDateTimeProvider

    private val today = LocalDate.parse("2026-04-24")
    private val now = Instant.parse("2026-04-24T11:30:00Z")
    private val localTime = LocalTime.parse("12:30")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FoodLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dateTimeProvider = MutableDateTimeProvider(
            now = now,
            today = today,
            localTime = localTime,
        )
        repository = FoodLogRepository(
            database = database,
            intentClassifier = DeterministicIntentClassifier(),
            parser = DeterministicParser(),
            dateTimeProvider = dateTimeProvider,
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
    fun teaQuantityMultipliesAmountAndCalories() = runTest {
        repository.seedDefaults()

        val result = repository.submitText("2 teas")
        val foodItem = repository.observeFoodItemsForDate(today).first().single()
        val total = repository.observeCaloriesForDate(today).first()
        val csv = repository.exportLegacyHealthCsv(today).csv

        assertTrue(result is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(2.0, foodItem.amount ?: 0.0, 0.001)
        assertEquals(50.0, foodItem.calories, 0.001)
        assertEquals(50.0, total, 0.001)
        assertTrue(csv.contains("2 cups,50"))
    }

    @Test
    fun compoundShortcutSubmissionCreatesOneRawEntryAndMultipleFoodItems() = runTest {
        repository.seedDefaults()
        database.userDefaultDao().upsert(default(trigger = "banana", name = "Banana", calories = 105.0, unit = "each"))
        database.userDefaultDao().upsert(default(trigger = "satsuma", name = "Satsuma", calories = 35.0, unit = "each"))

        val result = repository.submitText("banana, satsuma and tea")
        val foodItems = repository.observeFoodItemsForDate(today).first()
        val rawEntry = database.rawEntryDao().getById(result.rawEntryId)
        val total = repository.observeCaloriesForDate(today).first()

        assertTrue(result is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(3, (result as FoodLogRepository.SubmitResult.Parsed).foodItemIds.size)
        assertEquals(RawEntryStatus.PARSED, rawEntry?.status)
        assertEquals(listOf("Banana", "Satsuma", "Tea"), foodItems.map { it.name }.sorted())
        assertEquals(listOf(result.rawEntryId), foodItems.map { it.rawEntryId }.distinct())
        assertEquals(165.0, total, 0.001)
    }

    @Test
    fun compoundSubmissionWithUnknownPartStaysPendingAndCreatesNoFoodItems() = runTest {
        repository.seedDefaults()

        val result = repository.submitText("tea and curry")
        val pendingEntries = repository.observePendingEntriesForDate(today).first()
        val foodItems = repository.observeFoodItemsForDate(today).first()
        val rawEntry = database.rawEntryDao().getById(result.rawEntryId)

        assertTrue(result is FoodLogRepository.SubmitResult.Pending)
        assertEquals(listOf("tea and curry"), pendingEntries.map { it.rawText })
        assertEquals(RawEntryStatus.PENDING, rawEntry?.status)
        assertEquals(emptyList<FoodItemEntity>(), foodItems)
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
    fun pendingEntriesCanBeObservedForSelectedDate() = runTest {
        repository.seedDefaults()

        repository.submitText("yesterday curry")
        repository.submitText("apple")

        val todayPending = repository.observePendingEntriesForDate(today).first()
        val yesterdayPending = repository.observePendingEntriesForDate(today.minusDays(1)).first()

        assertEquals(listOf("apple"), todayPending.map { it.rawText })
        assertEquals(listOf("yesterday curry"), yesterdayPending.map { it.rawText })
    }

    @Test
    fun unprefixedEarlyMorningSubmissionUsesConfiguredPreviousFoodDay() = runTest {
        repository.seedDefaults()
        repository.setDayBoundaryTime(LocalTime.parse("03:00"))
        dateTimeProvider.localTime = LocalTime.parse("02:30")

        val result = repository.submitText("tea")
        val previousDayFoodItems = repository.observeFoodItemsForDate(today.minusDays(1)).first()
        val todayFoodItems = repository.observeFoodItemsForDate(today).first()
        val rawEntry = database.rawEntryDao().getById(result.rawEntryId)

        assertTrue(result is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(today.minusDays(1), result.logDate)
        assertEquals(today.minusDays(1), rawEntry?.logDate)
        assertEquals(LocalTime.parse("02:30"), rawEntry?.consumedTime)
        assertEquals(listOf("Tea"), previousDayFoodItems.map { it.name })
        assertEquals(emptyList<FoodItemEntity>(), todayFoodItems)
    }

    @Test
    fun explicitDatePrefixOverridesConfiguredFoodDayBoundary() = runTest {
        repository.seedDefaults()
        repository.setDayBoundaryTime(LocalTime.parse("03:00"))
        dateTimeProvider.localTime = LocalTime.parse("02:30")

        val result = repository.submitText("today tea")
        val todayFoodItems = repository.observeFoodItemsForDate(today).first()
        val previousDayFoodItems = repository.observeFoodItemsForDate(today.minusDays(1)).first()

        assertTrue(result is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(today, result.logDate)
        assertEquals(listOf("Tea"), todayFoodItems.map { it.name })
        assertEquals(emptyList<FoodItemEntity>(), previousDayFoodItems)
    }

    @Test
    fun pendingEntryCanBeResolvedWithManualCalories() = runTest {
        repository.seedDefaults()

        val pendingResult = repository.submitText("apple")
        val rawEntryId = pendingResult.rawEntryId
        val resolveResult = repository.resolvePendingEntryManually(
            rawEntryId = rawEntryId,
            name = "Apple",
            amount = 1.0,
            unit = "medium",
            calories = 95.0,
            notes = "User estimate",
        )
        val pendingEntries = repository.observePendingEntriesForDate(today).first()
        val foodItem = repository.observeFoodItemsForDate(today).first().single()
        val rawEntry = database.rawEntryDao().getById(rawEntryId)
        val csv = repository.exportLegacyHealthCsv(today).csv

        assertTrue(resolveResult is FoodLogRepository.ManualResolveResult.Resolved)
        assertEquals(emptyList<RawEntryEntity>(), pendingEntries)
        assertEquals(RawEntryStatus.PARSED, rawEntry?.status)
        assertEquals("Apple", foodItem.name)
        assertEquals(95.0, foodItem.calories, 0.001)
        assertEquals(FoodItemSource.MANUAL_OVERRIDE, foodItem.source)
        assertEquals(ConfidenceLevel.HIGH, foodItem.confidence)
        assertTrue(csv.contains("2026-04-24,12:30,Apple,1 medium,95,User estimate"))
    }

    @Test
    fun resolvedPendingEntryCanBeSavedAsShortcutForFutureSubmissions() = runTest {
        repository.seedDefaults()

        val pendingResult = repository.submitText("toast")
        val resolveResult = repository.resolvePendingEntryManually(
            rawEntryId = pendingResult.rawEntryId,
            name = "Toast",
            amount = 2.0,
            unit = "slice",
            calories = 190.0,
            notes = "Manual estimate",
            saveAsDefault = true,
        )
        val default = database.userDefaultDao().getActiveDefault("toast")
        val nextResult = repository.submitText("toast")
        val foodItems = repository.observeFoodItemsForDate(today).first()

        assertTrue(resolveResult is FoodLogRepository.ManualResolveResult.Resolved)
        assertEquals("toast", (resolveResult as FoodLogRepository.ManualResolveResult.Resolved).savedDefaultTrigger)
        assertEquals("Toast", default?.name)
        assertEquals(95.0, default?.calories ?: 0.0, 0.001)
        assertEquals("slice", default?.unit)
        assertTrue(nextResult is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(listOf(190.0, 95.0), foodItems.map { it.calories })
        assertEquals(emptyList<RawEntryEntity>(), repository.observePendingEntriesForDate(today).first())
    }

    @Test
    fun manualResolutionDoesNotSaveShortcutWhenUnchecked() = runTest {
        repository.seedDefaults()

        val pendingResult = repository.submitText("pear")
        repository.resolvePendingEntryManually(
            rawEntryId = pendingResult.rawEntryId,
            name = "Pear",
            amount = 1.0,
            unit = "medium",
            calories = 100.0,
            notes = null,
            saveAsDefault = false,
        )
        val nextResult = repository.submitText("pear")

        assertEquals(null, database.userDefaultDao().getActiveDefault("pear"))
        assertTrue(nextResult is FoodLogRepository.SubmitResult.Pending)
    }

    @Test
    fun pendingEntryCanBeRemovedWithHardDelete() = runTest {
        repository.seedDefaults()

        val pendingResult = repository.submitText("apple")
        val removeResult = repository.removePendingEntry(pendingResult.rawEntryId)
        val pendingEntries = repository.observePendingEntriesForDate(today).first()
        val rawEntry = database.rawEntryDao().getById(pendingResult.rawEntryId)

        assertEquals(FoodLogRepository.PendingEntryRemoveResult.Removed, removeResult)
        assertEquals(emptyList<RawEntryEntity>(), pendingEntries)
        assertEquals(null, rawEntry)
    }

    @Test
    fun pendingEntryCanBeEditedAndKeptPending() = runTest {
        repository.seedDefaults()

        val pendingResult = repository.submitText("banana cake")
        val updateResult = repository.updatePendingEntry(
            rawEntryId = pendingResult.rawEntryId,
            rawText = "Banana cake slice",
            amount = 1.0,
            unit = "slice",
            calories = null,
            notes = "Need calories later",
        )
        val pendingEntries = repository.observePendingEntriesForDate(today).first()
        val foodItems = repository.observeFoodItemsForDate(today).first()
        val rawEntry = database.rawEntryDao().getById(pendingResult.rawEntryId)

        assertEquals(FoodLogRepository.PendingEntryUpdateResult.Updated, updateResult)
        assertEquals(listOf("Banana cake slice"), pendingEntries.map { it.rawText })
        assertEquals(RawEntryStatus.PENDING, rawEntry?.status)
        assertEquals("Draft quantity: 1 slice\nNeed calories later", rawEntry?.notes)
        assertEquals(emptyList<FoodItemEntity>(), foodItems)
    }

    @Test
    fun pendingEntryEditRerunsParserAndResolvesShortcutWhenNoManualCalories() = runTest {
        repository.seedDefaults()

        val pendingResult = repository.submitText("teklfhjl")
        val updateResult = repository.updatePendingEntry(
            rawEntryId = pendingResult.rawEntryId,
            rawText = "tea",
            amount = null,
            unit = null,
            calories = null,
            notes = null,
        )
        val pendingEntries = repository.observePendingEntriesForDate(today).first()
        val foodItem = repository.observeFoodItemsForDate(today).first().single()
        val rawEntry = database.rawEntryDao().getById(pendingResult.rawEntryId)

        assertTrue(updateResult is FoodLogRepository.PendingEntryUpdateResult.Parsed)
        assertEquals(emptyList<RawEntryEntity>(), pendingEntries)
        assertEquals(RawEntryStatus.PARSED, rawEntry?.status)
        assertEquals("tea", rawEntry?.rawText)
        assertEquals("Tea", foodItem.name)
        assertEquals(25.0, foodItem.calories, 0.001)
    }

    @Test
    fun parsedEntryCannotBeRemovedAsPending() = runTest {
        repository.seedDefaults()

        val parsedResult = repository.submitText("tea")
        val removeResult = repository.removePendingEntry(parsedResult.rawEntryId)
        val rawEntry = database.rawEntryDao().getById(parsedResult.rawEntryId)

        assertEquals(FoodLogRepository.PendingEntryRemoveResult.NotPending, removeResult)
        assertEquals(RawEntryStatus.PARSED, rawEntry?.status)
    }

    @Test
    fun manualAddCreatesLoggedFoodItemUsingCurrentTimeWhenBlank() = runTest {
        repository.seedDefaults()

        val addResult = repository.addFoodItemManually(
            logDate = today,
            name = "Banana",
            amount = 1.0,
            unit = "medium",
            calories = 105.0,
            consumedTime = null,
            notes = "Manual estimate",
        )
        val foodItem = repository.observeFoodItemsForDate(today).first().single()
        val rawEntry = database.rawEntryDao().getById(
            (addResult as FoodLogRepository.ManualAddResult.Added).rawEntryId,
        )
        val total = repository.observeCaloriesForDate(today).first()
        val csv = repository.exportLegacyHealthCsv(today).csv

        assertEquals(today, addResult.logDate)
        assertEquals(RawEntryStatus.PARSED, rawEntry?.status)
        assertEquals("Manual entry: Banana", rawEntry?.rawText)
        assertEquals(localTime, rawEntry?.consumedTime)
        assertEquals("Banana", foodItem.name)
        assertEquals(localTime, foodItem.consumedTime)
        assertEquals(FoodItemSource.MANUAL_OVERRIDE, foodItem.source)
        assertEquals(105.0, total, 0.001)
        assertTrue(csv.contains("2026-04-24,12:30,Banana,1 medium,105,Manual estimate"))
    }

    @Test
    fun manualAddCanSaveShortcutForFutureSubmissions() = runTest {
        repository.seedDefaults()

        val addResult = repository.addFoodItemManually(
            logDate = today,
            name = "Greek yogurt",
            amount = 2.0,
            unit = "pot",
            calories = 180.0,
            consumedTime = LocalTime.parse("09:15"),
            notes = "Manual default",
            saveAsDefault = true,
        )
        val default = database.userDefaultDao().getActiveDefault("greek yogurt")
        val nextResult = repository.submitText("greek yogurt")
        val foodItems = repository.observeFoodItemsForDate(today).first()

        assertTrue(addResult is FoodLogRepository.ManualAddResult.Added)
        assertEquals("greek yogurt", (addResult as FoodLogRepository.ManualAddResult.Added).savedDefaultTrigger)
        assertEquals("Greek yogurt", default?.name)
        assertEquals(90.0, default?.calories ?: 0.0, 0.001)
        assertEquals("pot", default?.unit)
        assertTrue(nextResult is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(listOf(180.0, 90.0), foodItems.map { it.calories })
    }

    @Test
    fun dailyWeightDoesNotAffectCalorieTotalAndExportsWeightRow() = runTest {
        val result = repository.upsertDailyWeight(
            logDate = today,
            weightKg = 82.6,
            measuredTime = LocalTime.parse("07:15"),
        )
        val total = repository.observeCaloriesForDate(today).first()
        val weight = repository.observeDailyWeightForDate(today).first()
        val csv = repository.exportLegacyHealthCsv(today).csv
        val status = database.dailyStatusDao().observeByDate(today).first()

        assertEquals(FoodLogRepository.DailyWeightResult.Saved, result)
        assertEquals(0.0, total, 0.001)
        assertEquals(82.6, weight?.weightKg ?: 0.0, 0.001)
        assertTrue(csv.contains("2026-04-24,07:15,weight,82.6 kg,,Recorded weight"))
        assertEquals(now, status?.lastFoodChangedAt)
    }

    @Test
    fun dailyWeightBlankTimeUsesCurrentTimeAndCanBeUpdated() = runTest {
        repository.upsertDailyWeight(
            logDate = today,
            weightKg = 82.0,
            measuredTime = null,
        )
        val firstWeight = repository.observeDailyWeightForDate(today).first()
        dateTimeProvider.now = Instant.parse("2026-04-24T12:30:00Z")
        val updateResult = repository.upsertDailyWeight(
            logDate = today,
            weightKg = 83.0,
            measuredTime = LocalTime.parse("08:10"),
        )
        val weight = repository.observeDailyWeightForDate(today).first()

        assertEquals(FoodLogRepository.DailyWeightResult.Saved, updateResult)
        assertEquals(localTime, firstWeight?.measuredTime)
        assertEquals(83.0, weight?.weightKg ?: 0.0, 0.001)
        assertEquals(LocalTime.parse("08:10"), weight?.measuredTime)
        assertEquals(now, weight?.createdAt)
        assertEquals(Instant.parse("2026-04-24T12:30:00Z"), weight?.updatedAt)
    }

    @Test
    fun savedShortcutCanBeUpdated() = runTest {
        repository.seedDefaults()
        val pendingResult = repository.submitText("toast")
        repository.resolvePendingEntryManually(
            rawEntryId = pendingResult.rawEntryId,
            name = "Toast",
            amount = 1.0,
            unit = "slice",
            calories = 95.0,
            notes = "Initial",
            saveAsDefault = true,
        )

        val updateResult = repository.updateDefault(
            trigger = "toast",
            name = "Buttered toast",
            calories = 130.0,
            unit = "slice",
            notes = "Edited default",
        )
        val default = database.userDefaultDao().getActiveDefault("toast")
        repository.submitText("toast")
        val latestFoodItem = repository.observeFoodItemsForDate(today).first().last()

        assertEquals(FoodLogRepository.DefaultUpdateResult.Updated, updateResult)
        assertEquals("Buttered toast", default?.name)
        assertEquals(130.0, default?.calories ?: 0.0, 0.001)
        assertEquals("Edited default", default?.notes)
        assertEquals("Buttered toast", latestFoodItem.name)
        assertEquals(130.0, latestFoodItem.calories, 0.001)
    }

    @Test
    fun savedShortcutCanBeDeactivated() = runTest {
        repository.seedDefaults()
        val pendingResult = repository.submitText("toast")
        repository.resolvePendingEntryManually(
            rawEntryId = pendingResult.rawEntryId,
            name = "Toast",
            amount = 1.0,
            unit = "slice",
            calories = 95.0,
            notes = null,
            saveAsDefault = true,
        )

        repository.deactivateDefault("toast")
        val nextResult = repository.submitText("toast")
        val activeDefaults = repository.observeActiveDefaults().first()

        assertEquals(null, database.userDefaultDao().getActiveDefault("toast"))
        assertTrue(nextResult is FoodLogRepository.SubmitResult.Pending)
        assertEquals(listOf("tea"), activeDefaults.map { it.trigger })
    }

    @Test
    fun manualResolutionRejectsAlreadyParsedEntry() = runTest {
        repository.seedDefaults()

        val parsedResult = repository.submitText("tea")
        val resolveResult = repository.resolvePendingEntryManually(
            rawEntryId = parsedResult.rawEntryId,
            name = "Tea override",
            amount = null,
            unit = null,
            calories = 30.0,
            notes = null,
        )
        val foodItems = repository.observeFoodItemsForDate(today).first()

        assertEquals(FoodLogRepository.ManualResolveResult.NotPending, resolveResult)
        assertEquals(1, foodItems.size)
        assertEquals("Tea", foodItems.single().name)
    }

    @Test
    fun loggedFoodItemCanBeEdited() = runTest {
        repository.seedDefaults()
        val result = repository.submitText("tea") as FoodLogRepository.SubmitResult.Parsed

        val updateResult = repository.updateFoodItem(
            id = result.foodItemId,
            name = "Large tea",
            amount = 1.0,
            unit = "mug",
            calories = 35.0,
            consumedTime = LocalTime.parse("08:45"),
            notes = "Edited today",
        )
        val foodItem = repository.observeFoodItemsForDate(today).first().single()
        val total = repository.observeCaloriesForDate(today).first()
        val csv = repository.exportLegacyHealthCsv(today).csv

        assertEquals(FoodLogRepository.FoodItemUpdateResult.Updated, updateResult)
        assertEquals("Large tea", foodItem.name)
        assertEquals("mug", foodItem.unit)
        assertEquals(LocalTime.parse("08:45"), foodItem.consumedTime)
        assertEquals(35.0, foodItem.calories, 0.001)
        assertEquals("Edited today", foodItem.notes)
        assertEquals(35.0, total, 0.001)
        assertTrue(csv.contains("2026-04-24,08:45,Large tea,1 mug,35,Edited today"))
    }

    @Test
    fun loggedFoodItemCanBeRemovedWithHardDelete() = runTest {
        repository.seedDefaults()
        val result = repository.submitText("tea") as FoodLogRepository.SubmitResult.Parsed

        val removeResult = repository.removeFoodItem(result.foodItemId)
        val rawEntry = database.rawEntryDao().getById(result.rawEntryId)
        val foodItems = repository.observeFoodItemsForDate(today).first()
        val total = repository.observeCaloriesForDate(today).first()
        val csv = repository.exportLegacyHealthCsv(today).csv

        assertEquals(FoodLogRepository.FoodItemRemoveResult.Removed, removeResult)
        assertEquals(RawEntryStatus.PARSED, rawEntry?.status)
        assertEquals(null, database.foodItemDao().getById(result.foodItemId))
        assertEquals(emptyList<FoodItemEntity>(), foodItems)
        assertEquals(0.0, total, 0.001)
        assertTrue(!csv.contains("Tea"))
    }

    @Test
    fun queryDoesNotBecomePendingFoodCleanup() = runTest {
        repository.seedDefaults()

        val result = repository.submitText("how am I doing today?")
        val pendingEntries = repository.observePendingEntriesForDate(today).first()
        val rawEntries = database.rawEntryDao().observeRawEntriesForDate(today).first()

        assertTrue(result is FoodLogRepository.SubmitResult.NonFood)
        assertEquals(emptyList<RawEntryEntity>(), pendingEntries)
        assertEquals(EntryKind.QUERY, rawEntries.single().entryKind)
        assertEquals(RawEntryStatus.PARSED, rawEntries.single().status)
    }

    @Test
    fun exportCommandAndCorrectionDoNotBecomePendingFoodCleanup() = runTest {
        repository.seedDefaults()

        repository.submitText("end of day")
        repository.submitText("actually that was 50g")

        val pendingEntries = repository.observePendingEntriesForDate(today).first()
        val rawEntries = database.rawEntryDao().observeRawEntriesForDate(today).first()

        assertEquals(emptyList<RawEntryEntity>(), pendingEntries)
        assertEquals(
            listOf(EntryKind.EXPORT_COMMAND, EntryKind.CORRECTION),
            rawEntries.map { it.entryKind },
        )
        assertEquals(
            listOf(RawEntryStatus.PARSED, RawEntryStatus.NEEDS_REVIEW),
            rawEntries.map { it.status },
        )
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

        val csv = repository.exportLegacyHealthCsv(today).csv

        assertTrue(csv.startsWith("date,time_local,item,quantity,calories_kcal,notes"))
        assertTrue(csv.contains("Tea"))
        assertTrue(!csv.contains("Old tea"))
    }

    @Test
    fun exportsMarkDailyStatus() = runTest {
        repository.seedDefaults()
        repository.submitText("tea")

        val healthMonitorExport = repository.exportLegacyHealthCsv(today)
        var status = database.dailyStatusDao().observeByDate(today).first()

        assertEquals("food_log_2026-04-24.csv", healthMonitorExport.fileName)
        assertEquals(now, status?.legacyExportedAt)
        assertEquals("food_log_2026-04-24.csv", status?.legacyExportFileName)
        assertEquals(null, status?.auditExportedAt)
        assertEquals(null, status?.auditExportFileName)

        val auditExport = repository.exportAuditCsv(today)
        status = database.dailyStatusDao().observeByDate(today).first()

        assertEquals("foodlog-audit-2026-04-24.csv", auditExport.fileName)
        assertEquals(now, status?.legacyExportedAt)
        assertEquals("food_log_2026-04-24.csv", status?.legacyExportFileName)
        assertEquals(now, status?.auditExportedAt)
        assertEquals("foodlog-audit-2026-04-24.csv", status?.auditExportFileName)
    }

    @Test
    fun foodChangesAfterExportMarkDayAsChangedSinceExport() = runTest {
        repository.seedDefaults()
        val parsedResult = repository.submitText("tea") as FoodLogRepository.SubmitResult.Parsed

        dateTimeProvider.now = Instant.parse("2026-04-24T12:00:00Z")
        repository.exportLegacyHealthCsv(today).csv

        dateTimeProvider.now = Instant.parse("2026-04-24T12:30:00Z")
        repository.updateFoodItem(
            id = parsedResult.foodItemId,
            name = "Edited tea",
            amount = 1.0,
            unit = "cup",
            calories = 30.0,
            consumedTime = localTime,
            notes = null,
        )
        var status = database.dailyStatusDao().observeByDate(today).first()

        assertEquals(Instant.parse("2026-04-24T12:00:00Z"), status?.legacyExportedAt)
        assertEquals(Instant.parse("2026-04-24T12:30:00Z"), status?.lastFoodChangedAt)

        dateTimeProvider.now = Instant.parse("2026-04-24T13:00:00Z")
        repository.exportLegacyHealthCsv(today).csv
        status = database.dailyStatusDao().observeByDate(today).first()

        assertEquals(Instant.parse("2026-04-24T13:00:00Z"), status?.legacyExportedAt)
        assertEquals(Instant.parse("2026-04-24T12:30:00Z"), status?.lastFoodChangedAt)
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

    private fun default(
        trigger: String,
        name: String,
        calories: Double,
        unit: String,
    ): UserDefaultEntity =
        UserDefaultEntity(
            trigger = trigger,
            name = name,
            calories = calories,
            unit = unit,
        )

    private class MutableDateTimeProvider(
        var now: Instant,
        var today: LocalDate,
        var localTime: LocalTime,
    ) : DateTimeProvider {
        override fun nowInstant(): Instant = now

        override fun today(): LocalDate = today

        override fun localTime(): LocalTime = localTime
    }
}
