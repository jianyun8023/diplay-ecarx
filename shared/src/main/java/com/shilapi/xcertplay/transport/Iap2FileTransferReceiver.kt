package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.airplay.NativeMedia

/** A completed iAP2 v2 NowPlayingArtworkData transfer. */
data class Iap2ArtworkTransfer(val id: Int, val bytes: ByteArray)

/** Rust owns the v2 transfer state; GC-managed payload buffers have no native handle lifetime. */
class Iap2FileTransferReceiver(private val maximumArtworkBytes: Int = DEFAULT_MAXIMUM_ARTWORK_BYTES) {
    data class Outcome(val replies: List<ByteArray> = emptyList(), val completed: Iap2ArtworkTransfer? = null)

    init { require(maximumArtworkBytes > 0) }
    private var state: Array<Any?>? = null

    @Synchronized fun accept(datagram: ByteArray): Outcome {
        // Initialization happens on the link worker, whose failure path owns the input stream.
        val owner = state ?: NativeIap2FileTransfer.create(maximumArtworkBytes).also { state = it }
        val result = NativeIap2FileTransfer.accept(owner, datagram)
        @Suppress("UNCHECKED_CAST")
        val replies = (result[0] as Array<ByteArray>).toList()
        val id = (result[1] as IntArray)[0]
        return Outcome(replies, if (id < 0) null else Iap2ArtworkTransfer(id, result[2] as ByteArray))
    }

    /** Drops pending buffers and permits a fresh transfer, including after a link terminates. */
    @Synchronized fun clear() { state?.let(NativeIap2FileTransfer::clear) }

    companion object { const val DEFAULT_MAXIMUM_ARTWORK_BYTES = 2 * 1024 * 1024 }
}

internal object NativeIap2FileTransfer {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun create(maximum: Int): Array<Any?>
    @JvmStatic external fun accept(state: Array<Any?>, datagram: ByteArray): Array<Any?>
    @JvmStatic external fun clear(state: Array<Any?>)
}
