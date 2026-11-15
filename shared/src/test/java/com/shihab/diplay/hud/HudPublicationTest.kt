package com.shihab.diplay.hud
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HudPublicationTest {
    @Test fun publicationOwner() = HudPublicationContract.nativeSnapshotsRetriesAndCache()
}
