@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.shilapi.xcertplay.airplay

// Original Kotlin state owner from6cd98d70, retained only as an independent test reference.
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
