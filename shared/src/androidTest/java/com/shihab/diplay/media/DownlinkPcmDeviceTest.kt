package com.shihab.diplay.media
import org.junit.Test
class DownlinkPcmDeviceTest {
    @Test fun ownedBuffer() = DownlinkPcmContract.lpcmBufferAndSynchronousBorrow()
    @Test fun codecs() = DownlinkPcmContract.codecPlansAndOpusPreSkip()
}
