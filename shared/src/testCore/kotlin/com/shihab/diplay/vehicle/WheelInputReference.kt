package com.shihab.diplay.vehicle

/** Original per-key decisions, without interpreting the native token. */
class WheelPressReference {
    private val consumed = mutableMapOf<Any, Boolean>()
    fun filter(key: Any?, down: Boolean, first: Boolean, decide: () -> Boolean): Boolean {
        key ?: return false
        if (!down) return consumed.remove(key) ?: false
        consumed[key]?.let { return it }
        if (!first) return false
        return decide().also { consumed[key] = it }
    }
}

/** Original shared single/double Siri clock state. */
class WheelSiriReference {
    private var last: Long? = null
    fun opens(now: Long): Boolean {
        val previous = last
        last = now
        return previous == null || now - previous >= 400L
    }
    fun doublePress(now: Long, maximum: Long): Int {
        require(maximum >= 180L)
        val gap = last?.let { now - it }
        return when {
            gap == null || gap <= 0 || gap > maximum -> { last = now; 0 }
            gap < 180 -> { last = now; 1 }
            else -> { last = null; 2 }
        }
    }
    fun reset() { last = null }
}
