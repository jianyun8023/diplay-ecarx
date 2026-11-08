package com.shilapi.xcertplay.airplay

/** Experimental simulated accessory database. No handler in this class reaches a vehicle bus. */
internal class CarPlayVehicleState(private val vehicle: SimulatedVehicle, private val log: (String) -> Unit) {
    private data class Field(
        val id: Int, val kind: Int, val format: Int, val read: (SimulatedVehicle.Reading) -> Any,
        val write: ((SimulatedVehicle.Reading, Any) -> SimulatedVehicle.Reading)? = null,
        val range: Triple<Double, Double, Double>? = null, val unit: Int? = null,
        val mutable: Boolean = true,
    ) {
        fun description(v: SimulatedVehicle.Reading): Map<String, Any> = linkedMapOf<String, Any>(
            "iid" to id, "type" to kind, "format" to format, "mutable" to mutable,
            "writable" to (write != null), "oemWritable" to true, "priority" to 1, "initialValue" to read(v),
        ).apply {
            range?.let { (min, max, step) ->
                put("minimumValue", if (format == 9) min else min.toLong())
                put("maximumValue", if (format == 9) max else max.toLong())
                put("stepValue", if (format == 9) step else step.toLong())
            }
            unit?.let { put("units", it) }
            if (kind == 0x33000006) put("disabled", false)
            if (format >= 10) put("maximumLength", 65535)
        }
        fun accepts(v: Any?): Boolean = when (format) {
            0 -> v is Boolean
            1, 3, 4, 7, 9 -> v is Number && v.toDouble().isFinite() &&
                (format == 9 || v.toDouble() == v.toLong().toDouble()) &&
                (range == null || (v.toDouble() in range.first..range.second &&
                    kotlin.math.abs((v.toDouble() - range.first) / range.third - kotlin.math.round((v.toDouble() - range.first) / range.third)) < 0.0001))
            else -> false
        }
    }
    private data class Service(val id: Int, val kind: Int, val fields: List<Field>, val controls: List<Map<String, Any>> = emptyList())
    private data class Accessory(val id: Int, val kind: Int, val services: List<Service>)
    private val accessories: List<Accessory>
    private val fields: Map<Long, Field>
    private val subscribed = mutableSetOf<Long>()
    private val sent = mutableMapOf<Long, Any>()
    private var notifications = 0

    init {
        fun f(id: Int, kind: Int, format: Int, unit: Int? = null, range: Triple<Double, Double, Double>? = null,
              write: ((SimulatedVehicle.Reading, Any) -> SimulatedVehicle.Reading)? = null,
              read: (SimulatedVehicle.Reading) -> Any) = Field(id, kind, format, read, write, range, unit)
        fun constant(id: Int, kind: Int, value: Any, maximum: Int = 255) = f(id, kind,
            if (value is String) 10 else if (value is Boolean) 0 else 1,
            range = if (value is Int) Triple(0.0, maximum.toDouble(), 1.0) else null) { value }.copy(mutable = false)
        fun climateZone(id: Int, passenger: Boolean) = Service(id, 0x11000002, listOf(
            f(id + 1, 0x30000002, 0, write = { s, v -> s.copy(hvac = v as Boolean) }) { it.hvac },
            f(id + 2, 0x31000017, 9, 1082, Triple(16.0, 28.0, .5), { s, v ->
                val temperature = (v as Number).toDouble()
                if (s.syncTemperature) s.copy(leftTemperature = temperature, rightTemperature = temperature)
                else if (passenger) s.copy(rightTemperature = temperature) else s.copy(leftTemperature = temperature)
            }) { if (passenger) it.rightTemperature else it.leftTemperature },
            f(id + 3, 0x31000019, 9, 1082, Triple(-40.0, 85.0, .5)) { 22.0 },
            constant(id + 4, 0x36000065, if (passenger) "seat_front_right" else "seat_front_left"),
        ))
        fun distance(id: Int, kind: Int, hidden: Boolean, read: (SimulatedVehicle.Reading) -> Int) = Service(id, kind, buildList {
            add(f(id + 1, 0x3000004a, 4, 151) { read(it) })
            add(f(id + 2, 0x3000004b, 4, 164) { (read(it) / 1.609344).toInt() })
            if (hidden) add(constant(id + 3, 0x36000023, false))
        })
        val climate = mutableListOf(
            Service(513, 0x11000001, listOf(
                f(514, 0x31000002, 0, write = { s, v -> s.copy(hvac = v as Boolean) }) { it.hvac },
                f(515, 0x31000003, 0, write = { s, v -> s.copy(ac = v as Boolean) }) { it.ac },
                f(516, 0x31000022, 0, write = { s, v -> if (v as Boolean) s.copy(ac = true, hvac = true, fan = 7, leftTemperature = 16.0,
                    rightTemperature = 16.0, recirculation = true, autoClimate = false) else s.copy(fan = 3, autoClimate = true) }) {
                    it.ac && it.fan == 7 && it.leftTemperature == 16.0 && it.rightTemperature == 16.0
                },
                f(517, 0x31000029, 0, write = { s, v -> s.copy(defrost = v as Boolean) }) { it.defrost },
            )), climateZone(518, false), climateZone(532, true),
            Service(523, 0x11000007, listOf(
                f(524, 0x30000002, 0, write = { s, v -> s.copy(fan = if (v as Boolean) maxOf(s.fan, 1) else 0) }) { it.fan > 0 },
                f(525, 0x31000012, 1, range = Triple(0.0, 7.0, 1.0), write = { s, v -> s.copy(fan = (v as Number).toInt(), autoClimate = false) }) { it.fan },
                f(526, 0x3000005f, 0, write = { s, v -> s.copy(autoClimate = v as Boolean) }) { it.autoClimate },
                constant(531, 0x36000065, "seat_front"),
            )),
            Service(527, 0x11000011, listOf(f(528, 0x30000002, 0, write = { s, v -> s.copy(recirculation = v as Boolean) }) { it.recirculation })),
            Service(529, 0x11000008, listOf(f(530, 0x30000002, 0, write = { s, v -> s.copy(autoClimate = v as Boolean) }) { it.autoClimate })),
            Service(537, 0x11000012, listOf(f(538, 0x30000002, 0, write = { s, v -> s.copy(syncTemperature = v as Boolean,
                rightTemperature = if (v) s.leftTemperature else s.rightTemperature) }) { it.syncTemperature }, constant(539, 0x36000065, "seat_front"))),
        )
        for ((index, direction) in listOf(2, 16, 4).withIndex()) {
            val id = 548 + index * 5
            climate += Service(id, 0x11000006, listOf(
                f(id + 1, 0x30000002, 0, write = { s, v -> when (index) {
                    0 -> s.copy(upperVent = v as Boolean); 1 -> s.copy(middleVent = v as Boolean); else -> s.copy(lowerVent = v as Boolean)
                } }) { when (index) { 0 -> it.upperVent; 1 -> it.middleVent; else -> it.lowerVent } },
                f(id + 2, 0x3000005f, 0, write = { s, v -> s.copy(autoClimate = v as Boolean) }) { it.autoClimate },
                f(id + 3, 0x31000025, 13) { listOf(direction) }, constant(id + 4, 0x36000065, "seat_front"),
            ))
        }
        for (rear in listOf(false, true)) {
            val id = if (rear) 544 else 540
            climate += Service(id, 0x11000005, listOf(
                f(id + 1, 0x30000004, 1, range = Triple(0.0, 1.0, 1.0), write = { s, v ->
                    if (rear) s.copy(rearDefrost = (v as Number).toInt() == 1) else s.copy(defrost = (v as Number).toInt() == 1)
                }) { if (if (rear) it.rearDefrost else it.defrost) 1 else 0 },
                constant(id + 2, 0x31000014, 2), constant(id + 3, 0x36000065, if (rear) "windshield_rear" else "windshield_front"),
            ))
        }
        val seats = listOf(false, true).map { passenger ->
            val id = if (passenger) 1029 else 1025
            Service(id, 0x22000001, listOf(
                f(id + 1, 0x31000021, 7, range = Triple(0.0, 2.0, 1.0), write = { s, v ->
                    if (passenger) s.copy(passengerHeating = (v as Number).toInt()) else s.copy(seatHeating = (v as Number).toInt())
                }) { if (passenger) it.passengerHeating else it.seatHeating },
                f(id + 2, 0x30000002, 0, write = { s, v ->
                    if (passenger) s.copy(passengerHeating = if (v as Boolean) 1 else 0) else s.copy(seatHeating = if (v as Boolean) 1 else 0)
                }) { (if (passenger) it.passengerHeating else it.seatHeating) > 0 },
                constant(id + 3, 0x36000065, if (passenger) "seat_front_right" else "seat_front_left"),
            ))
        }
        val stations = listOf(90500, 101700, 106100).map { frequency -> mapOf(
            "identifier" to frequency.toString(), "mediaItemName" to "模拟 FM ${frequency / 1000.0}",
            "mediaItemShortName" to "模拟FM", "mediaItemType" to "fm", "mediaItemCategory" to 0, "frequency" to frequency,
        ) }
        val controls = listOf(4360 to 0x0f000032, 4361 to 0x0f000033, 4362 to 0x0f000035,
            4363 to 0x0f000036, 4364 to 0x0f00004b, 4365 to 0x0f00004c, 4366 to 0x0f00004d).map { (id, kind) ->
            linkedMapOf<String, Any>("iid" to id, "type" to kind, "sender" to 0, "hasResponse" to true, "priority" to 1).apply {
                val parameters = when (id) {
                    4364 -> listOf("identifier" to 10, "sourceIdentifier" to 10)
                    4365 -> listOf("frequency" to 3, "sourceIdentifier" to 10)
                    4366 -> listOf("identifier" to 10)
                    else -> emptyList()
                }
                if (parameters.isNotEmpty()) put("requestParameters", parameters.map { mapOf("name" to it.first, "format" to it.second) })
            }
        }
        val readings: List<Pair<String, (SimulatedVehicle.Reading) -> String>> = listOf(
            "车速 / 挡位" to { "${it.speed.toInt()} km/h · ${it.gear}" },
            "发动机转速" to { "${it.rpm} r/min" }, "燃油 / 续航" to { "${it.fuel}% · ${it.range} km" },
            "里程" to { "总计 ${it.odometer} km · 小计 ${"%.1f".format(java.util.Locale.ROOT, it.trip)} km" },
            "环境 / 水温 / 油温" to { "${it.ambient}°C / ${it.coolant}°C / ${it.oil}°C" },
            "胎压（左前/右前/左后/右后）" to { "${it.pressures.joinToString(" / ")} kPa" },
            "空调" to { "${it.leftTemperature}° / ${it.rightTemperature}° · 风量 ${it.fan} · A/C ${if (it.ac) "ON" else "OFF"}" },
            "驾驶辅助" to { "巡航 ${if (it.cruise) it.cruiseSpeed.toString() else "待机"} · 跟车 ${it.following}格 · 车道辅助 ${if (it.laneAssist) "ON" else "OFF"}" },
            "车辆检查" to { "${if (it.doorOpen) "车门未关" else "车门已关"} · ${if (it.seatbelt) "安全带已系" else "请系安全带"}" },
            "灯光 / 雨刷" to { "近光 ${if (it.headlights) "ON" else "OFF"} · 远光 ${if (it.highBeam) "ON" else "OFF"} · 雨刷 ${if (it.wipers) "ON" else "OFF"}" },
            "广播" to { "模拟 FM ${it.radioKhz / 1000.0} · ${if (it.radioPlaying) "播放中" else "已暂停"}" },
            "数据来源" to { "小米模拟器 · 未连接真实车身" },
        )
        val settings = readings.mapIndexed { index, (label, read) ->
            val id = 1281 + index * 8
            Service(id, 0x16000015, listOf(constant(id + 1, 0x30000019, "bench_$index"), constant(id + 2, 0x30000001, label),
                f(id + 3, 0x30000017, 10, read = read), constant(id + 4, 0x36000006, 0, 15), constant(id + 5, 0x3000005e, "car"),
                constant(id + 6, 0x30000003, index, 100), constant(id + 7, 0x36000028, "DiPlay 模拟")))
        } + Service(1480, 0x16000020, listOf(constant(1481, 0x30000019, "DiPlay 模拟"), constant(1482, 0x30000003, 0), constant(1483, 0x36000004, "车辆数据（模拟）")))
        val audio = listOf(2306 to 0, 2311 to 1, 2314 to 2, 2317 to 6, 2321 to 3).map { (id, type) ->
            Service(id - 1, 0x13000002, buildList {
                add(f(id, 0x33000006, 1, range = Triple(0.0, 100.0, 1.0), write = { s, v ->
                    val level = (v as Number).toInt()
                    when (id) { 2306 -> s.copy(volume = level); 2311 -> s.copy(navVolume = level); 2314 -> s.copy(callVolume = level)
                        2317 -> s.copy(musicVolume = level); else -> s.copy(siriVolume = level) }
                }) { when (id) { 2306 -> it.volume; 2311 -> it.navVolume; 2314 -> it.callVolume; 2317 -> it.musicVolume; else -> it.siriVolume } })
                add(constant(id + 1, 0x33000005, type, 7))
                if (id == 2306) { add(constant(2308, 0x30000001, "模拟音量")); add(f(2309, 0x33000009, 0, write = { s, v -> s.copy(muted = v as Boolean) }) { it.muted }) }
            })
        }
        accessories = listOf(
            Accessory(1280, 0x05000001, settings), Accessory(2304, 0x02000001, audio),
            Accessory(512, 0x01000001, climate), Accessory(1024, 0x01500001, seats),
            Accessory(768, 0x01200002, listOf(Service(769, 0x20000001, listOf(
                constant(770, 0x48000004, "DB12"), constant(771, 0x48000003, "DB12"), constant(772, 0x48000005, "DB12"),
            )))),
            Accessory(1536, 0x09000006, listOf(
                Service(1537, 0x1a000005, listOf(f(1538, 0x41000002, 1, 1205, Triple(0.0, 100.0, 1.0)) { it.fuel }, constant(1539, 0x4100001a, 10))),
                distance(1540, 0x1a000015, true) { it.range },
                Service(1544, 0x1a000014, listOf(f(1545, 0x35000013, 3, 674) { if (it.speed > 0) 8200 else 0 },
                    f(1546, 0x35000018, 3, 674) { 7600 }, f(1547, 0x41000025, 3, 674) { 50000 })),
            )),
            Accessory(1792, 0x04000001, listOf(distance(1793, 0x1a00000b, true) { it.range }, distance(1797, 0x1a00000a, false) { it.odometer })),
            Accessory(2048, 0x0a000001, listOf("tire_front_left", "tire_front_right", "tire_rear_left", "tire_rear_right").mapIndexed { index, name ->
                val id = 2049 + index * 5
                Service(id, 0x1b000001, listOf(f(id + 1, 0x3000001b, 3, 932, Triple(0.0, 1000.0, 1.0)) { it.pressures[index] },
                    constant(id + 2, 0x36000065, name), constant(id + 3, 0x3000001c, 0), constant(id + 4, 0x3000001a, 1)))
            }),
            Accessory(2560, 0x09000005, listOf(Service(2561, 0x1a00000f, listOf(
                f(2562, 0x30000024, 3, 981, Triple(0.0, 8000.0, 1.0)) { it.rpm },
                f(2563, 0x3000004e, 3, 981) { 8000 }, f(2564, 0x3000004f, 3, 981) { 6500 },
            )))),
            Accessory(4096, 0x03000001, listOf(Service(4112, 0x14000006, listOf(
                constant(4113, 0x30000019, "FM"), constant(4114, 0x32000025, 2), constant(4115, 0x32000011, false),
                f(4116, 0x32000026, 10) { it.radioKhz.toString() }, f(4117, 0x32000007, 3, range = Triple(87500.0, 108000.0, 100.0)) { it.radioKhz },
                f(4118, 0x32000012, 13) { stations },
            )))),
            Accessory(4352, 0x0f000001, listOf(Service(4353, 0x14000001, listOf(
                constant(4354, 0x32000023, "FM"), f(4355, 0x32000003, 10) { "模拟 FM ${it.radioKhz / 1000.0}" },
                constant(4356, 0x32000004, "DiPlay 模拟数据"), constant(4357, 0x32000005, "不输出真实电台音频"),
                f(4359, 0x32000034, 1, range = Triple(0.0, 2.0, 1.0)) { if (it.radioPlaying) 1 else 0 },
            ), controls))),
        )
        fields = accessories.flatMap { it.services }.flatMap { it.fields }.associateBy { it.id.toLong() }
        check(fields.size == accessories.sumOf { a -> a.services.sumOf { it.fields.size } })
    }

    private fun describe(): List<Map<String, Any>> {
        val current = vehicle.reading()
        return accessories.map { a -> mapOf("iid" to a.id, "type" to a.kind, "version" to "1.0", "services" to a.services.map { s ->
            linkedMapOf<String, Any>("iid" to s.id, "type" to s.kind, "characteristics" to s.fields.map { it.description(current) }).apply {
                if (s.controls.isNotEmpty()) put("controls", s.controls)
            }
        }) }
    }
    fun info(): Map<String, Any> = mapOf("protocolVersion" to "1.0", "pluginCount" to 1,
        "pluginMapping" to mapOf("DiPlay Bench" to 0), "pluginConfigs" to listOf(mapOf("name" to "DiPlay Bench", "pluginID" to 0, "accessories" to describe())))

    private fun wrap(message: Map<*, *>) = CarPlayOpack.encode(mapOf("pluginID" to 0, "pluginData" to CarPlayOpack.encode(message)))

    @Synchronized fun handle(bytes: ByteArray): List<ByteArray> {
        val envelope = CarPlayOpack.decode(bytes)
        require(envelope["pluginID"] == 0L) { "unknown vehicle plugin" }
        val request = CarPlayOpack.decode(envelope["pluginData"] as? ByteArray ?: error("vehicle pluginData missing"))
        val command = request["command"] as? String ?: error("vehicle command missing")
        val answer = linkedMapOf<String, Any?>("command" to command.removeSuffix("Request") + "Response", "transactionID" to request["transactionID"])
        when (command) {
            "configRequest" -> answer["values"] = mapOf("accessories" to describe())
            "registerRequest", "readRequest" -> {
                val ids = (request["values"] as? List<*> ?: error("vehicle field list missing")).map { it as? Long ?: error("invalid vehicle field ID") }
                val values = linkedMapOf<Long, Any>(); val errors = linkedMapOf<Long, Int>(); val current = vehicle.reading()
                for (id in ids) {
                    val field = fields[id]
                    if (field == null) errors[id] = -6727 else {
                        values[id] = field.read(current)
                        if (command == "registerRequest") { subscribed += id; sent[id] = values.getValue(id) }
                    }
                }
                answer["values"] = values; if (errors.isNotEmpty()) answer["errors"] = errors
                log("Vehicle state $command fields=${ids.size} supported=${values.size}")
            }
            "writeRequest" -> {
                val values = request["values"] as? Map<*, *> ?: error("vehicle writes missing")
                answer["errors"] = values.map { (id, value) ->
                    require(id is Long)
                    val field = fields[id]; val writer = field?.write
                    val status = if (writer != null && field.accepts(value)) { vehicle.change { writer(it, requireNotNull(value)) }; 0 } else -6727
                    log("Vehicle state simulated write iid=$id status=$status")
                    id to status
                }.toMap()
            }
            "controlRequest" -> {
                val controls = request["values"] as? Map<*, *> ?: error("vehicle controls missing")
                val errors = linkedMapOf<Long, Int>(); val values = linkedMapOf<Long, Any?>()
                for ((id, raw) in controls) {
                    require(id is Long)
                    val params = raw as? Map<*, *> ?: emptyMap<Any, Any>()
                    val frequency = if (id == 4364L) (params["identifier"] as? String)?.toIntOrNull()
                        else (params["frequency"] as? Long)?.takeIf { it in 87500..108000 }?.toInt()
                    if (id !in 4360L..4366L || (id in 4364L..4365L && (frequency == null || frequency !in 87500..108000 || frequency % 100 != 0)) ||
                        (id == 4366L && params["identifier"] != "FM") || (params["sourceIdentifier"] != null && params["sourceIdentifier"] != "FM")) {
                        errors[id] = -6735; continue
                    }
                    vehicle.change { s -> when (id) {
                        4360L -> s.copy(radioPlaying = true)
                        4361L -> s.copy(radioPlaying = false)
                        4362L -> s.copy(radioKhz = if (s.radioKhz >= 108000) 87500 else s.radioKhz + 100)
                        4363L -> s.copy(radioKhz = if (s.radioKhz <= 87500) 108000 else s.radioKhz - 100)
                        4364L, 4365L -> s.copy(radioKhz = requireNotNull(frequency), radioPlaying = true)
                        else -> s
                    } }
                    values[id] = null; log("Vehicle state simulated radio control iid=$id")
                }
                answer["values"] = values; if (errors.isNotEmpty()) answer["errors"] = errors
            }
            else -> { log("Vehicle state unsupported command=$command"); return emptyList() }
        }
        return listOf(wrap(answer))
    }

    @Synchronized fun updates(): List<ByteArray> {
        val current = vehicle.reading()
        val changes = linkedMapOf<Long, Any>()
        for (id in subscribed) {
            val value = fields.getValue(id).read(current)
            if (sent[id] != value) { sent[id] = value; changes[id] = value }
        }
        if (changes.isEmpty()) return emptyList()
        notifications++
        if (notifications == 1 || notifications % 100 == 0) log("Vehicle state updateNotify count=$notifications fields=${changes.keys}")
        return listOf(wrap(mapOf("command" to "updateNotify", "values" to changes)))
    }

    companion object {
        const val FEATURE = "vehicleStateProtocol"
        const val HIGH = "FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634"
        const val NORMAL = "3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E"
    }
}
