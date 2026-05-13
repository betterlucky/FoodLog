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
import com.betterlucky.foodlog.data.entities.ProductEntity
import com.betterlucky.foodlog.data.entities.ProductSource
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import com.betterlucky.foodlog.data.entities.RawEntryStatus
import com.betterlucky.foodlog.data.entities.ShortcutPortionMode
import com.betterlucky.foodlog.data.entities.UserDefaultEntity
import com.betterlucky.foodlog.domain.export.AuditCsvExporter
import com.betterlucky.foodlog.domain.export.JournalCsvExporter
import com.betterlucky.foodlog.domain.export.JournalExportOptions
import com.betterlucky.foodlog.domain.export.LegacyHealthCsvExporter
import com.betterlucky.foodlog.domain.dayboundary.FoodDayPolicy
import com.betterlucky.foodlog.domain.intent.DeterministicIntentClassifier
import com.betterlucky.foodlog.domain.intent.EntryIntent
import com.betterlucky.foodlog.domain.label.LabelInputMode
import com.betterlucky.foodlog.domain.label.LabelNutritionFacts
import com.betterlucky.foodlog.domain.label.LabelPortionResolver
import com.betterlucky.foodlog.domain.parser.DeterministicParser
import com.betterlucky.foodlog.domain.parser.ParsedFoodPart
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
    private val journalCsvExporter: JournalCsvExporter = JournalCsvExporter(),
    private val foodDayPolicy: FoodDayPolicy = FoodDayPolicy(),
) {
    private val appSettingsDao = database.appSettingsDao()
    private val rawEntryDao = database.rawEntryDao()
    private val foodItemDao = database.foodItemDao()
    private val productDao = database.productDao()
    private val userDefaultDao = database.userDefaultDao()
    private val dailyStatusDao = database.dailyStatusDao()
    private val dailyWeightDao = database.dailyWeightDao()

    suspend fun seedDefaults() {
        if (appSettingsDao.getById() == null) {
            appSettingsDao.upsert(AppSettingsEntity())
        }

        if (userDefaultDao.countByLookupKey(DEFAULT_TEA.lookupKey) == 0) {
            userDefaultDao.upsert(DEFAULT_TEA)
        }
    }

    suspend fun submitText(
        input: String,
        targetLogDate: LocalDate? = null,
    ): SubmitResult =
        database.withTransaction {
            val intent = intentClassifier.classify(input)
            val calendarToday = dateTimeProvider.today()
            val localTime = dateTimeProvider.localTime()
            val defaultLogDate = targetLogDate
                ?: currentFoodDate(
                    calendarToday = calendarToday,
                    localTime = localTime,
                )
            val parsed = parser.parse(
                input = input,
                today = calendarToday,
                defaultLogDate = defaultLogDate,
            )
            if (targetLogDate != null && parsed.logDate != targetLogDate) {
                return@withTransaction SubmitResult.DateMismatch(
                    requestedLogDate = parsed.logDate,
                    selectedLogDate = targetLogDate,
                )
            }
            val createdAt = dateTimeProvider.nowInstant()
            val consumedTime = parsed.consumedTime ?: localTime

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

            val resolvedDefaults = resolveDefaults(parsed.parts)

            if (resolvedDefaults.isEmpty() || resolvedDefaults.any { (_, default) -> default == null }) {
                SubmitResult.Pending(rawEntryId = rawEntryId, logDate = parsed.logDate)
            } else {
                val foodItemIds = resolvedDefaults.map { (part, default) ->
                    checkNotNull(default)
                    val resolvedFood = default.resolveShortcutFood(part)
                    foodItemDao.insert(
                        FoodItemEntity(
                            rawEntryId = rawEntryId,
                            logDate = parsed.logDate,
                            consumedTime = consumedTime,
                            name = resolvedFood.name,
                            amount = resolvedFood.amount,
                            unit = resolvedFood.unit,
                            grams = resolvedFood.grams,
                            calories = resolvedFood.calories,
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

    suspend fun logActiveShortcut(
        lookupKey: String,
        targetLogDate: LocalDate,
    ): ShortcutLogResult =
        database.withTransaction {
            val normalizedLookupKey = lookupKey.shortcutTrigger()
            if (normalizedLookupKey.isBlank()) {
                return@withTransaction ShortcutLogResult.InvalidInput
            }
            val userDefault = userDefaultDao.getActiveDefault(normalizedLookupKey)
                ?: return@withTransaction ShortcutLogResult.NotFound
            val localTime = dateTimeProvider.localTime()
            val part = ParsedFoodPart(
                normalizedFoodText = normalizedLookupKey,
                shortcutTrigger = normalizedLookupKey,
            )
            val createdAt = dateTimeProvider.nowInstant()
            val rawEntryId = rawEntryDao.insert(
                RawEntryEntity(
                    createdAt = createdAt,
                    logDate = targetLogDate,
                    consumedTime = localTime,
                    rawText = normalizedLookupKey,
                    entryKind = EntryKind.TEXT,
                    status = RawEntryStatus.PARSED,
                ),
            )
            val resolvedFood = userDefault.resolveShortcutFood(part)
            val foodItemId = foodItemDao.insert(
                FoodItemEntity(
                    rawEntryId = rawEntryId,
                    logDate = targetLogDate,
                    consumedTime = localTime,
                    name = resolvedFood.name,
                    amount = resolvedFood.amount,
                    unit = resolvedFood.unit,
                    grams = resolvedFood.grams,
                    calories = resolvedFood.calories,
                    source = userDefault.source,
                    confidence = userDefault.confidence,
                    notes = userDefault.notes,
                    createdAt = createdAt,
                ),
            )
            markFoodChanged(targetLogDate)
            ShortcutLogResult.Logged(
                rawEntryId = rawEntryId,
                foodItemId = foodItemId,
                logDate = targetLogDate,
            )
        }

    suspend fun previewSubmission(
        input: String,
        targetLogDate: LocalDate,
    ): SubmissionPreviewResult {
        val trimmedInput = input.trim()
        if (trimmedInput.isBlank()) {
            return SubmissionPreviewResult.InvalidInput
        }

        val intent = intentClassifier.classify(trimmedInput)
        val calendarToday = dateTimeProvider.today()
        val parsed = parser.parse(
            input = trimmedInput,
            today = calendarToday,
            defaultLogDate = targetLogDate,
        )
        if (parsed.logDate != targetLogDate) {
            return SubmissionPreviewResult.DateMismatch(
                requestedLogDate = parsed.logDate,
                selectedLogDate = targetLogDate,
            )
        }
        if (intent != EntryIntent.FOOD_LOG) {
            return SubmissionPreviewResult.NonFood(
                rawText = trimmedInput,
                logDate = parsed.logDate,
                consumedTime = parsed.consumedTime,
                intent = intent,
            )
        }

        val parts = parsed.parts.map { part -> part.toPreviewPart() }
        return if (parts.isNotEmpty() && parts.all { it.default != null }) {
            SubmissionPreviewResult.Ready(
                rawText = trimmedInput,
                logDate = parsed.logDate,
                consumedTime = parsed.consumedTime,
                parts = parts,
            )
        } else {
            SubmissionPreviewResult.NeedsResolution(
                rawText = trimmedInput,
                logDate = parsed.logDate,
                consumedTime = parsed.consumedTime,
                parts = parts,
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

    fun currentLocalTime(): LocalTime =
        dateTimeProvider.localTime()

    suspend fun setDayBoundaryTime(dayBoundaryTime: LocalTime?) {
        if (appSettingsDao.getById() == null) {
            appSettingsDao.upsert(AppSettingsEntity(dayBoundaryTime = dayBoundaryTime))
        } else {
            appSettingsDao.updateDayBoundaryTime(dayBoundaryTime)
        }
    }

    suspend fun setLastLabelInputMode(lastLabelInputMode: String) {
        val normalizedMode = when (lastLabelInputMode) {
            AppSettingsEntity.LAST_LABEL_INPUT_MODE_MEASURE -> AppSettingsEntity.LAST_LABEL_INPUT_MODE_MEASURE
            else -> AppSettingsEntity.LAST_LABEL_INPUT_MODE_ITEMS
        }
        if (appSettingsDao.getById() == null) {
            appSettingsDao.upsert(AppSettingsEntity(lastLabelInputMode = normalizedMode))
        } else {
            appSettingsDao.updateLastLabelInputMode(normalizedMode)
        }
    }

    suspend fun deactivateDefault(lookupKey: String): DefaultUpdateResult {
        val normalizedLookupKey = lookupKey.shortcutTrigger()
        if (normalizedLookupKey.isBlank()) {
            return DefaultUpdateResult.InvalidInput
        }
        userDefaultDao.getActiveDefault(normalizedLookupKey)
            ?: return DefaultUpdateResult.NotFound
        userDefaultDao.deactivate(normalizedLookupKey)
        return DefaultUpdateResult.Updated
    }

    suspend fun updateShortcutDefaultAmount(lookupKey: String, amount: Double?): Boolean {
        val existing = userDefaultDao.getActiveDefault(lookupKey) ?: return false
        userDefaultDao.upsert(existing.copy(defaultAmount = amount))
        return true
    }

    suspend fun updateDefault(
        lookupKey: String,
        name: String,
        calories: Double,
        unit: String,
        notes: String?,
        defaultAmount: Double? = null,
        portionMode: ShortcutPortionMode? = null,
        itemUnit: String? = null,
        itemSizeAmount: Double? = null,
        itemSizeUnit: String? = null,
        kcalPer100g: Double? = null,
        kcalPer100ml: Double? = null,
    ): DefaultUpdateResult {
        val normalizedLookupKey = lookupKey.shortcutTrigger()
        val trimmedName = name.trim()
        val trimmedUnit = unit.trim()
        val normalizedNotes = notes?.trim().orEmpty().ifBlank { null }

        if (normalizedLookupKey.isBlank() || trimmedName.isBlank() || calories <= 0.0) {
            return DefaultUpdateResult.InvalidInput
        }

        val existing = userDefaultDao.getActiveDefault(normalizedLookupKey)
            ?: return DefaultUpdateResult.NotFound
        val normalizedItemUnit = itemUnit?.trim().orEmpty().ifBlank { null }
        val normalizedItemSizeUnit = itemSizeUnit?.trim().orEmpty().lowercase().ifBlank { null }
        if (
            (defaultAmount != null && defaultAmount <= 0.0) ||
            (itemSizeAmount != null && itemSizeAmount <= 0.0) ||
            (kcalPer100g != null && kcalPer100g <= 0.0) ||
            (kcalPer100ml != null && kcalPer100ml <= 0.0)
        ) {
            return DefaultUpdateResult.InvalidInput
        }
        val metadataChanged = normalizedItemUnit != existing.itemUnit ||
            itemSizeAmount != existing.itemSizeAmount ||
            normalizedItemSizeUnit != existing.itemSizeUnit ||
            kcalPer100g != existing.kcalPer100g ||
            kcalPer100ml != existing.kcalPer100ml

        userDefaultDao.upsert(
            existing.copy(
                name = trimmedName,
                calories = calories,
                unit = trimmedUnit.ifBlank { DEFAULT_PORTION_UNIT },
                notes = normalizedNotes,
                defaultAmount = defaultAmount,
                portionMode = portionMode ?: existing.portionMode,
                itemUnit = normalizedItemUnit,
                itemSizeAmount = itemSizeAmount,
                itemSizeUnit = normalizedItemSizeUnit,
                kcalPer100g = kcalPer100g,
                kcalPer100ml = kcalPer100ml,
                nutritionBasisName = when {
                    metadataChanged -> trimmedName
                    trimmedName == existing.name -> existing.nutritionBasisName
                    else -> existing.nutritionBasisName ?: existing.name
                },
            ),
        )
        return DefaultUpdateResult.Updated
    }

    suspend fun addDefault(
        lookupKey: String,
        name: String,
        calories: Double,
        unit: String,
        notes: String?,
        defaultAmount: Double? = null,
        portionMode: ShortcutPortionMode = ShortcutPortionMode.PLAIN,
    ): DefaultUpdateResult {
        val normalizedLookupKey = lookupKey.shortcutTrigger()
        val trimmedName = name.trim()
        val trimmedUnit = unit.trim()
        val normalizedNotes = notes?.trim().orEmpty().ifBlank { null }

        if (normalizedLookupKey.isBlank() || trimmedName.isBlank() || calories <= 0.0) {
            return DefaultUpdateResult.InvalidInput
        }

        userDefaultDao.upsert(
            UserDefaultEntity(
                lookupKey = normalizedLookupKey,
                name = trimmedName,
                calories = calories,
                unit = trimmedUnit.ifBlank { DEFAULT_PORTION_UNIT },
                notes = normalizedNotes,
                defaultAmount = defaultAmount,
                portionMode = portionMode,
            ),
        )
        return DefaultUpdateResult.Updated
    }

    suspend fun addOcrShortcut(
        input: OcrShortcutInput,
    ): DefaultUpdateResult {
        val normalizedTrigger = input.lookupKey.shortcutTrigger()
        val trimmedName = input.name.trim()
        val normalizedNotes = input.notes?.trim().orEmpty().ifBlank { null }
        val unit = input.unit.trim().ifBlank { "serving" }
        if (normalizedTrigger.isBlank() || trimmedName.isBlank() || input.caloriesPerUnit <= 0.0) {
            return DefaultUpdateResult.InvalidInput
        }

        userDefaultDao.upsert(
            UserDefaultEntity(
                lookupKey = normalizedTrigger,
                name = trimmedName,
                calories = input.caloriesPerUnit,
                unit = unit,
                notes = normalizedNotes,
                source = FoodItemSource.SAVED_LABEL,
                confidence = ConfidenceLevel.HIGH,
                defaultAmount = input.defaultAmount,
                portionMode = input.portionMode,
                itemUnit = input.itemUnit?.trim().orEmpty().ifBlank { null },
                itemSizeAmount = input.itemSizeAmount?.takeIf { it > 0.0 },
                itemSizeUnit = input.itemSizeUnit?.trim().orEmpty().ifBlank { null },
                kcalPer100g = input.kcalPer100g,
                kcalPer100ml = input.kcalPer100ml,
                nutritionBasisName = trimmedName,
            ),
        )
        return DefaultUpdateResult.Updated
    }

    suspend fun logLabelProduct(input: LabelProductLogInput): LabelLogResult =
        database.withTransaction {
            val name = input.name.trim()
            if (name.isBlank() || input.calories <= 0.0) {
                return@withTransaction LabelLogResult.InvalidInput
            }
            val now = dateTimeProvider.nowInstant()
            val consumedTime = input.consumedTime ?: dateTimeProvider.localTime()

            // Reuse an existing product by name, or insert a new one
            val existingProduct = productDao.getByName(name)
            val productId = if (existingProduct != null) {
                existingProduct.id
            } else {
                productDao.insert(
                    ProductEntity(
                        name = name,
                        servingSizeGrams = input.servingSizeGrams,
                        servingUnit = input.servingUnit,
                        kcalPer100g = input.kcalPer100g,
                        kcalPerServing = input.kcalPerServing,
                        source = ProductSource.PACKAGING_PHOTO,
                        confidence = ConfidenceLevel.HIGH,
                        lastLoggedGrams = input.grams,
                        createdAt = now,
                    ),
                )
            }

            val rawEntryId = rawEntryDao.insert(
                RawEntryEntity(
                    createdAt = now,
                    logDate = input.logDate,
                    consumedTime = consumedTime,
                    rawText = "Label scan: $name",
                    entryKind = EntryKind.TEXT,
                    status = RawEntryStatus.PARSED,
                ),
            )
            val foodItemId = foodItemDao.insert(
                FoodItemEntity(
                    rawEntryId = rawEntryId,
                    logDate = input.logDate,
                    consumedTime = consumedTime,
                    name = name,
                    productId = productId,
                    amount = input.amount ?: input.grams ?: 1.0,
                    unit = input.unit ?: if (input.grams != null) "g" else (input.servingUnit ?: "serving"),
                    grams = input.grams,
                    calories = input.calories,
                    source = FoodItemSource.SAVED_LABEL,
                    confidence = ConfidenceLevel.HIGH,
                    notes = input.notes,
                    createdAt = now,
                ),
            )
            markFoodChanged(input.logDate)
            LabelLogResult.Logged(foodItemId = foodItemId, logDate = input.logDate)
        }

    suspend fun updateFoodItem(
        id: Long,
        name: String,
        amount: Double?,
        unit: String?,
        calories: Double?,
        consumedTime: LocalTime,
        notes: String?,
        updateShortcutLookupKey: String? = null,
    ): FoodItemUpdateResult = database.withTransaction {
        val trimmedName = name.trim()
        val normalizedAmount = amount?.takeIf { it > 0.0 }
        val normalizedUnit = unit?.trim().orEmpty().ifBlank { null }
        val normalizedNotes = notes?.trim().orEmpty().ifBlank { null }

        if (trimmedName.isBlank() || (calories != null && calories <= 0.0)) {
            return@withTransaction FoodItemUpdateResult.InvalidInput
        }

        val existing = foodItemDao.getById(id)
            ?: return@withTransaction FoodItemUpdateResult.NotFound

        if (calories == null) {
            if (updateShortcutLookupKey != null) {
                return@withTransaction FoodItemUpdateResult.InvalidInput
            }
            val parsed = parser.parse(
                input = trimmedName,
                today = dateTimeProvider.today(),
                defaultLogDate = existing.logDate,
            )
            val resolvedDefaults = resolveDefaults(parsed.parts)

            if (resolvedDefaults.isEmpty() || resolvedDefaults.any { (_, default) -> default == null }) {
                return@withTransaction FoodItemUpdateResult.UnresolvedDefaults(
                    missingLookupKeys = resolvedDefaults
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
                val resolvedFood = default.resolveShortcutFood(part)
                foodItemDao.insert(
                    FoodItemEntity(
                        rawEntryId = existing.rawEntryId,
                        logDate = existing.logDate,
                        consumedTime = consumedTime,
                        name = resolvedFood.name,
                        amount = resolvedFood.amount,
                        unit = resolvedFood.unit,
                        grams = resolvedFood.grams,
                        calories = resolvedFood.calories,
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
                consumedTime = consumedTime,
                notes = null,
            )
            markFoodChanged(existing.logDate)
            return@withTransaction FoodItemUpdateResult.UpdatedFromDefaults
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
        updateShortcutLookupKey
            ?.shortcutTrigger()
            ?.takeIf { it.isNotBlank() }
            ?.let { lookupKey ->
                val shortcut = shortcutUpdateCandidateForFoodItem(existing)?.default
                    ?.takeIf { it.lookupKey == lookupKey }
                    ?: userDefaultDao.getActiveDefault(lookupKey)
                shortcut?.let {
                    userDefaultDao.upsert(
                        it.copy(
                            name = trimmedName,
                            calories = it.caloriesForUpdatedShortcut(
                                amount = normalizedAmount,
                                calories = calories,
                            ),
                            unit = normalizedUnit ?: DEFAULT_PORTION_UNIT,
                            notes = normalizedNotes,
                            defaultAmount = normalizedAmount,
                        ),
                    )
                }
            }
        markFoodChanged(existing.logDate)
        return@withTransaction FoodItemUpdateResult.Updated
    }

    suspend fun shortcutUpdateCandidateForFoodItem(id: Long): ShortcutUpdateCandidate? {
        val existing = foodItemDao.getById(id) ?: return null
        return shortcutUpdateCandidateForFoodItem(existing)
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
                part.toPreviewPart()
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
                part.saveDefaultLookupKey
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.also { trigger ->
                        userDefaultDao.upsert(
                            UserDefaultEntity(
                                lookupKey = trigger.shortcutTrigger(),
                                name = part.name.trim(),
                                calories = part.calories,
                                unit = part.unit?.trim().orEmpty().ifBlank { DEFAULT_PORTION_UNIT },
                                notes = part.notes?.trim().orEmpty().ifBlank { null },
                                defaultAmount = part.amount?.takeIf { it > 0.0 },
                            ),
                        )
                    }
            }
            rawEntryDao.updatePendingDetails(
                id = existing.rawEntryId,
                logDate = existing.logDate,
                rawText = trimmedRawText,
                consumedTime = consumedTime,
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
        consumedTime: LocalTime? = null,
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
            val resolvedTime = consumedTime ?: parsed.consumedTime ?: rawEntry.consumedTime
            val resolvedDefaults = resolveDefaults(parsed.parts)

            if (resolvedDefaults.isNotEmpty() && resolvedDefaults.all { (_, default) -> default != null }) {
                val createdAt = dateTimeProvider.nowInstant()
                val foodItemIds = resolvedDefaults.map { (part, default) ->
                    checkNotNull(default)
                    val resolvedFood = default.resolveShortcutFood(part)
                    foodItemDao.insert(
                        FoodItemEntity(
                            rawEntryId = rawEntry.id,
                            logDate = parsed.logDate,
                            consumedTime = resolvedTime,
                            name = resolvedFood.name,
                            amount = resolvedFood.amount,
                            unit = resolvedFood.unit,
                            grams = resolvedFood.grams,
                            calories = resolvedFood.calories,
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
                    consumedTime = resolvedTime,
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
                consumedTime = resolvedTime,
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
        val previewParts = parsed.parts.map { part -> part.toPreviewPart() }
        if (previewParts.size <= 1) {
            return PendingEntryResolutionPreviewResult.SinglePart(
                rawText = rawEntry.rawText.trim(),
                part = previewParts.singleOrNull(),
                logDate = rawEntry.logDate,
                consumedTime = rawEntry.consumedTime,
            )
        }

        return PendingEntryResolutionPreviewResult.Ready(
            rawText = rawEntry.rawText.trim(),
            logDate = rawEntry.logDate,
            consumedTime = rawEntry.consumedTime,
            parts = previewParts,
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
                part.saveDefaultLookupKey
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.also { trigger ->
                        userDefaultDao.upsert(
                            UserDefaultEntity(
                                lookupKey = trigger.shortcutTrigger(),
                                name = part.name.trim(),
                                calories = part.calories,
                                unit = part.unit?.trim().orEmpty().ifBlank { DEFAULT_PORTION_UNIT },
                                notes = part.notes?.trim().orEmpty().ifBlank { null },
                                defaultAmount = part.amount?.takeIf { it > 0.0 },
                            ),
                        )
                    }
                foodItemId
            }
            rawEntryDao.updatePendingDetails(
                id = rawEntry.id,
                logDate = rawEntry.logDate,
                rawText = trimmedRawText,
                consumedTime = rawEntry.consumedTime,
                notes = null,
            )
            rawEntryDao.updateStatus(rawEntry.id, RawEntryStatus.PARSED)
            markFoodChanged(rawEntry.logDate)
            PendingEntryUpdateResult.Parsed(
                foodItemIds = foodItemIds,
                logDate = rawEntry.logDate,
            )
        }

    suspend fun saveWizardSubmission(
        sourceRawEntryId: Long?,
        originalRawText: String,
        completedRawText: String,
        pendingRawText: String?,
        logDate: LocalDate,
        consumedTime: LocalTime?,
        parts: List<FoodItemEditReplacementPart>,
    ): WizardSubmissionResult =
        database.withTransaction {
            val trimmedOriginal = originalRawText.trim()
            val trimmedCompleted = completedRawText.trim()
            val trimmedPending = pendingRawText?.trim().orEmpty().ifBlank { null }
            if (trimmedOriginal.isBlank()) {
                return@withTransaction WizardSubmissionResult.InvalidInput
            }
            if (parts.any { it.name.isBlank() || it.calories <= 0.0 }) {
                return@withTransaction WizardSubmissionResult.InvalidInput
            }

            val sourceRawEntry = sourceRawEntryId?.let { rawEntryDao.getById(it) }
            if (sourceRawEntryId != null && sourceRawEntry == null) {
                return@withTransaction WizardSubmissionResult.NotFound
            }
            if (sourceRawEntry != null && sourceRawEntry.status != RawEntryStatus.PENDING) {
                return@withTransaction WizardSubmissionResult.NotPending
            }

            val resolvedTime = consumedTime ?: sourceRawEntry?.consumedTime ?: dateTimeProvider.localTime()
            val createdAt = dateTimeProvider.nowInstant()
            val parsedRawEntryId = if (parts.isNotEmpty()) {
                if (sourceRawEntry != null && trimmedPending == null) {
                    sourceRawEntry.id.also { id ->
                        rawEntryDao.updatePendingDetails(
                            id = id,
                            logDate = logDate,
                            rawText = trimmedCompleted.ifBlank { trimmedOriginal },
                            consumedTime = resolvedTime,
                            notes = null,
                        )
                    }
                } else {
                    rawEntryDao.insert(
                        RawEntryEntity(
                            createdAt = createdAt,
                            logDate = logDate,
                            consumedTime = resolvedTime,
                            rawText = trimmedCompleted.ifBlank { trimmedOriginal },
                            entryKind = EntryKind.TEXT,
                            status = RawEntryStatus.PENDING,
                        ),
                    )
                }
            } else {
                null
            }

            val foodItemIds = if (parsedRawEntryId != null) {
                val ids = insertReplacementParts(
                    rawEntryId = parsedRawEntryId,
                    logDate = logDate,
                    consumedTime = resolvedTime,
                    createdAt = createdAt,
                    parts = parts,
                )
                rawEntryDao.updateStatus(parsedRawEntryId, RawEntryStatus.PARSED)
                markFoodChanged(logDate)
                ids
            } else {
                emptyList()
            }

            val pendingRawEntryId = when {
                trimmedPending == null -> {
                    if (sourceRawEntry != null && parts.isNotEmpty()) {
                        null
                    } else {
                        sourceRawEntry?.id
                    }
                }

                sourceRawEntry != null -> {
                    rawEntryDao.updatePendingDetails(
                        id = sourceRawEntry.id,
                        logDate = logDate,
                        rawText = trimmedPending,
                        consumedTime = resolvedTime,
                        notes = null,
                    )
                    sourceRawEntry.id
                }

                else -> rawEntryDao.insert(
                    RawEntryEntity(
                        createdAt = createdAt,
                        logDate = logDate,
                        consumedTime = resolvedTime,
                        rawText = trimmedPending,
                        entryKind = EntryKind.TEXT,
                        status = RawEntryStatus.PENDING,
                    ),
                )
            }

            WizardSubmissionResult.Saved(
                foodItemIds = foodItemIds,
                pendingRawEntryId = pendingRawEntryId,
                logDate = logDate,
            )
        }

    suspend fun resolvePendingEntryManually(
        rawEntryId: Long,
        name: String,
        amount: Double?,
        unit: String?,
        calories: Double,
        notes: String?,
        consumedTime: LocalTime? = null,
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

            val resolvedTime = consumedTime ?: rawEntry.consumedTime
            val foodItemId = foodItemDao.insert(
                FoodItemEntity(
                    rawEntryId = rawEntry.id,
                    logDate = rawEntry.logDate,
                    consumedTime = resolvedTime,
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
            rawEntryDao.updatePendingDetails(
                id = rawEntry.id,
                logDate = rawEntry.logDate,
                rawText = rawEntry.rawText,
                consumedTime = resolvedTime,
                notes = null,
            )
            rawEntryDao.updateStatus(rawEntry.id, RawEntryStatus.PARSED)
            markFoodChanged(rawEntry.logDate)

            val savedDefaultLookupKey = if (saveAsDefault) {
                val parsed = parser.parse(rawEntry.rawText, rawEntry.logDate)
                parsed.shortcutTrigger
                    ?.takeIf { it.isNotBlank() }
                    ?.also { trigger ->
                        userDefaultDao.upsert(
                            UserDefaultEntity(
                                lookupKey = trigger,
                                name = trimmedName,
                                calories = calories,
                                unit = normalizedUnit ?: DEFAULT_PORTION_UNIT,
                                notes = normalizedNotes,
                                defaultAmount = normalizedAmount,
                            ),
                        )
                    }
            } else {
                null
            }

            ManualResolveResult.Resolved(
                foodItemId = foodItemId,
                logDate = rawEntry.logDate,
                savedDefaultLookupKey = savedDefaultLookupKey,
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

            val savedDefaultLookupKey = if (saveAsDefault) {
                trimmedName.shortcutTrigger()
                    .takeIf { it.isNotBlank() }
                    ?.also { trigger ->
                        userDefaultDao.upsert(
                            UserDefaultEntity(
                                lookupKey = trigger,
                                name = trimmedName,
                                calories = calories,
                                unit = normalizedUnit ?: DEFAULT_PORTION_UNIT,
                                notes = normalizedNotes,
                                defaultAmount = normalizedAmount,
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
                savedDefaultLookupKey = savedDefaultLookupKey,
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

    suspend fun removeDailyWeight(logDate: LocalDate): DailyWeightRemoveResult =
        database.withTransaction {
            val removedCount = dailyWeightDao.deleteByDate(logDate)
            if (removedCount == 0) {
                DailyWeightRemoveResult.NotFound
            } else {
                markFoodChanged(logDate)
                DailyWeightRemoveResult.Removed
            }
        }

    suspend fun exportLegacyHealthCsv(date: LocalDate): ExportedCsv {
        val exported = buildLegacyHealthCsv(date)
        markLegacyHealthCsvExported(date, exported.fileName)
        return exported
    }

    suspend fun buildLegacyHealthCsv(date: LocalDate): ExportedCsv {
        val items = foodItemDao.getActiveFoodItemsBetween(date, date)
        val weight = dailyWeightDao.getByDate(date)
        val fileName = healthMonitorFileName(date)
        return ExportedCsv(
            csv = legacyHealthCsvExporter.export(items, weight),
            fileName = fileName,
        )
    }

    suspend fun markLegacyHealthCsvExported(
        date: LocalDate,
        fileName: String,
    ) {
        markLegacyExported(date, fileName)
    }

    suspend fun exportAuditCsv(date: LocalDate): ExportedCsv {
        val items = foodItemDao.getActiveFoodItemsBetween(date, date)
        val fileName = auditFileName(date)
        return ExportedCsv(
            csv = auditCsvExporter.export(items),
            fileName = fileName,
        ).also { markAuditExported(date, fileName) }
    }

    suspend fun buildJournalCsv(
        startDate: LocalDate,
        endDate: LocalDate,
        options: JournalExportOptions,
    ): ExportedCsv {
        require(!endDate.isBefore(startDate)) { "End date must be on or after start date." }

        val items = foodItemDao.getActiveFoodItemsBetween(startDate, endDate)
        val productIds = items.mapNotNull { it.productId }.distinct()
        val productsById = if (productIds.isEmpty()) {
            emptyMap()
        } else {
            productDao.getByIds(productIds).associateBy { it.id }
        }
        val weights = if (options.includeWeight) {
            dailyWeightDao.getBetween(startDate, endDate)
        } else {
            emptyList()
        }
        return ExportedCsv(
            csv = journalCsvExporter.export(
                items = items,
                productsById = productsById,
                dailyWeights = weights,
                options = options,
            ),
            fileName = journalFileName(startDate, endDate),
        )
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

    private suspend fun resolveDefaults(parts: List<ParsedFoodPart>): List<Pair<ParsedFoodPart, UserDefaultEntity?>> =
        parts.map { part -> part to part.shortcutTrigger?.let { userDefaultDao.getActiveDefault(it) } }

    private suspend fun ParsedFoodPart.toPreviewPart(): FoodItemDefaultEditPreviewPart {
        val default = shortcutTrigger?.let { userDefaultDao.getActiveDefault(it) }
        val resolved = default?.resolveShortcutFood(this)
        return FoodItemDefaultEditPreviewPart(
            inputText = normalizedFoodText,
            lookupKey = shortcutTrigger,
            quantity = quantity,
            quantityUnit = quantityUnit,
            default = default,
            resolvedName = resolved?.name ?: normalizedFoodText,
            resolvedAmount = resolved?.amount ?: quantity,
            resolvedUnit = resolved?.unit ?: quantityUnit.orEmpty(),
            resolvedCalories = resolved?.calories,
            resolvedNotes = default?.notes.orEmpty(),
        )
    }

    private suspend fun shortcutUpdateCandidateForFoodItem(existing: FoodItemEntity): ShortcutUpdateCandidate? {
        val rawEntry = rawEntryDao.getById(existing.rawEntryId) ?: return null
        val parsed = parser.parse(
            input = rawEntry.rawText,
            today = dateTimeProvider.today(),
            defaultLogDate = rawEntry.logDate,
        )
        val part = parsed.parts.singleOrNull() ?: return null
        val lookupKey = part.shortcutTrigger?.takeIf { it.isNotBlank() } ?: return null
        val default = userDefaultDao.getActiveDefault(lookupKey) ?: return null
        val resolved = default.resolveShortcutFood(part)
        return ShortcutUpdateCandidate(
            lookupKey = lookupKey,
            name = resolved.name,
            amount = resolved.amount,
            unit = resolved.unit,
            calories = resolved.calories,
            notes = default.notes,
            default = default,
        )
    }

    private suspend fun insertReplacementParts(
        rawEntryId: Long,
        logDate: LocalDate,
        consumedTime: LocalTime?,
        createdAt: java.time.Instant,
        parts: List<FoodItemEditReplacementPart>,
    ): List<Long> =
        parts.map { part ->
            val foodItemId = foodItemDao.insert(
                FoodItemEntity(
                    rawEntryId = rawEntryId,
                    logDate = logDate,
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
            part.saveDefaultLookupKey
                ?.shortcutTrigger()
                ?.takeIf { it.isNotBlank() }
                ?.also { trigger ->
                    userDefaultDao.upsert(
                        UserDefaultEntity(
                            lookupKey = trigger,
                            name = part.name.trim(),
                            calories = part.calories,
                            unit = part.unit?.trim().orEmpty().ifBlank { DEFAULT_PORTION_UNIT },
                            notes = part.notes?.trim().orEmpty().ifBlank { null },
                            defaultAmount = part.amount?.takeIf { it > 0.0 },
                        ),
                    )
                }
            foodItemId
        }

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

        data class DateMismatch(
            val requestedLogDate: LocalDate,
            val selectedLogDate: LocalDate,
        ) : SubmitResult {
            override val rawEntryId: Long = 0
            override val logDate: LocalDate = requestedLogDate
        }
    }

    sealed interface ShortcutLogResult {
        data class Logged(
            val rawEntryId: Long,
            val foodItemId: Long,
            val logDate: LocalDate,
        ) : ShortcutLogResult

        data object InvalidInput : ShortcutLogResult
        data object NotFound : ShortcutLogResult
    }

    sealed interface SubmissionPreviewResult {
        data class Ready(
            val rawText: String,
            val logDate: LocalDate,
            val consumedTime: LocalTime?,
            val parts: List<FoodItemDefaultEditPreviewPart>,
        ) : SubmissionPreviewResult

        data class NeedsResolution(
            val rawText: String,
            val logDate: LocalDate,
            val consumedTime: LocalTime?,
            val parts: List<FoodItemDefaultEditPreviewPart>,
        ) : SubmissionPreviewResult

        data class NonFood(
            val rawText: String,
            val logDate: LocalDate,
            val consumedTime: LocalTime?,
            val intent: EntryIntent,
        ) : SubmissionPreviewResult

        data class DateMismatch(
            val requestedLogDate: LocalDate,
            val selectedLogDate: LocalDate,
        ) : SubmissionPreviewResult

        data object InvalidInput : SubmissionPreviewResult
    }

    sealed interface WizardSubmissionResult {
        data class Saved(
            val foodItemIds: List<Long>,
            val pendingRawEntryId: Long?,
            val logDate: LocalDate,
        ) : WizardSubmissionResult

        data object InvalidInput : WizardSubmissionResult
        data object NotFound : WizardSubmissionResult
        data object NotPending : WizardSubmissionResult
    }

    sealed interface ManualResolveResult {
        data class Resolved(
            val foodItemId: Long,
            val logDate: LocalDate,
            val savedDefaultLookupKey: String?,
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
            val savedDefaultLookupKey: String?,
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
            val missingLookupKeys: List<String>,
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
        val lookupKey: String?,
        val quantity: Double,
        val quantityUnit: String?,
        val default: UserDefaultEntity?,
        val resolvedName: String,
        val resolvedAmount: Double?,
        val resolvedUnit: String?,
        val resolvedCalories: Double?,
        val resolvedNotes: String,
    )

    data class FoodItemEditReplacementPart(
        val name: String,
        val amount: Double?,
        val unit: String?,
        val calories: Double,
        val source: FoodItemSource,
        val confidence: ConfidenceLevel,
        val notes: String?,
        val saveDefaultLookupKey: String? = null,
    )

    data class ShortcutUpdateCandidate(
        val lookupKey: String,
        val name: String,
        val amount: Double?,
        val unit: String?,
        val calories: Double,
        val notes: String?,
        val default: UserDefaultEntity,
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
            val logDate: LocalDate,
            val consumedTime: LocalTime?,
            val parts: List<FoodItemDefaultEditPreviewPart>,
        ) : PendingEntryResolutionPreviewResult

        data class SinglePart(
            val rawText: String,
            val part: FoodItemDefaultEditPreviewPart?,
            val logDate: LocalDate,
            val consumedTime: LocalTime?,
        ) : PendingEntryResolutionPreviewResult
        data object NotFound : PendingEntryResolutionPreviewResult
        data object NotPending : PendingEntryResolutionPreviewResult
    }

    sealed interface DailyWeightResult {
        data object Saved : DailyWeightResult
        data object InvalidInput : DailyWeightResult
    }

    sealed interface DailyWeightRemoveResult {
        data object Removed : DailyWeightRemoveResult
        data object NotFound : DailyWeightRemoveResult
    }

    data class LabelProductLogInput(
        val name: String,
        val kcalPer100g: Double?,
        val servingSizeGrams: Double?,
        val servingUnit: String?,
        val kcalPerServing: Double?,
        val amount: Double?,
        val unit: String?,
        val grams: Double?,
        val calories: Double,
        val logDate: LocalDate,
        val consumedTime: LocalTime?,
        val notes: String?,
    )

    data class OcrShortcutInput(
        val lookupKey: String,
        val name: String,
        val caloriesPerUnit: Double,
        val unit: String,
        val notes: String?,
        val defaultAmount: Double?,
        val portionMode: ShortcutPortionMode,
        val itemUnit: String?,
        val itemSizeAmount: Double?,
        val itemSizeUnit: String?,
        val kcalPer100g: Double?,
        val kcalPer100ml: Double?,
    )

    sealed interface LabelLogResult {
        data class Logged(
            val foodItemId: Long,
            val logDate: LocalDate,
        ) : LabelLogResult

        data object InvalidInput : LabelLogResult
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

        fun journalFileName(
            startDate: LocalDate,
            endDate: LocalDate,
        ): String =
            "foodlog_journal_${startDate}_to_${endDate}.csv"

        val DEFAULT_TEA = UserDefaultEntity(
            lookupKey = "tea",
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

private const val DEFAULT_PORTION_UNIT = "portion"

private data class ResolvedShortcutFood(
    val name: String,
    val amount: Double?,
    val unit: String?,
    val grams: Double?,
    val calories: Double,
)

private fun UserDefaultEntity.resolveShortcutFood(part: ParsedFoodPart): ResolvedShortcutFood {
    val explicitQuantity = part.hasExplicitQuantity()
    val defaultAmount = defaultAmount?.takeIf { it > 0.0 }
    val resolved = when (portionMode) {
        ShortcutPortionMode.ITEM -> resolveItemShortcutFood(part, explicitQuantity, defaultAmount)
        ShortcutPortionMode.MEASURE -> resolveMeasureShortcutFood(part, explicitQuantity, defaultAmount)
        ShortcutPortionMode.PLAIN -> resolvePlainShortcutFood(part, explicitQuantity, defaultAmount)
    }
    return resolved.copy(name = name)
}

private fun UserDefaultEntity.caloriesForUpdatedShortcut(
    amount: Double?,
    calories: Double,
): Double =
    when (portionMode) {
        ShortcutPortionMode.ITEM,
        ShortcutPortionMode.MEASURE ->
            amount?.takeIf { it > 0.0 }?.let { calories / it } ?: calories
        ShortcutPortionMode.PLAIN -> calories
    }

private fun UserDefaultEntity.resolvePlainShortcutFood(
    part: ParsedFoodPart,
    explicitQuantity: Boolean,
    defaultAmount: Double?,
): ResolvedShortcutFood {
    val multiplier = if (explicitQuantity) part.quantity else 1.0
    val amount = (defaultAmount ?: 1.0) * multiplier
    return ResolvedShortcutFood(
        name = name,
        amount = amount,
        unit = unit,
        grams = if (unit == "g") amount else null,
        calories = calories * multiplier,
    )
}

private fun UserDefaultEntity.resolveItemShortcutFood(
    part: ParsedFoodPart,
    explicitQuantity: Boolean,
    defaultAmount: Double?,
): ResolvedShortcutFood {
    val amount = if (explicitQuantity) part.quantity else defaultAmount ?: 1.0
    val facts = toLabelFacts()
    val resolution = facts?.let {
        LabelPortionResolver.resolve(it, LabelInputMode.ITEMS, amount.toString())
    }
    return ResolvedShortcutFood(
        name = name,
        amount = resolution?.amount ?: amount,
        unit = resolution?.unit ?: itemUnit ?: unit,
        grams = resolution?.grams,
        calories = resolution?.calories ?: calories * amount,
    )
}

private fun UserDefaultEntity.resolveMeasureShortcutFood(
    part: ParsedFoodPart,
    explicitQuantity: Boolean,
    defaultAmount: Double?,
): ResolvedShortcutFood {
    val explicitMeasure = explicitQuantity && part.quantityUnit in setOf("g", "ml")
    val amount = when {
        explicitMeasure -> part.quantity
        explicitQuantity -> (defaultAmount ?: 1.0) * part.quantity
        else -> defaultAmount ?: 1.0
    }
    val resolvedUnit = when {
        explicitMeasure -> part.quantityUnit
        unit in setOf("g", "ml") -> unit
        itemSizeUnit in setOf("g", "ml") -> itemSizeUnit
        else -> unit
    } ?: unit
    val facts = toLabelFacts()
    val resolution = facts?.let {
        LabelPortionResolver.resolve(it, LabelInputMode.MEASURE, "${amount.formatPlainNumber()}$resolvedUnit")
    }
    return ResolvedShortcutFood(
        name = name,
        amount = resolution?.amount ?: amount,
        unit = resolution?.unit ?: resolvedUnit,
        grams = resolution?.grams ?: amount.takeIf { resolvedUnit == "g" },
        calories = resolution?.calories ?: calories * amount,
    )
}

private fun ParsedFoodPart.hasExplicitQuantity(): Boolean =
    quantityUnit != null || quantity != 1.0 || normalizedFoodText.shortcutTrigger() != shortcutTrigger

private fun UserDefaultEntity.toLabelFacts(): LabelNutritionFacts? =
    when {
        portionMode == ShortcutPortionMode.ITEM &&
            itemUnit != null &&
            itemSizeAmount != null &&
            itemSizeUnit == "g" &&
            kcalPer100g != null ->
            LabelNutritionFacts(
                rawText = "",
                kcalPer100g = kcalPer100g,
                packageSizeGrams = itemSizeAmount,
                packageItemCount = 1.0,
                packageItemUnit = itemUnit,
            )
        portionMode == ShortcutPortionMode.ITEM &&
            itemUnit != null &&
            itemSizeAmount != null &&
            itemSizeUnit == "ml" &&
            kcalPer100ml != null ->
            LabelNutritionFacts(
                rawText = "",
                kcalPer100ml = kcalPer100ml,
                packageSizeMilliliters = itemSizeAmount,
                packageItemCount = 1.0,
                packageItemUnit = itemUnit,
            )
        portionMode == ShortcutPortionMode.MEASURE && kcalPer100g != null ->
            LabelNutritionFacts(rawText = "", kcalPer100g = kcalPer100g)
        portionMode == ShortcutPortionMode.MEASURE && kcalPer100ml != null ->
            LabelNutritionFacts(rawText = "", kcalPer100ml = kcalPer100ml)
        else -> null
    }

private fun Double.formatPlainNumber(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

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
