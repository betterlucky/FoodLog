import Foundation

/// Ordered, explicit schema migrations. Each entry moves the database from version `index` to
/// `index + 1`. Never edit a shipped migration: append a new one and add a migration test.
enum Schema {
    static let migrations: [String] = [
        // 0 -> 1: initial iOS schema. Mirrors Android Room schema 17 plus the iOS additions
        // (food row shortcut link, ml, protein/fibre with provenance, AI provenance,
        // shortcut protein/fibre, product ml basis).
        """
        CREATE TABLE raw_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            createdAt TEXT NOT NULL,
            logDate TEXT NOT NULL,
            consumedTime TEXT,
            rawText TEXT NOT NULL,
            entryKind TEXT NOT NULL,
            status TEXT NOT NULL,
            parseJson TEXT,
            notes TEXT
        );
        CREATE INDEX index_raw_entries_logDate_status ON raw_entries (logDate, status);

        CREATE TABLE products (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            brand TEXT,
            description TEXT,
            packageSizeGrams REAL,
            packageSizeMilliliters REAL,
            packageItemCount REAL,
            servingSizeGrams REAL,
            servingUnit TEXT,
            kcalPer100g REAL,
            kcalPer100ml REAL,
            kcalPerServing REAL,
            proteinPer100g REAL,
            carbsPer100g REAL,
            fatPer100g REAL,
            fiberPer100g REAL,
            sugarsPer100g REAL,
            saltPer100g REAL,
            source TEXT NOT NULL,
            confidence TEXT NOT NULL,
            lastLoggedGrams REAL,
            createdAt TEXT NOT NULL,
            archived INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE shortcuts (
            lookupKey TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            calories REAL NOT NULL,
            unit TEXT NOT NULL,
            notes TEXT,
            source TEXT NOT NULL,
            confidence TEXT NOT NULL,
            active INTEGER NOT NULL DEFAULT 1,
            defaultAmount REAL,
            portionMode TEXT NOT NULL DEFAULT 'PLAIN',
            itemUnit TEXT,
            itemSizeAmount REAL,
            itemSizeUnit TEXT,
            kcalPer100g REAL,
            kcalPer100ml REAL,
            nutritionBasisName TEXT,
            proteinPer100 REAL,
            fibrePer100 REAL,
            proteinPerPortion REAL,
            fibrePerPortion REAL
        );

        CREATE TABLE food_items (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            rawEntryId INTEGER NOT NULL REFERENCES raw_entries(id) ON DELETE RESTRICT,
            logDate TEXT NOT NULL,
            consumedTime TEXT,
            name TEXT NOT NULL,
            productId INTEGER REFERENCES products(id) ON DELETE SET NULL,
            shortcutKey TEXT,
            amount REAL,
            unit TEXT,
            grams REAL,
            milliliters REAL,
            calories REAL NOT NULL,
            proteinG REAL,
            fibreG REAL,
            nutrientSource TEXT,
            source TEXT NOT NULL,
            confidence TEXT NOT NULL,
            notes TEXT,
            aiContractVersion INTEGER,
            aiProvider TEXT,
            aiModel TEXT,
            createdAt TEXT NOT NULL,
            voided INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX index_food_items_rawEntryId ON food_items (rawEntryId);
        CREATE INDEX index_food_items_productId ON food_items (productId);
        CREATE INDEX index_food_items_logDate ON food_items (logDate);
        CREATE INDEX index_food_items_shortcutKey ON food_items (shortcutKey);

        CREATE TABLE daily_weights (
            logDate TEXT PRIMARY KEY NOT NULL,
            weightKg REAL NOT NULL,
            measuredTime TEXT NOT NULL,
            createdAt TEXT NOT NULL,
            updatedAt TEXT NOT NULL
        );

        CREATE TABLE app_settings (
            id INTEGER PRIMARY KEY NOT NULL CHECK (id = 1),
            dayBoundaryTime TEXT,
            lastLabelInputMode TEXT NOT NULL DEFAULT 'ITEMS',
            journalIncludeWeight INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE imports (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            importedAt TEXT NOT NULL,
            format TEXT NOT NULL,
            version INTEGER NOT NULL,
            sourcePlatform TEXT NOT NULL,
            exportedAt TEXT NOT NULL,
            summary TEXT NOT NULL
        );
        """,
    ]

    static var currentVersion: Int { migrations.count }

    static func migrate(_ connection: SQLiteConnection) throws {
        let version = connection.userVersion
        guard version <= currentVersion else {
            throw FoodLogStoreError.newerSchema(found: version, supported: currentVersion)
        }
        for target in version..<currentVersion {
            try connection.transaction {
                try connection.executeScript(migrations[target])
                try connection.setUserVersion(target + 1)
            }
        }
    }
}
