package com.betterlucky.foodlog.domain.export

import com.betterlucky.foodlog.data.entities.FoodItemEntity

class LegacyHealthCsvExporter {
    fun export(items: List<FoodItemEntity>): String {
        val rows = mutableListOf(HEADER)
        sortedActive(items).forEach { item ->
            rows += csvLine(
                listOf(
                    item.logDate.toString(),
                    item.consumedTime?.toString().orEmpty(),
                    item.name,
                    quantityFor(item),
                    item.calories.formatCalories(),
                    item.notes.orEmpty(),
                ),
            )
        }
        return rows.joinToString("\n")
    }

    private fun sortedActive(items: List<FoodItemEntity>): List<FoodItemEntity> =
        items
            .filterNot { it.voided }
            .sortedWith(
                compareBy<FoodItemEntity> { it.logDate }
                    .thenBy { it.consumedTime }
                    .thenBy { it.createdAt },
            )

    private fun quantityFor(item: FoodItemEntity): String =
        when {
            item.amount != null && item.unit != null -> "${item.amount.formatAmount()} ${item.unit}"
            item.amount != null -> item.amount.formatAmount()
            item.unit != null -> item.unit
            else -> ""
        }

    companion object {
        const val HEADER = "date,time_local,item,quantity,calories_kcal,notes"
    }
}
