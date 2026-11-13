package com.shihab.diplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    init { NativeMedia.requireAvailable() }

    fun decode(bytes: ByteArray): Any? = NativeBplist.decode(bytes)
    fun encode(root: Any?): ByteArray = NativeBplist.encode(root)
}
