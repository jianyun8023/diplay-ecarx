package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia

/** Playback policy values are retained by Rust; this facade only converts platform DTOs. */
object PlaybackMetadataCore {
    init { NativeMedia.requireAvailable() }

    private fun words(info: CarPlayNowPlaying): LongArray {
        val values = ArrayList<Long>()
        for (number in listOf(info.artworkTransferId?.toLong(), info.durationMillis, info.elapsedMillis)) {
            values.add(if (number == null) 0L else 1L)
            values.add(number ?: 0L)
        }
        values.add(if (info.playing) 1L else 0L)
        for (text in listOf(info.title, info.album, info.artist, info.sourceApp)) {
            values.add(text?.length?.toLong() ?: -1L)
            text?.forEach { values.add(it.code.toLong()) }
        }
        return values.toLongArray()
    }

    fun trackChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        nativeTrackChanged(words(previous), words(next))

    fun recoveryCreate(): Array<LongArray> = arrayOf(nativeRecoveryCreate())
    fun recoveryQuery(owner: Array<LongArray>, field: Int): Long = nativeRecoveryQuery(owner, field)
    fun recoveryPrepare(owner: Array<LongArray>, action: Int, token: Long, info: CarPlayNowPlaying, color: Boolean): Boolean =
        nativeRecoveryPrepare(owner, action, token, words(info), color)
    fun recoveryComplete(owner: Array<LongArray>, action: Int, token: Long, allowed: Boolean): Boolean =
        nativeRecoveryComplete(owner, action, token, allowed)
    fun recoveryFinished(owner: Array<LongArray>, token: Long) = nativeRecoveryFinished(owner, token)
    fun recoveryEvents(owner: Array<LongArray>): LongArray = nativeRecoveryEvents(owner)

    private fun artworkWords(value: String?): LongArray =
        if (value == null) longArrayOf(-1) else longArrayOf(value.length.toLong()) + value.map { it.code.toLong() }.toLongArray()
    fun publicationCreate(): Array<LongArray> = arrayOf(nativePublicationCreate())
    fun publicationPlan(owner: Array<LongArray>, info: CarPlayNowPlaying, artwork: String?, selected: Boolean): Int =
        nativePublicationPlan(owner, words(info), artworkWords(artwork), selected)
    fun publicationCommit(owner: Array<LongArray>, info: CarPlayNowPlaying, artwork: String?) =
        nativePublicationCommit(owner, words(info), artworkWords(artwork))
    fun artworkCreate(): Array<LongArray> = arrayOf(nativeArtworkCreate())
    fun artworkHash(bytes: ByteArray): LongArray = nativeArtworkHash(bytes)
    fun artworkEvent(owner: Array<LongArray>, event: Int, session: Long = 0, id: Long = 0,
        version: Long = 0, hash: LongArray = longArrayOf()): LongArray =
        nativeArtworkEvent(owner, event, session, id, version, hash)

    @JvmStatic private external fun nativeTrackChanged(previous: LongArray, next: LongArray): Boolean
    @JvmStatic private external fun nativeRecoveryCreate(): LongArray
    @JvmStatic private external fun nativeRecoveryQuery(owner: Array<LongArray>, field: Int): Long
    @JvmStatic private external fun nativeRecoveryPrepare(owner: Array<LongArray>, action: Int, token: Long, info: LongArray, color: Boolean): Boolean
    @JvmStatic private external fun nativeRecoveryComplete(owner: Array<LongArray>, action: Int, token: Long, allowed: Boolean): Boolean
    @JvmStatic private external fun nativeRecoveryFinished(owner: Array<LongArray>, token: Long)
    @JvmStatic private external fun nativeRecoveryEvents(owner: Array<LongArray>): LongArray
    @JvmStatic private external fun nativePublicationCreate(): LongArray
    @JvmStatic private external fun nativePublicationPlan(owner: Array<LongArray>, info: LongArray, artwork: LongArray, selected: Boolean): Int
    @JvmStatic private external fun nativePublicationCommit(owner: Array<LongArray>, info: LongArray, artwork: LongArray)
    @JvmStatic private external fun nativeArtworkCreate(): LongArray
    @JvmStatic private external fun nativeArtworkHash(bytes: ByteArray): LongArray
    @JvmStatic private external fun nativeArtworkEvent(owner: Array<LongArray>, event: Int, session: Long, id: Long, version: Long, hash: LongArray): LongArray
}
