package com.shihab.diplay.airplay

import org.junit.Assert.*

internal object ControlInputContract {
    fun everySplitPreservesCipherAndRtspResidue() {
        val key = ByteArray(32) { it.toByte() }
        val wire = "POST /command RTSP/1.0\r\nCSeq: 7\r\nContent-Length: 4\r\n\r\nbodyOPTIONS * RTSP/1.0\r\n\r\nGET /tail"
            .toByteArray(Charsets.US_ASCII)
        val encrypted = ControlCipher(key, key).use { it.encrypt(wire) }
        for (split in 0..encrypted.size) {
            ControlCipher(key, key).use { cipher -> RtspInput().use { reader ->
                val messages = reader.read(encrypted, split, cipher) +
                    reader.read(encrypted.copyOfRange(split, encrypted.size) + byteArrayOf(99, 98), encrypted.size - split, cipher)
                assertEquals(2, messages.size)
                assertEquals("/command", messages[0].path)
                assertArrayEquals("body".toByteArray(), messages[0].body)
                assertEquals("OPTIONS", messages[1].method)
                assertEquals(0, reader.encryptedBytes)
                // The two directions still use the original cipher's independent counters.
                ControlCipher(key, key).use { peer ->
                    peer.encrypt(wire)
                    val suffix = peer.encrypt(" RTSP/1.0\r\n\r\n".toByteArray())
                    assertEquals("/tail", reader.read(suffix, suffix.size, cipher).single().path)
                    assertArrayEquals(byteArrayOf(8), peer.decrypt(cipher.encrypt(byteArrayOf(8))).data)
                }
            } }
        }
    }

    fun activationLimitsAndCloseKeepTheirExistingBoundary() {
        val key = ByteArray(32) { 7 }
        RtspInput().use { reader -> ControlCipher(key, key).use { cipher -> ControlCipher(key, key).use { peer ->
            assertTrue(reader.read("GET /".toByteArray(), 5, null).isEmpty())
            val rest = peer.encrypt(" RTSP/1.0\r\n\r\n".toByteArray())
            assertEquals("/", reader.read(rest, rest.size, cipher).single().path)
            val next = peer.encrypt("OPTIONS * RTSP/1.0\r\n\r\n".toByteArray())
            assertEquals("OPTIONS", reader.read(next, next.size, cipher).single().method)
            cipher.close()
            assertThrows(IllegalStateException::class.java) { reader.read(next, next.size, cipher) }
        } } }
        RtspInput().use { reader ->
            assertThrows(java.net.ProtocolException::class.java) { reader.read(ByteArray(16 * 1024 + 1) { 65 }, 16 * 1024 + 1, null) }
        }
        val reader = RtspInput()
        reader.close(); reader.close()
        assertThrows(IllegalStateException::class.java) { reader.read(byteArrayOf(), 0, null) }
        ControlCipher(key, key).use { cipher -> RtspInput().use { input ->
            val damaged = ControlCipher(key, key).use { it.encrypt(byteArrayOf(1)) }.apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            assertThrows(javax.crypto.AEADBadTagException::class.java) { input.read(damaged, damaged.size, cipher) }
            assertEquals(damaged.size, input.encryptedBytes)
        } }
    }
}
