package com.shihab.diplay.media

/** One stream's configure/degradation owner; SDK objects and actual codec calls stay on its worker. */
internal class DecoderConfigurationPolicy(rate: Int, mtk: Boolean, vendorRefused: Boolean) {
    private val owner = NativeVideoPolicy.configurationCreate(rate, mtk, vendorRefused)
    private fun value(field: Int, capability: Boolean = false): Long = NativeVideoPolicy.configurationQuery(owner, field, capability)
    private fun event(kind: Int, a: Int = 0, b: Int = 0, c: Int = 0, flags: Int = 0): Int =
        NativeVideoPolicy.configurationEvent(owner, kind, a, b, c, flags)
    val operatingRate: Int get() = value(0).toInt()
    val configuredRate: Int get() = value(1).toInt()
    val configuredMtkMode: MtkDecoderMode get() = MtkDecoderMode.entries[value(3).toInt()]
    val configuredMtkCodec2LowLatency: Boolean get() = value(4) != 0L
    val configuredVendorLowLatency: Boolean get() = value(5) != 0L
    val vendorLowLatencyRefused: Boolean get() = value(7) != 0L
    val startedLowLatency: Boolean get() = value(9) != 0L
    val startedPictureOrder: Boolean get() = value(10) != 0L
    var hardwareCreateFailed: Boolean
        get() = value(11) != 0L
        set(value) { event(7, if (value) 1 else 0) }
    val probeIntervalMillis: Long get() = value(12)
    val tryMtkCodec2: Boolean get() = value(100) != 0L
    val tryLowLatency: Boolean get() = value(101) != 0L
    fun requestedMtkMode(legacyAvailable: Boolean): MtkDecoderMode = MtkDecoderMode.entries[value(102, legacyAvailable).toInt()]
    fun begin(rate: Int, software: String?, lowLatency: String?, mode: MtkDecoderMode, sdk: Int) {
        val flags = (if (software != null) 1 else 0) or (if (lowLatency != null) 2 else 0) or
            (if (lowLatency?.let(::isSoftwareDecoderName) == true) 4 else 0) or
            (if (software?.let(::isSoftwareDecoderName) == true) 8 else 0)
        NativeVideoPolicy.configurationBegin(owner, rate, flags, mode.ordinal, sdk)
    }
    fun next(software: String?, lowLatency: String?): DecoderAttempt? {
        val values = NativeVideoPolicy.configurationNext(owner)
        if (values.isEmpty()) return null
        return DecoderAttempt(when (values[0]) { 1 -> lowLatency; 2 -> software; else -> null },
            values[1] != 0, values[2], MtkDecoderMode.entries[values[3]])
    }
    fun result(success: Boolean, sameLowLatency: Boolean): Int =
        NativeVideoPolicy.configurationResult(owner, success, sameLowLatency)
    val successfulRate: Int get() = value(22).toInt()
    fun released() { event(13) }
    fun finish(rate: Int, mode: MtkDecoderMode, requested: MtkDecoderMode, configured: Boolean, codec2: Boolean, legacy: Boolean): Boolean =
        event(1, rate, mode.ordinal, requested.ordinal, (if (configured) 1 else 0) or (if (codec2) 2 else 0) or (if (legacy) 4 else 0)) != 0
    fun declineVendor(): Boolean = event(2) != 0
    fun dropRate(): Int = event(3)
    /** Negative means no active mode; otherwise low two bits are old mode and next two bits its fallback. */
    fun dropMtk(): Int = event(4)
    fun dropMtkCodec2(): Boolean = event(5) != 0
    fun refuseLowLatency() { event(6) }
    fun acceptMtkFallback() { event(11) }
    fun dropsRateAfterFailure(codecFailure: Boolean): Boolean = event(12, flags = if (codecFailure) 1 else 0) != 0
    fun firstFrame() { event(17) }
    fun finishedAttempt() { event(18) }
    fun retryWithoutPrivateParameters(submitted: Boolean): Boolean = event(19, flags = if (submitted) 1 else 0) != 0
    fun noteParameters(vendor: Boolean, privateParameters: Boolean) {
        event(8, flags = (if (vendor) 1 else 0) or (if (privateParameters) 2 else 0))
    }
    fun noteFeatures(lowLatency: Boolean, pictureOrder: Boolean) {
        event(10, flags = (if (lowLatency) 1 else 0) or (if (pictureOrder) 2 else 0))
    }
    fun advanceProbeInterval() { event(9, 1) }
    fun probesHardware(hardware: Boolean, software: Boolean, preferred: Boolean, sameHardware: Boolean): Boolean =
        NativeVideoPolicy.softwareOutcome(owner, hardware, software, preferred, sameHardware) != 0
    fun formatFlags(tuned: Boolean, atOnce: Boolean, vendor: Boolean, sdk: Int, advertised: Boolean, pictureListed: Boolean, nameFlags: Int): Int =
        NativeVideoPolicy.formatFlags(owner, tuned, atOnce, vendor, sdk, advertised, pictureListed, nameFlags)
}
