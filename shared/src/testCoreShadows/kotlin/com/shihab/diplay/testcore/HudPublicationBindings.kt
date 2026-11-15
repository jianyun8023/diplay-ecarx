@file:Suppress("UNCHECKED_CAST")

package com.shihab.diplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(className = "com.shihab.diplay.hud.NativeHudPublication", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeHudPublication {
    companion object {
        @JvmStatic @Implementation fun create(): Long =
            HostNative.call("hud.NativeHudPublication", "create") as Long

        @JvmStatic @Implementation fun destroy(handle: Long) {
            HostNative.call("hud.NativeHudPublication", "destroy", handle)
        }

        @JvmStatic @Implementation fun get(handle: Long, field: Int): Long =
            HostNative.call("hud.NativeHudPublication", "get", handle, field) as Long

        @JvmStatic @Implementation fun set(handle: Long, field: Int, value: Long) {
            HostNative.call("hud.NativeHudPublication", "set", handle, field, value)
        }

        @JvmStatic @Implementation fun step(handle: Long, event: Int, a: Long, b: Long, c: Long): Long =
            HostNative.call("hud.NativeHudPublication", "step", handle, event, a, b, c) as Long

        @JvmStatic @Implementation fun cache(handle: Long, index: Int, lanes: IntArray?, turn: Int, cursor: Int) {
            HostNative.call("hud.NativeHudPublication", "cache", handle, index, lanes, turn, cursor)
        }

        @JvmStatic @Implementation fun lanePlan(handle: Long, force: Boolean, cursor: Int, showing: Int, hasTurn: Boolean, turn: Int, enabled: Int): IntArray =
            HostNative.call("hud.NativeHudPublication", "lanePlan", handle, force, cursor, showing, hasTurn, turn, enabled) as IntArray

        @JvmStatic @Implementation fun laneSnapshot(handle: Long, field: Int): IntArray? =
            HostNative.call("hud.NativeHudPublication", "laneSnapshot", handle, field) as IntArray?

        @JvmStatic @Implementation fun laneCommit(handle: Long, lanes: IntArray?) {
            HostNative.call("hud.NativeHudPublication", "laneCommit", handle, lanes)
        }

        @JvmStatic @Implementation fun guidanceChanged(handle: Long, fields: LongArray, road: String, force: Boolean): Boolean =
            HostNative.call("hud.NativeHudPublication", "guidanceChanged", handle, fields, road, force) as Boolean

        @JvmStatic @Implementation fun guidanceCommit(handle: Long, fields: LongArray?, road: String?) {
            HostNative.call("hud.NativeHudPublication", "guidanceCommit", handle, fields, road)
        }

        @JvmStatic @Implementation fun guidanceSnapshot(handle: Long): Array<Any?>? =
            HostNative.call("hud.NativeHudPublication", "guidanceSnapshot", handle) as Array<Any?>?

        @JvmStatic @Implementation fun destinationSnapshot(handle: Long): String? =
            HostNative.call("hud.NativeHudPublication", "destinationSnapshot", handle) as String?

        @JvmStatic @Implementation fun destinationCommit(handle: Long, value: String?) {
            HostNative.call("hud.NativeHudPublication", "destinationCommit", handle, value)
        }

        @JvmStatic @Implementation fun destinationChanged(handle: Long, value: String): Boolean =
            HostNative.call("hud.NativeHudPublication", "destinationChanged", handle, value) as Boolean
    }
}
