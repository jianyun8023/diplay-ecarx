package com.shilapi.xcertplay.airplay

internal fun kotlinFindIphoneCornerMask(value: Any?, depth: Int = 0): ByteArray? {
    if (depth > 6) return null
    return when (value) {
        is Map<*, *> -> {
            val candidate = value["topLeftCornerMask"] as? ByteArray
            if (candidate != null) candidate
            else value.values.firstNotNullOfOrNull { kotlinFindIphoneCornerMask(it, depth + 1) }
        }
        is List<*> -> value.firstNotNullOfOrNull { kotlinFindIphoneCornerMask(it, depth + 1) }
        else -> null
    }
}
