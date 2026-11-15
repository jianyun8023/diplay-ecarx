package com.shihab.diplay.media

import java.io.Closeable

/** Encodes 20 ms chunks of 48 kHz mono PCM into raw Opus access units for the CarPlay microphone uplink. */
internal interface MicrophoneOpusEncoder : Closeable {
    /** Short implementation name for diagnostic reports. */
    val implementation: String

    val available: Boolean

    /** Encodes one 20 ms little-endian 16-bit PCM frame and returns the Opus access units it produced. */
    fun encode(pcm: ByteArray): List<ByteArray>
}
