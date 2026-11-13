package com.shihab.diplay

import com.shihab.diplay.orchestration.CarPlayController
import org.robolectric.util.ReflectionHelpers

/** Exercise private run ownership without exporting a production test API across modules. */
internal fun CarPlayController.wirelessRunForTest(): Any =
    ReflectionHelpers.getField<Any?>(this, "wirelessRun") ?: replaceWirelessRunForTest()

internal fun CarPlayController.replaceWirelessRunForTest(): Any {
    ReflectionHelpers.getField<Any?>(this, "wirelessRun")?.let {
        it.javaClass.getDeclaredMethod("retire").apply { isAccessible = true }.invoke(it)
    }
    val generation = controllerCoreCallForTest<Int>("beginWireless")
    val type = javaClass.declaredClasses.single { it.simpleName == "WirelessRun" }
    val run = type.getDeclaredConstructor(CarPlayController::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(this, generation)
    ReflectionHelpers.setField(this, "wirelessRun", run)
    return run
}

internal fun <T> CarPlayController.controllerCoreCallForTest(method: String): T =
    ReflectionHelpers.callInstanceMethod(ReflectionHelpers.getField<Any>(this, "lifecycle"), method)

internal fun CarPlayController.wirelessGenerationForTest(): Int = controllerCoreCallForTest("getWirelessGeneration")

internal fun CarPlayController.wirelessStateCallForTest(method: String): Any? {
    val state = ReflectionHelpers.getField<Any>(wirelessRunForTest(), "state")
    return state.javaClass.getDeclaredMethod(method).invoke(state)
}

internal fun CarPlayController.resetWirelessStateForTest() {
    val state = ReflectionHelpers.getField<Any>(wirelessRunForTest(), "state")
    ReflectionHelpers.setField(wirelessRunForTest(), "state", state.javaClass.getDeclaredConstructor().newInstance())
}
