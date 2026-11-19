# Android Smoke Scenario

Use this after `scripts/android-smoke.sh` installs and launches the debug app. The script must preserve app data during ordinary reinstalls and must use its guarded backup/restore path for signature mismatches.

```sh
scripts/android-smoke.sh /tmp/foodlog-smoke.png
```

## Lodestone Daily Close Pass

Goal: prove the main daily handoff works from Room-backed app state, not from rendered UI text or stale CSV files.

1. Open today in FoodLog after the smoke script launches the app.
2. Tap **Scan label**.
3. Use **Take photo** or **Choose image** with a nutrition label that the local OCR reader can parse. If OCR is not available on the device, record that as the smoke blocker in the task notes or PR rather than substituting a plain manual food row.
4. Complete **Log from label** with a clear product name, a valid portion, and calories.
5. Confirm the food row appears in today's list and the day summary total includes it.
6. Add one daily weight from the weight control.
7. Confirm the day summary shows weight saved.
8. Use the daily close **Export daily report** action.
9. Confirm the app reports the daily report as current after export.
10. Confirm the Lodestone CSV exists in `Downloads/FoodLogData` as `food_log_YYYY-MM-DD.csv`. This matches `CsvShareHelper.EXPORT_RELATIVE_PATH`.
11. Confirm the CSV contains the label-derived food row and the daily weight row.

Expected result: the day shows exported/current, the exported file is current for that day, and no app-private data was wiped during install or reinstall.

Do not use a plain `adb uninstall com.betterlucky.foodlog` to fix install problems. If the smoke script refuses to continue because it cannot back up app data, stop and investigate the backup path instead of wiping local FoodLog data. Use `FOODLOG_ALLOW_DATA_LOSS=1` only when wiping local FoodLog data has been explicitly accepted for that device.
