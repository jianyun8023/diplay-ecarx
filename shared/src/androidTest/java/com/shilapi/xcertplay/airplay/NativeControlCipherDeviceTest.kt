package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Test

class NativeControlCipherDeviceTest {
    @Test fun nativeFramingMatchesKotlinAcrossEmptyLargeAndFragmentedMessages() {
        assertTrue(NativeMedia.available)
        val key = ByteArray(32) { it.toByte() }
        for (size in listOf(0, 1, 16384, 16385, 32769)) {
            val plain = ByteArray(size) { it.toByte() }
            val wire = ControlCipher(key, key).use { it.encrypt(plain) }
            assertArrayEquals(KotlinControlCipher(key, key).encrypt(plain), wire)
            for (split in listOf(0, 1, 2, 17, wire.size - 1).filter { it < wire.size }) {
                ControlCipher(key, key).use { decoder ->
                    val oracle = KotlinControlCipher(key, key)
                    val fragment = wire.copyOf(split)
                    val first = decoder.decrypt(fragment)
                    val expected = oracle.decrypt(fragment)
                    assertArrayEquals(expected.data, first.data); assertArrayEquals(expected.rest, first.rest)
                    if (expected.rest === fragment) assertSame(fragment, first.rest)
                    val final = decoder.decrypt(first.rest + wire.copyOfRange(split, wire.size))
                    assertArrayEquals(plain, first.data + final.data)
                    assertTrue(final.rest.isEmpty())
                }
            }
        }
    }

    @Test fun authenticationFailureKeepsSuccessfulPrefixProgressAndAcceptsU16Lengths() {
        assertTrue(NativeMedia.available)
        val key = ByteArray(32) { 7 }
        ControlCipher(key, key).use { encoder -> ControlCipher(key, key).use { decoder ->
            val first = encoder.encrypt(byteArrayOf(1, 2))
            val second = encoder.encrypt(byteArrayOf(3, 4))
            val bad = second.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            val error = assertThrows(AEADBadTagException::class.java) { decoder.decrypt(first + bad) }
            assertEquals("ChaCha20-Poly1305 tag mismatch", error.message)
            assertArrayEquals(byteArrayOf(3, 4), decoder.decrypt(second).data)
        } }
        val plain = ByteArray(65535) { it.toByte() }
        val header = byteArrayOf(-1, -1)
        val sealed = AirPlayCrypto.chachaSealBouncyCastle(key, AirPlayCrypto.nonce64(0), plain, header)
        ControlCipher(key, key).use { assertArrayEquals(plain, it.decrypt(header + sealed).data) }
    }

    @Test fun invalidKeyFailuresRemainLazyAndDirectionSpecific() {
        assertTrue(NativeMedia.available)
        val key = ByteArray(32)
        for (length in listOf(0, 1, 31, 33)) {
            ControlCipher(ByteArray(length), key).use { native ->
                val oracle = KotlinControlCipher(ByteArray(length), key)
                val partial = byteArrayOf(0, 0)
                assertSame(partial, native.decrypt(partial).rest)
                val expected = assertThrows(IllegalArgumentException::class.java) { oracle.decrypt(ByteArray(18)) }
                val actual = assertThrows(IllegalArgumentException::class.java) { native.decrypt(ByteArray(18)) }
                assertEquals(expected.javaClass, actual.javaClass)
                assertArrayEquals(oracle.encrypt(byteArrayOf(1)), native.encrypt(byteArrayOf(1)))
            }
            ControlCipher(key, ByteArray(length)).use { native ->
                assertThrows(IllegalArgumentException::class.java) { native.encrypt(ByteArray(0)) }
                val wire = KotlinControlCipher(key, key).encrypt(byteArrayOf(3))
                assertArrayEquals(byteArrayOf(3), native.decrypt(wire).data)
            }
        }
    }

    @Test fun realPairVerifyEnablesEncryptedControlAndEventSocketsAndClosesBothOwners() {
        assertTrue(NativeMedia.available)
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { listener ->
            Wire(Socket(loopback, listener.localPort)).use { control ->
                val controller = AirPlayCrypto.ed25519Generate()
                val pairings = PairingStore().apply { save("cipher-controller", controller.publicKey) }
                val ready = CountDownLatch(1)
                val session = session(listener.accept(), pairings, object : AirPlaySessionListener {
                    override fun onClusterControlReady(session: AirPlaySession) { ready.countDown() }
                })
                try {
                    session.start()
                    val ephemeral = AirPlayCrypto.x25519Generate()
                    val m1 = Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(1)), Tlv8Item(3, ephemeral.publicKey)))
                    control.request("POST", "/pair-verify", m1)
                    val m2 = Tlv8Codec.decode(control.read().body)
                    assertArrayEquals(byteArrayOf(2), m2[6])
                    val accessoryPublic = m2[3]!!
                    val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, accessoryPublic)
                    val encryptKey = AirPlayCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt".toByteArray(), "Pair-Verify-Encrypt-Info".toByteArray())
                    val id = "cipher-controller".toByteArray()
                    val signature = AirPlayCrypto.ed25519Sign(controller.privateKey, ephemeral.publicKey + id + accessoryPublic)
                    val sub = Tlv8Codec.encode(listOf(Tlv8Item(1, id), Tlv8Item(10, signature)))
                    val m3 = Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(3)),
                        Tlv8Item(5, AirPlayCrypto.chachaSeal(encryptKey, AirPlayCrypto.nonceLabel("PV-Msg03"), sub))))
                    control.request("POST", "/pair-verify", m3)
                    assertArrayEquals(byteArrayOf(4), Tlv8Codec.decode(control.read().body)[6])
                    fun key(salt: String, info: String) = AirPlayCrypto.hkdfSha512(shared, salt.toByteArray(), info.toByteArray())
                    control.cipher = ControlCipher(key("Control-Salt", "Control-Read-Encryption-Key"), key("Control-Salt", "Control-Write-Encryption-Key"))
                    control.request("GET", "/info", ByteArray(0))
                    assertEquals("cipher-device", (BplistCodec.decode(control.read().body) as Map<*, *>)["name"])
                    control.request("SETUP", "/", BplistCodec.encode(emptyMap<String, Any>()))
                    val ports = BplistCodec.decode(control.read().body) as Map<*, *>
                    Wire(Socket(loopback, (ports["eventPort"] as Number).toInt())).use { event ->
                        event.cipher = ControlCipher(key("Events-Salt", "Events-Write-Encryption-Key"), key("Events-Salt", "Events-Read-Encryption-Key"))
                        assertTrue(ready.await(5, TimeUnit.SECONDS))
                        event.request("OPTIONS", "*", ByteArray(0))
                        assertEquals("200", event.read().path)
                        assertTrue(session.sendCommand(mapOf("type" to "cipher-test", "params" to mapOf("data" to byteArrayOf(0, -1)))))
                        val command = event.read()
                        assertEquals("POST", command.method)
                        assertEquals("cipher-test", (BplistCodec.decode(command.body) as Map<*, *>)["type"])
                        val controlOwner = session.cipher!!
                        val eventOwner = field(session, "eventCipher") as ControlCipher
                        session.close(); session.close()
                        assertClosed(controlOwner); assertClosed(eventOwner)
                        assertNull(session.cipher); assertNull(field(session, "eventCipher"))
                    }
                } finally { session.close() }
            }
        }
    }

    @Test fun sessionCloseInterruptsBlockedEventWriteBeforeTakingTheWriteLock() {
        assertTrue(NativeMedia.available)
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val event = object : Socket() {
            override fun getOutputStream() = object : OutputStream() {
                override fun write(value: Int) {
                    entered.countDown(); check(released.await(5, TimeUnit.SECONDS)); throw IOException("closed")
                }
            }
            override fun close() { super.close(); released.countDown() }
        }
        val session = session(Socket())
        val controlOwner = ControlCipher(ByteArray(32), ByteArray(32))
        val eventOwner = ControlCipher(ByteArray(32), ByteArray(32))
        session.cipher = controlOwner
        field(session, "eventSocket", event); field(session, "eventCipher", eventOwner)
        val writer = Thread { session.sendCommand(mapOf("type" to "blocked")) }
        val closer = Thread { session.close() }
        try {
            writer.start(); assertTrue(entered.await(3, TimeUnit.SECONDS))
            closer.start(); closer.join(2000)
            assertFalse("close waited for the blocked event write lock", closer.isAlive)
            writer.join(2000); assertFalse(writer.isAlive)
            assertClosed(controlOwner); assertClosed(eventOwner)
        } finally { event.close(); writer.join(3000); closer.join(3000); session.close() }
    }

    @Test fun writerFailureAndReaderEofCannotDeadlockAConcurrentGearUpdate() {
        assertTrue(NativeMedia.available)
        for (readerEof in listOf(false, true)) exerciseAvailabilityClose(readerEof)
    }

    @Test fun anAcceptedSocketCannotPublishItsCipherAfterSessionClose() {
        assertTrue(NativeMedia.available)
        val loopback = InetAddress.getByName("127.0.0.1")
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val incoming = object : Socket() {
            override fun getInetAddress(): InetAddress = loopback
            override fun setSoLinger(on: Boolean, linger: Int) {
                accepted.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val server = object : ServerSocket() { override fun accept(): Socket = incoming }
        val control = object : Socket() { override fun getInetAddress(): InetAddress = loopback }
        val notifications = AtomicInteger()
        val session = session(control, listener = object : AirPlaySessionListener {
            override fun onClusterControlReady(session: AirPlaySession) { notifications.incrementAndGet() }
        })
        PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }.set(session.pairVerify, ByteArray(32))
        field(session, "eventServer", server)
        val worker = Thread {
            AirPlaySession::class.java.getDeclaredMethod("acceptEvent", ServerSocket::class.java)
                .apply { isAccessible = true }.invoke(session, server)
        }
        try {
            worker.start(); assertTrue(accepted.await(3, TimeUnit.SECONDS))
            session.close(); release.countDown(); worker.join(3000)
            assertFalse(worker.isAlive); assertTrue(incoming.isClosed)
            assertNull(field(session, "eventCipher")); assertNull(field(session, "eventSocket"))
            assertEquals(0, notifications.get())
        } finally { release.countDown(); incoming.close(); server.close(); session.close(); worker.join(3000) }
    }

    private fun exerciseAvailabilityClose(readerEof: Boolean) {
        val writing = CountDownLatch(1)
        val availabilityHeld = CountDownLatch(1)
        val failWrite = CountDownLatch(1)
        val eof = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val event = object : Socket() {
            override fun getOutputStream() = object : OutputStream() {
                override fun write(value: Int) {
                    writing.countDown()
                    check((if (readerEof) socketClosed else failWrite).await(5, TimeUnit.SECONDS))
                    throw IOException("test event write failed")
                }
            }
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int { check(eof.await(5, TimeUnit.SECONDS)); return -1 }
            }
            override fun close() { super.close(); socketClosed.countDown() }
        }
        val session = session(Socket())
        val controlOwner = ControlCipher(ByteArray(32), ByteArray(32))
        val eventOwner = ControlCipher(ByteArray(32), ByteArray(32))
        session.cipher = controlOwner
        field(session, "eventSocket", event); field(session, "eventCipher", eventOwner)
        val availability = field(session, "videoPlaybackAvailability") as VideoPlaybackAvailability
        availability.setFeatureEnabled(true); availability.setEventReady(true)
        fun worker(task: () -> Unit) = Thread {
            try { task() } catch (error: Throwable) { failures.add(error) }
        }.apply { isDaemon = true }
        val writer = worker { session.sendCommand(mapOf("type" to "blocked")) }
        val gear = worker {
            synchronized(availability) {
                availabilityHeld.countDown()
                session.setVideoPlaybackAllowed(true)
            }
        }
        val reader = if (readerEof) worker {
            AirPlaySession::class.java.getDeclaredMethod("runEventRead", Socket::class.java, ControlCipher::class.java)
                .apply { isAccessible = true }.invoke(session, event, eventOwner)
        } else null
        val workers = listOfNotNull(writer, gear, reader)
        try {
            writer.start(); assertTrue(writing.await(3, TimeUnit.SECONDS))
            gear.start(); assertTrue(availabilityHeld.await(3, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (gear.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals("gear must hold availability while waiting for eventWriteLock", Thread.State.BLOCKED, gear.state)
            if (reader != null) { reader.start(); eof.countDown() } else failWrite.countDown()
            workers.forEach { it.join(1500) }
            assertTrue("stuck threads: ${workers.map { it.state }}", workers.none { it.isAlive })
            assertTrue(failures.toString(), failures.isEmpty())
            assertTrue(session.isClosed)
            assertClosed(controlOwner); assertClosed(eventOwner)
            assertEquals(VideoPlaybackDelivery.QUEUED, session.setVideoPlaybackAllowed(false))
        } finally {
            failWrite.countDown(); eof.countDown(); event.close()
            workers.forEach { it.join(100) }
            if (workers.none { it.isAlive }) session.close()
        }
    }

    private fun session(socket: Socket, pairings: PairingStore = PairingStore(), listener: AirPlaySessionListener = object : AirPlaySessionListener {}) =
        AirPlaySession(socket, AirPlayConfig("cipher-device", "02:00:00:00:00:02", "02:00:00:00:00:01", "366.0",
            main = AirPlayDisplayConfig(1280, 720), videoInCar = true), AirPlayIdentity.generate(), pairings, null, listener, object : AirPlayMediaHandler {})

    private fun field(session: AirPlaySession, name: String): Any? = AirPlaySession::class.java.getDeclaredField(name).apply { isAccessible = true }.get(session)
    private fun field(session: AirPlaySession, name: String, value: Any?) = AirPlaySession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
    private fun assertClosed(cipher: ControlCipher) {
        assertThrows(IllegalStateException::class.java) { cipher.encrypt(ByteArray(0)) }
        assertEquals(0L, ControlCipher::class.java.getDeclaredField("handle").apply { isAccessible = true }.getLong(cipher))
    }

    private class Wire(private val socket: Socket) : Closeable {
        var cipher: ControlCipher? = null
        private var encrypted = ByteArray(0)
        private var plaintext = ByteArray(0)
        init { socket.soTimeout = 5000 }
        fun request(method: String, path: String, body: ByteArray) {
            val plain = "$method $path RTSP/1.0\r\nCSeq: 1\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
            val bytes = cipher?.encrypt(plain) ?: plain
            socket.getOutputStream().apply { write(bytes, 0, 1); flush(); write(bytes, 1, bytes.size - 1); flush() }
        }
        fun read(): RtspMessage.Request {
            val input = socket.getInputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val parsed = RtspMessage.parseMessages(plaintext)
                plaintext = parsed.rest
                if (parsed.messages.isNotEmpty()) return parsed.messages.single()
                val count = input.read(buffer)
                check(count > 0)
                val active = cipher
                if (active == null) plaintext += buffer.copyOf(count) else {
                    encrypted += buffer.copyOf(count)
                    val result = active.decrypt(encrypted)
                    encrypted = result.rest; plaintext += result.data
                }
            }
        }
        override fun close() { socket.close(); cipher?.close() }
    }
}
