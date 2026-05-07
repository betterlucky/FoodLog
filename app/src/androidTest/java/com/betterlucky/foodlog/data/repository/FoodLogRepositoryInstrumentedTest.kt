package com.betterlucky.foodlog.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.betterlucky.foodlog.data.db.FoodLogDatabase
import com.betterlucky.foodlog.data.entities.ConfidenceLevel
import com.betterlucky.foodlog.data.entities.EntryKind
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.FoodItemSource
import com.betterlucky.foodlog.data.entities.ProductSource
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import com.betterlucky.foodlog.data.entities.RawEntryStatus
import com.betterlucky.foodlog.data.entities.ShortcutPortionMode
import com.betterlucky.foodlog.data.entities.UserDefaultEntity
import com.betterlucky.foodlog.domain.dailyclose.DailyCloseReadiness
import com.betterlucky.foodlog.domain.dailyclose.dailyCloseReadiness
import com.betterlucky.foodlog.domain.intent.DeterministicIntentClassifier
import com.betterlucky.foodlog.domain.parser.DeterministicParser
import com.betterlucky.foodlog.util.DateTimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class FoodLogRepositoryInstrumentedTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FoodLogDatabase::class.java,
    )

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
    fun migration13To14BackfillsShortcutPortionColumns() {
        val databaseName = "migration-13-14-${System.nanoTime()}"
        migrationHelper.createDatabase(databaseName, 13).apply {
            execSQL(
                """
                INSERT INTO user_defaults (
                    `trigger`,
                    name,
                    calories,
                    unit,
                    notes,
                    source,
                    confidence,
                    active,
                    defaultAmount
                ) VALUES (
                    'tea',
                    'Tea',
                    25.0,
                    'cup',
                    'milk',
                    'USER_DEFAULT',
                    'HIGH',
                    1,
                    2.0
                )
                """.trimIndent(),
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            14,
            true,
            FoodLogDatabase.MIGRATION_13_14,
        ).apply {
            query(
                """
                SELECT
                    name,
                    calories,
                    unit,
                    notes,
                    defaultAmount,
                    portionMode,
                    itemUnit,
                    itemSizeAmount,
                    itemSizeUnit,
                    kcalPer100g,
                    kcalPer100ml,
                    nutritionBasisName
                FROM user_defaults
                WHERE `trigger` = 'tea'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Tea", cursor.getString(0))
                assertEquals(25.0, cursor.getDouble(1), 0.001)
                assertEquals("cup", cursor.getString(2))
                assertEquals("milk", cursor.getString(3))
                assertEquals(2.0, cursor.getDouble(4), 0.001)
                assertEquals("PLAIN", cursor.getString(5))
                assertNull(cursor.getString(6))
                assertTrue(cursor.isNull(7))
                assertNull(cursor.getString(8))
                assertTrue(cursor.isNull(9))
                assertTrue(cursor.isNull(10))
                assertNull(cursor.getString(11))
            }
            close()
        }
    }

    @Test
    fun migration14To15RenamesShortcutTriggerToLookupKey() {
        val databaseName = "migration-14-15-${System.nanoTime()}"
        migrationHelper.createDatabase(databaseName, 14).apply {
            execSQL(
                """
                INSERT INTO user_defaults (
                    `trigger`,
                    name,
                    calories,
                    unit,
                    notes,
                    source,
                    confidence,
                    active,
                    defaultAmount,
                    portionMode,
                    itemUnit,
                    itemSizeAmount,
                    itemSizeUnit,
                    kcalPer100g,
                    kcalPer100ml,
                    nutritionBasisName
                ) VALUES (
                    'tea',
                    'Tea',
                    25.0,
                    'cup',
                    'milk',
                    'USER_DEFAULT',
                    'HIGH',
                    1,
                    2.0,
                    'ITEM',
                    'cup',
                    250.0,
                    'ml',
                    NULL,
                    10.0,
                    'Tea'
                )
                """.trimIndent(),
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            15,
            true,
            FoodLogDatabase.MIGRATION_14_15,
        ).apply {
            query(
                """
                SELECT lookupKey, name, calories, unit, notes, defaultAmount, portionMode,
                    itemUnit, itemSizeAmount, itemSizeUnit, kcalPer100ml, nutritionBasisName
                FROM user_defaults
                WHERE lookupKey = 'tea'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("tea", cursor.getString(0))
                assertEquals("Tea", cursor.getString(1))
                assertEquals(25.0, cursor.getDouble(2), 0.001)
                assertEquals("cup", cursor.getString(3))
                assertEquals("milk", cursor.getString(4))
                assertEquals(2.0, cursor.getDouble(5), 0.001)
                assertEquals("ITEM", cursor.getString(6))
                assertEquals("cup", cursor.getString(7))
                assertEquals(250.0, cursor.getDouble(8), 0.001)
                assertEquals("ml", cursor.getString(9))
                assertEquals(10.0, cursor.getDouble(10), 0.001)
                assertEquals("Tea", cursor.getString(11))
            }
            close()
        }
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
    fun explicitTimeInSubmissionOverridesCurrentTime() = runTest {
        repository.seedDefaults()

        repository.submitText("1pm tea")
        repository.submitText("13:15 tea")
        repository.submitText("tea at 1:30pm")
        val foodItems = repository.observeFoodItemsForDate(today).first()

        assertEquals(
            listOf(
                LocalTime.parse("13:00"),
                LocalTime.parse("13:15"),
                LocalTime.parse("13:30"),
            ),
            foodItems.map { it.consumedTime },
        )
    }

    @Test
    fun explicitTimeInPendingSubmissionIsPreservedForResolution() = runTest {
        repository.seedDefaults()

        val result = repository.submitText("1pm curry")
        val pendingEntry = repository.observePendingEntriesForDate(today).first().single()
        val rawEntry = database.rawEntryDao().getById(result.rawEntryId)

        assertTrue(result is FoodLogRepository.SubmitResult.Pending)
        assertEquals(LocalTime.parse("13:00"), pendingEntry.consumedTime)
        assertEquals(LocalTime.parse("13:00"), rawEntry?.consumedTime)
    }

    @Test
    fun pmPrefixUnsupportedCompoundStaysPendingWithParsedTime() = runTest {
        repository.seedDefaults()

        val result = repository.submitText("10pm yoghurt with chia and pumpkin seeds")
        val pendingEntry = repository.observePendingEntriesForDate(today).first().single()
        val rawEntry = database.rawEntryDao().getById(result.rawEntryId)

        assertTrue(result is FoodLogRepository.SubmitResult.Pending)
        assertEquals(LocalTime.parse("22:00"), pendingEntry.consumedTime)
        assertEquals(LocalTime.parse("22:00"), rawEntry?.consumedTime)
    }

    @Test
    fun quantityOneDoesNotParseAsTime() = runTest {
        repository.seedDefaults()

        repository.submitText("1 tea")
        val foodItem = repository.observeFoodItemsForDate(today).first().single()

        assertEquals(1.0, foodItem.amount ?: 0.0, 0.001)
        assertEquals(localTime, foodItem.consumedTime)
    }

    @Test
    fun selectedDateGuardBlocksDatedSubmissionForAnotherDay() = runTest {
        repository.seedDefaults()

        val result = repository.submitText(
            input = "yesterday tea",
            targetLogDate = today,
        )

        assertEquals(
            FoodLogRepository.SubmitResult.DateMismatch(
                requestedLogDate = today.minusDays(1),
                selectedLogDate = today,
            ),
            result,
        )
        assertEquals(emptyList<FoodItemEntity>(), repository.observeFoodItemsForDate(today).first())
        assertEquals(emptyList<RawEntryEntity>(), repository.observePendingEntries().first())
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
    fun ampersandSlashAndSemicolonSeparateCompoundShortcuts() = runTest {
        repository.seedDefaults()
        database.userDefaultDao().upsert(default(trigger = "banana", name = "Banana", calories = 105.0, unit = "each"))
        database.userDefaultDao().upsert(default(trigger = "satsuma", name = "Satsuma", calories = 35.0, unit = "each"))

        repository.submitText("banana & satsuma / tea; banana")
        val foodItems = repository.observeFoodItemsForDate(today).first()

        assertEquals(listOf("Banana", "Banana", "Satsuma", "Tea"), foodItems.map { it.name }.sorted())
        assertEquals(270.0, repository.observeCaloriesForDate(today).first(), 0.001)
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
    fun previewSubmissionWithKnownShortcutIsReadyAndNonMutating() = runTest {
        repository.seedDefaults()

        val preview = repository.previewSubmission("tea", today)

        assertTrue(preview is FoodLogRepository.SubmissionPreviewResult.Ready)
        assertEquals(emptyList<RawEntryEntity>(), repository.observePendingEntriesForDate(today).first())
        assertEquals(emptyList<FoodItemEntity>(), repository.observeFoodItemsForDate(today).first())
    }

    @Test
    fun previewSubmissionWithMixedKnownAndUnknownPartsNeedsResolutionAndIsNonMutating() = runTest {
        repository.seedDefaults()

        val preview = repository.previewSubmission("tea and curry", today)

        assertTrue(preview is FoodLogRepository.SubmissionPreviewResult.NeedsResolution)
        val parts = (preview as FoodLogRepository.SubmissionPreviewResult.NeedsResolution).parts
        assertEquals(listOf("tea", "curry"), parts.map { it.inputText })
        assertEquals(listOf("Tea", null), parts.map { it.default?.name })
        assertEquals(emptyList<RawEntryEntity>(), repository.observePendingEntriesForDate(today).first())
        assertEquals(emptyList<FoodItemEntity>(), repository.observeFoodItemsForDate(today).first())
    }

    @Test
    fun previewSubmissionReportsDateMismatchWithoutMutating() = runTest {
        repository.seedDefaults()

        val preview = repository.previewSubmission("yesterday tea", today)

        assertEquals(
            FoodLogRepository.SubmissionPreviewResult.DateMismatch(
                requestedLogDate = today.minusDays(1),
                selectedLogDate = today,
            ),
            preview,
        )
        assertEquals(emptyList<RawEntryEntity>(), repository.observePendingEntries().first())
        assertEquals(emptyList<FoodItemEntity>(), repository.observeFoodItemsForDate(today).first())
    }

    @Test
    fun wizardPartialSaveLogsCompletedPartsAndKeepsRemainderPending() = runTest {
        repository.seedDefaults()

        val result = repository.saveWizardSubmission(
            sourceRawEntryId = null,
            originalRawText = "tea and curry",
            completedRawText = "tea",
            pendingRawText = "curry",
            logDate = today,
            consumedTime = LocalTime.parse("13:00"),
            parts = listOf(
                FoodLogRepository.FoodItemEditReplacementPart(
                    name = "Tea",
                    amount = 1.0,
                    unit = "cup",
                    calories = 25.0,
                    source = FoodItemSource.USER_DEFAULT,
                    confidence = ConfidenceLevel.HIGH,
                    notes = null,
                ),
            ),
        )
        val foodItems = repository.observeFoodItemsForDate(today).first()
        val pendingEntries = repository.observePendingEntriesForDate(today).first()

        assertTrue(result is FoodLogRepository.WizardSubmissionResult.Saved)
        assertEquals(listOf("Tea"), foodItems.map { it.name })
        assertEquals(listOf("curry"), pendingEntries.map { it.rawText })
        assertEquals(LocalTime.parse("13:00"), foodItems.single().consumedTime)
        assertEquals(LocalTime.parse("13:00"), pendingEntries.single().consumedTime)
    }

    @Test
    fun wizardSaveCanCreatePortionShortcut() = runTest {
        repository.seedDefaults()

        repository.saveWizardSubmission(
            sourceRawEntryId = null,
            originalRawText = "toast",
            completedRawText = "toast",
            pendingRawText = null,
            logDate = today,
            consumedTime = null,
            parts = listOf(
                FoodLogRepository.FoodItemEditReplacementPart(
                    name = "Toast",
                    amount = 2.0,
                    unit = "slices",
                    calories = 180.0,
                    source = FoodItemSource.MANUAL_OVERRIDE,
                    confidence = ConfidenceLevel.HIGH,
                    notes = null,
                    saveDefaultLookupKey = "toast",
                ),
            ),
        )

        val nextResult = repository.submitText("toast")
        val foodItems = repository.observeFoodItemsForDate(today).first()

        assertTrue(nextResult is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(2, foodItems.size)
        assertEquals(180.0, foodItems.last().calories, 0.001)
        assertEquals(2.0, foodItems.last().amount ?: 0.0, 0.001)
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
        assertEquals("toast", (resolveResult as FoodLogRepository.ManualResolveResult.Resolved).savedDefaultLookupKey)
        assertEquals("Toast", default?.name)
        assertEquals(190.0, default?.calories ?: 0.0, 0.001)
        assertEquals(2.0, default?.defaultAmount ?: 0.0, 0.001)
        assertEquals("slice", default?.unit)
        assertTrue(nextResult is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(listOf(190.0, 190.0), foodItems.map { it.calories })
        assertEquals(2.0, foodItems.last().amount ?: 0.0, 0.001)
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
    fun singlePendingGramEntryPreviewKeepsParsedQuantityAndTime() = runTest {
        repository.seedDefaults()

        val pendingResult = repository.submitText("13:45 10g fruit and nut mix")
        val preview = repository.previewPendingEntryResolution(
            rawEntryId = pendingResult.rawEntryId,
        ) as FoodLogRepository.PendingEntryResolutionPreviewResult.SinglePart

        assertEquals("10g fruit and nut mix", preview.part?.inputText)
        assertEquals("fruit and nut mix", preview.part?.lookupKey)
        assertEquals(10.0, preview.part?.quantity ?: 0.0, 0.001)
        assertEquals("g", preview.part?.quantityUnit)
        assertEquals(LocalTime.parse("13:45"), preview.consumedTime)
        assertEquals(null, preview.part?.default)
    }

    @Test
    fun manualPendingResolutionCanOverrideParsedTime() = runTest {
        repository.seedDefaults()

        val pendingResult = repository.submitText("13:45 10g fruit and nut mix")
        val resolveResult = repository.resolvePendingEntryManually(
            rawEntryId = pendingResult.rawEntryId,
            name = "Fruit and nut mix",
            amount = 10.0,
            unit = "g",
            calories = 52.0,
            notes = null,
            consumedTime = LocalTime.parse("14:10"),
        )
        val foodItem = repository.observeFoodItemsForDate(today).first().single()
        val rawEntry = database.rawEntryDao().getById(pendingResult.rawEntryId)

        assertTrue(resolveResult is FoodLogRepository.ManualResolveResult.Resolved)
        assertEquals(LocalTime.parse("14:10"), foodItem.consumedTime)
        assertEquals(LocalTime.parse("14:10"), rawEntry?.consumedTime)
    }

    @Test
    fun pendingCompoundEntryCanBeItemisedAndKeptPendingUntilCommitted() = runTest {
        repository.seedDefaults()
        val pendingResult = repository.submitText("tea, banana, satsuma")

        val preview = repository.previewPendingEntryResolution(
            rawEntryId = pendingResult.rawEntryId,
        ) as FoodLogRepository.PendingEntryResolutionPreviewResult.Ready
        val stillPending = repository.observePendingEntriesForDate(today).first()
        val noFoodItems = repository.observeFoodItemsForDate(today).first()

        assertEquals("tea, banana, satsuma", preview.rawText)
        assertEquals(listOf("tea", "banana", "satsuma"), preview.parts.map { it.inputText })
        assertEquals(listOf(true, false, false), preview.parts.map { it.default != null })
        assertEquals(listOf("tea, banana, satsuma"), stillPending.map { it.rawText })
        assertEquals(emptyList<FoodItemEntity>(), noFoodItems)

        val updateResult = repository.resolvePendingEntryParts(
            rawEntryId = pendingResult.rawEntryId,
            rawText = preview.rawText,
            parts = listOf(
                FoodLogRepository.FoodItemEditReplacementPart(
                    name = "Tea",
                    amount = 1.0,
                    unit = "cup",
                    calories = 25.0,
                    source = FoodItemSource.USER_DEFAULT,
                    confidence = ConfidenceLevel.HIGH,
                    notes = FoodLogRepository.DEFAULT_TEA.notes,
                ),
                FoodLogRepository.FoodItemEditReplacementPart(
                    name = "Banana",
                    amount = 1.0,
                    unit = "each",
                    calories = 105.0,
                    source = FoodItemSource.MANUAL_OVERRIDE,
                    confidence = ConfidenceLevel.HIGH,
                    notes = null,
                    saveDefaultLookupKey = "banana",
                ),
                FoodLogRepository.FoodItemEditReplacementPart(
                    name = "Satsuma",
                    amount = 1.0,
                    unit = "each",
                    calories = 35.0,
                    source = FoodItemSource.MANUAL_OVERRIDE,
                    confidence = ConfidenceLevel.HIGH,
                    notes = null,
                ),
            ),
        )
        val pendingEntries = repository.observePendingEntriesForDate(today).first()
        val foodItems = repository.observeFoodItemsForDate(today).first()
        val rawEntry = database.rawEntryDao().getById(pendingResult.rawEntryId)
        val bananaDefault = database.userDefaultDao().getActiveDefault("banana")

        assertTrue(updateResult is FoodLogRepository.PendingEntryUpdateResult.Parsed)
        assertEquals(emptyList<RawEntryEntity>(), pendingEntries)
        assertEquals(RawEntryStatus.PARSED, rawEntry?.status)
        assertEquals(listOf("Banana", "Satsuma", "Tea"), foodItems.map { it.name }.sorted())
        assertEquals(listOf(pendingResult.rawEntryId), foodItems.map { it.rawEntryId }.distinct())
        assertEquals("Banana", bananaDefault?.name)
        assertEquals(105.0, bananaDefault?.calories ?: 0.0, 0.001)
    }

    @Test
    fun plusSeparatedPendingEntryStagesRecognisedPartsAndGramQuantity() = runTest {
        repository.seedDefaults()

        val pendingResult = repository.submitText("1pm 150g sourdough with thin butter + tea")
        val preview = repository.previewPendingEntryResolution(
            rawEntryId = pendingResult.rawEntryId,
        ) as FoodLogRepository.PendingEntryResolutionPreviewResult.Ready
        val pendingEntry = repository.observePendingEntriesForDate(today).first().single()

        assertEquals(LocalTime.parse("13:00"), pendingEntry.consumedTime)
        assertEquals(
            listOf("150g sourdough with thin butter", "tea"),
            preview.parts.map { it.inputText },
        )
        assertEquals(listOf(null, "Tea"), preview.parts.map { it.default?.name })
        assertEquals(150.0, preview.parts.first().quantity, 0.001)
        assertEquals("g", preview.parts.first().quantityUnit)
        assertEquals("sourdough with thin butter", preview.parts.first().lookupKey)
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
        assertEquals("greek yogurt", (addResult as FoodLogRepository.ManualAddResult.Added).savedDefaultLookupKey)
        assertEquals("Greek yogurt", default?.name)
        assertEquals(180.0, default?.calories ?: 0.0, 0.001)
        assertEquals(2.0, default?.defaultAmount ?: 0.0, 0.001)
        assertEquals("pot", default?.unit)
        assertTrue(nextResult is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(listOf(180.0, 180.0), foodItems.map { it.calories })
        assertEquals(2.0, foodItems.last().amount ?: 0.0, 0.001)
    }

    @Test
    fun shortcutCanBeAddedDirectlyForFutureSubmissions() = runTest {
        repository.seedDefaults()

        val addResult = repository.addDefault(
            lookupKey = "  Banana  ",
            name = "Banana",
            calories = 105.0,
            unit = "each",
            notes = "Shortcut picker",
        )
        val default = database.userDefaultDao().getActiveDefault("banana")
        val nextResult = repository.submitText("banana")
        val foodItem = repository.observeFoodItemsForDate(today).first().single()

        assertEquals(FoodLogRepository.DefaultUpdateResult.Updated, addResult)
        assertEquals("Banana", default?.name)
        assertEquals(105.0, default?.calories ?: 0.0, 0.001)
        assertEquals("each", default?.unit)
        assertTrue(nextResult is FoodLogRepository.SubmitResult.Parsed)
        assertEquals("Banana", foodItem.name)
        assertEquals(105.0, foodItem.calories, 0.001)
    }

    @Test
    fun directShortcutWithBlankUnitDefaultsToPortion() = runTest {
        repository.seedDefaults()

        val addResult = repository.addDefault(
            lookupKey = "roast dinner",
            name = "Full roast dinner",
            calories = 850.0,
            unit = "",
            notes = null,
        )
        val default = database.userDefaultDao().getActiveDefault("roast dinner")
        val result = repository.submitText("2 roast dinners")
        val foodItem = repository.observeFoodItemsForDate(today).first().single()

        assertEquals(FoodLogRepository.DefaultUpdateResult.Updated, addResult)
        assertEquals("portion", default?.unit)
        assertTrue(result is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(2.0, foodItem.amount ?: 0.0, 0.001)
        assertEquals("portion", foodItem.unit)
        assertEquals(1700.0, foodItem.calories, 0.001)
    }

    @Test
    fun gramShortcutScalesCaloriesByParsedWeight() = runTest {
        repository.seedDefaults()

        repository.addDefault(
            lookupKey = "sourdough with thin butter",
            name = "Sourdough with thin butter",
            calories = 2.4,
            unit = "g",
            notes = null,
        )
        val result = repository.submitText("100g sourdough with thin butter")
        val foodItem = repository.observeFoodItemsForDate(today).first().single()

        assertTrue(result is FoodLogRepository.SubmitResult.Parsed)
        assertEquals("Sourdough with thin butter", foodItem.name)
        assertEquals(100.0, foodItem.amount ?: 0.0, 0.001)
        assertEquals("g", foodItem.unit)
        assertEquals(240.0, foodItem.calories, 0.001)
    }

    @Test
    fun unitQuantityShortcutScalesCaloriesByParsedAmount() = runTest {
        repository.seedDefaults()

        repository.addDefault(
            lookupKey = "sourdough",
            name = "Sourdough",
            calories = 90.0,
            unit = "slice",
            notes = null,
        )
        val result = repository.submitText("2 slices sourdough")
        val foodItem = repository.observeFoodItemsForDate(today).first().single()

        assertTrue(result is FoodLogRepository.SubmitResult.Parsed)
        assertEquals("Sourdough", foodItem.name)
        assertEquals(2.0, foodItem.amount ?: 0.0, 0.001)
        assertEquals("slice", foodItem.unit)
        assertEquals(180.0, foodItem.calories, 0.001)
    }

    @Test
    fun shortcutWithoutTypedTimeUsesCurrentTimeButExplicitTimeIsPreserved() = runTest {
        repository.seedDefaults()
        dateTimeProvider.localTime = LocalTime.parse("14:45")

        val currentTimeResult = repository.submitText("tea")
        val explicitTimeResult = repository.submitText("08:30 tea")
        val foodItems = repository.observeFoodItemsForDate(today).first()

        assertTrue(currentTimeResult is FoodLogRepository.SubmitResult.Parsed)
        assertTrue(explicitTimeResult is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(listOf(LocalTime.parse("08:30"), LocalTime.parse("14:45")), foodItems.map { it.consumedTime })
    }

    @Test
    fun unitQuantityPendingPreviewKeepsParsedUnit() = runTest {
        repository.seedDefaults()

        val pendingResult = repository.submitText("3 slices sourdough + tea")
        val preview = repository.previewPendingEntryResolution(
            rawEntryId = pendingResult.rawEntryId,
        ) as FoodLogRepository.PendingEntryResolutionPreviewResult.Ready

        assertEquals(listOf("3 slices sourdough", "tea"), preview.parts.map { it.inputText })
        assertEquals(3.0, preview.parts.first().quantity, 0.001)
        assertEquals("slice", preview.parts.first().quantityUnit)
        assertEquals("sourdough", preview.parts.first().lookupKey)
        assertEquals("Tea", preview.parts.last().default?.name)
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
            lookupKey = "toast",
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
    fun loggedShortcutEditCanUpdateShortcutInSameTransaction() = runTest {
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
        val logResult = repository.submitText("toast") as FoodLogRepository.SubmitResult.Parsed
        val candidate = repository.shortcutUpdateCandidateForFoodItem(logResult.foodItemId)

        val updateResult = repository.updateFoodItem(
            id = logResult.foodItemId,
            name = "Toast",
            amount = 2.0,
            unit = "slice",
            calories = 190.0,
            consumedTime = LocalTime.parse("10:00"),
            notes = "New usual",
            updateShortcutLookupKey = candidate?.lookupKey,
        )
        val default = database.userDefaultDao().getActiveDefault("toast")
        repository.submitText("toast")
        val latestFoodItem = repository.observeFoodItemsForDate(today).first().last()

        assertEquals("toast", candidate?.lookupKey)
        assertEquals(FoodLogRepository.FoodItemUpdateResult.Updated, updateResult)
        assertEquals(190.0, default?.calories ?: 0.0, 0.001)
        assertEquals(2.0, default?.defaultAmount ?: 0.0, 0.001)
        assertEquals("New usual", default?.notes)
        assertEquals(190.0, latestFoodItem.calories, 0.001)
        assertEquals(2.0, latestFoodItem.amount ?: 0.0, 0.001)
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
        assertEquals(listOf("tea"), activeDefaults.map { it.lookupKey })
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
    fun loggedFoodItemCanBeReplacedFromDefaultsWhenCaloriesBlank() = runTest {
        repository.seedDefaults()
        database.userDefaultDao().upsert(default(trigger = "banana", name = "Banana", calories = 105.0, unit = "each"))
        database.userDefaultDao().upsert(default(trigger = "satsuma", name = "Satsuma", calories = 35.0, unit = "each"))
        val result = repository.submitText("tea") as FoodLogRepository.SubmitResult.Parsed

        val updateResult = repository.updateFoodItem(
            id = result.foodItemId,
            name = "tea, banana, satsuma",
            amount = 999.0,
            unit = "ignored",
            calories = null,
            consumedTime = LocalTime.parse("08:45"),
            notes = FoodLogRepository.DEFAULT_TEA.notes,
        )
        val foodItems = repository.observeFoodItemsForDate(today).first()
        val rawEntry = database.rawEntryDao().getById(result.rawEntryId)
        val total = repository.observeCaloriesForDate(today).first()
        val csv = repository.exportLegacyHealthCsv(today).csv

        assertEquals(FoodLogRepository.FoodItemUpdateResult.UpdatedFromDefaults, updateResult)
        assertEquals(null, database.foodItemDao().getById(result.foodItemId))
        assertEquals("tea, banana, satsuma", rawEntry?.rawText)
        assertEquals(listOf("Banana", "Satsuma", "Tea"), foodItems.map { it.name }.sorted())
        assertEquals(listOf(LocalTime.parse("08:45")), foodItems.map { it.consumedTime }.distinct())
        assertEquals(165.0, total, 0.001)
        assertTrue(csv.contains("Banana,1 each,105"))
        assertTrue(csv.contains("Satsuma,1 each,35"))
        assertTrue(csv.contains("Tea,1 cup,25"))
    }

    @Test
    fun blankCalorieLoggedFoodEditDoesNotChangeRowWhenDefaultsDoNotResolve() = runTest {
        repository.seedDefaults()
        val result = repository.submitText("tea") as FoodLogRepository.SubmitResult.Parsed

        val updateResult = repository.updateFoodItem(
            id = result.foodItemId,
            name = "tea and curry",
            amount = null,
            unit = null,
            calories = null,
            consumedTime = LocalTime.parse("08:45"),
            notes = null,
        )
        val foodItem = repository.observeFoodItemsForDate(today).first().single()
        val rawEntry = database.rawEntryDao().getById(result.rawEntryId)

        assertEquals(
            FoodLogRepository.FoodItemUpdateResult.UnresolvedDefaults(listOf("curry")),
            updateResult,
        )
        assertEquals("Tea", foodItem.name)
        assertEquals(25.0, foodItem.calories, 0.001)
        assertEquals(localTime, foodItem.consumedTime)
        assertEquals("tea", rawEntry?.rawText)
    }

    @Test
    fun labelProductLogCreatesAuditedFoodProductAndExportRow() = runTest {
        repository.seedDefaults()

        val result = repository.logLabelProduct(
            FoodLogRepository.LabelProductLogInput(
                name = "Tomato soup",
                kcalPer100g = 33.0,
                servingSizeGrams = 255.0,
                servingUnit = "cup",
                kcalPerServing = 83.0,
                amount = 1.0,
                unit = "cup",
                grams = 255.0,
                calories = 83.0,
                logDate = today,
                consumedTime = LocalTime.parse("13:15"),
                notes = "Label scan",
            ),
        )
        val foodItem = repository.observeFoodItemsForDate(today).first().single()
        val rawEntry = database.rawEntryDao().getById(foodItem.rawEntryId)
        val product = database.productDao().getById(foodItem.productId ?: 0)
        val csv = repository.exportLegacyHealthCsv(today).csv
        val status = database.dailyStatusDao().getByDate(today)

        assertTrue(result is FoodLogRepository.LabelLogResult.Logged)
        assertEquals((result as FoodLogRepository.LabelLogResult.Logged).foodItemId, foodItem.id)
        assertEquals("Label scan: Tomato soup", rawEntry?.rawText)
        assertEquals(RawEntryStatus.PARSED, rawEntry?.status)
        assertEquals("Tomato soup", foodItem.name)
        assertEquals(FoodItemSource.SAVED_LABEL, foodItem.source)
        assertEquals(1.0, foodItem.amount ?: 0.0, 0.001)
        assertEquals("cup", foodItem.unit)
        assertEquals(255.0, foodItem.grams ?: 0.0, 0.001)
        assertEquals(83.0, foodItem.calories, 0.001)
        assertEquals(ProductSource.PACKAGING_PHOTO, product?.source)
        assertEquals(33.0, product?.kcalPer100g ?: 0.0, 0.001)
        assertEquals(255.0, product?.servingSizeGrams ?: 0.0, 0.001)
        assertEquals("food_log_2026-04-24.csv", status?.legacyExportFileName)
        assertEquals(now, status?.legacyExportedAt)
        assertTrue(csv.contains("2026-04-24,13:15,Tomato soup,1 cup,83,Label scan"))
    }

    @Test
    fun ocrShortcutUsesUsualAmountButTypedItemQuantityIsAbsolute() = runTest {
        repository.seedDefaults()

        val addResult = repository.addOcrShortcut(
            FoodLogRepository.OcrShortcutInput(
                lookupKey = "Yoghurt pot",
                name = "Yoghurt pot",
                caloriesPerUnit = 80.0,
                unit = "pot",
                notes = "From label",
                defaultAmount = 0.5,
                portionMode = ShortcutPortionMode.ITEM,
                itemUnit = "pot",
                itemSizeAmount = 125.0,
                itemSizeUnit = "g",
                kcalPer100g = 64.0,
                kcalPer100ml = null,
            ),
        )
        val default = database.userDefaultDao().getActiveDefault("yoghurt pot")
        val usualResult = repository.submitText("yoghurt pot")
        val explicitResult = repository.submitText("two yoghurt pots")
        val foodItems = repository.observeFoodItemsForDate(today).first()

        assertEquals(FoodLogRepository.DefaultUpdateResult.Updated, addResult)
        assertEquals(ShortcutPortionMode.ITEM, default?.portionMode)
        assertEquals(0.5, default?.defaultAmount ?: 0.0, 0.001)
        assertEquals("pot", default?.itemUnit)
        assertEquals(125.0, default?.itemSizeAmount ?: 0.0, 0.001)
        assertEquals("g", default?.itemSizeUnit)
        assertEquals(64.0, default?.kcalPer100g ?: 0.0, 0.001)
        assertEquals("Yoghurt pot", default?.nutritionBasisName)
        assertTrue(usualResult is FoodLogRepository.SubmitResult.Parsed)
        assertTrue(explicitResult is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(listOf(0.5, 2.0), foodItems.map { it.amount ?: 0.0 })
        assertEquals(listOf("pot", "pot"), foodItems.map { it.unit })
        assertEquals(listOf(40.0, 160.0), foodItems.map { it.calories })
    }

    @Test
    fun measureShortcutUsesUsualAmountButTypedMeasureIsAbsolute() = runTest {
        repository.seedDefaults()

        repository.addOcrShortcut(
            FoodLogRepository.OcrShortcutInput(
                lookupKey = "cheese",
                name = "Cheese",
                caloriesPerUnit = 4.0,
                unit = "g",
                notes = null,
                defaultAmount = 50.0,
                portionMode = ShortcutPortionMode.MEASURE,
                itemUnit = null,
                itemSizeAmount = null,
                itemSizeUnit = null,
                kcalPer100g = 400.0,
                kcalPer100ml = null,
            ),
        )

        val usualResult = repository.submitText("cheese")
        val explicitResult = repository.submitText("100g cheese")
        val foodItems = repository.observeFoodItemsForDate(today).first()

        assertTrue(usualResult is FoodLogRepository.SubmitResult.Parsed)
        assertTrue(explicitResult is FoodLogRepository.SubmitResult.Parsed)
        assertEquals(listOf(50.0, 100.0), foodItems.map { it.amount ?: 0.0 })
        assertEquals(listOf("g", "g"), foodItems.map { it.unit })
        assertEquals(listOf(200.0, 400.0), foodItems.map { it.calories })
    }

    @Test
    fun shortcutNutritionBasisStalesOnNameChangeButNotAmountChange() = runTest {
        repository.seedDefaults()
        repository.addOcrShortcut(
            FoodLogRepository.OcrShortcutInput(
                lookupKey = "yoghurt",
                name = "Yoghurt",
                caloriesPerUnit = 80.0,
                unit = "pot",
                notes = null,
                defaultAmount = 1.0,
                portionMode = ShortcutPortionMode.ITEM,
                itemUnit = "pot",
                itemSizeAmount = 125.0,
                itemSizeUnit = "g",
                kcalPer100g = 64.0,
                kcalPer100ml = null,
            ),
        )

        repository.updateDefault(
            lookupKey = "yoghurt",
            name = "Yoghurt",
            calories = 80.0,
            unit = "pot",
            notes = null,
            defaultAmount = 0.5,
            portionMode = ShortcutPortionMode.ITEM,
            itemUnit = "pot",
            itemSizeAmount = 125.0,
            itemSizeUnit = "g",
            kcalPer100g = 64.0,
            kcalPer100ml = null,
        )
        val amountOnly = database.userDefaultDao().getActiveDefault("yoghurt")

        repository.updateDefault(
            lookupKey = "yoghurt",
            name = "Greek yoghurt",
            calories = 80.0,
            unit = "pot",
            notes = null,
            defaultAmount = 0.5,
            portionMode = ShortcutPortionMode.ITEM,
            itemUnit = "pot",
            itemSizeAmount = 125.0,
            itemSizeUnit = "g",
            kcalPer100g = 64.0,
            kcalPer100ml = null,
        )
        val renamed = database.userDefaultDao().getActiveDefault("yoghurt")

        assertEquals(0.5, amountOnly?.defaultAmount ?: 0.0, 0.001)
        assertEquals("Yoghurt", amountOnly?.nutritionBasisName)
        assertEquals("Greek yoghurt", renamed?.name)
        assertEquals("Yoghurt", renamed?.nutritionBasisName)
    }

    @Test
    fun loggedFoodEditPreviewCanBeCommittedAfterManualMissingPartsAreCompleted() = runTest {
        repository.seedDefaults()
        val result = repository.submitText("tea") as FoodLogRepository.SubmitResult.Parsed

        val preview = repository.previewFoodItemDefaultEdit(
            id = result.foodItemId,
            name = "tea, satsuma, banana",
        ) as FoodLogRepository.FoodItemDefaultEditPreviewResult.Ready
        val unchangedItems = repository.observeFoodItemsForDate(today).first()

        assertEquals("tea, satsuma, banana", preview.rawText)
        assertEquals(listOf("tea", "satsuma", "banana"), preview.parts.map { it.inputText })
        assertEquals(listOf(true, false, false), preview.parts.map { it.default != null })
        assertEquals(listOf("Tea"), unchangedItems.map { it.name })

        val updateResult = repository.replaceFoodItemWithResolvedEditParts(
            id = result.foodItemId,
            rawText = preview.rawText,
            consumedTime = LocalTime.parse("08:45"),
            parts = listOf(
                FoodLogRepository.FoodItemEditReplacementPart(
                    name = "Tea",
                    amount = 1.0,
                    unit = "cup",
                    calories = 25.0,
                    source = FoodItemSource.USER_DEFAULT,
                    confidence = ConfidenceLevel.HIGH,
                    notes = FoodLogRepository.DEFAULT_TEA.notes,
                ),
                FoodLogRepository.FoodItemEditReplacementPart(
                    name = "Satsuma",
                    amount = 1.0,
                    unit = "each",
                    calories = 35.0,
                    source = FoodItemSource.MANUAL_OVERRIDE,
                    confidence = ConfidenceLevel.HIGH,
                    notes = null,
                    saveDefaultLookupKey = "satsuma",
                ),
                FoodLogRepository.FoodItemEditReplacementPart(
                    name = "Banana",
                    amount = 1.0,
                    unit = "each",
                    calories = 105.0,
                    source = FoodItemSource.MANUAL_OVERRIDE,
                    confidence = ConfidenceLevel.HIGH,
                    notes = null,
                ),
            ),
        )
        val foodItems = repository.observeFoodItemsForDate(today).first()
        val rawEntry = database.rawEntryDao().getById(result.rawEntryId)
        val total = repository.observeCaloriesForDate(today).first()
        val satsumaDefault = database.userDefaultDao().getActiveDefault("satsuma")
        val bananaDefault = database.userDefaultDao().getActiveDefault("banana")

        assertEquals(FoodLogRepository.FoodItemUpdateResult.UpdatedFromDefaults, updateResult)
        assertEquals(null, database.foodItemDao().getById(result.foodItemId))
        assertEquals("tea, satsuma, banana", rawEntry?.rawText)
        assertEquals(listOf("Banana", "Satsuma", "Tea"), foodItems.map { it.name }.sorted())
        assertEquals(listOf(LocalTime.parse("08:45")), foodItems.map { it.consumedTime }.distinct())
        assertEquals(165.0, total, 0.001)
        assertEquals("Satsuma", satsumaDefault?.name)
        assertEquals(35.0, satsumaDefault?.calories ?: 0.0, 0.001)
        assertEquals(null, bananaDefault)

        val nextResult = repository.submitText("satsuma")
        assertTrue(nextResult is FoodLogRepository.SubmitResult.Parsed)
        val nextParsedResult = nextResult as FoodLogRepository.SubmitResult.Parsed
        val nextFoodItem = repository.observeFoodItemsForDate(today).first()
            .single { it.rawEntryId == nextParsedResult.rawEntryId }

        assertEquals("Satsuma", nextFoodItem.name)
        assertEquals(35.0, nextFoodItem.calories, 0.001)
    }

    @Test
    fun explicitCaloriesKeepLoggedFoodEditAsSingleManualRow() = runTest {
        repository.seedDefaults()
        database.userDefaultDao().upsert(default(trigger = "banana", name = "Banana", calories = 105.0, unit = "each"))
        val result = repository.submitText("tea") as FoodLogRepository.SubmitResult.Parsed

        val updateResult = repository.updateFoodItem(
            id = result.foodItemId,
            name = "tea, banana",
            amount = 1.0,
            unit = "plate",
            calories = 200.0,
            consumedTime = LocalTime.parse("08:45"),
            notes = "Manual override",
        )
        val foodItem = repository.observeFoodItemsForDate(today).first().single()

        assertEquals(FoodLogRepository.FoodItemUpdateResult.Updated, updateResult)
        assertEquals("tea, banana", foodItem.name)
        assertEquals("plate", foodItem.unit)
        assertEquals(200.0, foodItem.calories, 0.001)
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
    fun generatedLegacyExportCanBeMarkedOnlyAfterFileSaveSucceeds() = runTest {
        repository.seedDefaults()
        repository.submitText("tea")

        val generatedExport = repository.buildLegacyHealthCsv(today)
        var status = database.dailyStatusDao().observeByDate(today).first()

        assertEquals("food_log_2026-04-24.csv", generatedExport.fileName)
        assertEquals(null, status?.legacyExportedAt)
        assertEquals(null, status?.legacyExportFileName)

        repository.markLegacyHealthCsvExported(today, generatedExport.fileName)
        status = database.dailyStatusDao().observeByDate(today).first()

        assertEquals(now, status?.legacyExportedAt)
        assertEquals("food_log_2026-04-24.csv", status?.legacyExportFileName)
    }

    @Test
    fun foodChangesAfterExportMarkDayAsChangedSinceExport() = runTest {
        repository.seedDefaults()
        val parsedResult = repository.submitText("tea") as FoodLogRepository.SubmitResult.Parsed

        dateTimeProvider.now = Instant.parse("2026-04-24T12:00:00Z")
        repository.exportLegacyHealthCsv(today).csv
        var status = database.dailyStatusDao().observeByDate(today).first()

        assertEquals(
            DailyCloseReadiness.AlreadyExported,
            dailyCloseReadiness(
                dailyStatus = status,
                pendingCount = 0,
                foodItemCount = 1,
                hasDailyWeight = false,
            ),
        )

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
        status = database.dailyStatusDao().observeByDate(today).first()

        assertEquals(Instant.parse("2026-04-24T12:00:00Z"), status?.legacyExportedAt)
        assertEquals(Instant.parse("2026-04-24T12:30:00Z"), status?.lastFoodChangedAt)
        assertEquals(
            DailyCloseReadiness.ReadyToExport,
            dailyCloseReadiness(
                dailyStatus = status,
                pendingCount = 0,
                foodItemCount = 1,
                hasDailyWeight = false,
            ),
        )

        dateTimeProvider.now = Instant.parse("2026-04-24T13:00:00Z")
        repository.exportLegacyHealthCsv(today).csv
        status = database.dailyStatusDao().observeByDate(today).first()

        assertEquals(Instant.parse("2026-04-24T13:00:00Z"), status?.legacyExportedAt)
        assertEquals(Instant.parse("2026-04-24T12:30:00Z"), status?.lastFoodChangedAt)
    }

    @Test
    fun removingFoodAfterExportMarksDayAsChangedSinceExport() = runTest {
        repository.seedDefaults()
        val parsedResult = repository.submitText("tea") as FoodLogRepository.SubmitResult.Parsed

        dateTimeProvider.now = Instant.parse("2026-04-24T12:00:00Z")
        repository.exportLegacyHealthCsv(today)

        dateTimeProvider.now = Instant.parse("2026-04-24T12:15:00Z")
        val removeResult = repository.removeFoodItem(parsedResult.foodItemId)
        val status = database.dailyStatusDao().observeByDate(today).first()

        assertEquals(FoodLogRepository.FoodItemRemoveResult.Removed, removeResult)
        assertEquals(Instant.parse("2026-04-24T12:00:00Z"), status?.legacyExportedAt)
        assertEquals(Instant.parse("2026-04-24T12:15:00Z"), status?.lastFoodChangedAt)
        assertEquals(
            DailyCloseReadiness.NoFoodLogged,
            dailyCloseReadiness(
                dailyStatus = status,
                pendingCount = 0,
                foodItemCount = 0,
                hasDailyWeight = false,
            ),
        )
    }

    @Test
    fun dailyWeightChangeAfterExportMarksDayAsChangedSinceExport() = runTest {
        repository.seedDefaults()
        repository.submitText("tea")

        dateTimeProvider.now = Instant.parse("2026-04-24T12:00:00Z")
        repository.exportLegacyHealthCsv(today)

        dateTimeProvider.now = Instant.parse("2026-04-24T12:20:00Z")
        val weightResult = repository.upsertDailyWeight(
            logDate = today,
            weightKg = 82.6,
            measuredTime = LocalTime.parse("07:15"),
        )
        val status = database.dailyStatusDao().observeByDate(today).first()

        assertEquals(FoodLogRepository.DailyWeightResult.Saved, weightResult)
        assertEquals(Instant.parse("2026-04-24T12:00:00Z"), status?.legacyExportedAt)
        assertEquals(Instant.parse("2026-04-24T12:20:00Z"), status?.lastFoodChangedAt)
        assertEquals(
            DailyCloseReadiness.ReadyToExport,
            dailyCloseReadiness(
                dailyStatus = status,
                pendingCount = 0,
                foodItemCount = 1,
                hasDailyWeight = true,
            ),
        )
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
            lookupKey = trigger,
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
