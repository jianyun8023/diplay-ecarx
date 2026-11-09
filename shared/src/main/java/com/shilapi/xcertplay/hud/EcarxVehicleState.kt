package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.CarPlayVehicleSource
import java.util.Locale
import kotlin.math.roundToInt

/** 753 SDK and car-service boundary. No simulated defaults or direct CAN writes. */
class EcarxVehicleState private constructor(
    context: Context,
    private val car: Any,
    private val functions: Any,
    private val sensors: Any,
    private val log: (String) -> Unit,
) : CarPlayVehicleSource {
    override val name = "ECARX 753"
    private val functionType = Class.forName(FUNCTION)
    private val sensorType = Class.forName(SENSOR)
    private val connectType = Class.forName(CONNECT)
    private val cache = mutableMapOf<Long, Any>()
    private var closed = false
    private var ready = false
    private var audio: EcarxVehicleAudio? = null
    private val radio = EcarxRadioService(context, log)
    private var radioState: EcarxRadioService.State? = null
    private var nextMediaRead = 0L
    private var lastAvailability: String? = null
    private val properties = EcarxVehicleSignalProbe(context, log) { id, raw ->
        synchronized(this) {
            if (!closed) {
                val field = if (id == RPM_PROPERTY) 2562L else 2050L + (id - TIRE_PROPERTY) * 5
                val value = runCatching { decodeProperty(id, raw) }
                    .onFailure { log("Vehicle state property=$id failed: ${it.cause ?: it}") }.getOrNull()
                if (value == null) cache.remove(field) else cache[field] = value
            }
        }
    }

    // Values are SDK enums, not UI levels (fan and seat heat use funcId + level).
    internal data class Binding(val id: Int, val function: Int, val zone: Int = 0,
        val kind: Int, val format: Int = 0, val maximum: Int = 1) {
        fun decode(raw: Number): Any? {
            val value = raw.toDouble()
            if (!value.isFinite()) return null
            return when {
                function == 0x10030100 -> when (value) { 268632321.0 -> true; 268632322.0 -> false; else -> null }
                format == 0 -> when (value) { 0.0 -> false; 1.0 -> true; else -> null }
                format == 9 -> value.takeIf { it in 16.0..31.0 && it * 2 == kotlin.math.floor(it * 2) }
                function == 0x10040300 -> value.takeIf { it == 0.0 || it == 1.0 }?.toInt()
                else -> {
                    val level = if (value == 0.0) 0.0 else value - function
                    level.takeIf { it in 0.0..maximum.toDouble() && it == kotlin.math.floor(it) }?.toInt()
                }
            }
        }

        fun encode(value: Any): Number? = when (format) {
            0 -> (value as? Boolean)?.let { if (function == 0x10030100) function + if (it) 1 else 2 else if (it) 1 else 0 }
            9 -> (value as? Number)?.toDouble()?.takeIf { decode(it) != null }?.toFloat()
            else -> (value as? Number)?.toDouble()?.takeIf {
                it.isFinite() && it in 0.0..maximum.toDouble() && it == kotlin.math.floor(it)
            }?.toInt()?.let { if (it == 0 || function == 0x10040300) it else function + it }
        }
    }

    private val functionListener = EcarxDimApi.proxy(Class.forName("$FUNCTION\$IFunctionValueWatcher"), "DiPlayVehicleFunctions") { method, args ->
        if (method in setOf("onFunctionChanged", "onFunctionValueChanged", "onCustomizeFunctionValueChanged",
                "onSupportedFunctionStatusChanged")) refreshFunction(args?.firstOrNull() as? Int)
        null
    }
    private val sensorListener = EcarxDimApi.proxy(Class.forName("$SENSOR\$ISensorListener"), "DiPlayVehicleSensors") { method, args ->
        val id = args?.firstOrNull() as? Int
        if (id in SENSOR_IDS && method in setOf("onSensorValueChanged", "onSensorSupportChanged")) refreshSensor(requireNotNull(id))
        null
    }
    private val connectListener = EcarxDimApi.proxy(Class.forName("$CONNECT\$IConnectWatcher"), "DiPlayVehicleConnection") { method, _ ->
        synchronized(this) {
            if (!closed) {
                ready = method == "onConnected"
                if (ready) {
                    refreshFunction(null)
                    SENSOR_IDS.forEach(::refreshSensor)
                } else cache.clear()
                log("Vehicle state ECARX connected=$ready")
            }
        }
        null
    }

    private fun start() {
        functionType.getMethod("registerFunctionValueWatcher", IntArray::class.java, functionListener.javaClass.interfaces.single())
            .invoke(functions, (BINDINGS.map { it.function } + ADAS.keys).distinct().toIntArray(), functionListener)
            .also { check(it == true) { "vehicle function observer refused" } }
        for (id in SENSOR_IDS) {
            check(sensorType.getMethod("registerListener", sensorListener.javaClass.interfaces.single(), INT, INT)
                .invoke(sensors, sensorListener, id, 3) == true) { "vehicle sensor observer refused: $id" }
        }
        // The SDK calls back immediately if its car service is already ready, otherwise on connection.
        connectType.getMethod("registerConnectWatcher", connectListener.javaClass.interfaces.single()).invoke(car, connectListener)
        properties.start(PROPERTIES, { connected ->
            synchronized(this) {
                if (!closed) {
                    audio = runCatching { connected.javaClass.getMethod("getCarManager", String::class.java).invoke(connected, "audio") }
                        .onFailure { log("Vehicle state audio unavailable: ${it.cause ?: it}") }.getOrNull()?.let { EcarxVehicleAudio(it, log) }
                    nextMediaRead = 0
                }
            }
        }, {
            synchronized(this) {
                audio = null
                (listOf(2562L) + (0..3).map { 2050L + it * 5 } + EcarxVehicleAudio.CHANNELS.map { it.id.toLong() }).forEach(cache::remove)
            }
        })
        radio.start()
    }

    @Synchronized private fun refreshFunction(function: Int?) {
        if (closed || !ready) return
        for (binding in BINDINGS.filter { function == null || it.function == function }) {
            val value = runCatching {
                if (functionType.getMethod("isFunctionSupported", INT, INT).invoke(functions, binding.function, binding.zone)?.toString() != "active") null
                else {
                    val getter = if (binding.format == 9) "getCustomizeFunctionValue" else "getFunctionValue"
                    val raw = functionType.getMethod(getter, INT, INT).invoke(functions, binding.function, binding.zone) as Number
                    binding.decode(raw)
                }
            }.onFailure { log("Vehicle state ECARX read iid=${binding.id} failed: ${it.cause ?: it}") }.getOrNull()
            if (value == null) cache.remove(binding.id.toLong()) else cache[binding.id.toLong()] = value
        }
        for ((key, reading) in ADAS.filter { function == null || it.key == function }) {
            val value = runCatching {
                if (functionType.getMethod("isFunctionSupported", INT, INT).invoke(functions, key, 0)?.toString() != "active") null
                else (functionType.getMethod("getFunctionValue", INT, INT).invoke(functions, key, 0) as Int).let {
                    when (it) { 0 -> "关闭"; 1 -> "开启"; else -> null }
                }
            }.onFailure { log("Vehicle state ADAS function=$key failed: ${it.cause ?: it}") }.getOrNull()
            if (value == null) cache.remove(reading.toLong()) else cache[reading.toLong()] = value
        }
    }

    @Synchronized private fun refreshSensor(id: Int) {
        if (closed || !ready) return
        val value = runCatching {
            if (sensorType.getMethod("isSensorSupported", INT).invoke(sensors, id)?.toString() != "active") null
            else (sensorType.getMethod("getSensorLatestValue", INT).invoke(sensors, id) as Number).toDouble()
                .takeIf { it.isFinite() && it >= 0 && it <= when (id) {
                    SPEED -> 125.0; FUEL -> 51_000_000.0; ODOMETER -> 2_000_000.0; else -> 4095.0
                } }
        }.onFailure { log("Vehicle state ECARX sensor=$id failed: ${it.cause ?: it}") }.getOrNull()
        val field = SENSOR_IDS.indexOf(id) * 8L + 1284
        if (value == null) cache.remove(field) else cache[field] = when (id) {
            // HY's real 753 client also converts this SDK m/s feed with * 3.6.
            SPEED -> String.format(Locale.ROOT, "%.1f km/h", value * 3.6)
            // SensorPaUtil converts the fuel signal to millilitres, never a percentage.
            FUEL -> String.format(Locale.ROOT, "%.1f L", value / 1000)
            else -> String.format(Locale.ROOT, "%.0f km", value)
        }
    }

    @Synchronized override fun values(): Map<Long, Any> = configuration().flatMap { it["services"] as List<*> }
        .flatMap { (it as Map<*, *>)["characteristics"] as List<*> }
        .map { it as Map<*, *> }.filter { it.containsKey("initialValue") }
        .associate { (it["iid"] as Int).toLong() to requireNotNull(it["initialValue"]) }

    @Synchronized override fun write(id: Long, value: Any): Boolean {
        if (closed) return false
        if (EcarxVehicleAudio.CHANNELS.any { it.id.toLong() == id }) {
            val accepted = audio?.write(id, value) == true
            if (accepted) { nextMediaRead = 0; refreshMedia() }
            return accepted
        }
        if (!ready) return false
        val binding = BINDINGS.firstOrNull { it.id.toLong() == id } ?: return false
        val encoded = binding.encode(value) ?: return false
        return runCatching {
            if (functionType.getMethod("isFunctionSupported", INT, INT).invoke(functions, binding.function, binding.zone)?.toString() != "active") return@runCatching false
            // Only submit advertised enum values; SDK sentinel/auto values never become a level.
            if (binding.format != 9) {
                val supported = functionType.getMethod("getSupportedFunctionValue", INT).invoke(functions, binding.function) as IntArray
                if (encoded.toInt() !in supported) return@runCatching false
            }
            val accepted = if (binding.format == 9) functionType.getMethod("setCustomizeFunctionValue", INT, INT, FLOAT)
                .invoke(functions, binding.function, binding.zone, encoded.toFloat()) == true
            else if (binding.zone == 0) functionType.getMethod("setFunctionValue", INT, INT)
                .invoke(functions, binding.function, encoded.toInt()) == true
            else functionType.getMethod("setFunctionValue", INT, INT, INT)
                .invoke(functions, binding.function, binding.zone, encoded.toInt()) == true
            log("Vehicle state ECARX write iid=$id accepted=$accepted")
            // The observer/readback owns the result; a successful request does not invent a value.
            if (accepted) refreshFunction(binding.function)
            accepted
        }.onFailure { log("Vehicle state ECARX write iid=$id failed: ${it.cause ?: it}") }.getOrDefault(false)
    }

    @Synchronized override fun configuration(): List<Map<String, Any>> {
        refreshMedia()
        val values = cache.toMap()
        fun field(id: Int, type: Int, format: Int, value: Any? = values[id.toLong()], writable: Boolean = false): MutableMap<String, Any> =
            linkedMapOf<String, Any>("iid" to id, "type" to type, "format" to format, "mutable" to true,
                "writable" to writable, "oemWritable" to true, "priority" to 1).apply {
                if (value != null) put("initialValue", value)
                if (format >= 10) put("maximumLength", 65535)
                if (format == 1 || format == 7) {
                    put("minimumValue", 0); put("maximumValue", 255); put("stepValue", 1)
                }
            }
        fun service(id: Int, type: Int, fields: List<Map<String, Any>>) = mapOf<String, Any>("iid" to id, "type" to type, "characteristics" to fields)
        val readings = listOf("车速", "剩余燃油", "总里程", "续航", "发动机转速", "胎压（左前/右前/左后/右后）", "车道保持开关", "变道警告开关")
        values[2562]?.let { cache[1316] = "$it r/min" }
        if (!values.containsKey(2562L)) cache.remove(1316L)
        val pressures = (0..3).map { values[2050L + it * 5] }
        if (pressures.any { it != null }) cache[1324] = pressures.joinToString(" / ") {
            (it as? Number)?.let { p -> String.format(Locale.ROOT, "%.0f", p.toDouble()) } ?: "—"
        } + " kPa" else cache.remove(1324L)
        val information = readings.mapIndexed { index, label ->
            val id = 1281 + index * 8
            service(id, 0x16000015, listOf(field(id + 1, 0x30000019, 10, "ecarx_$index"),
                field(id + 2, 0x30000001, 10, label), field(id + 3, 0x30000017, 10, cache[(id + 3).toLong()] ?: "车辆数据不可用"),
                field(id + 4, 0x36000006, 1, 0), field(id + 5, 0x3000005e, 10, "car"),
                field(id + 6, 0x30000003, 1, index), field(id + 7, 0x36000028, 10, name)))
        } + service(1480, 0x16000020, listOf(field(1481, 0x30000019, 10, name), field(1482, 0x30000003, 1, 0),
            field(1483, 0x36000004, 10, "车辆数据")))
        fun climateField(id: Int): Map<String, Any> {
            val binding = BINDINGS.single { it.id == id }
            return field(id, binding.kind, binding.format, writable = true).apply {
                if (binding.format != 0) {
                    put("minimumValue", if (binding.format == 9) 16.0 else 0)
                    put("maximumValue", if (binding.format == 9) 31.0 else binding.maximum)
                    put("stepValue", if (binding.format == 9) .5 else 1)
                    if (binding.format == 9) put("units", 1082)
                }
            }
        }
        val climate = listOf(service(513, 0x11000001, listOf(514, 515, 516, 517).map(::climateField)),
            service(518, 0x11000002, listOf(climateField(520), field(522, 0x36000065, 10, "seat_front_left"))),
            service(532, 0x11000002, listOf(climateField(534), field(536, 0x36000065, 10, "seat_front_right"))),
            service(523, 0x11000007, listOf(climateField(525), climateField(526), field(531, 0x36000065, 10, "seat_front"))),
            service(527, 0x11000011, listOf(climateField(528))),
            service(537, 0x11000012, listOf(climateField(538), field(539, 0x36000065, 10, "seat_front"))),
            service(544, 0x11000005, listOf(climateField(545), field(546, 0x31000014, 1, 2), field(547, 0x36000065, 10, "windshield_rear"))))
        val seats = listOf(1025, 1029).map { id -> service(id, 0x22000001,
            listOf(climateField(id + 1), field(id + 3, 0x36000065, 10, if (id == 1025) "seat_front_left" else "seat_front_right"))) }
        val volumes = EcarxVehicleAudio.CHANNELS.mapIndexed { index, channel ->
            service(channel.id - 1, 0x13000002, listOf(
                field(channel.id, 0x33000006, 1, writable = true).apply { put("maximumValue", 100); put("disabled", false) },
                field(channel.id + 1, 0x33000005, 1, channel.type), field(2400 + index, 0x30000001, 10, channel.label)))
        }
        val tires = (0..3).map { index ->
            val id = 2049 + index * 5
            service(id, 0x1b000001, listOf(field(id + 1, 0x3000001b, 3).apply { put("units", 932) },
                field(id + 2, 0x36000065, 10, listOf("tire_front_left", "tire_front_right", "tire_rear_left", "tire_rear_right")[index])))
        }
        val tuner = listOf("FM", "AM").mapIndexed { index, band ->
            val id = 4112 + index * 8
            val current = radioState?.takeIf { it.band == band }?.frequency
            val stations = current?.let { listOf(mapOf("identifier" to "$it", "mediaItemName" to radioLabel(band, it),
                "mediaItemShortName" to band, "mediaItemType" to band.lowercase(Locale.ROOT), "mediaItemCategory" to 0, "frequency" to it)) } ?: emptyList()
            service(id, 0x14000006, listOf(field(id + 1, 0x30000019, 10, band), field(id + 2, 0x32000025, 1, if (index == 0) 2 else 1),
                field(id + 3, 0x32000011, 0, false), field(id + 4, 0x32000026, 10, current?.toString()),
                field(id + 5, 0x32000007, 3, current).apply {
                    put("minimumValue", if (index == 0) 87500 else 531); put("maximumValue", if (index == 0) 108000 else 1629)
                    put("stepValue", if (index == 0) 100 else 9)
                }, field(id + 6, 0x32000012, 13, stations)))
        }
        val controls = (4360..4366).map { id ->
            val type = listOf(0x0f000032, 0x0f000033, 0x0f000035, 0x0f000036, 0x0f00004b, 0x0f00004c, 0x0f00004d)[id - 4360]
            linkedMapOf<String, Any>("iid" to id, "type" to type, "sender" to 0, "hasResponse" to true, "priority" to 1).apply {
                val params = when (id) {
                    4364 -> listOf("identifier" to 10, "sourceIdentifier" to 10)
                    4365 -> listOf("frequency" to 3, "sourceIdentifier" to 10)
                    4366 -> listOf("identifier" to 10)
                    else -> emptyList()
                }
                if (params.isNotEmpty()) put("requestParameters", params.map { mapOf("name" to it.first, "format" to it.second) })
            }
        }
        val nowPlaying = service(4353, 0x14000001, listOf(field(4354, 0x32000023, 10, radioState?.band),
            field(4355, 0x32000003, 10, radioState?.let { s -> s.frequency?.let { radioLabel(s.band, it) } }),
            field(4356, 0x32000004, 10, "原车收音机"), field(4359, 0x32000034, 1, radioState?.playing?.let { if (it) 1 else 0 }))) + ("controls" to controls)
        logAvailability()
        return listOf(mapOf("iid" to 1280, "type" to 0x05000001, "version" to "1.0", "services" to information),
            mapOf("iid" to 512, "type" to 0x01000001, "version" to "1.0", "services" to climate),
            mapOf("iid" to 1024, "type" to 0x01500001, "version" to "1.0", "services" to seats),
            mapOf("iid" to 2304, "type" to 0x02000001, "version" to "1.0", "services" to volumes),
            mapOf("iid" to 2048, "type" to 0x0a000001, "version" to "1.0", "services" to tires),
            mapOf("iid" to 2560, "type" to 0x09000005, "version" to "1.0", "services" to listOf(service(2561, 0x1a00000f,
                listOf(field(2562, 0x30000024, 3).apply { put("units", 981) })))),
            mapOf("iid" to 4096, "type" to 0x03000001, "version" to "1.0", "services" to tuner),
            mapOf("iid" to 4352, "type" to 0x0f000001, "version" to "1.0", "services" to listOf(nowPlaying)))
    }

    // One line per change, so a report shows which signals this car really fills (or leaves empty).
    private fun logAvailability() {
        val keys = listOf("speed", "fuel", "odometer", "range", "rpm", "tires", "laneKeep", "laneChangeWarning")
        val present = keys.filterIndexed { index, _ -> cache.containsKey(1284L + index * 8) }
        val climate = BINDINGS.count { cache.containsKey(it.id.toLong()) }
        val volumes = EcarxVehicleAudio.CHANNELS.count { cache.containsKey(it.id.toLong()) }
        val tires = (0..3).count { cache.containsKey(2050L + it * 5) }
        val summary = "connected=$ready present=$present missing=${keys - present.toSet()} tires=$tires/4 " +
            "climate=$climate/${BINDINGS.size} volume=$volumes/${EcarxVehicleAudio.CHANNELS.size} radio=${radioState?.band ?: "none"}"
        if (summary == lastAvailability) return
        lastAvailability = summary
        log("Vehicle state ECARX availability $summary")
    }

    private fun refreshMedia() {
        if (closed || SystemClock.elapsedRealtime() < nextMediaRead) return
        nextMediaRead = SystemClock.elapsedRealtime() + 1000
        EcarxVehicleAudio.CHANNELS.forEach { cache.remove(it.id.toLong()) }
        audio?.read()?.let(cache::putAll)
        radioState = radio.read()
    }

    @Synchronized override fun control(id: Long, params: Map<*, *>): Boolean {
        if (closed) return false
        val accepted = radio.control(id, params)
        nextMediaRead = 0
        refreshMedia()
        return accepted
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        cache.clear()
        audio = null
        radioState = null
        properties.stop()
        radio.close()
        runCatching { functionType.getMethod("unregisterFunctionValueWatcher", functionListener.javaClass.interfaces.single()).invoke(functions, functionListener) }
        runCatching { sensorType.getMethod("unregisterListener", sensorListener.javaClass.interfaces.single()).invoke(sensors, sensorListener) }
        runCatching { connectType.getMethod("unregisterConnectWatcher").invoke(car) }
    }

    companion object {
        private val INT = Int::class.javaPrimitiveType!!
        private val FLOAT = Float::class.javaPrimitiveType!!
        internal const val CAR = "com.ecarx.xui.adaptapi.car.Car"
        internal const val FUNCTION = "com.ecarx.xui.adaptapi.car.base.ICarFunction"
        internal const val SENSOR = "com.ecarx.xui.adaptapi.car.sensor.ISensor"
        internal const val CONNECT = "com.ecarx.xui.adaptapi.binder.IConnectable"
        internal const val PROTO = "vendor.cevt.hardware.automotive.vehicle.geely.V2_0.GeelyProto\$PublicAttrValue"
        internal const val RPM_PROPERTY = 291504901
        internal const val TIRE_PROPERTY = 560992339
        // The car dump advertises RPM at 1–10 Hz; tire properties are change-driven.
        private val PROPERTIES = listOf(EcarxVehicleSignalProbe.Signal(RPM_PROPERTY, "engineRpm", Float::class.javaObjectType, 5f)) +
            (0..3).map { EcarxVehicleSignalProbe.Signal(TIRE_PROPERTY + it, "tirePressure$it", ByteArray::class.java) }
        private val ADAS = mapOf(537329920 to 1332, 537330432 to 1340)

        internal fun decodeProperty(id: Int, raw: Any?): Any? {
            if (id == RPM_PROPERTY) return (raw as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..12000.0 }?.roundToInt()
            if (id !in TIRE_PROPERTY..TIRE_PROPERTY + 3 || raw !is ByteArray) return null
            val type = Class.forName(PROTO)
            val value = type.getMethod("parseFrom", ByteArray::class.java).invoke(null, raw)
            val availability = type.getMethod("getAvailability").invoke(value)
            if (availability.javaClass.getMethod("getNumber").invoke(availability) != 1 || type.getMethod("hasDoubleData").invoke(value) != true) return null
            return (type.getMethod("getDoubleData").invoke(value) as Double)
                .takeIf { it.isFinite() && it > 0 && it <= 1000 }?.roundToInt()
        }

        private fun radioLabel(band: String, frequency: Int) = if (band == "FM")
            String.format(Locale.ROOT, "FM %.1f MHz", frequency / 1000.0) else "AM $frequency kHz"
        private const val SPEED = 1048832
        private const val FUEL = 1050112
        private const val ODOMETER = 1050368
        private val SENSOR_IDS = listOf(SPEED, FUEL, ODOMETER, 1050624)
        internal val BINDINGS = listOf(
            Binding(514, 0x10010100, kind = 0x31000002), Binding(515, 0x10010300, kind = 0x31000003),
            Binding(516, 0x10010400, kind = 0x31000022), Binding(517, 0x10040200, kind = 0x31000029),
            Binding(520, 0x10060100, 1, 0x31000017, 9, 31), Binding(534, 0x10060100, 4, 0x31000017, 9, 31),
            Binding(525, 0x10020100, kind = 0x31000012, format = 1, maximum = 7),
            Binding(526, 0x10010200, kind = 0x3000005f),
            // Circulation uses enum values too; the boolean is converted explicitly below.
            Binding(528, 0x10030100, kind = 0x30000002),
            // The firmware's PaClSync getter and CbClSync setter both use ON=1 (not inverted Dual).
            Binding(538, 0x10060500, kind = 0x30000002),
            Binding(545, 0x10040300, kind = 0x30000004, format = 1),
            Binding(1026, 0x10050200, 1, 0x31000021, 7, 3), Binding(1030, 0x10050200, 4, 0x31000021, 7, 3),
        )

        fun supported(context: Context): Boolean = EcarxCapabilities.detect(context).identified && EcarxCapabilities.classPresent(CAR)

        fun create(context: Context, log: (String) -> Unit): EcarxVehicleState? {
            var source: EcarxVehicleState? = null
            return runCatching {
                val car = Class.forName(CAR).getMethod("create", Context::class.java).invoke(null, context.applicationContext) ?: return null
                val type = Class.forName("com.ecarx.xui.adaptapi.car.ICar")
                EcarxVehicleState(context.applicationContext, car, requireNotNull(type.getMethod("getICarFunction").invoke(car)),
                    requireNotNull(type.getMethod("getSensorManager").invoke(car)), log).also { source = it; it.start() }
            }.getOrElse { source?.close(); log("Vehicle state ECARX unavailable: ${it.cause ?: it}"); null }
        }
    }
}
