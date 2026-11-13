package com.shihab.diplay

import com.shihab.diplay.hud.VehicleNightSource
import java.util.Calendar

/** Stable preference values; never persist enum ordinals. */
enum class CarPlayNightMode(val key: String) {
    SYSTEM("system"),
    AMBIENT("ambient"),
    VEHICLE("vehicle"),
    DAY("day"),
    NIGHT("night"),
    SCHEDULE("schedule");

    companion object {
        fun fromKey(key: String?): CarPlayNightMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * The choices Settings offers, in display order. ECARX head units lock Android's own theme (the
 * target reports `ui_night_mode=2`, locked), so following Android is not offered there.
 */
internal fun carPlayNightModeChoices(ecarx: Boolean): List<CarPlayNightMode> = buildList {
    if (!ecarx) add(CarPlayNightMode.SYSTEM)
    addAll(listOf(CarPlayNightMode.VEHICLE, CarPlayNightMode.AMBIENT, CarPlayNightMode.SCHEDULE,
        CarPlayNightMode.DAY, CarPlayNightMode.NIGHT))
}

/** On ECARX, a saved or default "follow Android" means following the head unit. */
internal fun effectiveCarPlayNightMode(savedKey: String?, ecarx: Boolean): CarPlayNightMode {
    val mode = CarPlayNightMode.fromKey(savedKey)
    return if (ecarx && mode == CarPlayNightMode.SYSTEM) CarPlayNightMode.VEHICLE else mode
}

data class CarPlayNightSchedule(val startMinute: Int = 18 * 60, val endMinute: Int = 6 * 60) {
    init {
        require(startMinute in 0 until 24 * 60 && endMinute in 0 until 24 * 60)
    }

    fun isNight(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }
}

internal data class NightTimeSnapshot(val minuteOfDay: Int, val millisUntilNextMinute: Long)

internal interface NightTimeSource {
    fun snapshot(): NightTimeSnapshot
}

internal object SystemNightTimeSource : NightTimeSource {
    override fun snapshot(): NightTimeSnapshot = Calendar.getInstance().let {
        // Appearance and the next tick must use the same instant across a minute boundary.
        NightTimeSnapshot(
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE),
            60_000L - it.get(Calendar.SECOND) * 1_000L - it.get(Calendar.MILLISECOND),
        )
    }
}

/**
 * Main-thread controller. Only its output is sent to CarPlay; Android theme is never changed.
 *
 * [CarPlayNightMode.VEHICLE] follows the head unit's resolved decision at once; the head unit has
 * already debounced it. [CarPlayNightMode.AMBIENT] ("outside light") prefers the body [twilight]
 * sensor, which ignores the head unit's forced day or night setting, and applies the driver's delay
 * to every change after the first reading of each start. Without it, Android's light sensor and the
 * lux threshold are used. Until either follow mode has an answer, [recentNight] or the schedule is
 * the guess, never Android's theme.
 */
internal class CarPlayNightModeController(
    private val light: LightSource,
    private val scheduler: Scheduler,
    initialNight: Boolean,
    private val onNightChanged: (Boolean) -> Unit,
    private val timeSource: NightTimeSource = SystemNightTimeSource,
    private val vehicle: VehicleNightSource? = null,
    private val diagnostic: (String) -> Unit = {},
    private val twilight: VehicleNightSource? = null,
    /** A decision of a follow mode saved by an earlier process, when still recent. */
    private val recentNight: (CarPlayNightMode) -> Boolean? = { null },
    private val rememberNight: (CarPlayNightMode, Boolean) -> Unit = { _, _ -> },
) {
    interface LightSource {
        val available: Boolean
        fun start(onLux: (Float) -> Unit): Boolean
        fun stop()
    }

    interface Scheduler {
        fun postDelayed(task: Runnable, delayMillis: Long)
        fun remove(task: Runnable)
    }

    var night: Boolean = initialNight
        private set
    /** What decided the current [night], for the diagnostic report. */
    var reason: String = "initial"
        private set
    private var mode = CarPlayNightMode.SYSTEM
    private var systemNight = initialNight
    private var threshold = AmbientLightThreshold()
    private var delaySeconds = 2
    private var schedule = CarPlayNightSchedule()
    private var resumed = false
    private var listening = false
    private var listeningVehicle = false
    private var listeningTwilight = false
    /** True while the selected follow mode has no working source and follows Android instead. */
    private var onSystem = false
    // Decisions seen in this process; null until the source has answered once.
    private var vehicleNight: Boolean? = null
    private var outsideNight: Boolean? = null
    private var outsideAnsweredSinceStart = false
    private var loggedGuess: String? = null
    private var pending: Boolean? = null
    private val transition = Runnable {
        val target = pending
        pending = null
        if (target == null || !resumed || mode != CarPlayNightMode.AMBIENT) return@Runnable
        when {
            listeningTwilight -> applyNight(target, "outside-light")
            listening -> applyNight(target, "android-light")
        }
    }
    private val scheduleTick = object : Runnable {
        override fun run() {
            if (!resumed || mode != CarPlayNightMode.SCHEDULE) return
            applySchedule()
        }
    }

    /** Also applies a new choice while paused, from the last known values, so Settings takes effect at once. */
    fun configure(
        mode: CarPlayNightMode,
        systemNight: Boolean,
        threshold: AmbientLightThreshold = AmbientLightThreshold(),
        delaySeconds: Int = 2,
        schedule: CarPlayNightSchedule = CarPlayNightSchedule(),
    ) {
        stopListening()
        this.mode = mode
        this.systemNight = systemNight
        this.threshold = threshold
        this.delaySeconds = delaySeconds.coerceIn(0, 60)
        this.schedule = schedule
        applyMode()
    }

    fun resume(systemNight: Boolean) {
        this.systemNight = systemNight
        resumed = true
        applyMode()
    }

    fun pause() {
        resumed = false
        stopListening()
    }

    fun systemChanged(night: Boolean) {
        systemNight = night
        val followsSystem = mode == CarPlayNightMode.SYSTEM ||
            (onSystem && (mode == CarPlayNightMode.AMBIENT || mode == CarPlayNightMode.VEHICLE))
        if (followsSystem) applyNight(night, "system")
    }

    private fun applyMode() {
        onSystem = false
        when (mode) {
            CarPlayNightMode.SYSTEM -> applyNight(systemNight, "system")
            CarPlayNightMode.DAY -> applyNight(false, "fixed")
            CarPlayNightMode.NIGHT -> applyNight(true, "fixed")
            CarPlayNightMode.SCHEDULE -> applySchedule()
            CarPlayNightMode.AMBIENT -> applyOutsideLight()
            CarPlayNightMode.VEHICLE -> applyVehicle()
        }
    }

    private fun applyVehicle() {
        val source = vehicle
        if (source == null || !source.available) return followSystem("vehicle unavailable")
        vehicleNight?.let { applyNight(it, "vehicle") } ?: applyGuess(CarPlayNightMode.VEHICLE)
        if (!resumed || listeningVehicle) return
        listeningVehicle = source.start(::onVehicleNight)
        if (!listeningVehicle) {
            source.stop()
            followSystem("vehicle start failed")
        }
    }

    private fun applyOutsideLight() {
        val body = twilight
        if (body != null && body.available) {
            outsideNight?.let { applyNight(it, "outside-light") } ?: applyGuess(CarPlayNightMode.AMBIENT)
            if (!resumed || listeningTwilight) return
            outsideAnsweredSinceStart = false
            listeningTwilight = body.start(::onOutsideNight)
            if (listeningTwilight) {
                diagnostic("Outside light source=twilight delay=${delaySeconds}s")
                return
            }
            body.stop()
            diagnostic("Outside light twilight start failed")
        }
        if (light.available) {
            if (!resumed || listening) return
            listening = light.start(::onLux)
            if (listening) {
                diagnostic("Outside light source=android-sensor threshold=${threshold.lux}lux delay=${delaySeconds}s")
                return
            }
            light.stop()
        }
        followSystem("outside light unavailable")
    }

    private fun followSystem(why: String) {
        onSystem = true
        if (resumed) diagnostic("Night mode setting=${mode.key} follows Android: $why")
        applyNight(systemNight, "system")
    }

    /** Until the source answers: a recent saved decision, else the schedule, never Android's theme. */
    private fun applyGuess(mode: CarPlayNightMode) {
        val recent = recentNight(mode)
        val basis = if (recent != null) "recent" else "schedule"
        val guess = recent ?: schedule.isNight(timeSource.snapshot().minuteOfDay)
        val summary = "setting=${mode.key} basis=$basis night=$guess"
        if (summary != loggedGuess) diagnostic("Night guess $summary")
        loggedGuess = summary
        applyNight(guess, "guess-$basis")
    }

    /** Vehicle deliveries arrive on the main thread and only on real head-unit decisions. */
    private fun onVehicleNight(target: Boolean) {
        if (!resumed || !listeningVehicle || mode != CarPlayNightMode.VEHICLE) return
        vehicleNight = target
        rememberNight(CarPlayNightMode.VEHICLE, target)
        applyNight(target, "vehicle")
    }

    /** The first reading of a start replaces the guess at once; later changes wait for the delay. */
    private fun onOutsideNight(target: Boolean) {
        if (!resumed || !listeningTwilight || mode != CarPlayNightMode.AMBIENT) return
        outsideNight = target
        rememberNight(CarPlayNightMode.AMBIENT, target)
        if (!outsideAnsweredSinceStart) {
            outsideAnsweredSinceStart = true
            cancelPending()
            applyNight(target, "outside-light")
            return
        }
        if (target == night) {
            requestTransition(null)
            return
        }
        if (target != pending) diagnostic("Outside light change night=$target delay=${delaySeconds}s")
        requestTransition(target)
    }

    private fun onLux(lux: Float) {
        if (!resumed || !listening || mode != CarPlayNightMode.AMBIENT) return
        val target = when {
            !lux.isFinite() || lux < 0f -> null
            !night && lux < threshold.lux.toFloat() -> true
            night && lux >= threshold.lux.toFloat() -> false
            else -> null
        }
        requestTransition(target)
    }

    /** Arms [target] behind the delay; null cancels an armed transition without applying it. */
    private fun requestTransition(target: Boolean?) {
        if (target == pending) return
        cancelPending()
        pending = target
        // TYPE_LIGHT is commonly on-change: a stable reading need not emit again.
        if (target != null) {
            if (delaySeconds == 0) transition.run()
            else scheduler.postDelayed(transition, delaySeconds * 1_000L)
        }
    }

    private fun stopListening() {
        if (listening) light.stop()
        listening = false
        if (listeningVehicle) vehicle?.stop()
        listeningVehicle = false
        if (listeningTwilight) twilight?.stop()
        listeningTwilight = false
        cancelPending()
        scheduler.remove(scheduleTick)
    }

    private fun applySchedule() {
        val time = timeSource.snapshot()
        applyNight(schedule.isNight(time.minuteOfDay), "schedule")
        if (!resumed || mode != CarPlayNightMode.SCHEDULE) return
        scheduler.remove(scheduleTick)
        scheduler.postDelayed(scheduleTick, time.millisUntilNextMinute.coerceIn(1, 60_000))
    }

    private fun cancelPending() {
        scheduler.remove(transition)
        pending = null
    }

    private fun applyNight(value: Boolean, why: String) {
        if (value == night) return
        night = value
        reason = why
        onNightChanged(value)
    }
}
