package com.betterlucky.foodlog.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.betterlucky.foodlog.data.entities.AppSettingsEntity
import com.betterlucky.foodlog.data.entities.ConfidenceLevel
import com.betterlucky.foodlog.data.entities.FoodItemSource
import com.betterlucky.foodlog.data.repository.FoodLogRepository
import com.betterlucky.foodlog.domain.intent.EntryIntent
import com.betterlucky.foodlog.domain.label.LabelNutritionFacts
import com.betterlucky.foodlog.domain.parser.TimeTextParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

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

data class BarcodeProductReview(
    val barcode: String,
    val name: String,
    val brand: String,
    val packageSizeGrams: Double?,
    val packageItemCount: Double?,
    val kcalPer100g: Double?,
    val kcalPerServing: Double?,
    val servingUnit: String?,
    val servingSizeGrams: Double?,
    val lastLoggedGrams: Double?,
    val proteinPer100g: Double?,
    val fiberPer100g: Double?,
    val carbsPer100g: Double?,
    val fatPer100g: Double?,
    val sugarsPer100g: Double?,
    val saltPer100g: Double?,
    val labelDerived: Boolean = false,
    val note: String?,
    val requiresManualNutrition: Boolean,
) {
    fun withLabelFacts(facts: LabelNutritionFacts): BarcodeProductReview {
        val labelNote = buildString {
            append(if (facts.isPartial) "Label read partially; check values before logging." else "Label values applied; check before logging.")
            if (facts.prepared) append(" Prepared serving detected.")
            if (facts.kcalPerServing != null && kcalPerServing != null && kotlin.math.abs(facts.kcalPerServing - kcalPerServing) > 1.0) {
                append(" Label: ${facts.kcalPerServing.formatForMessage()} kcal")
                facts.servingUnit?.let { append(" per $it") }
                append("; barcode: ${kcalPerServing.formatForMessage()} kcal.")
            }
        }
        return copy(
            packageSizeGrams = facts.packageSizeGrams ?: packageSizeGrams,
            packageItemCount = facts.packageItemCount ?: packageItemCount,
            kcalPer100g = facts.kcalPer100g ?: kcalPer100g,
            kcalPerServing = facts.kcalPerServing ?: kcalPerServing,
            servingUnit = facts.servingUnit ?: servingUnit,
            servingSizeGrams = facts.servingSizeGrams ?: servingSizeGrams,
            proteinPer100g = facts.proteinPer100g ?: proteinPer100g,
            fiberPer100g = facts.fiberPer100g ?: fiberPer100g,
            carbsPer100g = facts.carbsPer100g ?: carbsPer100g,
            fatPer100g = facts.fatPer100g ?: fatPer100g,
            sugarsPer100g = facts.sugarsPer100g ?: sugarsPer100g,
            saltPer100g = facts.saltPer100g ?: saltPer100g,
            labelDerived = true,
            note = labelNote,
            requiresManualNutrition = facts.kcalPer100g == null && facts.kcalPerServing == null,
        )
    }
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
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val inputText = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)

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
                dayBoundaryTime = (values[9] as AppSettingsEntity?)?.dayBoundaryTime,
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

        submitText(text, clearInput = true)
    }

    fun logShortcut(trigger: String) {
        submitText(trigger, clearInput = false)
    }

    fun prepareBarcodeReview(
        barcode: String,
        forceRefresh: Boolean = false,
        onReady: (BarcodeProductReview) -> Unit,
        onManualRequired: (BarcodeProductReview) -> Unit,
    ) {
        viewModelScope.launch {
            when (val result = repository.prepareBarcodeReview(barcode, forceRefresh)) {
                is FoodLogRepository.BarcodeReviewResult.Ready -> {
                    message.value = result.draft.note
                    onReady(result.draft.toReview())
                }
                is FoodLogRepository.BarcodeReviewResult.ManualRequired -> {
                    message.value = result.draft.note
                    onManualRequired(result.draft.toReview())
                }
                FoodLogRepository.BarcodeReviewResult.InvalidBarcode -> {
                    message.value = "Enter a valid barcode."
                }
            }
        }
    }

    fun logBarcodeProduct(
        review: BarcodeProductReview,
        name: String,
        brand: String,
        packageSizeGrams: String,
        packageItemCount: String,
        consumedItemCount: String,
        kcalPer100g: String,
        kcalPerServing: String,
        servingUnit: String,
        consumedServingCount: String,
        grams: String,
        time: String,
        onLogged: () -> Unit,
    ) {
        viewModelScope.launch {
            val parsedPackageGrams = packageSizeGrams.toDoubleOrNull()?.takeIf { it > 0.0 }
            val parsedPackageItemCount = packageItemCount.toDoubleOrNull()?.takeIf { it > 0.0 }
            val parsedConsumedItemCount = consumedItemCount.toDoubleOrNull()?.takeIf { it > 0.0 }
            val parsedKcalPer100g = kcalPer100g.toDoubleOrNull()?.takeIf { it > 0.0 }
            val parsedKcalPerServing = kcalPerServing.toDoubleOrNull()?.takeIf { it > 0.0 }
            val parsedConsumedServingCount = consumedServingCount.toDoubleOrNull()?.takeIf { it > 0.0 }
            val parsedGrams = grams.toDoubleOrNull()?.takeIf { it > 0.0 }
            val parsedTime = time.takeIf { it.isNotBlank() }?.let(TimeTextParser::parseOrNull)
            val result = repository.logBarcodeProduct(
                FoodLogRepository.BarcodeProductLogInput(
                    barcode = review.barcode,
                    logDate = selectedDate.value,
                    consumedTime = parsedTime,
                    name = name,
                    brand = brand,
                    packageSizeGrams = parsedPackageGrams,
                    packageItemCount = parsedPackageItemCount,
                    consumedItemCount = parsedConsumedItemCount,
                    servingSizeGrams = review.servingSizeGrams,
                    kcalPer100g = parsedKcalPer100g,
                    kcalPerServing = parsedKcalPerServing,
                    servingUnit = servingUnit,
                    consumedServingCount = parsedConsumedServingCount,
                    grams = parsedGrams,
                    proteinPer100g = review.proteinPer100g,
                    fiberPer100g = review.fiberPer100g,
                    carbsPer100g = review.carbsPer100g,
                    fatPer100g = review.fatPer100g,
                    sugarsPer100g = review.sugarsPer100g,
                    saltPer100g = review.saltPer100g,
                    labelDerived = review.labelDerived,
                ),
            )
            message.value = when (result) {
                is FoodLogRepository.BarcodeLogResult.Logged -> {
                    onLogged()
                    "Logged barcode product for ${result.logDate}"
                }
                FoodLogRepository.BarcodeLogResult.InvalidInput ->
                    "Add product name, calories, and an amount."
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

private fun FoodLogRepository.BarcodeProductDraft.toReview(): BarcodeProductReview =
    BarcodeProductReview(
        barcode = barcode,
        name = name,
        brand = brand,
        packageSizeGrams = packageSizeGrams,
        packageItemCount = packageItemCount,
        kcalPer100g = kcalPer100g,
        kcalPerServing = kcalPerServing,
        servingUnit = servingUnit,
        servingSizeGrams = servingSizeGrams,
        lastLoggedGrams = lastLoggedGrams,
        proteinPer100g = null,
        fiberPer100g = null,
        carbsPer100g = null,
        fatPer100g = null,
        sugarsPer100g = null,
        saltPer100g = null,
        note = note,
        requiresManualNutrition = requiresManualNutrition,
    )

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
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TodayViewModel::class.java)) {
            return TodayViewModel(repository) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
