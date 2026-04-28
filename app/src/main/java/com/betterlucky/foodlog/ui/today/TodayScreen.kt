package com.betterlucky.foodlog.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.betterlucky.foodlog.data.entities.DailyWeightEntity
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import com.betterlucky.foodlog.data.entities.UserDefaultEntity
import com.betterlucky.foodlog.data.entities.DailyStatusEntity
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.floor

@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onShareCsv: (String, String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    var resolvingEntry by remember { mutableStateOf<RawEntryEntity?>(null) }
    var editingDefault by remember { mutableStateOf<UserDefaultEntity?>(null) }
    var forgettingDefault by remember { mutableStateOf<UserDefaultEntity?>(null) }
    var editingFoodItem by remember { mutableStateOf<FoodItemEntity?>(null) }
    var removingFoodItem by remember { mutableStateOf<FoodItemEntity?>(null) }
    var editingBoundary by remember { mutableStateOf(false) }
    var addingFoodItem by remember { mutableStateOf(false) }
    var editingWeight by remember { mutableStateOf(false) }

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
            Text(
                text = uiState.selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = viewModel::nextDay) {
                Text("Next")
            }
        }

        FoodDaySettingsRow(
            dayBoundaryTime = uiState.dayBoundaryTime,
            onEdit = { editingBoundary = true },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = uiState.inputText,
                onValueChange = viewModel::onInputChanged,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 96.dp, max = 180.dp),
                minLines = 2,
                maxLines = 5,
                label = { Text("Type food naturally") },
            )
            Button(
                onClick = {
                    focusManager.clearFocus()
                    viewModel.submit()
                },
            ) {
                Text("Log")
            }
        }

        uiState.message?.let {
            Text(text = it)
        }

        Text(
            text = "Total: ${uiState.totalCalories.toInt()} kcal",
            fontWeight = FontWeight.Bold,
        )

        DailyWeightRow(
            dailyWeight = uiState.dailyWeight,
            onEdit = { editingWeight = true },
        )

        OutlinedButton(onClick = { viewModel.exportLegacyCsv(onShareCsv) }) {
            Text("Export Health Monitor CSV")
        }

        ExportStatus(
            dailyStatus = uiState.dailyStatus,
            pendingCount = uiState.pendingEntries.size,
            foodItemCount = uiState.items.size,
            hasDailyWeight = uiState.dailyWeight != null,
        )

        DailyClosePrompt(
            dailyStatus = uiState.dailyStatus,
            pendingCount = uiState.pendingEntries.size,
            foodItemCount = uiState.items.size,
            hasDailyWeight = uiState.dailyWeight != null,
            onExportLegacy = { viewModel.exportLegacyCsv(onShareCsv) },
        )

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SectionTitle("Logged Items")
                    TextButton(onClick = { addingFoodItem = true }) {
                        Text("Add item")
                    }
                }
            }
            if (uiState.items.isEmpty()) {
                item {
                    EmptyState("No food logged for this day yet.")
                }
            } else {
                items(uiState.items) { item ->
                    FoodItemRow(
                        item = item,
                        onEdit = { editingFoodItem = item },
                        onRemove = { removingFoodItem = item },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                SectionTitle("Pending")
            }
            if (uiState.pendingEntries.isEmpty()) {
                item {
                    EmptyState("No pending entries for this day.")
                }
            } else {
                items(uiState.pendingEntries) { entry ->
                    PendingEntryRow(
                        entry = entry,
                        onResolve = { resolvingEntry = entry },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                SectionTitle("Shortcuts")
            }
            if (uiState.userDefaults.isEmpty()) {
                item {
                    EmptyState("No shortcuts saved yet.")
                }
            } else {
                items(uiState.userDefaults) { userDefault ->
                    ShortcutRow(
                        userDefault = userDefault,
                        onLog = { viewModel.logShortcut(userDefault.trigger) },
                        onEdit = { editingDefault = userDefault },
                        onForget = { forgettingDefault = userDefault },
                    )
                }
            }
        }
    }

    resolvingEntry?.let { entry ->
        ResolvePendingDialog(
            entry = entry,
            onDismiss = { resolvingEntry = null },
            onRemove = {
                viewModel.removePendingEntry(
                    id = entry.id,
                    onRemoved = { resolvingEntry = null },
                )
            },
            onResolve = { name, amount, unit, calories, notes, saveAsDefault ->
                viewModel.resolvePendingEntry(
                    rawEntryId = entry.id,
                    name = name,
                    amount = amount,
                    unit = unit,
                    calories = calories,
                    notes = notes,
                    saveAsDefault = saveAsDefault,
                    onResolved = { resolvingEntry = null },
                )
            },
        )
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
        EditFoodItemDialog(
            item = item,
            onDismiss = { editingFoodItem = null },
            onSave = { name, amount, unit, calories, time, notes ->
                viewModel.updateFoodItem(
                    id = item.id,
                    name = name,
                    amount = amount,
                    unit = unit,
                    calories = calories,
                    time = time,
                    notes = notes,
                    onUpdated = { editingFoodItem = null },
                )
            },
        )
    }

    removingFoodItem?.let { item ->
        RemoveFoodItemDialog(
            item = item,
            onDismiss = { removingFoodItem = null },
            onConfirm = {
                viewModel.removeFoodItem(item.id)
                removingFoodItem = null
            },
        )
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

    if (addingFoodItem) {
        AddFoodItemDialog(
            onDismiss = { addingFoodItem = false },
            onSave = { name, amount, unit, calories, time, notes, saveAsDefault ->
                viewModel.addFoodItemManually(
                    name = name,
                    amount = amount,
                    unit = unit,
                    calories = calories,
                    time = time,
                    notes = notes,
                    saveAsDefault = saveAsDefault,
                    onAdded = { addingFoodItem = false },
                )
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
private fun ExportStatus(
    dailyStatus: DailyStatusEntity?,
    pendingCount: Int,
    foodItemCount: Int,
    hasDailyWeight: Boolean,
) {
    val readiness = dailyReadiness(
        dailyStatus = dailyStatus,
        pendingCount = pendingCount,
        foodItemCount = foodItemCount,
        hasDailyWeight = hasDailyWeight,
    )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Daily status: ${readiness.label}",
            color = when (readiness) {
                DailyReadiness.ResolvePending -> MaterialTheme.colorScheme.error
                DailyReadiness.ReadyToExport -> MaterialTheme.colorScheme.primary
                DailyReadiness.NoFoodLogged,
                DailyReadiness.AlreadyExported -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Health Monitor: ${dailyStatus.exportText(dailyStatus?.legacyExportedAt, dailyStatus?.legacyExportFileName)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (pendingCount > 0) {
            Text(
                text = "$pendingCount pending ${if (pendingCount == 1) "entry" else "entries"} before export",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleLarge,
    )
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
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = item.name, fontWeight = FontWeight.SemiBold)
                Text(
                    text = listOfNotNull(
                        item.consumedTime?.toString() ?: "No time",
                        quantityText(item),
                    ).joinToString(" - "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = "${item.calories.toInt()} kcal",
                fontWeight = FontWeight.Bold,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onEdit) {
                Text("Edit")
            }
            TextButton(onClick = onRemove) {
                Text("Remove")
            }
        }
    }
}

private fun quantityText(item: FoodItemEntity): String? =
    when {
        item.amount != null && item.unit != null -> "${item.amount.formatAmount()} ${pluralizedUnit(item.unit, item.amount)}"
        item.amount != null -> item.amount.formatAmount()
        item.unit != null -> item.unit
        else -> null
    }

private fun Double.formatAmount(): String =
    if (rem(1.0) == 0.0) toInt().toString() else toString()

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

@Composable
private fun PendingEntryRow(
    entry: RawEntryEntity,
    onResolve: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(text = entry.rawText, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Needs review",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onResolve,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp),
            ) {
                Text("Resolve")
            }
        }
    }
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
                OutlinedTextField(
                    value = boundaryTime,
                    onValueChange = { boundaryTime = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    singleLine = true,
                    isError = timeError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    label = { Text("Boundary time") },
                    supportingText = timeError?.let { { Text(it) } },
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
    try {
        LocalTime.parse(trim())
    } catch (_: DateTimeParseException) {
        null
    }

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
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Time") },
                    placeholder = { Text("HH:mm") },
                    supportingText = { Text("Blank = now") },
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
private fun AddFoodItemDialog(
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        amount: String,
        unit: String,
        calories: String,
        time: String,
        notes: String,
        saveAsDefault: Boolean,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var saveAsDefault by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add logged item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        value = time,
                        onValueChange = { time = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Time") },
                        supportingText = { Text("Blank = now") },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = saveAsDefault,
                        onCheckedChange = { saveAsDefault = it },
                    )
                    Text(
                        text = "Save as shortcut",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, amount, unit, calories, time, notes, saveAsDefault) }) {
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
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onResolve: (
        name: String,
        amount: String,
        unit: String,
        calories: String,
        notes: String,
        saveAsDefault: Boolean,
    ) -> Unit,
) {
    var name by remember(entry.id) { mutableStateOf(entry.rawText) }
    var amount by remember(entry.id) { mutableStateOf("") }
    var unit by remember(entry.id) { mutableStateOf("") }
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
                    text = entry.rawText,
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
            Button(onClick = { onResolve(name, amount, unit, calories, notes, saveAsDefault) }) {
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
    onDismiss: () -> Unit,
    onSave: (name: String, amount: String, unit: String, calories: String, time: String, notes: String) -> Unit,
) {
    var name by remember(item.id) { mutableStateOf(item.name) }
    var amount by remember(item.id) { mutableStateOf(item.amount?.formatAmount().orEmpty()) }
    var unit by remember(item.id) { mutableStateOf(item.unit.orEmpty()) }
    var calories by remember(item.id) { mutableStateOf(item.calories.formatAmount()) }
    var time by remember(item.id) { mutableStateOf(item.consumedTime?.toString().orEmpty()) }
    var notes by remember(item.id) { mutableStateOf(item.notes.orEmpty()) }
    var caloriesEdited by remember(item.id) { mutableStateOf(false) }

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
                        caloriesEdited = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("Calories") },
                    supportingText = { Text("Blank = use defaults") },
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Time") },
                    placeholder = { Text("HH:mm") },
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
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, amount, unit, calories, time, notes) }) {
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
private fun RemoveFoodItemDialog(
    item: FoodItemEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove logged item?") },
        text = {
            Text(
                text = "${item.name} at ${item.calories.formatAmount()} kcal will be removed from this report.",
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
