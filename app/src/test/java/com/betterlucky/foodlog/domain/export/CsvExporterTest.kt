package com.betterlucky.foodlog.domain.export

import com.betterlucky.foodlog.data.entities.ConfidenceLevel
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

    private fun foodItem(
        name: String = "Tea",
        notes: String? = "English tea with skimmed milk and half a teaspoon of sugar",
        consumedTime: LocalTime? = LocalTime.parse("16:00"),
        amount: Double = 1.0,
        voided: Boolean = false,
    ): FoodItemEntity =
        FoodItemEntity(
            id = 1,
            rawEntryId = 10,
            logDate = LocalDate.parse("2026-04-24"),
            consumedTime = consumedTime,
            name = name,
            amount = amount,
            unit = "cup",
            calories = 25.0,
            source = FoodItemSource.USER_DEFAULT,
            confidence = ConfidenceLevel.HIGH,
            notes = notes,
            createdAt = Instant.parse("2026-04-24T15:00:00Z"),
            voided = voided,
        )
}
