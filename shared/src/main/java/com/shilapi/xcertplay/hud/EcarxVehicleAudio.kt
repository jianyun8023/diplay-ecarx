package com.shilapi.xcertplay.hud

import kotlin.math.roundToInt

/** Uses the car's volume groups, whose indices and ranges are resolved at runtime. */
internal class EcarxVehicleAudio(private val manager: Any, private val log: (String) -> Unit) {
    data class Channel(val id: Int, val type: Int, val usage: Int, val label: String)
    private data class Group(val id: Int, val minimum: Int, val maximum: Int, val current: Int)

    private fun group(channel: Channel): Group {
        val type = manager.javaClass
        val id = type.getMethod("getVolumeGroupIdForUsage", INT).invoke(manager, channel.usage) as Int
        check(id >= 0) { "volume usage ${channel.usage} unavailable" }
        val minimum = type.getMethod("getGroupMinVolume", INT).invoke(manager, id) as Int
        val maximum = type.getMethod("getGroupMaxVolume", INT).invoke(manager, id) as Int
        val current = type.getMethod("getGroupVolume", INT).invoke(manager, id) as Int
        check(minimum < maximum && current in minimum..maximum) { "invalid volume group $id" }
        return Group(id, minimum, maximum, current)
    }

    fun read(): Map<Long, Int> = CHANNELS.mapNotNull { channel ->
        runCatching {
            val group = group(channel)
            channel.id.toLong() to ((group.current - group.minimum) * 100.0 / (group.maximum - group.minimum)).roundToInt()
        }.onFailure { log("Vehicle state audio read iid=${channel.id} failed: ${it.cause ?: it}") }.getOrNull()
    }.toMap()

    fun write(id: Long, value: Any): Boolean {
        val channel = CHANNELS.firstOrNull { it.id.toLong() == id } ?: return false
        val percent = (value as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it in 0.0..100.0 && it == kotlin.math.floor(it)
        } ?: return false
        return runCatching {
            val group = group(channel)
            val step = (group.minimum + percent * (group.maximum - group.minimum) / 100).roundToInt()
            manager.javaClass.getMethod("setGroupVolume", INT, INT, INT).invoke(manager, group.id, step, 0)
            log("Vehicle state audio write iid=$id group=${group.id} step=$step dispatched")
            true
        }.onFailure { log("Vehicle state audio write iid=$id failed: ${it.cause ?: it}") }.getOrDefault(false)
    }

    companion object {
        private val INT = Int::class.javaPrimitiveType!!
        val CHANNELS = listOf(Channel(2306, 0, 1, "媒体音量"), Channel(2311, 1, 12, "导航音量"),
            Channel(2314, 2, 2, "通话音量"), Channel(2321, 3, 16, "语音音量"))
    }
}
