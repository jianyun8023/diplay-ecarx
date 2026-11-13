package com.shilapi.xcertplay.airplay

import java.util.ArrayDeque

/** Independent test-only queue policy from the former AirPlayIapTunnelStream implementation. */
class KotlinIapStreamQueue {
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var closed = false
    private var failed = false
    fun offer(bytes: ByteArray): Int {
        if (bytes.isEmpty() || closed || failed) return 0
        if (pendingBytes + bytes.size > 1_048_576) { failed = true; return 2 }
        pending.addLast(bytes.copyOf()); pendingBytes += bytes.size
        return 1
    }
    fun take(maximum: Int): ByteArray? {
        val chunk = pending.pollFirst() ?: return null
        pendingBytes -= chunk.size
        if (chunk.size <= maximum) return chunk
        val head = chunk.copyOf(maximum)
        val tail = chunk.copyOfRange(maximum, chunk.size)
        pending.addFirst(tail); pendingBytes += tail.size
        return head
    }
    fun status(): Int = (if (closed) 1 else 0) or (if (failed) 2 else 0)
    fun fail(): Boolean { if (closed || failed) return false; failed = true; return true }
    fun close(): Boolean { if (closed) return false; closed = true; return true }
}
