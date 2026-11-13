package com.shihab.diplay.airplay

/** Stateless cryptography; identity persistence, Unicode and hardware signing remain on Android. */
internal object NativeCrypto {
    init { check(NativeMedia.available) { "Native protocol core is unavailable" } }
    @JvmStatic external fun x25519Generate(): Array<ByteArray>
    @JvmStatic external fun x25519Shared(privateKey: ByteArray, peer: ByteArray): ByteArray
    @JvmStatic external fun ed25519Generate(): Array<ByteArray>
    @JvmStatic external fun ed25519Sign(seed: ByteArray, data: ByteArray): ByteArray
    @JvmStatic external fun ed25519Verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
    @JvmStatic external fun hkdfSha512(input: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray
    @JvmStatic external fun sha512(parts: Array<out ByteArray>): ByteArray
    @JvmStatic external fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
    @JvmStatic external fun ecdsaDerToRaw(der: ByteArray): ByteArray
    @JvmStatic external fun ecdsaRawToDer(raw: ByteArray): ByteArray
}
