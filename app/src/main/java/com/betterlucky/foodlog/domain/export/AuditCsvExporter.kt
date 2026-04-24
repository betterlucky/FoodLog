package com.betterlucky.foodlog.domain.export

import com.betterlucky.foodlog.data.entities.FoodItemEntity

class AuditCsvExporter {
    fun export(items: List<FoodItemEntity>): String {
        val rows = mutableListOf(HEADER)
        sortedActive(items).forEach { item ->
            rows += csvLine(
                listOf(
                    item.logDate.toString(),
                    item.consumedTime?.toString().orEmpty(),
                    item.name,
                    item.amount?.formatAmount().orEmpty(),
                    item.unit.orEmpty(),
                    item.grams?.formatAmount().orEmpty(),
                    item.calories.formatCalories(),
                    item.source.name,
                    item.confidence.name,
                    "",
                    item.notes.orEmpty(),
                    item.rawEntryId.toString(),
                    item.createdAt.toString(),
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

    companion object {
        const val HEADER =
            "log_date,consumed_time,item,amount,unit,grams,calories,source,confidence,product,notes,raw_entry_id,created_at"
    }
}
