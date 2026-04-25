package com.betterlucky.foodlog.ui.today

import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import com.betterlucky.foodlog.data.entities.UserDefaultEntity
import com.betterlucky.foodlog.data.entities.DailyStatusEntity
import java.time.LocalDate

data class TodayUiState(
    val selectedDate: LocalDate,
    val items: List<FoodItemEntity> = emptyList(),
    val pendingEntries: List<RawEntryEntity> = emptyList(),
    val userDefaults: List<UserDefaultEntity> = emptyList(),
    val dailyStatus: DailyStatusEntity? = null,
    val totalCalories: Double = 0.0,
    val inputText: String = "",
    val isLoading: Boolean = true,
    val message: String? = null,
)
