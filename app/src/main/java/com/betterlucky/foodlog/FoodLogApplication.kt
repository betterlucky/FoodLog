package com.betterlucky.foodlog

import android.app.Application
import com.betterlucky.foodlog.data.db.FoodLogDatabase
import com.betterlucky.foodlog.data.repository.FoodLogRepository
import com.betterlucky.foodlog.domain.intent.DeterministicIntentClassifier
import com.betterlucky.foodlog.domain.parser.DeterministicParser
import com.betterlucky.foodlog.util.SystemDateTimeProvider

class FoodLogApplication : Application() {
    val database: FoodLogDatabase by lazy {
        FoodLogDatabase.create(this)
    }

    val repository: FoodLogRepository by lazy {
        FoodLogRepository(
            database = database,
            intentClassifier = DeterministicIntentClassifier(),
            parser = DeterministicParser(),
            dateTimeProvider = SystemDateTimeProvider(),
        )
    }
}
