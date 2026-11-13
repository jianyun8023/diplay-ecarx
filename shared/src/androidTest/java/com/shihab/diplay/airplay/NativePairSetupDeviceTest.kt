package com.shihab.diplay.airplay

import androidx.test.runner.AndroidJUnit4
import java.math.BigInteger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePairSetupDeviceTest {
    @Test fun completeHandshakeUsesAndroidUtf8ForRawControllerAndAccessoryIdentifiers() {
        val rawIds = listOf(byteArrayOf(-64, -81), byteArrayOf(-19, -96, -128), "控制😀\u0000".toByteArray())
        val keys = AirPlayIdentity.generate()
        for (name in listOf("a\ud800b", "\udc00\u0000\ufeff")) for (id in rawIds) {
            val identity = keys.copy(pairingId = name)
            val store = PairingStore()
            PairSetup(identity, store).use { owner ->
                val exchange = begin(owner)
                val controller = AirPlayCrypto.ed25519Generate()
                verifyM6(identity, exchange, owner.handle(m5(exchange, id, controller.privateKey, controller.publicKey)))
                assertTrue(owner.complete)
                assertArrayEquals(controller.publicKey, store.get(id.toString(Charsets.UTF_8)))
            }
        }
    }

    @Test fun longKeysKeepTheFullRawTranscriptAndStoreWhileCryptoUsesFirst32Bytes() {
        val original = AirPlayIdentity.generate()
        val identity = original.copy(privateKey = original.privateKey + byteArrayOf(7), publicKey = original.publicKey + byteArrayOf(8, 9))
        val store = PairingStore()
        PairSetup(identity, store).use { owner ->
            val exchange = begin(owner)
            val controller = AirPlayCrypto.ed25519Generate()
            val raw = controller.publicKey + ByteArray(300) { 7 }
            verifyM6(identity, exchange, owner.handle(m5(exchange, byteArrayOf(0), controller.privateKey, raw)), original.publicKey)
            assertArrayEquals(raw, store.get("\u0000"))
            assertTrue(owner.complete)
        }
    }

    @Test fun storeExceptionRetainsItsCommitAndErrorPropagatesUnchanged() {
        for (fatal in listOf(false, true)) {
            val failure = AssertionError("platform store error")
            var failSave = true
            val identity = AirPlayIdentity.generate()
            val store = PairingStore { _, _ -> if (failSave) {
                if (fatal) throw failure else throw IllegalStateException("platform store exception")
            } }
            PairSetup(identity, store).use { owner ->
                val exchange = begin(owner)
                val controller = AirPlayCrypto.ed25519Generate()
                val request = m5(exchange, "controller".toByteArray(), controller.privateKey, controller.publicKey)
                if (fatal) assertSame(failure, assertThrows(AssertionError::class.java) { owner.handle(request) })
                else assertArrayEquals(error(5), owner.handle(request))
                assertFalse(owner.complete)
                assertArrayEquals(controller.publicKey, store.get("controller"))
                failSave = false
                verifyM6(identity, exchange, owner.handle(request))
                assertTrue(owner.complete)
            }
        }
    }

    @Test fun invalidAccessorySeedFailsOnlyAfterTheControllerWasSaved() {
        val valid = AirPlayIdentity.generate()
        val identity = valid.copy(privateKey = valid.privateKey.copyOf(31))
        val store = PairingStore()
        PairSetup(identity, store).use { owner ->
            val exchange = begin(owner)
            val controller = AirPlayCrypto.ed25519Generate()
            assertArrayEquals(error(5), owner.handle(m5(exchange, "controller".toByteArray(), controller.privateKey, controller.publicKey)))
            assertArrayEquals(controller.publicKey, store.get("controller"))
            assertFalse(owner.complete)
        }
    }

    @Test fun wideSrpPublicValuesRetryAndLowOrderEdKeysRetainTheOldRejectionContract() {
        val identity = AirPlayIdentity.generate()
        PairSetup(identity, PairingStore()).use { owner ->
            for (state in listOf(0, 2, 4, 6, 255)) assertArrayEquals(error(state), owner.handle(message(state)))
            val exchange = begin(owner, wide = true)
            val controller = AirPlayCrypto.ed25519Generate()
            val forged = ByteArray(64).apply { fill(0x66, 0, 32); this[0] = 0x58; this[32] = 1 }
            assertArrayEquals(error(6), owner.handle(m5(exchange, byteArrayOf(1), controller.privateKey,
                ByteArray(32).apply { this[0] = 1 }, forged)))
            verifyM6(identity, exchange, owner.handle(m5(exchange, byteArrayOf(1), controller.privateKey, controller.publicKey)))
            assertTrue(owner.complete)
            owner.handle(message(1))
            assertArrayEquals(error(4), owner.handle(message(3, Tlv8Item(3, byteArrayOf(0)), Tlv8Item(4, ByteArray(64)))))
            assertArrayEquals(error(5), owner.handle(message(5, Tlv8Item(5, ByteArray(16)))))
            assertTrue(owner.complete)
        }
    }

    @Test fun closeSerializesWithRequestsAndStoreCanCloseReentrantlyWithoutKeepingAnOwner() {
        val owner = PairSetup(AirPlayIdentity.generate(), PairingStore())
        val ready = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val worker = Thread {
            try {
                owner.handle(message(1)); ready.countDown()
                repeat(3) { owner.handle(message(1)); owner.complete }
            } catch (closed: IllegalStateException) {
                if (closed.message != "PairSetup is closed") failures.add(closed)
            } catch (failure: Throwable) { failures.add(failure) }
        }
        try {
            worker.start(); assertTrue(ready.await(10, TimeUnit.SECONDS))
            owner.close(); owner.close(); worker.join(10000)
            assertFalse(worker.isAlive); assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(0L, handle(owner)); assertFalse(owner.complete)
            assertThrows(IllegalStateException::class.java) { owner.handle(message(1)) }
        } finally { owner.close(); worker.join(10000) }
        val identity = AirPlayIdentity.generate()
        lateinit var reentrant: PairSetup
        reentrant = PairSetup(identity, PairingStore { _, _ -> assertFalse(reentrant.complete); reentrant.close() })
        reentrant.use {
            val exchange = begin(it)
            val controller = AirPlayCrypto.ed25519Generate()
            verifyM6(identity, exchange, it.handle(m5(exchange, byteArrayOf(0), controller.privateKey, controller.publicKey)))
            assertEquals(0L, handle(it)); assertFalse(it.complete)
        }
    }

    private fun begin(owner: PairSetup, wide: Boolean = false): PairSetupDeviceSrpClient {
        assertTrue(NativeMedia.available)
        assertFalse(owner.complete)
        val response = Tlv8Codec.decode(owner.handle(message(1) + byteArrayOf(6, 2, 7)))
        assertTrue(handle(owner) != 0L)
        assertArrayEquals(byteArrayOf(2), response[6])
        // N<<16 is added to A to exercise the original uncapped minimum-width SRP padding.
        val offset = if (wide) PairSetupDeviceSrpClient.modulus.shiftLeft(16) else BigInteger.ZERO
        val exchange = PairSetupDeviceSrpClient(response[2]!!, response[3]!!, publicOffset = offset)
        assertArrayEquals(error(4), owner.handle(message(3, Tlv8Item(3, exchange.publicA), Tlv8Item(4, ByteArray(64)))))
        val m4 = Tlv8Codec.decode(owner.handle(message(3, Tlv8Item(3, exchange.publicA), Tlv8Item(4, exchange.proof))))
        assertArrayEquals(byteArrayOf(4), m4[6]); assertArrayEquals(exchange.serverProof, m4[4])
        return exchange
    }

    private fun m5(exchange: PairSetupDeviceSrpClient, id: ByteArray, privateKey: ByteArray, public: ByteArray,
        signatureOverride: ByteArray? = null): ByteArray {
        val signed = derive(exchange.key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info") + id + public
        val signature = signatureOverride ?: AirPlayCrypto.ed25519Sign(privateKey, signed)
        val sub = Tlv8Codec.encode(listOf(Tlv8Item(1, id), Tlv8Item(3, public), Tlv8Item(10, signature)))
        val key = derive(exchange.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        return message(5, Tlv8Item(5, AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonceLabel("PS-Msg05"), sub)))
    }

    private fun verifyM6(identity: AirPlayIdentity, exchange: PairSetupDeviceSrpClient, response: ByteArray,
        public: ByteArray = identity.publicKey) {
        val outer = Tlv8Codec.decode(response)
        assertArrayEquals(byteArrayOf(6), outer[6])
        val key = derive(exchange.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val sub = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonceLabel("PS-Msg06"), outer[5]!!))
        assertArrayEquals(identity.publicKey, sub[3])
        assertArrayEquals(identity.pairingId.toByteArray(Charsets.UTF_8), sub[1])
        val signKey = derive(exchange.key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        assertTrue(AirPlayCrypto.ed25519Verify(public, signKey + sub[1]!! + sub[3]!!, sub[10]!!))
    }

    private fun handle(owner: PairSetup) = PairSetup::class.java.getDeclaredField("handle").apply { isAccessible = true }.getLong(owner)
    private fun derive(key: ByteArray, salt: String, info: String) = AirPlayCrypto.hkdfSha512(key, salt.toByteArray(), info.toByteArray())
    private fun message(state: Int, vararg items: Tlv8Item) = Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(state.toByte()))) + items)
    private fun error(state: Int) = byteArrayOf(6, 1, state.toByte(), 7, 1, 2)
}
