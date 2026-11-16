package com.shihab.diplay.transport

import java.io.IOException

/** Transport facts survive wrapping and localization; messages are only for presentation. */
enum class TransportFailureKind {
    OTHER, IAP_TUNNEL, BLUETOOTH_OFF;

    companion object {
        fun from(error: Throwable): TransportFailureKind =
            generateSequence(error) { it.cause }.take(8)
                .filterIsInstance<TransportFailure>().firstOrNull()?.kind ?: OTHER
    }
}

class TransportFailure(val kind: TransportFailureKind, message: String, cause: Throwable? = null) :
    IOException(message, cause)
