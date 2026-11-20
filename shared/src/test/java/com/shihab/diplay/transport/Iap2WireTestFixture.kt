package com.shihab.diplay.transport

/** An iAP2 link packet: 9-byte header (checksum-filled), payload, then the payload checksum. */
internal fun iap2Packet(
    control: Int,
    sequence: Int,
    acknowledgement: Int,
    sessionId: Int,
    payload: ByteArray,
): ByteArray {
    val length = 10 + payload.size
    val header = byteArrayOf(
        0xff.toByte(), 0x5a, (length ushr 8).toByte(), length.toByte(),
        control.toByte(), sequence.toByte(), acknowledgement.toByte(), sessionId.toByte(), 0,
    )
    header[8] = iap2Checksum(header.copyOf(8)).toByte()
    return header + payload + iap2Checksum(payload).toByte()
}

internal fun iap2Checksum(bytes: ByteArray): Int =
    (-bytes.sumOf { it.toInt() and 0xff }) and 0xff
