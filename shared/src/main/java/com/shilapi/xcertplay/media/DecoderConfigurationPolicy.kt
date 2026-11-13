package com.shilapi.xcertplay.media

/** One stream's configure/degradation owner; SDK objects and actual codec calls stay on its worker. */
internal class DecoderConfigurationPolicy(rate: Int, mtk: Boolean, vendorRefused: Boolean) {
    private val owner = NativeVideoPolicy.configurationCreate(rate, mtk, vendorRefused)
    private fun value(field: Int, capability: Boolean = false): Long = NativeVideoPolicy.configurationQuery(owner, field, capability)
    private fun event(kind: Int, a: Int = 0, b: Int = 0, c: Int = 0, flags: Int = 0): Int =
        NativeVideoPolicy.configurationEvent(owner, kind, a, b, c, flags)
    val operatingRate: Int get() = value(0).toInt()
    val configuredRate: Int get() = value(1).toInt()
    val maximumMtkMode: MtkDecoderMode get() = MtkDecoderMode.entries[value(2).toInt()]
    val configuredMtkMode: MtkDecoderMode get() = MtkDecoderMode.entries[value(3).toInt()]
    val configuredMtkCodec2LowLatency: Boolean get() = value(4) != 0L
    val configuredVendorLowLatency: Boolean get() = value(5) != 0L
    val configuredPrivateParameters: Boolean get() = value(6) != 0L
    val vendorLowLatencyRefused: Boolean get() = value(7) != 0L
    val lowLatencyDecoderRefused: Boolean get() = value(8) != 0L
    val startedLowLatency: Boolean get() = value(9) != 0L
    val startedPictureOrder: Boolean get() = value(10) != 0L
    var hardwareCreateFailed: Boolean
        get() = value(11) != 0L
        set(value) { event(7, if (value) 1 else 0) }
    val probeIntervalMillis: Long get() = value(12)
    val tryMtkCodec2: Boolean get() = value(100) != 0L
    val tryLowLatency: Boolean get() = value(101) != 0L
    fun requestedMtkMode(legacyAvailable: Boolean): MtkDecoderMode = MtkDecoderMode.entries[value(102, legacyAvailable).toInt()]
    fun begin() { event(0) }
    fun released() { event(13) }
    fun resetQueuedFrames() { event(14) }
    fun inputQueued() { event(15) }
    fun keepsUnusedDecoder(requested: Boolean, hasCodec: Boolean): Boolean =
        event(16, flags = (if (requested) 1 else 0) or (if (hasCodec) 2 else 0)) != 0
    fun finish(rate: Int, mode: MtkDecoderMode, requested: MtkDecoderMode, configured: Boolean, codec2: Boolean, legacy: Boolean): Boolean =
        event(1, rate, mode.ordinal, requested.ordinal, (if (configured) 1 else 0) or (if (codec2) 2 else 0) or (if (legacy) 4 else 0)) != 0
    fun declineVendor(force: Boolean = false): Boolean = event(2, if (force) 1 else 0) != 0
    fun dropRate(): Int = event(3)
    /** Negative means no active mode; otherwise low two bits are old mode and next two bits its fallback. */
    fun dropMtk(): Int = event(4)
    fun dropMtkCodec2(): Boolean = event(5) != 0
    fun refuseLowLatency() { event(6) }
    fun acceptMtkFallback() { event(11) }
    fun dropsRateAfterFailure(codecFailure: Boolean, rendered: Boolean): Boolean =
        event(12, flags = (if (codecFailure) 1 else 0) or (if (rendered) 2 else 0)) != 0
    fun noteParameters(vendor: Boolean, privateParameters: Boolean) {
        event(8, flags = (if (vendor) 1 else 0) or (if (privateParameters) 2 else 0))
    }
    fun noteFeatures(lowLatency: Boolean, pictureOrder: Boolean) {
        event(10, flags = (if (lowLatency) 1 else 0) or (if (pictureOrder) 2 else 0))
    }
    fun resetProbeInterval() { event(9) }
    fun advanceProbeInterval() { event(9, 1) }
    fun probesHardware(hardware: Boolean, software: Boolean, preferred: Boolean, sameHardware: Boolean): Boolean =
        NativeVideoPolicy.softwareOutcome(owner, hardware, software, preferred, sameHardware) != 0
    fun allows(attempt: DecoderAttempt, software: Boolean, sdk: Int): Boolean =
        NativeVideoPolicy.attemptAllowed(owner, attempt.codecName == null, software, sdk)
    fun formatFlags(tuned: Boolean, atOnce: Boolean, vendor: Boolean, sdk: Int, advertised: Boolean, pictureListed: Boolean, nameFlags: Int): Int =
        NativeVideoPolicy.formatFlags(owner, tuned, atOnce, vendor, sdk, advertised, pictureListed, nameFlags)
}
