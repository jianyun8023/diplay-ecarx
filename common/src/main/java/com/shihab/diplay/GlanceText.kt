package com.shihab.diplay

import android.content.Context
import com.shihab.diplay.host.R

/**
 * How a remaining distance and a remaining duration are spelled.
 *
 * The home-screen widget and the cluster strip show the same two numbers, so they round and
 * abbreviate in one place: exact metres below a kilometre — the factory cluster strip reads
 * `938 米` and the strip sits beside it — one kilometre decimal to ten, whole kilometres above, and
 * durations rounded to the nearest minute.
 */
internal object GlanceText {
    fun distance(context: Context, meters: Long): String = when {
        meters < 1_000 -> context.getString(R.string.widget_distance_meters, meters.coerceAtLeast(0).toInt())
        meters < 10_000 -> context.getString(R.string.widget_distance_km_decimal, meters / 1000.0)
        else -> context.getString(R.string.widget_distance_km, ((meters + 500) / 1000).toInt())
    }

    fun duration(context: Context, seconds: Long): String {
        val minutes = ((seconds + 30) / 60).toInt()
        return if (minutes < 60) context.getString(R.string.widget_minutes, minutes)
        else context.getString(R.string.widget_hours_minutes, minutes / 60, minutes % 60)
    }
}
