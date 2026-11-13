package com.shilapi.xcertplay.media

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Checks route-provider wiring without loading AAudio. Production resolves the actual AAudio device
 * ID through AudioManager; that SDK/native route still needs Android acceptance.
 */
class NativeAudioOutRouteTest {
    @Test fun routedDeviceIsResolvedOnlyWhileTheHandleIsOpen() {
        var queried = 0
        val provider: (Long) -> AudioDeviceInfo? = { handle ->
            assertEquals(123L, handle)
            queried++
            null
        }

        val constructor = NativeAudioOut::class.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            kotlin.jvm.functions.Function1::class.java,
        )
        constructor.isAccessible = true
        val out = constructor.newInstance(123L, 4, provider)

        assertNull(out.routedDevice)
        assertEquals("open handle should consult the resolver", 1, queried)

        val handle = NativeAudioOut::class.java.getDeclaredField("handle").apply { isAccessible = true }
        handle.setLong(out, 0L)
        assertNull(out.routedDevice)
        assertEquals("closed handle must not consult the resolver", 1, queried)
    }
}
