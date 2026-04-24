package com.betterlucky.foodlog

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.betterlucky.foodlog.ui.today.TodayScreen
import com.betterlucky.foodlog.ui.today.TodayViewModel
import com.betterlucky.foodlog.ui.today.TodayViewModelFactory

class MainActivity : ComponentActivity() {
    private val viewModel: TodayViewModel by viewModels {
        TodayViewModelFactory((application as FoodLogApplication).repository)
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

    private fun shareCsv(csv: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "FoodLog export")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(intent, "Share FoodLog CSV"))
    }
}
