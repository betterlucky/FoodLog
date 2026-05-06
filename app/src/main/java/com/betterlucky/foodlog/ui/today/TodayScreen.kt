package com.betterlucky.foodlog.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onShareCsv: (String, String) -> String,
    onTakeLabelPhoto: () -> Unit,
    onChooseLabelImage: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val labelReview by viewModel.labelReview.collectAsState()
    val pendingQuantityPicker by viewModel.pendingQuantityPicker.collectAsState()
    val loggingWizard by viewModel.loggingWizard.collectAsState()
    val pagerScope = rememberCoroutineScope()
    val originPage = remember { Int.MAX_VALUE / 2 }
    val originDate = remember { uiState.selectedDate }
    val pagerState = rememberPagerState(
        initialPage = originPage,
        pageCount = { Int.MAX_VALUE },
    )
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapPositionalThreshold = 0.36f,
    )

    var editingDefault by remember { mutableStateOf<com.betterlucky.foodlog.data.entities.UserDefaultEntity?>(null) }
    var forgettingDefault by remember { mutableStateOf<com.betterlucky.foodlog.data.entities.UserDefaultEntity?>(null) }
    var editingFoodItem by remember { mutableStateOf<com.betterlucky.foodlog.data.entities.FoodItemEntity?>(null) }
    var editingWeightDate by remember { mutableStateOf<LocalDate?>(null) }
    var editingBoundary by remember { mutableStateOf(false) }
    var showingShortcuts by remember { mutableStateOf(false) }
    var addingShortcut by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var choosingLabelImage by remember { mutableStateOf(false) }
    var loggedItemsViewMode by remember { mutableStateOf(LoggedItemsViewMode.Time) }
    var editFoodError by remember { mutableStateOf<String?>(null) }
    var editResolution by remember { mutableStateOf<LoggedFoodEditResolution?>(null) }
    var editResolutionTime by remember { mutableStateOf("") }
    var pendingExpanded by remember { mutableStateOf(false) }
    var loggedExpanded by remember { mutableStateOf(false) }

    fun dateForPage(page: Int): LocalDate =
        originDate.plusDays((page - originPage).toLong())

    fun pageForDate(date: LocalDate): Int {
        val offset = ChronoUnit.DAYS.between(originDate, date)
            .coerceIn(
                Int.MIN_VALUE.toLong() - originPage,
                Int.MAX_VALUE.toLong() - originPage,
            )
        return originPage + offset.toInt()
    }

    fun animateToDate(date: LocalDate) {
        pagerScope.launch {
            pagerState.animateScrollToPage(pageForDate(date))
        }
    }

    fun jumpToDate(date: LocalDate) {
        viewModel.selectDate(date)
        pagerScope.launch {
            pagerState.scrollToPage(pageForDate(date))
        }
    }

    fun exportLegacyAndAdvance(date: LocalDate) {
        viewModel.exportLegacyCsv(
            date = date,
            onExported = onShareCsv,
            onAdvanceToDate = ::jumpToDate,
        )
    }

    DayStatePreloader(viewModel = viewModel, selectedDate = uiState.selectedDate)

    LaunchedEffect(pagerState, originDate) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            viewModel.selectDate(dateForPage(page))
        }
    }

    LaunchedEffect(uiState.selectedDate) {
        val targetPage = pageForDate(uiState.selectedDate)
        if (targetPage != pagerState.currentPage && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(targetPage)
        }
    }

    val daySwipeEnabled =
        labelReview == null &&
            pendingQuantityPicker == null &&
            loggingWizard == null &&
            editingDefault == null &&
            forgettingDefault == null &&
            editingFoodItem == null &&
            editingWeightDate == null &&
            !editingBoundary &&
            !showingShortcuts &&
            !addingShortcut &&
            !pickingDate &&
            !choosingLabelImage

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            flingBehavior = flingBehavior,
            userScrollEnabled = daySwipeEnabled,
            beyondViewportPageCount = 1,
        ) { page ->
            val date = dateForPage(page)
            val isSettledPage = page == pagerState.settledPage
            val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                .coerceIn(-1f, 1f)
            ElasticDayPagerPage(pageOffset = pageOffset) {
                Box(modifier = Modifier.fillMaxSize()) {
                    FoodLogDayPage(
                        date = date,
                        viewModel = viewModel,
                        uiState = uiState,
                        isActivePage = isSettledPage,
                        loggedItemsViewMode = loggedItemsViewMode,
                        onViewModeChanged = { loggedItemsViewMode = it },
                        pendingExpanded = pendingExpanded,
                        onPendingExpandedChanged = { pendingExpanded = it },
                        loggedExpanded = loggedExpanded,
                        onLoggedExpandedChanged = { loggedExpanded = it },
                        onOpenPicker = { pickingDate = true },
                        onPreviousDay = { animateToDate(date.minusDays(1)) },
                        onToday = { viewModel.selectCurrentFoodDate(::jumpToDate) },
                        onNextDay = { animateToDate(date.plusDays(1)) },
                        onEditBoundary = { editingBoundary = true },
                        onEditWeight = { editingWeightDate = date },
                        onEditFoodItem = { item ->
                            editFoodError = null
                            editResolution = null
                            editResolutionTime = item.consumedTime?.toString().orEmpty()
                            editingFoodItem = item
                        },
                        onShowShortcuts = { showingShortcuts = true },
                        onChooseLabelImage = { choosingLabelImage = true },
                        onExportLegacy = ::exportLegacyAndAdvance,
                    )
                    if (!isSettledPage) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(enabled = true, onClick = {}),
                        )
                    }
                }
            }
        }
    }

    TodayScreenDialogs(
        uiState = uiState,
        viewModel = viewModel,
        labelReview = labelReview,
        pendingQuantityPicker = pendingQuantityPicker,
        loggingWizard = loggingWizard,
        editingDefault = editingDefault,
        onEditingDefaultChanged = { editingDefault = it },
        forgettingDefault = forgettingDefault,
        onForgettingDefaultChanged = { forgettingDefault = it },
        editingFoodItem = editingFoodItem,
        onEditingFoodItemChanged = { editingFoodItem = it },
        editingWeightDate = editingWeightDate,
        onEditingWeightDateChanged = { editingWeightDate = it },
        editingBoundary = editingBoundary,
        onEditingBoundaryChanged = { editingBoundary = it },
        showingShortcuts = showingShortcuts,
        onShowingShortcutsChanged = { showingShortcuts = it },
        addingShortcut = addingShortcut,
        onAddingShortcutChanged = { addingShortcut = it },
        pickingDate = pickingDate,
        onPickingDateChanged = { pickingDate = it },
        choosingLabelImage = choosingLabelImage,
        onChoosingLabelImageChanged = { choosingLabelImage = it },
        editFoodError = editFoodError,
        onEditFoodErrorChanged = { editFoodError = it },
        editResolution = editResolution,
        onEditResolutionChanged = { editResolution = it },
        editResolutionTime = editResolutionTime,
        onEditResolutionTimeChanged = { editResolutionTime = it },
        onDateSelected = ::jumpToDate,
        onTakeLabelPhoto = onTakeLabelPhoto,
        onChooseLabelImage = onChooseLabelImage,
    )
}

@Composable
private fun DayStatePreloader(
    viewModel: TodayViewModel,
    selectedDate: LocalDate,
) {
    (-2L..2L).forEach { offset ->
        viewModel.dayState(selectedDate.plusDays(offset)).collectAsState()
    }
}

@Composable
private fun ElasticDayPagerPage(
    pageOffset: Float,
    content: @Composable () -> Unit,
) {
    val pull = pageOffset.absoluteValue.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val direction = if (pageOffset == 0f) 0f else pageOffset / pageOffset.absoluteValue
                cameraDistance = 18f * density
                rotationY = -direction * pull * 5.5f
                scaleX = 1f - (pull * 0.025f)
                scaleY = 1f - (pull * 0.012f)
                translationX = -direction * pull * 10f
                alpha = 1f - (pull * 0.05f)
                transformOrigin = TransformOrigin(
                    pivotFractionX = if (direction < 0f) 1f else 0f,
                    pivotFractionY = 0.5f,
                )
            },
    ) {
        content()
    }
}

@Composable
private fun TodayScreenDialogs(
    uiState: TodayUiState,
    viewModel: TodayViewModel,
    labelReview: LabelReviewState?,
    pendingQuantityPicker: com.betterlucky.foodlog.data.entities.UserDefaultEntity?,
    loggingWizard: LoggingWizardSession?,
    editingDefault: com.betterlucky.foodlog.data.entities.UserDefaultEntity?,
    onEditingDefaultChanged: (com.betterlucky.foodlog.data.entities.UserDefaultEntity?) -> Unit,
    forgettingDefault: com.betterlucky.foodlog.data.entities.UserDefaultEntity?,
    onForgettingDefaultChanged: (com.betterlucky.foodlog.data.entities.UserDefaultEntity?) -> Unit,
    editingFoodItem: com.betterlucky.foodlog.data.entities.FoodItemEntity?,
    onEditingFoodItemChanged: (com.betterlucky.foodlog.data.entities.FoodItemEntity?) -> Unit,
    editingWeightDate: LocalDate?,
    onEditingWeightDateChanged: (LocalDate?) -> Unit,
    editingBoundary: Boolean,
    onEditingBoundaryChanged: (Boolean) -> Unit,
    showingShortcuts: Boolean,
    onShowingShortcutsChanged: (Boolean) -> Unit,
    addingShortcut: Boolean,
    onAddingShortcutChanged: (Boolean) -> Unit,
    pickingDate: Boolean,
    onPickingDateChanged: (Boolean) -> Unit,
    choosingLabelImage: Boolean,
    onChoosingLabelImageChanged: (Boolean) -> Unit,
    editFoodError: String?,
    onEditFoodErrorChanged: (String?) -> Unit,
    editResolution: LoggedFoodEditResolution?,
    onEditResolutionChanged: (LoggedFoodEditResolution?) -> Unit,
    editResolutionTime: String,
    onEditResolutionTimeChanged: (String) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onTakeLabelPhoto: () -> Unit,
    onChooseLabelImage: () -> Unit,
) {
    if (showingShortcuts) {
        ShortcutPickerDialog(
            userDefaults = uiState.userDefaults,
            onDismiss = { onShowingShortcutsChanged(false) },
            onAdd = { onAddingShortcutChanged(true) },
            onLog = { userDefault -> viewModel.logShortcut(userDefault.trigger) },
            onEdit = { userDefault -> onEditingDefaultChanged(userDefault) },
            onForget = { userDefault -> onForgettingDefaultChanged(userDefault) },
        )
    }

    editingDefault?.let { userDefault ->
        EditShortcutDialog(
            userDefault = userDefault,
            onDismiss = { onEditingDefaultChanged(null) },
            onSave = { name, calories, unit, notes ->
                viewModel.updateShortcut(
                    trigger = userDefault.trigger,
                    name = name,
                    calories = calories,
                    unit = unit,
                    notes = notes,
                    onUpdated = { onEditingDefaultChanged(null) },
                )
            },
        )
    }

    if (addingShortcut) {
        AddShortcutDialog(
            onDismiss = { onAddingShortcutChanged(false) },
            onSave = { trigger, name, calories, unit, notes ->
                viewModel.addShortcut(
                    trigger = trigger,
                    name = name,
                    calories = calories,
                    unit = unit,
                    notes = notes,
                    onAdded = { onAddingShortcutChanged(false) },
                )
            },
        )
    }

    forgettingDefault?.let { userDefault ->
        ForgetShortcutDialog(
            userDefault = userDefault,
            onDismiss = { onForgettingDefaultChanged(null) },
            onConfirm = {
                viewModel.forgetShortcut(userDefault.trigger)
                onForgettingDefaultChanged(null)
            },
        )
    }

    editingFoodItem?.let { item ->
        val resolution = editResolution
        if (resolution == null) {
            EditFoodItemDialog(
                item = item,
                errorMessage = editFoodError,
                onDismiss = { onEditingFoodItemChanged(null) },
                onRemove = {
                    viewModel.removeFoodItem(item.id)
                    onEditingFoodItemChanged(null)
                },
                onSave = { name, amount, unit, calories, time, notes ->
                    onEditFoodErrorChanged(null)
                    viewModel.updateFoodItem(
                        id = item.id,
                        name = name,
                        amount = amount,
                        unit = unit,
                        calories = calories,
                        time = time,
                        notes = notes,
                        onUpdated = { onEditingFoodItemChanged(null) },
                        onError = onEditFoodErrorChanged,
                        onNeedsDefaultResolution = {
                            onEditResolutionTimeChanged(time)
                            onEditResolutionChanged(it)
                        },
                    )
                },
            )
        } else {
            ResolveLoggedFoodEditDialog(
                resolution = resolution,
                errorMessage = editFoodError,
                onDismiss = { onEditingFoodItemChanged(null) },
                onSave = { parts ->
                    onEditFoodErrorChanged(null)
                    viewModel.saveResolvedFoodItemEdit(
                        id = item.id,
                        rawText = resolution.rawText,
                        time = editResolutionTime,
                        parts = parts,
                        onUpdated = { onEditingFoodItemChanged(null) },
                        onError = onEditFoodErrorChanged,
                    )
                },
            )
        }
    }

    if (editingBoundary) {
        DayBoundaryDialog(
            currentBoundary = uiState.dayBoundaryTime,
            onDismiss = { onEditingBoundaryChanged(false) },
            onSave = { boundary ->
                viewModel.updateDayBoundaryTime(boundary)
                onEditingBoundaryChanged(false)
            },
        )
    }

    editingWeightDate?.let { date ->
        val dayState by viewModel.dayState(date).collectAsState()
        DailyWeightDialog(
            dailyWeight = dayState.dailyWeight,
            onDismiss = { onEditingWeightDateChanged(null) },
            onSave = { stone, pounds, time ->
                viewModel.saveDailyWeight(
                    date = date,
                    stone = stone,
                    pounds = pounds,
                    time = time,
                    onSaved = { onEditingWeightDateChanged(null) },
                )
            },
        )
    }

    if (pickingDate) {
        LogDatePickerDialog(
            selectedDate = uiState.selectedDate,
            onDismiss = { onPickingDateChanged(false) },
            onDateSelected = { date ->
                onDateSelected(date)
                onPickingDateChanged(false)
            },
        )
    }

    if (choosingLabelImage) {
        LabelImageSourceDialog(
            onDismiss = { onChoosingLabelImageChanged(false) },
            onTakePhoto = {
                onChoosingLabelImageChanged(false)
                onTakeLabelPhoto()
            },
            onChooseImage = {
                onChoosingLabelImageChanged(false)
                onChooseLabelImage()
            },
        )
    }

    labelReview?.let { review ->
        if (review.isProcessing) {
            AlertDialog(
                onDismissRequest = { viewModel.clearLabelReview() },
                title = { Text("Log from label") },
                text = { Text("Reading label...") },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { viewModel.clearLabelReview() }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }

    pendingQuantityPicker?.let { shortcut ->
        QuantityPickerDialog(
            shortcut = shortcut,
            onDismiss = viewModel::dismissQuantityPicker,
            onConfirm = { amount ->
                viewModel.logShortcutWithAmount(shortcut.trigger, amount)
            },
        )
    }

    loggingWizard?.let { session ->
        LoggingWizardDialog(
            session = session,
            onDismiss = viewModel::clearLoggingWizard,
            onPartChanged = viewModel::updateLoggingWizardPart,
            onCurrentPartChanged = viewModel::setLoggingWizardCurrentPart,
            onTimeChanged = viewModel::updateLoggingWizardTime,
            onTimeConfirmed = viewModel::confirmLoggingWizardTime,
            onInputModeChanged = viewModel::setLastLabelInputMode,
            onRemove = session.sourceRawEntryId?.takeIf { session.source == LoggingWizardSource.Pending }?.let { rawEntryId ->
                {
                    viewModel.removePendingEntry(
                        id = rawEntryId,
                        onRemoved = viewModel::clearLoggingWizard,
                    )
                }
            },
            onSave = viewModel::saveLoggingWizard,
        )
    }
}
