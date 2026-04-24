package com.betterlucky.foodlog.domain.intent

import org.junit.Assert.assertEquals
import org.junit.Test

class DeterministicIntentClassifierTest {
    private val classifier = DeterministicIntentClassifier()

    @Test
    fun classifiesFoodLogs() {
        assertEquals(EntryIntent.FOOD_LOG, classifier.classify("tea"))
        assertEquals(EntryIntent.FOOD_LOG, classifier.classify("apple"))
        assertEquals(EntryIntent.FOOD_LOG, classifier.classify("yesterday curry"))
    }

    @Test
    fun classifiesQueries() {
        assertEquals(EntryIntent.QUERY, classifier.classify("how am I doing today?"))
        assertEquals(EntryIntent.QUERY, classifier.classify("what have I eaten"))
    }

    @Test
    fun classifiesCorrectionsAndExports() {
        assertEquals(EntryIntent.CORRECTION, classifier.classify("actually that was 50g"))
        assertEquals(EntryIntent.CORRECTION, classifier.classify("delete the banana"))
        assertEquals(EntryIntent.EXPORT_COMMAND, classifier.classify("end of day"))
        assertEquals(EntryIntent.EXPORT_COMMAND, classifier.classify("export today"))
    }
}

