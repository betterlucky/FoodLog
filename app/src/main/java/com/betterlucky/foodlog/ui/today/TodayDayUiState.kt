package com.betterlucky.foodlog.ui.today

import com.betterlucky.foodlog.data.entities.DailyStatusEntity
import com.betterlucky.foodlog.data.entities.DailyWeightEntity
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import java.time.LocalDate

data class TodayDayUiState(
    val date: LocalDate,
    val items: List<FoodItemEntity> = emptyList(),
    val totalCalories: Double = 0.0,
    val pendingEntries: List<RawEntryEntity> = emptyList(),
    val dailyStatus: DailyStatusEntity? = null,
    val dailyWeight: DailyWeightEntity? = null,
    val isReady: Boolean = false,
)
