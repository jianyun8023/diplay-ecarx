package com.shihab.diplay.hud

import com.shihab.diplay.iap2.body.Iap2BodyReader
import com.shihab.diplay.iap2.wire.Iap2Frame

/**
 * Independent Kotlin reference for the Glance song projection; the native core must match it.
 * This is the pre-migration production algorithm, kept only so the playback core contract in
 * `com.shihab.diplay.media` can drive both implementations over the same random frames.
 */
internal class KotlinClusterSongState {
    private var title: String? = null
    private var artist: String? = null
    private var playing = false
    private var last: ClusterSong? = null

    /** The complete new card when this frame changed it, otherwise null. */
    internal fun accept(frame: Iap2Frame): ClusterSong? {
        if (frame.messageId != ClusterSongState.NOW_PLAYING_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        runCatching { body.optionalGroup(ITEM) }.getOrNull()?.let { item ->
            val nextTitle = runCatching { item.optionalString(TITLE) }.getOrNull()
            if (nextTitle != null) {
                title = nextTitle
                if (nextTitle.isBlank()) artist = null
            }
            if (nextTitle?.isBlank() != true) {
                runCatching { item.optionalString(ARTIST) }.getOrNull()?.let { artist = it }
            }
        }
        runCatching { body.optionalGroup(PLAYBACK)?.optionalU8(STATUS) }.getOrNull()?.let { status ->
            playing = status == STATUS_PLAYING || status == STATUS_SEEK_FORWARD || status == STATUS_SEEK_BACKWARD
        }
        val next = ClusterSongState.text(title, artist)?.let { ClusterSong(it, playing) }
        if (next == last) return null
        last = next
        return next
    }

    /** The card for the song known so far, if any. */
    internal fun current(): ClusterSong? = last

    /** The session ended: forget the song. */
    internal fun clear() {
        title = null
        artist = null
        playing = false
        last = null
    }

    companion object {
        private const val ITEM = 0
        private const val TITLE = 1
        private const val ARTIST = 12
        private const val PLAYBACK = 1
        private const val STATUS = 0
        private const val STATUS_PLAYING = 1
        private const val STATUS_SEEK_FORWARD = 3
        private const val STATUS_SEEK_BACKWARD = 4
    }
}
