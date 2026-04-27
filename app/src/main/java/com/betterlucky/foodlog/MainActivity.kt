package com.betterlucky.foodlog

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
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
            MaterialTheme {
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
