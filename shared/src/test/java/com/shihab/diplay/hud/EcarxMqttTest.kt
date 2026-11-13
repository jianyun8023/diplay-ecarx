package com.shihab.diplay.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class EcarxMqttTest {
    @Test fun jsonEscapeQuotesBackslashAndControlCharacters() {
        assertEquals("a\\\"b\\\\c", EcarxMqtt.jsonEscape("a\"b\\c"))
        assertEquals("a b", EcarxMqtt.jsonEscape("a\nb"))
    }
}
