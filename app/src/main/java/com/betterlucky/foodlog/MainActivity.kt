package com.betterlucky.foodlog

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.betterlucky.foodlog.data.ocr.LabelOcrReader
import com.betterlucky.foodlog.data.ocr.LabelOcrResult
import com.betterlucky.foodlog.domain.label.LabelNutritionFacts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.betterlucky.foodlog.ui.today.TodayScreen
import com.betterlucky.foodlog.ui.today.TodayViewModel
import com.betterlucky.foodlog.ui.today.TodayViewModelFactory
import com.betterlucky.foodlog.util.CsvShareHelper
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: TodayViewModel by viewModels {
        TodayViewModelFactory((application as FoodLogApplication).repository)
    }
    private val csvShareHelper: CsvShareHelper by lazy {
        CsvShareHelper(this)
    }
    private val labelOcrReader: LabelOcrReader by lazy {
        LabelOcrReader(this)
    }
    private var pendingLabelCallbacks: Pair<(LabelNutritionFacts) -> Unit, (String) -> Unit>? = null
    private var pendingLabelPhotoUri: Uri? = null
    private val takeLabelPhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingLabelPhotoUri
        if (success && uri != null) {
            readLabelImage(uri)
        } else {
            pendingLabelCallbacks?.second?.invoke("No label photo was captured.")
        }
        pendingLabelPhotoUri = null
    }
    private val chooseLabelImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            readLabelImage(uri)
        } else {
            pendingLabelCallbacks?.second?.invoke("No label image was selected.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = FoodLogColorScheme) {
                TodayScreen(
                    viewModel = viewModel,
                    onShareCsv = ::shareCsv,
                    onScanBarcode = ::scanBarcode,
                    onTakeLabelPhoto = ::takeLabelPhoto,
                    onChooseLabelImage = ::chooseLabelImage,
                )
            }
        }
    }

    private fun takeLabelPhoto(
        onRead: (LabelNutritionFacts) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        pendingLabelCallbacks = onRead to onFailed
        val directory = File(cacheDir, "label-images").apply { mkdirs() }
        val file = File(directory, "label-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        pendingLabelPhotoUri = uri
        takeLabelPhotoLauncher.launch(uri)
    }

    private fun chooseLabelImage(
        onRead: (LabelNutritionFacts) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        pendingLabelCallbacks = onRead to onFailed
        chooseLabelImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    private fun readLabelImage(uri: Uri) {
        lifecycleScope.launch {
            when (val result = labelOcrReader.read(uri)) {
                is LabelOcrResult.Read -> {
                    pendingLabelCallbacks?.first?.invoke(result.facts)
                    Toast.makeText(this@MainActivity, "Label read; check values before logging", Toast.LENGTH_LONG).show()
                }
                is LabelOcrResult.Failed -> {
                    pendingLabelCallbacks?.second?.invoke(result.message)
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
            pendingLabelCallbacks = null
        }
    }

    private fun scanBarcode(
        onScanned: (String) -> Unit,
        onUnavailable: (String) -> Unit,
    ) {
        GmsBarcodeScanning.getClient(this)
            .startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue
                if (rawValue.isNullOrBlank()) {
                    onUnavailable("No barcode was read.")
                } else {
                    onScanned(rawValue)
                }
            }
            .addOnFailureListener { exception ->
                onUnavailable(exception.message ?: "Scanner unavailable. Enter the barcode manually.")
            }
    }

    private fun shareCsv(
        csv: String,
        fileName: String,
    ) {
        val path = csvShareHelper.saveCsv(csv, fileName)
        Toast.makeText(this, "Saved $path", Toast.LENGTH_LONG).show()
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
