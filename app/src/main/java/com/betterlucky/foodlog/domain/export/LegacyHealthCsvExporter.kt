package com.betterlucky.foodlog.domain.export

import com.betterlucky.foodlog.data.entities.DailyWeightEntity
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class LegacyHealthCsvExporter {
    fun export(
        items: List<FoodItemEntity>,
        dailyWeight: DailyWeightEntity? = null,
    ): String {
        val rows = mutableListOf(HEADER)
        sortedRows(items, dailyWeight).forEach { row ->
            rows += csvLine(row.csvValues)
        }
        return rows.joinToString("\n")
    }

    private fun sortedRows(
        items: List<FoodItemEntity>,
        dailyWeight: DailyWeightEntity?,
    ): List<ExportRow> {
        val foodRows = items
            .filterNot { it.voided }
            .map { item ->
                ExportRow(
                    logDate = item.logDate,
                    time = item.consumedTime,
                    createdAt = item.createdAt,
                    csvValues = listOf(
                        item.logDate.toString(),
                        item.consumedTime?.toString().orEmpty(),
                        item.name,
                        quantityFor(item),
                        item.calories.formatCalories(),
                        item.notes.orEmpty(),
                    ),
                )
            }
        val weightRows = listOfNotNull(
            dailyWeight?.let { weight ->
                ExportRow(
                    logDate = weight.logDate,
                    time = weight.measuredTime,
                    createdAt = weight.createdAt,
                    csvValues = listOf(
                        weight.logDate.toString(),
                        weight.measuredTime.toString(),
                        "weight",
                        "${weight.weightKg.formatWeightKg()} kg",
                        "",
                        "Recorded weight",
                    ),
                )
            },
        )

        return (foodRows + weightRows)
            .sortedWith(
                compareBy<ExportRow> { it.logDate }
                    .thenBy { it.time }
                    .thenBy { it.createdAt },
            )
    }

    private fun quantityFor(item: FoodItemEntity): String =
        when {
            item.amount != null && item.unit != null -> "${item.amount.formatAmount()} ${pluralizedUnit(item.unit, item.amount)}"
            item.amount != null -> item.amount.formatAmount()
            item.unit != null -> item.unit
            else -> ""
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

    companion object {
        const val HEADER = "date,time_local,item,quantity,calories_kcal,notes"
    }

    private data class ExportRow(
        val logDate: LocalDate,
        val time: LocalTime?,
        val createdAt: Instant,
        val csvValues: List<String?>,
    )
}

private fun Double.formatWeightKg(): String =
    String.format(java.util.Locale.US, "%.1f", this)
