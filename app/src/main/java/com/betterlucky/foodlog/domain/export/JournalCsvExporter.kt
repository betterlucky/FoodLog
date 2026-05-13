package com.betterlucky.foodlog.domain.export

import com.betterlucky.foodlog.data.entities.ConfidenceLevel
import com.betterlucky.foodlog.data.entities.DailyWeightEntity
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.FoodItemSource
import com.betterlucky.foodlog.data.entities.ProductEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class JournalExportOptions(
    val includeWeight: Boolean = false,
    val includeProduct: Boolean = false,
    val includeSource: Boolean = false,
    val includeGrams: Boolean = false,
    val includeRawEntryId: Boolean = false,
    val includeCreatedAt: Boolean = false,
    val includeConfidence: Boolean = false,
    val includeProductId: Boolean = false,
)

class JournalCsvExporter {
    fun export(
        items: List<FoodItemEntity>,
        productsById: Map<Long, ProductEntity>,
        dailyWeights: List<DailyWeightEntity>,
        options: JournalExportOptions = JournalExportOptions(),
    ): String {
        val rows = mutableListOf(header(options))
        sortedRows(items, productsById, dailyWeights, options).forEach { row ->
            rows += csvLine(row.csvValues(options))
        }
        return rows.joinToString("\n")
    }

    private fun header(options: JournalExportOptions): String =
        (
            listOf("date", "time_local", "item", "quantity", "calories_kcal", "notes") +
                listOfNotNull(
                    "product".takeIf { options.includeProduct },
                    "source".takeIf { options.includeSource },
                    "grams".takeIf { options.includeGrams },
                    "raw_entry_id".takeIf { options.includeRawEntryId },
                    "created_at".takeIf { options.includeCreatedAt },
                    "confidence".takeIf { options.includeConfidence },
                    "product_id".takeIf { options.includeProductId },
                )
            ).joinToString(",")

    private fun sortedRows(
        items: List<FoodItemEntity>,
        productsById: Map<Long, ProductEntity>,
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
                    item = item.name,
                    quantity = quantityFor(item),
                    calories = item.calories.formatCalories(),
                    notes = item.notes.orEmpty(),
                    product = item.productId?.let(productsById::get)?.displayName().orEmpty(),
                    source = item.source.displayName(),
                    grams = item.grams?.formatAmount().orEmpty(),
                    rawEntryId = item.rawEntryId.toString(),
                    confidence = item.confidence.displayName(),
                    productId = item.productId?.toString().orEmpty(),
                )
            }
        val weightRows = if (options.includeWeight) {
            dailyWeights.map { weight ->
                JournalExportRow(
                    logDate = weight.logDate,
                    time = weight.measuredTime,
                    createdAt = weight.createdAt,
                    item = "weight",
                    quantity = "${weight.weightKg.formatWeightKg()} kg",
                    calories = "",
                    notes = "Recorded weight",
                    product = "",
                    source = "",
                    grams = "",
                    rawEntryId = "",
                    confidence = "",
                    productId = "",
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
        const val DEFAULT_HEADER = "date,time_local,item,quantity,calories_kcal,notes"
    }

    private data class JournalExportRow(
        val logDate: LocalDate,
        val time: LocalTime?,
        val createdAt: Instant,
        val item: String,
        val quantity: String,
        val calories: String,
        val notes: String,
        val product: String,
        val source: String,
        val grams: String,
        val rawEntryId: String,
        val confidence: String,
        val productId: String,
    ) {
        fun csvValues(options: JournalExportOptions): List<String?> =
            listOf(
                logDate.toString(),
                time?.toString().orEmpty(),
                item,
                quantity,
                calories,
                notes,
            ) +
                listOfNotNull(
                    product.takeIf { options.includeProduct },
                    source.takeIf { options.includeSource },
                    grams.takeIf { options.includeGrams },
                    rawEntryId.takeIf { options.includeRawEntryId },
                    createdAt.toString().takeIf { options.includeCreatedAt },
                    confidence.takeIf { options.includeConfidence },
                    productId.takeIf { options.includeProductId },
                )
    }
}

private fun ProductEntity.displayName(): String =
    listOfNotNull(brand?.takeIf { it.isNotBlank() }, name.takeIf { it.isNotBlank() })
        .joinToString(" ")

private fun FoodItemSource.displayName(): String =
    when (this) {
        FoodItemSource.SAVED_LABEL -> "label scan"
        FoodItemSource.ACTIVE_LEFTOVER -> "leftover"
        FoodItemSource.RECENT_PRODUCT -> "product"
        FoodItemSource.USER_DEFAULT -> "shortcut"
        FoodItemSource.MANUAL_OVERRIDE -> "manual entry"
        FoodItemSource.ESTIMATE -> "estimate"
    }

private fun ConfidenceLevel.displayName(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }

private fun Double.formatWeightKg(): String =
    String.format(java.util.Locale.US, "%.1f", this)
