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

    private val fallback = if (NativeMedia.available) null else KotlinControlCipher(readKey, writeKey)
    private var handle = if (fallback == null) NativeControlCipher.create(readKey, writeKey).also { check(it != 0L) } else 0L
    private var closed = false

    @Synchronized fun decrypt(buffer: ByteArray): Decrypted {
        check(!closed) { "ControlCipher is closed" }
        return fallback?.decrypt(buffer) ?: NativeControlCipher.decrypt(handle, buffer)
    }

    @Synchronized fun encrypt(plaintext: ByteArray): ByteArray {
        check(!closed) { "ControlCipher is closed" }
        return fallback?.encrypt(plaintext) ?: NativeControlCipher.encrypt(handle, plaintext)
    }

    @Synchronized override fun close() {
        if (closed) return
        if (fallback == null) NativeControlCipher.destroy(handle)
        handle = 0L
        closed = true
    }
}

internal class KotlinControlCipher(private val readKey: ByteArray, private val writeKey: ByteArray) {
    private var readCounter = 0L
    private var writeCounter = 0L

    fun decrypt(buffer: ByteArray): ControlCipher.Decrypted {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = readU16Le(buffer, offset)
            val frameEnd = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < frameEnd) break
            output.add(AirPlayCrypto.chachaOpen(
                readKey, AirPlayCrypto.nonce64(readCounter), buffer, buffer,
                offset = offset + HEADER_SIZE, length = length + TAG_SIZE,
                aadOffset = offset, aadLength = HEADER_SIZE,
            ))
            readCounter++
            offset = frameEnd
        }
        return ControlCipher.Decrypted(
            if (output.size == 1) output[0] else concatBytes(*output.toTypedArray()),
            if (offset == 0) buffer else buffer.copyOfRange(offset, buffer.size),
        )
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ArrayList<ByteArray>()
        var offset = 0
        do {
            val chunk = plaintext.copyOfRange(offset, minOf(offset + MAX_PAYLOAD, plaintext.size))
            val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
            val ciphertextAndTag = AirPlayCrypto.chachaSeal(writeKey, AirPlayCrypto.nonce64(writeCounter), chunk, header)
            output.add(header)
            output.add(ciphertextAndTag)
            writeCounter++
            offset += MAX_PAYLOAD
        } while (offset < plaintext.size)
        return concatBytes(*output.toTypedArray())
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16
        const val MAX_PAYLOAD = 0x4000
    }
}
