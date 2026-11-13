package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

/** Black-box contracts of the pre-migration Kotlin responder and its BC 1.79 key boundary. */
class PairVerifyContractTest {
    private val responders = ArrayList<PairVerify>()
    @After fun closeResponders() { responders.forEach { it.close() } }
    private data class Handshake(val peerRaw: ByteArray, val serverPublic: ByteArray,
        val shared: ByteArray, val encryptionKey: ByteArray)

    private inner class Fixture(identity: AirPlayIdentity = AirPlayIdentity.generate(), storedKeyBytes: Int = 32) {
        val identity = identity
        val controllerId = "controller-π\u0000id"
        val controller = AirPlayCrypto.ed25519Generate()
        val ephemeral = AirPlayCrypto.x25519Generate()
        val pairings = PairingStore().apply {
            save(controllerId, if (storedKeyBytes < 32) controller.publicKey.copyOf(storedKeyBytes)
                else controller.publicKey + ByteArray(storedKeyBytes - 32) { 0x5a })
        }
        val responder = PairVerify(identity, pairings).also { responders.add(it) }

        fun begin(raw: ByteArray = ephemeral.publicKey, suffix: ByteArray = byteArrayOf()): Handshake {
            val reply = Tlv8Codec.decode(responder.handle(message(1, Tlv8Item(3, raw)) + suffix))
            assertArrayEquals(byteArrayOf(2), reply[6]); assertNull(reply[7])
            val serverPublic = reply[3]!!
            val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, serverPublic)
            assertArrayEquals(shared, responder.shared)
            val key = derive(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
            val sub = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonceLabel("PV-Msg02"), reply[5]!!))
            val accessoryId = identity.pairingId.toByteArray(Charsets.UTF_8)
            assertArrayEquals(accessoryId, sub[1])
            assertTrue(AirPlayCrypto.ed25519Verify(identity.publicKey,
                concatBytes(serverPublic, accessoryId, raw), sub[10]!!))
            return Handshake(raw, serverPublic, shared, key)
        }

        fun finish(handshake: Handshake, id: String = controllerId, signature: ByteArray? = null,
            subSuffix: ByteArray = byteArrayOf(), outerSuffix: ByteArray = byteArrayOf()): ByteArray {
            val rawId = id.toByteArray(Charsets.UTF_8)
            val signed = signature ?: AirPlayCrypto.ed25519Sign(controller.privateKey,
                concatBytes(handshake.peerRaw, rawId, handshake.serverPublic))
            val sub = Tlv8Codec.encode(listOf(Tlv8Item(1, rawId), Tlv8Item(10, signed))) + subSuffix
            val sealed = AirPlayCrypto.chachaSeal(handshake.encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg03"), sub)
            return responder.handle(message(3, Tlv8Item(5, sealed)) + outerSuffix)
        }
    }

    @Test fun m2PublishesTheSharedSecretAndEncryptionBeforeControlKeysExist() {
        val fixture = Fixture()
        assertNull(fixture.responder.shared); assertNull(fixture.responder.controlKeys)
        assertFalse(fixture.responder.isVerified); assertNull(fixture.responder.verifiedControllerId)
        val pending = fixture.begin()
        assertNull(fixture.responder.controlKeys); assertFalse(fixture.responder.isVerified)
        assertArrayEquals(message(4), fixture.finish(pending))
        assertTrue(fixture.responder.isVerified)
        assertEquals(fixture.controllerId, fixture.responder.verifiedControllerId)
        val keys = fixture.responder.controlKeys!!
        assertArrayEquals(derive(pending.shared, "Control-Salt", "Control-Write-Encryption-Key"), keys.readKey)
        assertArrayEquals(derive(pending.shared, "Control-Salt", "Control-Read-Encryption-Key"), keys.writeKey)
    }

    @Test fun keyAgreementUsesTheFirst32BytesButBothSignaturesUseTheFullPeerRaw() {
        val fixture = Fixture(storedKeyBytes = 39)
        val raw = fixture.ephemeral.publicKey + byteArrayOf(9, 8, 7)
        val pending = fixture.begin(raw)
        // BC's (byte[], offset) key constructors consume the first 32 bytes, including stored Ed keys.
        val prefixSignature = AirPlayCrypto.ed25519Sign(fixture.controller.privateKey,
            concatBytes(raw.copyOf(32), fixture.controllerId.toByteArray(Charsets.UTF_8), pending.serverPublic))
        assertError(4, fixture.finish(pending, signature = prefixSignature))
        assertArrayEquals(message(4), fixture.finish(pending))
        assertTrue(fixture.responder.isVerified)
    }

    @Test fun truncatedDuplicateTlvItemsLeavePreviouslyCompletedItemsUsable() {
        val fixture = Fixture()
        val pending = fixture.begin(suffix = byteArrayOf(3, 2, 99))
        assertArrayEquals(message(4), fixture.finish(pending,
            subSuffix = byteArrayOf(1, 2, 99), outerSuffix = byteArrayOf(5, 2, 99)))
        assertTrue(fixture.responder.isVerified)
    }

    @Test fun missingShortAndLowOrderKeysKeepTheirDistinctErrorStates() {
        assertError(2, Fixture().responder.handle(message(1)))
        for (raw in listOf(byteArrayOf(), ByteArray(31) { 3 }, ByteArray(32), byteArrayOf(1) + ByteArray(31))) {
            val fixture = Fixture()
            assertError(1, fixture.responder.handle(message(1, Tlv8Item(3, raw))))
            assertNull(fixture.responder.shared); assertFalse(fixture.responder.isVerified)
        }
        for (request in listOf(byteArrayOf(), message(255), message(2), Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf()))))) {
            val state = Tlv8Codec.decode(request)[6]?.firstOrNull()?.toInt()?.and(255) ?: 0
            assertError(state, Fixture().responder.handle(request))
        }
    }

    @Test fun m3RejectionsDoNotConsumeThePendingHandshake() {
        val fixture = Fixture()
        assertError(4, fixture.responder.handle(message(3, Tlv8Item(5, ByteArray(16)))))
        val pending = fixture.begin()
        assertError(4, fixture.responder.handle(message(3)))
        assertError(3, fixture.responder.handle(message(3, Tlv8Item(5, ByteArray(16)))))
        assertError(4, fixture.finish(pending, id = "not-paired"))
        assertError(4, fixture.finish(pending, signature = ByteArray(64)))
        assertError(4, fixture.finish(pending, signature = ByteArray(63)))
        assertArrayEquals(pending.shared, fixture.responder.shared)
        assertFalse(fixture.responder.isVerified); assertNull(fixture.responder.controlKeys)
        assertArrayEquals(message(4), fixture.finish(pending))

        val shortStoredKey = Fixture(storedKeyBytes = 31)
        assertError(4, shortStoredKey.finish(shortStoredKey.begin()))
    }

    @Test fun failedNewM1KeepsOldSharedAndEncryptionButReplacesTheTranscript() {
        val fixture = Fixture()
        val pending = fixture.begin()
        assertError(1, fixture.responder.handle(message(1, Tlv8Item(3, ByteArray(31) { 7 }))))
        assertArrayEquals(pending.shared, fixture.responder.shared)
        // Retained encryption still attempts to open M3 (error state 3 rather than missing-state 4).
        assertError(3, fixture.responder.handle(message(3, Tlv8Item(5, ByteArray(16)))))
        // The old, correctly encrypted signature now has the wrong own/client public transcript.
        assertError(4, fixture.finish(pending))
    }

    @Test fun duplicateOrFailedRequestsAndNewM1DoNotClearAnAlreadyVerifiedController() {
        val fixture = Fixture()
        val first = fixture.begin()
        assertArrayEquals(message(4), fixture.finish(first))
        val saved = fixture.responder.controlKeys!!
        fun stillVerified() {
            assertTrue(fixture.responder.isVerified)
            assertEquals(fixture.controllerId, fixture.responder.verifiedControllerId)
            assertArrayEquals(saved.readKey, fixture.responder.controlKeys!!.readKey)
            assertArrayEquals(saved.writeKey, fixture.responder.controlKeys!!.writeKey)
        }
        assertArrayEquals(message(4), fixture.finish(first)); stillVerified()
        assertError(255, fixture.responder.handle(message(255))); stillVerified()
        assertError(3, fixture.responder.handle(message(3, Tlv8Item(5, ByteArray(16))))); stillVerified()
        assertError(1, fixture.responder.handle(message(1, Tlv8Item(3, ByteArray(31))))); stillVerified()
        assertError(4, fixture.finish(first)); stillVerified()
        val next = fixture.begin(); stillVerified()
        assertArrayEquals(message(4), fixture.finish(next))
    }

    @Test fun identitySigningFailureHappensAfterSharedAndEncryptionArePublished() {
        val validIdentity = AirPlayIdentity.generate()
        val fixture = Fixture(validIdentity.copy(privateKey = validIdentity.privateKey.copyOf(31)))
        assertError(1, fixture.responder.handle(message(1, Tlv8Item(3, fixture.ephemeral.publicKey))))
        assertEquals(32, fixture.responder.shared!!.size)
        assertFalse(fixture.responder.isVerified); assertNull(fixture.responder.controlKeys)
        assertError(3, fixture.responder.handle(message(3, Tlv8Item(5, ByteArray(16)))))
    }

    @Test fun lowOrderAndNoncanonicalStoredEdKeysRejectTheUniversalIdentityForgery() {
        val fixture = Fixture()
        val pending = fixture.begin()
        // R=B, S=1 verifies for identity A in ring 0.17.14 without BC's public-key boundary check.
        val forged = ByteArray(64).apply { fill(0x66, 0, 32); this[0] = 0x58; this[32] = 1 }
        val bad = arrayListOf(ByteArray(32), ByteArray(32).apply { this[0] = 1 })
        for (hex in listOf(
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
        )) bad.add(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        for (first in 0xec..0xff) bad.add(ByteArray(32) { 0xff.toByte() }.apply { this[0] = first.toByte(); this[31] = 0x7f })
        for (point in bad) for (sign in listOf(0, 0x80)) {
            val encoded = point.copyOf().apply { this[31] = (this[31].toInt() or sign).toByte() }
            fixture.pairings.save(fixture.controllerId, encoded)
            assertError(4, fixture.finish(pending, signature = forged))
            assertFalse(fixture.responder.isVerified)
        }
    }

    companion object {
        private fun message(state: Int, vararg items: Tlv8Item): ByteArray =
            Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(state.toByte()))) + items)
        private fun derive(shared: ByteArray, salt: String, info: String): ByteArray =
            AirPlayCrypto.hkdfSha512(shared, salt.toByteArray(Charsets.US_ASCII), info.toByteArray(Charsets.US_ASCII))
        private fun assertError(state: Int, response: ByteArray) {
            val fields = Tlv8Codec.decode(response)
            assertEquals(setOf(6, 7), fields.keys)
            assertArrayEquals(byteArrayOf(state.toByte()), fields[6]); assertArrayEquals(byteArrayOf(2), fields[7])
        }
    }
}
