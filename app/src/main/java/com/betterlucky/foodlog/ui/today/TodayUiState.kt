package com.betterlucky.foodlog.ui.today

import com.betterlucky.foodlog.data.entities.AppSettingsEntity
import com.betterlucky.foodlog.data.entities.UserDefaultEntity
import java.time.LocalDate
import java.time.LocalTime

data class TodayUiState(
    val selectedDate: LocalDate,
    val userDefaults: List<UserDefaultEntity> = emptyList(),
    val dayBoundaryTime: LocalTime? = null,
    val lastLabelInputMode: String = AppSettingsEntity.LAST_LABEL_INPUT_MODE_ITEMS,
    val inputDrafts: Map<LocalDate, String> = emptyMap(),
    val message: String? = null,
)
