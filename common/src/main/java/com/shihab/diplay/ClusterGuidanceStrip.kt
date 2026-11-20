package com.shihab.diplay

import android.content.Context
import android.text.format.DateFormat
import com.shihab.diplay.glance.CarPlayGlance
import com.shihab.diplay.host.R
import java.util.Date

/**
 * The arrival, duration and remaining distance DiPlay draws along the bottom of the projected
 * cluster map.
 *
 * Apple's own cluster map (stream type 111) has no guidance banner: the iPhone's glass turn card is
 * a separate cluster content, so with the map content the panel shows a map and nothing else. The
 * head unit's factory navigation answers the same question with one line — `22:34 到达 3 分钟
 * 938 米` — and this reproduces it for CarPlay from the route state the HUD outputs already read,
 * with no second copy of anything: [CarPlayGlance] is fed by 0x5201/0x5202 for every session.
 */
internal object ClusterGuidanceStrip {
    /** The two halves of the strip; whichever half the phone has not sent is left out. */
    data class Line(val arrival: String, val detail: String) {
        val text: String
            get() = when {
                arrival.isEmpty() -> detail
                detail.isEmpty() -> arrival
                else -> "$arrival · $detail"
            }
    }

    /**
     * Null when there is nothing to say: no session, or a route whose phone has not sent an
     * arrival, a duration or a remaining distance yet.
     */
    fun content(context: Context, snapshot: CarPlayGlance.Snapshot): Line? {
        if (!snapshot.connected) return null
        val arrival = snapshot.arrivalEpochSeconds?.let { epoch ->
            val time = DateFormat.getTimeFormat(context).format(Date(epoch * 1000))
            context.getString(R.string.turn_card_info_arrival, time)
        }
        val detail = listOfNotNull(
            snapshot.remainingSeconds?.let { GlanceText.duration(context, it) },
            snapshot.remainingMeters?.let { GlanceText.distance(context, it) },
        ).joinToString(" · ")
        if (arrival == null && detail.isEmpty()) return null
        return Line(arrival.orEmpty(), detail)
    }
}
