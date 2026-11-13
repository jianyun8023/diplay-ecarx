package com.shihab.diplay.media

/** True when any 16-bit little-endian sample in the range reaches [peak]. */
internal fun isAudiblePcm16(data: ByteArray, offset: Int, length: Int, peak: Int): Boolean {
    var index = offset
    val end = offset + length - 1
    while (index < end) {
        val sample = (data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)
        if (sample >= peak || sample <= -peak) return true
        index += 2
    }
    return false
}
