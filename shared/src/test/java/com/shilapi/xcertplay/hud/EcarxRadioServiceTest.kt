package com.shilapi.xcertplay.hud

import android.os.Binder
import android.os.Looper
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxRadioServiceTest {
    private class Radio : Binder() {
        var band = 1
        var frequency = 90500
        var status = 0x1001
        var accepted = true
        val commands = mutableListOf<Pair<Int, Int?>>()
        init { attachInterface(null, EcarxRadioService.DESCRIPTOR) }
        override fun onTransact(code: Int, request: Parcel, reply: Parcel?, flags: Int): Boolean {
            request.enforceInterface(EcarxRadioService.DESCRIPTOR)
            val value = when (code) {
                13 -> band
                14 -> frequency
                22 -> status
                2 -> { assertNull(request.readStrongBinder()); commands += code to null; if (accepted) 1 else 0 }
                4, 5, 6 -> { commands += code to request.readInt(); if (accepted) 1 else 0 }
                19, 20 -> { commands += code to null; if (accepted) 1 else 0 }
                else -> return false
            }
            reply!!.writeNoException()
            reply.writeInt(value)
            return true
        }
    }

    private fun service(radio: Binder): EcarxRadioService {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).setComponentNameAndServiceForBindService(EcarxRadioService.COMPONENT, radio)
        return EcarxRadioService(app) {}.also { it.start(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun bindingReadingAndClosingNeverStartOrStopTheTuner() {
        val radio = Radio()
        val source = service(radio)
        assertEquals(EcarxRadioService.State("FM", 90500, true), source.read())
        radio.status = 0x1000
        assertEquals(false, source.read()!!.playing)
        radio.status = 0x2000
        radio.frequency = -1
        assertEquals(EcarxRadioService.State("FM", null, null), source.read())
        source.close()
        source.close()
        assertTrue(radio.commands.isEmpty())
        assertNull(source.read())
        assertFalse(source.control(4360, emptyMap<String, Any>()))
    }

    @Test fun controlsUseTheDumpedTransactionIdsAndDirections() {
        val radio = Radio()
        service(radio).use { source ->
            assertTrue(source.control(4360, emptyMap<String, Any>()))
            assertEquals(listOf(2 to null, 19 to null), radio.commands)
            assertTrue(source.control(4361, emptyMap<String, Any>()))
            assertEquals(20 to null, radio.commands.last())
            assertTrue(source.control(4362, emptyMap<String, Any>()))
            assertEquals(6 to 0, radio.commands.last())
            assertTrue(source.control(4363, emptyMap<String, Any>()))
            assertEquals(6 to 1, radio.commands.last())
            assertTrue(source.control(4364, mapOf("identifier" to "101700", "sourceIdentifier" to "FM")))
            assertEquals(5 to 101700, radio.commands.last())
            assertEquals(90500, source.read()!!.frequency)
            assertTrue(source.control(4366, mapOf("identifier" to "AM")))
            assertEquals(4 to 0, radio.commands.last())
            radio.band = 0; radio.frequency = 531
            assertTrue(source.control(4365, mapOf("frequency" to 999L, "sourceIdentifier" to "AM")))
            assertEquals(5 to 999, radio.commands.last())
            radio.accepted = false
            assertFalse(source.control(4360, emptyMap<String, Any>()))
        }
        assertFalse(radio.commands.any { it.first == 3 })
    }

    @Test fun invalidOrCrossBandRequestsCauseNoTunerCommands() {
        val radio = Radio()
        service(radio).use { source ->
            for (params in listOf(mapOf("frequency" to 90550), mapOf("frequency" to Double.NaN),
                mapOf("frequency" to 108100), mapOf("frequency" to 90500.5),
                mapOf("frequency" to "90500"), mapOf("frequency" to 999L, "sourceIdentifier" to "AM"),
                mapOf("frequency" to 90500L, "sourceIdentifier" to 1))) assertFalse(source.control(4365, params))
            assertFalse(source.control(4366, mapOf("identifier" to "DAB")))
            assertFalse(source.control(4364, mapOf("identifier" to "90550")))
            assertFalse(source.control(9999, emptyMap<String, Any>()))
            assertTrue(radio.commands.isEmpty())
        }
    }

    @Test fun unexpectedServiceAbiIsUnavailable() {
        service(Binder().apply { attachInterface(null, "other.service") }).use { source ->
            assertNull(source.read())
            assertFalse(source.control(4360, emptyMap<String, Any>()))
        }
    }
}
