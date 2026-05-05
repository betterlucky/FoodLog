package com.betterlucky.foodlog.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.betterlucky.foodlog.data.entities.DailyWeightEntity
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import com.betterlucky.foodlog.data.entities.UserDefaultEntity
import com.betterlucky.foodlog.data.entities.DailyStatusEntity
import com.betterlucky.foodlog.domain.label.LabelInputMode
import com.betterlucky.foodlog.domain.label.LabelPortionResolver
import com.betterlucky.foodlog.domain.label.toItemAmount
import com.betterlucky.foodlog.domain.parser.TimeTextParser
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.floor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onShareCsv: (String, String) -> Unit,
    onTakeLabelPhoto: () -> Unit,
    onChooseLabelImage: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val labelReview by viewModel.labelReview.collectAsState()
    val pendingQuantityPicker by viewModel.pendingQuantityPicker.collectAsState()
    val focusManager = LocalFocusManager.current
    var resolvingEntry by remember { mutableStateOf<RawEntryEntity?>(null) }
    var editingDefault by remember { mutableStateOf<UserDefaultEntity?>(null) }
    var forgettingDefault by remember { mutableStateOf<UserDefaultEntity?>(null) }
    var editingFoodItem by remember { mutableStateOf<FoodItemEntity?>(null) }
    var editingBoundary by remember { mutableStateOf(false) }
    var editingWeight by remember { mutableStateOf(false) }
    var showingShortcuts by remember { mutableStateOf(false) }
    var addingShortcut by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var choosingLabelImage by remember { mutableStateOf(false) }
    var loggedItemsViewMode by remember { mutableStateOf(LoggedItemsViewMode.Time) }
    var expandedLoggedClumps by remember { mutableStateOf(emptySet<String>()) }
    var pendingExpanded by remember(uiState.selectedDate) { mutableStateOf(uiState.pendingEntries.isNotEmpty()) }
    var loggedExpanded by remember(uiState.selectedDate) { mutableStateOf(uiState.items.isNotEmpty()) }
    val listState = rememberLazyListState()
    val readiness = dailyReadiness(
        dailyStatus = uiState.dailyStatus,
        pendingCount = uiState.pendingEntries.size,
        foodItemCount = uiState.items.size,
        hasDailyWeight = uiState.dailyWeight != null,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = viewModel::previousDay) {
                Text("Prev")
            }
            OutlinedButton(onClick = { pickingDate = true }) {
                Text(
                    text = uiState.selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            TextButton(onClick = viewModel::nextDay) {
                Text("Next")
            }
        }

        FoodDaySettingsRow(
            dayBoundaryTime = uiState.dayBoundaryTime,
            onEdit = { editingBoundary = true },
        )

        LaunchedEffect(uiState.pendingEntries.size) {
            if (uiState.pendingEntries.isNotEmpty()) {
                pendingExpanded = true
            }
        }

        LaunchedEffect(uiState.items.size) {
            if (uiState.items.isNotEmpty()) {
                loggedExpanded = true
            }
        }

        TodayStatusSummary(
            totalCalories = uiState.totalCalories,
            pendingCount = uiState.pendingEntries.size,
            dailyStatus = uiState.dailyStatus,
            readiness = readiness,
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = uiState.inputText,
                onValueChange = viewModel::onInputChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 180.dp),
                minLines = 2,
                maxLines = 5,
                label = { Text("Type food naturally") },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.submit()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Log")
                }
                Button(
                    onClick = { showingShortcuts = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Shortcuts")
                }
                Button(
                    onClick = { choosingLabelImage = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Scan label")
                }
            }
        }

        uiState.message?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                CollapsibleSectionHeader(
                    title = "Pending (${uiState.pendingEntries.size})",
                    detail = pendingCountText(uiState.pendingEntries.size),
                    expanded = pendingExpanded,
                    onToggle = { pendingExpanded = !pendingExpanded },
                )
            }
            if (pendingExpanded && uiState.pendingEntries.isEmpty()) {
                item {
                    EmptyState("No pending entries for this day.")
                }
            } else if (pendingExpanded) {
                items(uiState.pendingEntries) { entry ->
                    PendingEntryRow(
                        entry = entry,
                        onResolve = { resolvingEntry = entry },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                CollapsibleSectionHeader(
                    title = "Logged (${uiState.items.size})",
                    detail = loggedItemsSummary(
                        itemCount = uiState.items.size,
                        totalCalories = uiState.totalCalories,
                    ),
                    expanded = loggedExpanded,
                    onToggle = { loggedExpanded = !loggedExpanded },
                )
            }
            if (loggedExpanded && uiState.items.isEmpty()) {
                item {
                    EmptyState("No food logged for this day yet.")
                }
            } else if (loggedExpanded) {
                item {
                    LoggedItemsViewControls(
                        selectedMode = loggedItemsViewMode,
                        onModeSelected = { mode ->
                            loggedItemsViewMode = mode
                            expandedLoggedClumps = emptySet()
                        },
                    )
                }
                when (loggedItemsViewMode) {
                    LoggedItemsViewMode.Time,
                    LoggedItemsViewMode.Calories -> {
                        items(uiState.items.sortedFor(loggedItemsViewMode)) { item ->
                            FoodItemRow(
                                item = item,
                                onClick = { editingFoodItem = item },
                            )
                        }
                    }
                    LoggedItemsViewMode.Clumped -> {
                        loggedItemClumps(uiState.items).forEach { clump ->
                            val isExpanded = clump.key in expandedLoggedClumps
                            item {
                                ClumpedFoodItemRow(
                                    clump = clump,
                                    expanded = isExpanded,
                                    onClick = {
                                        if (clump.items.size == 1) {
                                            editingFoodItem = clump.items.single()
                                        } else {
                                            expandedLoggedClumps = if (isExpanded) {
                                                expandedLoggedClumps - clump.key
                                            } else {
                                                expandedLoggedClumps + clump.key
                                            }
                                        }
                                    },
                                )
                            }
                            if (isExpanded) {
                                items(clump.items.sortedFor(LoggedItemsViewMode.Time)) { item ->
                                    FoodItemRow(
                                        item = item,
                                        onClick = { editingFoodItem = item },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                DailyWeightRow(
                    dailyWeight = uiState.dailyWeight,
                    onEdit = { editingWeight = true },
                )
            }

            item {
                DailyClosePrompt(
                    dailyStatus = uiState.dailyStatus,
                    pendingCount = uiState.pendingEntries.size,
                    foodItemCount = uiState.items.size,
                    hasDailyWeight = uiState.dailyWeight != null,
                    onExportLegacy = { viewModel.exportLegacyCsv(onShareCsv) },
                )
            }
        }
    }

    if (showingShortcuts) {
        ShortcutPickerDialog(
            userDefaults = uiState.userDefaults,
            onDismiss = { showingShortcuts = false },
            onAdd = { addingShortcut = true },
            onLog = { userDefault ->
                viewModel.logShortcut(userDefault.trigger)
            },
            onEdit = { userDefault ->
                editingDefault = userDefault
            },
            onForget = { userDefault ->
                forgettingDefault = userDefault
            },
        )
    }

    resolvingEntry?.let { entry ->
        var pendingResolution by remember(entry.id) { mutableStateOf<LoggedFoodEditResolution?>(null) }
        var pendingResolutionError by remember(entry.id) { mutableStateOf<String?>(null) }
        var pendingDraft by remember(entry.id) { mutableStateOf<PendingEntryDraft?>(null) }
        LaunchedEffect(entry.id) {
            viewModel.previewPendingEntryResolution(
                rawEntryId = entry.id,
                onReady = { pendingResolution = it },
                onSinglePart = { pendingDraft = it },
            )
        }
        val resolution = pendingResolution
        if (resolution == null) {
            ResolvePendingDialog(
                entry = entry,
                draft = pendingDraft,
                onDismiss = { resolvingEntry = null },
                onRemove = {
                    viewModel.removePendingEntry(
                        id = entry.id,
                        onRemoved = { resolvingEntry = null },
                    )
                },
                onResolve = { name, amount, unit, calories, time, notes, saveAsDefault ->
                    viewModel.resolvePendingEntry(
                        rawEntryId = entry.id,
                        name = name,
                        amount = amount,
                        unit = unit,
                        calories = calories,
                        time = time,
                        notes = notes,
                        saveAsDefault = saveAsDefault,
                        onResolved = { resolvingEntry = null },
                    )
                },
            )
        } else {
            ResolveLoggedFoodEditDialog(
                resolution = resolution,
                title = "Resolve pending meal",
                contextText = entry.consumedTime?.let { "Time: $it" },
                dismissLabel = "Keep pending",
                errorMessage = pendingResolutionError,
                onDismiss = { resolvingEntry = null },
                onSave = { parts ->
                    pendingResolutionError = null
                    viewModel.saveResolvedPendingEntry(
                        rawEntryId = entry.id,
                        rawText = resolution.rawText,
                        parts = parts,
                        onResolved = { resolvingEntry = null },
                        onError = { pendingResolutionError = it },
                    )
                },
            )
        }
    }

    editingDefault?.let { userDefault ->
        EditShortcutDialog(
            userDefault = userDefault,
            onDismiss = { editingDefault = null },
            onSave = { name, calories, unit, notes ->
                viewModel.updateShortcut(
                    trigger = userDefault.trigger,
                    name = name,
                    calories = calories,
                    unit = unit,
                    notes = notes,
                    onUpdated = { editingDefault = null },
                )
            },
        )
    }

    if (addingShortcut) {
        AddShortcutDialog(
            onDismiss = { addingShortcut = false },
            onSave = { trigger, name, calories, unit, notes ->
                viewModel.addShortcut(
                    trigger = trigger,
                    name = name,
                    calories = calories,
                    unit = unit,
                    notes = notes,
                    onAdded = { addingShortcut = false },
                )
            },
        )
    }

    forgettingDefault?.let { userDefault ->
        ForgetShortcutDialog(
            userDefault = userDefault,
            onDismiss = { forgettingDefault = null },
            onConfirm = {
                viewModel.forgetShortcut(userDefault.trigger)
                forgettingDefault = null
            },
        )
    }

    editingFoodItem?.let { item ->
        var editFoodError by remember(item.id) { mutableStateOf<String?>(null) }
        var editResolution by remember(item.id) { mutableStateOf<LoggedFoodEditResolution?>(null) }
        var editResolutionTime by remember(item.id) { mutableStateOf(item.consumedTime?.toString().orEmpty()) }
        val resolution = editResolution
        if (resolution == null) {
            EditFoodItemDialog(
                item = item,
                errorMessage = editFoodError,
                onDismiss = { editingFoodItem = null },
                onRemove = {
                    viewModel.removeFoodItem(item.id)
                    editingFoodItem = null
                },
                onSave = { name, amount, unit, calories, time, notes ->
                    editFoodError = null
                    viewModel.updateFoodItem(
                        id = item.id,
                        name = name,
                        amount = amount,
                        unit = unit,
                        calories = calories,
                        time = time,
                        notes = notes,
                        onUpdated = { editingFoodItem = null },
                        onError = { editFoodError = it },
                        onNeedsDefaultResolution = {
                            editResolutionTime = time
                            editResolution = it
                        },
                    )
                },
            )
        } else {
            ResolveLoggedFoodEditDialog(
                resolution = resolution,
                errorMessage = editFoodError,
                onDismiss = { editingFoodItem = null },
                onSave = { parts ->
                    editFoodError = null
                    viewModel.saveResolvedFoodItemEdit(
                        id = item.id,
                        rawText = resolution.rawText,
                        time = editResolutionTime,
                        parts = parts,
                        onUpdated = { editingFoodItem = null },
                        onError = { editFoodError = it },
                    )
                },
            )
        }
    }

    if (editingBoundary) {
        DayBoundaryDialog(
            currentBoundary = uiState.dayBoundaryTime,
            onDismiss = { editingBoundary = false },
            onSave = { boundary ->
                viewModel.updateDayBoundaryTime(boundary)
                editingBoundary = false
            },
        )
    }

    if (editingWeight) {
        DailyWeightDialog(
            dailyWeight = uiState.dailyWeight,
            onDismiss = { editingWeight = false },
            onSave = { stone, pounds, time ->
                viewModel.saveDailyWeight(
                    stone = stone,
                    pounds = pounds,
                    time = time,
                    onSaved = { editingWeight = false },
                )
            },
        )
    }

    if (pickingDate) {
        LogDatePickerDialog(
            selectedDate = uiState.selectedDate,
            onDismiss = { pickingDate = false },
            onDateSelected = { date ->
                viewModel.selectDate(date)
                pickingDate = false
            },
        )
    }

    if (choosingLabelImage) {
        LabelImageSourceDialog(
            onDismiss = { choosingLabelImage = false },
            onTakePhoto = {
                choosingLabelImage = false
                onTakeLabelPhoto()
            },
            onChooseImage = {
                choosingLabelImage = false
                onChooseLabelImage()
            },
        )
    }

    labelReview?.let { review ->
        LabelReviewDialog(
            review = review,
            initialInputMode = LabelInputMode.fromStorage(uiState.lastLabelInputMode),
            onDismiss = { viewModel.clearLabelReview() },
            onInputModeChanged = { viewModel.setLastLabelInputMode(it) },
            onLog = { name, inputMode, amountText, calories, time, saveAsShortcut ->
                viewModel.logLabelItem(
                    name = name,
                    inputMode = inputMode,
                    amountText = amountText,
                    calories = calories,
                    time = time,
                    saveAsShortcut = saveAsShortcut,
                )
            },
        )
    }

    pendingQuantityPicker?.let { shortcut ->
        QuantityPickerDialog(
            shortcut = shortcut,
            onDismiss = { viewModel.dismissQuantityPicker() },
            onConfirm = { amount ->
                viewModel.logShortcutWithAmount(shortcut.trigger, amount)
            },
        )
    }
}

@Composable
private fun FoodDaySettingsRow(
    dayBoundaryTime: LocalTime?,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Boundary: ${dayBoundaryTime?.toString() ?: "calendar day"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onEdit) {
            Text("Settings")
        }
    }
}

@Composable
private fun DailyClosePrompt(
    dailyStatus: DailyStatusEntity?,
    pendingCount: Int,
    foodItemCount: Int,
    hasDailyWeight: Boolean,
    onExportLegacy: () -> Unit,
) {
    val readiness = dailyReadiness(
        dailyStatus = dailyStatus,
        pendingCount = pendingCount,
        foodItemCount = foodItemCount,
        hasDailyWeight = hasDailyWeight,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FoodLogCardShape,
        colors = CardDefaults.cardColors(
            containerColor = when (readiness) {
                DailyReadiness.ResolvePending -> MaterialTheme.colorScheme.errorContainer
                DailyReadiness.ReadyToExport -> MaterialTheme.colorScheme.primaryContainer
                DailyReadiness.NoFoodLogged,
                DailyReadiness.AlreadyExported -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Daily close",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = readiness.closePromptText(),
                    color = when (readiness) {
                        DailyReadiness.ResolvePending -> MaterialTheme.colorScheme.onErrorContainer
                        DailyReadiness.ReadyToExport -> MaterialTheme.colorScheme.onPrimaryContainer
                        DailyReadiness.NoFoodLogged,
                        DailyReadiness.AlreadyExported -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (readiness == DailyReadiness.ReadyToExport) {
                Button(onClick = onExportLegacy) {
                    Text("Export")
                }
            }
        }
    }
}

private enum class DailyReadiness(val label: String) {
    NoFoodLogged("No food logged"),
    ResolvePending("Resolve pending entries"),
    ReadyToExport("Ready to export"),
    AlreadyExported("Already exported"),
}

private fun DailyReadiness.closePromptText(): String =
    when (this) {
        DailyReadiness.NoFoodLogged -> "No export needed yet."
        DailyReadiness.ResolvePending -> "Resolve pending entries before Health Monitor export."
        DailyReadiness.ReadyToExport -> "Export the Health Monitor CSV before closing this day."
        DailyReadiness.AlreadyExported -> "Health Monitor export is current."
    }

private fun pendingCountText(count: Int): String =
    when (count) {
        0 -> "all clear"
        1 -> "1 item"
        else -> "$count items"
    }

private fun loggedItemsSummary(
    itemCount: Int,
    totalCalories: Double,
): String =
    when (itemCount) {
        0 -> "empty"
        1 -> "1 item - ${totalCalories.toInt()} kcal"
        else -> "$itemCount items - ${totalCalories.toInt()} kcal"
    }

private val FoodLogCardShape = RoundedCornerShape(8.dp)

private enum class LoggedItemsViewMode(val label: String) {
    Time("Time"),
    Calories("Calories"),
    Clumped("Clumped"),
}

@Composable
private fun TodayStatusSummary(
    totalCalories: Double,
    pendingCount: Int,
    dailyStatus: DailyStatusEntity?,
    readiness: DailyReadiness,
) {
    val color = when (readiness) {
        DailyReadiness.ResolvePending -> MaterialTheme.colorScheme.error
        DailyReadiness.ReadyToExport -> MaterialTheme.colorScheme.primary
        DailyReadiness.NoFoodLogged,
        DailyReadiness.AlreadyExported -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FoodLogCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Today",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "${totalCalories.toInt()} kcal",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "$pendingCount pending",
                        color = if (pendingCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (pendingCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = readiness.label,
                        color = color,
                        fontWeight = if (readiness == DailyReadiness.ReadyToExport) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (readiness != DailyReadiness.NoFoodLogged) {
                Text(
                    text = "Health Monitor: ${dailyStatus.exportText(dailyStatus?.legacyExportedAt, dailyStatus?.legacyExportFileName)}",
                    color = color,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CollapsibleSectionHeader(
    title: String,
    detail: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (expanded) "▾" else "▸",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
        )
        detail?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LoggedItemsViewControls(
    selectedMode: LoggedItemsViewMode,
    onModeSelected: (LoggedItemsViewMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LoggedItemsViewMode.entries.forEach { mode ->
            val selected = mode == selectedMode
            if (selected) {
                Button(
                    onClick = { onModeSelected(mode) },
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                OutlinedButton(
                    onClick = { onModeSelected(mode) },
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun DailyStatusEntity?.exportText(
    exportedAt: Instant?,
    fileName: String?,
): String =
    when {
        exportedAt == null -> "not exported"
        this?.lastFoodChangedAt != null && lastFoodChangedAt > exportedAt ->
            "changed since ${exportedAt.displayTime()}${fileName.exportFileSuffix()}"
        else -> "exported ${exportedAt.displayTime()}${fileName.exportFileSuffix()}"
    }

private fun String?.exportFileSuffix(): String =
    if (isNullOrBlank()) "" else " - $this"

private fun dailyReadiness(
    dailyStatus: DailyStatusEntity?,
    pendingCount: Int,
    foodItemCount: Int,
    hasDailyWeight: Boolean,
): DailyReadiness =
    when {
        pendingCount > 0 -> DailyReadiness.ResolvePending
        foodItemCount == 0 && !hasDailyWeight -> DailyReadiness.NoFoodLogged
        dailyStatus.isLegacyExportCurrent() -> DailyReadiness.AlreadyExported
        else -> DailyReadiness.ReadyToExport
    }

private fun DailyStatusEntity?.isLegacyExportCurrent(): Boolean {
    val exportedAt = this?.legacyExportedAt ?: return false
    val changedAt = lastFoodChangedAt ?: return true
    return changedAt <= exportedAt
}

private fun Instant.displayTime(): String =
    atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private data class LoggedItemClump(
    val key: String,
    val name: String,
    val quantity: String?,
    val items: List<FoodItemEntity>,
)

@Composable
private fun DailyWeightRow(
    dailyWeight: DailyWeightEntity?,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Weight: ${dailyWeight?.displayWeight() ?: "not recorded"}",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            dailyWeight?.let {
                Text(
                    text = "Measured at ${it.measuredTime}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        TextButton(onClick = onEdit) {
            Text(if (dailyWeight == null) "Add" else "Edit")
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun FoodItemRow(
    item: FoodItemEntity,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = FoodLogCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = listOfNotNull(
                        item.consumedTime?.toString() ?: "No time",
                        quantityText(item),
                    ).joinToString(" - "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "${item.calories.toInt()} kcal",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun quantityText(item: FoodItemEntity): String? =
    when {
        item.amount != null &&
            item.unit != null &&
            item.source == com.betterlucky.foodlog.data.entities.FoodItemSource.SAVED_LABEL &&
            item.unit !in setOf("g", "ml") ->
            LabelPortionResolver.displayAmount(item.amount, item.unit)
        item.amount != null && item.unit != null -> "${item.amount.formatAmount()} ${pluralizedUnit(item.unit, item.amount)}"
        item.amount != null -> item.amount.formatAmount()
        item.unit != null -> item.unit
        else -> null
    }

@Composable
private fun ClumpedFoodItemRow(
    clump: LoggedItemClump,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val count = clump.items.size
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = FoodLogCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (count == 1) clump.name else "${clump.name} x$count",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = clumpDetailText(clump, expanded),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "${clump.items.sumOf { it.calories }.toInt()} kcal",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun clumpDetailText(
    clump: LoggedItemClump,
    expanded: Boolean,
): String {
    val count = clump.items.size
    val times = clump.items
        .map { it.consumedTime?.toString() ?: "No time" }
        .distinct()
        .joinToString(", ")
    val quantity = clump.quantity?.let { if (count == 1) it else "$it each" }
    val action = if (count > 1) {
        if (expanded) "tap to collapse" else "tap to expand"
    } else {
        "tap to edit"
    }

    return listOfNotNull(times, quantity, action).joinToString(" - ")
}

private fun List<FoodItemEntity>.sortedFor(mode: LoggedItemsViewMode): List<FoodItemEntity> =
    when (mode) {
        LoggedItemsViewMode.Time,
        LoggedItemsViewMode.Clumped -> sortedWith(
            compareBy<FoodItemEntity> { it.consumedTime }
                .thenBy { it.createdAt }
                .thenBy { it.id },
        )
        LoggedItemsViewMode.Calories -> sortedWith(
            compareByDescending<FoodItemEntity> { it.calories }
                .thenBy { it.consumedTime }
                .thenBy { it.createdAt }
                .thenBy { it.id },
        )
    }

private fun loggedItemClumps(items: List<FoodItemEntity>): List<LoggedItemClump> =
    items
        .groupBy { item ->
            listOf(
                item.name.trim().lowercase(),
                quantityText(item).orEmpty(),
                item.calories.formatAmount(),
            ).joinToString("|")
        }
        .map { (key, groupedItems) ->
            val first = groupedItems.first()
            LoggedItemClump(
                key = key,
                name = first.name,
                quantity = quantityText(first),
                items = groupedItems.sortedFor(LoggedItemsViewMode.Time),
            )
        }
        .sortedWith(
            compareBy<LoggedItemClump> { it.items.firstOrNull()?.consumedTime }
                .thenBy { it.items.firstOrNull()?.createdAt }
                .thenBy { it.name.lowercase() },
        )

private fun Double.formatAmount(): String =
    if (rem(1.0) == 0.0) toInt().toString() else toString()

private fun Double.formatLabelNumber(): String =
    if (rem(1.0) == 0.0) {
        toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", this).trimEnd('0').trimEnd('.')
    }

private fun Float.snapToTenth(): Float =
    (kotlin.math.round(this * 10f) / 10f).coerceIn(0f, 1f)

private fun DailyWeightEntity.displayWeight(): String {
    val stonePounds = weightKg.toStonePounds()
    return "${stonePounds.stone} st ${stonePounds.pounds.formatPounds()} lb (${weightKg.formatWeightKg()} kg)"
}

private fun Double.toStonePounds(): StonePounds {
    val totalPounds = this / 0.45359237
    val stone = floor(totalPounds / 14.0).toInt()
    val pounds = totalPounds - (stone * 14.0)
    return StonePounds(stone = stone, pounds = pounds)
}

private data class StonePounds(
    val stone: Int,
    val pounds: Double,
)

private fun Double.formatWeightKg(): String =
    String.format(java.util.Locale.US, "%.1f", this)

private fun Double.formatPounds(): String =
    String.format(java.util.Locale.US, "%.1f", this).removeSuffix(".0")

private fun pluralizedUnit(
    unit: String,
    amount: Double,
): String =
    when {
        amount == 1.0 -> unit
        unit == "cup" -> "cups"
        else -> unit
    }

private fun RawEntryEntity.displayFoodText(): String {
    if (consumedTime == null) return rawText

    val normalizedRawText = rawText.trim()
    val timePattern = TimeTextParser.PATTERN
    val prefixMatch = Regex("^($timePattern)\\s+(.+)$", RegexOption.IGNORE_CASE)
        .matchEntire(normalizedRawText)
    if (prefixMatch != null) {
        return prefixMatch.groupValues[2]
    }

    val suffixMatch = Regex("^(.+?)\\s+(?:at\\s+)?($timePattern)$", RegexOption.IGNORE_CASE)
        .matchEntire(normalizedRawText)
    return suffixMatch?.groupValues?.get(1) ?: normalizedRawText
}


@Composable
private fun LabelImageSourceDialog(
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseImage: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use label") },
        text = { Text("Use one clear photo of the nutrition label. You can check and edit the values before logging.") },
        confirmButton = {
            Button(onClick = onTakePhoto) {
                Text("Take photo")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onChooseImage) {
                    Text("Choose image")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
private fun LabelReviewDialog(
    review: LabelReviewState,
    initialInputMode: LabelInputMode,
    onDismiss: () -> Unit,
    onInputModeChanged: (LabelInputMode) -> Unit,
    onLog: (name: String, inputMode: LabelInputMode, amountText: String, calories: String, time: String, saveAsShortcut: Boolean) -> Unit,
) {
    val facts = review.facts
    var name by remember { mutableStateOf("") }
    var inputMode by remember(facts.rawText, initialInputMode) { mutableStateOf(initialInputMode) }
    var amountText by remember(facts.rawText) { mutableStateOf("") }
    var sliderAmount by remember(facts.rawText) { mutableStateOf(0f) }
    val resolvedPortion = LabelPortionResolver.resolve(facts, inputMode, amountText)
    var calories by remember(facts.rawText) { mutableStateOf("") }
    var time by remember(facts.rawText) {
        mutableStateOf(LocalTime.now().truncatedTo(ChronoUnit.MINUTES).toString())
    }
    var saveAsShortcut by remember { mutableStateOf(false) }
    var caloriesEdited by remember(facts.rawText) { mutableStateOf(false) }
    var step by remember(facts.rawText) { mutableStateOf(LabelDialogStep.Product) }
    var showingAmountHelp by remember { mutableStateOf(false) }
    LaunchedEffect(inputMode, amountText) {
        if (inputMode == LabelInputMode.ITEMS) {
            amountText.toItemAmount()?.let { parsedAmount ->
                sliderAmount = parsedAmount.toFloat().coerceIn(0f, 1f)
            }
        }
    }
    LaunchedEffect(inputMode, amountText, resolvedPortion.calories, caloriesEdited) {
        if (!caloriesEdited) {
            calories = resolvedPortion.calories?.formatLabelNumber().orEmpty()
        }
    }
    val canLog = name.isNotBlank() &&
        resolvedPortion.isValidAmount &&
        calories.toDoubleOrNull()?.let { it > 0.0 } == true
    val canContinue = when (step) {
        LabelDialogStep.Product -> name.isNotBlank()
        LabelDialogStep.Portion -> resolvedPortion.isValidAmount
        LabelDialogStep.Review -> canLog
    }
    val portionQuantity = resolvedPortion.amount?.let { amount ->
        val unit = resolvedPortion.unit.orEmpty()
        if (inputMode == LabelInputMode.ITEMS) {
            LabelPortionResolver.displayAmount(amount, unit)
        } else {
            "${amount.formatLabelNumber()} $unit"
        }
    }.orEmpty()
    val portionWeight = resolvedPortion.grams?.let { "${it.formatLabelNumber()}g" }.orEmpty()
    val portionSummary = buildString {
        append(portionQuantity)
        if (portionWeight.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(portionWeight)
        }
    }
    val itemUnit = LabelPortionResolver.itemUnit(facts)
    val sliderPortionLabel = resolvedPortion.amount
        ?.takeIf { inputMode == LabelInputMode.ITEMS }
        ?.let { LabelPortionResolver.displayAmount(it, itemUnit).replaceFirstChar { char -> char.titlecase() } }
        ?: "Choose amount"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Log from label")
                if (step == LabelDialogStep.Portion) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(Color(0xFF1F51FF), CircleShape)
                            .border(2.dp, Color.Black, CircleShape)
                            .clickable { showingAmountHelp = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "i",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (review.isProcessing) {
                    Text(
                        text = "Reading label...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (facts.hasRequiredCalories) {
                    val hint = buildString {
                        facts.kcalPer100g?.let { append("${it.toInt()} kcal/100g") }
                        facts.kcalPerServing?.let {
                            if (isNotEmpty()) append(" · ")
                            append("${it.toInt()} kcal/${facts.servingUnit ?: "serving"}")
                        }
                        facts.servingSizeGrams?.let { append(" (${it.toInt()}g)") }
                    }
                    Text(
                        text = "From label: $hint",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text = "No calorie values found — check the label is in focus and try again, or enter manually.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                when (step) {
                    LabelDialogStep.Product -> {
                        Text(
                            text = "Tell me what the product we just scanned is.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Item name") },
                        )
                    }
                    LabelDialogStep.Portion -> {
                        Text(
                            text = "What's your portion size today?",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Items",
                                modifier = Modifier.weight(1f),
                                fontWeight = if (inputMode == LabelInputMode.ITEMS) FontWeight.SemiBold else null,
                            )
                            Switch(
                                checked = inputMode == LabelInputMode.MEASURE,
                                onCheckedChange = { checked ->
                                    inputMode = if (checked) LabelInputMode.MEASURE else LabelInputMode.ITEMS
                                    amountText = ""
                                    onInputModeChanged(inputMode)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    uncheckedThumbColor = MaterialTheme.colorScheme.primary,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                                    uncheckedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                            Text(
                                text = "g/ml",
                                modifier = Modifier.weight(1f),
                                fontWeight = if (inputMode == LabelInputMode.MEASURE) FontWeight.SemiBold else null,
                            )
                        }
                        if (inputMode == LabelInputMode.ITEMS) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = sliderPortionLabel,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Slider(
                                value = sliderAmount,
                                onValueChange = { rawValue ->
                                    val snapped = rawValue.snapToTenth()
                                    sliderAmount = snapped
                                    amountText = if (snapped == 0f) "" else snapped.toDouble().formatLabelNumber()
                                },
                                valueRange = 0f..1f,
                                steps = 9,
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White,
                                    activeTickColor = Color.Black,
                                    inactiveTickColor = Color.Black,
                                ),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = amountText,
                                    onValueChange = { amountText = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("Amount") },
                                    textStyle = MaterialTheme.typography.bodyLarge,
                                )
                                OutlinedTextField(
                                    value = itemUnit,
                                    onValueChange = {},
                                    modifier = Modifier.weight(1f),
                                    readOnly = true,
                                    singleLine = true,
                                    label = { Text("Unit") },
                                    textStyle = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = amountText,
                                onValueChange = { amountText = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Amount (g/ml)") },
                            )
                        }
                        if (portionSummary.isNotBlank()) {
                            Text(
                                text = portionSummary,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    LabelDialogStep.Review -> {
                        Text(
                            text = "Preview log entry",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = listOf(time, name).joinToString(" - "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = listOf(portionSummary.takeIf { it.isNotBlank() }, calories.toDoubleOrNull()?.let { "${it.formatLabelNumber()} kcal" })
                                .filterNotNull()
                                .joinToString(" - "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TimeTextField(
                                value = time,
                                onValueChange = { time = it },
                                modifier = Modifier.weight(1f),
                                label = "Time",
                            )
                            OutlinedTextField(
                                value = calories,
                                onValueChange = {
                                    calories = it
                                    caloriesEdited = true
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                label = { Text("Calories") },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = saveAsShortcut,
                                onCheckedChange = { saveAsShortcut = it },
                            )
                            Text("Save as shortcut", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (step) {
                        LabelDialogStep.Product -> step = LabelDialogStep.Portion
                        LabelDialogStep.Portion -> step = LabelDialogStep.Review
                        LabelDialogStep.Review -> onLog(name, inputMode, amountText, calories, time, saveAsShortcut)
                    }
                },
                enabled = canContinue,
            ) {
                Text(if (step == LabelDialogStep.Review) "Log" else "Next")
            }
        },
        dismissButton = {
            Row {
                if (step != LabelDialogStep.Product) {
                    TextButton(
                        onClick = {
                            step = when (step) {
                                LabelDialogStep.Portion -> LabelDialogStep.Product
                                LabelDialogStep.Review -> LabelDialogStep.Portion
                                LabelDialogStep.Product -> LabelDialogStep.Product
                            }
                        },
                    ) {
                        Text("Back")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
    if (showingAmountHelp) {
        AlertDialog(
            onDismissRequest = { showingAmountHelp = false },
            title = { Text("Amount entry") },
            text = {
                Text("The amount and calories boxes are editable. Amount accepts decimals, fractions, and text such as 0.75, 1/3, one third, or two thirds of a can.")
            },
            confirmButton = {
                TextButton(onClick = { showingAmountHelp = false }) {
                    Text("OK")
                }
            },
        )
    }
}

private enum class LabelDialogStep {
    Product,
    Portion,
    Review,
}

@Composable
private fun LabelInputModeButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(text)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(text)
        }
    }
}

@Composable
private fun QuantityPickerDialog(
    shortcut: UserDefaultEntity,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    val parsedAmount = amountText.toDoubleOrNull()?.takeIf { it > 0.0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How much ${shortcut.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${shortcut.calories.formatAmount()} kcal per ${shortcut.unit}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("Amount (${shortcut.unit})") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "1" to 1.0,
                        "½" to 0.5,
                        "⅓" to (1.0 / 3.0),
                        "⅔" to (2.0 / 3.0),
                    ).forEach { (label, value) ->
                        OutlinedButton(
                            onClick = { amountText = value.formatAmount() },
                            modifier = Modifier.weight(1f),
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { parsedAmount?.let(onConfirm) },
                enabled = parsedAmount != null,
            ) {
                Text("Log")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun PendingEntryRow(
    entry: RawEntryEntity,
    onResolve: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onResolve),
        shape = FoodLogCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayFoodText(),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = listOfNotNull("Needs review", entry.consumedTime?.let { "Time: $it" }).joinToString(" - "),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = "Review",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ShortcutPickerDialog(
    userDefaults: List<UserDefaultEntity>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onLog: (UserDefaultEntity) -> Unit,
    onEdit: (UserDefaultEntity) -> Unit,
    onForget: (UserDefaultEntity) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var lastLogged by remember { mutableStateOf<String?>(null) }
    val filteredDefaults = userDefaults.filter { userDefault ->
        val normalizedQuery = query.trim()
        normalizedQuery.isBlank() ||
            userDefault.trigger.contains(normalizedQuery, ignoreCase = true) ||
            userDefault.name.contains(normalizedQuery, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shortcuts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search shortcuts") },
                )
                lastLogged?.let {
                    Text(
                        text = "Logged $it",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (filteredDefaults.isEmpty()) {
                    EmptyState("No matching shortcuts.")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filteredDefaults) { userDefault ->
                            ShortcutRow(
                                userDefault = userDefault,
                                onLog = {
                                    onLog(userDefault)
                                    lastLogged = userDefault.name
                                },
                                onEdit = { onEdit(userDefault) },
                                onForget = { onForget(userDefault) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onAdd) {
                Text("Add shortcut")
            }
        },
    )
}

@Composable
private fun ShortcutRow(
    userDefault: UserDefaultEntity,
    onLog: () -> Unit,
    onEdit: () -> Unit,
    onForget: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLog),
        shape = FoodLogCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = userDefault.trigger, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${userDefault.name} - ${userDefault.calories.formatAmount()} kcal per ${userDefault.unit}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit) {
                        Text("Edit")
                    }
                    TextButton(onClick = onForget) {
                        Text("Forget")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogDatePickerDialog(
    selectedDate: LocalDate,
    onDismiss: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate.toEpochMillis(),
        initialDisplayMode = DisplayMode.Picker,
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    datePickerState.selectedDateMillis
                        ?.toLocalDate()
                        ?.let(onDateSelected)
                },
            ) {
                Text("Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    ) {
        DatePicker(
            state = datePickerState,
            showModeToggle = true,
        )
    }
}

@Composable
private fun TimeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    var pickingTime by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = true,
            singleLine = true,
            isError = isError,
            label = { Text(label) },
            placeholder = { Text("HH:mm") },
            supportingText = supportingText?.let { { Text(it) } },
        )
        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { pickingTime = true },
            )
        }
    }

    if (pickingTime) {
        TimeChoiceDialog(
            value = value,
            onDismiss = { pickingTime = false },
            onTimeSelected = { selected ->
                onValueChange(selected)
                pickingTime = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeChoiceDialog(
    value: String,
    onDismiss: () -> Unit,
    onTimeSelected: (String) -> Unit,
) {
    val initialTime = remember(value) {
        TimeTextParser.parseOrNull(value) ?: LocalTime.now()
    }
    val timePickerState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = true,
    )
    var textInput by remember { mutableStateOf(false) }
    var selectedHour by remember { mutableStateOf(initialTime.hour) }
    var selectedMinute by remember { mutableStateOf(initialTime.minute) }

    LaunchedEffect(timePickerState.hour, timePickerState.minute) {
        selectedHour = timePickerState.hour
        selectedMinute = timePickerState.minute
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose time") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { textInput = false }) {
                        Text("Clock")
                    }
                    OutlinedButton(onClick = { textInput = true }) {
                        Text("Text")
                    }
                }
                if (textInput) {
                    TimeInput(state = timePickerState)
                } else {
                    TimePicker(state = timePickerState)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onTimeSelected(
                        LocalTime.of(selectedHour, selectedMinute)
                            .format(DateTimeFormatter.ofPattern("HH:mm")),
                    )
                },
            ) {
                Text("Set")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun DayBoundaryDialog(
    currentBoundary: LocalTime?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var enabled by remember(currentBoundary) { mutableStateOf(currentBoundary != null) }
    var boundaryTime by remember(currentBoundary) {
        mutableStateOf(currentBoundary?.toString() ?: "03:00")
    }
    var timeError by remember(currentBoundary) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Food day boundary") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Early-morning boundary",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                }
                TimeTextField(
                    value = boundaryTime,
                    onValueChange = { boundaryTime = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    isError = timeError != null,
                    label = "Boundary time",
                    supportingText = timeError,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!enabled) {
                        onSave(null)
                        return@Button
                    }

                    timeError = if (boundaryTime.parseLocalTimeOrNull() == null) {
                        "Use HH:mm, such as 03:00."
                    } else {
                        null
                    }

                    if (timeError == null) {
                        onSave(boundaryTime)
                    }
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun String.parseLocalTimeOrNull(): LocalTime? =
    TimeTextParser.parseOrNull(this)

private fun LocalDate.toEpochMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun DailyWeightDialog(
    dailyWeight: DailyWeightEntity?,
    onDismiss: () -> Unit,
    onSave: (stone: String, pounds: String, time: String) -> Unit,
) {
    val existing = remember(dailyWeight?.logDate, dailyWeight?.updatedAt) {
        dailyWeight?.weightKg?.toStonePounds()
    }
    var stone by remember(dailyWeight?.logDate, dailyWeight?.updatedAt) {
        mutableStateOf(existing?.stone?.toString().orEmpty())
    }
    var pounds by remember(dailyWeight?.logDate, dailyWeight?.updatedAt) {
        mutableStateOf(existing?.pounds?.formatPounds().orEmpty())
    }
    var time by remember(dailyWeight?.logDate, dailyWeight?.updatedAt) {
        mutableStateOf(dailyWeight?.measuredTime?.toString().orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (dailyWeight == null) "Add weight" else "Edit weight") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = stone,
                        onValueChange = { stone = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("Stone") },
                    )
                    OutlinedTextField(
                        value = pounds,
                        onValueChange = { pounds = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("Pounds") },
                    )
                }
                TimeTextField(
                    value = time,
                    onValueChange = { time = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Time",
                    supportingText = "Blank = now",
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(stone, pounds, time) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ResolvePendingDialog(
    entry: RawEntryEntity,
    draft: PendingEntryDraft?,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onResolve: (
        name: String,
        amount: String,
        unit: String,
        calories: String,
        time: String,
        notes: String,
        saveAsDefault: Boolean,
    ) -> Unit,
) {
    var name by remember(entry.id, draft?.name) { mutableStateOf(draft?.name ?: entry.displayFoodText()) }
    var amount by remember(entry.id, draft?.amount) { mutableStateOf(draft?.amount.orEmpty()) }
    var unit by remember(entry.id, draft?.unit) { mutableStateOf(draft?.unit.orEmpty()) }
    var time by remember(entry.id, draft?.time, entry.consumedTime) {
        mutableStateOf(draft?.time ?: entry.consumedTime?.toString().orEmpty())
    }
    var calories by remember(entry.id) { mutableStateOf("") }
    var notes by remember(entry.id) { mutableStateOf("") }
    var saveAsDefault by remember(entry.id) { mutableStateOf(false) }
    val canSaveAsDefault = calories.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review pending entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = entry.displayFoodText(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Item") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("Amount") },
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Unit") },
                    )
                }
                OutlinedTextField(
                    value = calories,
                    onValueChange = { calories = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("Calories") },
                )
                TimeTextField(
                    value = time,
                    onValueChange = { time = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Time",
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp),
                    minLines = 2,
                    maxLines = 3,
                    label = { Text("Notes") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = saveAsDefault && canSaveAsDefault,
                        onCheckedChange = { saveAsDefault = it },
                        enabled = canSaveAsDefault,
                    )
                    Text(
                        text = "Save as shortcut",
                        color = if (!canSaveAsDefault) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onResolve(name, amount, unit, calories, time, notes, saveAsDefault) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRemove) {
                    Text("Remove")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
private fun EditShortcutDialog(
    userDefault: UserDefaultEntity,
    onDismiss: () -> Unit,
    onSave: (name: String, calories: String, unit: String, notes: String) -> Unit,
) {
    var name by remember(userDefault.trigger) { mutableStateOf(userDefault.name) }
    var calories by remember(userDefault.trigger) { mutableStateOf(userDefault.calories.formatAmount()) }
    var unit by remember(userDefault.trigger) { mutableStateOf(userDefault.unit) }
    var notes by remember(userDefault.trigger) { mutableStateOf(userDefault.notes.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit shortcut") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = userDefault.trigger,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Item") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = calories,
                        onValueChange = { calories = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("Calories") },
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Unit") },
                    )
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp),
                    minLines = 2,
                    maxLines = 3,
                    label = { Text("Notes") },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, calories, unit, notes) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AddShortcutDialog(
    onDismiss: () -> Unit,
    onSave: (trigger: String, name: String, calories: String, unit: String, notes: String) -> Unit,
) {
    var trigger by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add shortcut") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Shortcut") },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Item") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = calories,
                        onValueChange = { calories = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("Calories") },
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Unit") },
                    )
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp),
                    minLines = 2,
                    maxLines = 3,
                    label = { Text("Notes") },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(trigger, name, calories, unit, notes) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ForgetShortcutDialog(
    userDefault: UserDefaultEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forget shortcut?") },
        text = {
            Text(
                text = "'${userDefault.trigger}' currently logs ${userDefault.name} at ${userDefault.calories.formatAmount()} kcal per ${userDefault.unit}.",
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Forget")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun EditFoodItemDialog(
    item: FoodItemEntity,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onSave: (name: String, amount: String, unit: String, calories: String, time: String, notes: String) -> Unit,
) {
    val caloriesPerUnit = remember(item.id) {
        val a = item.amount?.takeIf { it > 0.0 }
        val c = item.calories.takeIf { it > 0.0 }
        if (item.source == com.betterlucky.foodlog.data.entities.FoodItemSource.USER_DEFAULT && a != null && c != null) c / a else null
    }
    var name by remember(item.id) { mutableStateOf(item.name) }
    var amount by remember(item.id) { mutableStateOf(item.amount?.formatAmount().orEmpty()) }
    var unit by remember(item.id) { mutableStateOf(item.unit.orEmpty()) }
    var calories by remember(item.id) { mutableStateOf(item.calories.formatAmount()) }
    var time by remember(item.id) { mutableStateOf(item.consumedTime?.toString().orEmpty()) }
    var notes by remember(item.id) { mutableStateOf(item.notes.orEmpty()) }
    var caloriesEdited by remember(item.id) { mutableStateOf(false) }
    var caloriesRecalculated by remember(item.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit logged item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (!caloriesEdited && it.trim() != item.name) {
                            calories = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Item") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { newAmount ->
                            amount = newAmount
                            if (!caloriesEdited && caloriesPerUnit != null) {
                                val parsedAmount = newAmount.toDoubleOrNull()?.takeIf { it > 0.0 }
                                if (parsedAmount != null) {
                                    calories = String.format(java.util.Locale.US, "%.1f", caloriesPerUnit * parsedAmount).trimEnd('0').trimEnd('.')
                                    caloriesRecalculated = true
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("Amount") },
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Unit") },
                    )
                }
                OutlinedTextField(
                    value = calories,
                    onValueChange = {
                        calories = it
                        caloriesEdited = true
                        caloriesRecalculated = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("Calories") },
                    supportingText = {
                        if (caloriesRecalculated) {
                            Text("Recalculated from shortcut rate")
                        } else {
                            Text("Blank = use defaults")
                        }
                    },
                )
                TimeTextField(
                    value = time,
                    onValueChange = { time = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Time",
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp),
                    minLines = 2,
                    maxLines = 3,
                    label = { Text("Notes") },
                )
                errorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, amount, unit, calories, time, notes) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRemove) {
                    Text("Remove")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
private fun ResolveLoggedFoodEditDialog(
    resolution: LoggedFoodEditResolution,
    title: String = "Complete edited meal",
    contextText: String? = null,
    dismissLabel: String = "Cancel",
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (parts: List<LoggedFoodEditResolvedPartInput>) -> Unit,
) {
    var drafts by remember(resolution.rawText) {
        mutableStateOf(
            resolution.parts.map { part ->
                LoggedFoodEditResolvedPartInput(
                    inputText = part.inputText,
                    trigger = part.trigger,
                    resolvedByDefault = part.resolvedByDefault,
                    name = part.name,
                    amount = part.amount?.formatAmount().orEmpty(),
                    unit = part.unit,
                    calories = part.calories?.formatAmount().orEmpty(),
                    notes = part.notes,
                    saveAsDefault = false,
                )
            },
        )
    }
    val unresolvedIndices = drafts.withIndex()
        .filter { !it.value.resolvedByDefault }
        .map { it.index }
    var unresolvedPosition by remember(resolution.rawText) { mutableStateOf(0) }
    val currentIndex = unresolvedIndices.getOrNull(unresolvedPosition)
    val currentDraft = currentIndex?.let { drafts[it] }
    var name by remember(currentIndex) { mutableStateOf(currentDraft?.name.orEmpty()) }
    var amount by remember(currentIndex) { mutableStateOf(currentDraft?.amount.orEmpty()) }
    var unit by remember(currentIndex) { mutableStateOf(currentDraft?.unit.orEmpty()) }
    var calories by remember(currentIndex) { mutableStateOf(currentDraft?.calories.orEmpty()) }
    var notes by remember(currentIndex) { mutableStateOf(currentDraft?.notes.orEmpty()) }
    var saveAsDefault by remember(currentIndex) { mutableStateOf(currentDraft?.saveAsDefault ?: false) }
    var localError by remember(currentIndex) { mutableStateOf<String?>(null) }
    val canSaveAsDefault = calories.isNotBlank()
    val recognisedParts = drafts.filter { it.resolvedByDefault }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                contextText?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (recognisedParts.isNotEmpty()) {
                    Text(
                        text = "Recognised: " + recognisedParts.joinToString(", ") {
                            "${it.name} (${it.calories} kcal)"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (currentDraft != null) {
                    Text(
                        text = "Item ${currentIndex + 1} of ${drafts.size}: ${currentDraft.inputText}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Item") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { amount = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            label = { Text("Amount") },
                        )
                        OutlinedTextField(
                            value = unit,
                            onValueChange = { unit = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Unit") },
                        )
                    }
                    OutlinedTextField(
                        value = calories,
                        onValueChange = {
                            calories = it
                            if (it.isBlank()) saveAsDefault = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("Calories") },
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp),
                        minLines = 2,
                        maxLines = 3,
                        label = { Text("Notes") },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = saveAsDefault && canSaveAsDefault,
                            onCheckedChange = { saveAsDefault = it },
                            enabled = canSaveAsDefault,
                        )
                        Text(
                            text = "Save ${currentDraft.inputText} as shortcut",
                            color = if (!canSaveAsDefault) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                (localError ?: errorMessage)?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val current = currentIndex
                    if (current == null) {
                        onSave(drafts)
                        return@Button
                    }
                    when {
                        name.isBlank() -> localError = "Add an item name for ${drafts[current].inputText}."
                        amount.isNotBlank() && amount.toDoubleOrNull() == null ->
                            localError = "Amount must be a number for ${drafts[current].inputText}."
                        calories.toDoubleOrNull()?.takeIf { it > 0.0 } == null ->
                            localError = "Add calories for ${drafts[current].inputText}."
                        else -> {
                            localError = null
                            val updatedDrafts = drafts.toMutableList()
                            updatedDrafts[current] = drafts[current].copy(
                                name = name,
                                amount = amount,
                                unit = unit,
                                calories = calories,
                                notes = notes,
                                saveAsDefault = saveAsDefault && canSaveAsDefault,
                            )
                            drafts = updatedDrafts
                            if (unresolvedPosition < unresolvedIndices.lastIndex) {
                                unresolvedPosition += 1
                            } else {
                                onSave(updatedDrafts)
                            }
                        }
                    }
                },
            ) {
                Text(if (unresolvedPosition < unresolvedIndices.lastIndex) "Next" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel)
            }
        },
    )
}
