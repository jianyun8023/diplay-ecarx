package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import android.os.Build
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

object UsbQueueReplay {
    val payload = byteArrayOf(11, 22, 33, 44)
    val outcomes = ArrayDeque<Boolean>()
    val sizes = mutableListOf<Int>()
    val legacySizes = mutableListOf<Int>()
    val legacyOutcomes = ArrayDeque<Boolean>()
    val buffers = mutableListOf<ByteBuffer>()
    var request: UsbRequest? = null
    var buffer: ByteBuffer? = null
    var queueException: RuntimeException? = null
    var timeout = false
    var onQueue: ((UsbRequest) -> Unit)? = null
    var transfer: ByteArray? = null
    var transferOffset = 0
    val completedBytes = mutableListOf<Int>()
    @Volatile var untimedWaits = 0
    /** Timed waits that time out once each before a completion is returned. */
    var timeouts = 0
    var cancelResult = true
    /** The next completion carries no bytes, as a cancelled read does. */
    var emptyCompletion = false
    var cancels = 0
    fun reset() {
        legacySizes.clear(); legacyOutcomes.clear(); outcomes.clear(); sizes.clear(); buffers.clear(); request = null; buffer = null
        queueException = null; timeout = false; onQueue = null
        transfer = null; transferOffset = 0; completedBytes.clear(); untimedWaits = 0
        timeouts = 0; cancelResult = true; emptyCompletion = false; cancels = 0
    }
}

@Implements(UsbRequest::class)
class CompatibilityUsbRequestShadow {
    @RealObject lateinit var request: UsbRequest
    @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint) = true
    @Implementation(minSdk = 26) fun queue(buffer: ByteBuffer): Boolean {
        UsbQueueReplay.sizes.add(buffer.remaining())
        UsbQueueReplay.buffers.add(buffer)
        if (Build.VERSION.SDK_INT in 26..27) {
            require(buffer.remaining() <= 16_384) { "number of remaining bytes is out of range [0, 16384]" }
        }
        UsbQueueReplay.queueException?.let { throw it }
        UsbQueueReplay.onQueue?.invoke(request)
        val queued = UsbQueueReplay.outcomes.pollFirst() ?: true
        if (queued) { UsbQueueReplay.request = request; UsbQueueReplay.buffer = buffer }
        return queued
    }
    /** Android 7.x queue; the buffer is filled from position 0 up to [length]. */
    @Implementation fun queue(buffer: ByteBuffer, length: Int): Boolean {
        check(buffer.position() == 0 && buffer.remaining() == length) { "Unexpected Android 7 queue range" }
        if (Build.VERSION.SDK_INT != 28) return queue(buffer)
        UsbQueueReplay.legacySizes += length
        val queued = UsbQueueReplay.legacyOutcomes.pollFirst() ?: false
        if (queued) { UsbQueueReplay.request = request; UsbQueueReplay.buffer = buffer }
        return queued
    }
    @Implementation fun cancel(): Boolean {
        UsbQueueReplay.cancels += 1
        return UsbQueueReplay.cancelResult
    }
    @Implementation fun close() = Unit
}

@Implements(UsbDeviceConnection::class)
class CompatibilityUsbConnectionShadow {
    @Implementation(minSdk = 26) fun requestWait(timeoutMillis: Long): UsbRequest {
        if (UsbQueueReplay.timeout) throw TimeoutException()
        if (UsbQueueReplay.timeouts > 0) {
            UsbQueueReplay.timeouts -= 1
            throw TimeoutException()
        }
        if (UsbQueueReplay.emptyCompletion) {
            UsbQueueReplay.emptyCompletion = false
            return UsbQueueReplay.request!!
        }
        val buffer = UsbQueueReplay.buffer!!
        val transfer = UsbQueueReplay.transfer
        if (transfer == null) {
            buffer.put(UsbQueueReplay.payload)
        } else {
            val count = minOf(buffer.remaining(), transfer.size - UsbQueueReplay.transferOffset)
            check(count > 0) { "No unread replay bytes" }
            buffer.put(transfer, UsbQueueReplay.transferOffset, count)
            UsbQueueReplay.transferOffset += count
            UsbQueueReplay.completedBytes.add(count)
        }
        return UsbQueueReplay.request!!
    }
    /** Android 7.x blocking wait. */
    @Implementation fun requestWait(): UsbRequest {
        UsbQueueReplay.untimedWaits += 1
        return requestWait(Long.MAX_VALUE)
    }
    @Implementation fun close() = Unit
}
