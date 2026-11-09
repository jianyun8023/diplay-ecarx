package com.shilapi.xcertplay.update

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class UpdateMirrorTest {
    private val github = "https://github.com/jianyun8023/diplay-ecarx/releases/latest/download/update.json"

    @Test
    fun mirrorPrefixesTheFullGithubUrl() {
        assertEquals(github, UpdateMirror.url(UpdateMirror.DIRECT, github))
        assertEquals("https://ghfast.top/$github", UpdateMirror.url("https://ghfast.top/", github))
    }

    @Test
    fun autoTriesTheLastWorkingRouteThenDirectThenMirrors() {
        val custom = "https://mirror.example/"
        assertEquals(listOf(UpdateMirror.DIRECT) + UpdateMirror.BUILT_IN + custom,
            UpdateMirror.routes(UpdateMirror.AUTO, null, custom))
        assertEquals(listOf("https://gh-proxy.com/", UpdateMirror.DIRECT, "https://ghfast.top/"),
            UpdateMirror.routes(UpdateMirror.AUTO, "https://gh-proxy.com/", null))
    }

    @Test
    fun aManualChoiceUsesOnlyThatRoute() {
        assertEquals(listOf(UpdateMirror.DIRECT), UpdateMirror.routes(UpdateMirror.DIRECT, "https://ghfast.top/", null))
        assertEquals(listOf("https://ghfast.top/"), UpdateMirror.routes("https://ghfast.top/", UpdateMirror.DIRECT, null))
    }

    @Test
    fun customPrefixesMustBePlainHttps() {
        assertEquals("https://mirror.example/", UpdateMirror.normalize(" https://mirror.example "))
        assertEquals("https://mirror.example/gh/", UpdateMirror.normalize("https://mirror.example/gh/"))
        for (value in listOf("", "mirror.example", "http://mirror.example/", "https://user@mirror.example/",
            "https://mirror.example/?u=", "https://mirror.example/#x", "https://github.com/", "file:///sdcard/")) {
            assertNull(value, UpdateMirror.normalize(value))
        }
    }

    @Test
    fun firstWorkingFallsThroughAndReportsTheRouteThatAnswered() {
        val tried = mutableListOf<String>()
        val (value, route) = UpdateMirror.firstWorking(listOf("a", "b", "c")) {
            tried += it
            if (it != "b") throw IOException("down $it")
            "ok"
        }
        assertEquals("ok", value)
        assertEquals("b", route)
        assertEquals(listOf("a", "b"), tried)
    }

    @Test
    fun firstWorkingThrowsTheLastFailureWhenEveryRouteFails() {
        try {
            UpdateMirror.firstWorking(listOf("a", "b")) { throw IOException("down $it") }
            fail("Every route failed")
        } catch (error: IOException) {
            assertEquals("down b", error.message)
            assertEquals("down a", error.suppressed.single().message)
        }
    }
}
