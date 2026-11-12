package com.shilapi.xcertplay.iap2.wire

import com.shilapi.xcertplay.airplay.NativeMedia

/** Rust owns scalar and list layout; Android retains its established UTF-8 replacement behavior. */
object Iap2WireCodec {
    fun void(): ByteArray = ByteArray(0)
    fun u8(value: Int): ByteArray = NativeIap2Wire.encodeScalar(NativeIap2Wire.U8, value.toLong())
    fun i8(value: Int): ByteArray = NativeIap2Wire.encodeScalar(NativeIap2Wire.I8, value.toLong())
    fun bool(value: Boolean): ByteArray = u8(if (value) 1 else 0)
    fun u16(value: Int): ByteArray = NativeIap2Wire.encodeScalar(NativeIap2Wire.U16, value.toLong())
    fun i16(value: Int): ByteArray = NativeIap2Wire.encodeScalar(NativeIap2Wire.I16, value.toLong())
    fun u32(value: Long): ByteArray = NativeIap2Wire.encodeScalar(NativeIap2Wire.U32, value)
    fun u32(value: Int): ByteArray = u32(value.toLong())
    fun i32(value: Int): ByteArray = NativeIap2Wire.encodeScalar(NativeIap2Wire.I32, value.toLong())
    fun u64(value: Long): ByteArray = NativeIap2Wire.encodeScalar(NativeIap2Wire.U64, value)
    fun bytes(value: ByteArray): ByteArray = value.copyOf()

    fun string(value: String): ByteArray = NativeIap2Wire.joinStrings(arrayOf(platformString(value)))
    fun strings(values: Iterable<String>): ByteArray = NativeIap2Wire.joinStrings(values.map(::platformString).toTypedArray())
    fun u16List(values: Iterable<Int>): ByteArray = NativeIap2Wire.encodeU16List(values.toList().toIntArray())

    private fun platformString(value: String): ByteArray {
        require('\u0000' !in value) { "NUL-terminated strings must not contain U+0000" }
        return value.encodeToByteArray()
    }

    fun readU8(bytes: ByteArray, offset: Int = 0): Int = NativeIap2Wire.readScalar(NativeIap2Wire.U8, bytes, offset, -1).toInt()
    fun readI8(bytes: ByteArray, offset: Int = 0): Int = NativeIap2Wire.readScalar(NativeIap2Wire.I8, bytes, offset, -1).toInt()
    fun readBool(bytes: ByteArray, offset: Int = 0, nonzeroIsTrue: Boolean = true): Boolean =
        NativeIap2Wire.readScalar(if (nonzeroIsTrue) NativeIap2Wire.BOOL_NONZERO else NativeIap2Wire.BOOL_ONE, bytes, offset, -1) != 0L
    fun readU16(bytes: ByteArray, offset: Int = 0): Int = NativeIap2Wire.readScalar(NativeIap2Wire.U16, bytes, offset, -1).toInt()
    fun readI16(bytes: ByteArray, offset: Int = 0): Int = NativeIap2Wire.readScalar(NativeIap2Wire.I16, bytes, offset, -1).toInt()
    fun readU32(bytes: ByteArray, offset: Int = 0): Long = NativeIap2Wire.readScalar(NativeIap2Wire.U32, bytes, offset, -1)
    fun readI32(bytes: ByteArray, offset: Int = 0): Int = NativeIap2Wire.readScalar(NativeIap2Wire.I32, bytes, offset, -1).toInt()
    fun readU64(bytes: ByteArray, offset: Int = 0): Long = NativeIap2Wire.readScalar(NativeIap2Wire.U64, bytes, offset, -1)
    fun readBytes(payload: ByteArray): ByteArray = payload.copyOf()

    fun readString(payload: ByteArray): String {
        val range = NativeIap2Wire.stringRanges(payload, false)
        return payload.decodeToString(range[0], range[1])
    }

    fun readStrings(payload: ByteArray): List<String> {
        val ranges = NativeIap2Wire.stringRanges(payload, true)
        return (ranges.indices step 2).map { payload.decodeToString(ranges[it], ranges[it + 1]) }
    }

    fun readU16List(payload: ByteArray): List<Int> = NativeIap2Wire.readU16List(payload).toList()
}

internal object NativeIap2Wire {
    const val U8 = 0
    const val I8 = 1
    const val U16 = 2
    const val I16 = 3
    const val U32 = 4
    const val I32 = 5
    const val U64 = 6
    const val BOOL_NONZERO = 7
    const val BOOL_ONE = 8
    init { check(NativeMedia.available) { "Rust core is unavailable: ${NativeMedia.loadError}" } }
    @JvmStatic external fun encodeScalar(kind: Int, value: Long): ByteArray
    @JvmStatic external fun readScalar(kind: Int, bytes: ByteArray, offset: Int, fieldId: Int): Long
    @JvmStatic external fun encodeU16List(values: IntArray): ByteArray
    @JvmStatic external fun readU16List(bytes: ByteArray): IntArray
    @JvmStatic external fun joinStrings(values: Array<ByteArray>): ByteArray
    @JvmStatic external fun stringRanges(bytes: ByteArray, multiple: Boolean): IntArray
    @JvmStatic external fun requireVoid(id: Int, bytes: ByteArray)
}
