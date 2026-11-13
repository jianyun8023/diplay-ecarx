package com.shihab.diplay.airplay

/**
 * RTSP/HTTP-style request framing for the CarPlay control channel.
 *
 * The control connection on TCP :7000 speaks a text request line plus headers plus an optional
 * body, using the same framing RTSP and HTTP share. Encryption, once pair-verify completes, wraps
 * this framing in a separate layer.
 */
object RtspMessage {
    data class Request(
        val method: String,
        val path: String,
        val protocol: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    data class Response(
        val protocol: String? = null,
        val status: Int? = null,
        val statusText: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    data class Parsed(val messages: List<Request>, val rest: ByteArray)

    init { NativeMedia.requireAvailable() }

    fun parseMessages(buffer: ByteArray): Parsed = NativeRtsp.parse(buffer)

    fun buildResponse(request: Request, response: Response): ByteArray =
        NativeRtsp.response(response.protocol ?: request.protocol, response.status ?: 200,
            response.statusText, request.headers["cseq"],
            response.headers.flatMap { listOf(it.key, it.value) }.toTypedArray(), response.body)
}
