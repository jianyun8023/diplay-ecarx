package com.shihab.diplay.hud

import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.iap2.wire.Iap2Frame
import com.shihab.diplay.media.CarPlayGlanceSong
import com.shihab.diplay.media.NativePlaybackCore

/** The CarPlay song card shown at a glance: composed text and playback state. */
internal data class ClusterSong(val text: String, val playing: Boolean)

/**
 * The CarPlay song, from iAP2 NowPlayingUpdate (0x5001). Rust `session::playback` owns the
 * incremental field interpretation and retention, including the Glance-only blank-title rules;
 * this facade keeps the platform strings, the "Title — Artist" composition, output trimming and
 * the cached card the glance compares against.
 */
internal class ClusterSongState {
    private val owner = run { NativeMedia.requireAvailable(); arrayOf(NativePlaybackCore.glanceCreate()) }
    private var last: ClusterSong? = null

    /** Updates the cached card; null can mean unchanged or cleared, so consumers compare [current]. */
    fun accept(frame: Iap2Frame): ClusterSong? {
        val update = NativePlaybackCore.glanceAccept(owner, frame.messageId, frame.payload) ?: return null
        val next = card(update)
        if (next == last) return null
        last = next
        return next
    }

    /** The card for the song known so far, if any. */
    fun current(): ClusterSong? = last

    /** The session ended: forget the song. */
    fun clear() {
        NativePlaybackCore.glanceClear(owner)
        last = null
    }

    private fun card(update: CarPlayGlanceSong): ClusterSong? =
        text(update.title, update.artist)?.let { ClusterSong(it, update.playing) }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001

        /** "Title — Artist", shortened to [MAX_TEXT_BYTES]; null without a title. */
        fun text(title: String?, artist: String?): String? {
            val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val full = artist?.trim()?.takeIf { it.isNotEmpty() }?.let { "$name — $it" } ?: name
            var end = full.length
            while (full.substring(0, end).toByteArray(Charsets.UTF_16LE).size > MAX_TEXT_BYTES) {
                end--
                if (end > 0 && Character.isLowSurrogate(full[end])) end--
            }
            return full.substring(0, end)
        }

        /** Longest song line accepted downstream, in UTF-16LE bytes. */
        const val MAX_TEXT_BYTES = 255
    }
}
