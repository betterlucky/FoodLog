package com.betterlucky.foodlog.domain.dailyclose

import com.betterlucky.foodlog.data.entities.DailyStatusEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

class DailyCloseReadinessTest {
    private val date = LocalDate.parse("2026-05-06")
    private val defaultTimeZone = TimeZone.getDefault()

    @Before
    fun setTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(defaultTimeZone)
    }

    @Test
    fun emptyDayNeedsNoExport() {
        assertEquals(
            DailyCloseReadiness.NoFoodLogged,
            dailyCloseReadiness(
                dailyStatus = null,
                pendingCount = 0,
                foodItemCount = 0,
                hasDailyWeight = false,
            ),
        )
    }

    @Test
    fun pendingEntriesBlockExportEvenWhenFoodExists() {
        assertEquals(
            DailyCloseReadiness.ResolvePending,
            dailyCloseReadiness(
                dailyStatus = exportedStatus(),
                pendingCount = 1,
                foodItemCount = 1,
                hasDailyWeight = false,
            ),
        )
    }

    @Test
    fun foodRowsWithoutCurrentExportAreReadyToExport() {
        assertEquals(
            DailyCloseReadiness.ReadyToExport,
            dailyCloseReadiness(
                dailyStatus = null,
                pendingCount = 0,
                foodItemCount = 1,
                hasDailyWeight = false,
            ),
        )
    }

    @Test
    fun weightOnlyDayWithoutCurrentExportIsReadyToExport() {
        assertEquals(
            DailyCloseReadiness.ReadyToExport,
            dailyCloseReadiness(
                dailyStatus = null,
                pendingCount = 0,
                foodItemCount = 0,
                hasDailyWeight = true,
            ),
        )
    }

    @Test
    fun exportedDayIsCurrentWhenNothingChangedAfterExport() {
        assertEquals(
            DailyCloseReadiness.AlreadyExported,
            dailyCloseReadiness(
                dailyStatus = exportedStatus(
                    exportedAt = Instant.parse("2026-05-06T20:00:00Z"),
                    changedAt = Instant.parse("2026-05-06T19:59:00Z"),
                ),
                pendingCount = 0,
                foodItemCount = 1,
                hasDailyWeight = false,
            ),
        )
    }

    @Test
    fun changedFoodAfterExportRequiresFreshExport() {
        assertEquals(
            DailyCloseReadiness.ReadyToExport,
            dailyCloseReadiness(
                dailyStatus = exportedStatus(
                    exportedAt = Instant.parse("2026-05-06T20:00:00Z"),
                    changedAt = Instant.parse("2026-05-06T20:01:00Z"),
                ),
                pendingCount = 0,
                foodItemCount = 1,
                hasDailyWeight = false,
            ),
        )
    }

    @Test
    fun changedExportedDayRequiresFreshExportEvenWhenNoRowsRemain() {
        assertEquals(
            DailyCloseReadiness.ReadyToExport,
            dailyCloseReadiness(
                dailyStatus = exportedStatus(
                    exportedAt = Instant.parse("2026-05-06T20:00:00Z"),
                    changedAt = Instant.parse("2026-05-06T20:01:00Z"),
                ),
                pendingCount = 0,
                foodItemCount = 0,
                hasDailyWeight = false,
            ),
        )
    }

    @Test
    fun promptTextMatchesEachCloseState() {
        assertEquals("No export needed yet.", DailyCloseReadiness.NoFoodLogged.closePromptText())
        assertEquals(
            "Resolve pending entries before the daily report.",
            DailyCloseReadiness.ResolvePending.closePromptText(),
        )
        assertEquals(
            "Export the latest daily report before closing this day.",
            DailyCloseReadiness.ReadyToExport.closePromptText(),
        )
        assertEquals("Daily report is current.", DailyCloseReadiness.AlreadyExported.closePromptText())
    }

    @Test
    fun exportStatusTextShowsMissingStaleAndCurrentStates() {
        assertEquals("not exported", null.legacyExportStatusText())
        assertEquals(
            "needs re-export: changed 21:01 after 21:00 export",
            exportedStatus(
                exportedAt = Instant.parse("2026-05-06T20:00:00Z"),
                changedAt = Instant.parse("2026-05-06T20:01:00Z"),
            ).legacyExportStatusText(),
        )
        assertEquals(
            "exported 21:00",
            exportedStatus(
                exportedAt = Instant.parse("2026-05-06T20:00:00Z"),
                changedAt = Instant.parse("2026-05-06T19:59:00Z"),
            ).legacyExportStatusText(),
        )
    }

    @Test
    fun exportActionTextDistinguishesFirstExportFromReExport() {
        assertEquals("Export daily report", null.legacyExportActionText())
        assertEquals("Re-export daily report", exportedStatus().legacyExportActionText())
    }

    @Test
    fun exportAuditTextShowsLastExportFileWhenPresent() {
        assertEquals(null, null.legacyExportAuditText())
        assertEquals(
            "Last exported: 21:00 - food_log_2026-05-06.csv",
            exportedStatus(
                exportedAt = Instant.parse("2026-05-06T20:00:00Z"),
            ).legacyExportAuditText(),
        )
        assertEquals(
            "Last exported: 21:00",
            exportedStatus(
                exportedAt = Instant.parse("2026-05-06T20:00:00Z"),
            ).copy(legacyExportFileName = null).legacyExportAuditText(),
        )
    }

    private fun exportedStatus(
        exportedAt: Instant = Instant.parse("2026-05-06T20:00:00Z"),
        changedAt: Instant? = Instant.parse("2026-05-06T19:59:00Z"),
    ): DailyStatusEntity =
        DailyStatusEntity(
            logDate = date,
            legacyExportedAt = exportedAt,
            lastFoodChangedAt = changedAt,
            legacyExportFileName = "food_log_2026-05-06.csv",
        )
}
