package com.shilapi.xcertplay.airplay

import kotlin.math.sin
import kotlin.math.cos

/** Bench-only source shared by the local cluster and the iPhone vehicle-state channel. */
class SimulatedVehicle(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    enum class Scene { PARK, CITY, HIGHWAY, WARNINGS }
    data class Reading(
        val speed: Double = 0.0, val rpm: Int = 0, val gear: String = "P",
        val fuel: Int = 78, val range: Int = 610, val ambient: Int = 7,
        val coolant: Int = 88, val oil: Int = 92, val odometer: Int = 23861,
        val trip: Double = 36.8, val pressures: List<Int> = listOf(240, 242, 238, 241),
        val headlights: Boolean = true, val highBeam: Boolean = false,
        val leftTurn: Boolean = false, val rightTurn: Boolean = false,
        val wipers: Boolean = false, val autoHold: Boolean = true,
        val parkingBrake: Boolean = true, val cruise: Boolean = false,
        val cruiseSpeed: Int = 60, val following: Int = 3, val laneAssist: Boolean = true,
        val doorOpen: Boolean = false, val seatbelt: Boolean = true,
        val engineWarning: Boolean = false, val driveMode: String = "舒适",
        val leftTemperature: Double = 22.0, val rightTemperature: Double = 23.0,
        val fan: Int = 3, val hvac: Boolean = true, val ac: Boolean = true,
        val autoClimate: Boolean = true, val recirculation: Boolean = false,
        val syncTemperature: Boolean = false, val upperVent: Boolean = false,
        val middleVent: Boolean = true, val lowerVent: Boolean = false,
        val defrost: Boolean = false, val rearDefrost: Boolean = false,
        val seatHeating: Int = 0, val passengerHeating: Int = 0,
        val radioKhz: Int = 101700, val radioPlaying: Boolean = false,
        val volume: Int = 35, val navVolume: Int = 45, val callVolume: Int = 50,
        val musicVolume: Int = 35, val siriVolume: Int = 45, val muted: Boolean = false,
        val simulatedCall: Boolean = false,
    )

    private var base = Reading()
    private var scene = Scene.PARK
    private var started = clock()
    private var animate = false
    @Synchronized fun scene(): Scene = scene

    @Synchronized fun select(value: Scene) {
        scene = value
        started = clock()
        animate = value == Scene.CITY || value == Scene.HIGHWAY
        base = base.copy(speed = 0.0, rpm = 0, gear = "P", fuel = 78, range = 610,
            coolant = 88, pressures = listOf(240, 242, 238, 241), doorOpen = false,
            seatbelt = true, engineWarning = false, autoHold = value == Scene.PARK,
            parkingBrake = value == Scene.PARK, cruise = value == Scene.HIGHWAY,
            cruiseSpeed = if (value == Scene.HIGHWAY) 100 else 60, leftTurn = false, rightTurn = false,
            driveMode = if (value == Scene.HIGHWAY) "运动" else "舒适")
        if (value == Scene.WARNINGS) base = base.copy(fuel = 8, range = 48, coolant = 112,
            pressures = listOf(165, 242, 238, 241), doorOpen = true, seatbelt = false,
            engineWarning = true, leftTurn = true, rightTurn = true)
    }

    @Synchronized fun reading(): Reading {
        if (!animate) return base
        val seconds = (clock() - started) / 1000.0
        val highway = scene == Scene.HIGHWAY
        val mean = if (highway) 100.0 else 42.0
        val amplitude = if (highway) 8.0 else 18.0
        val period = if (highway) 8.0 else 6.0
        val speed = mean + amplitude * sin(seconds / period)
        val distance = (mean * seconds + amplitude * period * (1 - cos(seconds / period))) / 3600
        return base.copy(speed = speed, rpm = (1100 + speed * 24).toInt(), gear = "D",
            trip = base.trip + distance, odometer = base.odometer + distance.toInt())
    }

    /** UI values are typed; protocol writes are checked against their declared ranges at that boundary. */
    @Synchronized fun change(update: (Reading) -> Reading) {
        val next = update(base)
        base = if (next.syncTemperature) next.copy(rightTemperature = next.leftTemperature) else next
    }

    @Synchronized fun speed(kph: Double) {
        require(kph.isFinite() && kph in 0.0..240.0)
        base = reading().copy(speed = kph, rpm = if (kph == 0.0) 0 else (1000 + kph * 24).toInt(),
            gear = if (kph == 0.0) "P" else "D", parkingBrake = kph == 0.0, autoHold = kph == 0.0)
        animate = false
    }
}
