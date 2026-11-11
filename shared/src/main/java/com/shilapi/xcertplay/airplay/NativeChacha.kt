package com.shilapi.xcertplay.airplay

import android.util.Log

/**
 * ChaCha20-Poly1305 open in `libdiplay_chacha` (Rust, ring's BoringSSL assembly; source in
 * `shared/src/main/rust/diplay-chacha`). The Java Cipher costs about 0.3 ms per call on its own when frames
 * arrive 16 ms apart, which is most of the media streams' decrypt time; this call skips that path.
 */
internal object NativeChacha {
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("diplay_chacha") }
            .onFailure { runCatching { Log.w("xcertplay-usb", "native ChaCha20-Poly1305 unavailable: $it") } }
            .isSuccess
    }

    /** Plaintext length written at [out] + [outOffset]; -1 when the tag does not verify. */
    @JvmStatic external fun open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        aadOffset: Int,
        aadLength: Int,
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        out: ByteArray,
        outOffset: Int,
    ): Int
}
