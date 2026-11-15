package com.shihab.diplay.media
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DownlinkPcmTest {
    @Test fun ownedBuffer() = DownlinkPcmContract.lpcmBufferAndSynchronousBorrow()
    @Test fun codecs() = DownlinkPcmContract.codecPlansAndOpusPreSkip()
}
