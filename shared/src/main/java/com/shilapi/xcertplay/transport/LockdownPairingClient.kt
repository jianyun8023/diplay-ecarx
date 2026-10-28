package com.shilapi.xcertplay.transport

import android.util.Log
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Blocking, plaintext Lockdown pairing over an already-open USBMUX host.
 *
 * Call [pair] from a worker thread, never Android's main thread. This client owns only the
 * connection it creates: it closes the plist channel (and therefore that connection) after every
 * outcome, but leaves [Iap2UsbMuxHost] open for its caller. It neither persists material nor
 * starts a session, negotiates TLS, starts a service, or establishes any CarPlay/iAP2 session.
 */
class LockdownPairingClient internal constructor(
    private val connect: (Long) -> BlockingDuplexByteStream,
    private val createRecord: (ByteArray, String, String, String) -> LockdownPairRecord =
        LockdownPairRecordGenerator::generate,
) {
    constructor(host: Iap2UsbMuxHost) : this({ timeoutMillis ->
        host.connect(destinationPort = Iap2UsbMuxHost.LOCKDOWN_PORT, timeoutMillis = timeoutMillis)
    })
    /**
     * Fetches the two pairing inputs, generates the local record, and sends plaintext Pair.
     *
     * [totalTimeoutMillis] covers the entire operation, including a pending pairing dialog.
     * [isCancelled] is checked between blocking protocol operations and while waiting to retry a
     * pending dialog. Pair response reads check cancellation every 100 ms without discarding
     * partial plist bytes; ordinary setup reads remain bounded to five seconds.
     * [onTrustPending] is called only after an explicit pending-dialog response from the phone.
     */
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
        require(totalTimeoutMillis in 1..MAXIMUM_TOTAL_TIMEOUT_MILLIS) {
            "totalTimeoutMillis must be between 1 and $MAXIMUM_TOTAL_TIMEOUT_MILLIS"
        }

        val deadline = Deadline(totalTimeoutMillis)
        checkCancelled(isCancelled)
        val connection = connect(stepTimeoutMillis(deadline))
        LockdownPlistChannel(connection).use { channel ->
            setUntrustedHostBuid(channel, label, systemBuid, deadline, isCancelled)
            val record = generatePairRecord(channel, label, hostId, systemBuid, deadline, isCancelled)
            Log.i(TAG, "lockdown pair prepared hostId=uuid systemBuid=uuid " +
                "deviceKey=${record.devicePublicKeyPem.size} deviceCert=${record.deviceCertificatePem.size} " +
                "hostCert=${record.hostCertificatePem.size} rootCert=${record.rootCertificatePem.size}")
            var attempt = 0
            while (true) {
                checkCancelled(isCancelled)
                Log.i(TAG, "lockdown pair attempt=${++attempt}")
                // A human may need more than the ordinary five-second protocol step budget.
                // Keep both the stream and partially received plist alive while waiting.
                val response = try {
                    channel.requestAwaitingResponse(pairRequest(label, record), deadline.remainingMillis()) {
                        checkCancelled(isCancelled)
                        deadline.remainingMillis()
                    }
                } catch (_: LockdownResponseTimedOut) {
                    throw LockdownPairingException.TimedOut
                }
                checkCancelled(isCancelled)
                when (val error = response.errorCodeOrNull()) {
                    null -> return PairedRecord(record, response.entries["EscrowBag"].asOptionalData())
                    "PairingDialogResponsePending" -> {
                        Log.i(TAG, "lockdown trust pending; keeping connection open")
                        onTrustPending()
                        waitForRetry(deadline, isCancelled)
                    }
                    else -> throwPairingError(error)
                }
            }
        }
    }

    private fun generatePairRecord(
        channel: LockdownPlistChannel,
        label: String,
        hostId: String,
        systemBuid: String,
        deadline: Deadline,
        isCancelled: () -> Boolean,
    ): LockdownPairRecord {
        val devicePublicKey = getValue(channel, label, "DevicePublicKey", deadline, isCancelled)
            as? LockdownPlistValue.Data
            ?: throw LockdownPairingException.InvalidResponse("DevicePublicKey was not data")
        val wifiAddress = getValue(channel, label, "WiFiAddress", deadline, isCancelled)
            as? LockdownPlistValue.Text
            ?: throw LockdownPairingException.InvalidResponse("WiFiAddress was not text")
        return createRecord(devicePublicKey.bytes, wifiAddress.value, hostId, systemBuid)
    }

    private fun pairRequest(
        label: String,
        pairRecord: LockdownPairRecord,
    ): LockdownPlistValue.Dictionary = LockdownPlistValue.Dictionary(
        linkedMapOf(
            "Label" to LockdownPlistValue.Text(label),
            "PairRecord" to pairRecord.toPairRequestDictionary(),
            "Request" to LockdownPlistValue.Text("Pair"),
            "ProtocolVersion" to LockdownPlistValue.Text("2"),
            "PairingOptions" to LockdownPlistValue.Dictionary(
                linkedMapOf("ExtendedPairingErrors" to LockdownPlistValue.Boolean(true)),
            ),
        ),
    )

    private fun setUntrustedHostBuid(
        channel: LockdownPlistChannel,
        label: String,
        systemBuid: String,
        deadline: Deadline,
        isCancelled: () -> Boolean,
    ) {
        checkCancelled(isCancelled)
        val response = channel.request(
            LockdownPlistValue.Dictionary(
                linkedMapOf(
                    "Label" to LockdownPlistValue.Text(label),
                    "Request" to LockdownPlistValue.Text("SetValue"),
                    "Key" to LockdownPlistValue.Text("UntrustedHostBUID"),
                    "Value" to LockdownPlistValue.Text(systemBuid),
                ),
            ),
            stepTimeoutMillis(deadline),
        )
        checkCancelled(isCancelled)
        response.errorCodeOrNull()?.let(::throwPairingError)
        Log.i(TAG, "lockdown UntrustedHostBUID set")
    }

    private fun getValue(
        channel: LockdownPlistChannel,
        label: String,
        key: String,
        deadline: Deadline,
        isCancelled: () -> Boolean,
    ): LockdownPlistValue {
        checkCancelled(isCancelled)
        val response = channel.request(
            LockdownPlistValue.Dictionary(
                linkedMapOf(
                    "Label" to LockdownPlistValue.Text(label),
                    "Request" to LockdownPlistValue.Text("GetValue"),
                    "Key" to LockdownPlistValue.Text(key),
                ),
            ),
            stepTimeoutMillis(deadline),
        )
        checkCancelled(isCancelled)
        response.errorCodeOrNull()?.let(::throwPairingError)
        return response.entries["Value"]
            ?: throw LockdownPairingException.InvalidResponse("GetValue response omitted Value")
    }

    private fun LockdownPlistValue?.asOptionalData(): ByteArray? = when (this) {
        null -> null
        is LockdownPlistValue.Data -> bytes
        else -> throw LockdownPairingException.InvalidResponse("EscrowBag was not data")
    }

    private fun LockdownPlistValue.Dictionary.errorCodeOrNull(): String? {
        return when (val error = entries["Error"] ?: return null) {
            is LockdownPlistValue.Text -> error.value
            is LockdownPlistValue.Integer -> (entries["ErrorString"] as? LockdownPlistValue.Text)?.value
                ?: error.value.toString()
            else -> throw LockdownPairingException.InvalidResponse("Error was not text or integer")
        }
    }

    /** Explicit refusal/locked responses are terminal regardless of which setup step sent them. */
    private fun throwPairingError(code: String): Nothing = when (code) {
        "UserDeniedPairing" -> throw LockdownPairingException.UserDeniedPairing
        "PasswordProtected" -> throw LockdownPairingException.PasswordProtected
        else -> throw LockdownPairingException.RemoteError(code)
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw LockdownPairingException.Cancelled
    }

    private fun stepTimeoutMillis(deadline: Deadline): Long =
        minOf(MAXIMUM_STEP_TIMEOUT_MILLIS, deadline.remainingMillis())

    private fun waitForRetry(deadline: Deadline, isCancelled: () -> Boolean) {
        var remaining = RETRY_INTERVAL_MILLIS
        while (remaining > 0) {
            checkCancelled(isCancelled)
            val sleepMillis = minOf(RETRY_CHECK_MILLIS, remaining, deadline.remainingMillis())
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LockdownPairingException.Cancelled
            }
            remaining -= sleepMillis
        }
    }

    private class Deadline(timeoutMillis: Long) {
        private val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND

        fun remainingMillis(): Long {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) throw LockdownPairingException.TimedOut
            return (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val RETRY_INTERVAL_MILLIS = 1_000L
        const val RETRY_CHECK_MILLIS = 100L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAXIMUM_STEP_TIMEOUT_MILLIS = 5_000L
        const val MAXIMUM_TOTAL_TIMEOUT_MILLIS = 5 * 60_000L
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
