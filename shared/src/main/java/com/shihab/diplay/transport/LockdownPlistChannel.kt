package com.shihab.diplay.transport

import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets

/** The small plist value set needed by Lockdown messages. */
sealed class LockdownPlistValue {
    data class Dictionary(val entries: Map<String, LockdownPlistValue>) : LockdownPlistValue()
    data class Text(val value: String) : LockdownPlistValue()
    data class Integer(val value: Long) : LockdownPlistValue()
    data class Boolean(val value: kotlin.Boolean) : LockdownPlistValue()

    class Data(bytes: ByteArray) : LockdownPlistValue() {
        private val value = bytes.copyOf()

        val bytes: ByteArray
            get() = value.copyOf()
    }
}

/** Blocking platform stream adapter for the Rust Lockdown protocol owner. */
class LockdownPlistChannel(
    private val connection: BlockingDuplexByteStream,
    maximumMessageBytes: Int = 1024 * 1024,
    private val defaultTimeoutMillis: Long = 5_000L,
) : Closeable {
    private val stateLock = Any()
    private val ioLock = Any()
    private val state = NativeLockdown.channelCreate(maximumMessageBytes, defaultTimeoutMillis)

    fun send(message: LockdownPlistValue.Dictionary) = synchronized(ioLock) {
        val maximum = checkOpen()
        sendLocked(encode(message), maximum)
    }

    fun receive(timeoutMillis: Long = defaultTimeoutMillis): LockdownPlistValue.Dictionary = synchronized(ioLock) {
        val maximum = checkOpen()
        NativeLockdown.validateTimeout(timeoutMillis)
        dictionary(receiveLocked(maximum, timeoutMillis, null))
    }

    fun request(message: LockdownPlistValue.Dictionary, timeoutMillis: Long = defaultTimeoutMillis): LockdownPlistValue.Dictionary =
        synchronized(ioLock) {
            NativeLockdown.validateTimeout(timeoutMillis)
            val maximum = checkOpen()
            sendLocked(encode(message), maximum)
            dictionary(receiveLocked(maximum, timeoutMillis, null))
        }

    internal fun requestAwaitingResponse(
        message: LockdownPlistValue.Dictionary,
        timeoutMillis: Long,
        checkCancelled: () -> Unit,
    ): LockdownPlistValue.Dictionary = synchronized(ioLock) {
        NativeLockdown.validateTimeout(timeoutMillis)
        val maximum = checkOpen()
        checkCancelled()
        sendLocked(encode(message), maximum)
        dictionary(receiveLocked(maximum, timeoutMillis, checkCancelled))
    }

    /** Workflows supply native typed trees; cancellation still precedes XML encoding. */
    internal fun requestNative(message: Array<Any?>, timeoutMillis: Long, checkCancelled: (() -> Unit)? = null): Array<Any?> =
        synchronized(ioLock) {
            NativeLockdown.validateTimeout(timeoutMillis)
            val maximum = checkOpen()
            checkCancelled?.invoke()
            sendLocked(NativeLockdown.codec(NativeLockdown.encodeTree(message)) as String, maximum)
            receiveLocked(maximum, timeoutMillis, checkCancelled)
        }

    fun detach(): BlockingDuplexByteStream = synchronized(ioLock) {
        synchronized(stateLock) {
            when (NativeLockdown.channelState(state, 2).toInt()) {
                0 -> connection
                2 -> throw IphoneUsbException.DeviceUnavailable("Lockdown plist channel is already detached")
                else -> throw IphoneUsbException.DeviceUnavailable("Lockdown plist channel is closed")
            }
        }
    }

    override fun close() {
        val close = synchronized(stateLock) { NativeLockdown.channelState(state, 1) != 0L }
        // Interrupt blocked I/O without taking its monitor; GC state has no destroy/use race.
        if (close) connection.close()
    }

    private fun checkOpen(): Int = synchronized(stateLock) {
        val maximum = NativeLockdown.channelState(state, 0).toInt()
        if (maximum == 0) throw IphoneUsbException.DeviceUnavailable("Lockdown plist channel is closed")
        maximum
    }

    private fun encode(message: LockdownPlistValue.Dictionary): String =
        NativeLockdown.codec(NativeLockdown.encode(LockdownXmlInput(message))) as String

    private fun sendLocked(xml: String, maximum: Int) {
        val bytes = xml.toByteArray(StandardCharsets.UTF_8)
        connection.send(NativeLockdown.codec(NativeLockdown.frame(maximum, bytes)) as ByteArray)
    }

    private fun receiveLocked(maximum: Int, timeoutMillis: Long, checkCancelled: (() -> Unit)?): Array<Any?> {
        val read = NativeLockdown.read(maximum, LockdownIo(connection, checkCancelled), timeoutMillis, checkCancelled != null)
        when ((read[0] as IntArray)[0]) {
            1 -> throw IphoneUsbException.TimedOut(read[2] as String)
            2 -> throw LockdownResponseTimedOut()
            3 -> throw IphoneUsbException.Protocol(read[2] as String)
        }
        try {
            val tokens = LockdownXmlTokens((read[1] as ByteArray).toString(StandardCharsets.UTF_8))
            @Suppress("UNCHECKED_CAST")
            return NativeLockdown.codec(NativeLockdown.decode(tokens)) as Array<Any?>
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: Exception) {
            throw IphoneUsbException.Protocol("Invalid Lockdown plist XML: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun dictionary(tree: Array<Any?>): LockdownPlistValue.Dictionary =
        NativeLockdown.value(tree) as LockdownPlistValue.Dictionary
}

/** Only the local human-response framing deadline, never an underlying USB transport timeout. */
internal class LockdownResponseTimedOut : IOException("Timed out waiting for Lockdown response")
