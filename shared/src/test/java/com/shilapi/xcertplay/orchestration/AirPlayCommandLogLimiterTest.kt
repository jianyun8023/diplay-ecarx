package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test

class AirPlayCommandLogLimiterTest {
    @Test fun repeatedModePayloadsAreSuppressedButTransitionsAndNewSessionsAreVisible() {
        val limiter = AirPlayCommandLogLimiter()
        val session1 = Any()
        val session2 = Any()
        val idle = mapOf<String, Any?>("appStates" to listOf(mapOf("appStateID" to 3, "entity" to 0)),
            "resources" to listOf(mapOf("resourceID" to 1, "entity" to 1)))
        val active = mapOf<String, Any?>("appStates" to listOf(mapOf("appStateID" to 3, "entity" to 1)),
            "resources" to listOf(mapOf("resourceID" to 1, "entity" to 1)))

        assertTrue(limiter.shouldLog(session1, "modesChanged", idle))
        repeat(100) { assertFalse(limiter.shouldLog(session1, "modesChanged", idle)) }
        assertTrue(limiter.shouldLog(session1, "modesChanged", active))
        assertFalse(limiter.shouldLog(session1, "modesChanged", active))
        assertTrue(limiter.shouldLog(session2, "modesChanged", active))
    }

    @Test fun periodicAudioCommandsStayOffInfoButImportantCommandsStayVisible() {
        val limiter = AirPlayCommandLogLimiter()
        val session = Any()
        repeat(100) {
            assertFalse(limiter.shouldLog(session, "duckAudio", emptyMap()))
            assertFalse(limiter.shouldLog(session, "unduckAudio", emptyMap()))
            assertFalse(limiter.shouldLog(session, "suggestUI", emptyMap()))
        }
        assertTrue(limiter.shouldLog(session, "disableBluetooth", emptyMap()))
        assertTrue(limiter.shouldLog(session, "requestUI", mapOf("url" to "carplay")))
    }
}
