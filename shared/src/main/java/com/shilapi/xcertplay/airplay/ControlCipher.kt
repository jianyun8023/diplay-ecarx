package com.shilapi.xcertplay.airplay

/**
 * ChaCha20-Poly1305 framing for the CarPlay control channel.
 *
 * Each frame is [2-byte little-endian ciphertext length][ciphertext][16-byte tag]; the length
 * header is the AEAD associated data and each direction uses an 8-byte little-endian nonce
 * counter plus its own key.
 */
class ControlCipher(readKey: ByteArray, writeKey: ByteArray) : java.io.Closeable {
    data class Decrypted(val data: ByteArray, val rest: ByteArray)

    init { NativeMedia.requireAvailable() }

    private var handle = NativeControlCipher.create(readKey, writeKey).also { check(it != 0L) }
    private var closed = false

    @Synchronized fun decrypt(buffer: ByteArray): Decrypted {
        check(!closed) { "ControlCipher is closed" }
        return NativeControlCipher.decrypt(handle, buffer)
    }

    @Synchronized fun encrypt(plaintext: ByteArray): ByteArray {
        check(!closed) { "ControlCipher is closed" }
        return NativeControlCipher.encrypt(handle, plaintext)
    }

    @Synchronized override fun close() {
        if (closed) return
        NativeControlCipher.destroy(handle)
        handle = 0L
        closed = true
    }
}
