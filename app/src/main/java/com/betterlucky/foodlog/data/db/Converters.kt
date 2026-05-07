package com.betterlucky.foodlog.data.db

import androidx.room.TypeConverter
import com.betterlucky.foodlog.data.entities.ConfidenceLevel
import com.betterlucky.foodlog.data.entities.ContainerStatus
import com.betterlucky.foodlog.data.entities.EntryKind
import com.betterlucky.foodlog.data.entities.FoodItemSource
import com.betterlucky.foodlog.data.entities.ProductPhotoStatus
import com.betterlucky.foodlog.data.entities.ProductSource
import com.betterlucky.foodlog.data.entities.RawEntryStatus
import com.betterlucky.foodlog.data.entities.ShortcutPortionMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class Converters {
    @TypeConverter
    fun instantToString(value: Instant?): String? = value?.toString()

    @TypeConverter
    fun stringToInstant(value: String?): Instant? = value?.let(Instant::parse)

    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun localTimeToString(value: LocalTime?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalTime(value: String?): LocalTime? = value?.let(LocalTime::parse)

    @TypeConverter
    fun entryKindToString(value: EntryKind?): String? = value?.name

    @TypeConverter
    fun stringToEntryKind(value: String?): EntryKind? = value?.let(EntryKind::valueOf)

    @TypeConverter
    fun rawEntryStatusToString(value: RawEntryStatus?): String? = value?.name

    @TypeConverter
    fun stringToRawEntryStatus(value: String?): RawEntryStatus? = value?.let(RawEntryStatus::valueOf)

    @TypeConverter
    fun productSourceToString(value: ProductSource?): String? = value?.name

    @TypeConverter
    fun stringToProductSource(value: String?): ProductSource? = value?.let(ProductSource::valueOf)

    @TypeConverter
    fun productPhotoStatusToString(value: ProductPhotoStatus?): String? = value?.name

    @TypeConverter
    fun stringToProductPhotoStatus(value: String?): ProductPhotoStatus? = value?.let(ProductPhotoStatus::valueOf)

    @TypeConverter
    fun containerStatusToString(value: ContainerStatus?): String? = value?.name

    @TypeConverter
    fun stringToContainerStatus(value: String?): ContainerStatus? = value?.let(ContainerStatus::valueOf)

    @TypeConverter
    fun foodItemSourceToString(value: FoodItemSource?): String? = value?.name

    @TypeConverter
    fun stringToFoodItemSource(value: String?): FoodItemSource? = value?.let(FoodItemSource::valueOf)

    @TypeConverter
    fun confidenceLevelToString(value: ConfidenceLevel?): String? = value?.name

    @TypeConverter
    fun stringToConfidenceLevel(value: String?): ConfidenceLevel? = value?.let(ConfidenceLevel::valueOf)

    @TypeConverter
    fun shortcutPortionModeToString(value: ShortcutPortionMode?): String? = value?.name

    @TypeConverter
    fun stringToShortcutPortionMode(value: String?): ShortcutPortionMode? = value?.let(ShortcutPortionMode::valueOf)
}
