package com.betterlucky.foodlog

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import com.betterlucky.foodlog.data.ocr.LabelOcrReader
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.betterlucky.foodlog.ui.today.TodayScreen
import com.betterlucky.foodlog.ui.today.TodayViewModel
import com.betterlucky.foodlog.ui.today.TodayViewModelFactory
import com.betterlucky.foodlog.util.CsvShareHelper
import java.io.File

class MainActivity : ComponentActivity() {
    private val labelOcrReader: LabelOcrReader by lazy {
        LabelOcrReader(this)
    }
    private val viewModel: TodayViewModel by viewModels {
        TodayViewModelFactory((application as FoodLogApplication).repository, labelOcrReader)
    }
    private val csvShareHelper: CsvShareHelper by lazy {
        CsvShareHelper(this)
    }
    private var pendingLabelPhotoUri: Uri? = null
    private val takeLabelPhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingLabelPhotoUri
        pendingLabelPhotoUri = null
        if (success && uri != null) {
            readLabelImage(uri)
        } else {
            Toast.makeText(this, "No label photo was captured.", Toast.LENGTH_SHORT).show()
        }
    }
    private val chooseLabelImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            readLabelImage(uri)
        } else {
            Toast.makeText(this, "No label image was selected.", Toast.LENGTH_SHORT).show()
        }
    }
    private val chooseJournalFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val persisted = runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
            if (!persisted) {
                Toast.makeText(this, "Could not keep access to that file. Please choose it again.", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            viewModel.saveJournalExportFile(
                uri = uri.toString(),
                displayName = displayNameFor(uri),
            )
        } else {
            Toast.makeText(this, "No journal file was selected.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = FoodLogColorScheme) {
                TodayScreen(
                    viewModel = viewModel,
                    onShareCsv = ::shareCsv,
                    onChooseJournalFile = ::chooseJournalFile,
                    onSaveJournalCsv = ::saveJournalCsv,
                    onTakeLabelPhoto = ::takeLabelPhoto,
                    onChooseLabelImage = ::chooseLabelImage,
                )
            }
        }
    }

    private fun takeLabelPhoto() {
        val directory = File(cacheDir, "label-images").apply { mkdirs() }
        val file = File(directory, "label-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        pendingLabelPhotoUri = uri
        takeLabelPhotoLauncher.launch(uri)
    }

    private fun chooseLabelImage() {
        chooseLabelImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    private fun readLabelImage(uri: Uri) {
        viewModel.processLabelImage(uri)
    }

    private fun chooseJournalFile() {
        chooseJournalFileLauncher.launch("foodlog_journal.csv")
    }

    private fun displayNameFor(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else {
                    null
                }
            }

    private fun shareCsv(
        csv: String,
        fileName: String,
    ): String {
        val path = csvShareHelper.saveCsv(csv, fileName)
        Toast.makeText(this, "Saved $path", Toast.LENGTH_LONG).show()
        return path
    }

    private fun saveJournalCsv(
        csv: String,
        fileName: String,
        uriString: String,
    ): String {
        val uri = Uri.parse(uriString)
        contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(csv.toByteArray(Charsets.UTF_8))
        } ?: error("Unable to open journal export file")
        Toast.makeText(this, "Saved $fileName", Toast.LENGTH_LONG).show()
        return uriString
    }
}

private val FoodLogColorScheme = lightColorScheme(
    primary = Color(0xFF006B5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC7F0E6),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF7A5D00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE28A),
    onSecondaryContainer = Color(0xFF251A00),
    tertiary = Color(0xFF3D5F8C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD5E3FF),
    onTertiaryContainer = Color(0xFF001B3B),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAFCF8),
    onBackground = Color(0xFF181D1A),
    surface = Color(0xFFFAFCF8),
    onSurface = Color(0xFF181D1A),
    surfaceVariant = Color(0xFFDDE5DF),
    onSurfaceVariant = Color(0xFF414942),
)
