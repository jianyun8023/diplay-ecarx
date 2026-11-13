package com.shihab.diplay.update

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
    private fun manifest(tag: String = "v137", apk: String = "DiPlay-v137-release.apk", notes: String = "- Fix reconnect") =
        JSONObject().put("tag", tag).put("apk", apk).put("notes", notes).toString()

    @Test
    fun buildsGithubUrlsFromTheTag() {
        val release = UpdateCatalog.parse(manifest())!!
        assertEquals("v137", release.tagName)
        assertEquals("DiPlay-v137-release.apk", release.apkName)
        assertEquals("https://github.com/jianyun8023/diplay-ecarx/releases/download/v137/DiPlay-v137-release.apk", release.apkUrl)
        assertEquals("https://github.com/jianyun8023/diplay-ecarx/releases/download/v137/SHA256SUMS.txt", release.checksumsUrl)
        assertEquals("• Fix reconnect", release.notes)
    }

    @Test
    fun manifestIsReadFromTheLatestReleaseOnGithub() {
        assertEquals("https://github.com/jianyun8023/diplay-ecarx/releases/latest/download/update.json", UpdateCatalog.MANIFEST_URL)
        assertEquals("https://github.com/jianyun8023/diplay-ecarx/releases/download/v143/update.json", UpdateCatalog.manifestUrl("v143"))
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

    private fun perAbiManifest() = JSONObject()
        .put("tag", "v149")
        .put("apk", "DiPlay-v149-arm64-v8a-release.apk")
        .put("apks", JSONObject()
            .put("arm64-v8a", "DiPlay-v149-arm64-v8a-release.apk")
            .put("armeabi-v7a", "DiPlay-v149-armeabi-v7a-release.apk")
            .put("x86_64", "DiPlay-v149-x86_64-release.apk"))
        .put("notes", "")
        .toString()

    @Test
    fun eachAbiDownloadsItsOwnPackage() {
        val arm64 = UpdateCatalog.parse(perAbiManifest(), "arm64-v8a")!!
        val armv7 = UpdateCatalog.parse(perAbiManifest(), "armeabi-v7a")!!
        val x86 = UpdateCatalog.parse(perAbiManifest(), "x86_64")!!
        assertEquals("DiPlay-v149-arm64-v8a-release.apk", arm64.apkName)
        assertEquals("DiPlay-v149-armeabi-v7a-release.apk", armv7.apkName)
        assertEquals("DiPlay-v149-x86_64-release.apk", x86.apkName)
        assertEquals(
            "https://github.com/jianyun8023/diplay-ecarx/releases/download/v149/DiPlay-v149-x86_64-release.apk",
            x86.apkUrl,
        )
    }

    @Test
    fun fallsBackToTheTopLevelApkWhenTheAbiEntryIsMissing() {
        // A manifest that only names a universal/default APK still upgrades every device.
        val release = UpdateCatalog.parse(manifest(apk = "DiPlay-v137-release.apk"), "x86_64")!!
        assertEquals("DiPlay-v137-release.apk", release.apkName)
    }

    @Test
    fun refusesAnUnsafePerAbiNameEvenIfTheTopLevelApkIsSafe() {
        val json = JSONObject()
            .put("tag", "v149")
            .put("apk", "DiPlay-v149-arm64-v8a-release.apk")
            .put("apks", JSONObject().put("x86_64", "../../evil.apk"))
            .toString()
        assertNull(UpdateCatalog.parse(json, "x86_64"))
    }

    @Test
    fun platformAbiPicksTheFirstShippedAbiInDeviceOrder() {
        assertEquals("arm64-v8a", UpdateCatalog.platformAbi(listOf("arm64-v8a", "armeabi-v7a")))
        // An x86_64 emulator reports only x86_64.
        assertEquals("x86_64", UpdateCatalog.platformAbi(listOf("x86_64", "x86")))
        // ARMv7-only hardware.
        assertEquals("armeabi-v7a", UpdateCatalog.platformAbi(listOf("armeabi-v7a", "armeabi")))
        // An unsupported device falls back to the ARM64 default rather than an unrunnable ABI.
        assertEquals("arm64-v8a", UpdateCatalog.platformAbi(listOf("riscv64")))
        assertEquals("arm64-v8a", UpdateCatalog.platformAbi(emptyList()))
    }
}
