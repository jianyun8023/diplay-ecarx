package com.shihab.diplay.airplay

import com.shihab.diplay.transport.BlockingDuplexByteStream
import java.io.IOException

/**
 * Full-duplex adapter for the CarPlay iAP2 DataStream.
 *
 * Inbound iAP2 bytes come from [IapTunnel]; outbound bytes are sent over the encrypted AirPlay
 * event channel using the `iAPSendMessage` command.
 */
internal class AirPlayIapTunnelStream(
    private val session: AirPlaySession,
    private val tunnel: IapTunnel,
    private val sendCommand: (ByteArray, Long) -> Boolean = { data, timeoutMillis ->
        session.sendIapMessage(data, timeoutMillis)
    },
) : BlockingDuplexByteStream {
    private val lock = Object()
    // Rust owns layout, byte budget, splitting and termination. GC owns payload storage,
    // allowing queued-before-close/error drain without a surviving native handle.
    private val queue = NativeIapStreamQueue.create()
    // Platform payloads; their presence is controlled by the Rust termination flags.
    private lateinit var terminalFailure: Throwable
    private var closeLocation: String? = null

    fun listen(): Int = tunnel.listen(
        object : IapTunnel.Listener {
            override fun onOpen(remoteAddress: String?) {
                session.logTrace(
                    "AirPlay iAP tunnel connected remote=${remoteAddress ?: "unknown"}",
                )
            }

            override fun onIap(bytes: ByteArray) = offer(bytes)

            override fun onDebug(message: String) {
                session.logTrace(message)
            }

            override fun onClosed(cause: Throwable?) {
                session.logTrace(
                    "AirPlay iAP tunnel closed cause=" +
                        "${cause?.javaClass?.simpleName ?: "peer EOF"}: " +
                        "${cause?.message ?: "no detail"}",
                )
                val shouldCloseSession = markFailed(cause)
                if (shouldCloseSession) session.close()
            }
        },
    )

    override fun send(data: ByteArray) {
        synchronized(lock) {
            checkSendLocked()
        }
        if (!tunnel.awaitPeerConnection(PEER_CONNECT_TIMEOUT_MILLIS)) {
            throw IOException("CarPlay iAP tunnel peer did not connect")
        }
        synchronized(lock) {
            checkSendLocked()
        }
        if (!sendCommand(data, EVENT_READY_TIMEOUT_MILLIS)) {
            throw IOException("AirPlay event channel rejected an iAP message")
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }

        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(lock) {
            while (true) {
                NativeIapStreamQueue.take(queue, maxBytes)?.let { return it }
                val status = NativeIapStreamQueue.status(queue)
                throwTerminalFailureLocked(status)
                if (status and CLOSED != 0) return EMPTY
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) return null
                try {
                    lock.wait(
                        remainingNanos / NANOS_PER_MILLISECOND,
                        (remainingNanos % NANOS_PER_MILLISECOND).toInt(),
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!NativeIapStreamQueue.close(queue)) return
            closeLocation = closeCaller()
            lock.notifyAll()
        }
        tunnel.close()
    }

    private fun offer(bytes: ByteArray) {
        synchronized(lock) {
            when (NativeIapStreamQueue.offer(queue, bytes)) {
                1 -> lock.notifyAll()
                2 -> {
                    terminalFailure = IOException("AirPlay iAP tunnel pending-data limit exceeded")
                    lock.notifyAll()
                    tunnel.close()
                }
            }
        }
    }

    private fun markFailed(cause: Throwable?): Boolean {
        synchronized(lock) {
            if (!NativeIapStreamQueue.fail(queue)) return false
            terminalFailure = cause ?: IOException("AirPlay iAP tunnel terminated")
            lock.notifyAll()
            return true
        }
    }

    private fun checkSendLocked() {
        val status = NativeIapStreamQueue.status(queue)
        throwTerminalFailureLocked(status)
        if (status and CLOSED != 0) throw closedFailureLocked()
    }

    private fun throwTerminalFailureLocked(status: Int) {
        if (status and FAILED == 0) return
        val failure = terminalFailure
        throw IOException(
            "AirPlay iAP tunnel failed: " +
                "${failure.javaClass.simpleName}: ${failure.message ?: "unknown error"}",
            failure,
        )
    }

    private fun closedFailureLocked(): IOException = IOException(
        buildString {
            append("AirPlay iAP tunnel is closed")
            closeLocation?.let { append("; requested by $it") }
        },
    )

    private fun closeCaller(): String =
        Thread.currentThread().stackTrace
            .drop(2)
            .take(8)
            .joinToString(" <- ") { frame ->
                "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
            }

    private companion object {
        const val CLOSED = 1
        const val FAILED = 2
        const val PEER_CONNECT_TIMEOUT_MILLIS = 15_000L
        const val EVENT_READY_TIMEOUT_MILLIS = 10_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val EMPTY = ByteArray(0)
    }
}

internal object NativeIapStreamQueue {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun create(): Array<Any?>
    @JvmStatic external fun offer(state: Array<Any?>, bytes: ByteArray): Int
    @JvmStatic external fun take(state: Array<Any?>, maximum: Int): ByteArray?
    @JvmStatic external fun status(state: Array<Any?>): Int
    @JvmStatic external fun fail(state: Array<Any?>): Boolean
    @JvmStatic external fun close(state: Array<Any?>): Boolean
}
