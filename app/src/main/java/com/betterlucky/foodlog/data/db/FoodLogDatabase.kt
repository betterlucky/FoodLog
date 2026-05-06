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
    version = 14,
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

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN barcode TEXT")
                db.execSQL("ALTER TABLE products ADD COLUMN packageSizeGrams REAL")
                db.execSQL("ALTER TABLE products ADD COLUMN externalUrl TEXT")
                db.execSQL("ALTER TABLE products ADD COLUMN lastSyncedAt TEXT")
                db.execSQL("ALTER TABLE products ADD COLUMN lastLoggedGrams REAL")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_barcode ON products(barcode)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN packageItemCount REAL")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("products", "fiberPer100g", "REAL")
                db.addColumnIfMissing("products", "sugarsPer100g", "REAL")
                db.addColumnIfMissing("products", "saltPer100g", "REAL")
                db.addColumnIfMissing("products", "servingUnit", "TEXT")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("products", "fiberPer100g", "REAL")
                db.addColumnIfMissing("products", "sugarsPer100g", "REAL")
                db.addColumnIfMissing("products", "saltPer100g", "REAL")
                db.addColumnIfMissing("products", "servingUnit", "TEXT")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate products table without barcode, externalUrl, lastSyncedAt columns
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `products_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `brand` TEXT,
                        `description` TEXT,
                        `containerType` TEXT,
                        `containerSizeGrams` REAL,
                        `packageSizeGrams` REAL,
                        `packageItemCount` REAL,
                        `servingSizeGrams` REAL,
                        `servingUnit` TEXT,
                        `kcalPer100g` REAL,
                        `kcalPerServing` REAL,
                        `proteinPer100g` REAL,
                        `carbsPer100g` REAL,
                        `fatPer100g` REAL,
                        `fiberPer100g` REAL,
                        `sugarsPer100g` REAL,
                        `saltPer100g` REAL,
                        `source` TEXT NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `lastLoggedGrams` REAL,
                        `createdAt` TEXT NOT NULL,
                        `archived` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `products_new`
                    (id, name, brand, description, containerType, containerSizeGrams,
                     packageSizeGrams, packageItemCount, servingSizeGrams, servingUnit,
                     kcalPer100g, kcalPerServing, proteinPer100g, carbsPer100g, fatPer100g,
                     fiberPer100g, sugarsPer100g, saltPer100g, source, confidence,
                     lastLoggedGrams, createdAt, archived)
                    SELECT id, name, brand, description, containerType, containerSizeGrams,
                     packageSizeGrams, packageItemCount, servingSizeGrams, servingUnit,
                     kcalPer100g, kcalPerServing, proteinPer100g, carbsPer100g, fatPer100g,
                     fiberPer100g, sugarsPer100g, saltPer100g, source, confidence,
                     lastLoggedGrams, createdAt, archived
                    FROM `products`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `products`")
                db.execSQL("ALTER TABLE `products_new` RENAME TO `products`")
                // Add defaultAmount to user_defaults for shortcut default quantity
                db.execSQL("ALTER TABLE user_defaults ADD COLUMN defaultAmount REAL")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing(
                    tableName = "app_settings",
                    columnName = "lastLabelInputMode",
                    declaration = "TEXT NOT NULL DEFAULT 'ITEMS'",
                )
            }
        }

        // Recreates app_settings so the DDL matches the Room-generated schema after
        // @ColumnInfo(defaultValue = "ITEMS") was added to lastLabelInputMode without
        // a version bump in the prior commit (identity hash mismatch on existing installs).
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_settings_new` (
                        `id` INTEGER NOT NULL,
                        `dayBoundaryTime` TEXT,
                        `lastLabelInputMode` TEXT NOT NULL DEFAULT 'ITEMS',
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT INTO `app_settings_new` (`id`, `dayBoundaryTime`, `lastLabelInputMode`) SELECT `id`, `dayBoundaryTime`, `lastLabelInputMode` FROM `app_settings`",
                )
                db.execSQL("DROP TABLE `app_settings`")
                db.execSQL("ALTER TABLE `app_settings_new` RENAME TO `app_settings`")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing(
                    tableName = "user_defaults",
                    columnName = "portionMode",
                    declaration = "TEXT NOT NULL DEFAULT 'PLAIN'",
                )
                db.addColumnIfMissing("user_defaults", "itemUnit", "TEXT")
                db.addColumnIfMissing("user_defaults", "itemSizeAmount", "REAL")
                db.addColumnIfMissing("user_defaults", "itemSizeUnit", "TEXT")
                db.addColumnIfMissing("user_defaults", "kcalPer100g", "REAL")
                db.addColumnIfMissing("user_defaults", "kcalPer100ml", "REAL")
                db.addColumnIfMissing("user_defaults", "nutritionBasisName", "TEXT")
                db.execSQL("UPDATE user_defaults SET portionMode = 'PLAIN' WHERE portionMode IS NULL OR portionMode = ''")
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
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .addMigrations(MIGRATION_8_9)
                .addMigrations(MIGRATION_9_10)
                .addMigrations(MIGRATION_10_11)
                .addMigrations(MIGRATION_11_12)
                .addMigrations(MIGRATION_12_13)
                .addMigrations(MIGRATION_13_14)
                .build()
    }
}

private fun SupportSQLiteDatabase.addColumnIfMissing(tableName: String, columnName: String, declaration: String) {
    query("PRAGMA table_info($tableName)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == columnName) {
                return
            }
        }
    }
    execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $declaration")
}
