package com.shihab.diplay.airplay

/** Host branches before their migration; no native policy calls. */
internal object BufferedPolicyReference {
    data class Setup(val reason: Int, val key: ByteArray?, val bits: Long)
    data class Control(val action: Int, val timestamp: Long?, val rate: Int)

    fun setup(stream: Map<String, Any?>): Setup {
        val key = stream["shk"] as? ByteArray
        if (key == null || key.size != 32) return Setup(1, null, 0)
        val bits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val format = KotlinAudioStreamCodec.fromFormatBits(bits, 103, "media")
        if (format.codec != AudioCodecKind.AAC_LC) return Setup(2, null, bits)
        if (stream["ct"]?.let { it !is Number || it.toInt() != 4 } == true ||
            stream["spf"]?.let { it !is Number || it.toInt() != 1024 } == true) return Setup(3, null, bits)
        return Setup(0, key, bits)
    }

    fun control(method: String, body: Map<String, Any?>): Control {
        val rtp = (body["rtpTime"] as? Number)?.toLong()
        val rate = when ((body["rate"] as? Number)?.toDouble()) { 0.0 -> 0; 1.0 -> 1; else -> null }
        return when (method) {
            "SETRATE", "SETRATEANCHORTIME" -> if (rate != null) Control(1, rtp, rate) else Control(0, null, 0)
            "GETANCHOR" -> Control(2, null, 0)
            "FLUSHBUFFERED" -> (body["flushUntilTS"] as? Number)?.toLong()?.let { Control(3, it, 0) }
                ?: Control(0, null, 0)
            else -> Control(0, null, 0)
        }
    }
}
