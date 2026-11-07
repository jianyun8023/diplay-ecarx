package com.shilapi.xcertplay.network

import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class Android9SoftApClientObserverTest {
    private class FakeRegistrar : Android9SoftApClientObserver.Registrar {
        var registrations = 0
        var unregistrations = 0
        var failWith: RuntimeException? = null
        lateinit var clients: (Int) -> Unit
        lateinit var state: (Int) -> Unit

        override fun register(onClients: (Int) -> Unit, onState: (Int) -> Unit): Closeable {
            registrations++
            failWith?.let { throw it }
            clients = onClients
            state = onState
            return Closeable { unregistrations++ }
        }
    }

    @Test fun distinguishesZeroClientsFromAnUnobservedCallbackAndTracksAge() {
        val registrar = FakeRegistrar()
        var now = 1_000_000_000L
        val transitions = mutableListOf<String>()
        val observer = Android9SoftApClientObserver(
            registrar, nowNs = { now }, dispatch = { it() }, log = transitions::add,
        )

        assertTrue(observer.diagnosticSnapshot().contains("softApClients=unknown"))
        observer.start()
        observer.start()
        assertEquals(1, registrar.registrations)
        assertTrue(observer.diagnosticSnapshot().contains("softApObservation=registered"))
        assertTrue(observer.diagnosticSnapshot().contains("softApClients=unknown"))

        registrar.state(13)
        registrar.clients(0)
        now += 8_000_000_000L
        assertTrue(observer.diagnosticSnapshot().contains("softApClients=0"))
        assertTrue(observer.diagnosticSnapshot().contains("softApState=enabled"))
        assertTrue(observer.diagnosticSnapshot().contains("softApCallbackAgeMs=8000"))
        registrar.clients(2)
        registrar.clients(2)
        registrar.clients(-1)
        assertTrue(observer.diagnosticSnapshot().contains("softApClients=2"))
        assertEquals(
            listOf("wireless SoftAP associatedClients=0", "wireless SoftAP associatedClients=2"),
            transitions.filter { it.startsWith("wireless SoftAP associatedClients=") },
        )
        assertTrue(transitions.contains("wireless SoftAP observation=registered"))
        observer.close()
        observer.close()
        assertEquals(1, registrar.unregistrations)
        registrar.clients(3)
        registrar.state(14)
        assertTrue(observer.diagnosticSnapshot().contains("softApClients=2"))
        assertTrue(observer.diagnosticSnapshot().contains("softApObservation=closed"))
        assertTrue(transitions.contains("wireless SoftAP state=enabled previous=unknown"))
        assertEquals(4, transitions.size)
    }

    @Test fun reportsTheAccessPointGoingDownOnceAndOnlyAfterItWasUp() {
        val registrar = FakeRegistrar()
        val transitions = mutableListOf<String>()
        var lost = 0
        val observer = Android9SoftApClientObserver(
            registrar, dispatch = { it() }, log = transitions::add, onApLost = { lost++ },
        )
        observer.start()
        // A hotspot that was never up is no loss: the start path reports that itself.
        registrar.state(11)
        assertEquals(0, lost)
        registrar.state(12)
        registrar.state(13)
        registrar.state(13)
        assertEquals(0, lost)
        // The car's Wi-Fi client coming on stops the AP (car test 2026-11-07).
        registrar.state(10)
        assertEquals(1, lost)
        registrar.state(11)
        registrar.state(13)
        registrar.state(14)
        assertEquals(1, lost)
        assertTrue(transitions.contains("wireless SoftAP state=disabling previous=enabled"))
        assertEquals(1, transitions.count { it == "wireless SoftAP state=enabled previous=enabling" })
        observer.close()
        registrar.state(11)
        assertEquals(1, lost)
    }

    @Test fun vendorSecurityErrorIsObservabilityOnlyAndNeverLeaksMessages() {
        val registrar = FakeRegistrar().apply {
            failWith = SecurityException("private SSID=secret phone=AA:BB")
        }
        val observer = Android9SoftApClientObserver(registrar, dispatch = { it() })
        observer.start()
        val snapshot = observer.diagnosticSnapshot()
        assertTrue(snapshot.contains("softApClients=unavailable"))
        assertTrue(snapshot.contains("softApObservation=unavailable"))
        assertTrue(snapshot.contains("failureClass=SecurityException"))
        assertFalse(snapshot.contains("secret") || snapshot.contains("AA:BB"))
        observer.close()
        assertEquals(0, registrar.unregistrations)
    }

    @Test fun closingBeforeTheRegistrationWorkerRunsNeverRegisters() {
        val registrar = FakeRegistrar()
        val pending = mutableListOf<() -> Unit>()
        val observer = Android9SoftApClientObserver(registrar, dispatch = { pending.add(it) })
        observer.start()
        assertTrue(observer.diagnosticSnapshot().contains("softApObservation=registering"))
        observer.close()
        pending.single().invoke()
        assertEquals(0, registrar.registrations)
        assertEquals(0, registrar.unregistrations)
        assertTrue(observer.diagnosticSnapshot().contains("softApObservation=closed"))
    }

    @Test fun closesARegistrationThatCompletesAfterOwnerClose() {
        var observer: Android9SoftApClientObserver? = null
        var closes = 0
        val registrar = Android9SoftApClientObserver.Registrar { _, _ ->
            observer!!.close()
            Closeable { closes++ }
        }
        observer = Android9SoftApClientObserver(registrar, dispatch = { it() })
        observer.start()
        assertEquals(1, closes)
        assertTrue(observer.diagnosticSnapshot().contains("softApObservation=closed"))
    }

    @Test fun callbackLogFailureNeverBreaksObserving() {
        val registrar = FakeRegistrar()
        val observer = Android9SoftApClientObserver(
            registrar, dispatch = { it() }, log = { throw IllegalStateException("logging failed") },
        )
        observer.start()
        registrar.clients(1)
        assertTrue(observer.diagnosticSnapshot().contains("softApClients=1"))
        observer.close()
        assertEquals(1, registrar.unregistrations)
    }
}
