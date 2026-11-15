package com.shihab.diplay

import com.shihab.diplay.media.PlaybackMetadataCore
import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkDecodeResult
import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkNextDeliverable
import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkNextRequest
import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkSubmitResult
import com.shihab.diplay.transport.Iap2FileTransferReceiver
import java.util.concurrent.Executor

/** Rust owns queue and cache policy; this adapter owns platform image references and executors. */
internal class NowPlayingArtworkQueue<Image : Any>(
    private val worker: Executor,
    private val main: Executor,
    private val decode: (ByteArray) -> Image?,
    private val publish: (owner: Any, id: Int, image: Image?) -> Unit,
    private val discard: (Image) -> Unit,
) {
    private class Owner(val token: Long)
    private data class Request(val owner: Owner, val id: Int, val version: Long, val bytes: ByteArray)
    private class CachedImage<Image : Any>(val image: Image, var references: Int = 0,
        var published: Boolean = false, var cached: Boolean = true, var discarded: Boolean = false)
    private data class Result<Image : Any>(val owner: Owner, val id: Int, val version: Long, val content: CachedImage<Image>?)

    private val lock = Any()
    private val state = PlaybackMetadataCore.artworkCreate()
    private var owner: Owner? = null
    private val payloads = mutableMapOf<Long, Request>()
    private val ready = mutableMapOf<Long, Result<Image>>()
    private val images = mutableMapOf<Long, CachedImage<Image>>()
    private var workerScheduled = false
    private var deliveryScheduled = false

    fun execute(task: Runnable) = worker.execute(task)

    fun newSession(): Any = synchronized(lock) {
        val started = PlaybackMetadataCore.artworkStartSession(state)
        owner = Owner(started.session)
        applyRetention(started.retention)
        owner!!
    }

    fun clear() = synchronized(lock) {
        val retention = PlaybackMetadataCore.artworkClearSession(state)
        owner = null
        applyRetention(retention)
    }

    fun submit(expected: Any, id: Int, bytes: ByteArray) {
        if (bytes.isEmpty() || id !in 0..255 || bytes.size > Iap2FileTransferReceiver.DEFAULT_MAXIMUM_ARTWORK_BYTES) return
        val target = expected as? Owner ?: return
        val hash = PlaybackMetadataCore.artworkHash(bytes)
        var scheduleWorker = false
        var scheduleDelivery = false
        synchronized(lock) {
            if (owner !== expected) return
            when (val submitted = PlaybackMetadataCore.artworkSubmit(state, target.token, id, hash)) {
                is ArtworkSubmitResult.Accepted -> {
                    payloads[submitted.version] = Request(target, id, submitted.version, bytes)
                    applyRetention(submitted.retention)
                }
                ArtworkSubmitResult.Rejected -> return
            }
            if (payloads.isNotEmpty() && !workerScheduled) { workerScheduled = true; scheduleWorker = true }
            if (ready.isNotEmpty() && !deliveryScheduled) { deliveryScheduled = true; scheduleDelivery = true }
        }
        if (scheduleDelivery) main.execute(::deliver)
        if (scheduleWorker) worker.execute(::drain)
    }

    private fun drain() {
        while (true) {
            var scheduleDelivery = false
            var cacheHit = false
            val request = synchronized(lock) {
                val next = PlaybackMetadataCore.artworkNextRequest(state)
                cacheHit = next is ArtworkNextRequest.CacheHit
                val request = (next as? ArtworkNextRequest.Request)?.let { payloads.remove(it.version) }
                applyRetention(next.retention)
                if (ready.isNotEmpty() && !deliveryScheduled) { deliveryScheduled = true; scheduleDelivery = true }
                if (request == null && !cacheHit) workerScheduled = false
                request
            }
            if (scheduleDelivery) main.execute(::deliver)
            if (cacheHit) continue
            if (request == null) return
            val current = synchronized(lock) {
                val valid = PlaybackMetadataCore.artworkIsCurrent(state, request.owner.token, request.id, request.version)
                if (!valid) applyRetention(PlaybackMetadataCore.artworkDecodeComplete(state,
                    decoded = false, version = request.version).retention)
                valid
            }
            if (!current) continue
            val image = try { decode(request.bytes) } catch (_: RuntimeException) { null }
            val schedule = synchronized(lock) {
                val completed = PlaybackMetadataCore.artworkDecodeComplete(state,
                    decoded = image != null, version = request.version)
                if (image != null) {
                    when (completed) {
                        is ArtworkDecodeResult.Stored -> images[completed.imageToken] = CachedImage(image)
                        else -> discard(image)
                    }
                }
                applyRetention(completed.retention)
                if (ready.isNotEmpty() && !deliveryScheduled) { deliveryScheduled = true; true } else false
            }
            if (schedule) main.execute(::deliver)
        }
    }

    /** Apply resource retention changes selected by Rust; image lifetimes remain platform-owned.
     * A deliverable's null image token is the decode-failure placeholder that publishes a clear. */
    private fun applyRetention(retention: PlaybackMetadataCore.ArtworkRetention) {
        payloads.keys.retainAll(retention.pendingVersions)
        val retained = retention.deliverables.mapTo(mutableSetOf()) { it.version }
        val removed = ready.keys.filter { it !in retained }
        removed.forEach { ready.remove(it)?.let(::releaseLocked) }
        for ((version, id, imageToken) in retention.deliverables) {
            if (version !in ready) {
                val content = imageToken?.let { checkNotNull(images[it]) }
                content?.references = (content?.references ?: 0) + 1
                ready[version] = Result(checkNotNull(owner), id, version, content)
            }
        }
        for ((id, content) in images.toMap()) {
            content.cached = id in retention.cachedTokens
            discardIfUnownedLocked(content)
            if (!content.cached && content.references == 0) images.remove(id)
        }
    }

    private fun releaseLocked(result: Result<Image>) {
        val content = result.content ?: return
        content.references--
        check(content.references >= 0) { "Artwork cache reference underflow" }
        discardIfUnownedLocked(content)
        if (!content.cached && content.references == 0) images.entries.removeAll { it.value === content }
    }

    private fun discardIfUnownedLocked(content: CachedImage<Image>) {
        if (!content.cached && !content.published && content.references == 0 && !content.discarded) {
            content.discarded = true
            discard(content.image)
        }
    }

    private fun deliver() {
        while (true) {
            val result = synchronized(lock) {
                val next = PlaybackMetadataCore.artworkNextDeliverable(state)
                val result = (next as? ArtworkNextDeliverable.Deliverable)?.let { ready.remove(it.version) }
                applyRetention(next.retention)
                if (result == null) deliveryScheduled = false
                result
            } ?: return
            var delivered = false
            try {
                val current = synchronized(lock) {
                    PlaybackMetadataCore.artworkIsCurrent(state, result.owner.token, result.id, result.version)
                }
                if (current) {
                    publish(result.owner, result.id, result.content?.image)
                    delivered = true
                }
            } finally {
                synchronized(lock) {
                    result.content?.let { if (delivered) it.published = true }
                    releaseLocked(result)
                }
            }
        }
    }
}
