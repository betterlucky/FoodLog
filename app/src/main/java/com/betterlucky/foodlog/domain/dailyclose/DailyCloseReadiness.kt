package com.betterlucky.foodlog.domain.dailyclose

import com.betterlucky.foodlog.data.entities.DailyStatusEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class DailyCloseReadiness(val label: String) {
    NoFoodLogged("No food logged"),
    ResolvePending("Resolve pending entries"),
    ReadyToExport("Ready to export"),
    AlreadyExported("Export current"),
}

fun dailyCloseReadiness(
    dailyStatus: DailyStatusEntity?,
    pendingCount: Int,
    foodItemCount: Int,
    hasDailyWeight: Boolean,
): DailyCloseReadiness =
    when {
        pendingCount > 0 -> DailyCloseReadiness.ResolvePending
        dailyStatus.isLegacyExportStale() -> DailyCloseReadiness.ReadyToExport
        foodItemCount == 0 && !hasDailyWeight -> DailyCloseReadiness.NoFoodLogged
        dailyStatus.isLegacyExportCurrent() -> DailyCloseReadiness.AlreadyExported
        else -> DailyCloseReadiness.ReadyToExport
    }

fun DailyCloseReadiness.closePromptText(): String =
    when (this) {
        DailyCloseReadiness.NoFoodLogged -> "No export needed yet."
        DailyCloseReadiness.ResolvePending -> "Resolve pending entries before the daily report."
        DailyCloseReadiness.ReadyToExport -> "Export the latest Lodestone CSV before closing this day."
        DailyCloseReadiness.AlreadyExported -> "Lodestone CSV is current."
    }

fun DailyStatusEntity?.legacyExportStatusText(): String {
    val exportedAt = this?.legacyExportedAt ?: return "not exported"
    val changedAt = lastFoodChangedAt
    return if (changedAt != null && changedAt > exportedAt) {
        "changed since export: ${changedAt.displayTime()} after ${exportedAt.displayTime()}"
    } else {
        "current: exported ${exportedAt.displayTime()}"
    }
}

fun DailyStatusEntity?.legacyExportActionText(): String =
    if (this?.legacyExportedAt == null) {
        "Export daily report"
    } else {
        "Re-export daily report"
    }

fun DailyStatusEntity?.legacyExportAuditText(): String? {
    val exportedAt = this?.legacyExportedAt ?: return null
    return "Last exported: ${exportedAt.displayTime()}${legacyExportFileName.exportFileSuffix()}"
}

private fun DailyStatusEntity?.isLegacyExportCurrent(): Boolean {
    val exportedAt = this?.legacyExportedAt ?: return false
    val changedAt = lastFoodChangedAt ?: return true
    return changedAt <= exportedAt
}

private fun DailyStatusEntity?.isLegacyExportStale(): Boolean {
    val exportedAt = this?.legacyExportedAt ?: return false
    val changedAt = lastFoodChangedAt ?: return false
    return changedAt > exportedAt
}

private fun String?.exportFileSuffix(): String =
    if (isNullOrBlank()) "" else " - $this"

private fun Instant.displayTime(): String =
    atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
