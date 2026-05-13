package com.betterlucky.foodlog.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.betterlucky.foodlog.data.entities.DailyStatusEntity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

class DailyClosePromptTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val defaultTimeZone = TimeZone.getDefault()

    @Before
    fun setTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(defaultTimeZone)
    }

    @Test
    fun exportedDayShowsLastExportAuditLine() {
        composeRule.setContent {
            MaterialTheme {
                DailyClosePrompt(
                    dailyStatus = DailyStatusEntity(
                        logDate = LocalDate.parse("2026-05-06"),
                        legacyExportedAt = Instant.parse("2026-05-06T20:00:00Z"),
                        lastFoodChangedAt = Instant.parse("2026-05-06T19:59:00Z"),
                        legacyExportFileName = "food_log_2026-05-06.csv",
                    ),
                    pendingCount = 0,
                    foodItemCount = 1,
                    hasDailyWeight = false,
                    onExportLegacy = {},
                )
            }
        }

        composeRule
            .onNodeWithText("Lodestone: exported 21:00")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Last exported: 21:00 - food_log_2026-05-06.csv")
            .assertIsDisplayed()
    }

    @Test
    fun staleExportShowsLastExportAuditLineAndExportAction() {
        composeRule.setContent {
            MaterialTheme {
                DailyClosePrompt(
                    dailyStatus = DailyStatusEntity(
                        logDate = LocalDate.parse("2026-05-06"),
                        legacyExportedAt = Instant.parse("2026-05-06T20:00:00Z"),
                        lastFoodChangedAt = Instant.parse("2026-05-06T20:01:00Z"),
                        legacyExportFileName = "food_log_2026-05-06.csv",
                    ),
                    pendingCount = 0,
                    foodItemCount = 1,
                    hasDailyWeight = false,
                    onExportLegacy = {},
                )
            }
        }

        composeRule
            .onNodeWithText("Lodestone: needs re-export: changed 21:01 after 21:00 export")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Last exported: 21:00 - food_log_2026-05-06.csv")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Re-export Lodestone CSV")
            .assertIsDisplayed()
    }

    @Test
    fun unexportedDayDoesNotShowLastExportAuditLine() {
        composeRule.setContent {
            MaterialTheme {
                DailyClosePrompt(
                    dailyStatus = null,
                    pendingCount = 0,
                    foodItemCount = 1,
                    hasDailyWeight = false,
                    onExportLegacy = {},
                )
            }
        }

        composeRule
            .onNodeWithText("Lodestone: not exported")
            .assertIsDisplayed()
        composeRule
            .onAllNodesWithText("Last exported", substring = true)
            .assertCountEquals(0)
        composeRule
            .onNodeWithText("Export Lodestone CSV")
            .assertIsDisplayed()
    }
}
