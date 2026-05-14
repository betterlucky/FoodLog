package com.betterlucky.foodlog.domain.export

import com.betterlucky.foodlog.data.entities.ConfidenceLevel
import com.betterlucky.foodlog.data.entities.DailyWeightEntity
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.FoodItemSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class CsvExporterTest {
    @Test
    fun legacyHeaderMatchesSampleCsv() {
        val csv = LegacyHealthCsvExporter().export(emptyList())

        assertEquals("date,time_local,item,quantity,calories_kcal,notes", csv)
    }

    @Test
    fun auditHeaderMatchesRichSchema() {
        val csv = AuditCsvExporter().export(emptyList())

        assertEquals(
            "log_date,consumed_time,item,amount,unit,grams,calories,source,confidence,product,notes,raw_entry_id,created_at",
            csv,
        )
    }

    @Test
    fun journalDefaultHeaderMatchesDailyReportShape() {
        val csv = JournalCsvExporter().export(
            items = emptyList(),
            dailyWeights = emptyList(),
        )

        assertEquals(
            "date,time_local,entry_type,item,quantity,calories_kcal,weight_kg,notes,source,food_item_id,product_id,created_at",
            csv,
        )
    }

    @Test
    fun journalExportsFoodOnlyByDefault() {
        val csv = JournalCsvExporter().export(
            items = listOf(foodItem()),
            dailyWeights = listOf(dailyWeight()),
        )

        assertEquals(2, csv.lines().size)
        assertTrue(csv.lines()[1].startsWith("2026-04-24,16:00,food,Tea,1 cup,25"))
        assertFalse(csv.lines().drop(1).any { it.contains(",weight,") })
    }

    @Test
    fun journalIncludesWeightOnlyWhenSelected() {
        val csv = JournalCsvExporter().export(
            items = listOf(foodItem(consumedTime = LocalTime.parse("16:00"))),
            dailyWeights = listOf(dailyWeight()),
            options = JournalExportOptions(includeWeight = true),
        )

        assertEquals(
            "2026-04-24,07:15,weight,weight,,,82.6,Recorded weight,daily weight,,,2026-04-24T06:15:00Z",
            csv.lines()[1],
        )
        assertTrue(csv.lines()[2].startsWith("2026-04-24,16:00,food,Tea"))
    }

    @Test
    fun journalIncludesStableFoodIdentifiers() {
        val csv = JournalCsvExporter().export(
            items = listOf(
                foodItem(
                    productId = 5,
                    grams = 250.0,
                    source = FoodItemSource.SAVED_LABEL,
                ),
            ),
            dailyWeights = emptyList(),
        )

        assertTrue(csv.lines()[1].contains("label scan,1,5,2026-04-24T15:00:00Z"))
    }

    @Test
    fun exportersEscapeCsvAndExcludeVoidedRows() {
        val active = foodItem(
            name = "Homemade chicken, veg",
            notes = "User said \"large\" portion",
        )
        val voided = foodItem(name = "Voided", voided = true)

        val legacy = LegacyHealthCsvExporter().export(listOf(voided, active))
        val audit = AuditCsvExporter().export(listOf(voided, active))

        assertTrue(legacy.contains("\"Homemade chicken, veg\""))
        assertTrue(legacy.contains("\"User said \"\"large\"\" portion\""))
        assertFalse(legacy.contains("Voided"))
        assertTrue(audit.contains("USER_DEFAULT,HIGH"))
        assertFalse(audit.contains("Voided"))
    }

    @Test
    fun blankConsumedTimeExportsAsBlankField() {
        val csv = LegacyHealthCsvExporter().export(
            listOf(foodItem(consumedTime = null)),
        )

        assertTrue(csv.lines()[1].startsWith("2026-04-24,,Tea"))
    }

    @Test
    fun legacyExporterPluralizesCupQuantities() {
        val csv = LegacyHealthCsvExporter().export(
            listOf(foodItem(amount = 2.0)),
        )

        assertTrue(csv.lines()[1].contains("2 cups"))
    }

    @Test
    fun weightRowExportsWithBlankCaloriesAndSortsByTime() {
        val csv = LegacyHealthCsvExporter().export(
            items = listOf(foodItem(consumedTime = LocalTime.parse("16:00"))),
            dailyWeight = DailyWeightEntity(
                logDate = LocalDate.parse("2026-04-24"),
                weightKg = 82.6,
                measuredTime = LocalTime.parse("07:15"),
                createdAt = Instant.parse("2026-04-24T06:15:00Z"),
                updatedAt = Instant.parse("2026-04-24T06:15:00Z"),
            ),
        )

        assertEquals("2026-04-24,07:15,weight,82.6 kg,,Recorded weight", csv.lines()[1])
        assertTrue(csv.lines()[2].startsWith("2026-04-24,16:00,Tea"))
    }

    private fun foodItem(
        name: String = "Tea",
        notes: String? = "English tea with skimmed milk and half a teaspoon of sugar",
        consumedTime: LocalTime? = LocalTime.parse("16:00"),
        amount: Double = 1.0,
        voided: Boolean = false,
        productId: Long? = null,
        grams: Double? = null,
        source: FoodItemSource = FoodItemSource.USER_DEFAULT,
    ): FoodItemEntity =
        FoodItemEntity(
            id = 1,
            rawEntryId = 10,
            logDate = LocalDate.parse("2026-04-24"),
            consumedTime = consumedTime,
            name = name,
            productId = productId,
            amount = amount,
            unit = "cup",
            grams = grams,
            calories = 25.0,
            source = source,
            confidence = ConfidenceLevel.HIGH,
            notes = notes,
            createdAt = Instant.parse("2026-04-24T15:00:00Z"),
            voided = voided,
        )

    private fun dailyWeight(): DailyWeightEntity =
        DailyWeightEntity(
            logDate = LocalDate.parse("2026-04-24"),
            weightKg = 82.6,
            measuredTime = LocalTime.parse("07:15"),
            createdAt = Instant.parse("2026-04-24T06:15:00Z"),
            updatedAt = Instant.parse("2026-04-24T06:15:00Z"),
        )
}
