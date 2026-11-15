package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia

/**
 * Playback policy values are retained by Rust; this facade only converts platform DTOs. Native
 * transport layouts (operation numbers, snapshot tails, bit fields) stay private here: callers
 * consume the named results below.
 */
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

    /** Named MediaCenter publication plan: whether to request play and whether to publish metadata. */
    class PublicationPlan(val requestPlay: Boolean, val publishMetadata: Boolean)

    fun publicationPlan(owner: Array<LongArray>, info: CarPlayNowPlaying, artwork: String?, selected: Boolean): PublicationPlan {
        val plan = nativePublicationPlan(owner, words(info), artworkWords(artwork), selected)
        return PublicationPlan(requestPlay = plan and 1 != 0, publishMetadata = plan and 2 != 0)
    }
    fun publicationCommit(owner: Array<LongArray>, info: CarPlayNowPlaying, artwork: String?) =
        nativePublicationCommit(owner, words(info), artworkWords(artwork))

    fun artworkCreate(): Array<LongArray> = arrayOf(nativeArtworkCreate())
    fun artworkHash(bytes: ByteArray): LongArray = nativeArtworkHash(bytes)

    /** Retention changes selected by Rust for the Kotlin image tables; image lifetimes stay platform-owned. */
    data class ArtworkRetention(
        val pendingVersions: Set<Long>,
        val deliverables: List<ArtworkDeliverable>,
        val cachedTokens: Set<Long>,
    )

    /** One deliverable the core retained; a null [imageToken] is the decode-failure placeholder. */
    data class ArtworkDeliverable(val version: Long, val id: Int, val imageToken: Long?)

    /** A started artwork session: the opaque session value for later calls plus the retention to apply. */
    data class ArtworkSession(val session: Long, val retention: ArtworkRetention)

    /** Submit outcome for one artwork transfer; a rejected transfer leaves the queue state unchanged. */
    sealed class ArtworkSubmitResult {
        /** The assigned version; the payload stays keyed by it until Rust releases it. */
        data class Accepted(val version: Long, val retention: ArtworkRetention) : ArtworkSubmitResult()
        data object Rejected : ArtworkSubmitResult()
    }

    /** The next decode request Rust selected, or a cache hit that needs no decode. */
    sealed class ArtworkNextRequest {
        abstract val retention: ArtworkRetention
        /** Decode bytes for [version]; the payload was keyed by it at submit time. */
        data class Request(val version: Long, val id: Int, override val retention: ArtworkRetention) : ArtworkNextRequest()
        /** The content hash reached the core cache: the queued deliverable already has its image. */
        data class CacheHit(override val retention: ArtworkRetention) : ArtworkNextRequest()
        /** No pending request is current: stop draining until the next submit. */
        data class Idle(override val retention: ArtworkRetention) : ArtworkNextRequest()
    }

    /** The committed outcome of one decode attempt. */
    sealed class ArtworkDecodeResult {
        abstract val retention: ArtworkRetention
        /** Entered the core cache under [imageToken]; the platform image is retained under it. */
        data class Stored(val imageToken: Long, override val retention: ArtworkRetention) : ArtworkDecodeResult()
        /** The cache already holds this content under [existingToken]; the fresh image is redundant. */
        data class Duplicate(val existingToken: Long, override val retention: ArtworkRetention) : ArtworkDecodeResult()
        /** Decode failed; the queued deliverable publishes a clear through its null image token. */
        data class Failed(override val retention: ArtworkRetention) : ArtworkDecodeResult()
        /** Nothing committed: no in-flight request, or it is no longer current (discard in flight). */
        data class Dropped(override val retention: ArtworkRetention) : ArtworkDecodeResult()
    }

    /** The next deliverable to publish, or idle when the core queue is empty. */
    sealed class ArtworkNextDeliverable {
        abstract val retention: ArtworkRetention
        /** Publish [version] for [id]; a null [imageToken] is the decode-failure placeholder. */
        data class Deliverable(val version: Long, val id: Int, val imageToken: Long?,
            override val retention: ArtworkRetention) : ArtworkNextDeliverable()
        data class Idle(override val retention: ArtworkRetention) : ArtworkNextDeliverable()
    }

    fun artworkStartSession(owner: Array<LongArray>): ArtworkSession {
        val event = artworkEvent(owner, 0)
        return ArtworkSession(event[0], retention(event))
    }

    fun artworkClearSession(owner: Array<LongArray>): ArtworkRetention = retention(artworkEvent(owner, 1))

    fun artworkSubmit(owner: Array<LongArray>, session: Long, id: Int, hash: LongArray): ArtworkSubmitResult {
        val event = artworkEvent(owner, 2, session = session, id = id.toLong(), hash = hash)
        return if (event[0] == 0L) ArtworkSubmitResult.Rejected
        else ArtworkSubmitResult.Accepted(event[0], retention(event))
    }

    fun artworkNextRequest(owner: Array<LongArray>): ArtworkNextRequest {
        val event = artworkEvent(owner, 3)
        val retention = retention(event)
        return when (event[0]) {
            -1L -> ArtworkNextRequest.CacheHit(retention)
            0L -> ArtworkNextRequest.Idle(retention)
            else -> ArtworkNextRequest.Request(event[0], event[1].toInt(), retention)
        }
    }

    fun artworkDecodeComplete(owner: Array<LongArray>, decoded: Boolean, version: Long): ArtworkDecodeResult {
        val event = artworkEvent(owner, 4, id = if (decoded) 1 else 0, version = version)
        val retention = retention(event)
        return when (event[0]) {
            2L -> ArtworkDecodeResult.Stored(event[1], retention)
            3L -> ArtworkDecodeResult.Duplicate(event[1], retention)
            1L -> ArtworkDecodeResult.Failed(retention)
            else -> ArtworkDecodeResult.Dropped(retention)
        }
    }

    fun artworkNextDeliverable(owner: Array<LongArray>): ArtworkNextDeliverable {
        val event = artworkEvent(owner, 5)
        val retention = retention(event)
        return if (event[0] == 0L) ArtworkNextDeliverable.Idle(retention)
        else ArtworkNextDeliverable.Deliverable(event[0], event[1].toInt(),
            event[2].takeIf { it != -1L }, retention)
    }

    fun artworkIsCurrent(owner: Array<LongArray>, session: Long, id: Int, version: Long): Boolean =
        artworkEvent(owner, 6, session = session, id = id.toLong(), version = version)[0] != 0L

    private fun artworkEvent(owner: Array<LongArray>, event: Int, session: Long = 0, id: Long = 0,
        version: Long = 0, hash: LongArray = longArrayOf()): LongArray =
        nativeArtworkEvent(owner, event, session, id, version, hash)

    /** Parses the retention tail every event snapshot appends; no caller sees the layout. */
    private fun retention(event: LongArray): ArtworkRetention {
        var index = 3
        val pendingVersions = mutableSetOf<Long>()
        repeat(event[index++].toInt()) { pendingVersions.add(event[index++]) }
        val deliverables = mutableListOf<ArtworkDeliverable>()
        repeat(event[index++].toInt()) {
            deliverables.add(ArtworkDeliverable(event[index++], event[index++].toInt(),
                event[index++].takeIf { it != -1L }))
        }
        val cachedTokens = mutableSetOf<Long>()
        repeat(event[index++].toInt()) { cachedTokens.add(event[index++]) }
        return ArtworkRetention(pendingVersions, deliverables, cachedTokens)
    }

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
