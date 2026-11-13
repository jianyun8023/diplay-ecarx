package com.shihab.diplay

import com.shihab.diplay.transport.Iap2FileTransferReceiver
import java.security.MessageDigest
import java.util.concurrent.Executor

/** Bounds artwork work and reuses decoded images when iAP2 assigns a new id to identical bytes. */
internal class NowPlayingArtworkQueue<Image : Any>(
    private val worker: Executor,
    private val main: Executor,
    private val decode: (ByteArray) -> Image?,
    private val publish: (owner: Any, id: Int, image: Image?) -> Unit,
    private val discard: (Image) -> Unit,
) {
    private data class Request(val owner: Any, val id: Int, val version: Long, val bytes: ByteArray,
                               val contentHash: String)
    private data class RequestInfo(val owner: Any, val id: Int, val version: Long)
    private class CachedImage<Image : Any>(
        val image: Image,
        var references: Int = 0,
        var published: Boolean = false,
        var cached: Boolean = true,
        var discarded: Boolean = false,
    )
    private data class Result<Image : Any>(val request: RequestInfo, val content: CachedImage<Image>?)

    private val lock = Any()
    private var owner: Any? = null
    private var version = 0L
    private val latest = LongArray(256)
    private val pending = LinkedHashMap<Int, Request>()
    private val ready = LinkedHashMap<Int, Result<Image>>()
    private val decodedByContent = LinkedHashMap<String, CachedImage<Image>>()
    private var workerScheduled = false
    private var deliveryScheduled = false

    /** Runs bounded metadata recovery on the existing worker, away from the UI and PCM. */
    fun execute(task: Runnable) = worker.execute(task)

    /** A fresh token also invalidates a decode already in progress. */
    fun newSession(): Any = synchronized(lock) {
        clearLocked()
        Any().also { owner = it }
    }

    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        owner = null
        pending.clear()
        ready.values.forEach(::releaseLocked)
        ready.clear()
        decodedByContent.values.forEach {
            it.cached = false
            discardIfUnownedLocked(it)
        }
        decodedByContent.clear()
    }

    fun submit(expected: Any, id: Int, bytes: ByteArray) {
        if (bytes.isEmpty() || id !in 0..255 || bytes.size > Iap2FileTransferReceiver.DEFAULT_MAXIMUM_ARTWORK_BYTES) return
        val hash = contentHash(bytes)
        var scheduleWorker = false
        var scheduleDelivery = false
        synchronized(lock) {
            if (owner !== expected) return
            val requestVersion = ++version
            latest[id] = requestVersion
            pending.remove(id)
            val info = RequestInfo(expected, id, requestVersion)
            val cached = decodedByContent[hash]
            if (cached != null) {
                touchContentLocked(hash, cached)
                scheduleDelivery = enqueueReadyLocked(info, cached)
            } else {
                ready.remove(id)?.let(::releaseLocked)
                pending[id] = Request(expected, id, requestVersion, bytes, hash)
                while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
                if (!workerScheduled) {
                    workerScheduled = true
                    scheduleWorker = true
                }
            }
        }
        if (scheduleDelivery) main.execute(::deliver)
        if (scheduleWorker) worker.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                if (pending.isEmpty()) {
                    workerScheduled = false
                    return
                }
                pending.remove(pending.keys.first())!!
            }
            val info = RequestInfo(request.owner, request.id, request.version)
            if (!isCurrent(info)) continue

            var stale = false
            var cachedSchedule: Boolean? = null
            synchronized(lock) {
                if (!isCurrentLocked(info)) {
                    stale = true
                } else {
                    decodedByContent[request.contentHash]?.let { cached ->
                        touchContentLocked(request.contentHash, cached)
                        cachedSchedule = enqueueReadyLocked(info, cached)
                    }
                }
            }
            if (stale) continue
            if (cachedSchedule != null) {
                if (cachedSchedule == true) main.execute(::deliver)
                continue
            }
            if (!isCurrent(info)) continue

            val image = try { decode(request.bytes) } catch (_: RuntimeException) { null }
            val schedule = synchronized(lock) {
                if (!isCurrentLocked(info)) {
                    stale = true
                    image?.let(discard)
                    false
                } else {
                    val content = if (image == null) null else {
                        val existing = decodedByContent[request.contentHash]
                        if (existing != null) {
                            image.let(discard)
                            touchContentLocked(request.contentHash, existing)
                            existing
                        } else {
                            CachedImage(image).also {
                                decodedByContent[request.contentHash] = it
                                trimContentCacheLocked()
                            }
                        }
                    }
                    enqueueReadyLocked(info, content)
                }
            }
            if (!stale && schedule) main.execute(::deliver)
        }
    }

    private fun enqueueReadyLocked(info: RequestInfo, content: CachedImage<Image>?): Boolean {
        ready.remove(info.id)?.let(::releaseLocked)
        content?.references = (content?.references ?: 0) + 1
        ready[info.id] = Result(info, content)
        while (ready.size > MAX_PENDING) ready.remove(ready.keys.first())?.let(::releaseLocked)
        if (deliveryScheduled) return false
        deliveryScheduled = true
        return true
    }

    private fun touchContentLocked(hash: String, content: CachedImage<Image>) {
        decodedByContent.remove(hash)
        decodedByContent[hash] = content
    }

    private fun trimContentCacheLocked() {
        while (decodedByContent.size > MAX_CACHED_CONTENT) {
            val oldest = decodedByContent.remove(decodedByContent.keys.first()) ?: continue
            oldest.cached = false
            discardIfUnownedLocked(oldest)
        }
    }

    private fun releaseLocked(result: Result<Image>) {
        val content = result.content ?: return
        content.references--
        check(content.references >= 0) { "Artwork cache reference underflow" }
        discardIfUnownedLocked(content)
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
                if (ready.isEmpty()) {
                    deliveryScheduled = false
                    return
                }
                ready.remove(ready.keys.first())!!
            }
            var delivered = false
            try {
                if (isCurrent(result.request)) {
                    publish(result.request.owner, result.request.id, result.content?.image)
                    delivered = true
                }
            } finally {
                result.content?.let { content ->
                    synchronized(lock) {
                        if (delivered) content.published = true
                        releaseLocked(result)
                    }
                }
            }
        }
    }

    private fun isCurrent(info: RequestInfo): Boolean = synchronized(lock) { isCurrentLocked(info) }
    private fun isCurrentLocked(info: RequestInfo): Boolean = owner === info.owner && latest[info.id] == info.version

    private fun contentHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val chars = CharArray(digest.size * 2)
        for (index in digest.indices) {
            val value = digest[index].toInt() and 0xff
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(chars)
    }

    private companion object {
        const val MAX_PENDING = 4 // At most 8 MiB compressed, plus the one decode in progress.
        const val MAX_CACHED_CONTENT = 4
        const val HEX = "0123456789abcdef"
    }
}
