package com.betterlucky.foodlog.domain.barcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarcodeNormalizerTest {
    @Test
    fun plainBarcodeIsAccepted() {
        assertEquals("5010525092980", BarcodeNormalizer.normalize("5010525092980"))
    }

    @Test
    fun barcodeWithWhitespaceIsCompacted() {
        assertEquals("5010525092980", BarcodeNormalizer.normalize(" 5010 5250 92980 "))
    }

    @Test
    fun openFoodFactsApiUrlExtractsBarcode() {
        val rawValue = "https://world.openfoodfacts.org/api/v2/product/5010525092980.json?fields=code,product_name,brands"

        assertEquals("5010525092980", BarcodeNormalizer.normalize(rawValue))
    }

    @Test
    fun openFoodFactsProductUrlExtractsBarcode() {
        val rawValue = "https://world.openfoodfacts.org/product/5010525092980/example-product"

        assertEquals("5010525092980", BarcodeNormalizer.normalize(rawValue))
    }

    @Test
    fun gs1DigitalLinkExtractsBarcode() {
        val rawValue = "https://id.gs1.org/01/05010525092980/10/ABC123"

        assertEquals("05010525092980", BarcodeNormalizer.normalize(rawValue))
    }

    @Test
    fun unsupportedTextIsRejected() {
        assertNull(BarcodeNormalizer.normalize("not a barcode"))
    }
}
