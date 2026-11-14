package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.iap2.wire.Iap2Frame

/** Immutable publication of the Rust-owned incremental iAP2 NowPlaying state. */
data class CarPlayNowPlaying(
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val artworkTransferId: Int? = null,
    val sourceApp: String? = null,
    val durationMillis: Long? = null,
    val elapsedMillis: Long? = null,
    val playing: Boolean = false,
)

/**
 * The Glance song-card projection built by the native playback core: raw, untrimmed text plus
 * the Glance playback flag. Composition and trimming stay with the display side.
 */
data class CarPlayGlanceSong(
    val title: String? = null,
    val artist: String? = null,
    val playing: Boolean = false,
)

class CarPlayPlaybackStatus {
    private val owner = run { NativeMedia.requireAvailable(); arrayOf(NativePlaybackCore.nowCreate()) }
    @get:Synchronized val nowPlaying: CarPlayNowPlaying get() = NativePlaybackCore.nowSnapshot(owner)
    val playing: Boolean get() = nowPlaying.playing

    @Synchronized fun acceptUpdate(frame: Iap2Frame): CarPlayNowPlaying? =
        NativePlaybackCore.nowAccept(owner, frame.messageId, frame.payload)

    @Synchronized fun accept(frame: Iap2Frame): Boolean? {
        val previous = playing
        return acceptUpdate(frame)?.playing?.takeIf { it != previous }
    }

    @Synchronized fun clear(): Boolean? = NativePlaybackCore.nowClear(owner, false)?.playing
    @Synchronized fun clearAll(): CarPlayNowPlaying? = NativePlaybackCore.nowClear(owner, true)

    companion object { const val NOW_PLAYING_UPDATE = 0x5001 }
}

/** The tokens are opaque GC values: no native allocation or additional release lifetime. */
internal object NativePlaybackCore {
    @JvmStatic external fun nowCreate(): LongArray
    @JvmStatic external fun nowSnapshot(owner: Array<LongArray>): CarPlayNowPlaying
    @JvmStatic external fun nowAccept(owner: Array<LongArray>, id: Int, payload: ByteArray): CarPlayNowPlaying?
    @JvmStatic external fun nowClear(owner: Array<LongArray>, all: Boolean): CarPlayNowPlaying?
    // Preserve the platform UTF-8 replacement and Kotlin whitespace behavior at the text boundary.
    @JvmStatic fun decodeText(bytes: ByteArray): String? = bytes.decodeToString().trim().ifEmpty { null }

    @JvmStatic external fun glanceCreate(): LongArray
    @JvmStatic external fun glanceSnapshot(owner: Array<LongArray>): CarPlayGlanceSong?
    @JvmStatic external fun glanceAccept(owner: Array<LongArray>, id: Int, payload: ByteArray): CarPlayGlanceSong?
    @JvmStatic external fun glanceClear(owner: Array<LongArray>): CarPlayGlanceSong?
    // The Glance blank rules need the raw platform text; trimming stays with the card composition.
    @JvmStatic fun decodeRawText(bytes: ByteArray): String = bytes.decodeToString()

    @JvmStatic external fun navigationCreate(): LongArray
    @JvmStatic external fun navigationEvent(owner: Array<LongArray>, event: Int, id: Long,
        stream: Int, hasStream: Boolean, now: Long, buffered: Long, tail: Long, priority: Boolean): Long
    @JvmStatic external fun navigationSnapshot(owner: Array<LongArray>, now: Long): LongArray
}
