package com.betterlucky.foodlog.domain.export

import com.betterlucky.foodlog.data.entities.DailyWeightEntity
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.FoodItemSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class JournalExportOptions(
    val includeWeight: Boolean = false,
)

class JournalCsvExporter {
    fun export(
        items: List<FoodItemEntity>,
        dailyWeights: List<DailyWeightEntity>,
        options: JournalExportOptions = JournalExportOptions(),
    ): String {
        val rows = mutableListOf(HEADER)
        sortedRows(items, dailyWeights, options).forEach { row ->
            rows += csvLine(row.csvValues)
        }
        return rows.joinToString("\n")
    }

    private fun sortedRows(
        items: List<FoodItemEntity>,
        dailyWeights: List<DailyWeightEntity>,
        options: JournalExportOptions,
    ): List<JournalExportRow> {
        val foodRows = items
            .filterNot { it.voided }
            .map { item ->
                JournalExportRow(
                    logDate = item.logDate,
                    time = item.consumedTime,
                    createdAt = item.createdAt,
                    csvValues = listOf(
                        item.logDate.toString(),
                        item.consumedTime?.toString().orEmpty(),
                        "food",
                        item.name,
                        quantityFor(item),
                        item.calories.formatCalories(),
                        "",
                        item.notes.orEmpty(),
                        item.source.displayName(),
                        item.id.toString(),
                        item.productId?.toString().orEmpty(),
                        item.createdAt.toString(),
                    ),
                )
            }
        val weightRows = if (options.includeWeight) {
            dailyWeights.map { weight ->
                JournalExportRow(
                    logDate = weight.logDate,
                    time = weight.measuredTime,
                    createdAt = weight.createdAt,
                    csvValues = listOf(
                        weight.logDate.toString(),
                        weight.measuredTime.toString(),
                        "weight",
                        "weight",
                        "",
                        "",
                        weight.weightKg.formatWeightKg(),
                        "Recorded weight",
                        "daily weight",
                        "",
                        "",
                        weight.createdAt.toString(),
                    ),
                )
            }
        } else {
            emptyList()
        }

        return (foodRows + weightRows)
            .sortedWith(
                compareBy<JournalExportRow> { it.logDate }
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
        const val HEADER =
            "date,time_local,entry_type,item,quantity,calories_kcal,weight_kg,notes,source,food_item_id,product_id,created_at"
    }

    private data class JournalExportRow(
        val logDate: LocalDate,
        val time: LocalTime?,
        val createdAt: Instant,
        val csvValues: List<String?>,
    )
}

private fun FoodItemSource.displayName(): String =
    when (this) {
        FoodItemSource.SAVED_LABEL -> "label scan"
        FoodItemSource.ACTIVE_LEFTOVER -> "leftover"
        FoodItemSource.RECENT_PRODUCT -> "product"
        FoodItemSource.USER_DEFAULT -> "shortcut"
        FoodItemSource.MANUAL_OVERRIDE -> "manual entry"
        FoodItemSource.ESTIMATE -> "estimate"
    }

private fun Double.formatWeightKg(): String =
    String.format(java.util.Locale.US, "%.1f", this)
