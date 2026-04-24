package com.betterlucky.foodlog.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onShareCsv: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(onClick = viewModel::previousDay) {
                Text("Prev")
            }
            Text(
                text = uiState.selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                fontWeight = FontWeight.Bold,
            )
            Button(onClick = viewModel::nextDay) {
                Text("Next")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = uiState.inputText,
                onValueChange = viewModel::onInputChanged,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Type food naturally") },
            )
            Button(onClick = viewModel::submit) {
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.exportLegacyCsv(onShareCsv) }) {
                Text("Export Legacy CSV")
            }
            Button(onClick = { viewModel.exportAuditCsv(onShareCsv) }) {
                Text("Export Audit CSV")
            }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(text = "Logged Items", fontWeight = FontWeight.Bold)
            }
            items(uiState.items) { item ->
                FoodItemRow(item)
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Pending", fontWeight = FontWeight.Bold)
            }
            items(uiState.pendingEntries) { entry ->
                PendingEntryRow(entry)
            }
        }
    }
}

@Composable
private fun FoodItemRow(item: FoodItemEntity) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = "${item.consumedTime ?: "--:--"}  ${item.name}")
        Text(text = "${item.calories.toInt()} kcal")
    }
}

@Composable
private fun PendingEntryRow(entry: RawEntryEntity) {
    Text(text = "${entry.logDate}: \"${entry.rawText}\"")
}
