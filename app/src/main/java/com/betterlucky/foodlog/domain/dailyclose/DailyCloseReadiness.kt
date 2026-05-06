package com.betterlucky.foodlog.domain.dailyclose

import com.betterlucky.foodlog.data.entities.DailyStatusEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class DailyCloseReadiness(val label: String) {
    NoFoodLogged("No food logged"),
    ResolvePending("Resolve pending entries"),
    ReadyToExport("Ready to export"),
    AlreadyExported("Already exported"),
}

fun dailyCloseReadiness(
    dailyStatus: DailyStatusEntity?,
    pendingCount: Int,
    foodItemCount: Int,
    hasDailyWeight: Boolean,
): DailyCloseReadiness =
    when {
        pendingCount > 0 -> DailyCloseReadiness.ResolvePending
        foodItemCount == 0 && !hasDailyWeight -> DailyCloseReadiness.NoFoodLogged
        dailyStatus.isLegacyExportCurrent() -> DailyCloseReadiness.AlreadyExported
        else -> DailyCloseReadiness.ReadyToExport
    }

fun DailyCloseReadiness.closePromptText(): String =
    when (this) {
        DailyCloseReadiness.NoFoodLogged -> "No export needed yet."
        DailyCloseReadiness.ResolvePending -> "Resolve pending entries before Lodestone export."
        DailyCloseReadiness.ReadyToExport -> "Export the Lodestone CSV before closing this day."
        DailyCloseReadiness.AlreadyExported -> "Lodestone export is current."
    }

fun DailyStatusEntity?.legacyExportStatusText(): String {
    val exportedAt = this?.legacyExportedAt ?: return "not exported"
    val suffix = legacyExportFileName.exportFileSuffix()
    return if (lastFoodChangedAt != null && lastFoodChangedAt > exportedAt) {
        "needs update since ${exportedAt.displayTime()}$suffix"
    } else {
        "exported ${exportedAt.displayTime()}$suffix"
    }
}

private fun DailyStatusEntity?.isLegacyExportCurrent(): Boolean {
    val exportedAt = this?.legacyExportedAt ?: return false
    val changedAt = lastFoodChangedAt ?: return true
    return changedAt <= exportedAt
}

private fun String?.exportFileSuffix(): String =
    if (isNullOrBlank()) "" else " - $this"

private fun Instant.displayTime(): String =
    atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
