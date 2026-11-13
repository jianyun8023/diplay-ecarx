package com.shihab.diplay.orchestration

import java.io.IOException
import java.net.InetAddress

/** The exception chain on one line, up to five causes deep, each with the frame it was thrown from. */
internal fun describeThrowable(error: Throwable): String {
    val builder = StringBuilder()
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 5) {
        if (depth > 0) builder.append(" <- ")
        builder.append(current.javaClass.simpleName)
        current.message?.let { builder.append(": ").append(it) }
        current.stackTrace.firstOrNull()?.let { frame ->
            builder.append(" @ ").append(frame.className.substringAfterLast('.'))
                .append('.').append(frame.methodName)
                .append('(').append(frame.fileName).append(':').append(frame.lineNumber).append(')')
        }
        current = current.cause
        depth++
    }
    return builder.toString()
}

/** The hotspot host address without its scope id; the address is mandatory for the tunnel. */
internal fun hostAddressText(address: InetAddress): String {
    val text = address.hostAddress?.substringBefore('%')
    if (text.isNullOrBlank()) {
        throw IOException("LocalOnlyHotspot host address is unavailable")
    }
    return text
}

internal fun ByteArray.macString(): String =
    joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

/** Whole milliseconds since a `System.nanoTime()` reading, never negative. */
internal fun elapsedMillis(startedNanos: Long): Long =
    ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0)
