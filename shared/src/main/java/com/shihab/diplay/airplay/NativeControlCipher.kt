package com.shihab.diplay.airplay

/** ControlCipher serializes access and destroys its sole native owner on close. */
internal object NativeControlCipher {
    @JvmStatic external fun create(readKey: ByteArray, writeKey: ByteArray): Long
    @JvmStatic external fun encrypt(handle: Long, plaintext: ByteArray): ByteArray
    @JvmStatic external fun decrypt(handle: Long, buffer: ByteArray): ControlCipher.Decrypted
    @JvmStatic external fun destroy(handle: Long)
}
