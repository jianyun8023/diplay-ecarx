package com.shilapi.xcertplay.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateCatalogTest {
    private fun manifest(tag: String = "v137", apk: String = "DiPlay-release.apk", notes: String = "- Fix reconnect") =
        JSONObject().put("tag", tag).put("apk", apk).put("notes", notes).toString()

    @Test
    fun buildsGithubUrlsFromTheTag() {
        val release = UpdateCatalog.parse(manifest())!!
        assertEquals("v137", release.tagName)
        assertEquals("DiPlay-release.apk", release.apkName)
        assertEquals("https://github.com/jianyun8023/diplay-ecarx/releases/download/v137/DiPlay-release.apk", release.apkUrl)
        assertEquals("https://github.com/jianyun8023/diplay-ecarx/releases/download/v137/SHA256SUMS.txt", release.checksumsUrl)
        assertEquals("- Fix reconnect", release.notes)
    }

    @Test
    fun manifestIsReadFromTheLatestReleaseOnGithub() {
        assertEquals("https://github.com/jianyun8023/diplay-ecarx/releases/latest/download/update.json", UpdateCatalog.MANIFEST_URL)
    }

    @Test
    fun missingNotesAreEmptyAndLongNotesAreCut() {
        assertEquals("", UpdateCatalog.parse(JSONObject().put("tag", "v137").put("apk", "a.apk").toString())!!.notes)
        val long = UpdateCatalog.parse(manifest(notes = "x".repeat(UpdateCatalog.MAXIMUM_NOTES_CHARS + 50)))!!.notes
        assertEquals(UpdateCatalog.MAXIMUM_NOTES_CHARS + 1, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun refusesTagsThatAreNotABuildNumber() {
        for (tag in listOf("", "137", "v0.2.22", "v137/../../x", "v137?x", "latest")) {
            assertNull(tag, UpdateCatalog.parse(manifest(tag = tag)))
        }
    }

    @Test
    fun refusesAnApkNameThatLeavesTheDownloadFolder() {
        for (name in listOf("", "../../evil.apk", "..\\evil.apk", "/data/evil.apk", "sub/evil.apk", ".apk", "evil.apk\u0000.txt.apk")) {
            assertNull(name, UpdateCatalog.parse(manifest(apk = name)))
        }
    }

    @Test
    fun refusesSomethingThatIsNotAManifest() {
        assertNull(UpdateCatalog.parse("[]"))
        assertNull(UpdateCatalog.parse("<html>rate limited</html>"))
    }
}
