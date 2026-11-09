package com.shilapi.xcertplay.hud

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.content.ServiceConnection
import android.os.Handler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Checks the names and signatures the reflective adapters call against a real AdaptAPI JAR.
 *
 * The test fixtures under `com.ecarx` are written by us, so they cannot catch a misspelled method
 * or a wrong parameter type. The vendor JAR is not redistributed; pass its path with
 * `-PecarxAdaptapiJar=<path>` or `ECARX_ADAPTAPI_JAR`. Without it, this test is skipped.
 */
class EcarxAdaptapiAbiTest {
    private val jar = System.getProperty("ecarx.adaptapi.jar").orEmpty()

    /** Vendor-first loading prevents our independent fixtures from passing this ABI check. */
    private class VendorFirst(jar: File, parent: ClassLoader) : URLClassLoader(arrayOf(jar.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (listOf("com.ecarx.", "android.car.", "vendor.cevt.").none(name::startsWith)) return super.loadClass(name, resolve)
            findLoadedClass(name) ?: runCatching { findClass(name) }.getOrElse { super.loadClass(name, resolve) }
        }
    }

    @Test fun adaptersMatchTheVendorInterfaces() {
        assumeTrue("set -PecarxAdaptapiJar or ECARX_ADAPTAPI_JAR to check the vendor ABI", jar.isNotEmpty())
        val file = File(jar)
        assertTrue("missing $jar", file.isFile)
        VendorFirst(file, javaClass.classLoader!!).use { loader ->
            fun type(name: String): Class<*> = Class.forName(name, false, loader)
            val int = Int::class.javaPrimitiveType!!
            val boolean = Boolean::class.javaPrimitiveType!!
            val string = String::class.java
            val dim = type(EcarxCapabilities.DIM_INTERACTION)
            val navi = type(EcarxCapabilities.NAVI_INTERACTION)
            val laneArray = java.lang.reflect.Array.newInstance(type(EcarxCapabilities.LANE_INFO), 0).javaClass
            val input = type("com.ecarx.xui.adaptapi.input.Input")
            val keyCallback = type("com.ecarx.xui.adaptapi.input.IKeyCallback")
            val device = type(EcarxCapabilities.DEVICE)
            val dayNight = type("com.ecarx.xui.adaptapi.device.daynigntmode.IDayNightMode")
            val dayNightCallback = type(
                "com.ecarx.xui.adaptapi.device.daynigntmode.IDayNightMode\$IDayNightChangeCallBack",
            )
            val audio = type("com.ecarx.xui.adaptapi.audio.audiofx.Audio")

            val missing = mutableListOf<String>()
            fun expect(owner: Class<*>, name: String, vararg params: Class<*>) {
                if (runCatching { owner.getMethod(name, *params) }.isFailure) {
                    missing += "${owner.name}.$name(${params.joinToString { it.simpleName }})"
                }
            }
            // EcarxDimApi
            expect(dim, "create", Context::class.java)
            expect(dim, "getNaviInteraction")
            expect(dim, "getShowPresentationOption")
            expect(dim, "setPresentationToDimSwitch", int, string, string, boolean)
            expect(dim, "registerInteractionCallback", type(EcarxCapabilities.DIM_CALLBACK))
            expect(navi, "registerNavigationInteractionCallback", type(EcarxCapabilities.NAVI_CALLBACK))
            expect(navi, "updateNavigationInfo", type(EcarxCapabilities.NAVI_INFO))
            expect(navi, "updateTurnByTurnArrow", int)
            expect(navi, "updateTurnByTurnArrow", string)
            expect(navi, "updateTurnByTurnArrow", Bitmap::class.java)
            expect(navi, "updateDistanceToNextGuidancePoint", int)
            expect(navi, "updateDistanceToDestination", int)
            expect(navi, "updateETA", int)
            expect(navi, "updateNextGuidancePointName", string)
            expect(navi, "updateAddresses", int, List::class.java)
            expect(navi, "updateLaneInfo", laneArray)
            expect(navi, "updateMuteState", int)
            expect(navi, "notifyTurnByTurnStarted")
            expect(navi, "notifyTurnByTurnStopped")
            expect(navi, "notifyNavigationStatus", int)
            // EcarxSwKeyIntercept
            expect(input, "create", Context::class.java)
            expect(input, "requestKeysInterception", IntArray::class.java, keyCallback)
            expect(input, "abandonKeysInterception", keyCallback)
            // EcarxVehicleDayNight
            expect(device, "create", Context::class.java)
            expect(device, "getDayNightMode")
            expect(dayNight, "getDayNight")
            expect(dayNight, "getDayNightMode")
            expect(dayNight, "registerDayNightModeCallBack", dayNightCallback)
            expect(dayNight, "unregisterDayNigntModeCallBack", dayNightCallback)
            // AudioDiagnosticSnapshot
            expect(audio, "create", Context::class.java)
            expect(audio, "getAudioState")
            expect(type("com.ecarx.xui.adaptapi.audio.audiofx.IAudioState"), "getSoundStageOptimizedSeat")
            // ECARX VehicleState: read/write and observer cleanup on the actual 753 ABI.
            val car = type(EcarxVehicleState.CAR)
            val vehicle = type("com.ecarx.xui.adaptapi.car.ICar")
            val functions = type(EcarxVehicleState.FUNCTION)
            val sensor = type(EcarxVehicleState.SENSOR)
            val watcher = type("${EcarxVehicleState.FUNCTION}\$IFunctionValueWatcher")
            val sensorListener = type("${EcarxVehicleState.SENSOR}\$ISensorListener")
            val connect = type(EcarxVehicleState.CONNECT)
            expect(car, "create", Context::class.java)
            expect(vehicle, "getICarFunction")
            expect(vehicle, "getSensorManager")
            expect(functions, "isFunctionSupported", int, int)
            expect(functions, "getFunctionValue", int, int)
            expect(functions, "getCustomizeFunctionValue", int, int)
            expect(functions, "getSupportedFunctionValue", int)
            expect(functions, "setFunctionValue", int, int)
            expect(functions, "setFunctionValue", int, int, int)
            expect(functions, "setCustomizeFunctionValue", int, int, Float::class.javaPrimitiveType!!)
            expect(functions, "registerFunctionValueWatcher", IntArray::class.java, watcher)
            expect(functions, "unregisterFunctionValueWatcher", watcher)
            expect(sensor, "isSensorSupported", int)
            expect(sensor, "getSensorLatestValue", int)
            expect(sensor, "registerListener", sensorListener, int, int)
            expect(sensor, "unregisterListener", sensorListener)
            expect(connect, "registerConnectWatcher", type("${EcarxVehicleState.CONNECT}\$IConnectWatcher"))
            expect(connect, "unregisterConnectWatcher")
            val androidCar = type("android.car.Car")
            val properties = type("android.car.hardware.property.CarPropertyManager")
            val propertyListener = type("android.car.hardware.property.CarPropertyManager\$CarPropertyEventListener")
            expect(androidCar, "createCar", Context::class.java, ServiceConnection::class.java, Handler::class.java)
            expect(androidCar, "getCarManager", string)
            expect(androidCar, "connect")
            expect(androidCar, "disconnect")
            expect(properties, "getProperty", Class::class.java, int, int)
            expect(properties, "registerListener", propertyListener, int, Float::class.javaPrimitiveType!!)
            expect(properties, "unregisterListener", propertyListener)
            expect(type("android.car.hardware.CarPropertyValue"), "getStatus")
            expect(type("android.car.hardware.CarPropertyValue"), "getValue")
            val carAudio = type("android.car.media.CarAudioManager")
            for (method in listOf("getVolumeGroupIdForUsage", "getGroupMinVolume", "getGroupMaxVolume", "getGroupVolume"))
                expect(carAudio, method, int)
            expect(carAudio, "setGroupVolume", int, int, int)
            val proto = type(EcarxVehicleState.PROTO)
            expect(proto, "parseFrom", ByteArray::class.java)
            for (method in listOf("getAvailability", "hasDoubleData", "getDoubleData")) expect(proto, method)
            assertEquals("methods missing from the vendor JAR", emptyList<String>(), missing)
            assertEquals(0x10060500, type("com.ecarx.xui.adaptapi.car.hvac.IHvac").getField("HVAC_FUNC_TEMP_DUAL").getInt(null))
            // Exercise the real SDK parser, not our fixture, with the tire signal's wire shape.
            val bytes = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN).put(8).put(1).put(41).putDouble(235.0).array()
            val parsed = proto.getMethod("parseFrom", ByteArray::class.java).invoke(null, bytes)
            assertEquals(true, proto.getMethod("hasDoubleData").invoke(parsed))
            assertEquals(235.0, proto.getMethod("getDoubleData").invoke(parsed))
            val availability = proto.getMethod("getAvailability").invoke(parsed)
            assertEquals(1, availability.javaClass.getMethod("getNumber").invoke(availability))

            // The data proxies name every getter on purpose; anything else gets an empty value.
            fun getters(name: String) = type(name).methods.map { it.name }.toSortedSet()
            assertEquals(
                sortedSetOf(
                    "getDayNightMode", "getDistanceToDestination", "getDistanceToNextGuidancePoint",
                    "getDrivingDirection", "getETA", "getExtensionInfo", "getHighwayExitInfo", "getLaneInfo",
                    "getMuteState", "getNavigationStatus", "getNavigationTurnId", "getNavigationTurnSVG",
                    "getNextGuidancePointName", "getRoadCameraInfo", "getServiceAreaInfo",
                ),
                getters(EcarxCapabilities.NAVI_INFO),
            )
            assertEquals(
                sortedSetOf("getLaneBackIcon", "getLaneBackIconId", "getLaneNumber"),
                getters(EcarxCapabilities.LANE_INFO),
            )
            assertEquals(
                sortedSetOf("getAddressName", "getCityName", "getDistance", "getFullAddress", "getLatitude", "getLongitude"),
                getters("${EcarxCapabilities.NAVI_INTERACTION}\$IAddress"),
            )
            val ints = listOf(
                "TYPE_DESTINATION" to 3, "NAVIGATION_STATUS_START" to 2, "NAVIGATION_STATUS_END" to 3,
            )
            for ((field, value) in ints) assertEquals(field, value, navi.getField(field).getInt(null))
        }
    }
}
