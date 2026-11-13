package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia

/**
 * Android's own software decoders. API 28 has no MediaCodecInfo.isSoftwareOnly, so they are known by
 * name there; later releases also report the flag.
 */
internal fun isSoftwareDecoderName(name: String): Boolean =
    NativeVideoPolicy.softwareName(name.map { it.uppercaseChar().lowercaseChar() }.joinToString(""))

/** One installed decoder of the stream's type, in MediaCodecList order. */
internal data class InstalledDecoder(val name: String, val software: Boolean, val alias: Boolean = false)

/**
 * The decoder MediaCodec.createDecoderByType would pick when the hardware is free: the first hardware decoder
 * that is not an alias. Creating it by name keeps a busy hardware decoder from quietly turning into Android's
 * software one: on the ECARX 753 (API 28) the Qualcomm HEVC decoder answered InsufficientResources and
 * createDecoderByType went on to OMX.google.hevc.decoder (car logs 2026-11-09 22:09:38, 2026-11-10 22:24:52).
 */
internal fun defaultHardwareDecoder(decoders: List<InstalledDecoder>): String? =
    decoders.getOrNull(NativeVideoPolicy.select(decoderFlags(decoders), false))?.name

/** The software decoder to fall back to, explicitly and reported, once the hardware one stays unavailable. */
internal fun softwareFallbackDecoder(decoders: List<InstalledDecoder>): String? =
    decoders.getOrNull(NativeVideoPolicy.select(decoderFlags(decoders), true))?.name

internal fun preferredDefaultDecoder(decoders: List<InstalledDecoder>, software: Boolean, hardwareFailed: Boolean): String? =
    decoders.getOrNull(NativeVideoPolicy.defaultDecoder(decoderFlags(decoders), software, hardwareFailed))?.name

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
    var retry: LongArray? = null
    while (true) {
        try {
            return create()
        } catch (failure: Exception) {
            val state = retry ?: NativeVideoPolicy.retryCreate().also { retry = it }
            val attempt = NativeVideoPolicy.retryAttempt(state, delaysMillis.size)
            if (attempt == 0) throw failure
            onRetry(attempt, failure)
            sleep(delaysMillis[NativeVideoPolicy.retryCommit(state)])
        }
    }
}

/**
 * While a stream decodes in software because the hardware was busy, the worker checks every [intervalMillis]
 * whether the hardware decoder can be created again. Each check that fails doubles the wait, up to
 * [MAX_INTERVAL_MILLIS].
 */
internal data class HardwareDecoderProbe(val name: String, val dueNanos: Long, val intervalMillis: Long) {
    fun failed(nowNanos: Long): HardwareDecoderProbe {
        val next = NativeVideoPolicy.probe(nowNanos, intervalMillis, true)
        return copy(dueNanos = next[0], intervalMillis = next[1])
    }

    fun isDue(nowNanos: Long): Boolean = NativeVideoPolicy.probeDue(dueNanos, nowNanos)

    companion object {
        const val FIRST_INTERVAL_MILLIS = 2_000L
        const val MAX_INTERVAL_MILLIS = 30_000L

        fun start(name: String, nowNanos: Long, intervalMillis: Long = FIRST_INTERVAL_MILLIS): HardwareDecoderProbe {
            val plan = NativeVideoPolicy.probe(nowNanos, intervalMillis, false)
            return HardwareDecoderProbe(name, plan[0], plan[1])
        }
    }
}

private fun decoderFlags(decoders: List<InstalledDecoder>): IntArray =
    decoders.map { (if (it.software) 1 else 0) or (if (it.alias) 2 else 0) }.toIntArray()

/** Java's per-Char case fold retains API 28 codec-name matching; Rust owns the name policies. */
internal fun videoCodecNameFlags(name: String, advertised: Boolean = false): Int =
    NativeVideoPolicy.nameFlags(name.map { it.uppercaseChar().lowercaseChar() }.joinToString(""), advertised)

internal object NativeVideoPolicy {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun decoderAttempts(rate: Int, software: Boolean, lowLatency: Boolean, mtkMode: Int): IntArray
    @JvmStatic external fun nameFlags(folded: String, advertised: Boolean): Int
    @JvmStatic external fun mtkMode(mode: Int, op: Int): IntArray
    @JvmStatic external fun mtkSelect(flags: IntArray, mode: Int): Int
    @JvmStatic external fun appliedMtkMode(mode: Int, legacy: Boolean): Int
    @JvmStatic external fun operatingRate(type: Int, labelled: Boolean, frameRate: Int): Int
    @JvmStatic external fun nextOperatingRate(requested: Int, used: Boolean, usedRate: Int): Int
    @JvmStatic external fun keepLowLatency(requested: Boolean, used: Boolean, same: Boolean): Boolean
    @JvmStatic external fun lowLatency(flags: IntArray): Int
    @JvmStatic external fun workerWait(drains: Boolean, last: Long, now: Long): Long
    @JvmStatic external fun prefersSoftware(sdk: Int, hevc: Boolean, requested: Boolean): Boolean
    @JvmStatic external fun defaultDecoder(flags: IntArray, software: Boolean, hardwareFailed: Boolean): Int
    @JvmStatic external fun firstSoftware(flags: IntArray): Int
    @JvmStatic external fun softwareOutcome(owner: LongArray, hardware: Boolean, software: Boolean, preferred: Boolean, sameHardware: Boolean): Int
    @JvmStatic external fun configurationCreate(rate: Int, mtk: Boolean, vendorRefused: Boolean): LongArray
    @JvmStatic external fun configurationQuery(owner: LongArray, field: Int, capability: Boolean): Long
    @JvmStatic external fun configurationEvent(owner: LongArray, event: Int, a: Int, b: Int, c: Int, flags: Int): Int
    @JvmStatic external fun attemptAllowed(owner: LongArray, defaultDecoder: Boolean, software: Boolean, sdk: Int): Boolean
    @JvmStatic external fun formatFlags(owner: LongArray, tuned: Boolean, atOnce: Boolean, vendorRequested: Boolean, sdk: Int, advertised: Boolean, pictureListed: Boolean, nameFlags: Int): Int
    @JvmStatic external fun softwareName(folded: String): Boolean
    @JvmStatic external fun select(flags: IntArray, software: Boolean): Int
    @JvmStatic external fun probe(nowNanos: Long, intervalMillis: Long, failed: Boolean): LongArray
    @JvmStatic external fun probeDue(dueNanos: Long, nowNanos: Long): Boolean
    @JvmStatic external fun retryCreate(): LongArray
    @JvmStatic external fun retryAttempt(token: LongArray, delayCount: Int): Int
    @JvmStatic external fun retryCommit(token: LongArray): Int
}
