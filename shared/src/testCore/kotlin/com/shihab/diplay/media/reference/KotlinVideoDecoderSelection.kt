// Independent pre-migration decoder policy from 6cd98d70; test sources only.
package com.shihab.diplay.media.reference

/**
 * Android's own software decoders. API 28 has no MediaCodecInfo.isSoftwareOnly, so they are known by
 * name there; later releases also report the flag.
 */
internal fun isSoftwareDecoderName(name: String): Boolean =
    SOFTWARE_DECODER_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

private val SOFTWARE_DECODER_PREFIXES = listOf("OMX.google.", "c2.android.")

/** One installed decoder of the stream's type, in MediaCodecList order. */
internal data class KotlinInstalledDecoder(val name: String, val software: Boolean, val alias: Boolean = false)

/**
 * The decoder MediaCodec.createDecoderByType would pick when the hardware is free: the first hardware decoder
 * that is not an alias. Creating it by name keeps a busy hardware decoder from quietly turning into Android's
 * software one: on the ECARX 753 (API 28) the Qualcomm HEVC decoder answered InsufficientResources and
 * createDecoderByType went on to OMX.google.hevc.decoder (car logs 2026-11-09 22:09:38, 2026-11-10 22:24:52).
 */
internal fun defaultHardwareDecoder(decoders: List<KotlinInstalledDecoder>): String? =
    decoders.firstOrNull { !it.software && !it.alias }?.name

/** The software decoder to fall back to, explicitly and reported, once the hardware one stays unavailable. */
internal fun softwareFallbackDecoder(decoders: List<KotlinInstalledDecoder>): String? =
    decoders.firstOrNull { it.software && !it.alias }?.name

/**
 * Creates with [create], retrying after each of [delaysMillis]: Qualcomm's driver frees a released decoder's
 * session asynchronously, so a decoder created right after another was released can be refused for a moment.
 */
internal fun <T> createWithRetries(
    delaysMillis: List<Long>,
    sleep: (Long) -> Unit,
    onRetry: (attempt: Int, failure: Exception) -> Unit = { _, _ -> },
    create: () -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return create()
        } catch (failure: Exception) {
            if (attempt >= delaysMillis.size) throw failure
            onRetry(attempt + 1, failure)
            sleep(delaysMillis[attempt++])
        }
    }
}

/**
 * While a stream decodes in software because the hardware was busy, the worker checks every [intervalMillis]
 * whether the hardware decoder can be created again. Each check that fails doubles the wait, up to
 * [MAX_INTERVAL_MILLIS].
 */
internal data class KotlinHardwareDecoderProbe(val name: String, val dueNanos: Long, val intervalMillis: Long) {
    fun failed(nowNanos: Long): KotlinHardwareDecoderProbe {
        val next = (intervalMillis * 2).coerceAtMost(MAX_INTERVAL_MILLIS)
        return copy(dueNanos = nowNanos + next * 1_000_000L, intervalMillis = next)
    }

    companion object {
        const val FIRST_INTERVAL_MILLIS = 2_000L
        const val MAX_INTERVAL_MILLIS = 30_000L

        fun start(name: String, nowNanos: Long, intervalMillis: Long = FIRST_INTERVAL_MILLIS) =
            KotlinHardwareDecoderProbe(name, nowNanos + intervalMillis * 1_000_000L, intervalMillis)
    }
}
