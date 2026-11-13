package com.shihab.diplay.media

/** Per-track navigation attenuation; never changes the head unit's system stream volume. */
object NavigationAudioGain {
    const val MIN_DB = -20
    const val MAX_DB = 0

    fun clamp(db: Int): Int = db.coerceIn(MIN_DB, MAX_DB)
}
