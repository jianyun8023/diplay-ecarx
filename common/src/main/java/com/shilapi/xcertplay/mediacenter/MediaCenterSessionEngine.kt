package com.shilapi.xcertplay.mediacenter

import android.net.Uri
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Single-worker, coalescing owner state. Binder timeouts do not imply remote cancellation.
 * No second worker or backend is ever started while an operation remains uncertain. */
internal class MediaCenterSessionEngine(
    private val worker: Executor,
    private val factory: (Any, () -> Boolean) -> MediaCenterTransport,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    data class Desired(val owner: Any, val now: CarPlayNowPlaying, val artwork: Uri?, val valid: () -> Boolean)
    data class Status(val phase: String, val detail: String, val registered: Boolean, val accepted: Boolean, val uncertain: Boolean, val claimed: Boolean = false, val artworkSupported: Boolean = false)
    private val desiredLock = Any()
    private val operationLock = Any()
    @Volatile private var operationOwner: Any? = null
    @Volatile private var desired: Desired? = null
    @Volatile private var status = Status("IDLE", "NONE", false, false, false)
    @Volatile private var registeredOwner: Any? = null
    @Volatile private var callbackOwner: Any? = null
    @Volatile private var operationAt = 0L
    @Volatile private var operation = "NONE"
    private val scheduled = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)
    // The fields below belong exclusively to the single worker.
    @Volatile private var transport: MediaCenterTransport? = null
    private var currentOwner: Any? = null
    private var failedOwner: Any? = null
    @Volatile private var timedOutOwner: Any? = null
    private var uncertainOwner: Any? = null
    private var last: CarPlayNowPlaying? = null
    private var lastArtwork: Uri? = null
    @Volatile private var selectionOwner: Any? = null

    fun update(value: Desired?) { synchronized(desiredLock) { desired = value }; queue() }
    fun stop(owner: Any) {
        val changed = synchronized(desiredLock) {
            if (desired?.owner === owner) { desired = null; true } else false
        }
        if (changed) queue()
    }
    fun snapshot(): Status = status
    /** A stopped owner may still have queued IPC or a late registration token. */
    fun cleanForOtherBackend(): Boolean = desired == null && !scheduled.get() && !dirty.get() &&
        transport == null && registeredOwner == null && operationAt == 0L && !status.uncertain
    fun canSelect(owner: Any): Boolean = ownsCallbacks(owner) && operation == "NONE"
    fun select(owner: Any) { if (canSelect(owner)) { selectionOwner = owner; queue() } }
    fun ownsMediaKeys(owner: Any): Boolean = ownsCallbacks(owner) && status.claimed
    fun ownsCallbacks(owner: Any): Boolean = callbackOwner === owner && desired?.let {
        it.owner === owner && hasMedia(it.now) && runCatching(it.valid).getOrDefault(false)
    } == true && !status.uncertain && transport?.connected == true

    /** Called by one process-wide timer, not by creating a new timer per frame or session. */
    fun tick() {
        synchronized(operationLock) {
            val at = operationAt
            if (at != 0L && clock() - at >= TIMEOUT_MILLIS) {
                callbackOwner = null
                timedOutOwner = operationOwner
                status = status.copy(phase = "TIMEOUT_UNCERTAIN", detail = operation, uncertain = true)
            }
        }
        queue()
    }

    private fun queue() {
        dirty.set(true)
        if (!scheduled.compareAndSet(false, true)) return
        worker.execute {
            try {
                // At most the latest snapshot is queued, even when Binder blocks indefinitely.
                while (dirty.getAndSet(false)) reconcile()
            } finally {
                scheduled.set(false)
                if (dirty.get()) queue()
            }
        }
    }

    private fun usable(value: Desired?): Boolean = value != null && hasMedia(value.now) &&
        runCatching(value.valid).getOrDefault(false)

    private fun reconcile() {
        val target = desired
        if (transport != null && !transport!!.connected) {
            failedOwner = currentOwner
            callbackOwner = null
            status = status.copy(phase = "UNAVAILABLE", detail = "SERVICE_DISCONNECTED", accepted = false, claimed = false)
            cleanup()
        }
        if (transport != null && (!usable(target) || target?.owner !== currentOwner || status.uncertain)) {
            cleanup()
        }
        if (status.uncertain) return
        if (!usable(target)) return
        target!!
        if (failedOwner === target.owner || uncertainOwner === target.owner || timedOutOwner === target.owner) return
        if (transport == null) {
            val owner = target.owner
            currentOwner = owner
            last = null; lastArtwork = null
            try {
                phase("CONNECTING")
                transport = factory(owner) { desired?.let { it.owner === owner && usable(it) } == true }
                transport!!.connect()
                finishOperation()
                if (desired?.owner !== owner || !usable(desired) || status.uncertain) { cleanup(); return }
                val frame = desired!!
                phase("REGISTERING")
                transport!!.register(frame.now, frame.artwork)
                finishOperation()
                if (!transport!!.registered) {
                    uncertainOwner = owner
                    status = Status("REGISTRATION_UNCERTAIN", "NO_TOKEN", false, false, true)
                    cleanup(); return
                }
                registeredOwner = owner
                // A late token is still cleaned against the original transport, never discarded.
                if (desired?.owner !== owner || !usable(desired) || status.uncertain) { cleanup(); return }
                status = Status("REGISTERED", "NONE", true, false, false, artworkSupported = transport!!.artworkSupported)
            } catch (error: Exception) {
                val attempted = operation == "REGISTERING"
                finishOperation()
                failedOwner = owner
                status = Status(if (attempted) "REGISTRATION_UNCERTAIN" else "UNAVAILABLE",
                    error.javaClass.simpleName, transport?.registered == true, false, attempted)
                if (attempted && transport?.registered != true) uncertainOwner = owner
                cleanup(); return
            } catch (_: LinkageError) {
                val attempted = operation == "REGISTERING"
                finishOperation(); failedOwner = owner
                status = Status(if (attempted) "REGISTRATION_UNCERTAIN" else "UNAVAILABLE", "LINKAGE", transport?.registered == true, false, attempted)
                cleanup(); return
            }
        }
        val frame = desired ?: return
        if (frame.owner !== currentOwner || !usable(frame)) { cleanup(); return }
        try {
            val previous = last
            val selected = selectionOwner === frame.owner
            selectionOwner = null
            val acquire = selected || (frame.now.playing && (previous == null || !previous.playing || previous.sourceApp != frame.now.sourceApp))
            if (acquire) {
                phase("REQUEST_PLAY")
                val claimed = transport!!.requestPlay()
                finishOperation()
                status = status.copy(claimed = claimed, detail = if (claimed) "PLAY_GRANTED" else "PLAY_NOT_GRANTED")
            }
            if (desired?.owner !== frame.owner || !usable(desired) || status.uncertain) { cleanup(); return }
            // Progress can change independently; no recurring requestPlay while playing.
            if (frame.now != previous || frame.artwork != lastArtwork) {
                phase("PUBLISHING")
                val accepted = transport!!.publish(frame.now, frame.artwork)
                finishOperation()
                if (desired?.owner !== frame.owner || !usable(desired) || status.uncertain) { cleanup(); return }
                status = Status("REGISTERED", if (accepted) "PLAYBACK_ACCEPTED" else "PLAYBACK_REJECTED", true, accepted, false, status.claimed, transport!!.artworkSupported)
                callbackOwner = if (accepted) frame.owner else null
                last = frame.now; lastArtwork = frame.artwork
            }
        } catch (error: Exception) {
            finishOperation(); failedOwner = currentOwner
            status = status.copy(phase = "PUBLISH_FAILED", detail = error.javaClass.simpleName, accepted = false)
            callbackOwner = null
            cleanup()
        } catch (_: LinkageError) {
            finishOperation(); failedOwner = currentOwner
            status = status.copy(phase = "PUBLISH_FAILED", detail = "LINKAGE", accepted = false)
            callbackOwner = null; cleanup()
        }
    }

    private fun cleanup() {
        val original = transport ?: return
        callbackOwner = null
        val before = status
        val hadToken = original.registered
        var cleaned = !hadToken && !status.uncertain
        try {
            if (hadToken) {
                phase("CLEARING")
                try { original.clear() } catch (_: Exception) { /* Still attempt the mandatory unregister. */ } catch (_: LinkageError) { }
                phase("UNREGISTERING")
                cleaned = original.unregister()
            }
        } catch (_: Exception) { cleaned = false } catch (_: LinkageError) { cleaned = false }
        finally {
            try { original.close() } catch (_: Exception) { cleaned = false } catch (_: LinkageError) { cleaned = false }
            finishOperation()
            transport = null; registeredOwner = null; currentOwner = null
            last = null; lastArtwork = null
            status = if (cleaned) {
                if (before.phase == "UNAVAILABLE" || before.phase == "PUBLISH_FAILED")
                    before.copy(registered = false, accepted = false, uncertain = false, claimed = false)
                else Status("IDLE", "CLEANED", false, false, false)
            }
                else Status("CLEANUP_UNCERTAIN", "RESTART_REQUIRED", false, false, true)
        }
    }

    private fun phase(value: String) = synchronized(operationLock) {
        operationOwner = currentOwner
        operation = value; operationAt = clock().coerceAtLeast(1L)
        status = status.copy(phase = value)
    }
    private fun finishOperation() = synchronized(operationLock) { operationAt = 0L; operation = "NONE"; operationOwner = null }

    companion object {
        private const val TIMEOUT_MILLIS = 8_000L
        fun hasMedia(now: CarPlayNowPlaying): Boolean =
            !now.title.isNullOrBlank() || !now.artist.isNullOrBlank() || !now.album.isNullOrBlank() ||
                now.artworkTransferId != null || (now.durationMillis ?: 0L) > 0L
    }
}
