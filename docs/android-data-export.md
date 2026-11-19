# Android Data Export

The iOS app imports existing Android history from a versioned JSON file. The file is built on the Mac from a pulled copy of the Android Room database, so the frozen Android app needs no new code.

## Making an export

With the Android phone connected over USB:

```sh
scripts/android-backup-app-data.sh device-backups/foodlog-android-YYYY-MM-DD.tar
mkdir -p device-backups/android-YYYY-MM-DD
tar -xf device-backups/foodlog-android-YYYY-MM-DD.tar -C device-backups/android-YYYY-MM-DD
scripts/export-android-data.py device-backups/android-YYYY-MM-DD/databases/foodlog.db device-backups/foodlog-export-YYYY-MM-DD.json
```

The backup is read-only on the phone. The script opens the database read-only, refuses Room schema versions it does not know, and checks that every food row's raw entry and product exist. Output stays in the ignored `device-backups/` folder. It contains personal food history: never commit it.

## Format (version 1)

Top level:

- `format`: always `"foodlog-export"`.
- `version`: `1`.
- `exportedAt`: UTC timestamp.
- `source`: `{ "platform": "android", "roomSchemaVersion": 17 }`.
- `settings`: `{ "dayBoundaryTime": "HH:mm" | null }`.
- `shortcuts`, `products`, `rawEntries`, `foodItems`, `dailyWeights`: arrays of rows.

Each row keeps its Room column names and values exactly, except that `active`, `voided`, and `archived` become JSON booleans. Dates are `YYYY-MM-DD`, times are `HH:mm`, and instants are ISO-8601 UTC strings. Enum columns (`source`, `confidence`, `portionMode`, `entryKind`, `status`) keep the Android enum names listed in `app/src/main/java/com/betterlucky/foodlog/data/entities/Enums.kt`.

Containers, product photos, and daily export status are not exported: the first two are unused and the last is tied to the Android-only Lodestone export.

Importers must treat the file as read-only input, import inside one transaction, and refuse unknown `format` or `version` values.
