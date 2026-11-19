package com.shihab.diplay.transport

import java.io.Closeable
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean

/** Platform I/O and TLS effects for Rust-owned StartSession/StartService negotiation. */
class LockdownCarKitClient internal constructor(
    private val connect: (Int, Long) -> BlockingDuplexByteStream,
    private val tlsOpen: (BlockingDuplexByteStream, LockdownPairRecord, Long) -> BlockingDuplexByteStream =
        TlsDuplexChannel::open,
) {
    constructor(host: Iap2UsbMuxHost) : this({ port, timeout -> host.connect(port, timeout) })

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
                when (val action = LockdownJniEffects.serviceStep(state, response)) {
                    is LockdownServiceAction.ConnectLockdown ->
                        channel = LockdownPlistChannel(connect(action.port, action.timeoutMillis))
                    is LockdownServiceAction.Request ->
                        response = channel!!.requestNative(action.request, action.timeoutMillis)
                    is LockdownServiceAction.SwitchToTls -> {
                        val tls = channel!!.use { plaintext ->
                            tlsOpen(plaintext.detach(), pairRecord, action.timeoutMillis)
                        }
                        channel = LockdownPlistChannel(tls)
                    }
                    is LockdownServiceAction.ConnectService -> {
                        val connection = connect(action.port, action.timeoutMillis)
                        service = if (action.tls) tlsOpen(connection, pairRecord, action.timeoutMillis) else connection
                    }
                    is LockdownServiceAction.Done -> {
                        val result = CarkitServiceStream(service!!, channel!!)
                        handedOff = true
                        return@use result
                    }
                    is LockdownServiceAction.Error -> throw IphoneUsbException.CarkitRejected(action.text, action.reason)
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
