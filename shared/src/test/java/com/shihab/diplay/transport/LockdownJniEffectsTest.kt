package com.shihab.diplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the single JNI layout translation: every action number the pairing and
 * CarKit clients consume decodes to its named action with the fields the Rust side puts in;
 * unknown or malformed layouts fail as a recognizable contract error before any I/O starts.
 */
class LockdownJniEffectsTest {
    private val request = arrayOf<Any?>(intArrayOf(1), "Label")

    /** The layout the Rust `action()` helper builds: kind, text/request, numbers, payload, extra. */
    private fun raw(
        kind: Int,
        text: Any? = null,
        numbers: LongArray = longArrayOf(),
        payload: ByteArray? = null,
        extra: Any? = null,
    ): Array<Any?> = arrayOf(intArrayOf(kind), text, numbers, payload, extra)

    @Test fun everyPairingActionKindDecodesWithItsFields() {
        assertEquals(LockdownPairAction.Check, LockdownJniEffects.pairAction(raw(1)))
        assertEquals(LockdownPairAction.Connect(7_000), LockdownJniEffects.pairAction(raw(2, numbers = longArrayOf(7_000))))
        // Requests carry the native value tree by reference; the array identity is the contract.
        assertEquals(
            LockdownPairAction.Request(request, 5_000, waitForTrust = false),
            LockdownJniEffects.pairAction(raw(3, request, longArrayOf(5_000, 0))),
        )
        assertEquals(
            LockdownPairAction.Request(request, 90_000, waitForTrust = true),
            LockdownJniEffects.pairAction(raw(3, request, longArrayOf(90_000, 1))),
        )
        val generate = LockdownJniEffects.pairAction(
            raw(4, payload = byteArrayOf(1, 2, 3), extra = "00:11:22:33:44:55"),
        ) as LockdownPairAction.Generate
        assertTrue(generate.devicePublicKey.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("00:11:22:33:44:55", generate.wifi)
        assertEquals(LockdownPairAction.LogUntrustedBuidSet, LockdownJniEffects.pairAction(raw(5)))
        assertEquals(LockdownPairAction.LogPairPrepared, LockdownJniEffects.pairAction(raw(6)))
        assertEquals(LockdownPairAction.LogPairAttempt(3), LockdownJniEffects.pairAction(raw(7, numbers = longArrayOf(3))))
        assertEquals(LockdownPairAction.TrustPending, LockdownJniEffects.pairAction(raw(8)))
        assertEquals(LockdownPairAction.Sleep(250), LockdownJniEffects.pairAction(raw(9, numbers = longArrayOf(250))))
        (LockdownJniEffects.pairAction(raw(10)) as LockdownPairAction.Done).let { assertNull(it.escrowBag) }
        (LockdownJniEffects.pairAction(raw(10, payload = byteArrayOf(9))) as LockdownPairAction.Done).let {
            assertTrue(it.escrowBag!!.contentEquals(byteArrayOf(9)))
        }
        for ((code, number) in listOf(
            LockdownPairErrorCode.CANCELLED to 0,
            LockdownPairErrorCode.TIMED_OUT to 1,
            LockdownPairErrorCode.USER_DENIED to 2,
            LockdownPairErrorCode.PASSWORD_PROTECTED to 3,
            LockdownPairErrorCode.REMOTE to 4,
            LockdownPairErrorCode.INVALID_RESPONSE to 5,
        )) {
            assertEquals(
                LockdownPairAction.Error(code, "text-$number"),
                LockdownJniEffects.pairAction(raw(11, "text-$number", longArrayOf(number.toLong()))),
            )
        }
    }

    @Test fun everyServiceActionKindDecodesWithItsFields() {
        assertEquals(
            LockdownServiceAction.ConnectLockdown(62078, 5_000),
            LockdownJniEffects.serviceAction(raw(2, numbers = longArrayOf(62078, 5_000))),
        )
        assertEquals(
            LockdownServiceAction.Request(request, 5_000),
            LockdownJniEffects.serviceAction(raw(3, request, longArrayOf(5_000, 0))),
        )
        assertEquals(
            LockdownServiceAction.SwitchToTls(5_000),
            LockdownJniEffects.serviceAction(raw(4, numbers = longArrayOf(5_000))),
        )
        assertEquals(
            LockdownServiceAction.ConnectService(1234, 5_000, tls = true),
            LockdownJniEffects.serviceAction(raw(5, numbers = longArrayOf(1234, 5_000, 1))),
        )
        assertEquals(
            LockdownServiceAction.ConnectService(1234, 5_000, tls = false),
            LockdownJniEffects.serviceAction(raw(5, numbers = longArrayOf(1234, 5_000, 0))),
        )
        assertEquals(LockdownServiceAction.Done, LockdownJniEffects.serviceAction(raw(10)))
        assertEquals(
            LockdownServiceAction.Error(reason = 1, text = "Lockdown StartSession failed: InvalidPairRecord"),
            LockdownJniEffects.serviceAction(
                raw(11, "Lockdown StartSession failed: InvalidPairRecord", longArrayOf(1)),
            ),
        )
    }

    @Test fun unknownOrMalformedActionsFailAsRecognizableContractErrorsWithoutIo() {
        for (kind in listOf(0, 12, 99)) {
            val error = assertThrows(LockdownContractException::class.java) { LockdownJniEffects.pairAction(raw(kind)) }
            assertTrue(error.message!!.contains("$kind"))
        }
        for (kind in listOf(0, 1, 6, 99)) {
            val error = assertThrows(LockdownContractException::class.java) { LockdownJniEffects.serviceAction(raw(kind)) }
            assertTrue(error.message!!.contains("$kind"))
        }
        val unknownCode = assertThrows(LockdownContractException::class.java) {
            LockdownJniEffects.pairAction(raw(11, "unmapped", longArrayOf(9)))
        }
        assertTrue(unknownCode.message!!.contains("pairing error code 9"))
        // Malformed layouts are contract errors, not raw casts or silent defaults.
        assertThrows(LockdownContractException::class.java) {
            LockdownJniEffects.pairAction(arrayOf(null, null, longArrayOf()))
        }
        assertThrows(LockdownContractException::class.java) {
            LockdownJniEffects.pairAction(arrayOf(intArrayOf(2), null, null))
        }
        assertThrows(LockdownContractException::class.java) {
            LockdownJniEffects.serviceAction(arrayOf(intArrayOf(2), null, longArrayOf(62078)))
        }
        assertThrows(LockdownContractException::class.java) {
            LockdownJniEffects.serviceAction(raw(3, "not-an-array", longArrayOf(5_000)))
        }
    }

    @Test fun missingPayloadsStayNullAndAreNeverReplacedByDefaults() {
        assertNull((LockdownJniEffects.pairAction(raw(10)) as LockdownPairAction.Done).escrowBag)
        assertEquals(
            LockdownPairAction.Error(LockdownPairErrorCode.CANCELLED, ""),
            LockdownJniEffects.pairAction(raw(11, "", longArrayOf(0))),
        )
    }
}
