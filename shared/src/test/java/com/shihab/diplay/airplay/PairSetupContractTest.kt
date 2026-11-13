package com.shihab.diplay.airplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.After

/** Executable contract of the old Kotlin M1-M6 responder, including its partial store commits. */
class PairSetupContractTest {
    private val owners = ArrayList<PairSetup>()
    @After fun closeOwners() { owners.forEach { it.close() } }
    private inner class Fixture(
        val identity: AirPlayIdentity = AirPlayIdentity.generate(),
        onSave: ((String, ByteArray) -> Unit)? = null,
    ) {
        val saved = ArrayList<Pair<String, ByteArray>>()
        val store = PairingStore { id, key -> saved.add(id to key.copyOf()); onSave?.invoke(id, key) }
        val setup = PairSetup(identity, store).also { owners.add(it) }
        val controller = AirPlayCrypto.ed25519Generate()
        val id = "controller-π\u0000".toByteArray(Charsets.UTF_8)

        fun throughM4(password: String = "3939", suffix: ByteArray = byteArrayOf()): PairSetupSrpClient {
            val m2 = Tlv8Codec.decode(setup.handle(message(1, Tlv8Item(0, byteArrayOf(99))) + suffix))
            assertArrayEquals(byteArrayOf(2), m2[6]); assertEquals(setOf(6, 3, 2), m2.keys)
            assertEquals(16, m2[2]!!.size); assertEquals(384, m2[3]!!.size)
            val client = PairSetupSrpClient(m2[2]!!, m2[3]!!, password)
            val m4 = setup.handle(message(3, Tlv8Item(3, client.publicA), Tlv8Item(4, client.proof)))
            if (password != "3939") assertError(4, m4) else {
                val decoded = Tlv8Codec.decode(m4)
                assertEquals(setOf(6, 4), decoded.keys); assertArrayEquals(byteArrayOf(4), decoded[6])
                assertArrayEquals(client.serverProof, decoded[4])
            }
            return client
        }

        fun m5(client: PairSetupSrpClient, rawId: ByteArray = id, rawPublic: ByteArray = controller.publicKey,
            signatureOverride: ByteArray? = null, subSuffix: ByteArray = byteArrayOf(), outerSuffix: ByteArray = byteArrayOf()): ByteArray {
            val signKey = derive(client.key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
            val signature = signatureOverride ?: AirPlayCrypto.ed25519Sign(controller.privateKey, signKey + rawId + rawPublic)
            val sub = Tlv8Codec.encode(listOf(Tlv8Item(1, rawId), Tlv8Item(3, rawPublic), Tlv8Item(10, signature))) + subSuffix
            return sealedM5(client, sub) + outerSuffix
        }

        fun checkM6(client: PairSetupSrpClient, response: ByteArray, verifyKey: ByteArray = identity.publicKey) {
            val outer = Tlv8Codec.decode(response)
            assertEquals(setOf(6, 5), outer.keys); assertArrayEquals(byteArrayOf(6), outer[6])
            val encryption = derive(client.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
            val sub = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encryption, AirPlayCrypto.nonceLabel("PS-Msg06"), outer[5]!!))
            val id = identity.pairingId.toByteArray(Charsets.UTF_8)
            assertEquals(setOf(1, 3, 10), sub.keys); assertArrayEquals(id, sub[1]); assertArrayEquals(identity.publicKey, sub[3])
            val signKey = derive(client.key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
            assertTrue(AirPlayCrypto.ed25519Verify(verifyKey, signKey + id + identity.publicKey, sub[10]!!))
            assertTrue(setup.complete)
        }
    }

    @Test fun completeM1ThroughM6AuthenticatesBothProofsAndPersistsBeforeCompleting() {
        val fixture = Fixture()
        assertFalse(fixture.setup.complete)
        val client = fixture.throughM4()
        assertTrue(fixture.saved.isEmpty()); assertNull(fixture.store.get(fixture.id.toString(Charsets.UTF_8)))
        assertFalse(fixture.setup.complete)
        fixture.checkM6(client, fixture.setup.handle(fixture.m5(client)))
        assertEquals(1, fixture.saved.size)
        assertEquals(fixture.id.toString(Charsets.UTF_8), fixture.saved.single().first)
        assertArrayEquals(fixture.controller.publicKey, fixture.store.get(fixture.id.toString(Charsets.UTF_8)))
    }

    @Test fun missingUnsupportedAndOutOfOrderStatesHaveDistinctErrors() {
        val fixture = Fixture()
        for (state in listOf(0, 2, 4, 6, 255)) assertError(state, fixture.setup.handle(message(state)))
        assertError(0, fixture.setup.handle(byteArrayOf()))
        assertError(0, fixture.setup.handle(byteArrayOf(6, 0)))
        assertError(4, fixture.setup.handle(message(3, Tlv8Item(3, byteArrayOf(1)), Tlv8Item(4, ByteArray(64)))))
        assertError(6, fixture.setup.handle(message(5, Tlv8Item(5, ByteArray(16)))))
        assertFalse(fixture.setup.complete); assertTrue(fixture.saved.isEmpty())
    }

    @Test fun wrongSrpCodeAndInvalidProofsCanRetryWithoutSaving() {
        val fixture = Fixture()
        val wrong = fixture.throughM4(password = "0000")
        for (a in listOf(byteArrayOf(), byteArrayOf(0), PairSetupSrpClient.unsigned(Srp6a.modulus())))
            assertError(4, fixture.setup.handle(message(3, Tlv8Item(3, a), Tlv8Item(4, wrong.proof))))
        assertError(4, fixture.setup.handle(message(3)))
        assertError(4, fixture.setup.handle(message(3, Tlv8Item(3, wrong.publicA), Tlv8Item(4, ByteArray(63)))))
        val client = fixture.throughM4()
        fixture.checkM6(client, fixture.setup.handle(fixture.m5(client)))
    }

    @Test fun authenticatedM5MissingFieldsAndBadOrShortSignaturesNeverSave() {
        val fixture = Fixture()
        val client = fixture.throughM4()
        for (sub in listOf(byteArrayOf(), Tlv8Codec.encode(listOf(Tlv8Item(1, fixture.id))),
            Tlv8Codec.encode(listOf(Tlv8Item(1, fixture.id), Tlv8Item(3, fixture.controller.publicKey)))))
            assertError(6, fixture.setup.handle(sealedM5(client, sub)))
        for (signature in listOf(ByteArray(64), ByteArray(63))) assertError(6, fixture.setup.handle(fixture.m5(client, signatureOverride = signature)))
        assertTrue(fixture.saved.isEmpty()); assertFalse(fixture.setup.complete)
        fixture.checkM6(client, fixture.setup.handle(fixture.m5(client)))
    }

    @Test fun shortAndLowOrderControllerKeysAreRejectedAsSignatureErrors() {
        val fixture = Fixture()
        val client = fixture.throughM4()
        assertError(6, fixture.setup.handle(fixture.m5(client, rawPublic = fixture.controller.publicKey.copyOf(31))))
        val identityPoint = ByteArray(32).apply { this[0] = 1 }
        val universal = ByteArray(64).apply { fill(0x66, 0, 32); this[0] = 0x58; this[32] = 1 }
        assertError(6, fixture.setup.handle(fixture.m5(client, rawPublic = identityPoint, signatureOverride = universal)))
        assertTrue(fixture.saved.isEmpty()); assertFalse(fixture.setup.complete)
    }

    @Test fun badAeadIsError5WhereMissingEncryptedDataIsError6AndBothAllowRetry() {
        val fixture = Fixture()
        val client = fixture.throughM4()
        assertError(6, fixture.setup.handle(message(5)))
        for (sealed in listOf(byteArrayOf(), ByteArray(3), ByteArray(16))) assertError(5, fixture.setup.handle(message(5, Tlv8Item(5, sealed))))
        assertTrue(fixture.saved.isEmpty()); assertFalse(fixture.setup.complete)
        fixture.checkM6(client, fixture.setup.handle(fixture.m5(client)))
    }

    @Test fun truncatedDuplicateTlvItemsAreIgnoredAfterPreviouslyCompletedValues() {
        val fixture = Fixture()
        val client = fixture.throughM4(suffix = byteArrayOf(6, 2, 9))
        fixture.checkM6(client, fixture.setup.handle(fixture.m5(client,
            subSuffix = byteArrayOf(3, 2, 9), outerSuffix = byteArrayOf(5, 2, 9))))
    }

    @Test fun longControllerKeyUsesPrefixForVerificationAndFullRawForTranscriptAndStorage() {
        val fixture = Fixture()
        val client = fixture.throughM4()
        val raw = fixture.controller.publicKey + ByteArray(300) { 0x5a }
        val signKey = derive(client.key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val prefixOnlySignature = AirPlayCrypto.ed25519Sign(fixture.controller.privateKey, signKey + fixture.id + fixture.controller.publicKey)
        assertError(6, fixture.setup.handle(fixture.m5(client, rawPublic = raw, signatureOverride = prefixOnlySignature)))
        fixture.checkM6(client, fixture.setup.handle(fixture.m5(client, rawPublic = raw)))
        assertArrayEquals(raw, fixture.store.get(fixture.id.toString(Charsets.UTF_8)))
        assertArrayEquals(raw, fixture.saved.single().second)
    }

    @Test fun identifiersUsePlatformUtf8ButSignaturesKeepOriginalRawBytes() {
        val valid = AirPlayIdentity.generate()
        val fixture = Fixture(valid.copy(pairingId = "accessory\ud800\u0000x"))
        val client = fixture.throughM4()
        for (raw in listOf(byteArrayOf(), byteArrayOf(-19, -96, -128, 0), "设备😀\u0000".toByteArray(Charsets.UTF_8))) {
            fixture.checkM6(client, fixture.setup.handle(fixture.m5(client, rawId = raw)))
            assertArrayEquals(fixture.controller.publicKey, fixture.store.get(raw.toString(Charsets.UTF_8)))
            assertEquals(raw.toString(Charsets.UTF_8), fixture.saved.last().first)
        }
    }

    @Test fun m1AndFailedM3RetainThePreviousSessionKeyAndCompleteFlag() {
        val fixture = Fixture()
        val first = fixture.throughM4()
        val oldM5 = fixture.m5(first)
        fixture.setup.handle(message(1)) // Only the SRP session is replaced.
        assertError(4, fixture.setup.handle(message(3, Tlv8Item(3, byteArrayOf(0)), Tlv8Item(4, ByteArray(64)))))
        fixture.checkM6(first, fixture.setup.handle(oldM5))
        assertError(5, fixture.setup.handle(message(5, Tlv8Item(5, ByteArray(16)))))
        assertTrue(fixture.setup.complete)
        val next = fixture.throughM4()
        assertTrue(fixture.setup.complete)
        assertError(5, fixture.setup.handle(oldM5)) // New valid M3 replaces the encryption session.
        assertTrue(fixture.setup.complete)
        fixture.checkM6(next, fixture.setup.handle(fixture.m5(next)))
    }

    @Test fun onSaveExceptionLeavesTheMapWrittenAndCompleteFalseThenCanRetry() {
        var failSave = true
        val fixture = Fixture(onSave = { _, _ -> if (failSave) throw IllegalStateException("test store failure") })
        val client = fixture.throughM4()
        assertError(5, fixture.setup.handle(fixture.m5(client)))
        assertArrayEquals(fixture.controller.publicKey, fixture.store.get(fixture.id.toString(Charsets.UTF_8)))
        assertEquals(1, fixture.saved.size); assertFalse(fixture.setup.complete)
        failSave = false
        fixture.checkM6(client, fixture.setup.handle(fixture.m5(client)))
        assertEquals(2, fixture.saved.size)
    }

    @Test fun onSaveErrorPropagatesInsteadOfBecomingAnAuthenticationError() {
        val fatal = AssertionError("test platform error")
        val fixture = Fixture(onSave = { _, _ -> throw fatal })
        val client = fixture.throughM4()
        assertSame(fatal, assertThrows(AssertionError::class.java) { fixture.setup.handle(fixture.m5(client)) })
        assertArrayEquals(fixture.controller.publicKey, fixture.store.get(fixture.id.toString(Charsets.UTF_8)))
        assertFalse(fixture.setup.complete)
    }

    @Test fun invalidIdentitySeedFailsAfterTheControllerWasAlreadySaved() {
        val identity = AirPlayIdentity.generate()
        val fixture = Fixture(identity.copy(privateKey = identity.privateKey.copyOf(31)))
        val client = fixture.throughM4()
        assertError(5, fixture.setup.handle(fixture.m5(client)))
        assertEquals(1, fixture.saved.size)
        assertArrayEquals(fixture.controller.publicKey, fixture.store.get(fixture.id.toString(Charsets.UTF_8)))
        assertFalse(fixture.setup.complete)
    }

    @Test fun identityPublicBytesArePassedThroughWithoutNewLengthOrConsistencyChecks() {
        val identity = AirPlayIdentity.generate()
        for (public in listOf(byteArrayOf(), identity.publicKey.copyOf(31), ByteArray(32), identity.publicKey + byteArrayOf(7))) {
            val fixture = Fixture(identity.copy(publicKey = public, privateKey = identity.privateKey + byteArrayOf(9)))
            val client = fixture.throughM4()
            fixture.checkM6(client, fixture.setup.handle(fixture.m5(client)), verifyKey = identity.publicKey)
            assertEquals(1, fixture.saved.size)
        }
    }

    @Test fun saveCanReadCompleteAndCloseTheOwnerReentrantly() {
        lateinit var fixture: Fixture
        fixture = Fixture(onSave = { _, _ ->
            assertFalse(fixture.setup.complete)
            fixture.setup.close()
            assertFalse(fixture.setup.complete)
        })
        val client = fixture.throughM4()
        val response = Tlv8Codec.decode(fixture.setup.handle(fixture.m5(client)))
        assertArrayEquals(byteArrayOf(6), response[6])
        val key = derive(client.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val sub = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonceLabel("PS-Msg06"), response[5]!!))
        assertArrayEquals(fixture.identity.publicKey, sub[3])
        assertFalse(fixture.setup.complete)
        assertArrayEquals(fixture.controller.publicKey, fixture.store.get(fixture.id.toString(Charsets.UTF_8)))
        assertThrows(IllegalStateException::class.java) { fixture.setup.handle(message(1)) }
    }

    @Test fun closingAnUnusedOwnerDoesNotStartAuthenticationAndRejectsLaterRequests() {
        val setup = PairSetup(AirPlayIdentity.generate(), PairingStore())
        setup.close(); setup.close()
        assertFalse(setup.complete)
        assertThrows(IllegalStateException::class.java) { setup.handle(message(1)) }
    }

    companion object {
        private fun message(state: Int, vararg items: Tlv8Item) =
            Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(state.toByte()))) + items)
        private fun derive(key: ByteArray, salt: String, info: String) =
            AirPlayCrypto.hkdfSha512(key, salt.toByteArray(Charsets.UTF_8), info.toByteArray(Charsets.UTF_8))
        private fun sealedM5(client: PairSetupSrpClient, sub: ByteArray): ByteArray {
            val key = derive(client.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
            return message(5, Tlv8Item(5, AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonceLabel("PS-Msg05"), sub)))
        }
        private fun assertError(state: Int, response: ByteArray) = assertArrayEquals(
            byteArrayOf(6, 1, state.toByte(), 7, 1, 2), response)
    }
}
