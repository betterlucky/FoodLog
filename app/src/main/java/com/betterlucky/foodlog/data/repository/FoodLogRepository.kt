package com.betterlucky.foodlog.data.repository

import androidx.room.withTransaction
import com.betterlucky.foodlog.data.db.FoodLogDatabase
import com.betterlucky.foodlog.data.entities.AppSettingsEntity
import com.betterlucky.foodlog.data.entities.EntryKind
import com.betterlucky.foodlog.data.entities.ConfidenceLevel
import com.betterlucky.foodlog.data.entities.DailyStatusEntity
import com.betterlucky.foodlog.data.entities.DailyWeightEntity
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
    private val dailyWeightDao = database.dailyWeightDao()

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

            val resolvedDefaults = parsed.parts
                .map { part -> part to part.shortcutTrigger?.let { userDefaultDao.getActiveDefault(it) } }

            if (resolvedDefaults.isEmpty() || resolvedDefaults.any { (_, default) -> default == null }) {
                SubmitResult.Pending(rawEntryId = rawEntryId, logDate = parsed.logDate)
            } else {
                val foodItemIds = resolvedDefaults.map { (part, default) ->
                    checkNotNull(default)
                    foodItemDao.insert(
                        FoodItemEntity(
                            rawEntryId = rawEntryId,
                            logDate = parsed.logDate,
                            consumedTime = consumedTime,
                            name = default.name,
                            amount = part.quantity,
                            unit = default.unit,
                            calories = default.calories * part.quantity,
                            source = default.source,
                            confidence = default.confidence,
                            notes = default.notes,
                            createdAt = createdAt,
                        ),
                    )
                }
                rawEntryDao.updateStatus(rawEntryId, RawEntryStatus.PARSED)
                markFoodChanged(parsed.logDate)
                SubmitResult.Parsed(
                    rawEntryId = rawEntryId,
                    foodItemIds = foodItemIds,
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

    fun observeDailyWeightForDate(date: LocalDate) =
        dailyWeightDao.observeByDate(date)

    fun observeAppSettings() =
        appSettingsDao.observeById()

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
        calories: Double?,
        consumedTime: LocalTime,
        notes: String?,
    ): FoodItemUpdateResult {
        val trimmedName = name.trim()
        val normalizedAmount = amount?.takeIf { it > 0.0 }
        val normalizedUnit = unit?.trim().orEmpty().ifBlank { null }
        val normalizedNotes = notes?.trim().orEmpty().ifBlank { null }

        if (trimmedName.isBlank() || (calories != null && calories <= 0.0)) {
            return FoodItemUpdateResult.InvalidInput
        }

        val existing = foodItemDao.getById(id)
            ?: return FoodItemUpdateResult.NotFound

        if (calories == null) {
            val parsed = parser.parse(
                input = trimmedName,
                today = dateTimeProvider.today(),
                defaultLogDate = existing.logDate,
            )
            val resolvedDefaults = parsed.parts
                .map { part -> part to part.shortcutTrigger?.let { userDefaultDao.getActiveDefault(it) } }

            if (resolvedDefaults.isEmpty() || resolvedDefaults.any { (_, default) -> default == null }) {
                return FoodItemUpdateResult.UnresolvedDefaults(
                    missingTriggers = resolvedDefaults
                        .filter { (_, default) -> default == null }
                        .mapNotNull { (part, _) -> part.shortcutTrigger }
                        .distinct(),
                )
            }

            val createdAt = dateTimeProvider.nowInstant()
            val overrideNotes = normalizedNotes?.takeIf { it != existing.notes }
            foodItemDao.deleteById(existing.id)
            resolvedDefaults.forEach { (part, default) ->
                checkNotNull(default)
                foodItemDao.insert(
                    FoodItemEntity(
                        rawEntryId = existing.rawEntryId,
                        logDate = existing.logDate,
                        consumedTime = consumedTime,
                        name = default.name,
                        amount = part.quantity,
                        unit = default.unit,
                        calories = default.calories * part.quantity,
                        source = default.source,
                        confidence = default.confidence,
                        notes = overrideNotes ?: default.notes,
                        createdAt = createdAt,
                    ),
                )
            }
            rawEntryDao.updatePendingDetails(
                id = existing.rawEntryId,
                logDate = existing.logDate,
                rawText = trimmedName,
                notes = null,
            )
            markFoodChanged(existing.logDate)
            return FoodItemUpdateResult.UpdatedFromDefaults
        }

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

    suspend fun previewFoodItemDefaultEdit(
        id: Long,
        name: String,
    ): FoodItemDefaultEditPreviewResult {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return FoodItemDefaultEditPreviewResult.InvalidInput
        }

        val existing = foodItemDao.getById(id)
            ?: return FoodItemDefaultEditPreviewResult.NotFound

        val parsed = parser.parse(
            input = trimmedName,
            today = dateTimeProvider.today(),
            defaultLogDate = existing.logDate,
        )
        if (parsed.parts.isEmpty()) {
            return FoodItemDefaultEditPreviewResult.InvalidInput
        }

        return FoodItemDefaultEditPreviewResult.Ready(
            rawText = trimmedName,
            parts = parsed.parts.map { part ->
                FoodItemDefaultEditPreviewPart(
                    inputText = part.normalizedFoodText,
                    trigger = part.shortcutTrigger,
                    quantity = part.quantity,
                    default = part.shortcutTrigger?.let { userDefaultDao.getActiveDefault(it) },
                )
            },
        )
    }

    suspend fun replaceFoodItemWithResolvedEditParts(
        id: Long,
        rawText: String,
        consumedTime: LocalTime,
        parts: List<FoodItemEditReplacementPart>,
    ): FoodItemUpdateResult =
        database.withTransaction {
            val trimmedRawText = rawText.trim()
            if (trimmedRawText.isBlank() || parts.isEmpty() || parts.any { it.name.isBlank() || it.calories <= 0.0 }) {
                return@withTransaction FoodItemUpdateResult.InvalidInput
            }

            val existing = foodItemDao.getById(id)
                ?: return@withTransaction FoodItemUpdateResult.NotFound

            val createdAt = dateTimeProvider.nowInstant()
            foodItemDao.deleteById(existing.id)
            parts.forEach { part ->
                foodItemDao.insert(
                    FoodItemEntity(
                        rawEntryId = existing.rawEntryId,
                        logDate = existing.logDate,
                        consumedTime = consumedTime,
                        name = part.name.trim(),
                        amount = part.amount?.takeIf { it > 0.0 },
                        unit = part.unit?.trim().orEmpty().ifBlank { null },
                        calories = part.calories,
                        source = part.source,
                        confidence = part.confidence,
                        notes = part.notes?.trim().orEmpty().ifBlank { null },
                        createdAt = createdAt,
                    ),
                )
                part.saveDefaultTrigger
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.also { trigger ->
                        userDefaultDao.upsert(
                            UserDefaultEntity(
                                trigger = trigger,
                                name = part.name.trim(),
                                calories = part.calories / (part.amount?.takeIf { it > 0.0 } ?: 1.0),
                                unit = part.unit?.trim().orEmpty().ifBlank { "serving" },
                                notes = part.notes?.trim().orEmpty().ifBlank { null },
                            ),
                        )
                    }
            }
            rawEntryDao.updatePendingDetails(
                id = existing.rawEntryId,
                logDate = existing.logDate,
                rawText = trimmedRawText,
                notes = null,
            )
            markFoodChanged(existing.logDate)
            FoodItemUpdateResult.UpdatedFromDefaults
        }

    suspend fun removeFoodItem(id: Long): FoodItemRemoveResult {
        val existing = foodItemDao.getById(id)
            ?: return FoodItemRemoveResult.NotFound

        foodItemDao.deleteById(existing.id)
        markFoodChanged(existing.logDate)
        return FoodItemRemoveResult.Removed
    }

    suspend fun removePendingEntry(id: Long): PendingEntryRemoveResult =
        database.withTransaction {
            val existing = rawEntryDao.getById(id)
                ?: return@withTransaction PendingEntryRemoveResult.NotFound

            if (existing.status != RawEntryStatus.PENDING) {
                return@withTransaction PendingEntryRemoveResult.NotPending
            }

            rawEntryDao.deleteById(existing.id)
            PendingEntryRemoveResult.Removed
        }

    suspend fun updatePendingEntry(
        rawEntryId: Long,
        rawText: String,
        amount: Double?,
        unit: String?,
        calories: Double?,
        notes: String?,
    ): PendingEntryUpdateResult =
        database.withTransaction {
            val trimmedRawText = rawText.trim()
            val normalizedAmount = amount?.takeIf { it > 0.0 }
            val normalizedUnit = unit?.trim().orEmpty().ifBlank { null }
            val normalizedNotes = pendingNotes(
                amount = normalizedAmount,
                unit = normalizedUnit,
                calories = calories?.takeIf { it > 0.0 },
                notes = notes,
            )

            if (trimmedRawText.isBlank()) {
                return@withTransaction PendingEntryUpdateResult.InvalidInput
            }

            val rawEntry = rawEntryDao.getById(rawEntryId)
                ?: return@withTransaction PendingEntryUpdateResult.NotFound

            if (rawEntry.status != RawEntryStatus.PENDING) {
                return@withTransaction PendingEntryUpdateResult.NotPending
            }

            val parsed = parser.parse(
                input = trimmedRawText,
                today = dateTimeProvider.today(),
                defaultLogDate = rawEntry.logDate,
            )
            val resolvedDefaults = parsed.parts
                .map { part -> part to part.shortcutTrigger?.let { userDefaultDao.getActiveDefault(it) } }

            if (resolvedDefaults.isNotEmpty() && resolvedDefaults.all { (_, default) -> default != null }) {
                val createdAt = dateTimeProvider.nowInstant()
                val foodItemIds = resolvedDefaults.map { (part, default) ->
                    checkNotNull(default)
                    foodItemDao.insert(
                        FoodItemEntity(
                            rawEntryId = rawEntry.id,
                            logDate = parsed.logDate,
                            consumedTime = rawEntry.consumedTime,
                            name = default.name,
                            amount = part.quantity,
                            unit = default.unit,
                            calories = default.calories * part.quantity,
                            source = default.source,
                            confidence = default.confidence,
                            notes = default.notes,
                            createdAt = createdAt,
                        ),
                    )
                }
                rawEntryDao.updatePendingDetails(
                    id = rawEntry.id,
                    logDate = parsed.logDate,
                    rawText = trimmedRawText,
                    notes = null,
                )
                rawEntryDao.updateStatus(rawEntry.id, RawEntryStatus.PARSED)
                markFoodChanged(parsed.logDate)
                return@withTransaction PendingEntryUpdateResult.Parsed(
                    foodItemIds = foodItemIds,
                    logDate = parsed.logDate,
                )
            }

            rawEntryDao.updatePendingDetails(
                id = rawEntry.id,
                logDate = rawEntry.logDate,
                rawText = trimmedRawText,
                notes = normalizedNotes,
            )
            PendingEntryUpdateResult.Updated
        }

    suspend fun previewPendingEntryResolution(rawEntryId: Long): PendingEntryResolutionPreviewResult {
        val rawEntry = rawEntryDao.getById(rawEntryId)
            ?: return PendingEntryResolutionPreviewResult.NotFound

        if (rawEntry.status != RawEntryStatus.PENDING) {
            return PendingEntryResolutionPreviewResult.NotPending
        }

        val parsed = parser.parse(
            input = rawEntry.rawText,
            today = dateTimeProvider.today(),
            defaultLogDate = rawEntry.logDate,
        )
        if (parsed.parts.size <= 1) {
            return PendingEntryResolutionPreviewResult.SinglePart
        }

        return PendingEntryResolutionPreviewResult.Ready(
            rawText = rawEntry.rawText.trim(),
            parts = parsed.parts.map { part ->
                FoodItemDefaultEditPreviewPart(
                    inputText = part.normalizedFoodText,
                    trigger = part.shortcutTrigger,
                    quantity = part.quantity,
                    default = part.shortcutTrigger?.let { userDefaultDao.getActiveDefault(it) },
                )
            },
        )
    }

    suspend fun resolvePendingEntryParts(
        rawEntryId: Long,
        rawText: String,
        parts: List<FoodItemEditReplacementPart>,
    ): PendingEntryUpdateResult =
        database.withTransaction {
            val trimmedRawText = rawText.trim()
            if (trimmedRawText.isBlank() || parts.isEmpty() || parts.any { it.name.isBlank() || it.calories <= 0.0 }) {
                return@withTransaction PendingEntryUpdateResult.InvalidInput
            }

            val rawEntry = rawEntryDao.getById(rawEntryId)
                ?: return@withTransaction PendingEntryUpdateResult.NotFound

            if (rawEntry.status != RawEntryStatus.PENDING) {
                return@withTransaction PendingEntryUpdateResult.NotPending
            }

            val createdAt = dateTimeProvider.nowInstant()
            val foodItemIds = parts.map { part ->
                val foodItemId = foodItemDao.insert(
                    FoodItemEntity(
                        rawEntryId = rawEntry.id,
                        logDate = rawEntry.logDate,
                        consumedTime = rawEntry.consumedTime,
                        name = part.name.trim(),
                        amount = part.amount?.takeIf { it > 0.0 },
                        unit = part.unit?.trim().orEmpty().ifBlank { null },
                        calories = part.calories,
                        source = part.source,
                        confidence = part.confidence,
                        notes = part.notes?.trim().orEmpty().ifBlank { null },
                        createdAt = createdAt,
                    ),
                )
                part.saveDefaultTrigger
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.also { trigger ->
                        userDefaultDao.upsert(
                            UserDefaultEntity(
                                trigger = trigger,
                                name = part.name.trim(),
                                calories = part.calories / (part.amount?.takeIf { it > 0.0 } ?: 1.0),
                                unit = part.unit?.trim().orEmpty().ifBlank { "serving" },
                                notes = part.notes?.trim().orEmpty().ifBlank { null },
                            ),
                        )
                    }
                foodItemId
            }
            rawEntryDao.updatePendingDetails(
                id = rawEntry.id,
                logDate = rawEntry.logDate,
                rawText = trimmedRawText,
                notes = null,
            )
            rawEntryDao.updateStatus(rawEntry.id, RawEntryStatus.PARSED)
            markFoodChanged(rawEntry.logDate)
            PendingEntryUpdateResult.Parsed(
                foodItemIds = foodItemIds,
                logDate = rawEntry.logDate,
            )
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

    suspend fun addFoodItemManually(
        logDate: LocalDate,
        name: String,
        amount: Double?,
        unit: String?,
        calories: Double,
        consumedTime: LocalTime?,
        notes: String?,
        saveAsDefault: Boolean = false,
    ): ManualAddResult =
        database.withTransaction {
            val trimmedName = name.trim()
            val normalizedAmount = amount?.takeIf { it > 0.0 }
            val normalizedUnit = unit?.trim().orEmpty().ifBlank { null }
            val normalizedNotes = notes?.trim().orEmpty().ifBlank { null }
            val resolvedTime = consumedTime ?: dateTimeProvider.localTime()
            val createdAt = dateTimeProvider.nowInstant()

            if (trimmedName.isBlank() || calories <= 0.0) {
                return@withTransaction ManualAddResult.InvalidInput
            }

            val rawEntryId = rawEntryDao.insert(
                RawEntryEntity(
                    createdAt = createdAt,
                    logDate = logDate,
                    consumedTime = resolvedTime,
                    rawText = "Manual entry: $trimmedName",
                    entryKind = EntryKind.TEXT,
                    status = RawEntryStatus.PARSED,
                    notes = "Created from manual add form.",
                ),
            )
            val foodItemId = foodItemDao.insert(
                FoodItemEntity(
                    rawEntryId = rawEntryId,
                    logDate = logDate,
                    consumedTime = resolvedTime,
                    name = trimmedName,
                    amount = normalizedAmount,
                    unit = normalizedUnit,
                    calories = calories,
                    source = FoodItemSource.MANUAL_OVERRIDE,
                    confidence = ConfidenceLevel.HIGH,
                    notes = normalizedNotes,
                    createdAt = createdAt,
                ),
            )
            markFoodChanged(logDate)

            val savedDefaultTrigger = if (saveAsDefault) {
                trimmedName.shortcutTrigger()
                    .takeIf { it.isNotBlank() }
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

            ManualAddResult.Added(
                rawEntryId = rawEntryId,
                foodItemId = foodItemId,
                logDate = logDate,
                savedDefaultTrigger = savedDefaultTrigger,
            )
        }

    suspend fun upsertDailyWeight(
        logDate: LocalDate,
        weightKg: Double,
        measuredTime: LocalTime?,
    ): DailyWeightResult {
        if (weightKg <= 0.0) {
            return DailyWeightResult.InvalidInput
        }

        database.withTransaction {
            val now = dateTimeProvider.nowInstant()
            val existing = dailyWeightDao.getByDate(logDate)
            dailyWeightDao.upsert(
                DailyWeightEntity(
                    logDate = logDate,
                    weightKg = weightKg,
                    measuredTime = measuredTime ?: dateTimeProvider.localTime(),
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
            markFoodChanged(logDate)
        }
        return DailyWeightResult.Saved
    }

    suspend fun exportLegacyHealthCsv(date: LocalDate): ExportedCsv {
        val items = foodItemDao.getActiveFoodItemsBetween(date, date)
        val weight = dailyWeightDao.getByDate(date)
        val fileName = healthMonitorFileName(date)
        return ExportedCsv(
            csv = legacyHealthCsvExporter.export(items, weight),
            fileName = fileName,
        ).also { markLegacyExported(date, fileName) }
    }

    suspend fun exportAuditCsv(date: LocalDate): ExportedCsv {
        val items = foodItemDao.getActiveFoodItemsBetween(date, date)
        val fileName = auditFileName(date)
        return ExportedCsv(
            csv = auditCsvExporter.export(items),
            fileName = fileName,
        ).also { markAuditExported(date, fileName) }
    }

    private suspend fun markLegacyExported(
        date: LocalDate,
        fileName: String,
    ) {
        val existing = dailyStatusDao.getByDate(date)
        dailyStatusDao.upsert(
            (existing ?: DailyStatusEntity(logDate = date))
                .copy(
                    legacyExportedAt = dateTimeProvider.nowInstant(),
                    legacyExportFileName = fileName,
                ),
        )
    }

    private suspend fun markFoodChanged(date: LocalDate) {
        val existing = dailyStatusDao.getByDate(date)
        dailyStatusDao.upsert(
            (existing ?: DailyStatusEntity(logDate = date))
                .copy(lastFoodChangedAt = dateTimeProvider.nowInstant()),
        )
    }

    private suspend fun markAuditExported(
        date: LocalDate,
        fileName: String,
    ) {
        val existing = dailyStatusDao.getByDate(date)
        dailyStatusDao.upsert(
            (existing ?: DailyStatusEntity(logDate = date))
                .copy(
                    auditExportedAt = dateTimeProvider.nowInstant(),
                    auditExportFileName = fileName,
                ),
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
            val foodItemIds: List<Long>,
            override val logDate: LocalDate,
        ) : SubmitResult {
            val foodItemId: Long
                get() = foodItemIds.first()
        }

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

    sealed interface ManualAddResult {
        data class Added(
            val rawEntryId: Long,
            val foodItemId: Long,
            val logDate: LocalDate,
            val savedDefaultTrigger: String?,
        ) : ManualAddResult

        data object InvalidInput : ManualAddResult
    }

    sealed interface DefaultUpdateResult {
        data object Updated : DefaultUpdateResult
        data object InvalidInput : DefaultUpdateResult
        data object NotFound : DefaultUpdateResult
    }

    sealed interface FoodItemUpdateResult {
        data object Updated : FoodItemUpdateResult
        data object UpdatedFromDefaults : FoodItemUpdateResult
        data object InvalidInput : FoodItemUpdateResult
        data class UnresolvedDefaults(
            val missingTriggers: List<String>,
        ) : FoodItemUpdateResult
        data object NotFound : FoodItemUpdateResult
    }

    sealed interface FoodItemDefaultEditPreviewResult {
        data class Ready(
            val rawText: String,
            val parts: List<FoodItemDefaultEditPreviewPart>,
        ) : FoodItemDefaultEditPreviewResult

        data object InvalidInput : FoodItemDefaultEditPreviewResult
        data object NotFound : FoodItemDefaultEditPreviewResult
    }

    data class FoodItemDefaultEditPreviewPart(
        val inputText: String,
        val trigger: String?,
        val quantity: Double,
        val default: UserDefaultEntity?,
    )

    data class FoodItemEditReplacementPart(
        val name: String,
        val amount: Double?,
        val unit: String?,
        val calories: Double,
        val source: FoodItemSource,
        val confidence: ConfidenceLevel,
        val notes: String?,
        val saveDefaultTrigger: String? = null,
    )

    sealed interface FoodItemRemoveResult {
        data object Removed : FoodItemRemoveResult
        data object NotFound : FoodItemRemoveResult
    }

    sealed interface PendingEntryRemoveResult {
        data object Removed : PendingEntryRemoveResult
        data object NotFound : PendingEntryRemoveResult
        data object NotPending : PendingEntryRemoveResult
    }

    sealed interface PendingEntryUpdateResult {
        data object Updated : PendingEntryUpdateResult
        data class Parsed(
            val foodItemIds: List<Long>,
            val logDate: LocalDate,
        ) : PendingEntryUpdateResult

        data object InvalidInput : PendingEntryUpdateResult
        data object NotFound : PendingEntryUpdateResult
        data object NotPending : PendingEntryUpdateResult
    }

    sealed interface PendingEntryResolutionPreviewResult {
        data class Ready(
            val rawText: String,
            val parts: List<FoodItemDefaultEditPreviewPart>,
        ) : PendingEntryResolutionPreviewResult

        data object SinglePart : PendingEntryResolutionPreviewResult
        data object NotFound : PendingEntryResolutionPreviewResult
        data object NotPending : PendingEntryResolutionPreviewResult
    }

    sealed interface DailyWeightResult {
        data object Saved : DailyWeightResult
        data object InvalidInput : DailyWeightResult
    }

    data class ExportedCsv(
        val csv: String,
        val fileName: String,
    )

    companion object {
        fun healthMonitorFileName(date: LocalDate): String =
            "food_log_$date.csv"

        fun auditFileName(date: LocalDate): String =
            "foodlog-audit-$date.csv"

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

private fun String.shortcutTrigger(): String =
    trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")

private fun pendingNotes(
    amount: Double?,
    unit: String?,
    calories: Double?,
    notes: String?,
): String? {
    val details = buildList {
        val quantity = listOfNotNull(amount?.formatDraftNumber(), unit).joinToString(" ").ifBlank { null }
        if (quantity != null) add("Draft quantity: $quantity")
        if (calories != null) add("Draft calories: ${calories.formatDraftNumber()}")
        notes?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
    }
    return details.joinToString("\n").ifBlank { null }
}

private fun Double.formatDraftNumber(): String =
    if (rem(1.0) == 0.0) toInt().toString() else toString()
