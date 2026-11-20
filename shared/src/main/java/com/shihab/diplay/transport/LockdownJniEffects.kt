package com.shihab.diplay.transport

/**
 * The one place the Lockdown JNI step results are translated: clients consume named actions with
 * typed fields and never read action indices, cast metadata or dispatch on raw action numbers.
 * The layout (kind `IntArray`, optional text/request, `LongArray` numbers, optional payload and
 * extra) is private to this adapter; decoding happens before any I/O, so an unknown or malformed
 * action fails as a recognizable contract error with no platform call started.
 */
internal class LockdownContractException(message: String) :
    IllegalStateException("Lockdown JNI contract violation: $message")

/** Named effects the Rust pairing state machine asks the platform to execute, in its own order. */
internal sealed interface LockdownPairAction {
    /** Report the current cancellation flag before the state machine proceeds. */
    data object Check : LockdownPairAction
    /** Open the Lockdown connection with this timeout budget. */
    data class Connect(val timeoutMillis: Long) : LockdownPairAction
    /** Send the request and wait; [waitForTrust] marks the human wait that stays cancellable. */
    data class Request(
        val request: Array<Any?>,
        val timeoutMillis: Long,
        val waitForTrust: Boolean,
    ) : LockdownPairAction
    /** Build the pair record from the device public key and WiFi address the phone reported. */
    data class Generate(val devicePublicKey: ByteArray, val wifi: String) : LockdownPairAction
    data object LogUntrustedBuidSet : LockdownPairAction
    data object LogPairPrepared : LockdownPairAction
    data class LogPairAttempt(val attempt: Long) : LockdownPairAction
    /** The phone is showing its trust dialog; keep the connection open while waiting. */
    data object TrustPending : LockdownPairAction
    data class Sleep(val millis: Long) : LockdownPairAction
    data class Done(val escrowBag: ByteArray?) : LockdownPairAction
    data class Error(val code: LockdownPairErrorCode, val text: String) : LockdownPairAction
}

/** Typed pairing failures; the numbers below are the contract the Rust side reports. */
internal enum class LockdownPairErrorCode(val native: Int) {
    CANCELLED(0),
    TIMED_OUT(1),
    USER_DENIED(2),
    PASSWORD_PROTECTED(3),
    REMOTE(4),
    INVALID_RESPONSE(5),
    ;

    companion object {
        fun of(native: Int): LockdownPairErrorCode? = entries.firstOrNull { it.native == native }
    }
}

/** Named effects of the Rust StartSession/StartService negotiation. */
internal sealed interface LockdownServiceAction {
    data class ConnectLockdown(val port: Int, val timeoutMillis: Long) : LockdownServiceAction
    data class Request(val request: Array<Any?>, val timeoutMillis: Long) : LockdownServiceAction
    data class SwitchToTls(val timeoutMillis: Long) : LockdownServiceAction
    data class ConnectService(val port: Int, val timeoutMillis: Long, val tls: Boolean) : LockdownServiceAction
    data object Done : LockdownServiceAction
    /** [reason] is the structured rejection code; 1 invalid pair record, 2 invalid host id, 0 other. */
    data class Error(val reason: Int, val text: String) : LockdownServiceAction
}

/** Named platform facts the pairing loop reports back to the Rust state machine. */
internal sealed interface LockdownPairEvent {
    /** The cancellation check the state machine requested; [cancelled] is the current flag. */
    data class Cancelled(val cancelled: Boolean) : LockdownPairEvent
    data object Continue : LockdownPairEvent
    data class Response(val value: Array<Any?>) : LockdownPairEvent
    data class RecordGenerated(val value: Array<Any?>) : LockdownPairEvent
    data object SleepInterrupted : LockdownPairEvent
    /** The local human-response deadline expired; transport failures never use this event. */
    data object ResponseTimedOut : LockdownPairEvent
}

/** Outcome of the cancellable trust-dialog wait. */
internal enum class LockdownTrustWait { WAITING, CANCELLED, TIMED_OUT }

internal object LockdownJniEffects {
    /** One pairing step: report [event], then decode the next named action. */
    fun pairStep(state: Array<Any?>, event: LockdownPairEvent, now: Long): LockdownPairAction {
        val (number, value, cancelled) = when (event) {
            is LockdownPairEvent.Cancelled -> Triple(1, null, event.cancelled)
            LockdownPairEvent.Continue -> Triple(2, null, false)
            is LockdownPairEvent.Response -> Triple(3, event.value, false)
            is LockdownPairEvent.RecordGenerated -> Triple(4, event.value, false)
            LockdownPairEvent.SleepInterrupted -> Triple(5, null, false)
            LockdownPairEvent.ResponseTimedOut -> Triple(6, null, false)
        }
        return pairAction(NativeLockdown.pairStep(state, number, value, cancelled, now))
    }

    /** The cancellable trust wait between pairing attempts; timeouts here are the overall budget. */
    fun trustWait(state: Array<Any?>, cancelled: Boolean, now: Long): LockdownTrustWait =
        when (NativeLockdown.pairWait(state, cancelled, now)) {
            1 -> LockdownTrustWait.CANCELLED
            2 -> LockdownTrustWait.TIMED_OUT
            else -> LockdownTrustWait.WAITING
        }

    /** One service negotiation step; [response] is the previous request's native reply. */
    fun serviceStep(state: Array<Any?>, response: Array<Any?>?): LockdownServiceAction =
        serviceAction(NativeLockdown.serviceStep(state, response))

    internal fun pairAction(raw: Array<Any?>): LockdownPairAction {
        val meta = raw.numbers()
        return when (raw.kind()) {
            1 -> LockdownPairAction.Check
            2 -> LockdownPairAction.Connect(meta.value(0))
            3 -> LockdownPairAction.Request(
                request = raw.request(1),
                timeoutMillis = meta.value(0),
                waitForTrust = meta.value(1) != 0L,
            )
            4 -> LockdownPairAction.Generate(
                devicePublicKey = raw.payload(3),
                wifi = raw.text(4),
            )
            5 -> LockdownPairAction.LogUntrustedBuidSet
            6 -> LockdownPairAction.LogPairPrepared
            7 -> LockdownPairAction.LogPairAttempt(meta.value(0))
            8 -> LockdownPairAction.TrustPending
            9 -> LockdownPairAction.Sleep(meta.value(0))
            10 -> LockdownPairAction.Done(raw.optionalPayload(3))
            11 -> LockdownPairAction.Error(
                code = LockdownPairErrorCode.of(meta.value(0).toInt())
                    ?: contract("unknown pairing error code ${meta.value(0)}"),
                text = raw.text(1),
            )
            else -> contract("unknown pairing action kind ${raw.kind()}")
        }
    }

    internal fun serviceAction(raw: Array<Any?>): LockdownServiceAction {
        val meta = raw.numbers()
        return when (raw.kind()) {
            2 -> LockdownServiceAction.ConnectLockdown(
                port = meta.value(0).toInt(),
                timeoutMillis = meta.value(1),
            )
            3 -> LockdownServiceAction.Request(
                request = raw.request(1),
                timeoutMillis = meta.value(0),
            )
            4 -> LockdownServiceAction.SwitchToTls(meta.value(0))
            5 -> LockdownServiceAction.ConnectService(
                port = meta.value(0).toInt(),
                timeoutMillis = meta.value(1),
                tls = meta.value(2) != 0L,
            )
            10 -> LockdownServiceAction.Done
            11 -> LockdownServiceAction.Error(
                reason = meta.value(0).toInt(),
                text = raw.text(1),
            )
            else -> contract("unknown service action kind ${raw.kind()}")
        }
    }

    private fun contract(message: String): Nothing = throw LockdownContractException(message)

    private fun Array<Any?>.kind(): Int =
        (getOrNull(0) as? IntArray)?.firstOrNull() ?: contract("action is missing its kind header")

    private fun Array<Any?>.numbers(): LongArray =
        getOrNull(2) as? LongArray ?: contract("action is missing its number fields")

    private fun LongArray.value(index: Int): Long =
        getOrNull(index) ?: contract("action is missing number field $index")

    private fun Array<Any?>.text(index: Int): String =
        getOrNull(index) as? String ?: contract("action is missing its text field $index")

    private fun Array<Any?>.request(index: Int): Array<Any?> =
        getOrNull(index) as? Array<Any?> ?: contract("action is missing its request value")

    private fun Array<Any?>.payload(index: Int): ByteArray =
        getOrNull(index) as? ByteArray ?: contract("action is missing its byte payload $index")

    private fun Array<Any?>.optionalPayload(index: Int): ByteArray? =
        getOrNull(index)?.let { it as? ByteArray ?: contract("action has a malformed byte payload $index") }
}
