package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean

/** Platform I/O and TLS effects for Rust-owned StartSession/StartService negotiation. */
class LockdownCarKitClient(private val host: Iap2UsbMuxHost) {
    @Throws(IphoneUsbException::class, GeneralSecurityException::class)
    fun open(pairedRecord: PairedRecord, label: String): BlockingDuplexByteStream = open(pairedRecord.pairRecord, label)

    @Throws(IphoneUsbException::class, GeneralSecurityException::class)
    fun open(pairRecord: LockdownPairRecord, label: String): BlockingDuplexByteStream =
        openService(pairRecord, label, "com.apple.carkit.service")

    fun openService(pairRecord: LockdownPairRecord, label: String, serviceName: String): BlockingDuplexByteStream {
        require(label.isNotBlank()) { "label must not be blank" }
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }
        val state = NativeLockdown.serviceCreate(label, pairRecord.hostId, pairRecord.systemBuid, serviceName)
        var channel: LockdownPlistChannel? = null
        var service: BlockingDuplexByteStream? = null
        var handedOff = false
        // use preserves the original failure if cleanup also fails. Before handoff both streams
        // belong here, including failures before a service stream has been created.
        return Closeable {
            if (!handedOff) {
                try { service?.close() } finally { channel?.close() }
            }
        }.use {
            var response: Array<Any?>? = null
            while (true) {
                val action = NativeLockdown.serviceStep(state, response)
                val meta = action[2] as LongArray
                when ((action[0] as IntArray)[0]) {
                    2 -> channel = LockdownPlistChannel(host.connect(meta[0].toInt(), meta[1]))
                    3 -> {
                        @Suppress("UNCHECKED_CAST")
                        val request = action[1] as Array<Any?>
                        response = channel!!.requestNative(request, meta[0])
                    }
                    4 -> {
                        val tls = channel!!.use { plaintext ->
                            TlsDuplexChannel.open(plaintext.detach(), pairRecord, meta[0])
                        }
                        channel = LockdownPlistChannel(tls)
                    }
                    5 -> {
                        val connection = host.connect(meta[0].toInt(), meta[1])
                        service = if (meta[2] != 0L) TlsDuplexChannel.open(connection, pairRecord, meta[1]) else connection
                    }
                    10 -> {
                        val result = CarkitServiceStream(service!!, channel!!)
                        handedOff = true
                        return@use result
                    }
                    11 -> throw IphoneUsbException.Protocol(action[1] as String)
                    else -> error("Invalid native service effect")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Service effect loop ended")
        }
    }

    /** Keeps the Lockdown session that created carkit alive for the service stream's lifetime. */
    private class CarkitServiceStream(
        private val service: BlockingDuplexByteStream,
        private val lockdown: Closeable,
    ) : BlockingDuplexByteStream {
        private val closed = AtomicBoolean(false)

        override fun send(data: ByteArray) = service.send(data)

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            service.recv(maxBytes, timeoutMillis)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                service.close()
            } finally {
                lockdown.close()
            }
        }
    }

}
