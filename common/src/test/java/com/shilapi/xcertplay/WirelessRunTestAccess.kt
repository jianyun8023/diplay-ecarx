package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.util.ReflectionHelpers

/** Exercise private run ownership without exporting a production test API across modules. */
internal fun CarPlayController.wirelessRunForTest(): Any =
    ReflectionHelpers.getField<Any?>(this, "wirelessRun") ?: replaceWirelessRunForTest(0)

internal fun CarPlayController.replaceWirelessRunForTest(generation: Int): Any {
    val type = javaClass.declaredClasses.single { it.simpleName == "WirelessRun" }
    val run = type.getDeclaredConstructor(CarPlayController::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(this, generation)
    ReflectionHelpers.setField(this, "wirelessRun", run)
    ReflectionHelpers.getField<AtomicInteger>(this, "wirelessGeneration").set(generation)
    val phase = javaClass.getDeclaredField("phase").apply { isAccessible = true }
    phase.set(this, requireNotNull(phase.type.enumConstants).single { (it as Enum<*>).name == "WIRELESS" })
    return run
}
