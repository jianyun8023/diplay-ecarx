package com.shihab.diplay.airplay

import androidx.test.runner.AndroidJUnit4
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePairVerifyDeviceTest {
    @Test fun nativeHandshakeUsesPlatformUtf8ForMalformedIdentifiersAndAccessoryNames() {
        val ids = listOf(byteArrayOf(0), byteArrayOf(-64, -81), byteArrayOf(-19, -96, -128),
            byteArrayOf(-12, -112, -128, -128), byteArrayOf(-30, -126), "控制😀\u0000".toByteArray())
        val key = AirPlayCrypto.ed25519Generate()
        for (name in listOf("accessory\u0000", "a\ud800b", "a\udc00b", "\ud800\u0000\ufeff")) {
            val identity = AirPlayIdentity(key.privateKey, key.publicKey, name)
            for (id in ids) {
                val pairings = PairingStore()
                PairVerify(identity, pairings).use { owner ->
                    assertNative(owner)
                    val exchange = begin(owner, identity)
                    val controller = AirPlayCrypto.ed25519Generate()
                    pairings.save(id.toString(Charsets.UTF_8), controller.publicKey)
                    assertArrayEquals(byteArrayOf(6, 1, 4), owner.handle(m3(exchange, id, controller.privateKey)))
                    assertEquals(id.toString(Charsets.UTF_8), owner.verifiedControllerId)
                    assertKeys(owner, exchange.shared)
                }
            }
        }
    }

    @Test fun nativeLongKeysUseFirst32BytesForCryptoAndWholePeerForTranscript() {
        val key = AirPlayCrypto.ed25519Generate()
        val identity = AirPlayIdentity(key.privateKey + byteArrayOf(5, 7), key.publicKey, "accessory")
        val controller = AirPlayCrypto.ed25519Generate()
        val pairings = PairingStore().apply { save("controller", controller.publicKey + ByteArray(300) { 7 }) }
        PairVerify(identity, pairings).use { owner ->
            assertNative(owner)
            val exchange = begin(owner, identity, ByteArray(300) { 17 })
            assertArrayEquals(byteArrayOf(6, 1, 4), owner.handle(m3(exchange, "controller".toByteArray(), controller.privateKey)))
            assertKeys(owner, exchange.shared)
        }
    }

    @Test fun nativeLookupObservesStoreChangesAtM3AndDoesNotCachePreviousSuccess() {
        val identity = AirPlayIdentity.generate()
        val controller = AirPlayCrypto.ed25519Generate()
        val pairings = PairingStore()
        PairVerify(identity, pairings).use { owner ->
            assertNative(owner)
            val exchange = begin(owner, identity)
            val m3 = m3(exchange, "controller".toByteArray(), controller.privateKey)
            assertArrayEquals(error(4), owner.handle(m3))
            pairings.save("controller", controller.publicKey)
            assertArrayEquals(byteArrayOf(6, 1, 4), owner.handle(m3))
            val keys = owner.controlKeys!!
            pairings.clear()
            assertArrayEquals(error(4), owner.handle(m3))
            assertTrue(owner.isVerified)
            assertArrayEquals(keys.readKey, owner.controlKeys!!.readKey)
            assertArrayEquals(keys.writeKey, owner.controlKeys!!.writeKey)
            pairings.save("controller", ByteArray(32).apply { this[0] = 1 })
            val forged = ByteArray(64).apply { fill(0x66, 0, 32); this[0] = 0x58; this[32] = 1 }
            assertArrayEquals(error(4), owner.handle(m3(exchange, "controller".toByteArray(), controller.privateKey, forged)))
        }
    }

    @Test fun invalidIdentitySeedFailsAtM2SigningAfterPublishingSharedLikeFallback() {
        val identity = AirPlayIdentity(ByteArray(31), ByteArray(32), "accessory")
        val m1 = Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(1)), Tlv8Item(3, AirPlayCrypto.x25519Generate().publicKey)))
        val m3 = Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(3)), Tlv8Item(5, ByteArray(16))))
        val fallback = KotlinPairVerify(identity, PairingStore())
        PairVerify(identity, PairingStore()).use { owner ->
            assertNative(owner)
            assertArrayEquals(fallback.handle(m1), owner.handle(m1))
            assertEquals(32, owner.shared!!.size)
            assertEquals(fallback.shared!!.size, owner.shared!!.size)
            assertArrayEquals(fallback.handle(m3), owner.handle(m3))
            assertArrayEquals(error(3), owner.handle(m3))
            assertFalse(owner.isVerified)
        }
    }

    @Test fun malformedWireErrorsMatchFallbackIncludingTruncatedTlvAndLowOrderPoints() {
        val identity = AirPlayIdentity.generate()
        val fallback = KotlinPairVerify(identity, PairingStore())
        PairVerify(identity, PairingStore()).use { owner ->
            assertNative(owner)
            val inputs = listOf(ByteArray(0), byteArrayOf(6, 1, -1), byteArrayOf(6, 2, 1),
                byteArrayOf(6, 1, 1, 3, 2, 9), byteArrayOf(6, 1, 3),
                Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(1)), Tlv8Item(3, ByteArray(31)))),
                Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(1)), Tlv8Item(3, ByteArray(32)))))
            inputs.forEach { assertArrayEquals(fallback.handle(it), owner.handle(it)) }
        }
    }

    @Test fun closeSerializesWithHandshakeQueriesAndClearsTheNativeHandle() {
        val owner = PairVerify(AirPlayIdentity.generate(), PairingStore())
        assertNative(owner)
        val m1 = Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(1)), Tlv8Item(3, AirPlayCrypto.x25519Generate().publicKey)))
        val ready = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val worker = Thread {
            try {
                owner.handle(m1)
                ready.countDown()
                repeat(100) { owner.handle(m1); owner.shared; owner.controlKeys; owner.verifiedControllerId; owner.isVerified }
            } catch (closed: IllegalStateException) {
                if (closed.message != "PairVerify is closed") failures.add(closed)
            } catch (failure: Throwable) { failures.add(failure) }
        }
        try {
            worker.start()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            owner.close(); owner.close(); worker.join(5000)
            assertFalse(worker.isAlive)
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(0L, handle(owner))
            assertNull(owner.shared); assertNull(owner.controlKeys); assertNull(owner.verifiedControllerId); assertFalse(owner.isVerified)
            try { owner.handle(m1); fail("closed owner accepted M1") } catch (closed: IllegalStateException) {
                assertEquals("PairVerify is closed", closed.message)
            }
        } finally { owner.close(); worker.join(5000) }
    }

    private data class Exchange(val client: ByteArray, val accessory: ByteArray, val shared: ByteArray)

    private fun begin(owner: PairVerify, identity: AirPlayIdentity, suffix: ByteArray = ByteArray(0)): Exchange {
        assertTrue(NativeMedia.available)
        val ephemeral = AirPlayCrypto.x25519Generate()
        val raw = ephemeral.publicKey + suffix
        val response = Tlv8Codec.decode(owner.handle(Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(1)), Tlv8Item(3, raw)))))
        assertArrayEquals(byteArrayOf(2), response[6])
        val publicKey = response[3]!!
        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, publicKey)
        assertArrayEquals(shared, owner.shared)
        val encryption = derive(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val sub = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encryption, AirPlayCrypto.nonceLabel("PV-Msg02"), response[5]!!))
        assertArrayEquals(identity.pairingId.toByteArray(Charsets.UTF_8), sub[1])
        assertTrue(AirPlayCrypto.ed25519Verify(identity.publicKey, publicKey + sub[1]!! + raw, sub[10]!!))
        return Exchange(raw, publicKey, shared)
    }

    private fun m3(exchange: Exchange, id: ByteArray, privateKey: ByteArray, signatureOverride: ByteArray? = null): ByteArray {
        val signature = signatureOverride ?: AirPlayCrypto.ed25519Sign(privateKey, exchange.client + id + exchange.accessory)
        val sub = Tlv8Codec.encode(listOf(Tlv8Item(1, id), Tlv8Item(10, signature)))
        val encryption = derive(exchange.shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        return Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(3)),
            Tlv8Item(5, AirPlayCrypto.chachaSeal(encryption, AirPlayCrypto.nonceLabel("PV-Msg03"), sub))))
    }

    private fun assertKeys(owner: PairVerify, shared: ByteArray) {
        assertTrue(owner.isVerified)
        assertArrayEquals(derive(shared, "Control-Salt", "Control-Write-Encryption-Key"), owner.controlKeys!!.readKey)
        assertArrayEquals(derive(shared, "Control-Salt", "Control-Read-Encryption-Key"), owner.controlKeys!!.writeKey)
    }
    private fun derive(shared: ByteArray, salt: String, info: String) = AirPlayCrypto.hkdfSha512(shared, salt.toByteArray(), info.toByteArray())
    private fun error(state: Int) = byteArrayOf(6, 1, state.toByte(), 7, 1, 2)
    private fun handle(owner: PairVerify) = PairVerify::class.java.getDeclaredField("handle").apply { isAccessible = true }.getLong(owner)
    private fun assertNative(owner: PairVerify) { assertTrue(NativeMedia.available); assertNotEquals(0L, handle(owner)) }
}
