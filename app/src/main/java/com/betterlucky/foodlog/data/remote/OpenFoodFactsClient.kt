package com.betterlucky.foodlog.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class OpenFoodFactsProduct(
    val barcode: String,
    val name: String?,
    val brand: String?,
    val packageSizeGrams: Double?,
    val servingSizeGrams: Double?,
    val kcalPer100g: Double?,
    val kcalPerServing: Double?,
    val proteinPer100g: Double?,
    val carbsPer100g: Double?,
    val fatPer100g: Double?,
    val url: String?,
)

sealed interface OpenFoodFactsLookupResult {
    data class Found(val product: OpenFoodFactsProduct) : OpenFoodFactsLookupResult
    data object NotFound : OpenFoodFactsLookupResult
    data class Failed(val message: String) : OpenFoodFactsLookupResult
}

class OpenFoodFactsClient {
    suspend fun lookup(barcode: String): OpenFoodFactsLookupResult =
        withContext(Dispatchers.IO) {
            val encodedBarcode = URLEncoder.encode(barcode, StandardCharsets.UTF_8.name())
            val fields = listOf(
                "code",
                "product_name",
                "brands",
                "quantity",
                "serving_size",
                "serving_quantity",
                "nutriments",
                "url",
            ).joinToString(",")
            val url = URL("https://world.openfoodfacts.org/api/v2/product/$encodedBarcode.json?fields=$fields")
            try {
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", "FoodLog/0.1.0 Android barcode lookup")
                    setRequestProperty("Accept", "application/json")
                }
                connection.inputStream.use { stream ->
                    val body = stream.bufferedReader().readText()
                    parseLookupResponse(barcode = barcode, body = body)
                }
            } catch (exception: IOException) {
                OpenFoodFactsLookupResult.Failed(exception.message ?: "Open Food Facts is unavailable.")
            } catch (exception: RuntimeException) {
                OpenFoodFactsLookupResult.Failed(exception.message ?: "Open Food Facts response could not be read.")
            }
        }

    private fun parseLookupResponse(
        barcode: String,
        body: String,
    ): OpenFoodFactsLookupResult {
        val root = JSONObject(body)
        if (root.optInt("status", 0) != 1) {
            return OpenFoodFactsLookupResult.NotFound
        }

        val product = root.optJSONObject("product")
            ?: return OpenFoodFactsLookupResult.NotFound
        val nutriments = product.optJSONObject("nutriments")
        val kcalPer100g = nutriments?.optNullableDouble("energy-kcal_100g")
            ?: nutriments?.optNullableDouble("energy_100g")?.let { it / 4.184 }
        val servingSizeGrams = product.optNullableDouble("serving_quantity")
            ?: product.optStringOrNull("serving_size")?.parseGrams()

        return OpenFoodFactsLookupResult.Found(
            OpenFoodFactsProduct(
                barcode = product.optStringOrNull("code") ?: barcode,
                name = product.optStringOrNull("product_name"),
                brand = product.optStringOrNull("brands"),
                packageSizeGrams = product.optStringOrNull("quantity")?.parseGrams(),
                servingSizeGrams = servingSizeGrams,
                kcalPer100g = kcalPer100g,
                kcalPerServing = servingSizeGrams?.let { grams ->
                    kcalPer100g?.let { kcal -> kcal * grams / 100.0 }
                },
                proteinPer100g = nutriments?.optNullableDouble("proteins_100g"),
                carbsPer100g = nutriments?.optNullableDouble("carbohydrates_100g"),
                fatPer100g = nutriments?.optNullableDouble("fat_100g"),
                url = product.optStringOrNull("url"),
            ),
        )
    }
}

private fun JSONObject.optStringOrNull(name: String): String? =
    optString(name).trim().ifBlank { null }

private fun JSONObject.optNullableDouble(name: String): Double? =
    if (has(name) && !isNull(name)) {
        optDouble(name).takeIf { !it.isNaN() && it > 0.0 }
    } else {
        null
    }

private fun String.parseGrams(): Double? {
    val normalized = lowercase().replace(",", ".")
    val match = Regex("""(\d+(?:\.\d+)?)\s*(kg|g|ml|l)\b""").find(normalized)
        ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    return when (match.groupValues[2]) {
        "kg", "l" -> value * 1000.0
        "g", "ml" -> value
        else -> null
    }
}
