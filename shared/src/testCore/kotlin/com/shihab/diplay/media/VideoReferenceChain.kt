@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.shihab.diplay.media

import com.shihab.diplay.airplay.MediaPacket
import com.shihab.diplay.airplay.VideoCodec

/** The reference-chain state is one Rust value; packet classification uses the shared wire codec. */
internal class VideoReferenceChain {
    private var owner = NativeVideoFlow.reference(0, 0)
    val needsKeyFrame: Boolean get() = NativeVideoFlow.referenceNeeds(owner)
    fun reset() { owner = NativeVideoFlow.reference(owner, 1) }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        NativeVideoFlow.referenceAccepts(owner, needsKeyFrame && MediaCodecSupport.isRandomAccess(bytes, codec))
    fun accepts(annexB: MediaPacket, codec: VideoCodec): Boolean =
        NativeVideoFlow.referenceAccepts(owner, needsKeyFrame && annexB.isRandomAccess(codec))
    fun onQueued() { owner = NativeVideoFlow.reference(owner, 2) }
}
