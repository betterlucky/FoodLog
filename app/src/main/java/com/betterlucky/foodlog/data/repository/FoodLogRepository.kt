package com.betterlucky.foodlog.data.repository

import androidx.room.withTransaction
import com.betterlucky.foodlog.data.db.FoodLogDatabase
import com.betterlucky.foodlog.data.entities.AppSettingsEntity
import com.betterlucky.foodlog.data.entities.EntryKind
import com.betterlucky.foodlog.data.entities.ConfidenceLevel
import com.betterlucky.foodlog.data.entities.DailyStatusEntity
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.FoodItemSource
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import com.betterlucky.foodlog.data.entities.RawEntryStatus
import com.betterlucky.foodlog.data.entities.UserDefaultEntity
import com.betterlucky.foodlog.domain.export.AuditCsvExporter
import com.betterlucky.foodlog.domain.export.LegacyHealthCsvExporter
import com.betterlucky.foodlog.domain.dayboundary.FoodDayPolicy
import com.betterlucky.foodlog.domain.intent.DeterministicIntentClassifier
import com.betterlucky.foodlog.domain.intent.EntryIntent
import com.betterlucky.foodlog.domain.parser.DeterministicParser
import com.betterlucky.foodlog.util.DateTimeProvider
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime

class FoodLogRepository(
    private val database: FoodLogDatabase,
    private val intentClassifier: DeterministicIntentClassifier,
    private val parser: DeterministicParser,
    private val dateTimeProvider: DateTimeProvider,
    private val legacyHealthCsvExporter: LegacyHealthCsvExporter = LegacyHealthCsvExporter(),
    private val auditCsvExporter: AuditCsvExporter = AuditCsvExporter(),
    private val foodDayPolicy: FoodDayPolicy = FoodDayPolicy(),
) {
    private val appSettingsDao = database.appSettingsDao()
    private val rawEntryDao = database.rawEntryDao()
    private val foodItemDao = database.foodItemDao()
    private val userDefaultDao = database.userDefaultDao()
    private val dailyStatusDao = database.dailyStatusDao()

    suspend fun seedDefaults() {
        if (appSettingsDao.getById() == null) {
            appSettingsDao.upsert(AppSettingsEntity())
        }

        if (userDefaultDao.countByTrigger(DEFAULT_TEA.trigger) == 0) {
            userDefaultDao.upsert(DEFAULT_TEA)
        }
    }

    suspend fun submitText(input: String): SubmitResult =
        database.withTransaction {
            val intent = intentClassifier.classify(input)
            val calendarToday = dateTimeProvider.today()
            val localTime = dateTimeProvider.localTime()
            val defaultLogDate = currentFoodDate(
                calendarToday = calendarToday,
                localTime = localTime,
            )
            val parsed = parser.parse(
                input = input,
                today = calendarToday,
                defaultLogDate = defaultLogDate,
            )
            val createdAt = dateTimeProvider.nowInstant()
            val consumedTime = localTime

            if (intent != EntryIntent.FOOD_LOG) {
                val rawEntryId = rawEntryDao.insert(
                    RawEntryEntity(
                        createdAt = createdAt,
                        logDate = parsed.logDate,
                        consumedTime = consumedTime,
                        rawText = input,
                        entryKind = intent.toEntryKind(),
                        status = intent.toRawEntryStatus(),
                        notes = intent.placeholderNotes(),
                    ),
                )
                return@withTransaction SubmitResult.NonFood(
                    rawEntryId = rawEntryId,
                    logDate = parsed.logDate,
                    intent = intent,
                )
            }

            val rawEntryId = rawEntryDao.insert(
                RawEntryEntity(
                    createdAt = createdAt,
                    logDate = parsed.logDate,
                    consumedTime = consumedTime,
                    rawText = input,
                    entryKind = EntryKind.TEXT,
                    status = RawEntryStatus.PENDING,
                ),
            )

            val default = parsed.shortcutTrigger?.let { userDefaultDao.getActiveDefault(it) }
            if (default == null) {
                SubmitResult.Pending(rawEntryId = rawEntryId, logDate = parsed.logDate)
            } else {
                val foodItemId = foodItemDao.insert(
                    FoodItemEntity(
                        rawEntryId = rawEntryId,
                        logDate = parsed.logDate,
                        consumedTime = consumedTime,
                        name = default.name,
                        amount = parsed.quantity,
                        unit = default.unit,
                        calories = default.calories * parsed.quantity,
                        source = default.source,
                        confidence = default.confidence,
                        notes = default.notes,
                        createdAt = createdAt,
                    ),
                )
                rawEntryDao.updateStatus(rawEntryId, RawEntryStatus.PARSED)
                markFoodChanged(parsed.logDate)
                SubmitResult.Parsed(
                    rawEntryId = rawEntryId,
                    foodItemId = foodItemId,
                    logDate = parsed.logDate,
                )
            }
        }

    fun observeFoodItemsForDate(date: LocalDate): Flow<List<FoodItemEntity>> =
        foodItemDao.observeFoodItemsForDate(date)

    fun observeCaloriesForDate(date: LocalDate): Flow<Double> =
        foodItemDao.observeCaloriesForDate(date)

    fun observePendingEntries(): Flow<List<RawEntryEntity>> =
        rawEntryDao.observeByStatus(RawEntryStatus.PENDING)

    fun observePendingEntriesForDate(date: LocalDate): Flow<List<RawEntryEntity>> =
        rawEntryDao.observeByStatusForDate(RawEntryStatus.PENDING, date)

    fun observeActiveDefaults(): Flow<List<UserDefaultEntity>> =
        userDefaultDao.observeActiveDefaults()

    fun observeDailyStatusForDate(date: LocalDate) =
        dailyStatusDao.observeByDate(date)

    suspend fun currentFoodDate(): LocalDate =
        currentFoodDate(
            calendarToday = dateTimeProvider.today(),
            localTime = dateTimeProvider.localTime(),
        )

    suspend fun setDayBoundaryTime(dayBoundaryTime: LocalTime?) {
        if (appSettingsDao.getById() == null) {
            appSettingsDao.upsert(AppSettingsEntity(dayBoundaryTime = dayBoundaryTime))
        } else {
            appSettingsDao.updateDayBoundaryTime(dayBoundaryTime)
        }
    }

    suspend fun deactivateDefault(trigger: String) {
        userDefaultDao.deactivate(trigger)
    }

    suspend fun updateDefault(
        trigger: String,
        name: String,
        calories: Double,
        unit: String,
        notes: String?,
    ): DefaultUpdateResult {
        val trimmedTrigger = trigger.trim()
        val trimmedName = name.trim()
        val trimmedUnit = unit.trim()
        val normalizedNotes = notes?.trim().orEmpty().ifBlank { null }

        if (trimmedTrigger.isBlank() || trimmedName.isBlank() || trimmedUnit.isBlank() || calories <= 0.0) {
            return DefaultUpdateResult.InvalidInput
        }

        val existing = userDefaultDao.getActiveDefault(trimmedTrigger)
            ?: return DefaultUpdateResult.NotFound

        userDefaultDao.upsert(
            existing.copy(
                name = trimmedName,
                calories = calories,
                unit = trimmedUnit,
                notes = normalizedNotes,
            ),
        )
        return DefaultUpdateResult.Updated
    }

    suspend fun updateFoodItem(
        id: Long,
        name: String,
        amount: Double?,
        unit: String?,
        calories: Double,
        consumedTime: LocalTime,
        notes: String?,
    ): FoodItemUpdateResult {
        val trimmedName = name.trim()
        val normalizedAmount = amount?.takeIf { it > 0.0 }
        val normalizedUnit = unit?.trim().orEmpty().ifBlank { null }
        val normalizedNotes = notes?.trim().orEmpty().ifBlank { null }

        if (trimmedName.isBlank() || calories <= 0.0) {
            return FoodItemUpdateResult.InvalidInput
        }

        val existing = foodItemDao.getById(id)
            ?: return FoodItemUpdateResult.NotFound

        foodItemDao.update(
            existing.copy(
                name = trimmedName,
                amount = normalizedAmount,
                unit = normalizedUnit,
                calories = calories,
                consumedTime = consumedTime,
                notes = normalizedNotes,
            ),
        )
        markFoodChanged(existing.logDate)
        return FoodItemUpdateResult.Updated
    }

    suspend fun removeFoodItem(id: Long): FoodItemRemoveResult {
        val existing = foodItemDao.getById(id)
            ?: return FoodItemRemoveResult.NotFound

        foodItemDao.deleteById(existing.id)
        markFoodChanged(existing.logDate)
        return FoodItemRemoveResult.Removed
    }

    suspend fun resolvePendingEntryManually(
        rawEntryId: Long,
        name: String,
        amount: Double?,
        unit: String?,
        calories: Double,
        notes: String?,
        saveAsDefault: Boolean = false,
    ): ManualResolveResult =
        database.withTransaction {
            val trimmedName = name.trim()
            val normalizedUnit = unit?.trim().orEmpty().ifBlank { null }
            val normalizedNotes = notes?.trim().orEmpty().ifBlank { null }
            val normalizedAmount = amount?.takeIf { it > 0.0 }

            if (trimmedName.isBlank() || calories <= 0.0) {
                return@withTransaction ManualResolveResult.InvalidInput
            }

            val rawEntry = rawEntryDao.getById(rawEntryId)
                ?: return@withTransaction ManualResolveResult.NotFound

            if (rawEntry.status != RawEntryStatus.PENDING) {
                return@withTransaction ManualResolveResult.NotPending
            }

            val foodItemId = foodItemDao.insert(
                FoodItemEntity(
                    rawEntryId = rawEntry.id,
                    logDate = rawEntry.logDate,
                    consumedTime = rawEntry.consumedTime,
                    name = trimmedName,
                    amount = normalizedAmount,
                    unit = normalizedUnit,
                    calories = calories,
                    source = FoodItemSource.MANUAL_OVERRIDE,
                    confidence = ConfidenceLevel.HIGH,
                    notes = normalizedNotes,
                    createdAt = dateTimeProvider.nowInstant(),
                ),
            )
            rawEntryDao.updateStatus(rawEntry.id, RawEntryStatus.PARSED)
            markFoodChanged(rawEntry.logDate)

            val savedDefaultTrigger = if (saveAsDefault) {
                val parsed = parser.parse(rawEntry.rawText, rawEntry.logDate)
                parsed.shortcutTrigger
                    ?.takeIf { it.isNotBlank() }
                    ?.also { trigger ->
                        userDefaultDao.upsert(
                            UserDefaultEntity(
                                trigger = trigger,
                                name = trimmedName,
                                calories = calories / (normalizedAmount ?: 1.0),
                                unit = normalizedUnit ?: "serving",
                                notes = normalizedNotes,
                            ),
                        )
                    }
            } else {
                null
            }

            ManualResolveResult.Resolved(
                foodItemId = foodItemId,
                logDate = rawEntry.logDate,
                savedDefaultTrigger = savedDefaultTrigger,
            )
        }

    suspend fun exportLegacyHealthCsv(date: LocalDate): String {
        val items = foodItemDao.getActiveFoodItemsBetween(date, date)
        return legacyHealthCsvExporter.export(items)
            .also { markLegacyExported(date) }
    }

    suspend fun exportAuditCsv(date: LocalDate): String {
        val items = foodItemDao.getActiveFoodItemsBetween(date, date)
        return auditCsvExporter.export(items)
            .also { markAuditExported(date) }
    }

    private suspend fun markLegacyExported(date: LocalDate) {
        val existing = dailyStatusDao.getByDate(date)
        dailyStatusDao.upsert(
            (existing ?: DailyStatusEntity(logDate = date))
                .copy(legacyExportedAt = dateTimeProvider.nowInstant()),
        )
    }

    private suspend fun markFoodChanged(date: LocalDate) {
        val existing = dailyStatusDao.getByDate(date)
        dailyStatusDao.upsert(
            (existing ?: DailyStatusEntity(logDate = date))
                .copy(lastFoodChangedAt = dateTimeProvider.nowInstant()),
        )
    }

    private suspend fun markAuditExported(date: LocalDate) {
        val existing = dailyStatusDao.getByDate(date)
        dailyStatusDao.upsert(
            (existing ?: DailyStatusEntity(logDate = date))
                .copy(auditExportedAt = dateTimeProvider.nowInstant()),
        )
    }

    private suspend fun currentFoodDate(
        calendarToday: LocalDate,
        localTime: LocalTime,
    ): LocalDate =
        foodDayPolicy.defaultLogDate(
            calendarDate = calendarToday,
            localTime = localTime,
            dayBoundaryTime = appSettingsDao.getById()?.dayBoundaryTime,
        )

    sealed interface SubmitResult {
        val rawEntryId: Long
        val logDate: LocalDate

        data class Parsed(
            override val rawEntryId: Long,
            val foodItemId: Long,
            override val logDate: LocalDate,
        ) : SubmitResult

        data class Pending(
            override val rawEntryId: Long,
            override val logDate: LocalDate,
        ) : SubmitResult

        data class NonFood(
            override val rawEntryId: Long,
            override val logDate: LocalDate,
            val intent: EntryIntent,
        ) : SubmitResult
    }

    sealed interface ManualResolveResult {
        data class Resolved(
            val foodItemId: Long,
            val logDate: LocalDate,
            val savedDefaultTrigger: String?,
        ) : ManualResolveResult

        data object InvalidInput : ManualResolveResult
        data object NotFound : ManualResolveResult
        data object NotPending : ManualResolveResult
    }

    sealed interface DefaultUpdateResult {
        data object Updated : DefaultUpdateResult
        data object InvalidInput : DefaultUpdateResult
        data object NotFound : DefaultUpdateResult
    }

    sealed interface FoodItemUpdateResult {
        data object Updated : FoodItemUpdateResult
        data object InvalidInput : FoodItemUpdateResult
        data object NotFound : FoodItemUpdateResult
    }

    sealed interface FoodItemRemoveResult {
        data object Removed : FoodItemRemoveResult
        data object NotFound : FoodItemRemoveResult
    }

    companion object {
        val DEFAULT_TEA = UserDefaultEntity(
            trigger = "tea",
            name = "Tea",
            calories = 25.0,
            unit = "cup",
            notes = "English tea with skimmed milk and half a teaspoon of sugar",
        )
    }
}

private fun EntryIntent.toEntryKind(): EntryKind =
    when (this) {
        EntryIntent.QUERY -> EntryKind.QUERY
        EntryIntent.CORRECTION -> EntryKind.CORRECTION
        EntryIntent.EXPORT_COMMAND -> EntryKind.EXPORT_COMMAND
        EntryIntent.NOTE -> EntryKind.NOTE
        EntryIntent.UNKNOWN,
        EntryIntent.FOOD_LOG -> EntryKind.TEXT
    }

private fun EntryIntent.toRawEntryStatus(): RawEntryStatus =
    when (this) {
        EntryIntent.CORRECTION -> RawEntryStatus.NEEDS_REVIEW
        EntryIntent.UNKNOWN -> RawEntryStatus.NEEDS_REVIEW
        EntryIntent.QUERY,
        EntryIntent.EXPORT_COMMAND,
        EntryIntent.NOTE,
        EntryIntent.FOOD_LOG -> RawEntryStatus.PARSED
    }

private fun EntryIntent.placeholderNotes(): String? =
    when (this) {
        EntryIntent.QUERY -> "Conversational responses will be handled by a later phase."
        EntryIntent.EXPORT_COMMAND -> "Daily export commands will be handled by a later phase."
        EntryIntent.CORRECTION -> "Correction handling will be handled by a later phase."
        EntryIntent.NOTE -> "Notes will be handled by a later phase."
        EntryIntent.UNKNOWN -> "Input was not confidently classified."
        EntryIntent.FOOD_LOG -> null
    }
