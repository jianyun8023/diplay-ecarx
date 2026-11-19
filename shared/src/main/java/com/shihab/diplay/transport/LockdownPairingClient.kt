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
        val connection = when (val first = LockdownJniEffects.pairStep(state, LockdownPairEvent.Cancelled(isCancelled()), System.nanoTime())) {
            is LockdownPairAction.Connect -> connect(first.timeoutMillis)
            is LockdownPairAction.Error -> pairingError(first)
            else -> throw LockdownContractException("pairing did not ask to connect first: ${first::class.simpleName}")
        }
        return LockdownPlistChannel(connection).use { channel ->
            var record: LockdownPairRecord? = null
            var action = LockdownJniEffects.pairStep(state, LockdownPairEvent.Continue, System.nanoTime())
            val checkWaiting = {
                when (LockdownJniEffects.trustWait(state, isCancelled(), System.nanoTime())) {
                    LockdownTrustWait.CANCELLED -> throw LockdownPairingException.Cancelled
                    LockdownTrustWait.TIMED_OUT -> throw LockdownPairingException.TimedOut
                    LockdownTrustWait.WAITING -> Unit
                }
            }
            while (true) {
                var event: LockdownPairEvent = LockdownPairEvent.Continue
                when (action) {
                    is LockdownPairAction.Check -> event = LockdownPairEvent.Cancelled(isCancelled())
                    is LockdownPairAction.Connect -> throw LockdownContractException("pairing asked to connect twice")
                    is LockdownPairAction.Request -> try {
                        event = LockdownPairEvent.Response(
                            channel.requestNative(action.request, action.timeoutMillis, if (action.waitForTrust) checkWaiting else null),
                        )
                    } catch (_: LockdownResponseTimedOut) {
                        event = LockdownPairEvent.ResponseTimedOut
                    }
                    is LockdownPairAction.Generate -> {
                        val created = createRecord(action.devicePublicKey, action.wifi, hostId, systemBuid)
                        record = created
                        event = LockdownPairEvent.RecordGenerated(arrayOf(
                            created.deviceCertificatePem, created.hostCertificatePem, created.hostId,
                            created.rootCertificatePem, created.systemBuid,
                        ))
                    }
                    is LockdownPairAction.LogUntrustedBuidSet -> Log.i("diplay-usb", "lockdown UntrustedHostBUID set")
                    is LockdownPairAction.LogPairPrepared -> {
                        val created = record!!
                        Log.i("diplay-usb", "lockdown pair prepared hostId=uuid systemBuid=uuid " +
                            "deviceKey=${created.devicePublicKeyPem.size} deviceCert=${created.deviceCertificatePem.size} " +
                            "hostCert=${created.hostCertificatePem.size} rootCert=${created.rootCertificatePem.size}")
                    }
                    is LockdownPairAction.LogPairAttempt -> Log.i("diplay-usb", "lockdown pair attempt=${action.attempt}")
                    is LockdownPairAction.TrustPending -> {
                        Log.i("diplay-usb", "lockdown trust pending; keeping connection open")
                        onTrustPending()
                    }
                    is LockdownPairAction.Sleep -> try {
                        Thread.sleep(action.millis)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        event = LockdownPairEvent.SleepInterrupted
                    }
                    is LockdownPairAction.Done -> return@use PairedRecord(record!!, action.escrowBag)
                    is LockdownPairAction.Error -> pairingError(action)
                }
                action = LockdownJniEffects.pairStep(state, event, System.nanoTime())
            }
            @Suppress("UNREACHABLE_CODE")
            error("Pairing effect loop ended")
        }
    }

    private fun pairingError(error: LockdownPairAction.Error): Nothing = when (error.code) {
        LockdownPairErrorCode.CANCELLED -> throw LockdownPairingException.Cancelled
        LockdownPairErrorCode.TIMED_OUT -> throw LockdownPairingException.TimedOut
        LockdownPairErrorCode.USER_DENIED -> throw LockdownPairingException.UserDeniedPairing
        LockdownPairErrorCode.PASSWORD_PROTECTED -> throw LockdownPairingException.PasswordProtected
        LockdownPairErrorCode.REMOTE -> throw LockdownPairingException.RemoteError(error.text)
        LockdownPairErrorCode.INVALID_RESPONSE -> throw LockdownPairingException.InvalidResponse(error.text)
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
