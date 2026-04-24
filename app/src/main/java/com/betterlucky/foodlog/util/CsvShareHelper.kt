package com.betterlucky.foodlog.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.betterlucky.foodlog.BuildConfig
import java.io.File

class CsvShareHelper(
    private val context: Context,
) {
    fun shareCsv(
        csv: String,
        fileName: String,
    ) {
        val exportDir = File(context.cacheDir, "exports").apply {
            mkdirs()
        }
        val exportFile = File(exportDir, fileName).apply {
            writeText(csv)
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            exportFile,
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share FoodLog CSV"))
    }
}
