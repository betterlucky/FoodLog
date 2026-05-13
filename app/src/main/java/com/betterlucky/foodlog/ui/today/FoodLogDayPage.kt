package com.betterlucky.foodlog.ui.today

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.betterlucky.foodlog.data.entities.DailyStatusEntity
import com.betterlucky.foodlog.data.entities.DailyWeightEntity
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import com.betterlucky.foodlog.domain.dailyclose.DailyCloseReadiness
import com.betterlucky.foodlog.domain.dailyclose.closePromptText
import com.betterlucky.foodlog.domain.dailyclose.dailyCloseReadiness
import com.betterlucky.foodlog.domain.dailyclose.legacyExportActionText
import com.betterlucky.foodlog.domain.dailyclose.legacyExportAuditText
import com.betterlucky.foodlog.domain.dailyclose.legacyExportStatusText
import com.betterlucky.foodlog.domain.label.LabelPortionResolver
import com.betterlucky.foodlog.domain.parser.TimeTextParser
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val FoodLogCardShape = RoundedCornerShape(8.dp)

@Composable
internal fun FoodLogDayPage(
    date: LocalDate,
    viewModel: TodayViewModel,
    uiState: TodayUiState,
    isActivePage: Boolean,
    loggedItemsViewMode: LoggedItemsViewMode,
    onViewModeChanged: (LoggedItemsViewMode) -> Unit,
    pendingExpanded: Boolean,
    onPendingExpandedChanged: (Boolean) -> Unit,
    loggedExpanded: Boolean,
    onLoggedExpandedChanged: (Boolean) -> Unit,
    onOpenPicker: () -> Unit,
    onPreviousDay: () -> Unit,
    onToday: () -> Unit,
    onNextDay: () -> Unit,
    onEditBoundary: () -> Unit,
    onEditWeight: () -> Unit,
    onEditFoodItem: (FoodItemEntity) -> Unit,
    onShowShortcuts: () -> Unit,
    onChooseLabelImage: () -> Unit,
    onExportLegacy: (LocalDate) -> Unit,
    onOpenJournalExport: () -> Unit,
) {
    val dayState by viewModel.dayState(date).collectAsState()
    val focusManager = LocalFocusManager.current
    val inputText = uiState.inputDrafts[date].orEmpty()
    val listState = rememberLazyListState()
    var expandedLoggedClumps by remember(date) { mutableStateOf(emptySet<String>()) }

    val readiness = dailyCloseReadiness(
        dailyStatus = dayState.dailyStatus,
        pendingCount = dayState.pendingEntries.size,
        foodItemCount = dayState.items.size,
        hasDailyWeight = dayState.dailyWeight != null,
    )

    val sortedItems = remember(date, dayState.items, loggedItemsViewMode) {
        dayState.items.sortedFor(loggedItemsViewMode)
    }

    val clumps = remember(date, dayState.items) {
        loggedItemClumps(dayState.items)
    }

    LaunchedEffect(isActivePage, dayState.pendingEntries.size) {
        if (isActivePage && dayState.pendingEntries.isNotEmpty()) {
            onPendingExpandedChanged(true)
        }
    }

    LaunchedEffect(isActivePage, dayState.items.size) {
        if (isActivePage && dayState.items.isNotEmpty()) {
            onLoggedExpandedChanged(true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FoodLogDateNavigator(
            selectedDate = date,
            readiness = readiness,
            pendingCount = dayState.pendingEntries.size,
            itemCount = dayState.items.size,
            hasDailyWeight = dayState.dailyWeight != null,
            onOpenPicker = onOpenPicker,
            onPreviousDay = onPreviousDay,
            onToday = onToday,
            onNextDay = onNextDay,
        )

        FoodDaySettingsRow(
            dayBoundaryTime = uiState.dayBoundaryTime,
            onEdit = onEditBoundary,
        )

        TodayStatusSummary(
            totalCalories = dayState.totalCalories,
            pendingCount = dayState.pendingEntries.size,
            dailyStatus = dayState.dailyStatus,
            readiness = readiness,
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { viewModel.onInputChanged(date, it) },
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
                        viewModel.submit(date)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Log")
                }
                Button(
                    onClick = onShowShortcuts,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Shortcuts")
                }
                Button(
                    onClick = onChooseLabelImage,
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
                    title = "Pending (${dayState.pendingEntries.size})",
                    detail = pendingCountText(dayState.pendingEntries.size),
                    expanded = pendingExpanded,
                    onToggle = { onPendingExpandedChanged(!pendingExpanded) },
                )
            }
            if (pendingExpanded && dayState.pendingEntries.isEmpty()) {
                item {
                    EmptyState("No pending entries for this day.")
                }
            } else if (pendingExpanded) {
                items(dayState.pendingEntries) { entry ->
                    PendingEntryRow(
                        entry = entry,
                        onResolve = { viewModel.openPendingEntryWizard(entry.id) },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                CollapsibleSectionHeader(
                    title = "Logged (${dayState.items.size})",
                    detail = loggedItemsSummary(
                        itemCount = dayState.items.size,
                        totalCalories = dayState.totalCalories,
                    ),
                    expanded = loggedExpanded,
                    onToggle = { onLoggedExpandedChanged(!loggedExpanded) },
                )
            }
            if (loggedExpanded && dayState.items.isEmpty()) {
                item {
                    EmptyState("No food logged for this day yet.")
                }
            } else if (loggedExpanded) {
                item {
                    LoggedItemsViewControls(
                        selectedMode = loggedItemsViewMode,
                        onModeSelected = { mode ->
                            onViewModeChanged(mode)
                            expandedLoggedClumps = emptySet()
                        },
                    )
                }
                when (loggedItemsViewMode) {
                    LoggedItemsViewMode.Time,
                    LoggedItemsViewMode.Calories -> {
                        items(sortedItems) { item ->
                            FoodItemRow(
                                item = item,
                                onClick = { onEditFoodItem(item) },
                            )
                        }
                    }
                    LoggedItemsViewMode.Clumped -> {
                        clumps.forEach { clump ->
                            val isExpanded = clump.key in expandedLoggedClumps
                            item {
                                ClumpedFoodItemRow(
                                    clump = clump,
                                    expanded = isExpanded,
                                    onClick = {
                                        if (clump.items.size == 1) {
                                            onEditFoodItem(clump.items.single())
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
                                items(clump.items) { item ->
                                    FoodItemRow(
                                        item = item,
                                        onClick = { onEditFoodItem(item) },
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
                    dailyWeight = dayState.dailyWeight,
                    onEdit = onEditWeight,
                )
            }

            item {
                DailyClosePrompt(
                    dailyStatus = dayState.dailyStatus,
                    pendingCount = dayState.pendingEntries.size,
                    foodItemCount = dayState.items.size,
                    hasDailyWeight = dayState.dailyWeight != null,
                    onExportLegacy = { onExportLegacy(date) },
                )
            }

            item {
                JournalExportPrompt(onOpenJournalExport = onOpenJournalExport)
            }
        }
    }
}

// --- Shared composables used by the day page ---

@Composable
private fun FoodLogDateNavigator(
    selectedDate: LocalDate,
    readiness: DailyCloseReadiness,
    pendingCount: Int,
    itemCount: Int,
    hasDailyWeight: Boolean,
    onOpenPicker: () -> Unit,
    onPreviousDay: () -> Unit,
    onToday: () -> Unit,
    onNextDay: () -> Unit,
) {
    val borderColor = when (readiness) {
        DailyCloseReadiness.AlreadyExported -> Color(0xFF2E7D60).copy(alpha = 0.55f)
        DailyCloseReadiness.ReadyToExport -> MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        DailyCloseReadiness.ResolvePending -> MaterialTheme.colorScheme.error.copy(alpha = 0.42f)
        DailyCloseReadiness.NoFoodLogged -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
    }
    val status = listOf(
        when (itemCount) {
            0 -> "no food logged"
            1 -> "1 logged item"
            else -> "$itemCount logged items"
        },
        when (pendingCount) {
            0 -> "no pending"
            1 -> "1 pending"
            else -> "$pendingCount pending"
        },
        if (hasDailyWeight) "weight saved" else "no weight",
        readiness.label.lowercase(),
    ).joinToString(" · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPicker),
        shape = FoodLogCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "Active day",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPreviousDay) {
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous day")
                }
                OutlinedButton(onClick = onToday) {
                    Text("Today")
                }
                IconButton(onClick = onNextDay) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "Next day")
                }
            }
        }
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
private fun TodayStatusSummary(
    totalCalories: Double,
    pendingCount: Int,
    dailyStatus: DailyStatusEntity?,
    readiness: DailyCloseReadiness,
) {
    val color = when (readiness) {
        DailyCloseReadiness.ResolvePending -> MaterialTheme.colorScheme.error
        DailyCloseReadiness.ReadyToExport -> MaterialTheme.colorScheme.primary
        DailyCloseReadiness.NoFoodLogged,
        DailyCloseReadiness.AlreadyExported -> MaterialTheme.colorScheme.onSurfaceVariant
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
                        fontWeight = if (readiness == DailyCloseReadiness.ReadyToExport) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (readiness != DailyCloseReadiness.NoFoodLogged) {
                Text(
                    text = "Daily report: ${dailyStatus.legacyExportStatusText()}",
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

internal enum class LoggedItemsViewMode(val label: String) {
    Time("Time"),
    Calories("Calories"),
    Clumped("Clumped"),
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
internal fun DailyClosePrompt(
    dailyStatus: DailyStatusEntity?,
    pendingCount: Int,
    foodItemCount: Int,
    hasDailyWeight: Boolean,
    onExportLegacy: () -> Unit,
) {
    val readiness = dailyCloseReadiness(
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
                DailyCloseReadiness.ResolvePending -> MaterialTheme.colorScheme.errorContainer
                DailyCloseReadiness.ReadyToExport -> MaterialTheme.colorScheme.primaryContainer
                DailyCloseReadiness.NoFoodLogged,
                DailyCloseReadiness.AlreadyExported -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Daily close",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = readiness.closePromptText(),
                color = when (readiness) {
                    DailyCloseReadiness.ResolvePending -> MaterialTheme.colorScheme.onErrorContainer
                    DailyCloseReadiness.ReadyToExport -> MaterialTheme.colorScheme.onPrimaryContainer
                    DailyCloseReadiness.NoFoodLogged,
                    DailyCloseReadiness.AlreadyExported -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (readiness != DailyCloseReadiness.NoFoodLogged) {
                val auditText = dailyStatus.legacyExportAuditText()
                Text(
                    text = "Daily report: ${dailyStatus.legacyExportStatusText()}",
                    color = when (readiness) {
                        DailyCloseReadiness.ResolvePending -> MaterialTheme.colorScheme.onErrorContainer
                        DailyCloseReadiness.ReadyToExport -> MaterialTheme.colorScheme.onPrimaryContainer
                        DailyCloseReadiness.AlreadyExported,
                        DailyCloseReadiness.NoFoodLogged -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (auditText != null) {
                    Text(
                        text = auditText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (readiness == DailyCloseReadiness.ReadyToExport) {
                Button(
                    onClick = onExportLegacy,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(dailyStatus.legacyExportActionText())
                }
            }
        }
    }
}

@Composable
private fun JournalExportPrompt(
    onOpenJournalExport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FoodLogCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Journal export",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Create a longer CSV from saved food rows.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onOpenJournalExport) {
                Text("Export")
            }
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

// --- Helper functions ---

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

private fun pluralizedUnit(
    unit: String,
    amount: Double,
): String =
    when {
        amount == 1.0 -> unit
        unit == "cup" -> "cups"
        else -> unit
    }

private data class LoggedItemClump(
    val key: String,
    val name: String,
    val quantity: String?,
    val items: List<FoodItemEntity>,
)

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

internal fun List<FoodItemEntity>.sortedFor(mode: LoggedItemsViewMode): List<FoodItemEntity> =
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
