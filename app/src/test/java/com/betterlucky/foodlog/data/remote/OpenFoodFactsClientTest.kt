package com.betterlucky.foodlog.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFoodFactsClientTest {
    private val client = OpenFoodFactsClient()

    @Test
    fun productNotFoundJsonMapsToNotFound() {
        val result = client.parseLookupResponse(
            barcode = "5010525092980",
            body = """{"code":"5010525092980","status":0,"status_verbose":"product not found"}""",
        )

        assertEquals(OpenFoodFactsLookupResult.NotFound, result)
    }

    @Test
    fun productJsonMapsSelectedFields() {
        val result = client.parseLookupResponse(
            barcode = "12345678",
            body = """
                {
                  "status": 1,
                  "product": {
                    "code": "12345678",
                    "product_name": "Test yoghurt",
                    "brands": "Example Brand",
                    "quantity": "450 g",
                    "serving_size": "150g",
                    "nutriments": {
                      "energy-kcal_100g": 95,
                      "proteins_100g": 4.2,
                      "carbohydrates_100g": 11.5,
                      "fat_100g": 3.1
                    },
                    "url": "https://world.openfoodfacts.org/product/12345678/test-yoghurt"
                  }
                }
            """.trimIndent(),
        )

        assertTrue(result is OpenFoodFactsLookupResult.Found)
        val product = (result as OpenFoodFactsLookupResult.Found).product
        assertEquals("12345678", product.barcode)
        assertEquals("Test yoghurt", product.name)
        assertEquals("Example Brand", product.brand)
        assertEquals(450.0, product.packageSizeGrams ?: 0.0, 0.001)
        assertEquals(150.0, product.servingSizeGrams ?: 0.0, 0.001)
        assertEquals(95.0, product.kcalPer100g ?: 0.0, 0.001)
        assertEquals(142.5, product.kcalPerServing ?: 0.0, 0.001)
    }
}
