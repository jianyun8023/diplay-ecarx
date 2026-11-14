package com.shihab.diplay

import com.shihab.diplay.media.PlaybackMetadataCore
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
        val result = PlaybackMetadataCore.artworkEvent(state, 0)
        owner = Owner(result[0])
        sync(result)
        owner!!
    }

    fun clear() = synchronized(lock) {
        val result = PlaybackMetadataCore.artworkEvent(state, 1)
        owner = null
        sync(result)
    }

    fun submit(expected: Any, id: Int, bytes: ByteArray) {
        if (bytes.isEmpty() || id !in 0..255 || bytes.size > Iap2FileTransferReceiver.DEFAULT_MAXIMUM_ARTWORK_BYTES) return
        val target = expected as? Owner ?: return
        val hash = PlaybackMetadataCore.artworkHash(bytes)
        var scheduleWorker = false
        var scheduleDelivery = false
        synchronized(lock) {
            if (owner !== expected) return
            val result = PlaybackMetadataCore.artworkEvent(state, 2, target.token, id.toLong(), hash = hash)
            if (result[0] == 0L) return
            payloads[result[0]] = Request(target, id, result[0], bytes)
            sync(result)
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
                val result = PlaybackMetadataCore.artworkEvent(state, 3)
                cacheHit = result[0] == -1L
                val request = payloads.remove(result[0])
                sync(result)
                if (ready.isNotEmpty() && !deliveryScheduled) { deliveryScheduled = true; scheduleDelivery = true }
                if (request == null && !cacheHit) workerScheduled = false
                request
            }
            if (scheduleDelivery) main.execute(::deliver)
            if (cacheHit) continue
            if (request == null) return
            val current = synchronized(lock) {
                val valid = PlaybackMetadataCore.artworkEvent(state, 6, request.owner.token,
                    request.id.toLong(), request.version)[0] != 0L
                if (!valid) sync(PlaybackMetadataCore.artworkEvent(state, 4, version = request.version))
                valid
            }
            if (!current) continue
            val image = try { decode(request.bytes) } catch (_: RuntimeException) { null }
            val schedule = synchronized(lock) {
                val result = PlaybackMetadataCore.artworkEvent(state, 4, id = if (image == null) 0 else 1,
                    version = request.version)
                if (image != null) {
                    if (result[0] == 2L) images[result[1]] = CachedImage(image) else discard(image)
                }
                sync(result)
                if (ready.isNotEmpty() && !deliveryScheduled) { deliveryScheduled = true; true } else false
            }
            if (schedule) main.execute(::deliver)
        }
    }

    /** Apply resource retention changes selected by Rust; image lifetimes remain platform-owned. */
    private fun sync(snapshot: LongArray) {
        var index = 3
        val pendingVersions = mutableSetOf<Long>()
        repeat(snapshot[index++].toInt()) { pendingVersions.add(snapshot[index++]) }
        payloads.keys.retainAll(pendingVersions)
        val entries = mutableListOf<Triple<Long, Int, Long>>()
        repeat(snapshot[index++].toInt()) { entries.add(Triple(snapshot[index++], snapshot[index++].toInt(), snapshot[index++])) }
        val retained = entries.mapTo(mutableSetOf()) { it.first }
        val removed = ready.keys.filter { it !in retained }
        removed.forEach { ready.remove(it)?.let(::releaseLocked) }
        for ((version, id, image) in entries) {
            if (version !in ready) {
                val content = if (image == -1L) null else checkNotNull(images[image])
                content?.references = (content?.references ?: 0) + 1
                ready[version] = Result(checkNotNull(owner), id, version, content)
            }
        }
        val cached = mutableSetOf<Long>()
        repeat(snapshot[index++].toInt()) { cached.add(snapshot[index++]) }
        for ((id, content) in images.toMap()) {
            content.cached = id in cached
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
                val snapshot = PlaybackMetadataCore.artworkEvent(state, 5)
                val result = ready.remove(snapshot[0])
                sync(snapshot)
                if (result == null) deliveryScheduled = false
                result
            } ?: return
            var delivered = false
            try {
                val current = synchronized(lock) {
                    PlaybackMetadataCore.artworkEvent(state, 6, result.owner.token, result.id.toLong(), result.version)[0] != 0L
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
