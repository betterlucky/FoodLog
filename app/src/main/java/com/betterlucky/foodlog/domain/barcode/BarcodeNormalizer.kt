package com.betterlucky.foodlog.domain.barcode

object BarcodeNormalizer {
    private val openFoodFactsProductPath = Regex(
        pattern = """/(?:api/v\d+/)?product/(\d{6,18})(?:\.json)?(?:[/?#].*)?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val gs1DigitalLinkPath = Regex(
        pattern = """/01/(\d{6,18})(?:[/?#].*)?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val digitsOnly = Regex("""^\d{6,18}$""")

    fun normalize(rawValue: String): String? {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val compactDigits = trimmed.filter(Char::isDigit)
        if (compactDigits.length == trimmed.count { !it.isWhitespace() } && digitsOnly.matches(compactDigits)) {
            return compactDigits
        }

        openFoodFactsProductPath.find(trimmed)?.let { match ->
            return match.groupValues[1]
        }

        gs1DigitalLinkPath.find(trimmed)?.let { match ->
            return match.groupValues[1]
        }

        return null
    }
}
