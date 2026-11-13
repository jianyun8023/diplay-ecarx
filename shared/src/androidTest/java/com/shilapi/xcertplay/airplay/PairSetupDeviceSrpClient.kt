package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import java.security.MessageDigest

/** Independent client-side SRP equations; does not call the server's verify/encoding helpers. */
internal class PairSetupDeviceSrpClient(
    salt: ByteArray,
    publicB: ByteArray,
    password: String = "3939",
    publicOffset: BigInteger = BigInteger.ZERO,
) {
    val publicA: ByteArray
    val proof: ByteArray
    val key: ByteArray
    val serverProof: ByteArray

    init {
        val n = modulus
        val g = BigInteger.valueOf(5)
        val a = BigInteger(1, ByteArray(32) { 0x33 })
        val aPublic = g.modPow(a, n) + publicOffset
        val bPublic = BigInteger(1, publicB)
        val x = BigInteger(1, hash(salt, hash("Pair-Setup:$password".toByteArray(Charsets.UTF_8))))
        val k = BigInteger(1, hash(unsigned(n), padded(g)))
        publicA = padded(aPublic)
        val u = BigInteger(1, hash(publicA, padded(bPublic)))
        val s = (bPublic - k * g.modPow(x, n)).mod(n).modPow(a + u * x, n)
        key = hash(unsigned(s))
        val hashN = hash(unsigned(n))
        val hashG = hash(unsigned(g))
        val xor = ByteArray(hashN.size) { (hashN[it].toInt() xor hashG[it].toInt()).toByte() }
        proof = hash(xor, hash("Pair-Setup".toByteArray(Charsets.UTF_8)), salt, publicA, padded(bPublic), key)
        serverProof = hash(publicA, proof, key)
    }

    companion object {
        val modulus = BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16)
        fun hash(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").run {
            parts.forEach { update(it) }; digest()
        }
        fun unsigned(value: BigInteger): ByteArray {
            val raw = value.toByteArray()
            return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        }
        fun padded(value: BigInteger): ByteArray {
            val raw = unsigned(value)
            return ByteArray(maxOf(384, raw.size)).also { raw.copyInto(it, it.size - raw.size) }
        }
    }
}
