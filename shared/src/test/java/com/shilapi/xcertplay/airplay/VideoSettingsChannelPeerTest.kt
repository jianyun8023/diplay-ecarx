package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSettingsChannelPeerTest {
    private val readKey = ByteArray(32) { 1 }
    private val writeKey = ByteArray(32) { 2 }
    private val logs = CopyOnWriteArrayList<String>()

    @Test
    fun aStrangersBadFrameDoesNotEndTheChannel() {
        val channel = VideoSettingsChannel(readKey, writeKey, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))) { logs += it }
        try {
            val port = channel.listen(InetAddress.getLoopbackAddress())
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().write(byteArrayOf(32, 0) + ByteArray(32 + 16) { 9 })
                socket.getOutputStream().flush()
                Thread.sleep(700)
            }
            assertTrue("stranger ended the channel: $logs", logs.none { it.contains("ended") })
        } finally { channel.close() }
    }

    @Test
    fun theControlPeersSyncIsAnswered() {
        val channel = VideoSettingsChannel(readKey, writeKey, InetAddress.getLoopbackAddress()) { logs += it }
        try {
            val port = channel.listen(InetAddress.getLoopbackAddress())
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = 3000
                val sync = ByteArray(32)
                sync[3] = 32
                "sync".toByteArray(Charsets.US_ASCII).copyInto(sync, 4)
                val aad = byteArrayOf(32, 0)
                socket.getOutputStream().write(
                    aad + AirPlayCrypto.chachaSeal(readKey, AirPlayCrypto.nonce64(0), sync, aad),
                )
                socket.getOutputStream().flush()
                val reply = ByteArray(2 + 32 + 16)
                var read = 0
                while (read < reply.size) {
                    val n = socket.getInputStream().read(reply, read, reply.size - read)
                    assertTrue("channel closed before replying", n >= 0)
                    read += n
                }
                val plain = AirPlayCrypto.chachaOpen(
                    writeKey, AirPlayCrypto.nonce64(0), reply.copyOfRange(2, reply.size), reply.copyOfRange(0, 2),
                )
                assertEquals("rply", String(plain, 4, 4, Charsets.US_ASCII))
            }
        } finally { channel.close() }
    }
    @Test
    fun commandsAcknowledgeSyncBeforeSendingFragmentedEncryptedResponse() {
        val response = ByteArray(2500) { (it % 251).toByte() }
        VideoSettingsChannel(readKey, writeKey, InetAddress.getLoopbackAddress()) { logs += it }.use { channel ->
            val pending = java.util.concurrent.atomic.AtomicBoolean(false)
            channel.onPoll = { if (pending.getAndSet(false)) listOf(byteArrayOf(42)) else emptyList() }
            channel.onCommand = { body ->
                org.junit.Assert.assertArrayEquals(byteArrayOf(9), body)
                listOf(response)
            }
            val port = channel.listen(InetAddress.getLoopbackAddress())
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = 3000
                val packet = java.nio.ByteBuffer.allocate(33).putInt(33).array()
                "sync".toByteArray().copyInto(packet, 4)
                packet[27] = 7
                packet[32] = 9
                val aad = byteArrayOf(33, 0)
                val wire = aad + AirPlayCrypto.chachaSeal(readKey, AirPlayCrypto.nonce64(0), packet, aad)
                wire.asList().chunked(7).forEach { socket.getOutputStream().write(it.toByteArray()) }
                val input = java.io.DataInputStream(socket.getInputStream())
                fun readFrame(counter: Long): ByteArray {
                    val prefix = ByteArray(2).also(input::readFully)
                    val length = (prefix[0].toInt() and 255) or ((prefix[1].toInt() and 255) shl 8)
                    val sealed = ByteArray(length + 16).also(input::readFully)
                    return AirPlayCrypto.chachaOpen(writeKey, AirPlayCrypto.nonce64(counter), sealed, prefix)
                }
                val ack = readFrame(0)
                assertEquals("rply", String(ack, 4, 4))
                assertEquals(7, ack[27].toInt())
                val answer = readFrame(1) + readFrame(2) + readFrame(3)
                assertEquals("asyn", String(answer, 4, 4))
                assertEquals("cmnd", String(answer, 16, 4))
                org.junit.Assert.assertArrayEquals(response, answer.copyOfRange(32, answer.size))
                pending.set(true)
                val update = readFrame(4)
                assertEquals("asyn", String(update, 4, 4))
                org.junit.Assert.assertArrayEquals(byteArrayOf(42), update.copyOfRange(32, update.size))
            }
        }
    }

}
