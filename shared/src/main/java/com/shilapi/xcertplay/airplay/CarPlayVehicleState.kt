package com.shilapi.xcertplay.airplay

import java.io.Closeable

/** Owns vehicle reads and validated writes; missing readings stay absent. */
interface CarPlayVehicleSource : Closeable {
    val name: String
    fun configuration(): List<Map<String, Any>>
    fun values(): Map<Long, Any>
    fun write(id: Long, value: Any): Boolean
    fun control(id: Long, params: Map<*, *>): Boolean = false
    override fun close() {}
}

/** One wire protocol for the head unit and the independent bench simulator. */
internal class CarPlayVehicleState(private val source: CarPlayVehicleSource, private val log: (String) -> Unit) : Closeable {
    private val subscribed = mutableSetOf<Long>()
    private val sent = mutableMapOf<Long, Any>()
    private var notifications = 0
    override fun close() = source.close()

    fun info(): Map<String, Any> = mapOf("protocolVersion" to "1.0", "pluginCount" to 1,
        "pluginMapping" to mapOf(source.name to 0), "pluginConfigs" to listOf(mapOf("name" to source.name, "pluginID" to 0, "accessories" to source.configuration())))

    private fun wrap(message: Map<*, *>) = CarPlayOpack.encode(mapOf("pluginID" to 0, "pluginData" to CarPlayOpack.encode(message)))

    @Synchronized fun handle(bytes: ByteArray): List<ByteArray> {
        val envelope = CarPlayOpack.decode(bytes)
        require(envelope["pluginID"] == 0L) { "unknown vehicle plugin" }
        val request = CarPlayOpack.decode(envelope["pluginData"] as? ByteArray ?: error("vehicle pluginData missing"))
        val command = request["command"] as? String ?: error("vehicle command missing")
        val answer = linkedMapOf<String, Any?>("command" to command.removeSuffix("Request") + "Response", "transactionID" to request["transactionID"])
        when (command) {
            "configRequest" -> answer["values"] = mapOf("accessories" to source.configuration())
            "registerRequest", "readRequest" -> {
                val ids = (request["values"] as? List<*> ?: error("vehicle field list missing")).map { it as? Long ?: error("invalid vehicle field ID") }
                val values = linkedMapOf<Long, Any>(); val errors = linkedMapOf<Long, Int>(); val current = source.values()
                for (id in ids) {
                    val value = current[id]
                    if (command == "registerRequest") subscribed += id
                    if (value == null) errors[id] = -6727 else {
                        values[id] = value
                        if (command == "registerRequest") sent[id] = value
                    }
                }
                answer["values"] = values; if (errors.isNotEmpty()) answer["errors"] = errors
                log("Vehicle state $command fields=${ids.size} supported=${values.size} ids=${ids.sorted()}")
            }
            "writeRequest" -> {
                val values = request["values"] as? Map<*, *> ?: error("vehicle writes missing")
                answer["errors"] = values.map { (id, value) ->
                    require(id is Long)
                    val status = if (value != null && source.write(id, value)) 0 else -6727
                    log("Vehicle state write iid=$id status=$status")
                    id to status
                }.toMap()
            }
            "controlRequest" -> {
                val controls = request["values"] as? Map<*, *> ?: error("vehicle controls missing")
                val errors = linkedMapOf<Long, Int>(); val values = linkedMapOf<Long, Any?>()
                for ((id, raw) in controls) {
                    require(id is Long)
                    val params = raw as? Map<*, *> ?: emptyMap<Any, Any>()
                    if (source.control(id, params)) values[id] = null else errors[id] = -6735
                    log("Vehicle state control iid=$id status=" + (errors[id] ?: 0))
                }
                answer["values"] = values; if (errors.isNotEmpty()) answer["errors"] = errors
            }
            else -> { log("Vehicle state unsupported command=$command"); return emptyList() }
        }
        return listOf(wrap(answer))
    }

    @Synchronized fun updates(): List<ByteArray> {
        val current = source.values()
        val changes = linkedMapOf<Long, Any>()
        val errors = linkedMapOf<Long, Int>()
        for (id in subscribed) {
            val value = current[id]
            if (value == null) {
                if (sent.remove(id) != null) errors[id] = -6727
            } else if (sent[id] != value) { sent[id] = value; changes[id] = value }
        }
        if (changes.isEmpty() && errors.isEmpty()) return emptyList()
        notifications++
        if (notifications == 1 || notifications % 100 == 0) log("Vehicle state updateNotify count=$notifications fields=${changes.keys}")
        return listOf(wrap(linkedMapOf<String, Any>("command" to "updateNotify", "values" to changes).apply {
            if (errors.isNotEmpty()) put("errors", errors)
        }))
    }

    companion object {
        const val FEATURE = "vehicleStateProtocol"
        const val HIGH = "FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634"
        const val NORMAL = "3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E"
    }
}
