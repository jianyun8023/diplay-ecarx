package com.shilapi.xcertplay

import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MfiIdentityStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Identity(val key: ByteArray, val certificate: ByteArray)

    // Fresh self-signed test material for each test. No stored accessory identity.
    private fun identity(): Identity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val name = X500Name("CN=DiPlay synthetic test only")
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger.ONE))
            setSignature(algorithm)
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(0)))
            setEndDate(Time(Date(4102444800000L)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
        }.generateTBSCertificate()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(tbs.encoded)
        val certificate = DERSequence(arrayOf<ASN1Encodable>(tbs, algorithm, DERBitString(signer.sign()))).encoded
        return Identity(pair.private.encoded, certificate)
    }

    private fun installedCertificate(root: File) =
        File(MfiIdentityStore.directory(root), MfiIdentityStore.CERTIFICATE_FILE).readBytes()

    @Test fun importAcceptsTheFilesInEitherOrder() {
        val root = temporary.newFolder()
        val identity = identity()
        assertFalse(MfiIdentityStore.installed(root))

        MfiIdentityStore.import(root, identity.certificate, identity.key)

        assertTrue(MfiIdentityStore.installed(root))
        assertArrayEquals(identity.certificate, installedCertificate(root))
        assertEquals(setOf("offline-mfi"), root.list()!!.toSet())
    }

    @Test fun importReplacesTheCurrentIdentity() {
        val root = temporary.newFolder()
        val first = identity()
        MfiIdentityStore.install(root, first.key, first.certificate)
        val second = identity()

        MfiIdentityStore.import(root, second.key, second.certificate)

        assertArrayEquals(second.certificate, installedCertificate(root))
        assertEquals(setOf("offline-mfi"), root.list()!!.toSet())
    }

    @Test fun mismatchedFilesLeaveTheCurrentIdentityInPlace() {
        val root = temporary.newFolder()
        val current = identity()
        MfiIdentityStore.install(root, current.key, current.certificate)

        try {
            MfiIdentityStore.import(root, identity().key, identity().certificate)
            fail("A key from another identity must be refused")
        } catch (_: Exception) {
        }

        assertArrayEquals(current.certificate, installedCertificate(root))
        assertEquals(setOf("offline-mfi"), root.list()!!.toSet())
    }

    @Test fun oversizedFilesAreRefusedBeforeTheyReachDisk() {
        try {
            MfiIdentityStore.readBounded(ByteArray(16 * 1024 + 1).inputStream())
            fail("An oversized identity file must be refused")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(3, MfiIdentityStore.readBounded(byteArrayOf(1, 2, 3).inputStream()).size)
    }
}
