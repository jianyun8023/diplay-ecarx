package com.shihab.diplay.airplay

import com.shihab.diplay.mfi.MfiAuthenticator
import com.shihab.diplay.mfi.MfiCertificateType

/** The ephemeral MFiSAP exchange is owned by Rust; hardware actions remain on the platform. */
object MfiSapAuthSetup {
    fun handle(body: ByteArray, mfi: MfiAuthenticator): ByteArray? =
        NativeMfiSap.handle(body, MfiSapPlatform(mfi))
}

internal object NativeMfiSap {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun handle(body: ByteArray, sdk: Any): ByteArray?
}

/** Only SDK effects and typed certificate metadata; no transcript, hash or packet policy. */
internal class MfiSapPlatform(private val mfi: MfiAuthenticator) {
    fun kind(): Int = when (mfi.certificateType) { MfiCertificateType.MFI -> 0; MfiCertificateType.BAA -> 1 }
    fun certificate(): ByteArray = mfi.readCertificate()
    fun major(): Int = mfi.protocolMajor()
    fun sign(challenge: ByteArray): ByteArray = mfi.signChallenge(challenge)
    fun baa(): Array<ByteArray> = mfi.baaCertificates().let { arrayOf(it.leaf, it.intermediate) }
}
