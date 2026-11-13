package com.shihab.diplay.hud

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * AdaptAPI access and cache-bypassing recovery through the firmware navigation manager.
 * The head unit supplies every vendor class at runtime.
 * Calls retain diagnostic failures, including missing classes or methods on other firmware.
 * A successful void call means dispatch only; it does not prove the cluster changed.
 */
internal object EcarxDimApi {
    private const val TAG = "DiPlay-Ecarx-DIM"
    @Volatile private var reportSink: ((String) -> Unit)? = null

    fun attachReport(sink: (String) -> Unit) { reportSink = sink }

    private fun report(line: String) {
        Log.i(TAG, line)
        reportSink?.invoke(line)
    }

    const val DEFAULT_SCREEN_ID = 1

    // Snapshot values differ from notifyNavigationStatus's START=2 / END=3 lifecycle events.
    const val NAVIGATION_SNAPSHOT_ACTIVE = 1
    const val NAVIGATION_SNAPSHOT_IDLE = 0

    data class EcarxResult(val ok: Boolean, val reason: String) {
        companion object {
            fun ok(what: String) = EcarxResult(true, what)
            fun failed(what: String) = EcarxResult(false, what)
        }
    }

    /** App-owned handles keep vendor types out of the always-loaded HUD bridge's fields. */
    class DimHandle internal constructor(internal val api: Any)
    class NaviHandle internal constructor(internal val api: Any)

    /** Snapshot status uses ACTIVE=1 / IDLE=0; omitted numeric fields are submitted as zero. */
    data class Guidance(
        val turnId: Int = 0,
        val distanceToNextMeters: Long = -1,
        val distanceToDestinationMeters: Long = -1,
        val etaRemainingSeconds: Int = -1,
        val drivingDirection: Int = 0,
        val status: Int = 0,
        val muteState: Int = 0,
        val dayNightMode: Int = 0,
        val nextGuidancePointName: String = "",
    )

    data class Lane(val number: Int, val iconId: Int)

    fun createDimInteraction(context: Context): DimHandle? = runCatching {
        Class.forName(EcarxCapabilities.DIM_INTERACTION)
            .getMethod("create", Context::class.java).invoke(null, context)?.let(::DimHandle)
    }.getOrElse {
        report("DimInteraction.create failed: ${describe(it)}")
        null
    }

    fun naviInteraction(dim: DimHandle): NaviHandle? = runCatching {
        call(dim.api, "getNaviInteraction")?.let(::NaviHandle)
    }.getOrElse {
        report("getNaviInteraction failed: ${describe(it)}")
        null
    }

    fun setPresentationToDimSwitch(
        dim: DimHandle,
        show: Boolean,
        screenId: Int = DEFAULT_SCREEN_ID,
        packageName: String = "",
    ): EcarxResult = dispatch("setPresentationToDimSwitch($screenId,\"$packageName\",\"$packageName\",$show)") {
        call(dim.api, "setPresentationToDimSwitch",
            arrayOf(Int::class.javaPrimitiveType!!, String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!),
            screenId, packageName, packageName, show)
    }

    fun registerInteractionCallback(dim: DimHandle, label: String, onOption: (Int) -> Unit): Any? = runCatching {
        val type = Class.forName(EcarxCapabilities.DIM_CALLBACK)
        val callback = proxy(type, "EcarxDimCallback($label)") { method, args ->
            if (method == "onShowPresentationOptionChanged") {
                (args?.firstOrNull() as? Int)?.let { option ->
                    report("callback dim.$label onShowPresentationOptionChanged([$option])")
                    onOption(option)
                }
            }
            null
        }
        call(dim.api, "registerInteractionCallback", arrayOf(type), callback)
        callback
    }.getOrElse {
        report("registerInteractionCallback failed: ${describe(it)}")
        null
    }

    fun registerNavigationInteractionCallback(navi: NaviHandle, label: String, onAction: ((Int) -> Unit)? = null): Any? = runCatching {
        val type = Class.forName(EcarxCapabilities.NAVI_CALLBACK)
        val callback = proxy(type, "EcarxNavigationCallback($label)") { method, args ->
            when (method) {
                "onDoInteractionAction" -> (args?.firstOrNull() as? Int)?.let { action ->
                    report("callback navi.$label onDoInteractionAction(action=$action hasAddress=${args?.getOrNull(1) != null})")
                    onAction?.invoke(action)
                }
                "onSearchAddress" -> if (NavigationOutputs.debugLogsEnabled()) {
                    report("callback navi.$label onSearchAddress([${args?.firstOrNull()}])")
                }
            }
            null
        }
        call(navi.api, "registerNavigationInteractionCallback", arrayOf(type), callback)
        callback
    }.getOrElse {
        report("registerNavigationInteractionCallback failed: ${describe(it)}")
        null
    }

    fun updateNavigationInfo(navi: NaviHandle, guidance: Guidance): EcarxResult = dispatch("updateNavigationInfo") {
        val type = Class.forName(EcarxCapabilities.NAVI_INFO)
        val info = proxy(type, "EcarxNavigationInfo") { method, _ ->
            when (method) {
                "getNavigationTurnId" -> guidance.turnId
                "getDistanceToNextGuidancePoint" -> guidance.distanceToNextMeters.coerceAtLeast(0L)
                "getDistanceToDestination" -> guidance.distanceToDestinationMeters.coerceAtLeast(0L)
                "getETA" -> guidance.etaRemainingSeconds.coerceAtLeast(0).toLong()
                "getDrivingDirection" -> guidance.drivingDirection
                "getNavigationStatus" -> guidance.status
                "getMuteState" -> guidance.muteState
                "getDayNightMode" -> guidance.dayNightMode
                "getNextGuidancePointName" -> guidance.nextGuidancePointName
                // The OEM app sends drawings through updateTurnByTurnArrow, not this getter.
                "getNavigationTurnSVG" -> ""
                "getLaneInfo", "getExtensionInfo", "getHighwayExitInfo", "getRoadCameraInfo", "getServiceAreaInfo" -> null
                else -> UNHANDLED
            }
        }
        call(navi.api, "updateNavigationInfo", arrayOf(type), info)
    }

    fun updateTurnByTurnArrow(navi: NaviHandle, arrow: Int): EcarxResult = dispatch("updateTurnByTurnArrow($arrow)") {
        call(navi.api, "updateTurnByTurnArrow", arrayOf(Int::class.javaPrimitiveType!!), arrow)
    }

    /** Recovery must bypass AdaptAPI caches, which are updated before its swallowed Binder errors. */
    fun refreshNavigation(guidance: Guidance, destination: String): EcarxResult {
        val manager = runCatching {
            Class.forName("android.cevt.dimsupportservice.DimSupportManager")
                .getMethod("getNavigationManager").invoke(null)
        }.getOrElse { return EcarxResult.failed("manager=${describe(it)}") }
            ?: return EcarxResult.failed("manager=unavailable")
        val type = manager.javaClass
        val intType = Int::class.javaPrimitiveType!!
        val boolType = Boolean::class.javaPrimitiveType!!
        val details = mutableListOf<String>()
        var accepted = true
        fun send(label: String, method: String, types: Array<Class<*>>, vararg args: Any) {
            val result = dispatch(method) { type.getMethod(method, *types).invoke(manager, *args) }
            accepted = accepted && result.ok
            details += "$label=${if (result.ok) "returned" else result.reason}"
        }
        send("ongoing", "updateOngoingNavigation",
            arrayOf(boolType, String::class.java, boolType),
            guidance.status == NAVIGATION_SNAPSHOT_ACTIVE, destination, guidance.muteState == 1)
        if (guidance.distanceToNextMeters in 0L..Int.MAX_VALUE.toLong()) {
            send("next", "updateTurnByTurnNextTurn", arrayOf(intType, String::class.java),
                guidance.distanceToNextMeters.toInt(), guidance.nextGuidancePointName)
        }
        if (guidance.distanceToDestinationMeters in 0L..Int.MAX_VALUE.toLong()) {
            send("distance", "updateDistanceToDestination", arrayOf(intType), guidance.distanceToDestinationMeters.toInt())
        }
        if (guidance.etaRemainingSeconds >= 0) {
            send("eta", "updateEta", arrayOf(intType), guidance.etaRemainingSeconds)
        }
        if (guidance.turnId > 0) {
            send("arrow", "updateTurnByTurnIcon", arrayOf(intType, String::class.java), 0, guidance.turnId.toString())
        }
        return EcarxResult(accepted, details.joinToString(" "))
    }

    /** The String overload is the SVG channel used by the factory navigation app. */
    fun updateTurnByTurnArrow(navi: NaviHandle, svg: String): EcarxResult = dispatch("updateTurnByTurnArrow(svg ${svg.length}B)") {
        call(navi.api, "updateTurnByTurnArrow", arrayOf(String::class.java), svg)
    }

    fun updateTurnByTurnArrow(navi: NaviHandle, arrow: Bitmap): EcarxResult = dispatch("updateTurnByTurnArrow(Bitmap)") {
        call(navi.api, "updateTurnByTurnArrow", arrayOf(Bitmap::class.java), arrow)
    }

    /** Firmware capability diagnostics still inspect the actual runtime method surface. */
    fun arrowOverloads(target: NaviHandle?): String {
        if (target == null) return "none"
        val names = arrayOf("int", "string", "bitmap")
        val types = arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, String::class.java, Bitmap::class.java)
        return types.indices.filter { index ->
            runCatching { target.api.javaClass.getMethod("updateTurnByTurnArrow", types[index]) }.isSuccess
        }.joinToString("/") { names[it] }.ifEmpty { "none" }
    }

    fun updateDistanceToNextGuidancePoint(navi: NaviHandle, meters: Long): EcarxResult = dispatch("updateDistanceToNextGuidancePoint(${meters.toInt()})") {
        call(navi.api, "updateDistanceToNextGuidancePoint", arrayOf(Int::class.javaPrimitiveType!!), meters.toInt())
    }

    fun updateDistanceToDestination(navi: NaviHandle, meters: Long): EcarxResult = dispatch("updateDistanceToDestination(${meters.toInt()})") {
        call(navi.api, "updateDistanceToDestination", arrayOf(Int::class.javaPrimitiveType!!), meters.toInt())
    }

    fun updateEta(navi: NaviHandle, remainingSeconds: Int): EcarxResult = dispatch("updateETA($remainingSeconds)") {
        call(navi.api, "updateETA", arrayOf(Int::class.javaPrimitiveType!!), remainingSeconds)
    }

    fun updateNextGuidancePointName(navi: NaviHandle, name: String): EcarxResult = dispatch("updateNextGuidancePointName") {
        call(navi.api, "updateNextGuidancePointName", arrayOf(String::class.java), name)
    }

    /** The 753 type-3 implementation reads only the first name; this is not a selectable address. */
    fun updateDestinationName(navi: NaviHandle, name: String): EcarxResult = dispatch("updateDestinationName(${if (name.isEmpty()) "clear" else "present"})") {
        val addresses = if (name.isEmpty()) emptyList() else {
            val type = Class.forName("${EcarxCapabilities.NAVI_INTERACTION}\$IAddress")
            listOf(proxy(type, "EcarxDestination") { method, _ ->
                when (method) {
                    "getAddressName" -> name
                    "getCityName", "getFullAddress" -> ""
                    "getDistance" -> -1L
                    "getLatitude", "getLongitude" -> Double.NaN
                    else -> UNHANDLED
                }
            })
        }
        call(navi.api, "updateAddresses", arrayOf(Int::class.javaPrimitiveType!!, List::class.java), 3, addresses)
    }

    fun updateLaneInfo(navi: NaviHandle, lanes: List<Lane>): EcarxResult = dispatch("updateLaneInfo(${lanes.size})") {
        val type = Class.forName(EcarxCapabilities.LANE_INFO)
        val values = java.lang.reflect.Array.newInstance(type, lanes.size)
        lanes.forEachIndexed { index, lane ->
            java.lang.reflect.Array.set(values, index, proxy(type, "EcarxLaneInfo") { method, _ ->
                when (method) {
                    "getLaneNumber" -> lane.number
                    "getLaneBackIconId" -> lane.iconId
                    "getLaneBackIcon" -> null
                    else -> UNHANDLED
                }
            })
        }
        call(navi.api, "updateLaneInfo", arrayOf(values.javaClass), values)
    }

    /** The 753 navigation plugin sends one null argument to clear the lane strip. */
    fun clearLaneInfo(navi: NaviHandle): EcarxResult = dispatch("updateLaneInfo(null)") {
        val type = java.lang.reflect.Array.newInstance(Class.forName(EcarxCapabilities.LANE_INFO), 0).javaClass
        call(navi.api, "updateLaneInfo", arrayOf(type), null)
    }

    fun notifyTurnByTurnStarted(navi: NaviHandle): EcarxResult = dispatch("notifyTurnByTurnStarted") { call(navi.api, "notifyTurnByTurnStarted") }

    fun notifyTurnByTurnStopped(navi: NaviHandle): EcarxResult = dispatch("notifyTurnByTurnStopped") { call(navi.api, "notifyTurnByTurnStopped") }

    fun notifyNavigationStarted(navi: NaviHandle): EcarxResult = notifyNavigationStatus(navi, 2)

    fun notifyNavigationEnded(navi: NaviHandle): EcarxResult = notifyNavigationStatus(navi, 3)

    /** This firmware re-sends ongoing navigation even when the mute value is unchanged. */
    fun updateMuteState(navi: NaviHandle, state: Int): EcarxResult = dispatch("updateMuteState($state)") {
        call(navi.api, "updateMuteState", arrayOf(Int::class.javaPrimitiveType!!), state)
    }

    /** Older firmware omits this from the interface even when an implementation exposes it. */
    fun notifyNavigationStatus(navi: NaviHandle, status: Int): EcarxResult {
        val method = runCatching { navi.api.javaClass.getMethod("notifyNavigationStatus", Int::class.javaPrimitiveType!!) }
            .getOrNull() ?: return EcarxResult.failed("notifyNavigationStatus($status): method not found")
        return dispatch("notifyNavigationStatus($status)") { method.invoke(navi.api, status) }
    }

    fun showPresentationOption(dim: DimHandle): Int? = runCatching { call(dim.api, "getShowPresentationOption") as? Int }.getOrNull()

    /** No compilation library has been recovered for this optional firmware extension. */
    fun dimProtocolManager(context: Context): Any? = runCatching {
        val cls = Class.forName(EcarxCapabilities.DIM_PROTOCOL_MANAGER)
        val getInstance = cls.getMethod("getInstance", Context::class.java)
        if (Modifier.isStatic(getInstance.modifiers)) {
            getInstance.invoke(null, context)
        } else {
            val ctor = cls.getDeclaredConstructor().also { it.isAccessible = true }
            getInstance.invoke(ctor.newInstance(), context)
        }
    }.getOrElse {
        report("DIMProtocolManager.getInstance failed: ${describe(it)}")
        null
    }

    private inline fun dispatch(label: String, action: () -> Unit): EcarxResult = runCatching { action() }
        .fold({ EcarxResult.ok(label) }, { EcarxResult.failed("$label threw ${describe(it)}") })

    private fun call(target: Any, name: String, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? =
        target.javaClass.getMethod(name, *types).also { it.isAccessible = true }.invoke(target, *args)

    /** Returned by a data proxy for a getter it does not know, such as one added by newer firmware. */
    internal val UNHANDLED = Any()
    private val reportedUnhandled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    internal fun proxy(type: Class<*>, label: String, value: (String, Array<out Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "toString" -> label
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> when (val result = value(method.name, args)) {
                    // Vendor code dereferences getter results, so an unknown getter gets an empty
                    // value instead of null and is reported once for the diagnostic report.
                    UNHANDLED -> {
                        if (reportedUnhandled.add("$label.${method.name}")) {
                            report("unhandled getter $label.${method.name}: ${method.returnType.simpleName}")
                        }
                        emptyValue(method.returnType)
                    }
                    null -> if (method.returnType.isPrimitive) emptyValue(method.returnType) else null
                    else -> result
                }
            }
        }

    private fun emptyValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        String::class.java -> ""
        List::class.java, Collection::class.java -> emptyList<Any>()
        else -> if (type.isArray) java.lang.reflect.Array.newInstance(type.componentType, 0) else null
    }

    private fun describe(error: Throwable): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }
}
