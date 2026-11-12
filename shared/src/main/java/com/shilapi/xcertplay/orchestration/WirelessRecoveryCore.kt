package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.NativeMedia
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationException
import com.shilapi.xcertplay.mfi.MfiAuthenticationFailedException
import com.shilapi.xcertplay.mfi.MfiException
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import com.shilapi.xcertplay.transport.Iap2IdentificationException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

/** Tokens are values, never native allocations. Only the selected implementation interprets them. */
internal object WirelessRecoveryCore {
    init { NativeMedia.requireAvailable() }
    fun budgetCreate(): Long = NativeWirelessRecovery.budgetCreate()
    fun budgetAdvance(token: Long, stage: Int): Long =
        NativeWirelessRecovery.budgetAdvance(token, stage).also { check(it >= 0) }
    fun budgetQuery(token: Long, field: Int): Int = NativeWirelessRecovery.budgetQuery(token, field)
    fun attemptCreate(): Long = NativeWirelessRecovery.attemptCreate()
    fun attemptUpdate(token: Long, event: Int, phase: Int = 0): Long =
        NativeWirelessRecovery.attemptUpdate(token, event, phase).also { check(it >= 0) }
    fun attemptQuery(token: Long, field: Int): Int = NativeWirelessRecovery.attemptQuery(token, field)
    fun classify(facts: Int): Int = NativeWirelessRecovery.classify(facts)
    fun attemptFailure(token: Long, facts: Int): Int = NativeWirelessRecovery.attemptFailure(token, facts)
}

internal object NativeWirelessRecovery {
    @JvmStatic external fun budgetCreate(): Long
    @JvmStatic external fun budgetAdvance(token: Long, stage: Int): Long
    @JvmStatic external fun budgetQuery(token: Long, field: Int): Int
    @JvmStatic external fun attemptCreate(): Long
    @JvmStatic external fun attemptUpdate(token: Long, event: Int, phase: Int): Long
    @JvmStatic external fun attemptQuery(token: Long, field: Int): Int
    @JvmStatic external fun classify(facts: Int): Int
    @JvmStatic external fun attemptFailure(token: Long, facts: Int): Int
}

/** Java type/cause inspection produces facts; core code decides precedence and retry policy. */
internal object WirelessFailureFacts {
    const val TOP_ERROR = 1
    const val TOP_BOOTSTRAP = 1 shl 1
    const val TOP_CANCEL = 1 shl 2
    const val TOP_IO = 1 shl 3
    const val SECURITY = 1 shl 4
    const val MFI_REJECTED = 1 shl 5
    const val RADIO_OFF = 1 shl 6
    const val IDENTIFICATION = 1 shl 7
    const val MFI_TERMINAL = 1 shl 8
    const val NO_PAIRED = 1 shl 9
    const val ANY_ERROR = 1 shl 10
    const val ARGUMENT = 1 shl 11
    const val STATE = 1 shl 12
    const val INTERRUPTED = 1 shl 13
    const val INTERRUPTED_IO = 1 shl 14
    const val CANCEL = 1 shl 15
    const val STARTUP = 1 shl 16
    const val CONNECTION = 1 shl 17
    const val MFI_RETRY = 1 shl 18
    const val MFI = 1 shl 19
    const val CHOSEN_UNPAIRED = 1 shl 20
    const val NOT_CHOSEN = 1 shl 21
    const val UNAVAILABLE = 1 shl 22

    fun from(error: Throwable): Int {
        var facts = 0
        if (error is Error) facts = facts or TOP_ERROR
        if (error is WirelessBootstrapRecovery.BootstrapFailure) facts = facts or TOP_BOOTSTRAP
        if (error is CancellationException) facts = facts or TOP_CANCEL
        if (error is IOException) facts = facts or TOP_IO
        for (cause in generateSequence(error) { it.cause }.take(8)) {
            if (cause is SecurityException) facts = facts or SECURITY
            if (cause is MfiAuthenticationFailedException) facts = facts or MFI_REJECTED
            if (cause.message?.contains(BluetoothRadioCheck.RADIO_OFF_MARK) == true) facts = facts or RADIO_OFF
            if (cause is Iap2IdentificationException) facts = facts or IDENTIFICATION
            if (cause is Iap2MfiAuthenticationException) facts = facts or if (cause.retryable) MFI_RETRY else MFI_TERMINAL
            if (cause is Error) facts = facts or ANY_ERROR
            if (cause is IllegalArgumentException) facts = facts or ARGUMENT
            if (cause is IllegalStateException) facts = facts or STATE
            if (cause is InterruptedException) facts = facts or INTERRUPTED
            if (cause is InterruptedIOException) facts = facts or INTERRUPTED_IO
            if (cause is CancellationException) facts = facts or CANCEL
            if (cause is WirelessStartupException) facts = facts or STARTUP
            if (cause is ConnectionFailureException) {
                if (cause !is WirelessBootstrapRecovery.BootstrapFailure) facts = facts or CONNECTION
                facts = facts or when (cause.reason) {
                    ConnectionFailureCause.NO_PAIRED_IPHONE -> NO_PAIRED
                    ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED -> CHOSEN_UNPAIRED
                    ConnectionFailureCause.IPHONE_NOT_CHOSEN -> NOT_CHOSEN
                    ConnectionFailureCause.WIRELESS_CARPLAY_UNAVAILABLE -> UNAVAILABLE
                    else -> 0
                }
            }
            if (cause is MfiException) facts = facts or MFI
        }
        return facts
    }
}
