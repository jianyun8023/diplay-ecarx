@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.shilapi.xcertplay.airplay

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Independent BC 1.79 test oracle; this source is never packaged in the application. */
internal object BouncyCryptoOracle {
    fun x25519Shared(privateKey: ByteArray, publicKey: ByteArray): ByteArray = ByteArray(32).also {
        X25519PrivateKeyParameters(privateKey, 0).generateSecret(X25519PublicKeyParameters(publicKey, 0), it, 0)
    }
    fun ed25519Sign(seed: ByteArray, data: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, Ed25519PrivateKeyParameters(seed, 0)); update(data, 0, data.size); generateSignature()
    }
    fun ed25519Verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(publicKey, 0)); update(data, 0, data.size); verifySignature(signature)
        }
    } catch (_: Exception) { false }
    fun hkdfSha512(input: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        HKDFBytesGenerator(SHA512Digest()).run {
            init(HKDFParameters(input, salt, info))
            ByteArray(length).also { generateBytes(it, 0, length) }
        }
    fun sha512(vararg parts: ByteArray): ByteArray = SHA512Digest().run {
        parts.forEach { update(it, 0, it.size) }
        ByteArray(digestSize).also { doFinal(it, 0) }
    }
    fun chachaSeal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray =
        ChaCha20Poly1305().run {
            init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
            ByteArray(getOutputSize(plain.size)).also {
                val count = processBytes(plain, 0, plain.size, it, 0); doFinal(it, count)
            }
        }
    fun chachaOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0),
        offset: Int = 0, length: Int = sealed.size - offset, aadOffset: Int = 0, aadLength: Int = aad.size - aadOffset): ByteArray =
        ChaCha20Poly1305().run {
            init(false, AEADParameters(KeyParameter(key), 128, nonce)); processAADBytes(aad, aadOffset, aadLength)
            val out = ByteArray(getOutputSize(length))
            val count = processBytes(sealed, offset, length, out, 0)
            val size = count + doFinal(out, count)
            if (size == out.size) out else out.copyOf(size)
        }
}
