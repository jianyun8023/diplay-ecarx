package com.shihab.diplay.media

internal fun videoDecoderAttempts(
    operatingRate: Int,
    softwareDecoder: String?,
    lowLatencyDecoder: String? = null,
    maximumMtkMode: MtkDecoderMode = MtkDecoderMode.NONE,
): List<DecoderAttempt> {
    val policy = DecoderConfigurationPolicy(operatingRate, mtk = true, vendorRefused = false)
    policy.begin(operatingRate, softwareDecoder, lowLatencyDecoder, maximumMtkMode, 29)
    val attempts = mutableListOf<DecoderAttempt>()
    while (true) {
        val attempt = policy.next(softwareDecoder, lowLatencyDecoder) ?: break
        attempts += attempt
        policy.result(false, attempt.codecName == lowLatencyDecoder)
    }
    return attempts
}

internal fun nextOperatingRate(requested: Int, used: DecoderAttempt?): Int {
    val policy = DecoderConfigurationPolicy(requested, mtk = false, vendorRefused = false)
    policy.begin(requested, null, null, MtkDecoderMode.NONE, 29)
    while (true) {
        val attempt = policy.next(null, null) ?: break
        val result = policy.result(attempt == used, true)
        if (attempt == used) return if (result and 1 != 0) 0 else requested
    }
    return requested
}

internal fun nextLowLatencyDecoder(requested: String?, used: DecoderAttempt?): String? {
    val software = used?.codecName?.takeIf { it != requested }
    val policy = DecoderConfigurationPolicy(60, mtk = false, vendorRefused = false)
    policy.begin(60, software, requested, MtkDecoderMode.NONE, 29)
    while (true) {
        val attempt = policy.next(software, requested) ?: break
        val result = policy.result(attempt == used, attempt.codecName == requested)
        if (attempt == used) return if (result and 2 != 0) null else requested
    }
    return requested
}
