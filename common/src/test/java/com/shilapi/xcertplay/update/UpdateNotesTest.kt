package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotesTest {
    @Test
    fun headingsLoseTheirMarks() {
        assertEquals("主要变化\n安装与使用提醒", UpdateNotes.plain("### 主要变化\n## 安装与使用提醒 ##"))
        // Not a heading without the space; leave it alone.
        assertEquals("#143 fixed", UpdateNotes.plain("#143 fixed"))
    }

    @Test
    fun listItemsBecomeBulletsAndKeepTheirIndent() {
        assertEquals("• one\n  • nested\n• three", UpdateNotes.plain("- one\n  * nested\n+ three"))
    }

    @Test
    fun emphasisCodeAndLinksKeepOnlyTheirText() {
        assertEquals(
            "应用内更新：下载 DiPlay-release.apk，见车主使用说明。",
            UpdateNotes.plain("**应用内更新**：下载 `DiPlay-release.apk`，见[车主使用说明](https://github.com/x/y/blob/v143/docs/USER-GUIDE.zh-CN.md)。"),
        )
        assertEquals("a b c", UpdateNotes.plain("__a__ *b* ![c](https://x/c.png)"))
    }

    @Test
    fun plainArithmeticAndPathsSurvive() {
        assertEquals("2 * 3 = 6, a_b_c, 5*", UpdateNotes.plain("2 * 3 = 6, a_b_c, 5*"))
    }

    @Test
    fun blankRunsCollapseAndWindowsLineEndsAreRead() {
        assertEquals("a\n\nb", UpdateNotes.plain("a\r\n\r\n\r\n\r\nb\r\n"))
    }

    @Test
    fun theV143ReleaseBodyReadsAsPlainText() {
        val body = checkNotNull(javaClass.getResource("/update/release-v143.md")).readText()
        val plain = UpdateNotes.plain(body)
        for (syntax in listOf("###", "**", "`", "](", "\n- ")) {
            assertFalse("$syntax left in notes", plain.contains(syntax))
        }
        assertTrue(plain.contains("主要变化\n\n• 应用内更新："))
        assertTrue(plain.contains("车主使用说明 · 完整更新记录"))
    }

    @Test
    fun matchesTheScriptThatWritesUpdateJson() {
        // release-v143.txt is scripts/release_notes_plain.py run on release-v143.md. Regenerate it when either side changes.
        val body = checkNotNull(javaClass.getResource("/update/release-v143.md")).readText()
        val script = checkNotNull(javaClass.getResource("/update/release-v143.txt")).readText()
        assertEquals(script, UpdateNotes.plain(body))
        assertEquals("Converting plain text again changes nothing", script, UpdateNotes.plain(script))
    }
}
