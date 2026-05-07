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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.betterlucky.foodlog.data.entities.DailyWeightEntity
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import com.betterlucky.foodlog.data.entities.ShortcutPortionMode
import com.betterlucky.foodlog.data.entities.UserDefaultEntity
import com.betterlucky.foodlog.data.repository.FoodLogRepository
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
            userDefault.lookupKey.contains(normalizedQuery, ignoreCase = true) ||
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
            Text(text = userDefault.name, fontWeight = FontWeight.SemiBold)
            Text(
                text = shortcutSummaryText(userDefault),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
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

private fun shortcutSummaryText(userDefault: UserDefaultEntity): String {
    val base = "${userDefault.calories.formatAmount()} kcal per ${userDefault.unit}"
    val usual = userDefault.defaultAmount?.let { amount ->
        when (userDefault.portionMode) {
            ShortcutPortionMode.ITEM,
            ShortcutPortionMode.MEASURE ->
                "usual ${amount.formatAmount()} ${pluralizedShortcutUnit(userDefault.unit, amount)}"
            ShortcutPortionMode.PLAIN ->
                null
        }
    }
    val stale = userDefault.nutritionBasisName
        ?.takeIf { it != userDefault.name }
        ?.let { "label info from $it" }
    return listOf(base, usual, stale).filterNotNull().joinToString(" · ")
}

private fun pluralizedShortcutUnit(
    unit: String,
    amount: Double,
): String =
    if (amount == 1.0 || unit.endsWith("s")) unit else "${unit}s"

private fun shortcutFieldsDiffer(
    candidate: FoodLogRepository.ShortcutUpdateCandidate,
    name: String,
    amount: String,
    unit: String,
    calories: String,
    notes: String,
): Boolean {
    val parsedAmount = amount.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
    val parsedCalories = calories.trim().toDoubleOrNull()
    return name.trim() != candidate.name ||
        !nullableDoubleNearly(parsedAmount, candidate.amount) ||
        unit.trim().ifBlank { null } != candidate.unit ||
        !nullableDoubleNearly(parsedCalories, candidate.calories) ||
        notes.trim().ifBlank { null } != candidate.notes
}

private fun nullableDoubleNearly(
    left: Double?,
    right: Double?,
): Boolean =
    when {
        left == null && right == null -> true
        left == null || right == null -> false
        else -> (left - right).absoluteValue < 0.001
    }

@Composable
private fun ShortcutModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun EditShortcutDialog(
    userDefault: UserDefaultEntity,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (
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
    ) -> Unit,
) {
    var name by remember(userDefault.lookupKey) { mutableStateOf(userDefault.name) }
    var calories by remember(userDefault.lookupKey) { mutableStateOf(userDefault.calories.formatAmount()) }
    var unit by remember(userDefault.lookupKey) { mutableStateOf(userDefault.unit) }
    var notes by remember(userDefault.lookupKey) { mutableStateOf(userDefault.notes.orEmpty()) }
    var defaultAmount by remember(userDefault.lookupKey) { mutableStateOf(userDefault.defaultAmount?.formatAmount().orEmpty()) }
    var portionMode by remember(userDefault.lookupKey) { mutableStateOf(userDefault.portionMode) }
    var itemUnit by remember(userDefault.lookupKey) { mutableStateOf(userDefault.itemUnit.orEmpty()) }
    var itemSizeAmount by remember(userDefault.lookupKey) { mutableStateOf(userDefault.itemSizeAmount?.formatAmount().orEmpty()) }
    var itemSizeUnit by remember(userDefault.lookupKey) { mutableStateOf(userDefault.itemSizeUnit.orEmpty()) }
    var kcalPer100g by remember(userDefault.lookupKey) { mutableStateOf(userDefault.kcalPer100g?.formatAmount().orEmpty()) }
    var kcalPer100ml by remember(userDefault.lookupKey) { mutableStateOf(userDefault.kcalPer100ml?.formatAmount().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit shortcut") },
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
                        label = { Text("Unit (optional)") },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = defaultAmount,
                        onValueChange = { defaultAmount = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("Usual amount") },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShortcutModeButton(
                        label = "Plain",
                        selected = portionMode == ShortcutPortionMode.PLAIN,
                        onClick = { portionMode = ShortcutPortionMode.PLAIN },
                        modifier = Modifier.weight(1f),
                    )
                    ShortcutModeButton(
                        label = "Item",
                        selected = portionMode == ShortcutPortionMode.ITEM,
                        onClick = { portionMode = ShortcutPortionMode.ITEM },
                        modifier = Modifier.weight(1f),
                    )
                    ShortcutModeButton(
                        label = "g/ml",
                        selected = portionMode == ShortcutPortionMode.MEASURE,
                        onClick = { portionMode = ShortcutPortionMode.MEASURE },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (portionMode != ShortcutPortionMode.PLAIN) {
                    OutlinedTextField(
                        value = itemUnit,
                        onValueChange = { itemUnit = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Item unit") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = itemSizeAmount,
                            onValueChange = { itemSizeAmount = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            label = { Text("Item size") },
                        )
                        OutlinedTextField(
                            value = itemSizeUnit,
                            onValueChange = { itemSizeUnit = it.lowercase() },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("g/ml") },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = kcalPer100g,
                            onValueChange = { kcalPer100g = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            label = { Text("kcal/100g") },
                        )
                        OutlinedTextField(
                            value = kcalPer100ml,
                            onValueChange = { kcalPer100ml = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            label = { Text("kcal/100ml") },
                        )
                    }
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
                DialogErrorText(errorMessage)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name,
                        calories,
                        unit,
                        notes,
                        defaultAmount,
                        portionMode,
                        itemUnit,
                        itemSizeAmount,
                        itemSizeUnit,
                        kcalPer100g,
                        kcalPer100ml,
                    )
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
internal fun AddShortcutDialog(
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, calories: String, unit: String, notes: String) -> Unit,
) {
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
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") },
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
                        label = { Text("Unit (optional)") },
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
                DialogErrorText(errorMessage)
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
                text = "'${userDefault.name}' logs at ${userDefault.calories.formatAmount()} kcal per ${userDefault.unit}.",
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
    shortcutUpdateCandidate: FoodLogRepository.ShortcutUpdateCandidate?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onSave: (name: String, amount: String, unit: String, calories: String, time: String, notes: String, updateShortcut: Boolean) -> Unit,
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
    var updateShortcut by remember(item.id, shortcutUpdateCandidate?.lookupKey) { mutableStateOf(false) }
    val shortcutChanged = shortcutUpdateCandidate?.let {
        shortcutFieldsDiffer(
            candidate = it,
            name = name,
            amount = amount,
            unit = unit,
            calories = calories,
            notes = notes,
        )
    } == true

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
                if (shortcutChanged) {
                    val candidate = checkNotNull(shortcutUpdateCandidate)
                    ShortcutToggle(
                        checked = updateShortcut,
                        enabled = true,
                        label = "Update shortcut '${candidate.name}'",
                        onCheckedChange = { updateShortcut = it },
                    )
                }
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
            Button(onClick = { onSave(name, amount, unit, calories, time, notes, updateShortcut && shortcutChanged) }) {
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
                    lookupKey = part.lookupKey,
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
    errorMessage: String?,
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
                DialogErrorText(errorMessage)
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
private fun DialogErrorText(errorMessage: String?) {
    errorMessage?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
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
    onPortionModeChanged: (Int, LabelInputMode, LoggingWizardPartDraft) -> Unit,
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
                if (
                    step == LoggingWizardStep.Portion &&
                    (session.source == LoggingWizardSource.Label || session.shortcutPortionMode != ShortcutPortionMode.PLAIN)
                ) {
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
                onPortionModeChanged = onPortionModeChanged,
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
                Text(portionHelpText(session))
            },
            confirmButton = {
                TextButton(onClick = { showingAmountHelp = false }) {
                    Text("OK")
                }
            },
        )
    }
}

private fun portionHelpText(session: LoggingWizardSession): String =
    when (session.labelInputMode) {
        LabelInputMode.ITEMS ->
            "Use items for things you count, such as a pot, can, bar, bottle, or bag. The slider starts at one item and can be adjusted; this is your portion, not a suggested serving."
        LabelInputMode.MEASURE ->
            "Use g/ml when you want to log by weight or volume. If the label gives a package, item, or serving size, the slider uses that as an upper bound only; it is not a recommendation."
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
    onPortionModeChanged: (Int, LabelInputMode, LoggingWizardPartDraft) -> Unit,
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
            onPortionModeChanged = onPortionModeChanged,
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
    onPortionModeChanged: (Int, LabelInputMode, LoggingWizardPartDraft) -> Unit,
) {
    when (step) {
        LoggingWizardStep.Product -> WizardProductStep(currentIndex, currentPart, onPartChanged)
        LoggingWizardStep.Portion -> {
            if (currentPart != null) {
                WizardPortionStep(
                    session = session,
                    currentIndex = currentIndex,
                    currentPart = currentPart,
                    onPartChanged = onPartChanged,
                    onInputModeChanged = onInputModeChanged,
                    onPortionModeChanged = onPortionModeChanged,
                )
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
    onPortionModeChanged: (Int, LabelInputMode, LoggingWizardPartDraft) -> Unit,
) {
    if (
        session.labelFacts != null &&
        (session.source == LoggingWizardSource.Label || session.shortcutPortionMode != ShortcutPortionMode.PLAIN)
    ) {
        LabelWizardPortionStep(
            facts = session.labelFacts,
            inputMode = session.labelInputMode,
            part = currentPart,
            onInputModeChanged = { mode ->
                val amountText = defaultAmountForMode(session.labelFacts, mode)
                val resolved = LabelPortionResolver.resolve(session.labelFacts, mode, amountText)
                onPortionModeChanged(
                    currentIndex,
                    mode,
                    currentPart.copy(
                        amount = amountText,
                        unit = resolved.unit ?: currentPart.unit,
                        calories = resolved.calories?.formatLabelNumber() ?: currentPart.calories,
                        deferred = false,
                    ),
                )
            },
            onPartChanged = { onPartChanged(currentIndex, it) },
        )
    } else {
        val caloriesPerUnit = if (session.source == LoggingWizardSource.Shortcut) {
            val amount = currentPart.amount.toDoubleOrNull()?.takeIf { it > 0.0 }
            val calories = currentPart.calories.toDoubleOrNull()?.takeIf { it > 0.0 }
            if (amount != null && calories != null) calories / amount else null
        } else {
            null
        }
        AmountUnitFields(
            amount = currentPart.amount,
            unit = currentPart.unit,
            onAmountChange = { value ->
                val updatedCalories = caloriesPerUnit
                    ?.let { perUnit ->
                        value.toDoubleOrNull()
                            ?.takeIf { amount -> amount > 0.0 }
                            ?.let { amount -> perUnit * amount }
                    }
                    ?.formatLabelNumber()
                    ?: currentPart.calories
                onPartChanged(
                    currentIndex,
                    currentPart.copy(
                        amount = value,
                        calories = updatedCalories,
                        deferred = false,
                    ),
                )
            },
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
        if (
            session.source == LoggingWizardSource.Label &&
            currentPart.saveAsShortcut &&
            session.labelInputMode == LabelInputMode.ITEMS &&
            currentPart.shortcutItemSizeAmount.isBlank()
        ) {
            LabelShortcutItemSizeFields(
                part = currentPart,
                onPartChanged = { onPartChanged(currentIndex, it) },
            )
        }
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
    val measureSliderRange = measureSliderRange(facts)
    val sliderAmount = part.amount.toFloatOrNull()?.coerceIn(0.1f, 1f) ?: 1f
    val sliderPortionLabel = resolvedPortion.amount
        ?.let { LabelPortionResolver.displayAmount(it, itemUnit).replaceFirstChar { char -> char.titlecase() } }
        ?: "Choose amount"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Items",
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            textAlign = TextAlign.End,
            fontWeight = if (inputMode == LabelInputMode.ITEMS) FontWeight.SemiBold else null,
        )
        PortionModeToggle(
            inputMode = inputMode,
            onInputModeChanged = onInputModeChanged,
        )
        Text(
            text = "g/ml",
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            textAlign = TextAlign.Start,
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
            minValue = 0.1f,
            maxValue = 1f,
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
        measureSliderRange?.let { range ->
            val value = part.amount.measureAmountValue()
                ?.toFloat()
                ?.coerceIn(1f, range.maxAmount.toFloat())
                ?: range.maxAmount.toFloat()
            Text(
                text = "Up to ${range.maxAmount.formatLabelNumber()}${range.unit} from label",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            PortionAmountSlider(
                value = value,
                onValueChange = { rawValue ->
                    val snapped = rawValue.roundMeasureAmount()
                    val amountText = "${snapped.formatLabelNumber()}${range.unit}"
                    val resolved = LabelPortionResolver.resolve(facts, inputMode, amountText)
                    onPartChanged(
                        part.copy(
                            amount = amountText,
                            unit = resolved.unit ?: range.unit,
                            calories = resolved.calories?.formatLabelNumber() ?: part.calories,
                            deferred = false,
                        ),
                    )
                },
                minValue = 1f,
                maxValue = range.maxAmount.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = part.amount.measureAmountInputText(),
            onValueChange = { value ->
                val amountText = measureAmountText(value, measureSliderRange?.unit ?: part.unit)
                val resolved = LabelPortionResolver.resolve(facts, inputMode, amountText)
                onPartChanged(
                    part.copy(
                        amount = amountText,
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
    val portionQuantity = when (inputMode) {
        LabelInputMode.ITEMS -> resolvedPortion.amount
            ?.let { LabelPortionResolver.displayAmount(it, resolvedPortion.unit.orEmpty()) }
            .orEmpty()
        LabelInputMode.MEASURE -> equivalentItemText(facts, resolvedPortion.amount, resolvedPortion.unit)
            .orEmpty()
    }
    val portionWeight = resolvedPortion.grams?.let { "${it.formatLabelNumber()}g" }.orEmpty()
    val portionVolume = resolvedPortion.milliliters?.let { "${it.formatLabelNumber()}ml" }.orEmpty()
    val summary = listOf(
        portionQuantity.takeIf { it.isNotBlank() },
        portionWeight.takeIf { inputMode == LabelInputMode.ITEMS && it.isNotBlank() },
        portionVolume.takeIf { inputMode == LabelInputMode.ITEMS && it.isNotBlank() },
    )
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
private fun PortionModeToggle(
    inputMode: LabelInputMode,
    onInputModeChanged: (LabelInputMode) -> Unit,
) {
    val selectedRight = inputMode == LabelInputMode.MEASURE
    val borderColor = MaterialTheme.colorScheme.primary
    val thumbColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surface

    Canvas(
        modifier = Modifier
            .width(68.dp)
            .height(44.dp)
            .semantics {
                role = Role.Switch
                contentDescription = "Portion input mode"
                stateDescription = if (selectedRight) "Measure" else "Items"
            }
            .clickable(
                role = Role.Switch,
                onClickLabel = "Switch portion input mode",
            ) {
                onInputModeChanged(
                    if (selectedRight) LabelInputMode.ITEMS else LabelInputMode.MEASURE,
                )
            },
    ) {
        val strokeWidth = 3.dp.toPx()
        val trackHeight = 34.dp.toPx()
        val trackWidth = size.width
        val trackTop = (size.height - trackHeight) / 2f
        val radius = trackHeight / 2f
        val thumbRadius = 13.dp.toPx()
        val thumbX = if (selectedRight) {
            trackWidth - radius
        } else {
            radius
        }

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, trackTop),
            size = Size(trackWidth, trackHeight),
            cornerRadius = CornerRadius(radius, radius),
        )
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(strokeWidth / 2f, trackTop + strokeWidth / 2f),
            size = Size(trackWidth - strokeWidth, trackHeight - strokeWidth),
            cornerRadius = CornerRadius(radius, radius),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth),
        )
        drawCircle(
            color = thumbColor,
            radius = thumbRadius,
            center = Offset(thumbX, size.height / 2f),
        )
    }
}

private data class MeasureSliderRange(
    val maxAmount: Double,
    val unit: String,
)

private fun measureSliderRange(
    facts: com.betterlucky.foodlog.domain.label.LabelNutritionFacts,
): MeasureSliderRange? {
    val servingAmount = facts.servingAmount?.takeIf { it > 0.0 }
    if (servingAmount != null) {
        facts.servingSizeGrams?.takeIf { it > 1.0 }?.let {
            return MeasureSliderRange(it / servingAmount, "g")
        }
    }
    facts.packageSizeGrams?.takeIf { it > 1.0 }?.let { return MeasureSliderRange(it, "g") }
    facts.packageSizeMilliliters?.takeIf { it > 1.0 }?.let { return MeasureSliderRange(it, "ml") }
    val itemCount = facts.packageItemCount?.takeIf { it > 0.0 }
    if (itemCount != null) {
        facts.packageSizeGrams?.takeIf { it > 1.0 }?.let { return MeasureSliderRange(it / itemCount, "g") }
        facts.packageSizeMilliliters?.takeIf { it > 1.0 }?.let { return MeasureSliderRange(it / itemCount, "ml") }
    }
    facts.servingSizeGrams?.takeIf { it > 1.0 }?.let { return MeasureSliderRange(it, "g") }
    return null
}

private fun equivalentItemText(
    facts: com.betterlucky.foodlog.domain.label.LabelNutritionFacts,
    amount: Double?,
    unit: String?,
): String? {
    val measuredAmount = amount?.takeIf { it > 0.0 } ?: return null
    val measuredUnit = unit?.takeIf { it == "g" || it == "ml" } ?: return null
    val itemUnit = facts.servingItemUnit ?: facts.packageItemUnit ?: return null
    val fullItemSize = when {
        measuredUnit == "g" && facts.servingAmount?.takeIf { it > 0.0 } != null && facts.servingSizeGrams != null ->
            facts.servingSizeGrams / facts.servingAmount
        measuredUnit == "g" && facts.packageSizeGrams != null && facts.packageItemCount?.takeIf { it > 0.0 } != null ->
            facts.packageSizeGrams / facts.packageItemCount
        measuredUnit == "ml" && facts.packageSizeMilliliters != null && facts.packageItemCount?.takeIf { it > 0.0 } != null ->
            facts.packageSizeMilliliters / facts.packageItemCount
        else -> null
    }?.takeIf { it > 0.0 } ?: return null
    return LabelPortionResolver.displayAmount(measuredAmount / fullItemSize, itemUnit)
}

private fun defaultAmountForMode(
    facts: com.betterlucky.foodlog.domain.label.LabelNutritionFacts,
    inputMode: LabelInputMode,
): String =
    when (inputMode) {
        LabelInputMode.ITEMS -> "1"
        LabelInputMode.MEASURE -> {
            val range = measureSliderRange(facts)
            if (range != null) {
                "${range.maxAmount.formatLabelNumber()}${range.unit}"
            } else if (facts.kcalPer100ml != null && facts.kcalPer100g == null) {
                "1ml"
            } else {
                "1g"
            }
        }
    }

private fun String.measureAmountValue(): Double? =
    Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(?:g|ml)?\s*$""", RegexOption.IGNORE_CASE)
        .matchEntire(this)
        ?.groupValues
        ?.get(1)
        ?.replace(',', '.')
        ?.toDoubleOrNull()

private fun String.measureAmountInputText(): String =
    measureAmountValue()?.formatLabelNumber() ?: this

private fun measureAmountText(
    value: String,
    unit: String,
): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return trimmed
    if (Regex("""(?:g|ml)\s*$""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return trimmed
    val normalizedUnit = unit.takeIf { it == "g" || it == "ml" } ?: "g"
    return "$trimmed$normalizedUnit"
}

private fun Float.roundMeasureAmount(): Double =
    if (this < 10f) {
        (this * 10f).toInt() / 10.0
    } else {
        toInt().toDouble()
    }.coerceAtLeast(1.0)

@Composable
private fun LabelShortcutItemSizeFields(
    part: LoggingWizardPartDraft,
    onPartChanged: (LoggingWizardPartDraft) -> Unit,
) {
    Text(
        text = "Shortcut item size",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = part.shortcutItemSizeAmount,
            onValueChange = { onPartChanged(part.copy(shortcutItemSizeAmount = it)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            label = { Text("Full item") },
        )
        OutlinedTextField(
            value = part.shortcutItemSizeUnit,
            onValueChange = { onPartChanged(part.copy(shortcutItemSizeUnit = it.lowercase())) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("g/ml") },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShortcutModeButton(
                        label = "Clock",
                        selected = !textInput,
                        onClick = { textInput = false },
                        modifier = Modifier.weight(1f),
                    )
                    ShortcutModeButton(
                        label = "Text",
                        selected = textInput,
                        onClick = { textInput = true },
                        modifier = Modifier.weight(1f),
                    )
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
    minValue: Float,
    maxValue: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = Color.White

    fun amountAtOffset(offsetX: Float, width: Float, thumbRadius: Float): Float {
        val start = thumbRadius
        val end = width - thumbRadius
        val fraction = ((offsetX - start) / (end - start)).coerceIn(0f, 1f)
        return (minValue + (fraction * (maxValue - minValue))).coerceIn(minValue, maxValue)
    }

    Canvas(
        modifier = modifier
            .height(48.dp)
            .pointerInput(minValue, maxValue) {
                detectTapGestures { offset ->
                    onValueChange(amountAtOffset(offset.x, size.width.toFloat(), 11.dp.toPx()))
                }
            }
            .pointerInput(minValue, maxValue) {
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
        val fraction = ((value.coerceIn(minValue, maxValue) - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
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
        session.source == LoggingWizardSource.Shortcut -> LoggingWizardStep.Portion
        session.labelFacts != null &&
            (session.source == LoggingWizardSource.Label || session.shortcutPortionMode != ShortcutPortionMode.PLAIN) &&
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
            if (
                session.labelFacts != null &&
                (session.source == LoggingWizardSource.Label || session.shortcutPortionMode != ShortcutPortionMode.PLAIN) &&
                part != null
            ) {
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
