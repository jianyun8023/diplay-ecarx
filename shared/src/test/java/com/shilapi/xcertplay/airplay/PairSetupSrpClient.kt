package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Properties

/** Independent client-side SRP equations; does not call the server's verify/encoding helpers. */
internal class PairSetupSrpClient(
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
        val n = BigInteger(1, golden.getProperty("n").bytesFromHex())
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
        val golden: Properties = Properties().apply {
            PairSetupSrpClient::class.java.getResourceAsStream("/pairsetup-srp-golden.properties")!!.use { load(it) }
        }
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

internal fun String.bytesFromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
