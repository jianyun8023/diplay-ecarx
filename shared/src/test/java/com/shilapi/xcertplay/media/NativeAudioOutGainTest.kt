package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAudioOutGainTest {
    @Test fun focusGainNeverEntersJniOrTouchesTheNativeHandle() {
        // An invalid handle makes the old direct JNI call fail, even without an Android device.
        val constructor = NativeAudioOut::class.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, kotlin.jvm.functions.Function1::class.java,
        )
        constructor.isAccessible = true
        val out = constructor.newInstance(123L, 4, null)
        val gain = NativeAudioOut::class.java.getDeclaredField("requestedGain").apply { isAccessible = true }
        val handle = NativeAudioOut::class.java.getDeclaredField("handle").apply { isAccessible = true }
        val callback = java.util.concurrent.FutureTask {
            assertTrue(out.setVolume(0.2f))
        }
        Thread(callback, "focus-callback").start()
        callback.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(0.2f, gain.getFloat(out))
        assertEquals(123L, handle.getLong(out))
        handle.setLong(out, 0L)
        assertTrue(out.setVolume(1f))
        assertEquals(1f, gain.getFloat(out))
        assertEquals(0L, handle.getLong(out))
    }
}
