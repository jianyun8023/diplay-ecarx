package com.shilapi.xcertplay.airplay

import javax.crypto.AEADBadTagException

/** Native cryptographic primitives; Android retains byte encoding and identity/storage adapters. */
object AirPlayCrypto {
    val chachaImplementation: String get() = "ring"
    data class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)
    data class Ed25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun x25519Generate(): X25519KeyPair = NativeCrypto.x25519Generate().let { X25519KeyPair(it[0], it[1]) }
    fun x25519Shared(privateKeyRaw: ByteArray, peerPublicKeyRaw: ByteArray): ByteArray =
        NativeCrypto.x25519Shared(privateKeyRaw, peerPublicKeyRaw)
    fun ed25519Generate(): Ed25519KeyPair = NativeCrypto.ed25519Generate().let { Ed25519KeyPair(it[0], it[1]) }
    fun ed25519Sign(privateKeyRaw: ByteArray, data: ByteArray): ByteArray = NativeCrypto.ed25519Sign(privateKeyRaw, data)
    fun ed25519Verify(publicKeyRaw: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        NativeCrypto.ed25519Verify(publicKeyRaw, data, signature)
    fun hkdfSha512(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray =
        NativeCrypto.hkdfSha512(inputKeyMaterial, salt, info, length)
    fun sha512(vararg parts: ByteArray): ByteArray = NativeCrypto.sha512(parts)
    fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray =
        NativeCrypto.chachaSeal(key, nonce, plaintext, aad)

    fun chachaOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0), offset: Int = 0, length: Int = ciphertextAndTag.size - offset,
        aadOffset: Int = 0, aadLength: Int = aad.size - aadOffset): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= ciphertextAndTag.size - length)
        require(aadOffset >= 0 && aadLength >= 0 && aadOffset <= aad.size - aadLength)
        require(key.size == 32) { "ChaCha20 requires a 256 bit key" }
        require(nonce.size == 12) { "ChaCha20Poly1305 nonce must be 96 bits" }
        check(NativeMedia.available) { "Native protocol core is unavailable" }
        if (length < 16) throw AEADBadTagException("ChaCha20-Poly1305 tag mismatch")
        val output = ByteArray(length - 16)
        if (NativeMedia.chachaOpen(key, nonce, aad, aadOffset, aadLength,
                ciphertextAndTag, offset, length, output, 0) < 0) {
            throw AEADBadTagException("ChaCha20-Poly1305 tag mismatch")
        }
        return output
    }

    /** Four zero bytes followed by an eight-byte little-endian counter. */
    fun nonce64(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        var value = counter
        for (index in 4 until 12) { nonce[index] = value.toByte(); value = value ushr 8 }
        return nonce
    }

    /** Android's ASCII replacement semantics, then at most eight label bytes. */
    fun nonceLabel(label: String): ByteArray = ByteArray(12).also {
        val ascii = label.asciiBytes()
        ascii.copyInto(it, 4, 0, minOf(ascii.size, 8))
    }
}
