package com.shilapi.xcertplay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Source scan for the "Settings layout" rules in AGENTS.md. */
class SettingsLayoutSourceRulesTest {
    private val dir = File("src/main/java/com/shilapi/xcertplay")
    private val sources = dir.listFiles { f -> f.extension == "kt" }!!
    // The Settings screen is split across these files; CarPlay projection screens are out of scope.
    private val settingsSources = listOf("DiPlayActivity.kt", "DiPlayControls.kt", "DiPlayUpdates.kt")
        .map { File(dir, it) }

    @Test fun blockGapsUseTheNamedConstant() {
        val literal = Regex("""(bottom|top)Margin = dp\((16|18|20)\)""")
        assertEquals(emptyList<String>(), settingsSources.flatMap { hits(it, literal) })
    }

    @Test fun sizesAreNotPixelConstants() {
        val pixelConstant = Regex("""\b[A-Z][A-Z0-9_]*_PX\b""")
        assertEquals(emptyList<String>(), sources.flatMap { hits(it, pixelConstant) })
    }

    private fun hits(file: File, pattern: Regex) = file.readLines().withIndex()
        .filter { pattern.containsMatchIn(it.value) }
        .map { "${file.name}:${it.index + 1}: ${it.value.trim()}" }
}
