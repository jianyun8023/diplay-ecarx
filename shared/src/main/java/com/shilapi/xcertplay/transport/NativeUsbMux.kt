package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.airplay.NativeMedia

/** Native protocol values only. Bootstrap-array results also cross host-test classloaders safely. */
internal object NativeUsbMux {
    init { check(NativeMedia.available) { "Native protocol core is unavailable" } }
    @JvmStatic external fun framerCreate(): Long
    @JvmStatic external fun framerAppend(handle: Long, bytes: ByteArray)
    /** [intArray(kind, protocol/invalidLength, length, word8, sequence), payload?, diagnostic?]. */
    @JvmStatic external fun framerRead(handle: Long): Array<Any?>?
    @JvmStatic external fun framerBuffered(handle: Long): Int
    @JvmStatic external fun framerDestroy(handle: Long)
    @JvmStatic external fun hostCreate(): Long
    @JvmStatic external fun version(): ByteArray
    @JvmStatic external fun handshake(handle: Long, protocol: Int, length: Int, word8: Int): Int
    @JvmStatic external fun muxFrame(handle: Long, tcp: ByteArray?): ByteArray
    @JvmStatic external fun muxSent(handle: Long)
    @JvmStatic external fun muxReceived(handle: Long, sequence: Int)
    @JvmStatic external fun sourcePort(handle: Long, active: IntArray): Int
    @JvmStatic external fun hostDestroy(handle: Long)
    /** [intArray(destination, flags, sequence, ack, window), payload]. */
    @JvmStatic external fun parseTcp(protocol: Int, frame: ByteArray): Array<Any?>?
    @JvmStatic external fun encodeTcp(source: Int, destination: Int, sequence: Int, ack: Int, flags: Int, payload: ByteArray): ByteArray
    @JvmStatic external fun tcpCreate(source: Int, destination: Int): Long
    @JvmStatic external fun tcpStatus(handle: Long, retired: ByteArray): Int
    /** [intArray(chunkBytes, source, destination, sequence, ack), complete TCP segment], with chunk selection owned by Rust. */
    @JvmStatic external fun tcpPrepare(handle: Long, bytes: ByteArray, offset: Int): Array<Any?>
    @JvmStatic external fun tcpSent(handle: Long, bytes: Int)
    @JvmStatic external fun tcpControl(handle: Long, retired: ByteArray, command: Int): ByteArray
    @JvmStatic external fun tcpPacket(handle: Long, retired: ByteArray, flags: Int, sequence: Int, payload: ByteArray): Int
    @JvmStatic external fun tcpAckSent(handle: Long): Int
    @JvmStatic external fun tcpRecv(handle: Long, retired: ByteArray, maxBytes: Int): ByteArray?
    @JvmStatic external fun tcpClosePlan(handle: Long, abort: Boolean): Int
    @JvmStatic external fun tcpHostClose(handle: Long, failed: Boolean): Boolean
    /** Destroys the heap owner and returns its opaque terminal queue; Rust alone advances its cursor. */
    @JvmStatic external fun tcpFreeze(handle: Long): ByteArray
}
