package com.betterlucky.foodlog.data.entities

enum class EntryKind {
    TEXT,
    PHOTO,
    CORRECTION,
    QUERY,
    EXPORT_COMMAND,
    NOTE,
    SYSTEM,
}

enum class RawEntryStatus {
    PENDING,
    PARSED,
    NEEDS_REVIEW,
    FAILED,
    IGNORED,
}

enum class ProductSource {
    PACKAGING_PHOTO,
    MANUAL,
    ESTIMATE,
}

enum class ProductPhotoStatus {
    PENDING,
    EXTRACTED,
    CONFIRMED,
    FAILED,
}

enum class ContainerStatus {
    OPEN,
    CLOSED,
    DISCARDED,
}

enum class FoodItemSource {
    SAVED_LABEL,
    ACTIVE_LEFTOVER,
    RECENT_PRODUCT,
    USER_DEFAULT,
    MANUAL_OVERRIDE,
    ESTIMATE,
}

enum class ShortcutPortionMode {
    PLAIN,
    ITEM,
    MEASURE,
}

enum class ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW,
}
