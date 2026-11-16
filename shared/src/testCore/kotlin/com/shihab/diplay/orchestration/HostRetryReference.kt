package com.shihab.diplay.orchestration

/** The pre-migration host rules, retained independently for native contract tests. */
class HostRetryReference {
    var attempts = 0
    var retries = 0
    private var frameSession: Any? = null
    private var firstFrameMillis = 0L

    fun schedule(startup: Boolean, tunnel: Boolean): Long? {
        val delay = if (startup) {
            if (retries >= 5) return null
            (2_000L * (1L shl retries++)).coerceAtMost(30_000L)
        } else if (tunnel) 15_000L
        else (2_000L * (1L shl attempts.coerceAtMost(4))).coerceAtMost(30_000L)
        attempts++
        return delay
    }
    fun firstFrame(session: Any, now: Long): Boolean {
        if (frameSession === session) return false
        frameSession = session
        firstFrameMillis = now
        return true
    }
    fun resetIfStable(session: Any, now: Long): Boolean {
        if (frameSession !== session || now - firstFrameMillis < 60_000L) return false
        retries = 0
        return true
    }
    fun disconnected() { frameSession = null }
    fun resetAttempts() { attempts = 0 }
    fun resetStartup() { retries = 0; disconnected() }
}

/** Previous host control observations, independent of the native token representation. */
class ControlRecoveryReference {
    private var count = 0
    private var loss = false
    fun running() { count++; loss = false }
    fun loss() { loss = true }
    fun snapshot(): Int = count
    fun recoveredSince(snapshot: Int): Boolean = count != snapshot && !loss
}

/** Previous host marker, independent of the native value token. */
class BackgroundAttemptReference {
    private var kept = false
    fun keep() { kept = true }
    fun foreground() { kept = false }
    fun consumeFailure(): Boolean {
        if (!kept) return false
        kept = false
        return true
    }
}

/** Former Host failure latches; generations and the global stop are independent. */
class HostFailureReference {
    var stopped = false
    private var startupGeneration = -1
    private var usbGeneration: Int? = null
    fun stop() { stopped = true }
    fun resume() { stopped = false }
    fun duplicateStartup(generation: Int): Boolean {
        if (startupGeneration == generation) return true
        startupGeneration = generation
        return false
    }
    fun stopUsb(generation: Int) { usbGeneration = generation; stopped = true }
    fun usbBlocks(generation: Int): Boolean = usbGeneration == generation
    fun usbFallback() { stopped = false; usbGeneration = null }
}
