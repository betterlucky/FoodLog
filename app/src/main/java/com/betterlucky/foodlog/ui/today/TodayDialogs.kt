package com.betterlucky.foodlog.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.betterlucky.foodlog.data.entities.DailyWeightEntity
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import com.betterlucky.foodlog.data.entities.UserDefaultEntity
import com.betterlucky.foodlog.domain.label.LabelInputMode
import com.betterlucky.foodlog.domain.label.LabelPortionResolver
import com.betterlucky.foodlog.domain.parser.TimeTextParser
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

private val DialogCardShape = RoundedCornerShape(8.dp)

@Composable
internal fun ShortcutPickerDialog(
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
                    Text(
                        text = "No matching shortcuts.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
        shape = DialogCardShape,
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
internal fun EditShortcutDialog(
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
internal fun AddShortcutDialog(
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
internal fun ForgetShortcutDialog(
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
internal fun EditFoodItemDialog(
    item: com.betterlucky.foodlog.data.entities.FoodItemEntity,
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
                        } else if (caloriesPerUnit != null) {
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
internal fun ResolveLoggedFoodEditDialog(
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

@Composable
internal fun DayBoundaryDialog(
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

@Composable
internal fun DailyWeightDialog(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogDatePickerDialog(
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
internal fun LabelImageSourceDialog(
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseImage: () -> Unit,
) {
    var showingHelp by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Use label")
                IconButton(onClick = { showingHelp = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Label scan help",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = "Use one clear photo of the nutrition label. You can check and edit the values before logging.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onTakePhoto,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Take photo")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onChooseImage,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Choose image")
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }
                }
            }
        },
        confirmButton = {},
    )
    if (showingHelp) {
        AlertDialog(
            onDismissRequest = { showingHelp = false },
            title = { Text("Label scan") },
            text = {
                Text("Take or choose one clear photo of the nutrition label. FoodLog reads the calories locally, then lets you check and edit everything before saving.")
            },
            confirmButton = {
                TextButton(onClick = { showingHelp = false }) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
internal fun LoggingWizardDialog(
    session: LoggingWizardSession,
    onDismiss: () -> Unit,
    onPartChanged: (Int, LoggingWizardPartDraft) -> Unit,
    onCurrentPartChanged: (Int) -> Unit,
    onTimeChanged: (String) -> Unit,
    onTimeConfirmed: () -> Unit,
    onInputModeChanged: (LabelInputMode) -> Unit,
    onRemove: (() -> Unit)?,
    onSave: () -> Unit,
) {
    val currentIndex = session.currentPartIndex.coerceIn(0, session.parts.lastIndex.coerceAtLeast(0))
    val currentPart = session.parts.getOrNull(currentIndex)
    var step by remember(session.originalRawText, currentIndex) {
        mutableStateOf(firstWizardStep(session, currentPart))
    }
    var showingAmountHelp by remember { mutableStateOf(false) }
    val completedCount = session.parts.count { it.isComplete }
    val pendingCount = session.parts.count { !it.isComplete }
    val title = when (session.source) {
        LoggingWizardSource.Label -> "Log from label"
        LoggingWizardSource.Pending -> "Review pending entry"
        LoggingWizardSource.Shortcut -> "Log shortcut"
        LoggingWizardSource.FreeText -> "Complete log entry"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title)
                if (step == LoggingWizardStep.Portion && session.source == LoggingWizardSource.Label) {
                    IconButton(onClick = { showingAmountHelp = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Amount entry help",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        text = {
            LoggingWizardBody(
                session = session,
                currentIndex = currentIndex,
                currentPart = currentPart,
                step = step,
                completedCount = completedCount,
                pendingCount = pendingCount,
                onPartChanged = onPartChanged,
                onTimeChanged = onTimeChanged,
                onInputModeChanged = onInputModeChanged,
            )
        },
        confirmButton = {
            LoggingWizardConfirmButton(
                session = session,
                currentIndex = currentIndex,
                currentPart = currentPart,
                step = step,
                onStepChanged = { step = it },
                onTimeConfirmed = onTimeConfirmed,
                onCurrentPartChanged = onCurrentPartChanged,
                onSave = onSave,
            )
        },
        dismissButton = {
            LoggingWizardDismissButtons(
                session = session,
                currentIndex = currentIndex,
                currentPart = currentPart,
                step = step,
                onRemove = onRemove,
                onPartChanged = onPartChanged,
                onCurrentPartChanged = onCurrentPartChanged,
                onStepChanged = { step = it },
                onDismiss = onDismiss,
            )
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

@Composable
private fun LoggingWizardBody(
    session: LoggingWizardSession,
    currentIndex: Int,
    currentPart: LoggingWizardPartDraft?,
    step: LoggingWizardStep,
    completedCount: Int,
    pendingCount: Int,
    onPartChanged: (Int, LoggingWizardPartDraft) -> Unit,
    onTimeChanged: (String) -> Unit,
    onInputModeChanged: (LabelInputMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WizardContextText(session = session, completedCount = completedCount, pendingCount = pendingCount)
        if (currentPart != null && step != LoggingWizardStep.Review) {
            Text(
                text = if (session.parts.size > 1) {
                    "Item ${currentIndex + 1} of ${session.parts.size}: ${currentPart.inputText}"
                } else {
                    currentPart.inputText
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        WizardStepContent(
            session = session,
            currentIndex = currentIndex,
            currentPart = currentPart,
            step = step,
            onPartChanged = onPartChanged,
            onTimeChanged = onTimeChanged,
            onInputModeChanged = onInputModeChanged,
        )
    }
}

@Composable
private fun WizardStepContent(
    session: LoggingWizardSession,
    currentIndex: Int,
    currentPart: LoggingWizardPartDraft?,
    step: LoggingWizardStep,
    onPartChanged: (Int, LoggingWizardPartDraft) -> Unit,
    onTimeChanged: (String) -> Unit,
    onInputModeChanged: (LabelInputMode) -> Unit,
) {
    when (step) {
        LoggingWizardStep.Product -> WizardProductStep(currentIndex, currentPart, onPartChanged)
        LoggingWizardStep.Portion -> {
            if (currentPart != null) {
                WizardPortionStep(session, currentIndex, currentPart, onPartChanged, onInputModeChanged)
            }
        }
        LoggingWizardStep.Calories -> WizardCaloriesStep(currentIndex, currentPart, onPartChanged)
        LoggingWizardStep.TimeNotes -> {
            if (currentPart != null) {
                WizardTimeNotesStep(session, currentIndex, currentPart, onPartChanged, onTimeChanged)
            }
        }
        LoggingWizardStep.Review -> WizardReviewStep(session, currentIndex)
    }
}

@Composable
private fun WizardProductStep(
    currentIndex: Int,
    currentPart: LoggingWizardPartDraft?,
    onPartChanged: (Int, LoggingWizardPartDraft) -> Unit,
) {
    OutlinedTextField(
        value = currentPart?.name.orEmpty(),
        onValueChange = { value ->
            currentPart?.let { onPartChanged(currentIndex, it.copy(name = value, deferred = false)) }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Item") },
    )
}

@Composable
private fun WizardPortionStep(
    session: LoggingWizardSession,
    currentIndex: Int,
    currentPart: LoggingWizardPartDraft,
    onPartChanged: (Int, LoggingWizardPartDraft) -> Unit,
    onInputModeChanged: (LabelInputMode) -> Unit,
) {
    if (session.source == LoggingWizardSource.Label && session.labelFacts != null) {
        LabelWizardPortionStep(
            facts = session.labelFacts,
            inputMode = session.labelInputMode,
            part = currentPart,
            onInputModeChanged = { mode ->
                onInputModeChanged(mode)
                val resolved = LabelPortionResolver.resolve(session.labelFacts, mode, currentPart.amount)
                onPartChanged(
                    currentIndex,
                    currentPart.copy(
                        unit = resolved.unit ?: currentPart.unit,
                        calories = resolved.calories?.formatLabelNumber() ?: currentPart.calories,
                        deferred = false,
                    ),
                )
            },
            onPartChanged = { onPartChanged(currentIndex, it) },
        )
    } else {
        AmountUnitFields(
            amount = currentPart.amount,
            unit = currentPart.unit,
            onAmountChange = { onPartChanged(currentIndex, currentPart.copy(amount = it, deferred = false)) },
            onUnitChange = { onPartChanged(currentIndex, currentPart.copy(unit = it, deferred = false)) },
        )
    }
}

@Composable
private fun WizardCaloriesStep(
    currentIndex: Int,
    currentPart: LoggingWizardPartDraft?,
    onPartChanged: (Int, LoggingWizardPartDraft) -> Unit,
) {
    OutlinedTextField(
        value = currentPart?.calories.orEmpty(),
        onValueChange = { value ->
            currentPart?.let { onPartChanged(currentIndex, it.copy(calories = value, deferred = false)) }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        label = { Text("Calories") },
    )
}

@Composable
private fun WizardTimeNotesStep(
    session: LoggingWizardSession,
    currentIndex: Int,
    currentPart: LoggingWizardPartDraft,
    onPartChanged: (Int, LoggingWizardPartDraft) -> Unit,
    onTimeChanged: (String) -> Unit,
) {
    Text(
        text = "When did you have this?",
        style = MaterialTheme.typography.bodyMedium,
    )
    TimeTextField(
        value = session.timeText,
        onValueChange = onTimeChanged,
        modifier = Modifier.fillMaxWidth(),
        label = "Time",
    )
    OutlinedTextField(
        value = currentPart.notes,
        onValueChange = { onPartChanged(currentIndex, currentPart.copy(notes = it)) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        minLines = 2,
        maxLines = 3,
        label = { Text("Notes") },
    )
    if (currentPart.resolvedByDefault) {
        ShortcutToggle(
            checked = true,
            enabled = false,
            label = "Shortcut already saved",
            onCheckedChange = {},
        )
    } else {
        ShortcutToggle(
            checked = currentPart.saveAsShortcut,
            enabled = currentPart.hasPositiveCalories,
            label = "Save as shortcut",
            onCheckedChange = { onPartChanged(currentIndex, currentPart.copy(saveAsShortcut = it)) },
        )
    }
}

@Composable
private fun WizardReviewStep(
    session: LoggingWizardSession,
    currentIndex: Int,
) {
    Text("Review log entry", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    session.parts.forEachIndexed { index, part ->
        val status = when {
            part.isComplete -> "${part.name} - ${part.calories} kcal"
            else -> "${part.inputText} - pending"
        }
        Text(
            text = status,
            color = if (index == currentIndex) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LoggingWizardConfirmButton(
    session: LoggingWizardSession,
    currentIndex: Int,
    currentPart: LoggingWizardPartDraft?,
    step: LoggingWizardStep,
    onStepChanged: (LoggingWizardStep) -> Unit,
    onTimeConfirmed: () -> Unit,
    onCurrentPartChanged: (Int) -> Unit,
    onSave: () -> Unit,
) {
    Button(
        enabled = canAdvanceWizardStep(session, currentPart, step),
        onClick = {
            if (step == LoggingWizardStep.TimeNotes) {
                onTimeConfirmed()
            }
            val nextStep = nextWizardStep(session, currentIndex, step)
            val nextPart = session.parts.indexOfFirstIndexed { index, part ->
                index > currentIndex && part.needsInput
            }
            when {
                step == LoggingWizardStep.TimeNotes && nextPart < 0 && session.parts.all { it.isComplete } -> onSave()
                nextStep != null -> onStepChanged(nextStep)
                nextPart >= 0 -> onCurrentPartChanged(nextPart)
                else -> onSave()
            }
        },
    ) {
        Text(wizardConfirmLabel(session, step, currentIndex))
    }
}

private fun wizardConfirmLabel(
    session: LoggingWizardSession,
    step: LoggingWizardStep,
    currentIndex: Int,
): String {
    val noMoreInput = session.parts.drop(currentIndex + 1).none { it.needsInput }
    val willSave = (step == LoggingWizardStep.Review && noMoreInput) ||
        (step == LoggingWizardStep.TimeNotes && noMoreInput && session.parts.all { it.isComplete })
    return if (willSave) "Save" else "Next"
}

@Composable
private fun LoggingWizardDismissButtons(
    session: LoggingWizardSession,
    currentIndex: Int,
    currentPart: LoggingWizardPartDraft?,
    step: LoggingWizardStep,
    onRemove: (() -> Unit)?,
    onPartChanged: (Int, LoggingWizardPartDraft) -> Unit,
    onCurrentPartChanged: (Int) -> Unit,
    onStepChanged: (LoggingWizardStep) -> Unit,
    onDismiss: () -> Unit,
) {
    Row {
        onRemove?.let {
            TextButton(onClick = it) {
                Text("Remove")
            }
        }
        currentPart?.takeIf { session.source != LoggingWizardSource.Label && !it.isComplete }?.let { part ->
            TextButton(
                onClick = {
                    onPartChanged(currentIndex, part.copy(deferred = true))
                    val nextPart = session.parts.indexOfFirstIndexed { index, candidate ->
                        index > currentIndex && candidate.needsInput
                    }
                    if (nextPart >= 0) {
                        onCurrentPartChanged(nextPart)
                    } else {
                        onStepChanged(LoggingWizardStep.Review)
                    }
                },
            ) {
                Text("Keep pending")
            }
        }
        TextButton(
            onClick = {
                val previous = previousWizardStep(step)
                when {
                    previous != null -> onStepChanged(previous)
                    currentIndex > 0 -> onCurrentPartChanged(currentIndex - 1)
                    else -> onDismiss()
                }
            },
        ) {
            Text(if (step == LoggingWizardStep.Product && currentIndex == 0) "Cancel" else "Back")
        }
    }
}

@Composable
internal fun QuantityPickerDialog(
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

// --- Shared helper composables and functions ---

@Composable
private fun WizardContextText(session: LoggingWizardSession, completedCount: Int, pendingCount: Int) {
    val label = when (session.source) {
        LoggingWizardSource.Label -> session.labelFacts?.let { facts ->
            buildString {
                facts.kcalPer100g?.let { append("${it.toInt()} kcal/100g") }
                facts.kcalPerServing?.let {
                    if (isNotEmpty()) append(" · ")
                    append("${it.toInt()} kcal/${facts.servingUnit ?: "serving"}")
                }
                facts.servingSizeGrams?.let { append(" (${it.toInt()}g)") }
            }.takeIf { it.isNotBlank() }?.let { "From label: $it" }
        }
        LoggingWizardSource.Pending -> "Pending: ${session.originalRawText}"
        LoggingWizardSource.Shortcut -> "Shortcut: ${session.originalRawText}"
        LoggingWizardSource.FreeText -> "From text: ${session.originalRawText}"
    }
    label?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (session.parts.size > 1) {
        Text(
            text = "$completedCount complete · $pendingCount pending",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AmountUnitFields(
    amount: String,
    unit: String,
    onAmountChange: (String) -> Unit,
    onUnitChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = amount,
            onValueChange = onAmountChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            label = { Text("Amount") },
        )
        OutlinedTextField(
            value = unit,
            onValueChange = onUnitChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("Unit") },
        )
    }
}

@Composable
private fun LabelWizardPortionStep(
    facts: com.betterlucky.foodlog.domain.label.LabelNutritionFacts,
    inputMode: LabelInputMode,
    part: LoggingWizardPartDraft,
    onInputModeChanged: (LabelInputMode) -> Unit,
    onPartChanged: (LoggingWizardPartDraft) -> Unit,
) {
    val itemUnit = LabelPortionResolver.itemUnit(facts)
    val resolvedPortion = LabelPortionResolver.resolve(facts, inputMode, part.amount)
    val sliderAmount = part.amount.toFloatOrNull()?.coerceIn(0.1f, 1f) ?: 0.1f
    val sliderPortionLabel = resolvedPortion.amount
        ?.let { LabelPortionResolver.displayAmount(it, itemUnit).replaceFirstChar { char -> char.titlecase() } }
        ?: "Choose amount"
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
                onInputModeChanged(if (checked) LabelInputMode.MEASURE else LabelInputMode.ITEMS)
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
        Text(
            text = sliderPortionLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        PortionAmountSlider(
            value = sliderAmount,
            onValueChange = { rawValue ->
                val snapped = rawValue.snapToTenth()
                val resolved = LabelPortionResolver.resolve(facts, inputMode, snapped.toDouble().formatLabelNumber())
                onPartChanged(
                    part.copy(
                        amount = snapped.toDouble().formatLabelNumber(),
                        unit = resolved.unit ?: itemUnit,
                        calories = resolved.calories?.formatLabelNumber() ?: part.calories,
                        deferred = false,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        AmountUnitFields(
            amount = part.amount,
            unit = itemUnit,
            onAmountChange = { value ->
                val resolved = LabelPortionResolver.resolve(facts, inputMode, value)
                onPartChanged(
                    part.copy(
                        amount = value,
                        unit = resolved.unit ?: itemUnit,
                        calories = resolved.calories?.formatLabelNumber() ?: part.calories,
                        deferred = false,
                    ),
                )
            },
            onUnitChange = {},
        )
    } else {
        OutlinedTextField(
            value = part.amount,
            onValueChange = { value ->
                val resolved = LabelPortionResolver.resolve(facts, inputMode, value)
                onPartChanged(
                    part.copy(
                        amount = value,
                        unit = resolved.unit ?: part.unit,
                        calories = resolved.calories?.formatLabelNumber() ?: part.calories,
                        deferred = false,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Amount (g/ml)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
    val portionQuantity = resolvedPortion.amount?.let { amount ->
        if (inputMode == LabelInputMode.ITEMS) {
            LabelPortionResolver.displayAmount(amount, resolvedPortion.unit.orEmpty())
        } else {
            "${amount.formatLabelNumber()} ${resolvedPortion.unit.orEmpty()}"
        }
    }.orEmpty()
    val portionWeight = resolvedPortion.grams?.let { "${it.formatLabelNumber()}g" }.orEmpty()
    val summary = listOf(portionQuantity.takeIf { it.isNotBlank() }, portionWeight.takeIf { it.isNotBlank() })
        .filterNotNull()
        .joinToString(" · ")
    if (summary.isNotBlank()) {
        Text(
            text = summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ShortcutToggle(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Text(
            text = label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun TimeTextField(
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
private fun PortionAmountSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = Color.White

    fun amountAtOffset(offsetX: Float, width: Float, thumbRadius: Float): Float {
        val start = thumbRadius
        val end = width - thumbRadius
        val fraction = ((offsetX - start) / (end - start)).coerceIn(0f, 1f)
        return (0.1f + (fraction * 0.9f)).snapToTenth()
    }

    Canvas(
        modifier = modifier
            .height(48.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onValueChange(amountAtOffset(offset.x, size.width.toFloat(), 11.dp.toPx()))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onValueChange(amountAtOffset(change.position.x, size.width.toFloat(), 11.dp.toPx()))
                }
            },
    ) {
        val thumbRadius = 11.dp.toPx()
        val trackHeight = 10.dp.toPx()
        val trackStart = thumbRadius
        val trackEnd = size.width - thumbRadius
        val trackWidth = trackEnd - trackStart
        val trackTop = (size.height - trackHeight) / 2f
        val fraction = ((value.snapToTenth() - 0.1f) / 0.9f).coerceIn(0f, 1f)
        val thumbCenter = Offset(trackStart + (trackWidth * fraction), size.height / 2f)
        val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)

        drawRoundRect(
            color = inactiveColor,
            topLeft = Offset(trackStart, trackTop),
            size = Size(trackWidth, trackHeight),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = activeColor,
            topLeft = Offset(trackStart, trackTop),
            size = Size(thumbCenter.x - trackStart, trackHeight),
            cornerRadius = corner,
        )
        drawCircle(
            color = activeColor,
            radius = thumbRadius,
            center = thumbCenter,
        )
    }
}

// --- Wizard step logic ---

private enum class LoggingWizardStep {
    Product,
    Portion,
    Calories,
    TimeNotes,
    Review,
}

private fun firstWizardStep(
    session: LoggingWizardSession,
    part: LoggingWizardPartDraft?,
): LoggingWizardStep =
    when {
        part == null -> LoggingWizardStep.Review
        part.name.isBlank() -> LoggingWizardStep.Product
        session.source == LoggingWizardSource.Label && session.labelFacts != null &&
            !LabelPortionResolver.resolve(session.labelFacts, session.labelInputMode, part.amount).isValidAmount -> LoggingWizardStep.Portion
        !part.hasPositiveCalories -> LoggingWizardStep.Calories
        !session.timeConfirmed -> LoggingWizardStep.TimeNotes
        else -> LoggingWizardStep.Review
    }

private fun canAdvanceWizardStep(
    session: LoggingWizardSession,
    part: LoggingWizardPartDraft?,
    step: LoggingWizardStep,
): Boolean =
    when (step) {
        LoggingWizardStep.Product -> part?.name?.isNotBlank() == true
        LoggingWizardStep.Portion -> {
            if (session.source == LoggingWizardSource.Label && session.labelFacts != null && part != null) {
                LabelPortionResolver.resolve(session.labelFacts, session.labelInputMode, part.amount).isValidAmount
            } else {
                true
            }
        }
        LoggingWizardStep.Calories -> part?.hasPositiveCalories == true
        LoggingWizardStep.TimeNotes -> session.timeConfirmed || TimeTextParser.parseOrNull(session.timeText) != null
        LoggingWizardStep.Review -> true
    }

private fun nextWizardStep(
    session: LoggingWizardSession,
    currentIndex: Int,
    step: LoggingWizardStep,
): LoggingWizardStep? {
    val part = session.parts.getOrNull(currentIndex)
    val rawNext = when (step) {
        LoggingWizardStep.Product -> if (session.source == LoggingWizardSource.Label) LoggingWizardStep.Portion else nextAfterCalories(session, part)
        LoggingWizardStep.Portion -> LoggingWizardStep.Calories
        LoggingWizardStep.Calories -> if (session.timeConfirmed) LoggingWizardStep.Review else LoggingWizardStep.TimeNotes
        LoggingWizardStep.TimeNotes -> LoggingWizardStep.Review
        LoggingWizardStep.Review -> return null
    }
    return if (rawNext == LoggingWizardStep.Calories && part?.hasPositiveCalories == true) {
        if (session.timeConfirmed) LoggingWizardStep.Review else LoggingWizardStep.TimeNotes
    } else {
        rawNext
    }
}

private fun nextAfterCalories(
    session: LoggingWizardSession,
    part: LoggingWizardPartDraft?,
): LoggingWizardStep =
    if (part?.hasPositiveCalories == true) {
        if (session.timeConfirmed) LoggingWizardStep.Review else LoggingWizardStep.TimeNotes
    } else {
        LoggingWizardStep.Calories
    }

private fun previousWizardStep(step: LoggingWizardStep): LoggingWizardStep? =
    when (step) {
        LoggingWizardStep.Product -> null
        LoggingWizardStep.Portion -> LoggingWizardStep.Product
        LoggingWizardStep.Calories -> LoggingWizardStep.Portion
        LoggingWizardStep.TimeNotes -> LoggingWizardStep.Calories
        LoggingWizardStep.Review -> LoggingWizardStep.TimeNotes
    }

private inline fun <T> List<T>.indexOfFirstIndexed(predicate: (Int, T) -> Boolean): Int {
    forEachIndexed { index, item ->
        if (predicate(index, item)) return index
    }
    return -1
}

// --- Utility functions shared across dialogs ---

internal fun Double.formatAmount(): String =
    if (rem(1.0) == 0.0) toInt().toString() else toString()

private fun Double.formatLabelNumber(): String =
    if (rem(1.0) == 0.0) {
        toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", this).trimEnd('0').trimEnd('.')
    }

private fun Float.snapToTenth(): Float =
    (kotlin.math.round(this * 10f) / 10f).coerceIn(0f, 1f)

internal fun DailyWeightEntity.displayWeight(): String {
    val stonePounds = weightKg.toStonePounds()
    return "${stonePounds.stone} st ${stonePounds.pounds.formatPounds()} lb (${weightKg.formatWeightKg()} kg)"
}

internal fun Double.toStonePounds(): StonePounds {
    val totalPounds = this / 0.45359237
    val stone = kotlin.math.floor(totalPounds / 14.0).toInt()
    val pounds = totalPounds - (stone * 14.0)
    return StonePounds(stone = stone, pounds = pounds)
}

internal data class StonePounds(
    val stone: Int,
    val pounds: Double,
)

private fun Double.formatWeightKg(): String =
    String.format(java.util.Locale.US, "%.1f", this)

internal fun Double.formatPounds(): String =
    String.format(java.util.Locale.US, "%.1f", this).removeSuffix(".0")

private fun String.parseLocalTimeOrNull(): LocalTime? =
    TimeTextParser.parseOrNull(this)

private fun LocalDate.toEpochMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
