package com.shihab.diplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mandatory-core load failures keep the original exception object end to end: the unified entry
 * throws the loader's own failure and a converted production caller surfaces that same object,
 * never a fresh context-free assertion message (2026-11-19 boundary review item 7).
 */
class NativeMediaLoadFailureTest {
    @Test fun theOriginalLoadFailureReachesTheEntryAndProductionCallersUnchanged() {
        // A successful load lets the production caller continue into the native implementation.
        NativeMedia.requireAvailable()
        assertTrue(NativeMedia.available)
        assertEquals(64, AirPlayCrypto.sha512(byteArrayOf(1)).size)

        // Make the loader hold a known failure through the same Result channel, then verify the
        // unified entry and one of the converted production callers propagate that same object.
        // The delegate is static final, so the swap targets its already-evaluated value slot.
        val delegate = NativeMedia.javaClass.getDeclaredField("load\$delegate")
            .apply { isAccessible = true }.get(NativeMedia)
        val valueField = delegate.javaClass.declaredFields
            .first { it.name == "_value" || it.name == "value" }
            .apply { isAccessible = true }
        val original = valueField.get(delegate)
        val known = UnsatisfiedLinkError("known native load failure for tests")
        val identity = AirPlayIdentity.generate()
        try {
            valueField.set(delegate, Result.failure<Unit>(known))
            assertFalse(NativeMedia.available)
            assertSame(known, NativeMedia.loadError)
            assertSame(known, runCatching { NativeMedia.requireAvailable() }.exceptionOrNull())
            assertSame(known, runCatching { AirPlayCrypto.prepare() }.exceptionOrNull())
            // One of the 16 former check(NativeMedia.available) sites, at its production call path.
            assertSame(known, runCatching { PairSetup(identity, PairingStore()).handle(ByteArray(0)) }.exceptionOrNull())
        } finally {
            @Suppress("UNCHECKED_CAST")
            valueField.set(delegate, original)
        }
        // The restored loader lets callers continue into the native implementation again.
        assertTrue(NativeMedia.available)
        assertEquals(64, AirPlayCrypto.sha512(byteArrayOf(2)).size)
    }
}
