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
    val packageItemCount: Double?,
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
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.use { it.bufferedReader().readText() }.orEmpty()
                when {
                    responseCode == HttpURLConnection.HTTP_NOT_FOUND -> OpenFoodFactsLookupResult.NotFound
                    responseCode in 200..299 -> parseLookupResponse(barcode = barcode, body = body)
                    else -> OpenFoodFactsLookupResult.Failed("Open Food Facts returned HTTP $responseCode.")
                }
            } catch (exception: IOException) {
                OpenFoodFactsLookupResult.Failed("Open Food Facts is unavailable.")
            } catch (exception: RuntimeException) {
                OpenFoodFactsLookupResult.Failed(exception.message ?: "Open Food Facts response could not be read.")
            }
        }

    internal fun parseLookupResponse(
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
        val packageQuantity = product.optStringOrNull("quantity")?.parsePackageQuantity()
        val servingSizeGrams = product.optNullableDouble("serving_quantity")
            ?: product.optStringOrNull("serving_size")?.parseGrams()

        return OpenFoodFactsLookupResult.Found(
            OpenFoodFactsProduct(
                barcode = product.optStringOrNull("code") ?: barcode,
                name = product.optStringOrNull("product_name"),
                brand = product.optStringOrNull("brands"),
                packageSizeGrams = packageQuantity?.grams,
                packageItemCount = packageQuantity?.itemCount,
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

private data class PackageQuantityInfo(
    val grams: Double?,
    val itemCount: Double?,
)

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
    return value.toGrams(match.groupValues[2])
}

private fun String.parsePackageQuantity(): PackageQuantityInfo {
    val normalized = lowercase().replace(",", ".")
    val multiplierMatch = Regex("""(\d+(?:\.\d+)?)\s*[x×]\s*(\d+(?:\.\d+)?)\s*(kg|g|ml|l)\b""")
        .find(normalized)
    if (multiplierMatch != null) {
        val count = multiplierMatch.groupValues[1].toDoubleOrNull()
        val itemValue = multiplierMatch.groupValues[2].toDoubleOrNull()
        val unit = multiplierMatch.groupValues[3]
        val itemGrams = itemValue?.toGrams(unit)
        return PackageQuantityInfo(
            grams = if (count != null && itemGrams != null) count * itemGrams else parseGrams(),
            itemCount = count?.takeIf { it > 0.0 },
        )
    }

    val grams = parseGrams()
    val itemCount = Regex("""(?:^|\b)(\d+(?:\.\d+)?)\s*(?:sausages?|bars?|pieces?|packs?|portions?|servings?|slices?|items?)\b""")
        .find(normalized)
        ?.groupValues
        ?.get(1)
        ?.toDoubleOrNull()
        ?.takeIf { it > 0.0 }
    return PackageQuantityInfo(grams = grams, itemCount = itemCount)
}

private fun Double.toGrams(unit: String): Double? =
    when (unit) {
        "kg", "l" -> this * 1000.0
        "g", "ml" -> this
        else -> null
    }
