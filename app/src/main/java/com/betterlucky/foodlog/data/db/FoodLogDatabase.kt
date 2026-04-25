package com.betterlucky.foodlog.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.betterlucky.foodlog.data.dao.ContainerDao
import com.betterlucky.foodlog.data.dao.DailyStatusDao
import com.betterlucky.foodlog.data.dao.FoodItemDao
import com.betterlucky.foodlog.data.dao.ProductDao
import com.betterlucky.foodlog.data.dao.ProductPhotoDao
import com.betterlucky.foodlog.data.dao.RawEntryDao
import com.betterlucky.foodlog.data.dao.UserDefaultDao
import com.betterlucky.foodlog.data.entities.ContainerEntity
import com.betterlucky.foodlog.data.entities.DailyStatusEntity
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
        DailyStatusEntity::class,
    ],
    version = 2,
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
    abstract fun dailyStatusDao(): DailyStatusDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_statuses (
                        logDate TEXT NOT NULL,
                        legacyExportedAt TEXT,
                        auditExportedAt TEXT,
                        PRIMARY KEY(logDate)
                    )
                    """.trimIndent(),
                )
            }
        }

        fun create(context: Context): FoodLogDatabase =
            Room.databaseBuilder(
                context,
                FoodLogDatabase::class.java,
                "foodlog.db",
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
