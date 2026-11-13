package com.shihab.diplay.transport

import android.util.Log
import java.io.IOException
import java.security.GeneralSecurityException

/** Platform effects for the Rust Lockdown pairing workflow; each call owns one connection. */
class LockdownPairingClient internal constructor(
    private val connect: (Long) -> BlockingDuplexByteStream,
    private val createRecord: (ByteArray, String, String, String) -> LockdownPairRecord =
        LockdownPairRecordGenerator::generate,
) {
    constructor(host: Iap2UsbMuxHost) : this({ timeout ->
        host.connect(destinationPort = Iap2UsbMuxHost.LOCKDOWN_PORT, timeoutMillis = timeout)
    })

    @Throws(IphoneUsbException::class, LockdownPairingException::class, GeneralSecurityException::class)
    fun pair(
        label: String,
        hostId: String,
        systemBuid: String,
        totalTimeoutMillis: Long,
        isCancelled: () -> Boolean = { false },
        onTrustPending: () -> Unit = {},
    ): PairedRecord {
        require(label.isNotBlank()) { "label must not be blank" }
        require(hostId.isNotBlank()) { "hostId must not be blank" }
        require(systemBuid.isNotBlank()) { "systemBuid must not be blank" }
        val state = NativeLockdown.pairCreate(label, hostId, systemBuid, totalTimeoutMillis, System.nanoTime())
        val connectPlan = NativeLockdown.pairStep(state, 1, null, isCancelled(), System.nanoTime())
        if ((connectPlan[0] as IntArray)[0] == 11) pairingError(connectPlan)
        val connection = connect((connectPlan[2] as LongArray)[0])
        return LockdownPlistChannel(connection).use { channel ->
            var record: LockdownPairRecord? = null
            var action = NativeLockdown.pairStep(state, 2, null, false, System.nanoTime())
            val checkWaiting = {
                when (NativeLockdown.pairWait(state, isCancelled(), System.nanoTime())) {
                    1 -> throw LockdownPairingException.Cancelled
                    2 -> throw LockdownPairingException.TimedOut
                    else -> Unit
                }
            }
            while (true) {
                val kind = (action[0] as IntArray)[0]
                val meta = action[2] as LongArray
                var event = 2
                var value: Array<Any?>? = null
                var cancelled = false
                when (kind) {
                    1 -> { event = 1; cancelled = isCancelled() }
                    3 -> {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val request = action[1] as Array<Any?>
                            value = channel.requestNative(request, meta[0], if (meta[1] != 0L) checkWaiting else null)
                            event = 3
                        } catch (_: LockdownResponseTimedOut) {
                            event = 6
                        }
                    }
                    4 -> {
                        val created = createRecord(action[3] as ByteArray, action[4] as String, hostId, systemBuid)
                        record = created
                        value = arrayOf(created.deviceCertificatePem, created.hostCertificatePem, created.hostId,
                            created.rootCertificatePem, created.systemBuid)
                        event = 4
                    }
                    5 -> Log.i("xcertplay-usb", "lockdown UntrustedHostBUID set")
                    6 -> {
                        val created = record!!
                        Log.i("xcertplay-usb", "lockdown pair prepared hostId=uuid systemBuid=uuid " +
                            "deviceKey=${created.devicePublicKeyPem.size} deviceCert=${created.deviceCertificatePem.size} " +
                            "hostCert=${created.hostCertificatePem.size} rootCert=${created.rootCertificatePem.size}")
                    }
                    7 -> Log.i("xcertplay-usb", "lockdown pair attempt=${meta[0]}")
                    8 -> {
                        Log.i("xcertplay-usb", "lockdown trust pending; keeping connection open")
                        onTrustPending()
                    }
                    9 -> try { Thread.sleep(meta[0]) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        event = 5
                    }
                    10 -> return@use PairedRecord(record!!, action[3] as ByteArray?)
                    11 -> pairingError(action)
                    else -> error("Invalid native pairing effect")
                }
                action = NativeLockdown.pairStep(state, event, value, cancelled, System.nanoTime())
            }
            @Suppress("UNREACHABLE_CODE")
            error("Pairing effect loop ended")
        }
    }

    private fun pairingError(action: Array<Any?>): Nothing = when ((action[2] as LongArray)[0].toInt()) {
        0 -> throw LockdownPairingException.Cancelled
        1 -> throw LockdownPairingException.TimedOut
        2 -> throw LockdownPairingException.UserDeniedPairing
        3 -> throw LockdownPairingException.PasswordProtected
        4 -> throw LockdownPairingException.RemoteError(action[1] as String)
        5 -> throw LockdownPairingException.InvalidResponse(action[1] as String)
        else -> error("Invalid native pairing error")
    }
}

/** Successful pairing material kept in memory only. Byte-array accessors return defensive copies. */
class PairedRecord internal constructor(
    val pairRecord: LockdownPairRecord,
    escrowBag: ByteArray?,
) {
    private val storedEscrowBag = escrowBag?.copyOf()

    val escrowBag: ByteArray?
        get() = storedEscrowBag?.copyOf()

    override fun toString(): String = "PairedRecord(redacted)"
}

/** Typed pairing failures; their messages never include pairing keys, certificates, or escrow data. */
sealed class LockdownPairingException(message: String) : IOException(message) {
    data object Cancelled : LockdownPairingException("Lockdown pairing was cancelled")
    data object TimedOut : LockdownPairingException("Lockdown pairing timed out")
    data object UserDeniedPairing : LockdownPairingException("The user denied Lockdown pairing")
    data object PasswordProtected : LockdownPairingException("The iPhone is password protected")
    class RemoteError(val code: String) : LockdownPairingException("Lockdown pairing failed: $code")
    class InvalidResponse(message: String) : LockdownPairingException("Invalid Lockdown pairing response: $message")
}
