#!/usr/bin/env python3
"""Convert a pulled Android FoodLog Room database into the versioned FoodLog JSON export.

Usage:
    scripts/android-backup-app-data.sh device-backups/foodlog-android.tar
    tar -xf device-backups/foodlog-android.tar -C device-backups/android
    scripts/export-android-data.py device-backups/android/databases/foodlog.db device-backups/foodlog-export.json

The database is opened read-only. Output contains personal food history: keep it under the
ignored device-backups/ folder and never commit it.
"""
import argparse
import json
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

FORMAT = "foodlog-export"
VERSION = 1
SUPPORTED_SCHEMA_VERSIONS = {17}

BOOLEAN_COLUMNS = {"active", "voided", "archived", "journalIncludeWeight"}

TABLES = {
    "shortcuts": ("user_defaults", "lookupKey"),
    "products": ("products", "id"),
    "rawEntries": ("raw_entries", "id"),
    "foodItems": ("food_items", "id"),
    "dailyWeights": ("daily_weights", "logDate"),
}


def rows(connection: sqlite3.Connection, table: str, order_by: str) -> list[dict]:
    cursor = connection.execute(f"SELECT * FROM `{table}` ORDER BY `{order_by}`")
    columns = [description[0] for description in cursor.description]
    result = []
    for values in cursor.fetchall():
        row = {}
        for column, value in zip(columns, values):
            if column in BOOLEAN_COLUMNS and value is not None:
                value = bool(value)
            row[column] = value
        result.append(row)
    return result


def build_export(database: Path) -> dict:
    connection = sqlite3.connect(f"file:{database}?mode=ro", uri=True)
    try:
        schema_version = connection.execute("PRAGMA user_version").fetchone()[0]
        if schema_version not in SUPPORTED_SCHEMA_VERSIONS:
            raise SystemExit(
                f"Unsupported Room schema version {schema_version}; expected one of {sorted(SUPPORTED_SCHEMA_VERSIONS)}"
            )
        settings_rows = rows(connection, "app_settings", "id")
        settings = settings_rows[0] if settings_rows else {}
        export = {
            "format": FORMAT,
            "version": VERSION,
            "exportedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
            "source": {"platform": "android", "roomSchemaVersion": schema_version},
            "settings": {"dayBoundaryTime": settings.get("dayBoundaryTime")},
        }
        for key, (table, order_by) in TABLES.items():
            export[key] = rows(connection, table, order_by)
    finally:
        connection.close()
    check_references(export)
    return export


def check_references(export: dict) -> None:
    raw_ids = {row["id"] for row in export["rawEntries"]}
    product_ids = {row["id"] for row in export["products"]}
    for item in export["foodItems"]:
        if item["rawEntryId"] not in raw_ids:
            raise SystemExit(f"Food item {item['id']} references missing raw entry {item['rawEntryId']}")
        if item["productId"] is not None and item["productId"] not in product_ids:
            raise SystemExit(f"Food item {item['id']} references missing product {item['productId']}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("database", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    export = build_export(args.database)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(json.dumps(export, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(args.output)
    counts = ", ".join(f"{key}={len(export[key])}" for key in TABLES)
    print(f"Wrote {args.output} ({counts})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
