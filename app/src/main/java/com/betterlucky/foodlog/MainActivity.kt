package com.betterlucky.foodlog

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.betterlucky.foodlog.ui.today.TodayScreen
import com.betterlucky.foodlog.ui.today.TodayViewModel
import com.betterlucky.foodlog.ui.today.TodayViewModelFactory
import com.betterlucky.foodlog.util.CsvShareHelper

class MainActivity : ComponentActivity() {
    private val viewModel: TodayViewModel by viewModels {
        TodayViewModelFactory((application as FoodLogApplication).repository)
    }
    private val csvShareHelper: CsvShareHelper by lazy {
        CsvShareHelper(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = FoodLogColorScheme) {
                TodayScreen(
                    viewModel = viewModel,
                    onShareCsv = ::shareCsv,
                )
            }
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
