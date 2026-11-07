package com.shilapi.xcertplay.media

import android.content.Context
import android.provider.Settings
import kotlin.math.roundToInt

/**
 * The car's own navigation and media volume, as CarAudioService keeps them in Settings.Global.
 * A prompt mixed into the music stream plays at the music's volume; scaling it by the difference
 * keeps the car's navigation volume setting in charge of it. The PCM can only be lowered, so a
 * navigation volume above the music's plays at the music's level.
 *
 * ECARX dcy11: group 0 is media and group 1 navigation, each in steps of 150 millibel from -4800
 * (step 0) to +1050 (step 39), per `dumpsys car_service` on 2026-11-07.
 */
object CarNavigationVolume {
    private const val KEY_PREFIX = "android.car.VOLUME_GROUP/"
    private const val MEDIA_GROUP = 0
    private const val NAVIGATION_GROUP = 1
    private const val STEP_DB = 1.5

    /** dB for prompts on the music stream, or null when the car's volumes are unreadable. */
    fun relativeDb(context: Context): Int? {
        val media = step(context, MEDIA_GROUP) ?: return null
        val navigation = step(context, NAVIGATION_GROUP) ?: return null
        return relativeDb(media, navigation)
    }

    internal fun relativeDb(mediaStep: Int, navigationStep: Int): Int =
        NavigationAudioGain.clamp(((navigationStep - mediaStep) * STEP_DB).roundToInt())

    private fun step(context: Context, group: Int): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, KEY_PREFIX + group) }.getOrNull()
}
