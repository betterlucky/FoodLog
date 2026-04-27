package com.betterlucky.foodlog.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

class CsvShareHelper(
    private val context: Context,
) {
    fun saveCsv(
        csv: String,
        fileName: String,
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = existingDownloadUri(fileName) ?: createDownloadUri(fileName)
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(csv.toByteArray(Charsets.UTF_8))
            } ?: error("Unable to open export file")

            return "$EXPORT_RELATIVE_PATH$fileName"
        }

        val exportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            EXPORT_SUBDIRECTORY,
        ).apply {
            mkdirs()
        }
        File(exportDir, fileName).apply {
            writeText(csv)
        }

        return "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_SUBDIRECTORY/$fileName"
    }

    private fun createDownloadUri(fileName: String): Uri =
        context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, EXPORT_RELATIVE_PATH)
            },
        ) ?: error("Unable to create export file")

    private fun existingDownloadUri(fileName: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(fileName, EXPORT_RELATIVE_PATH)

        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            } else {
                null
            }
        }
    }

    companion object {
        private const val EXPORT_SUBDIRECTORY = "FoodLogData"
        private val EXPORT_RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_SUBDIRECTORY/"
    }
}
