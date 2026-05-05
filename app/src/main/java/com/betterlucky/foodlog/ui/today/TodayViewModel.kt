package com.betterlucky.foodlog.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.betterlucky.foodlog.data.entities.AppSettingsEntity
import com.betterlucky.foodlog.data.entities.ConfidenceLevel
import com.betterlucky.foodlog.data.entities.FoodItemSource
import com.betterlucky.foodlog.data.entities.UserDefaultEntity
import com.betterlucky.foodlog.data.ocr.LabelOcrReader
import com.betterlucky.foodlog.data.ocr.LabelOcrResult
import com.betterlucky.foodlog.data.repository.FoodLogRepository
import com.betterlucky.foodlog.domain.intent.EntryIntent
import com.betterlucky.foodlog.domain.label.LabelInputMode
import com.betterlucky.foodlog.domain.label.LabelNutritionFacts
import com.betterlucky.foodlog.domain.label.LabelPortionResolver
import com.betterlucky.foodlog.domain.parser.TimeTextParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class LoggedFoodEditResolution(
    val rawText: String,
    val parts: List<LoggedFoodEditResolutionPart>,
)

data class LoggedFoodEditResolutionPart(
    val inputText: String,
    val trigger: String?,
    val resolvedByDefault: Boolean,
    val name: String,
    val amount: Double?,
    val unit: String,
    val calories: Double?,
    val notes: String,
)

data class LoggedFoodEditResolvedPartInput(
    val inputText: String,
    val trigger: String?,
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
    val saveShortcutDefaultAmount: Boolean = false,
)

data class LoggingWizardPartDraft(
    val inputText: String,
    val trigger: String?,
    val resolvedByDefault: Boolean,
    val name: String,
    val amount: String,
    val unit: String,
    val calories: String,
    val notes: String = "",
    val saveAsShortcut: Boolean = false,
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
    private val inputText = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)

    private val _labelReview = MutableStateFlow<LabelReviewState?>(null)
    val labelReview: StateFlow<LabelReviewState?> = _labelReview.asStateFlow()

    private val _pendingQuantityPicker = MutableStateFlow<UserDefaultEntity?>(null)
    val pendingQuantityPicker: StateFlow<UserDefaultEntity?> = _pendingQuantityPicker.asStateFlow()

    private val _loggingWizard = MutableStateFlow<LoggingWizardSession?>(null)
    val loggingWizard: StateFlow<LoggingWizardSession?> = _loggingWizard.asStateFlow()

    val uiState: StateFlow<TodayUiState> =
        combine(
            selectedDate,
            inputText,
            message,
            selectedDate.flatMapLatest(repository::observeFoodItemsForDate),
            selectedDate.flatMapLatest(repository::observeCaloriesForDate),
            selectedDate.flatMapLatest(repository::observePendingEntriesForDate),
            repository.observeActiveDefaults(),
            selectedDate.flatMapLatest(repository::observeDailyStatusForDate),
            selectedDate.flatMapLatest(repository::observeDailyWeightForDate),
            repository.observeAppSettings(),
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            TodayUiState(
                selectedDate = values[0] as LocalDate,
                inputText = values[1] as String,
                message = values[2] as String?,
                items = values[3] as List<com.betterlucky.foodlog.data.entities.FoodItemEntity>,
                totalCalories = values[4] as Double,
                pendingEntries = values[5] as List<com.betterlucky.foodlog.data.entities.RawEntryEntity>,
                userDefaults = values[6] as List<com.betterlucky.foodlog.data.entities.UserDefaultEntity>,
                dailyStatus = values[7] as com.betterlucky.foodlog.data.entities.DailyStatusEntity?,
                dailyWeight = values[8] as com.betterlucky.foodlog.data.entities.DailyWeightEntity?,
                dayBoundaryTime = (values[9] as? AppSettingsEntity)?.dayBoundaryTime,
                lastLabelInputMode = (values[9] as? AppSettingsEntity)?.lastLabelInputMode
                    ?: AppSettingsEntity.LAST_LABEL_INPUT_MODE_ITEMS,
                isLoading = false,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TodayUiState(selectedDate = selectedDate.value),
        )

    init {
        viewModelScope.launch {
            repository.seedDefaults()
            selectedDate.value = repository.currentFoodDate()
        }
    }

    fun onInputChanged(value: String) {
        inputText.value = value
        message.value = null
    }

    fun submit() {
        val text = inputText.value
        if (text.isBlank()) return

        previewOrSubmitText(text)
    }

    fun logShortcut(trigger: String) {
        viewModelScope.launch {
            val default = repository.getActiveShortcut(trigger)
            when {
                default == null -> previewOrSubmitText(trigger)
                default.defaultAmount != null -> openShortcutWizard(default, default.defaultAmount, saveDefaultAmount = false)
                else -> _pendingQuantityPicker.value = default
            }
        }
    }

    fun logShortcutWithAmount(trigger: String, amount: Double, saveAsDefault: Boolean = true) {
        viewModelScope.launch {
            _pendingQuantityPicker.value = null
            val default = repository.getActiveShortcut(trigger)
            if (default == null) {
                message.value = "Could not log shortcut."
                return@launch
            }
            openShortcutWizard(default, amount, saveDefaultAmount = saveAsDefault)
        }
    }

    fun dismissQuantityPicker() {
        _pendingQuantityPicker.value = null
    }

    private fun openShortcutWizard(
        default: UserDefaultEntity,
        amount: Double,
        saveDefaultAmount: Boolean,
    ) {
        _loggingWizard.value = default.toShortcutLoggingWizardSession(
            amount = amount,
            logDate = selectedDate.value,
            defaultTime = currentWizardTime(),
            saveDefaultAmount = saveDefaultAmount,
        )
        message.value = null
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
            val result = reader.read(uri)
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
            repository.setLastLabelInputMode(mode.storageValue)
            _loggingWizard.update { session ->
                session?.takeIf { it.source == LoggingWizardSource.Label }
                    ?.copy(labelInputMode = mode)
                    ?: session
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
            FoodLogRepository.FoodItemEditReplacementPart(
                name = part.name,
                amount = part.amount.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull(),
                unit = part.unit,
                calories = calories,
                source = if (part.resolvedByDefault) FoodItemSource.USER_DEFAULT else FoodItemSource.MANUAL_OVERRIDE,
                confidence = ConfidenceLevel.HIGH,
                notes = part.notes,
                saveDefaultTrigger = if (part.saveAsShortcut && !part.resolvedByDefault) {
                    part.trigger?.takeIf { it.isNotBlank() } ?: part.name
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
                                val amount = part.amount.trim().toDoubleOrNull()
                                val trigger = part.trigger
                                if (trigger != null && amount != null && amount > 0.0) {
                                    repository.updateShortcutDefaultAmount(trigger, amount)
                                }
                            }
                    }
                    _loggingWizard.value = null
                    _labelReview.value = null
                    inputText.value = ""
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
                    if (part.saveAsShortcut) {
                        val shortcutCalories = resolvedPortion.amount
                            ?.takeIf { it > 0.0 }
                            ?.let { parsedCalories / it }
                            ?: parsedCalories
                        repository.addDefault(
                            trigger = part.name.trim().lowercase(),
                            name = part.name,
                            calories = shortcutCalories,
                            unit = resolvedPortion.unit ?: "item",
                            notes = part.notes.ifBlank { null },
                            defaultAmount = resolvedPortion.amount,
                        )
                    }
                    _loggingWizard.value = null
                    null
                }
                FoodLogRepository.LabelLogResult.InvalidInput -> "Add item name and calories."
            }
        }
    }

    private fun submitText(
        text: String,
        clearInput: Boolean,
    ) {
        viewModelScope.launch {
            val result = repository.submitText(
                input = text,
                targetLogDate = selectedDate.value,
            )
            if (clearInput) {
                if (result !is FoodLogRepository.SubmitResult.DateMismatch) {
                    inputText.value = ""
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

    private fun previewOrSubmitText(text: String) {
        viewModelScope.launch {
            when (val preview = repository.previewSubmission(text, selectedDate.value)) {
                is FoodLogRepository.SubmissionPreviewResult.Ready -> {
                    if (preview.consumedTime == null) {
                        _loggingWizard.value = preview.toLoggingWizardSession(defaultTime = currentWizardTime())
                        message.value = null
                    } else {
                        submitText(text, clearInput = true)
                    }
                }
                is FoodLogRepository.SubmissionPreviewResult.NeedsResolution -> {
                    _loggingWizard.value = preview.toLoggingWizardSession(defaultTime = currentWizardTime())
                    message.value = null
                }
                is FoodLogRepository.SubmissionPreviewResult.NonFood -> submitText(text, clearInput = true)
                is FoodLogRepository.SubmissionPreviewResult.DateMismatch ->
                    message.value = "Switch to ${preview.requestedLogDate} before adding that entry."
                FoodLogRepository.SubmissionPreviewResult.InvalidInput -> Unit
            }
        }
    }

    private fun currentWizardTime(): LocalTime =
        repository.currentLocalTime().truncatedTo(ChronoUnit.MINUTES)

    fun selectDate(date: LocalDate) {
        selectedDate.value = date
    }

    fun previousDay() {
        selectedDate.update { it.minusDays(1) }
    }

    fun nextDay() {
        selectedDate.update { it.plusDays(1) }
    }

    fun exportLegacyCsv(onExported: (String, String) -> Unit) {
        viewModelScope.launch {
            val date = selectedDate.value
            val exported = repository.exportLegacyHealthCsv(date)
            onExported(exported.csv, exported.fileName)
        }
    }

    fun exportAuditCsv(onExported: (String, String) -> Unit) {
        viewModelScope.launch {
            val date = selectedDate.value
            val exported = repository.exportAuditCsv(date)
            onExported(exported.csv, exported.fileName)
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
            selectedDate.value = repository.currentFoodDate()
            message.value = if (parsedTime == null) {
                "Using calendar days"
            } else {
                "Using ${parsedTime} food day boundary"
            }
        }
    }

    fun saveDailyWeight(
        stone: String,
        pounds: String,
        time: String,
        onSaved: () -> Unit,
    ) {
        val parsedStone = stone.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull() ?: 0.0
        val parsedPounds = pounds.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull() ?: 0.0
        val parsedTime = time.trim().takeIf { it.isNotBlank() }?.let(::parseTimeOrNull)

        if (stone.isNotBlank() && stone.trim().toDoubleOrNull() == null) {
            message.value = "Stone must be a number."
            return
        }

        if (pounds.isNotBlank() && pounds.trim().toDoubleOrNull() == null) {
            message.value = "Pounds must be a number."
            return
        }

        if (parsedStone <= 0.0 && parsedPounds <= 0.0) {
            message.value = "Add a weight in stone and pounds."
            return
        }

        if (parsedPounds >= 14.0) {
            message.value = "Pounds should be less than 14."
            return
        }

        if (time.isNotBlank() && parsedTime == null) {
            message.value = "Time must use HH:mm, such as 07:30."
            return
        }

        viewModelScope.launch {
            message.value = when (
                repository.upsertDailyWeight(
                    logDate = selectedDate.value,
                    weightKg = stonePoundsToKg(parsedStone, parsedPounds),
                    measuredTime = parsedTime,
                )
            ) {
                FoodLogRepository.DailyWeightResult.Saved -> {
                    onSaved()
                    "Saved weight for ${selectedDate.value}"
                }
                FoodLogRepository.DailyWeightResult.InvalidInput -> "Add a valid weight."
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

            val result = repository.updateFoodItem(
                id = id,
                name = name,
                amount = parsedAmount,
                unit = unit,
                calories = parsedCalories,
                consumedTime = parsedTime,
                notes = notes,
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
                    val missing = result.missingTriggers.joinToString(", ")
                    if (missing.isBlank()) {
                        "Add calories or use known shortcuts to update this item."
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
                    logDate = selectedDate.value,
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
                    if (result.savedDefaultTrigger == null) {
                        "Logged item for ${result.logDate}"
                    } else {
                        "Logged item and saved shortcut '${result.savedDefaultTrigger}'"
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
                    if (result.savedDefaultTrigger == null) {
                        "Resolved pending entry for ${result.logDate}"
                    } else {
                        "Resolved and saved shortcut '${result.savedDefaultTrigger}'"
                    }
                }
                FoodLogRepository.ManualResolveResult.InvalidInput -> "Add an item name and calories to resolve the pending entry."
                FoodLogRepository.ManualResolveResult.NotFound -> "That pending entry no longer exists."
                FoodLogRepository.ManualResolveResult.NotPending -> "That entry has already been handled."
            }
        }
    }

    fun forgetShortcut(trigger: String) {
        viewModelScope.launch {
            repository.deactivateDefault(trigger)
            message.value = "Forgot shortcut '$trigger'"
        }
    }

    fun updateShortcut(
        trigger: String,
        name: String,
        calories: String,
        unit: String,
        notes: String,
        onUpdated: () -> Unit,
    ) {
        val parsedCalories = calories.trim().toDoubleOrNull()
        if (name.isBlank() || unit.isBlank() || parsedCalories == null || parsedCalories <= 0.0) {
            message.value = "Add a name, unit, and calories to update the shortcut."
            return
        }

        viewModelScope.launch {
            message.value = when (repository.updateDefault(trigger, name, parsedCalories, unit, notes)) {
                FoodLogRepository.DefaultUpdateResult.Updated -> {
                    onUpdated()
                    "Updated shortcut '$trigger'"
                }
                FoodLogRepository.DefaultUpdateResult.InvalidInput -> "Add a name, unit, and calories to update the shortcut."
                FoodLogRepository.DefaultUpdateResult.NotFound -> "That shortcut no longer exists."
            }
        }
    }

    fun addShortcut(
        trigger: String,
        name: String,
        calories: String,
        unit: String,
        notes: String,
        onAdded: () -> Unit,
    ) {
        val parsedCalories = calories.trim().toDoubleOrNull()
        if (trigger.isBlank() || name.isBlank() || unit.isBlank() || parsedCalories == null || parsedCalories <= 0.0) {
            message.value = "Add a trigger, name, unit, and calories to create a shortcut."
            return
        }

        viewModelScope.launch {
            message.value = when (repository.addDefault(trigger, name, parsedCalories, unit, notes)) {
                FoodLogRepository.DefaultUpdateResult.Updated -> {
                    onAdded()
                    "Saved shortcut '${trigger.trim().lowercase()}'"
                }
                FoodLogRepository.DefaultUpdateResult.InvalidInput -> "Add a trigger, name, unit, and calories to create a shortcut."
                FoodLogRepository.DefaultUpdateResult.NotFound -> "That shortcut no longer exists."
            }
        }
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
            saveDefaultTrigger = part.trigger.takeIf { part.saveAsDefault && !part.resolvedByDefault },
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
            val default = part.default
            LoggedFoodEditResolutionPart(
                inputText = part.inputText,
                trigger = part.trigger,
                resolvedByDefault = default != null,
                name = default?.name ?: part.inputText,
                amount = part.quantity,
                unit = default?.unit ?: part.quantityUnit.orEmpty(),
                calories = default?.calories?.times(part.quantity),
                notes = default?.notes.orEmpty(),
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

private fun UserDefaultEntity.toShortcutLoggingWizardSession(
    amount: Double,
    logDate: LocalDate,
    defaultTime: LocalTime,
    saveDefaultAmount: Boolean,
): LoggingWizardSession =
    LoggingWizardSession(
        source = LoggingWizardSource.Shortcut,
        originalRawText = trigger,
        logDate = logDate,
        consumedTime = null,
        timeText = defaultTime.toString(),
        timeWasSpecified = false,
        timeConfirmed = false,
        saveShortcutDefaultAmount = saveDefaultAmount,
        parts = listOf(
            LoggingWizardPartDraft(
                inputText = trigger,
                trigger = trigger,
                resolvedByDefault = true,
                name = name,
                amount = amount.formatDraftAmount(),
                unit = unit,
                calories = (calories * amount).formatDraftAmount(),
                notes = notes.orEmpty(),
            ),
        ),
    )

private fun FoodLogRepository.FoodItemDefaultEditPreviewPart.toLoggingWizardPartDraft(): LoggingWizardPartDraft {
    val default = default
    return LoggingWizardPartDraft(
        inputText = inputText,
        trigger = trigger,
        resolvedByDefault = default != null,
        name = default?.name ?: trigger?.replaceFirstChar { it.titlecase() } ?: inputText,
        amount = quantity
            .takeIf { quantityUnit != null || it != 1.0 }
            ?.formatDraftAmount()
            .orEmpty(),
        unit = default?.unit ?: quantityUnit.orEmpty(),
        calories = default?.calories?.times(quantity)?.formatDraftAmount().orEmpty(),
        notes = default?.notes.orEmpty(),
    )
}

private fun LabelNutritionFacts.toLabelLoggingWizardSession(
    logDate: LocalDate,
    consumedTime: LocalTime?,
    defaultTime: LocalTime,
    inputMode: LabelInputMode,
): LoggingWizardSession {
    val itemUnit = LabelPortionResolver.itemUnit(this)
    val amount = servingAmount?.takeIf { it > 0.0 } ?: 1.0
    val amountText = amount.formatDraftAmount()
    val resolved = LabelPortionResolver.resolve(this, inputMode, amountText)
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
                trigger = null,
                resolvedByDefault = false,
                name = "",
                amount = amountText,
                unit = resolved.unit ?: itemUnit,
                calories = resolved.calories?.formatDraftAmount().orEmpty(),
            ),
        ),
    ).withFirstIncompletePart()
}

private fun LoggingWizardSession.withFirstIncompletePart(): LoggingWizardSession {
    val firstIncomplete = parts.indexOfFirst { it.needsInput }
    val firstPart = if (firstIncomplete >= 0) firstIncomplete else 0
    return copy(currentPartIndex = firstPart)
}

private fun FoodLogRepository.PendingEntryResolutionPreviewResult.Ready.toLoggedFoodEditResolution(): LoggedFoodEditResolution =
    LoggedFoodEditResolution(
        rawText = rawText,
        parts = parts.map { part ->
            val default = part.default
            LoggedFoodEditResolutionPart(
                inputText = part.inputText,
                trigger = part.trigger,
                resolvedByDefault = default != null,
                name = default?.name ?: part.inputText,
                amount = part.quantity,
                unit = default?.unit ?: part.quantityUnit.orEmpty(),
                calories = default?.calories?.times(part.quantity),
                notes = default?.notes.orEmpty(),
            )
        },
    )

private fun FoodLogRepository.FoodItemDefaultEditPreviewPart.toPendingEntryDraft(consumedTime: LocalTime?): PendingEntryDraft =
    PendingEntryDraft(
        name = trigger?.takeIf { it.isNotBlank() } ?: inputText,
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
