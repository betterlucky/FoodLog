package com.betterlucky.foodlog.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.betterlucky.foodlog.data.entities.AppSettingsEntity
import com.betterlucky.foodlog.data.entities.ConfidenceLevel
import com.betterlucky.foodlog.data.entities.FoodItemSource
import com.betterlucky.foodlog.data.entities.ShortcutPortionMode
import com.betterlucky.foodlog.data.ocr.LabelOcrReader
import com.betterlucky.foodlog.data.ocr.LabelOcrResult
import com.betterlucky.foodlog.data.repository.FoodLogRepository
import com.betterlucky.foodlog.domain.intent.EntryIntent
import com.betterlucky.foodlog.domain.label.LabelInputMode
import com.betterlucky.foodlog.domain.label.LabelNutritionFacts
import com.betterlucky.foodlog.domain.label.LabelPortionResolution
import com.betterlucky.foodlog.domain.label.LabelPortionResolver
import com.betterlucky.foodlog.domain.parser.TimeTextParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

data class LoggedFoodEditResolution(
    val rawText: String,
    val parts: List<LoggedFoodEditResolutionPart>,
)

data class LoggedFoodEditResolutionPart(
    val inputText: String,
    val lookupKey: String?,
    val resolvedByDefault: Boolean,
    val name: String,
    val amount: Double?,
    val unit: String,
    val calories: Double?,
    val notes: String,
)

data class LoggedFoodEditResolvedPartInput(
    val inputText: String,
    val lookupKey: String?,
    val resolvedByDefault: Boolean,
    val name: String,
    val amount: String,
    val unit: String,
    val calories: String,
    val notes: String,
    val saveAsDefault: Boolean,
)

data class PendingEntryDraft(
    val name: String,
    val amount: String,
    val unit: String,
    val time: String,
)

data class LabelReviewState(
    val facts: LabelNutritionFacts,
    val isProcessing: Boolean = false,
)

enum class LoggingWizardSource {
    FreeText,
    Pending,
    Shortcut,
    Label,
}

data class LoggingWizardSession(
    val source: LoggingWizardSource,
    val sourceRawEntryId: Long? = null,
    val originalRawText: String,
    val logDate: LocalDate,
    val consumedTime: LocalTime?,
    val timeText: String,
    val timeWasSpecified: Boolean,
    val timeConfirmed: Boolean,
    val parts: List<LoggingWizardPartDraft>,
    val currentPartIndex: Int = 0,
    val labelFacts: LabelNutritionFacts? = null,
    val labelInputMode: LabelInputMode = LabelInputMode.ITEMS,
    val shortcutPortionMode: ShortcutPortionMode = ShortcutPortionMode.PLAIN,
    val saveShortcutDefaultAmount: Boolean = false,
)

data class LoggingWizardPartDraft(
    val inputText: String,
    val lookupKey: String?,
    val resolvedByDefault: Boolean,
    val name: String,
    val amount: String,
    val unit: String,
    val calories: String,
    val notes: String = "",
    val saveAsShortcut: Boolean = false,
    val shortcutItemSizeAmount: String = "",
    val shortcutItemSizeUnit: String = "",
    val deferred: Boolean = false,
) {
    val hasPositiveCalories: Boolean
        get() = calories.toDoubleOrNull()?.let { it > 0.0 } == true

    val isComplete: Boolean
        get() = name.isNotBlank() && hasPositiveCalories && !deferred

    val needsInput: Boolean
        get() = !isComplete && !deferred
}

private fun Double.formatForMessage(): String =
    if (this % 1.0 == 0.0) {
        toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", this)
    }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val repository: FoodLogRepository,
    private val labelOcrReader: LabelOcrReader? = null,
) : ViewModel() {
    private var currentLabelOcrJob: Job? = null
    private var labelOcrRequestId: Long = 0

    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val inputDrafts = MutableStateFlow<Map<LocalDate, String>>(emptyMap())
    private val message = MutableStateFlow<String?>(null)

    private val _labelReview = MutableStateFlow<LabelReviewState?>(null)
    val labelReview: StateFlow<LabelReviewState?> = _labelReview.asStateFlow()

    private val _shortcutUpdateCandidates =
        MutableStateFlow<Map<Long, FoodLogRepository.ShortcutUpdateCandidate?>>(emptyMap())
    val shortcutUpdateCandidates: StateFlow<Map<Long, FoodLogRepository.ShortcutUpdateCandidate?>> =
        _shortcutUpdateCandidates.asStateFlow()

    private val _loggingWizard = MutableStateFlow<LoggingWizardSession?>(null)
    val loggingWizard: StateFlow<LoggingWizardSession?> = _loggingWizard.asStateFlow()

    private val dayStateCache = ConcurrentHashMap<LocalDate, StateFlow<TodayDayUiState>>()

    val uiState: StateFlow<TodayUiState> =
        combine(
            selectedDate,
            inputDrafts,
            message,
            repository.observeActiveDefaults(),
            repository.observeAppSettings(),
        ) { date, drafts, msg, defaults, settings ->
            TodayUiState(
                selectedDate = date,
                inputDrafts = drafts,
                message = msg,
                userDefaults = defaults,
                dayBoundaryTime = settings?.dayBoundaryTime,
                lastLabelInputMode = settings?.lastLabelInputMode
                    ?: AppSettingsEntity.LAST_LABEL_INPUT_MODE_ITEMS,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TodayUiState(selectedDate = selectedDate.value),
        )

    fun dayState(date: LocalDate): StateFlow<TodayDayUiState> =
        dayStateCache.computeIfAbsent(date) {
            combine(
                repository.observeFoodItemsForDate(date),
                repository.observeCaloriesForDate(date),
                repository.observePendingEntriesForDate(date),
                repository.observeDailyStatusForDate(date),
                repository.observeDailyWeightForDate(date),
            ) { items, calories, pending, status, weight ->
                TodayDayUiState(
                    date = date,
                    items = items,
                    totalCalories = calories,
                    pendingEntries = pending,
                    dailyStatus = status,
                    dailyWeight = weight,
                    isReady = true,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = TodayDayUiState(date = date),
            )
        }

    init {
        viewModelScope.launch {
            repository.seedDefaults()
            setSelectedDate(repository.currentFoodDate())
        }
    }

    fun inputTextForDate(date: LocalDate): String =
        inputDrafts.value[date].orEmpty()

    fun onInputChanged(date: LocalDate, value: String) {
        inputDrafts.update { it + (date to value) }
        message.value = null
    }

    fun submit(date: LocalDate) {
        val text = inputDrafts.value[date].orEmpty()
        if (text.isBlank()) return
        previewOrSubmitText(text, date)
    }

    fun logShortcut(lookupKey: String) {
        viewModelScope.launch {
            message.value = when (val result = repository.logActiveShortcut(lookupKey, selectedDate.value)) {
                is FoodLogRepository.ShortcutLogResult.Logged -> "Logged for ${result.logDate}"
                FoodLogRepository.ShortcutLogResult.InvalidInput -> "That shortcut could not be logged."
                FoodLogRepository.ShortcutLogResult.NotFound -> "That shortcut no longer exists."
            }
        }
    }

    fun processLabelImage(uri: Uri) {
        val reader = labelOcrReader ?: run {
            message.value = "Label scan is not available on this device."
            return
        }
        currentLabelOcrJob?.cancel()
        val requestId = ++labelOcrRequestId
        currentLabelOcrJob = viewModelScope.launch {
            _labelReview.value = LabelReviewState(facts = LabelNutritionFacts(rawText = ""), isProcessing = true)
            val result = runCatching { reader.read(uri) }
                .getOrElse { LabelOcrResult.Failed("Could not read label.") }
            if (!isActive || requestId != labelOcrRequestId || _labelReview.value == null) return@launch
            currentLabelOcrJob = null
            when (result) {
                is LabelOcrResult.Read -> {
                    _labelReview.value = null
                    _loggingWizard.value = result.facts.toLabelLoggingWizardSession(
                        logDate = selectedDate.value,
                        consumedTime = null,
                        defaultTime = currentWizardTime(),
                        inputMode = LabelInputMode.fromStorage(uiState.value.lastLabelInputMode),
                    )
                }
                is LabelOcrResult.Failed -> {
                    _labelReview.value = null
                    message.value = result.message
                }
            }
        }
    }

    fun setLastLabelInputMode(mode: LabelInputMode) {
        viewModelScope.launch {
            _loggingWizard.update { session ->
                session?.withLabelInputMode(mode) ?: session
            }
            if (_loggingWizard.value?.source == LoggingWizardSource.Label) {
                repository.setLastLabelInputMode(mode.storageValue)
            }
        }
    }

    fun updateLoggingWizardPortionMode(index: Int, mode: LabelInputMode, part: LoggingWizardPartDraft) {
        _loggingWizard.update { session ->
            session
                ?.withLabelInputMode(mode)
                ?.copy(
                    parts = session.parts.mapIndexed { partIndex, existing ->
                        if (partIndex == index) part else existing
                    },
                )
        }
        if (_loggingWizard.value?.source == LoggingWizardSource.Label) {
            viewModelScope.launch {
                repository.setLastLabelInputMode(mode.storageValue)
            }
        }
    }

    fun clearLabelReview() {
        labelOcrRequestId++
        currentLabelOcrJob?.cancel()
        currentLabelOcrJob = null
        _labelReview.value = null
    }

    fun openPendingEntryWizard(rawEntryId: Long) {
        viewModelScope.launch {
            when (val preview = repository.previewPendingEntryResolution(rawEntryId)) {
                is FoodLogRepository.PendingEntryResolutionPreviewResult.Ready ->
                    _loggingWizard.value = preview.toLoggingWizardSession(rawEntryId = rawEntryId, defaultTime = currentWizardTime())
                is FoodLogRepository.PendingEntryResolutionPreviewResult.SinglePart -> {
                    val part = preview.part
                    if (part != null) {
                        _loggingWizard.value = LoggingWizardSession(
                            source = LoggingWizardSource.Pending,
                            sourceRawEntryId = rawEntryId,
                            originalRawText = preview.rawText,
                            logDate = preview.logDate,
                            consumedTime = preview.consumedTime,
                            timeText = (preview.consumedTime ?: currentWizardTime()).toString(),
                            timeWasSpecified = preview.consumedTime != null,
                            timeConfirmed = preview.consumedTime != null,
                            parts = listOf(part.toLoggingWizardPartDraft()),
                        ).withFirstIncompletePart()
                    }
                }
                FoodLogRepository.PendingEntryResolutionPreviewResult.NotFound ->
                    message.value = "That pending entry no longer exists."
                FoodLogRepository.PendingEntryResolutionPreviewResult.NotPending ->
                    message.value = "That entry has already been handled."
            }
        }
    }

    fun clearLoggingWizard() {
        _loggingWizard.value = null
    }

    fun loadShortcutUpdateCandidate(foodItemId: Long) {
        viewModelScope.launch {
            val candidate = repository.shortcutUpdateCandidateForFoodItem(foodItemId)
            _shortcutUpdateCandidates.update { it + (foodItemId to candidate) }
        }
    }

    fun updateLoggingWizardPart(index: Int, part: LoggingWizardPartDraft) {
        _loggingWizard.update { session ->
            session?.copy(
                parts = session.parts.mapIndexed { partIndex, existing ->
                    if (partIndex == index) part else existing
                },
            )
        }
    }

    fun setLoggingWizardCurrentPart(index: Int) {
        _loggingWizard.update { session ->
            session?.copy(currentPartIndex = index.coerceIn(0, session.parts.lastIndex.coerceAtLeast(0)))
        }
    }

    fun updateLoggingWizardTime(timeText: String) {
        _loggingWizard.update { session ->
            session?.copy(timeText = timeText, timeConfirmed = false)
        }
    }

    fun confirmLoggingWizardTime() {
        _loggingWizard.update { session ->
            session?.copy(timeConfirmed = true)
        }
    }

    fun saveLoggingWizard() {
        val session = _loggingWizard.value ?: return
        if (session.source == LoggingWizardSource.Label) {
            saveLabelWizard(session)
            return
        }
        val parsedTime = TimeTextParser.parseOrNull(session.timeText)
        if (session.timeText.isBlank() || parsedTime == null) {
            message.value = "Time must use HH:mm, such as 08:30."
            return
        }
        val completedParts = session.parts.filter { it.isComplete }
        val pendingParts = session.parts.filter { !it.isComplete }
        val pendingRawText = pendingParts
            .joinToString(" and ") { it.inputText.ifBlank { it.name } }
            .ifBlank { null }
        val completedRawText = completedParts
            .joinToString(" and ") { it.inputText.ifBlank { it.name } }
            .ifBlank { session.originalRawText }
        val replacements = completedParts.mapNotNull { part ->
            val calories = part.calories.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return@mapNotNull null
            val shortcutResolution = if (session.source == LoggingWizardSource.Shortcut && session.labelFacts != null) {
                LabelPortionResolver.resolve(session.labelFacts, session.labelInputMode, part.amount)
                    .takeIf { it.isValidAmount }
            } else {
                null
            }
            FoodLogRepository.FoodItemEditReplacementPart(
                name = part.name,
                amount = shortcutResolution?.amount
                    ?: part.amount.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull(),
                unit = shortcutResolution?.unit ?: part.unit,
                calories = shortcutResolution?.calories ?: calories,
                source = if (part.resolvedByDefault) FoodItemSource.USER_DEFAULT else FoodItemSource.MANUAL_OVERRIDE,
                confidence = ConfidenceLevel.HIGH,
                notes = part.notes,
                saveDefaultLookupKey = if (part.saveAsShortcut && !part.resolvedByDefault) {
                    part.lookupKey?.takeIf { it.isNotBlank() } ?: part.name
                } else {
                    null
                },
            )
        }

        if (completedParts.isNotEmpty() && replacements.size != completedParts.size) {
            message.value = "Add item names and calories before saving."
            return
        }
        if (completedParts.isEmpty() && pendingRawText == null) {
            message.value = "Complete or defer at least one item."
            return
        }

        viewModelScope.launch {
            val result = repository.saveWizardSubmission(
                sourceRawEntryId = session.sourceRawEntryId,
                originalRawText = session.originalRawText,
                completedRawText = completedRawText,
                pendingRawText = pendingRawText,
                logDate = session.logDate,
                consumedTime = parsedTime,
                parts = replacements,
            )
            message.value = when (result) {
                is FoodLogRepository.WizardSubmissionResult.Saved -> {
                    if (session.saveShortcutDefaultAmount) {
                        completedParts.firstOrNull()
                            ?.let { part ->
                                val amount = session.labelFacts
                                    ?.let { facts ->
                                        LabelPortionResolver.resolve(
                                            facts = facts,
                                            mode = session.labelInputMode,
                                            amountText = part.amount,
                                        ).takeIf { it.isValidAmount }?.amount
                                    }
                                    ?: part.amount.trim().toDoubleOrNull()
                                val lookupKey = part.lookupKey
                                if (lookupKey != null && amount != null && amount > 0.0) {
                                    repository.updateShortcutDefaultAmount(lookupKey, amount)
                                }
                            }
                    }
                    _loggingWizard.value = null
                    _labelReview.value = null
                    clearInputDraft(session.logDate)
                    when {
                        result.foodItemIds.isNotEmpty() && result.pendingRawEntryId != null ->
                            "Logged ${result.foodItemIds.size} item(s); kept ${pendingParts.size} pending."
                        result.foodItemIds.isNotEmpty() -> "Logged ${result.foodItemIds.size} item(s)."
                        result.pendingRawEntryId != null -> "Kept pending."
                        else -> null
                    }
                }
                FoodLogRepository.WizardSubmissionResult.InvalidInput -> "Add item names and calories before saving."
                FoodLogRepository.WizardSubmissionResult.NotFound -> "That pending entry no longer exists."
                FoodLogRepository.WizardSubmissionResult.NotPending -> "That entry has already been handled."
            }
        }
    }

    private fun saveLabelWizard(session: LoggingWizardSession) {
        val facts = session.labelFacts ?: return
        val part = session.parts.singleOrNull() ?: return
        val parsedCalories = part.calories.toDoubleOrNull()?.takeIf { it > 0.0 }
        val parsedTime = TimeTextParser.parseOrNull(session.timeText)
        val resolvedPortion = LabelPortionResolver.resolve(facts, session.labelInputMode, part.amount)
        if (part.name.isBlank() || parsedCalories == null || !resolvedPortion.isValidAmount) {
            message.value = "Add item name and calories."
            return
        }
        if (session.timeText.isBlank() || parsedTime == null) {
            message.value = "Time must use HH:mm, such as 08:30."
            return
        }
        viewModelScope.launch {
            val result = repository.logLabelProduct(
                FoodLogRepository.LabelProductLogInput(
                    name = part.name,
                    kcalPer100g = facts.kcalPer100g,
                    servingSizeGrams = facts.servingSizeGrams,
                    servingUnit = facts.servingUnit,
                    kcalPerServing = facts.kcalPerServing,
                    amount = resolvedPortion.amount,
                    unit = resolvedPortion.unit,
                    grams = resolvedPortion.grams,
                    calories = parsedCalories,
                    logDate = session.logDate,
                    consumedTime = parsedTime,
                    notes = part.notes.ifBlank { null },
                ),
            )
            message.value = when (result) {
                is FoodLogRepository.LabelLogResult.Logged -> {
                    val shortcutResult = if (part.saveAsShortcut) {
                        val itemSize = shortcutItemSizeFor(facts, session.labelInputMode, resolvedPortion, part)
                        val shortcutMode = shortcutModeForLabelSave(
                            inputMode = session.labelInputMode,
                            itemSize = itemSize,
                            facts = facts,
                        )
                        val shortcutCalories = shortcutCaloriesPerUnit(
                            mode = shortcutMode,
                            resolvedPortion = resolvedPortion,
                            parsedCalories = parsedCalories,
                            itemSize = itemSize,
                            facts = facts,
                        )
                        repository.addOcrShortcut(
                            FoodLogRepository.OcrShortcutInput(
                                lookupKey = part.name.trim().lowercase(),
                                name = part.name,
                                caloriesPerUnit = shortcutCalories,
                                unit = shortcutUnit(
                                    mode = shortcutMode,
                                    resolvedUnit = resolvedPortion.unit,
                                    itemSizeUnit = itemSize?.unit,
                                ),
                                notes = part.notes.ifBlank { null },
                                defaultAmount = resolvedPortion.amount,
                                portionMode = shortcutMode,
                                itemUnit = resolvedPortion.unit?.takeIf { shortcutMode == ShortcutPortionMode.ITEM },
                                itemSizeAmount = itemSize?.amount,
                                itemSizeUnit = itemSize?.unit,
                                kcalPer100g = facts.kcalPer100g,
                                kcalPer100ml = facts.kcalPer100ml,
                            ),
                        )
                    } else {
                        null
                    }
                    _loggingWizard.value = null
                    when (shortcutResult) {
                        FoodLogRepository.DefaultUpdateResult.Updated ->
                            "Logged item and saved shortcut '${part.name.trim()}'."
                        FoodLogRepository.DefaultUpdateResult.InvalidInput,
                        FoodLogRepository.DefaultUpdateResult.NotFound ->
                            "Logged item, but could not save shortcut."
                        null -> "Logged item."
                    }
                }
                FoodLogRepository.LabelLogResult.InvalidInput -> "Add item name and calories."
            }
        }
    }

    private fun submitText(
        text: String,
        targetDate: LocalDate,
        clearInput: Boolean,
    ) {
        viewModelScope.launch {
            val result = repository.submitText(
                input = text,
                targetLogDate = targetDate,
            )
            if (clearInput) {
                if (result !is FoodLogRepository.SubmitResult.DateMismatch) {
                    clearInputDraft(targetDate)
                }
            }
            message.value = when (result) {
                is FoodLogRepository.SubmitResult.Parsed ->
                    if (result.foodItemIds.size == 1) {
                        "Logged for ${result.logDate}"
                    } else {
                        "Logged ${result.foodItemIds.size} items for ${result.logDate}"
                    }
                is FoodLogRepository.SubmitResult.Pending -> "Saved as pending for ${result.logDate}"
                is FoodLogRepository.SubmitResult.NonFood -> result.intent.placeholderMessage()
                is FoodLogRepository.SubmitResult.DateMismatch ->
                    "Switch to ${result.requestedLogDate} before adding that entry."
            }
        }
    }

    private fun previewOrSubmitText(text: String, targetDate: LocalDate) {
        viewModelScope.launch {
            when (val preview = repository.previewSubmission(text, targetDate)) {
                is FoodLogRepository.SubmissionPreviewResult.Ready -> {
                    submitText(text, targetDate, clearInput = true)
                }
                is FoodLogRepository.SubmissionPreviewResult.NeedsResolution -> {
                    _loggingWizard.value = preview.toLoggingWizardSession(defaultTime = currentWizardTime())
                    message.value = null
                }
                is FoodLogRepository.SubmissionPreviewResult.NonFood -> submitText(text, targetDate, clearInput = true)
                is FoodLogRepository.SubmissionPreviewResult.DateMismatch ->
                    message.value = "Switch to ${preview.requestedLogDate} before adding that entry."
                FoodLogRepository.SubmissionPreviewResult.InvalidInput -> Unit
            }
        }
    }

    private fun clearInputDraft(date: LocalDate) {
        inputDrafts.update { it - date }
    }

    private fun currentWizardTime(): LocalTime =
        repository.currentLocalTime().truncatedTo(ChronoUnit.MINUTES)

    private fun setSelectedDate(date: LocalDate) {
        if (selectedDate.value != date) {
            message.value = null
        }
        selectedDate.value = date
        pruneDayStateCache(centerDate = date)
    }

    private fun pruneDayStateCache(centerDate: LocalDate) {
        val firstKeptDate = centerDate.minusDays(DAY_STATE_CACHE_RADIUS_DAYS)
        val lastKeptDate = centerDate.plusDays(DAY_STATE_CACHE_RADIUS_DAYS)
        dayStateCache.keys.removeIf { date ->
            date < firstKeptDate || date > lastKeptDate
        }
    }

    fun selectDate(date: LocalDate) {
        setSelectedDate(date)
    }

    fun selectCurrentFoodDate(onSelected: (LocalDate) -> Unit) {
        viewModelScope.launch {
            val date = repository.currentFoodDate()
            setSelectedDate(date)
            onSelected(date)
        }
    }

    fun previousDay() {
        setSelectedDate(selectedDate.value.minusDays(1))
    }

    fun nextDay() {
        setSelectedDate(selectedDate.value.plusDays(1))
    }

    fun exportLegacyCsv(
        date: LocalDate,
        onExported: (String, String) -> String,
        onAdvanceToDate: (LocalDate) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                val exported = repository.buildLegacyHealthCsv(date)
                onExported(exported.csv, exported.fileName)
                repository.markLegacyHealthCsvExported(date, exported.fileName)
                exported.fileName
            }.onSuccess { fileName ->
                val nextDate = date.plusDays(1)
                setSelectedDate(nextDate)
                message.value = "Saved $fileName. Moved to $nextDate."
                onAdvanceToDate(nextDate)
            }.onFailure {
                message.value = "Could not save Lodestone export."
            }
        }
    }

    fun exportAuditCsv(date: LocalDate, onExported: (String, String) -> String) {
        viewModelScope.launch {
            runCatching {
                val exported = repository.exportAuditCsv(date)
                onExported(exported.csv, exported.fileName)
                exported.fileName
            }.onSuccess { fileName ->
                message.value = "Saved $fileName."
            }.onFailure {
                message.value = "Could not save audit export."
            }
        }
    }

    fun updateDayBoundaryTime(value: String?) {
        val parsedTime = value?.let(::parseTimeOrNull)
        if (value != null && parsedTime == null) {
            message.value = "Boundary time must use HH:mm, such as 03:00."
            return
        }

        viewModelScope.launch {
            repository.setDayBoundaryTime(parsedTime)
            setSelectedDate(repository.currentFoodDate())
            message.value = if (parsedTime == null) {
                "Using calendar days"
            } else {
                "Using ${parsedTime} food day boundary"
            }
        }
    }

    fun saveDailyWeight(
        date: LocalDate,
        stone: String,
        pounds: String,
        time: String,
        onSaved: () -> Unit,
        onError: (String) -> Unit = {},
    ) {
        val parsedStone = stone.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull() ?: 0.0
        val parsedPounds = pounds.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull() ?: 0.0
        val parsedTime = time.trim().takeIf { it.isNotBlank() }?.let(::parseTimeOrNull)

        if (stone.isNotBlank() && stone.trim().toDoubleOrNull() == null) {
            val error = "Stone must be a number."
            message.value = error
            onError(error)
            return
        }

        if (pounds.isNotBlank() && pounds.trim().toDoubleOrNull() == null) {
            val error = "Pounds must be a number."
            message.value = error
            onError(error)
            return
        }

        if (parsedStone <= 0.0 && parsedPounds <= 0.0) {
            val error = "Add a weight in stone and pounds."
            message.value = error
            onError(error)
            return
        }

        if (parsedPounds >= 14.0) {
            val error = "Pounds should be less than 14."
            message.value = error
            onError(error)
            return
        }

        if (time.isNotBlank() && parsedTime == null) {
            val error = "Time must use HH:mm, such as 07:30."
            message.value = error
            onError(error)
            return
        }

        viewModelScope.launch {
            val result = repository.upsertDailyWeight(
                logDate = date,
                weightKg = stonePoundsToKg(parsedStone, parsedPounds),
                measuredTime = parsedTime,
            )
            val resultMessage = when (result) {
                FoodLogRepository.DailyWeightResult.Saved -> {
                    onSaved()
                    "Saved weight for $date"
                }
                FoodLogRepository.DailyWeightResult.InvalidInput -> "Add a valid weight."
            }
            message.value = resultMessage
            if (result == FoodLogRepository.DailyWeightResult.InvalidInput) {
                onError(resultMessage)
            }
        }
    }

    fun updateFoodItem(
        id: Long,
        name: String,
        amount: String,
        unit: String,
        calories: String,
        time: String,
        notes: String,
        updateShortcut: Boolean,
        updateShortcutLookupKey: String?,
        onUpdated: () -> Unit,
        onError: (String) -> Unit,
        onNeedsDefaultResolution: (LoggedFoodEditResolution) -> Unit,
    ) {
        val parsedAmount = amount.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val parsedCalories = calories.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val parsedTime = parseTimeOrNull(time)

        if (name.isBlank()) {
            onError("Add an item name to update the logged item.")
            return
        }

        if (amount.isNotBlank() && parsedAmount == null) {
            onError("Amount must be a number.")
            return
        }

        if (calories.isNotBlank() && (parsedCalories == null || parsedCalories <= 0.0)) {
            onError("Calories must be a positive number, or leave them blank to use defaults.")
            return
        }

        if (parsedTime == null) {
            onError("Time must use HH:mm, such as 08:30.")
            return
        }

        viewModelScope.launch {
            if (parsedCalories == null) {
                when (val preview = repository.previewFoodItemDefaultEdit(id = id, name = name)) {
                    is FoodLogRepository.FoodItemDefaultEditPreviewResult.Ready -> {
                        if (preview.parts.any { it.default == null }) {
                            onNeedsDefaultResolution(preview.toLoggedFoodEditResolution())
                            message.value = "Complete the remaining items before saving."
                            return@launch
                        }
                    }
                    FoodLogRepository.FoodItemDefaultEditPreviewResult.InvalidInput -> {
                        val resultMessage = "Add calories or use known shortcuts to update this item."
                        message.value = resultMessage
                        onError(resultMessage)
                        return@launch
                    }
                    FoodLogRepository.FoodItemDefaultEditPreviewResult.NotFound -> {
                        val resultMessage = "That logged item no longer exists."
                        message.value = resultMessage
                        onError(resultMessage)
                        return@launch
                    }
                }
            }

            if (updateShortcut && parsedCalories == null) {
                val error = "Add calories before updating the shortcut."
                message.value = error
                onError(error)
                return@launch
            }
            val result = repository.updateFoodItem(
                id = id,
                name = name,
                amount = parsedAmount,
                unit = unit,
                calories = parsedCalories,
                consumedTime = parsedTime,
                notes = notes,
                updateShortcutLookupKey = updateShortcutLookupKey.takeIf { updateShortcut },
            )
            val resultMessage = when (result) {
                FoodLogRepository.FoodItemUpdateResult.Updated -> {
                    onUpdated()
                    "Updated logged item"
                }
                FoodLogRepository.FoodItemUpdateResult.UpdatedFromDefaults -> {
                    onUpdated()
                    "Updated logged item from defaults"
                }
                FoodLogRepository.FoodItemUpdateResult.InvalidInput -> "Add an item name to update the logged item."
                is FoodLogRepository.FoodItemUpdateResult.UnresolvedDefaults -> {
                    val missing = result.missingLookupKeys.joinToString(", ")
                    if (missing.isBlank()) {
                        "Add calories or save shortcuts for the missing items."
                    } else {
                        "Add calories or save shortcuts for: $missing."
                    }
                }
                FoodLogRepository.FoodItemUpdateResult.NotFound -> "That logged item no longer exists."
            }
            message.value = resultMessage
            if (
                result != FoodLogRepository.FoodItemUpdateResult.Updated &&
                result != FoodLogRepository.FoodItemUpdateResult.UpdatedFromDefaults
            ) {
                onError(resultMessage)
            }
        }
    }

    fun saveResolvedFoodItemEdit(
        id: Long,
        rawText: String,
        time: String,
        parts: List<LoggedFoodEditResolvedPartInput>,
        onUpdated: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val parsedTime = parseTimeOrNull(time)
        if (parsedTime == null) {
            onError("Time must use HH:mm, such as 08:30.")
            return
        }

        if (parts.isEmpty()) {
            onError("Add at least one item to update the logged item.")
            return
        }

        val replacementParts = replacementPartsOrNull(parts, onError) ?: return

        viewModelScope.launch {
            val result = repository.replaceFoodItemWithResolvedEditParts(
                id = id,
                rawText = rawText,
                consumedTime = parsedTime,
                parts = replacementParts,
            )
            val resultMessage = when (result) {
                FoodLogRepository.FoodItemUpdateResult.Updated,
                FoodLogRepository.FoodItemUpdateResult.UpdatedFromDefaults -> {
                    onUpdated()
                    "Updated logged item"
                }
                FoodLogRepository.FoodItemUpdateResult.InvalidInput -> "Add item names and calories to update the logged item."
                is FoodLogRepository.FoodItemUpdateResult.UnresolvedDefaults -> "Add calories for every item before saving."
                FoodLogRepository.FoodItemUpdateResult.NotFound -> "That logged item no longer exists."
            }
            message.value = resultMessage
            if (
                result != FoodLogRepository.FoodItemUpdateResult.Updated &&
                result != FoodLogRepository.FoodItemUpdateResult.UpdatedFromDefaults
            ) {
                onError(resultMessage)
            }
        }
    }

    fun removeFoodItem(id: Long) {
        viewModelScope.launch {
            message.value = when (repository.removeFoodItem(id)) {
                FoodLogRepository.FoodItemRemoveResult.Removed -> "Removed logged item"
                FoodLogRepository.FoodItemRemoveResult.NotFound -> "That logged item no longer exists."
            }
        }
    }

    fun removePendingEntry(
        id: Long,
        onRemoved: () -> Unit,
    ) {
        viewModelScope.launch {
            message.value = when (repository.removePendingEntry(id)) {
                FoodLogRepository.PendingEntryRemoveResult.Removed -> {
                    onRemoved()
                    "Removed pending entry"
                }
                FoodLogRepository.PendingEntryRemoveResult.NotFound -> "That pending entry no longer exists."
                FoodLogRepository.PendingEntryRemoveResult.NotPending -> "That entry has already been handled."
            }
        }
    }

    fun previewPendingEntryResolution(
        rawEntryId: Long,
        onReady: (LoggedFoodEditResolution) -> Unit,
        onSinglePart: (PendingEntryDraft) -> Unit,
    ) {
        viewModelScope.launch {
            when (val preview = repository.previewPendingEntryResolution(rawEntryId)) {
                is FoodLogRepository.PendingEntryResolutionPreviewResult.Ready -> {
                    if (preview.parts.any { it.default != null }) {
                        onReady(preview.toLoggedFoodEditResolution())
                    }
                }
                is FoodLogRepository.PendingEntryResolutionPreviewResult.SinglePart ->
                    preview.part?.toPendingEntryDraft(preview.consumedTime)?.let(onSinglePart)
                FoodLogRepository.PendingEntryResolutionPreviewResult.NotFound ->
                    message.value = "That pending entry no longer exists."
                FoodLogRepository.PendingEntryResolutionPreviewResult.NotPending ->
                    message.value = "That entry has already been handled."
            }
        }
    }

    fun saveResolvedPendingEntry(
        rawEntryId: Long,
        rawText: String,
        parts: List<LoggedFoodEditResolvedPartInput>,
        onResolved: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (parts.isEmpty()) {
            onError("Add at least one item to resolve the pending entry.")
            return
        }

        val replacementParts = replacementPartsOrNull(parts, onError) ?: return
        viewModelScope.launch {
            val result = repository.resolvePendingEntryParts(
                rawEntryId = rawEntryId,
                rawText = rawText,
                parts = replacementParts,
            )
            val resultMessage = when (result) {
                FoodLogRepository.PendingEntryUpdateResult.Updated -> "Saved pending entry"
                is FoodLogRepository.PendingEntryUpdateResult.Parsed -> {
                    onResolved()
                    "Logged edited entry"
                }
                FoodLogRepository.PendingEntryUpdateResult.InvalidInput -> "Add item names and calories to resolve the pending entry."
                FoodLogRepository.PendingEntryUpdateResult.NotFound -> "That pending entry no longer exists."
                FoodLogRepository.PendingEntryUpdateResult.NotPending -> "That entry has already been handled."
            }
            message.value = resultMessage
            if (result !is FoodLogRepository.PendingEntryUpdateResult.Parsed) {
                onError(resultMessage)
            }
        }
    }

    fun addFoodItemManually(
        date: LocalDate,
        name: String,
        amount: String,
        unit: String,
        calories: String,
        time: String,
        notes: String,
        saveAsDefault: Boolean,
        onAdded: () -> Unit,
    ) {
        val parsedAmount = amount.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val parsedCalories = calories.trim().toDoubleOrNull()
        val parsedTime = time.trim().takeIf { it.isNotBlank() }?.let(::parseTimeOrNull)

        if (name.isBlank() || parsedCalories == null || parsedCalories <= 0.0) {
            message.value = "Add an item name and calories to log the item."
            return
        }

        if (amount.isNotBlank() && parsedAmount == null) {
            message.value = "Amount must be a number."
            return
        }

        if (time.isNotBlank() && parsedTime == null) {
            message.value = "Time must use HH:mm, such as 08:30."
            return
        }

        viewModelScope.launch {
            message.value = when (
                val result = repository.addFoodItemManually(
                    logDate = date,
                    name = name,
                    amount = parsedAmount,
                    unit = unit,
                    calories = parsedCalories,
                    consumedTime = parsedTime,
                    notes = notes,
                    saveAsDefault = saveAsDefault,
                )
            ) {
                is FoodLogRepository.ManualAddResult.Added -> {
                    onAdded()
                    if (result.savedDefaultLookupKey == null) {
                        "Logged item for ${result.logDate}"
                    } else {
                        "Logged item and saved shortcut '${name.trim()}'"
                    }
                }
                FoodLogRepository.ManualAddResult.InvalidInput -> "Add an item name and calories to log the item."
            }
        }
    }

    fun resolvePendingEntry(
        rawEntryId: Long,
        name: String,
        amount: String,
        unit: String,
        calories: String,
        time: String,
        notes: String,
        saveAsDefault: Boolean,
        onResolved: () -> Unit,
    ) {
        val parsedAmount = amount.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val parsedCalories = calories.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val parsedTime = time.trim().takeIf { it.isNotBlank() }?.let(::parseTimeOrNull)

        if (name.isBlank()) {
            message.value = "Add an item name to save the pending entry."
            return
        }

        if (amount.isNotBlank() && parsedAmount == null) {
            message.value = "Amount must be a number."
            return
        }

        if (calories.isNotBlank() && (parsedCalories == null || parsedCalories <= 0.0)) {
            message.value = "Calories must be a positive number, or leave them blank to keep pending."
            return
        }

        if (time.isNotBlank() && parsedTime == null) {
            message.value = "Time must use HH:mm, such as 08:30."
            return
        }

        if (parsedCalories == null) {
            viewModelScope.launch {
                message.value = when (
                    repository.updatePendingEntry(
                        rawEntryId = rawEntryId,
                        rawText = name,
                        amount = parsedAmount,
                        unit = unit,
                        calories = null,
                        notes = notes,
                        consumedTime = parsedTime,
                    )
                ) {
                    FoodLogRepository.PendingEntryUpdateResult.Updated -> {
                        onResolved()
                        "Saved pending entry"
                    }
                    is FoodLogRepository.PendingEntryUpdateResult.Parsed -> {
                        onResolved()
                        "Logged edited entry"
                    }
                    FoodLogRepository.PendingEntryUpdateResult.InvalidInput -> "Add an item name to save the pending entry."
                    FoodLogRepository.PendingEntryUpdateResult.NotFound -> "That pending entry no longer exists."
                    FoodLogRepository.PendingEntryUpdateResult.NotPending -> "That entry has already been handled."
                }
            }
            return
        }

        viewModelScope.launch {
            val result = repository.resolvePendingEntryManually(
                rawEntryId = rawEntryId,
                name = name,
                amount = parsedAmount,
                unit = unit,
                calories = parsedCalories,
                notes = notes,
                consumedTime = parsedTime,
                saveAsDefault = saveAsDefault,
            )
            message.value = when (result) {
                is FoodLogRepository.ManualResolveResult.Resolved -> {
                    onResolved()
                    if (result.savedDefaultLookupKey == null) {
                        "Resolved pending entry for ${result.logDate}"
                    } else {
                        "Resolved and saved shortcut '${name.trim()}'"
                    }
                }
                FoodLogRepository.ManualResolveResult.InvalidInput -> "Add an item name and calories to resolve the pending entry."
                FoodLogRepository.ManualResolveResult.NotFound -> "That pending entry no longer exists."
                FoodLogRepository.ManualResolveResult.NotPending -> "That entry has already been handled."
            }
        }
    }

    fun forgetShortcut(lookupKey: String, onForgotten: () -> Unit = {}) {
        viewModelScope.launch {
            message.value = when (repository.deactivateDefault(lookupKey)) {
                FoodLogRepository.DefaultUpdateResult.Updated -> {
                    onForgotten()
                    "Forgot shortcut"
                }
                FoodLogRepository.DefaultUpdateResult.InvalidInput -> "That shortcut could not be forgotten."
                FoodLogRepository.DefaultUpdateResult.NotFound -> "That shortcut no longer exists."
            }
        }
    }

    fun updateShortcut(
        lookupKey: String,
        name: String,
        calories: String,
        unit: String,
        notes: String,
        defaultAmount: String,
        portionMode: ShortcutPortionMode,
        itemUnit: String,
        itemSizeAmount: String,
        itemSizeUnit: String,
        kcalPer100g: String,
        kcalPer100ml: String,
        onUpdated: () -> Unit,
        onError: (String) -> Unit = {},
    ) {
        val parsedCalories = calories.trim().toDoubleOrNull()
        val parsedDefaultAmount = defaultAmount.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val parsedItemSizeAmount = itemSizeAmount.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val parsedKcalPer100g = kcalPer100g.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val parsedKcalPer100ml = kcalPer100ml.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
        if (name.isBlank() || parsedCalories == null || parsedCalories <= 0.0) {
            val error = "Add a name and calories to update the shortcut."
            message.value = error
            onError(error)
            return
        }
        if (defaultAmount.isNotBlank() && parsedDefaultAmount == null) {
            val error = "Usual amount must be a number."
            message.value = error
            onError(error)
            return
        }
        if (itemSizeAmount.isNotBlank() && parsedItemSizeAmount == null) {
            val error = "Item size must be a number."
            message.value = error
            onError(error)
            return
        }
        if (kcalPer100g.isNotBlank() && parsedKcalPer100g == null) {
            val error = "kcal/100g must be a number."
            message.value = error
            onError(error)
            return
        }
        if (kcalPer100ml.isNotBlank() && parsedKcalPer100ml == null) {
            val error = "kcal/100ml must be a number."
            message.value = error
            onError(error)
            return
        }

        viewModelScope.launch {
            val result = repository.updateDefault(
                lookupKey = lookupKey,
                name = name,
                calories = parsedCalories,
                unit = unit,
                notes = notes,
                defaultAmount = parsedDefaultAmount?.takeIf { it > 0.0 },
                portionMode = portionMode,
                itemUnit = itemUnit,
                itemSizeAmount = parsedItemSizeAmount?.takeIf { it > 0.0 },
                itemSizeUnit = itemSizeUnit,
                kcalPer100g = parsedKcalPer100g?.takeIf { it > 0.0 },
                kcalPer100ml = parsedKcalPer100ml?.takeIf { it > 0.0 },
            )
            val resultMessage = when (result) {
                FoodLogRepository.DefaultUpdateResult.Updated -> {
                    onUpdated()
                    "Updated shortcut '$lookupKey'"
                }
                FoodLogRepository.DefaultUpdateResult.InvalidInput -> "Add a name and calories to update the shortcut."
                FoodLogRepository.DefaultUpdateResult.NotFound -> "That shortcut no longer exists."
            }
            message.value = resultMessage
            if (result != FoodLogRepository.DefaultUpdateResult.Updated) {
                onError(resultMessage)
            }
        }
    }

    fun addShortcut(
        name: String,
        calories: String,
        unit: String,
        notes: String,
        onAdded: () -> Unit,
        onError: (String) -> Unit = {},
    ) {
        val parsedCalories = calories.trim().toDoubleOrNull()
        if (name.isBlank() || parsedCalories == null || parsedCalories <= 0.0) {
            val error = "Add a name and calories to create a shortcut."
            message.value = error
            onError(error)
            return
        }

        viewModelScope.launch {
            val result = repository.addDefault(name, name, parsedCalories, unit, notes)
            val resultMessage = when (result) {
                FoodLogRepository.DefaultUpdateResult.Updated -> {
                    onAdded()
                    "Saved shortcut '${name.trim().lowercase()}'"
                }
                FoodLogRepository.DefaultUpdateResult.InvalidInput -> "Add a name and calories to create a shortcut."
                FoodLogRepository.DefaultUpdateResult.NotFound -> "That shortcut no longer exists."
            }
            message.value = resultMessage
            if (result != FoodLogRepository.DefaultUpdateResult.Updated) {
                onError(resultMessage)
            }
        }
    }

    private companion object {
        const val DAY_STATE_CACHE_RADIUS_DAYS = 2L
    }
}

private fun parseTimeOrNull(value: String): LocalTime? {
    return TimeTextParser.parseOrNull(value)
}

private fun stonePoundsToKg(
    stone: Double,
    pounds: Double,
): Double =
    ((stone * 14.0) + pounds) * 0.45359237

private fun replacementPartsOrNull(
    parts: List<LoggedFoodEditResolvedPartInput>,
    onError: (String) -> Unit,
): List<FoodLogRepository.FoodItemEditReplacementPart>? {
    val replacementParts = mutableListOf<FoodLogRepository.FoodItemEditReplacementPart>()
    parts.forEach { part ->
        val parsedAmount = part.amount.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val parsedCalories = part.calories.trim().toDoubleOrNull()

        if (part.name.isBlank()) {
            onError("Add an item name for ${part.inputText}.")
            return null
        }
        if (part.amount.isNotBlank() && parsedAmount == null) {
            onError("Amount must be a number for ${part.inputText}.")
            return null
        }
        if (parsedCalories == null || parsedCalories <= 0.0) {
            onError("Add calories for ${part.inputText}.")
            return null
        }

        replacementParts += FoodLogRepository.FoodItemEditReplacementPart(
            name = part.name,
            amount = parsedAmount,
            unit = part.unit,
            calories = parsedCalories,
            source = if (part.resolvedByDefault) FoodItemSource.USER_DEFAULT else FoodItemSource.MANUAL_OVERRIDE,
            confidence = ConfidenceLevel.HIGH,
            notes = part.notes,
            saveDefaultLookupKey = part.lookupKey.takeIf { part.saveAsDefault && !part.resolvedByDefault },
        )
    }
    return replacementParts
}

private fun EntryIntent.placeholderMessage(): String =
    when (this) {
        EntryIntent.QUERY -> "Saved as a chat question. AI summaries will come later."
        EntryIntent.EXPORT_COMMAND -> "Saved as an export request. Export workflow will come later."
        EntryIntent.CORRECTION -> "Saved as a correction for later review."
        EntryIntent.NOTE -> "Saved as a note for later."
        EntryIntent.UNKNOWN -> "Saved for later review."
        EntryIntent.FOOD_LOG -> "Saved."
    }

private fun FoodLogRepository.FoodItemDefaultEditPreviewResult.Ready.toLoggedFoodEditResolution(): LoggedFoodEditResolution =
    LoggedFoodEditResolution(
        rawText = rawText,
        parts = parts.map { part ->
            LoggedFoodEditResolutionPart(
                inputText = part.inputText,
                lookupKey = part.lookupKey,
                resolvedByDefault = part.default != null,
                name = part.resolvedName,
                amount = part.resolvedAmount,
                unit = part.resolvedUnit.orEmpty(),
                calories = part.resolvedCalories,
                notes = part.resolvedNotes,
            )
        },
    )

private fun FoodLogRepository.SubmissionPreviewResult.Ready.toLoggingWizardSession(defaultTime: LocalTime): LoggingWizardSession =
    LoggingWizardSession(
        source = LoggingWizardSource.FreeText,
        originalRawText = rawText,
        logDate = logDate,
        consumedTime = consumedTime,
        timeText = (consumedTime ?: defaultTime).toString(),
        timeWasSpecified = consumedTime != null,
        timeConfirmed = consumedTime != null,
        parts = parts.map { it.toLoggingWizardPartDraft() },
    ).withFirstIncompletePart()

private fun FoodLogRepository.SubmissionPreviewResult.NeedsResolution.toLoggingWizardSession(defaultTime: LocalTime): LoggingWizardSession =
    LoggingWizardSession(
        source = LoggingWizardSource.FreeText,
        originalRawText = rawText,
        logDate = logDate,
        consumedTime = consumedTime,
        timeText = (consumedTime ?: defaultTime).toString(),
        timeWasSpecified = consumedTime != null,
        timeConfirmed = consumedTime != null,
        parts = parts.map { it.toLoggingWizardPartDraft() },
    ).withFirstIncompletePart()

private fun FoodLogRepository.PendingEntryResolutionPreviewResult.Ready.toLoggingWizardSession(
    rawEntryId: Long,
    defaultTime: LocalTime,
): LoggingWizardSession =
    LoggingWizardSession(
        source = LoggingWizardSource.Pending,
        sourceRawEntryId = rawEntryId,
        originalRawText = rawText,
        logDate = logDate,
        consumedTime = consumedTime,
        timeText = (consumedTime ?: defaultTime).toString(),
        timeWasSpecified = consumedTime != null,
        timeConfirmed = consumedTime != null,
        parts = parts.map { it.toLoggingWizardPartDraft() },
    ).withFirstIncompletePart()

private fun FoodLogRepository.FoodItemDefaultEditPreviewPart.toLoggingWizardPartDraft(): LoggingWizardPartDraft {
    val default = default
    return LoggingWizardPartDraft(
        inputText = inputText,
        lookupKey = lookupKey,
        resolvedByDefault = default != null,
        name = resolvedName,
        amount = resolvedAmount
            ?.takeIf { resolvedUnit != null || it != 1.0 }
            ?.formatDraftAmount()
            .orEmpty(),
        unit = resolvedUnit.orEmpty(),
        calories = resolvedCalories?.formatDraftAmount().orEmpty(),
        notes = resolvedNotes,
    )
}

private fun LabelNutritionFacts.toLabelLoggingWizardSession(
    logDate: LocalDate,
    consumedTime: LocalTime?,
    defaultTime: LocalTime,
    inputMode: LabelInputMode,
): LoggingWizardSession {
    val itemUnit = LabelPortionResolver.itemUnit(this)
    val amountText = defaultLabelAmountText(inputMode, this)
    val resolved = LabelPortionResolver.resolve(this, inputMode, amountText)
    val inferredItemSize = inferredFullItemSize(this, inputMode, resolved)
    return LoggingWizardSession(
        source = LoggingWizardSource.Label,
        originalRawText = rawText,
        logDate = logDate,
        consumedTime = consumedTime,
        timeText = (consumedTime ?: defaultTime).toString(),
        timeWasSpecified = consumedTime != null,
        timeConfirmed = consumedTime != null,
        labelFacts = this,
        labelInputMode = inputMode,
        parts = listOf(
            LoggingWizardPartDraft(
                inputText = "Label scan",
                lookupKey = null,
                resolvedByDefault = false,
                name = "",
                amount = amountText,
                unit = resolved.unit ?: itemUnit,
                calories = resolved.calories?.formatDraftAmount().orEmpty(),
                shortcutItemSizeAmount = inferredItemSize?.amount?.formatDraftAmount().orEmpty(),
                shortcutItemSizeUnit = inferredItemSize?.unit.orEmpty(),
            ),
        ),
    ).withFirstIncompletePart()
}

private fun defaultLabelAmountText(
    inputMode: LabelInputMode,
    facts: LabelNutritionFacts,
): String =
    when (inputMode) {
        LabelInputMode.ITEMS -> "1"
        LabelInputMode.MEASURE -> {
            val range = defaultMeasureRange(facts)
            when {
                range != null -> "${range.amount.formatDraftAmount()}${range.unit}"
                facts.kcalPer100ml != null && facts.kcalPer100g == null -> "1ml"
                else -> "1g"
            }
        }
    }

private data class DefaultMeasureRange(
    val amount: Double,
    val unit: String,
)

private fun defaultMeasureRange(facts: LabelNutritionFacts): DefaultMeasureRange? {
    val servingAmount = facts.servingAmount?.takeIf { it > 0.0 }
    if (servingAmount != null) {
        facts.servingSizeGrams?.takeIf { it > 1.0 }?.let {
            return DefaultMeasureRange(amount = it / servingAmount, unit = "g")
        }
    }
    facts.packageSizeGrams?.takeIf { it > 1.0 }?.let {
        return DefaultMeasureRange(amount = it, unit = "g")
    }
    facts.packageSizeMilliliters?.takeIf { it > 1.0 }?.let {
        return DefaultMeasureRange(amount = it, unit = "ml")
    }
    facts.servingSizeGrams?.takeIf { it > 1.0 }?.let {
        return DefaultMeasureRange(amount = it, unit = "g")
    }
    return null
}

private data class ShortcutItemSize(
    val amount: Double,
    val unit: String,
)

private fun LabelInputMode.toShortcutPortionMode(): ShortcutPortionMode =
    when (this) {
        LabelInputMode.ITEMS -> ShortcutPortionMode.ITEM
        LabelInputMode.MEASURE -> ShortcutPortionMode.MEASURE
    }

private fun shortcutModeForLabelSave(
    inputMode: LabelInputMode,
    itemSize: ShortcutItemSize?,
    facts: LabelNutritionFacts,
): ShortcutPortionMode =
    when {
        inputMode == LabelInputMode.MEASURE -> ShortcutPortionMode.MEASURE
        itemSize != null && (facts.kcalPer100g != null || facts.kcalPer100ml != null) -> ShortcutPortionMode.ITEM
        else -> ShortcutPortionMode.PLAIN
    }

private fun shortcutCaloriesPerUnit(
    mode: ShortcutPortionMode,
    resolvedPortion: LabelPortionResolution,
    parsedCalories: Double,
    itemSize: ShortcutItemSize?,
    facts: LabelNutritionFacts,
): Double =
    when (mode) {
        ShortcutPortionMode.ITEM -> {
            val size = itemSize
            when {
                size?.unit == "g" && facts.kcalPer100g != null -> facts.kcalPer100g * size.amount / 100.0
                size?.unit == "ml" && facts.kcalPer100ml != null -> facts.kcalPer100ml * size.amount / 100.0
                else -> resolvedPortion.amount?.takeIf { it > 0.0 }?.let { parsedCalories / it } ?: parsedCalories
            }
        }
        ShortcutPortionMode.MEASURE,
        ShortcutPortionMode.PLAIN ->
            if (mode == ShortcutPortionMode.PLAIN) {
                parsedCalories
            } else {
            resolvedPortion.amount?.takeIf { it > 0.0 }?.let { parsedCalories / it } ?: parsedCalories
            }
    }

private fun shortcutUnit(
    mode: ShortcutPortionMode,
    resolvedUnit: String?,
    itemSizeUnit: String?,
): String =
    when (mode) {
        ShortcutPortionMode.ITEM -> resolvedUnit ?: "item"
        ShortcutPortionMode.MEASURE -> itemSizeUnit ?: resolvedUnit ?: "g"
        ShortcutPortionMode.PLAIN -> resolvedUnit ?: "serving"
    }

private fun shortcutItemSizeFor(
    facts: LabelNutritionFacts,
    inputMode: LabelInputMode,
    resolvedPortion: LabelPortionResolution,
    part: LoggingWizardPartDraft,
): ShortcutItemSize? =
    userProvidedItemSize(part)
        ?: inferredFullItemSize(facts, inputMode, resolvedPortion)

private fun userProvidedItemSize(part: LoggingWizardPartDraft): ShortcutItemSize? {
    val amount = part.shortcutItemSizeAmount.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
    val unit = part.shortcutItemSizeUnit.trim().lowercase().takeIf { it == "g" || it == "ml" } ?: return null
    return ShortcutItemSize(amount = amount, unit = unit)
}

private fun inferredFullItemSize(
    facts: LabelNutritionFacts,
    inputMode: LabelInputMode,
    resolvedPortion: LabelPortionResolution,
): ShortcutItemSize? {
    if (inputMode != LabelInputMode.ITEMS) return null
    val amount = resolvedPortion.amount?.takeIf { it > 0.0 } ?: return null
    resolvedPortion.grams?.takeIf { it > 0.0 }?.let {
        return ShortcutItemSize(amount = it / amount, unit = "g")
    }
    resolvedPortion.milliliters?.takeIf { it > 0.0 }?.let {
        return ShortcutItemSize(amount = it / amount, unit = "ml")
    }
    val packageItems = facts.packageItemCount?.takeIf { it > 0.0 }
    if (packageItems != null) {
        facts.packageSizeGrams?.takeIf { it > 0.0 }?.let {
            return ShortcutItemSize(amount = it / packageItems, unit = "g")
        }
        facts.packageSizeMilliliters?.takeIf { it > 0.0 }?.let {
            return ShortcutItemSize(amount = it / packageItems, unit = "ml")
        }
    }
    return null
}

private fun LoggingWizardSession.withFirstIncompletePart(): LoggingWizardSession {
    val firstIncomplete = parts.indexOfFirst { it.needsInput }
    val firstPart = if (firstIncomplete >= 0) firstIncomplete else 0
    return copy(currentPartIndex = firstPart)
}

private fun LoggingWizardSession.withLabelInputMode(mode: LabelInputMode): LoggingWizardSession =
    takeIf { source == LoggingWizardSource.Label || source == LoggingWizardSource.Shortcut }
        ?.copy(
            labelInputMode = mode,
            shortcutPortionMode = if (source == LoggingWizardSource.Shortcut) {
                mode.toShortcutPortionMode()
            } else {
                shortcutPortionMode
            },
        )
        ?: this

private fun FoodLogRepository.PendingEntryResolutionPreviewResult.Ready.toLoggedFoodEditResolution(): LoggedFoodEditResolution =
    LoggedFoodEditResolution(
        rawText = rawText,
        parts = parts.map { part ->
            LoggedFoodEditResolutionPart(
                inputText = part.inputText,
                lookupKey = part.lookupKey,
                resolvedByDefault = part.default != null,
                name = part.resolvedName,
                amount = part.resolvedAmount,
                unit = part.resolvedUnit.orEmpty(),
                calories = part.resolvedCalories,
                notes = part.resolvedNotes,
            )
        },
    )

private fun FoodLogRepository.FoodItemDefaultEditPreviewPart.toPendingEntryDraft(consumedTime: LocalTime?): PendingEntryDraft =
    PendingEntryDraft(
        name = lookupKey?.takeIf { it.isNotBlank() } ?: inputText,
        amount = quantity
            .takeIf { quantityUnit != null || it != 1.0 }
            ?.formatDraftAmount()
            .orEmpty(),
        unit = quantityUnit.orEmpty(),
        time = consumedTime?.toString().orEmpty(),
    )

private fun Double.formatDraftAmount(): String =
    if (rem(1.0) == 0.0) toInt().toString() else toString()

class TodayViewModelFactory(
    private val repository: FoodLogRepository,
    private val labelOcrReader: LabelOcrReader? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TodayViewModel::class.java)) {
            return TodayViewModel(repository, labelOcrReader) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
