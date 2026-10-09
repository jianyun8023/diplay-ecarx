package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * The reflective face of the ECARX XUI DIM API.
 *
 * Everything here is deliberately reflection-only:
 *  * the DIM classes are head-unit system classes, so a compile-time reference would turn a
 *    missing class into `NoClassDefFoundError` at class-init time on every other head unit;
 *  * ECARX ships no public SDK jar, so the method names and descriptors below are the ones
 *    recovered from the bytecode of the two shipped ECARX apps on this head unit.
 *
 * No call in this file may throw. Each returns [EcarxResult], whose `reason` is written to the
 * session log so a refusal on an unexpected firmware is diagnosable instead of silent.
 *
 * Recovered contract:
 * ```
 * DimInteraction.create(Context)                                        static -> DimInteraction (may be null)
 * DimInteraction.getNaviInteraction()                                   -> INaviInteraction
 * DimInteraction.getMediaInteraction()                                  -> IMediaInteraction
 * DimInteraction.getPhoneCallInteraction()                              -> IPhoneCallInteraction
 * DimInteraction.setPresentationToDimSwitch(int,String,String,boolean)  <- 仪表投屏开关
 * INaviInteraction.updateNavigationInfo(INavigationInfo)
 * INaviInteraction.updateTurnByTurnArrow(int)
 * INaviInteraction.updateDistanceToNextGuidancePoint(int)
 * INaviInteraction.updateDistanceToDestination(int)
 * INaviInteraction.updateETA(int)
 * INaviInteraction.updateNextGuidancePointName(String)
 * INaviInteraction.updateLaneInfo(ILaneInfo[])
 * INaviInteraction.notifyTurnByTurnStarted() / notifyTurnByTurnStopped() / notifyNavigationStatus(int)
 * INaviInteraction$INavigationInfo getters:
 *        getNavigationTurnId():int        getDistanceToNextGuidancePoint():long
 *        getDistanceToDestination():long  getDrivingDirection():int
 *        getETA():long                    getLaneInfo():ILaneInfo[]
 *        getMuteState():int               getNavigationStatus():int
 *        getNextGuidancePointName():String getDayNightMode():int
 *        getNavigationTurnSVG():String    getHighwayExitInfo():IHighwayExitInfo
 *        getRoadCameraInfo():IRoadCamera  getServiceAreaInfo():IServiceArea
 *        getExtensionInfo():Bundle
 * ecarx.dimprotocol.DIMProtocolManager.getInstance(Context)              -> DIMProtocolManager
 * DIMProtocolManager.setNaviMode(int)
 * ```
 */
internal object EcarxDimApi {
    private const val TAG = "DiPlay-Ecarx-DIM"

    /** The screen id the stock ECARX apps pass; both of them hardcode `1`. */
    const val DEFAULT_SCREEN_ID = 1

    data class EcarxResult(val ok: Boolean, val reason: String) {
        companion object {
            fun ok(what: String) = EcarxResult(true, what)
            fun failed(what: String) = EcarxResult(false, what)
        }
    }

    /** One navigation frame in ECARX vocabulary. `-1` means "this field has no new value". */
    data class Guidance(
        val turnId: Int = 0,
        val distanceToNextMeters: Long = -1,
        val distanceToDestinationMeters: Long = -1,
        val etaEpochSeconds: Long = -1,
        val drivingDirection: Int = 0,
        val status: Int = 0,
        val muteState: Int = 0,
        val dayNightMode: Int = 0,
        val nextGuidancePointName: String = "",
    )

    // ---------------------------------------------------------------- DimInteraction

    fun createDimInteraction(context: Context): Any? = runCatching {
        val cls = Class.forName(EcarxCapabilities.DIM_INTERACTION)
        cls.getMethod("create", Context::class.java).invoke(null, context)
    }.getOrElse {
        Log.i(TAG, "DimInteraction.create failed: ${describe(it)}")
        null
    }

    fun naviInteraction(dim: Any): Any? = read(dim, "getNaviInteraction")

    fun mediaInteraction(dim: Any): Any? = read(dim, "getMediaInteraction")

    fun phoneCallInteraction(dim: Any): Any? = read(dim, "getPhoneCallInteraction")

    /**
     * The 仪表投屏 switch. On this head unit both stock ECARX apps call
     * `setPresentationToDimSwitch(1, "", "", true/false)` — the two strings are always empty,
     * so the platform does not use them for identification here.
     */
    fun setPresentationToDimSwitch(
        dim: Any,
        show: Boolean,
        screenId: Int = DEFAULT_SCREEN_ID,
    ): EcarxResult = call(
        dim,
        "setPresentationToDimSwitch",
        arrayOf(
            Int::class.javaPrimitiveType!!,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        ),
        arrayOf<Any?>(screenId, "", "", show),
        "setPresentationToDimSwitch($screenId,\"\",\"\",$show)",
    )

    // ---------------------------------------------------------------- INaviInteraction

    fun updateNavigationInfo(navi: Any, guidance: Guidance): EcarxResult {
        val itf = runCatching { Class.forName(EcarxCapabilities.NAVI_INFO) }.getOrNull()
            ?: return EcarxResult.failed("INavigationInfo class missing")
        val info = runCatching {
            Proxy.newProxyInstance(itf.classLoader, arrayOf(itf), NaviInfoHandler(guidance))
        }.getOrElse { return EcarxResult.failed("INavigationInfo proxy failed: ${describe(it)}") }
        return call(navi, "updateNavigationInfo", arrayOf(itf), arrayOf<Any?>(info), "updateNavigationInfo")
    }

    fun updateTurnByTurnArrow(navi: Any, arrow: Int): EcarxResult =
        callInt(navi, "updateTurnByTurnArrow", arrow)

    fun updateDistanceToNextGuidancePoint(navi: Any, meters: Long): EcarxResult =
        callInt(navi, "updateDistanceToNextGuidancePoint", meters.toInt())

    fun updateDistanceToDestination(navi: Any, meters: Long): EcarxResult =
        callInt(navi, "updateDistanceToDestination", meters.toInt())

    fun updateEta(navi: Any, epochSeconds: Long): EcarxResult =
        callInt(navi, "updateETA", epochSeconds.toInt())

    fun updateNextGuidancePointName(navi: Any, name: String): EcarxResult =
        call(navi, "updateNextGuidancePointName", arrayOf(String::class.java), arrayOf<Any?>(name),
            "updateNextGuidancePointName(\"$name\")")

    /** Clears the lane strip. An empty typed array is what the stock apps send when none remain. */
    fun updateLaneInfoEmpty(navi: Any): EcarxResult {
        val lane = runCatching { Class.forName(EcarxCapabilities.LANE_INFO) }.getOrNull()
            ?: return EcarxResult.failed("ILaneInfo class missing")
        val empty = runCatching { java.lang.reflect.Array.newInstance(lane, 0) }
            .getOrElse { return EcarxResult.failed("ILaneInfo[] allocation failed: ${describe(it)}") }
        // The declared parameter is ILaneInfo[]; take the type off the array we just built so the
        // lookup matches the interface method exactly.
        return call(navi, "updateLaneInfo", arrayOf(empty.javaClass), arrayOf<Any?>(empty), "updateLaneInfo(empty)")
    }

    fun notifyTurnByTurnStarted(navi: Any): EcarxResult = call0(navi, "notifyTurnByTurnStarted")

    fun notifyTurnByTurnStopped(navi: Any): EcarxResult = call0(navi, "notifyTurnByTurnStopped")

    fun notifyNavigationStatus(navi: Any, status: Int): EcarxResult =
        callInt(navi, "notifyNavigationStatus", status)

    // ---------------------------------------------------------------- DIMProtocolManager

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
        Log.i(TAG, "DIMProtocolManager.getInstance failed: ${describe(it)}")
        null
    }

    /** `setNaviMode` — the switch that makes the cluster accept an external navigation source. */
    fun setNaviMode(manager: Any, mode: Int): EcarxResult = callInt(manager, "setNaviMode", mode)

    // ---------------------------------------------------------------- plumbing

    private fun call0(target: Any, name: String): EcarxResult =
        call(target, name, emptyArray(), emptyArray(), name)

    private fun callInt(target: Any, name: String, value: Int): EcarxResult =
        call(target, name, arrayOf(Int::class.javaPrimitiveType!!), arrayOf<Any?>(value), "$name($value)")

    private fun call(target: Any, name: String, params: Array<Class<*>>, args: Array<Any?>, label: String): EcarxResult {
        val method = lookup(target, name, params) ?: return EcarxResult.failed("$label: method not found")
        return runCatching { method.invoke(target, *args) }
            .fold({ EcarxResult.ok(label) }, { EcarxResult.failed("$label threw ${describe(it)}") })
    }

    private fun read(target: Any, name: String): Any? {
        val method = lookup(target, name, emptyArray()) ?: return null
        return runCatching { method.invoke(target) }.getOrElse {
            Log.i(TAG, "$name failed: ${describe(it)}")
            null
        }
    }

    private fun lookup(target: Any, name: String, params: Array<Class<*>>): Method? =
        runCatching { target.javaClass.getMethod(name, *params) }
            .recoverCatching {
                target.javaClass.getDeclaredMethod(name, *params).also { it.isAccessible = true }
            }
            .getOrNull()

    /** Unwraps the reflection wrapper so the log line names the real failure. */
    private fun describe(error: Throwable): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    /** Answers the getter set the platform reads off [EcarxCapabilities.NAVI_INFO]. */
    private class NaviInfoHandler(private val g: Guidance) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getNavigationTurnId" -> g.turnId
            "getDistanceToNextGuidancePoint" -> g.distanceToNextMeters.coerceAtLeast(0L)
            "getDistanceToDestination" -> g.distanceToDestinationMeters.coerceAtLeast(0L)
            "getETA" -> g.etaEpochSeconds.coerceAtLeast(0L)
            "getDrivingDirection" -> g.drivingDirection
            "getNavigationStatus" -> g.status
            "getMuteState" -> g.muteState
            "getDayNightMode" -> g.dayNightMode
            "getNextGuidancePointName" -> g.nextGuidancePointName
            "getLaneInfo" -> null
            "getExtensionInfo" -> null
            "getHighwayExitInfo" -> null
            "getRoadCameraInfo" -> null
            "getServiceAreaInfo" -> null
            "getNavigationTurnSVG" -> null
            // toString/hashCode/equals must stay sane so the platform can log the object.
            "toString" ->
                "EcarxNavigationInfo(turn=${g.turnId} next=${g.distanceToNextMeters} dest=${g.distanceToDestinationMeters})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> defaultValue(method.returnType)
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }
}
