package com.betterlucky.foodlog.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.betterlucky.foodlog.data.dao.ContainerDao
import com.betterlucky.foodlog.data.dao.FoodItemDao
import com.betterlucky.foodlog.data.dao.ProductDao
import com.betterlucky.foodlog.data.dao.ProductPhotoDao
import com.betterlucky.foodlog.data.dao.RawEntryDao
import com.betterlucky.foodlog.data.dao.UserDefaultDao
import com.betterlucky.foodlog.data.entities.ContainerEntity
import com.betterlucky.foodlog.data.entities.FoodItemEntity
import com.betterlucky.foodlog.data.entities.ProductEntity
import com.betterlucky.foodlog.data.entities.ProductPhotoEntity
import com.betterlucky.foodlog.data.entities.RawEntryEntity
import com.betterlucky.foodlog.data.entities.UserDefaultEntity

@Database(
    entities = [
        RawEntryEntity::class,
        FoodItemEntity::class,
        ProductEntity::class,
        ProductPhotoEntity::class,
        ContainerEntity::class,
        UserDefaultEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FoodLogDatabase : RoomDatabase() {
    abstract fun rawEntryDao(): RawEntryDao
    abstract fun foodItemDao(): FoodItemDao
    abstract fun productDao(): ProductDao
    abstract fun productPhotoDao(): ProductPhotoDao
    abstract fun containerDao(): ContainerDao
    abstract fun userDefaultDao(): UserDefaultDao

    companion object {
        fun create(context: Context): FoodLogDatabase =
            Room.databaseBuilder(
                context,
                FoodLogDatabase::class.java,
                "foodlog.db",
            ).build()
    }
}
