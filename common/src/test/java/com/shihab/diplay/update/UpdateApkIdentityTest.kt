package com.shihab.diplay.update

import android.content.pm.PackageInfo
import android.content.pm.Signature
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateApkIdentityTest {
    @Suppress("DEPRECATION")
    private fun info(packageName: String = "com.shihab.diplay", versionCode: Int = 137, signature: String = "0a0b") =
        PackageInfo().apply {
            this.packageName = packageName
            this.versionCode = versionCode
            signatures = arrayOf(Signature(signature))
        }

    private fun refused(archive: PackageInfo): String = try {
        UpdateApkIdentity.check(archive, info(versionCode = 136), 136)
        fail("The update must be refused")
        ""
    } catch (error: IOException) {
        error.message.orEmpty()
    }

    @Test fun acceptsANewerBuildOfThisAppWithTheSameSignature() {
        UpdateApkIdentity.check(info(), info(versionCode = 136), 136)
    }

    @Test fun refusesAnotherSignature() {
        assertEquals("Downloaded update is not signed like this app", refused(info(signature = "0c0d")))
    }

    @Test fun refusesAnotherApp() {
        assertEquals("Downloaded update is for another app", refused(info(packageName = "com.example.other")))
    }

    @Test fun refusesABuildThatIsNotNewer() {
        assertEquals("Downloaded update is not newer", refused(info(versionCode = 136)))
    }
}
