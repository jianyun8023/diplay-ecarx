package com.shihab.diplay.mfi

import com.shihab.diplay.iap2.message.Iap2AuthenticationMessages
import com.shihab.diplay.iap2.session.Iap2Session
import com.shihab.diplay.iap2.wire.Iap2Frame
import com.shihab.diplay.transport.Iap2HandshakeCore
import com.shihab.diplay.transport.Iap2HandshakeDeadline

/** Runs LIVI's minimal iAP2 CSM MFi exchange without taking ownership of [Iap2Session]. */
class Iap2MfiAuthenticationClient(
    private val authentication: MfiAuthenticator,
    private val maximumCertificateLength: Int = MfiAuthenticationClient.DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH,
) {
    init {
        require(maximumCertificateLength in 1..MAX_CSM_CERTIFICATE_BYTES) {
            "maximumCertificateLength must be in 1..$MAX_CSM_CERTIFICATE_BYTES"
        }
    }

    /** Blocks until the phone confirms AA05, or throws a typed authentication failure. */
    fun run(
        session: Iap2Session,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onProgress: (String) -> Unit = {},
    ) {
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS"
        }
        val deadlineNanos = Iap2HandshakeDeadline(1, timeoutMillis)
        val certificate = authentication.readCertificate(maximumCertificateLength)
        val certificateFrame = when (authentication.certificateType) {
            MfiCertificateType.MFI -> Iap2AuthenticationMessages.accessoryCertificate(certificate)
            MfiCertificateType.BAA -> Iap2AuthenticationMessages.accessoryCertificateBody(certificate)
        }
        val certificatePayloadBytes = certificateFrame.payload.size
        val core = Iap2HandshakeCore(1)
        onProgress(
            "mfi type=${authentication.certificateType} " +
                "certificate loaded bytes=${certificate.size} " +
                "payload bytes=$certificatePayloadBytes",
        )
        while (true) {
            val remaining = remainingMillis(deadlineNanos)
            if (remaining == 0L) throw Iap2MfiAuthenticationException("Timed out waiting for iAP2 MFi authentication", retryable = true)
            val frame = session.recv(remaining)
                ?: throw Iap2MfiAuthenticationException("Timed out waiting for iAP2 MFi authentication", retryable = true)
            when (core.message(frame.messageId)) {
                Iap2HandshakeCore.Action.MFI_CERTIFICATE -> {
                    onProgress("iap2 mfi rx=0xaa00 request-certificate")
                    send(session, certificateFrame, deadlineNanos)
                    onProgress("iap2 mfi tx=0xaa01 certificate bytes=$certificatePayloadBytes")
                }
                Iap2HandshakeCore.Action.MFI_CHALLENGE -> {
                    val challenge = requireParameterZero(frame)
                    if (!Iap2HandshakeCore.challengeValid(challenge.size)) {
                        throw Iap2MfiAuthenticationException("Invalid iAP2 MFi challenge length ${challenge.size}")
                    }
                    onProgress("iap2 mfi rx=0xaa02 challenge bytes=${challenge.size}")
                    val signature = authentication.signChallenge(challenge)
                    send(session, Iap2AuthenticationMessages.response(signature), deadlineNanos)
                    onProgress("iap2 mfi tx=0xaa03 signature bytes=${signature.size}")
                }
                Iap2HandshakeCore.Action.MFI_ACCEPTED -> {
                    onProgress("iap2 mfi rx=0xaa05 authentication-succeeded")
                    return
                }
                Iap2HandshakeCore.Action.MFI_REJECTED -> throw Iap2MfiAuthenticationException("iPhone sent AuthenticationFailed")
                else -> throw Iap2MfiAuthenticationException(
                    "Unexpected iAP2 MFi message 0x${frame.messageId.toString(16).padStart(4, '0')}",
                )
            }
        }
    }

    private fun send(
        session: Iap2Session,
        frame: Iap2Frame,
        deadlineNanos: Iap2HandshakeDeadline,
    ) {
        val remaining = remainingMillis(deadlineNanos)
        if (remaining == 0L) throw Iap2MfiAuthenticationException("Timed out sending iAP2 MFi authentication reply", retryable = true)
        session.send(frame, remaining)
    }

    private fun requireParameterZero(frame: Iap2Frame): ByteArray {
        return try {
            Iap2AuthenticationMessages.challenge(frame)
        } catch (failure: Exception) {
            throw Iap2MfiAuthenticationException("iAP2 MFi challenge is missing parameter 0", failure)
        }
    }

    private fun remainingMillis(deadlineNanos: Iap2HandshakeDeadline): Long = deadlineNanos.remaining()

    companion object {
        private const val MAX_CSM_CERTIFICATE_BYTES = 65_525
        private const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        private const val MAX_TIMEOUT_MILLIS = 5 * 60 * 1_000L
    }
}

/** A malformed, rejected, unexpected, or timed-out iAP2 MFi authentication exchange. */
class Iap2MfiAuthenticationException(
    message: String,
    cause: Throwable? = null,
    /** Only a silent/closed transport is recoverable; a phone's rejection is not. */
    val retryable: Boolean = false,
) : MfiException(message, cause)
