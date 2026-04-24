package com.betterlucky.foodlog.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.betterlucky.foodlog.data.repository.FoodLogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val repository: FoodLogRepository,
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val inputText = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TodayUiState> =
        combine(
            selectedDate,
            inputText,
            message,
            selectedDate.flatMapLatest(repository::observeFoodItemsForDate),
            selectedDate.flatMapLatest(repository::observeCaloriesForDate),
            repository.observePendingEntries(),
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            TodayUiState(
                selectedDate = values[0] as LocalDate,
                inputText = values[1] as String,
                message = values[2] as String?,
                items = values[3] as List<com.betterlucky.foodlog.data.entities.FoodItemEntity>,
                totalCalories = values[4] as Double,
                pendingEntries = values[5] as List<com.betterlucky.foodlog.data.entities.RawEntryEntity>,
                isLoading = false,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TodayUiState(selectedDate = selectedDate.value),
        )

    init {
        viewModelScope.launch {
            repository.seedDefaults()
        }
    }

    fun onInputChanged(value: String) {
        inputText.value = value
        message.value = null
    }

    fun submit() {
        val text = inputText.value
        if (text.isBlank()) return

        viewModelScope.launch {
            val result = repository.submitText(text)
            inputText.value = ""
            message.value = when (result) {
                is FoodLogRepository.SubmitResult.Parsed -> "Logged for ${result.logDate}"
                is FoodLogRepository.SubmitResult.Pending -> "Saved as pending for ${result.logDate}"
            }
        }
    }

    fun selectDate(date: LocalDate) {
        selectedDate.value = date
    }

    fun previousDay() {
        selectedDate.update { it.minusDays(1) }
    }

    fun nextDay() {
        selectedDate.update { it.plusDays(1) }
    }

    fun exportLegacyCsv(onExported: (String) -> Unit) {
        viewModelScope.launch {
            onExported(repository.exportLegacyHealthCsv(selectedDate.value))
        }
    }

    fun exportAuditCsv(onExported: (String) -> Unit) {
        viewModelScope.launch {
            onExported(repository.exportAuditCsv(selectedDate.value))
        }
    }
}

class TodayViewModelFactory(
    private val repository: FoodLogRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TodayViewModel::class.java)) {
            return TodayViewModel(repository) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
