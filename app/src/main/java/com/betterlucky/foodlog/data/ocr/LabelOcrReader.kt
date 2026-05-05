package com.betterlucky.foodlog.data.ocr

import android.content.Context
import android.net.Uri
import com.betterlucky.foodlog.domain.label.LabelNutritionFacts
import com.betterlucky.foodlog.domain.label.LabelNutritionParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class LabelOcrReader(
    private val context: Context,
    private val parser: LabelNutritionParser = LabelNutritionParser(),
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(uri: Uri): LabelOcrResult =
        withContext(Dispatchers.IO) {
            try {
                val image = InputImage.fromFilePath(context, uri)
                val text = recognizer.process(image).await().text
                if (text.isBlank()) {
                    LabelOcrResult.Failed("No label text was found.")
                } else {
                    LabelOcrResult.Read(parser.parse(text))
                }
            } catch (exception: Exception) {
                LabelOcrResult.Failed(exception.message ?: "Label text could not be read.")
            }
        }
}

sealed interface LabelOcrResult {
    data class Read(val facts: LabelNutritionFacts) : LabelOcrResult
    data class Failed(val message: String) : LabelOcrResult
}
