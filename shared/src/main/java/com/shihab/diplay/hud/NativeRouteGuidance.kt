package com.shihab.diplay.hud

/** Only the facade owns a handle; all calls, including destroy, run under its monitor. */
internal object NativeRouteGuidance {
    @JvmStatic external fun create(staleNs: Long, emptyNs: Long, keepAcrossNoRoute: Boolean): Long
    @JvmStatic external fun accept(handle: Long, messageId: Int, payload: ByteArray,
        nowNs: Long, wallSeconds: Long, liveSession: Boolean): Int
    @JvmStatic external fun current(handle: Long, nowNs: Long, liveSession: Boolean, dim: Boolean): AppleManeuver?
    @JvmStatic external fun query(handle: Long, field: Int): Long
    @JvmStatic external fun phoneStateAge(handle: Long, nowNs: Long): Long
    @JvmStatic external fun expired(handle: Long, nowNs: Long, liveSession: Boolean): Boolean
    @JvmStatic external fun text(handle: Long, field: Int, nowNs: Long, liveSession: Boolean): String
    @JvmStatic external fun clear(handle: Long, reason: String): Boolean
    @JvmStatic external fun destroy(handle: Long)
}
