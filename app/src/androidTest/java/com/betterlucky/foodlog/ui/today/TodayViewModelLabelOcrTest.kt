package com.betterlucky.foodlog.ui.today

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.betterlucky.foodlog.data.db.FoodLogDatabase
import com.betterlucky.foodlog.data.ocr.LabelImageReader
import com.betterlucky.foodlog.data.ocr.LabelOcrResult
import com.betterlucky.foodlog.data.repository.FoodLogRepository
import com.betterlucky.foodlog.domain.intent.DeterministicIntentClassifier
import com.betterlucky.foodlog.domain.label.LabelNutritionFacts
import com.betterlucky.foodlog.domain.parser.DeterministicParser
import com.betterlucky.foodlog.util.DateTimeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TodayViewModelLabelOcrTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: FoodLogDatabase
    private lateinit var repository: FoodLogRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FoodLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FoodLogRepository(
            database = database,
            intentClassifier = DeterministicIntentClassifier(),
            parser = DeterministicParser(),
            dateTimeProvider = FixedDateTimeProvider(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun successfulLabelReadOpensLabelWizard() = runTest {
        val reader = QueueLabelImageReader()
        reader.enqueue(LabelOcrResult.Read(LabelNutritionFacts(rawText = "Energy 400 kcal per 100g", kcalPer100g = 400.0)))
        val viewModel = TodayViewModel(repository, reader)

        viewModel.processLabelImage(Uri.parse("content://labels/one"))
        advanceUntilIdle()

        val wizard = viewModel.loggingWizard.value
        assertEquals(LoggingWizardSource.Label, wizard?.source)
        assertEquals("Energy 400 kcal per 100g", wizard?.originalRawText)
        assertEquals("1g", wizard?.parts?.single()?.amount)
        assertEquals("4", wizard?.parts?.single()?.calories)
        assertNull(viewModel.labelReview.value)
    }

    @Test
    fun failedLabelReadClearsReviewAndShowsMessage() = runTest {
        val reader = QueueLabelImageReader()
        reader.enqueue(LabelOcrResult.Failed("No label text was found."))
        val viewModel = TodayViewModel(repository, reader)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        viewModel.processLabelImage(Uri.parse("content://labels/fail"))
        advanceUntilIdle()

        assertNull(viewModel.loggingWizard.value)
        assertNull(viewModel.labelReview.value)
        assertEquals("No label text was found.", viewModel.uiState.value.message)
    }

    @Test
    fun unavailableLabelReaderShowsMessage() = runTest {
        val viewModel = TodayViewModel(repository, labelOcrReader = null)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        viewModel.processLabelImage(Uri.parse("content://labels/missing-reader"))
        advanceUntilIdle()

        assertEquals("Label scan is not available on this device.", viewModel.uiState.value.message)
    }

    @Test
    fun newerLabelRequestWinsOverOlderPendingRead() = runTest {
        val reader = QueueLabelImageReader()
        val first = reader.enqueuePending()
        val second = reader.enqueuePending()
        val viewModel = TodayViewModel(repository, reader)

        viewModel.processLabelImage(Uri.parse("content://labels/first"))
        advanceUntilIdle()
        assertTrue(viewModel.labelReview.value?.isProcessing == true)

        viewModel.processLabelImage(Uri.parse("content://labels/second"))
        advanceUntilIdle()
        first.complete(LabelOcrResult.Read(LabelNutritionFacts(rawText = "first", kcalPer100g = 100.0)))
        advanceUntilIdle()
        assertNull(viewModel.loggingWizard.value)

        second.complete(LabelOcrResult.Read(LabelNutritionFacts(rawText = "second", kcalPer100g = 200.0)))
        advanceUntilIdle()

        assertEquals("second", viewModel.loggingWizard.value?.originalRawText)
    }

    @Test
    fun clearingLabelReviewPreventsPendingReadFromOpeningWizard() = runTest {
        val reader = QueueLabelImageReader()
        val pending = reader.enqueuePending()
        val viewModel = TodayViewModel(repository, reader)

        viewModel.processLabelImage(Uri.parse("content://labels/clear"))
        advanceUntilIdle()
        viewModel.clearLabelReview()
        pending.complete(LabelOcrResult.Read(LabelNutritionFacts(rawText = "late", kcalPer100g = 100.0)))
        advanceUntilIdle()

        assertNull(viewModel.labelReview.value)
        assertNull(viewModel.loggingWizard.value)
    }

    private class QueueLabelImageReader : LabelImageReader {
        private val results = ArrayDeque<CompletableDeferred<LabelOcrResult>>()

        fun enqueue(result: LabelOcrResult) {
            results += CompletableDeferred(result)
        }

        fun enqueuePending(): CompletableDeferred<LabelOcrResult> =
            CompletableDeferred<LabelOcrResult>().also { results += it }

        override suspend fun read(uri: Uri): LabelOcrResult =
            try {
                results.removeFirst().await()
            } catch (exception: CancellationException) {
                throw exception
            }
    }

    private class FixedDateTimeProvider : DateTimeProvider {
        override fun nowInstant(): Instant = Instant.parse("2026-04-24T11:30:00Z")
        override fun today(): LocalDate = LocalDate.parse("2026-04-24")
        override fun localTime(): LocalTime = LocalTime.parse("12:30")
    }
}
