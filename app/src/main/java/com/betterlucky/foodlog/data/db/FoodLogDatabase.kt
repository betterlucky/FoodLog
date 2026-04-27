package com.betterlucky.foodlog.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.betterlucky.foodlog.data.dao.AppSettingsDao
import com.betterlucky.foodlog.data.dao.ContainerDao
import com.betterlucky.foodlog.data.dao.DailyStatusDao
import com.betterlucky.foodlog.data.dao.DailyWeightDao
import com.betterlucky.foodlog.data.dao.FoodItemDao
import com.betterlucky.foodlog.data.dao.ProductDao
import com.betterlucky.foodlog.data.dao.ProductPhotoDao
import com.betterlucky.foodlog.data.dao.RawEntryDao
import com.betterlucky.foodlog.data.dao.UserDefaultDao
import com.betterlucky.foodlog.data.entities.AppSettingsEntity
import com.betterlucky.foodlog.data.entities.ContainerEntity
import com.betterlucky.foodlog.data.entities.DailyStatusEntity
import com.betterlucky.foodlog.data.entities.DailyWeightEntity
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
        AppSettingsEntity::class,
        DailyWeightEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FoodLogDatabase : RoomDatabase() {
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun rawEntryDao(): RawEntryDao
    abstract fun foodItemDao(): FoodItemDao
    abstract fun productDao(): ProductDao
    abstract fun productPhotoDao(): ProductPhotoDao
    abstract fun containerDao(): ContainerDao
    abstract fun userDefaultDao(): UserDefaultDao
    abstract fun dailyStatusDao(): DailyStatusDao
    abstract fun dailyWeightDao(): DailyWeightDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_statuses ADD COLUMN lastFoodChangedAt TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_settings (
                        id INTEGER NOT NULL,
                        dayBoundaryTime TEXT,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL("INSERT OR IGNORE INTO app_settings (id, dayBoundaryTime) VALUES (1, NULL)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_statuses ADD COLUMN legacyExportFileName TEXT")
                db.execSQL("ALTER TABLE daily_statuses ADD COLUMN auditExportFileName TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_weights (
                        logDate TEXT NOT NULL,
                        weightKg REAL NOT NULL,
                        measuredTime TEXT NOT NULL,
                        createdAt TEXT NOT NULL,
                        updatedAt TEXT NOT NULL,
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
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .build()
    }
}
