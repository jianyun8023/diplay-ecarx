package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Lynk port (fix9): during a CarPlay session, take the head unit's own Bluetooth audio path away
 * from the iPhone — media and calls only, adapter stays on.
 *
 * The iPhone keeps the head unit on its list of audio outputs for as long as the Bluetooth link to
 * it is up, so iOS hands music and calls to the car's A2DP/HFP profiles instead of to CarPlay, and
 * the car's media app answers with an AVRCP `pause` — the reported "music stops the moment it
 * starts" fault. The owner's manual workaround is to turn the phone's Bluetooth off, or to untick
 * "Media audio" on the head unit's paired-device screen; there is no ADB on this head unit, so the
 * app has to do it.
 *
 * `CarBluetoothYielder` (the earlier attempt, rolled back) only called `disconnect()`, which is a
 * one-shot: the head unit reconnects the profile and a throttled re-drop loop follows. This version
 * leads with `setConnectionPolicy(device, FORBIDDEN)`, the same thing the car's own checkbox writes
 * — it is persistent, so the profiles stay down without a fight — and keeps `disconnect()` as the
 * immediate effect plus a throttled re-assert as a belt-and-braces path.
 *
 * Profile-level yield never took effect on this head unit (see reachability below), so
 * `setConnectionPolicy(FORBIDDEN)` was never reached and the switch that drove it was removed in
 * fix16. The profile code is kept only so `restoreNow` can release the state an older build may
 * still have left behind; [adapterEnabled] is now the only yield path, and it must wake the adapter
 * before any reconnect, because wireless CarPlay's iAP2 handshake and its reconnects run over an
 * RFCOMM channel of that same adapter.
 *
 * Reachability is the open question on this ROM: the earlier attempt logged
 * `a2dp=timeout hfp=timeout` — `getProfileProxy` answered `true` and `onServiceConnected` never
 * arrived. So each profile is acquired through two independent paths and the field log says which
 * one answered:
 *
 *  1. `proxy`  — the public `BluetoothAdapter.getProfileProxy`, then reflection into
 *                `BluetoothA2dp`/`BluetoothHeadset` (`setConnectionPolicy`, `setPriority`,
 *                `disconnect`, `connect`). These are `@SystemApi`, so they are absent from the
 *                public SDK but present on the device; the install is platform-signed, which is
 *                what `BLUETOOTH_PRIVILEGED` needs.
 *  2. `binder` — `ServiceManager` → `IBluetoothA2dp` / `IBluetoothHeadset`, skipping the callback
 *                mechanism entirely. Used when path 1 times out.
 *
 * One line per session — `BT guard link: services a2dp=proxy hfp=binder` — is enough to tell the
 * two apart in the exported log.
 */
public object CarBluetoothProfileGuard {

    private const val TAG = "DiPlay-BT-Yield"
    private const val PREFS = "diplay_bt_profile_guard"
    private const val KEY_ADAPTER_ENABLED = "yield_adapter"
    private const val KEY_ADAPTER_DELAY_SECONDS = "adapter_delay_seconds"

    /** The adapter-off fallback delay, in seconds. The settings stepper writes it. */
    const val ADAPTER_SECONDS_MIN = 1
    const val ADAPTER_SECONDS_MAX = 10
    const val ADAPTER_SECONDS_DEFAULT = 3
    private const val KEY_APPLIED = "applied"
    private const val KEY_APPLIED_MODE = "applied_mode"
    private const val KEY_APPLIED_TARGETS = "applied_targets"
    private const val MODE_LINK = "link"
    private const val MODE_ADAPTER = "adapter"

    /**
     * Let the handshake settle before touching the profiles.
     *
     * Adapter mode used to wait 10 s, on the theory that switching the whole adapter off too early
     * would break the wireless handshake that runs over its RFCOMM channel. The field log settled
     * that: `arm` fires 0.12 s *after* `session established`, and a session switched off this way
     * then ran minutes at 35 fps with audio and iAP2 traffic intact.
     *
     * What the delay *does* cost is the owner's music: iOS restores its audio within a second of
     * CarPlay coming up, and while the adapter is still on iOS hands that audio to the Bluetooth
     * link instead of to CarPlay — the reported "it starts playing and stops right away".
     *
     * fix15: a 500 ms adapter delay is not safe either, and the field log showed why — it landed in
     * the middle of the iAP2 handshake, whose natural completion takes about 1.5 s, so the adapter
     * went off with the handshake half-finished, the controller judged that session failed, tore it
     * down and retried forever. This delay therefore only stands as the fallback now: the normal
     * path is [yieldNow], fired the instant the wireless handoff completes, and this timer only
     * covers a handoff that never reports back.
     *
     * fix16: how long that fallback waits is the user's to set (1–10 s, default 3). Set it below the
     * handoff's ~1.5 s and the adapter goes off at the chosen value instead of on handoff
     * completion — deliberate, because a bootstrap channel closed during the handoff is no longer
     * judged a failure.
     */
    private const val LINK_DELAY_MILLIS = 5_000L
    private const val REASSERT_PERIOD_MILLIS = 30_000L
    private const val REDROP_THROTTLE_MILLIS = 3_000L
    private const val PROFILE_TIMEOUT_SECONDS = 5L

    /** `BluetoothProfile.CONNECTION_POLICY_*`; `setPriority`'s `PRIORITY_OFF`/`ON` share the values. */
    private const val POLICY_FORBIDDEN = 0
    private const val POLICY_ALLOWED = 100

    private const val A2DP = BluetoothProfile.A2DP
    private const val HEADSET = BluetoothProfile.HEADSET

    /** `BluetoothProfile.ACTION_CONNECTION_STATE_CHANGED`: the generic (system API) sibling. */
    private const val ACTION_PROFILE_STATE = "android.bluetooth.profile.action.CONNECTION_STATE_CHANGED"

    private val logcat: (String) -> Unit = { Log.i(TAG, it) }
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "diplay-bt-guard").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var session: Session? = null
    private var pending: ScheduledFuture<*>? = null
    private var reassert: ScheduledFuture<*>? = null
    private var watcher: BroadcastReceiver? = null
    private var lastDropAt = 0L
    private var recoveryPending = false
    private var ownershipRevision = 0L

    /** One profile service, whichever way it was reached. `profile == null` means "unreachable". */
    private class Handle(val label: String, val profileId: Int, val source: String, val profile: Any?)

    private class Session(
        val context: Context,
        val adapterMode: Boolean,
        val peerAddress: String?,
        val log: (String) -> Unit,
    ) {
        var handles: List<Handle>? = null
        var targets: List<BluetoothDevice> = emptyList()
    }

    // ------------------------------------------------------------------ settings

    /** Adapter-off mode. It kills the wireless handshake while it holds, so it stays opt-in. */
    fun adapterEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ADAPTER_ENABLED, false)

    fun saveAdapterEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ADAPTER_ENABLED, enabled).apply()
    }

    fun adapterDelaySeconds(context: Context): Int =
        preferences(context).getInt(KEY_ADAPTER_DELAY_SECONDS, ADAPTER_SECONDS_DEFAULT)
            .coerceIn(ADAPTER_SECONDS_MIN, ADAPTER_SECONDS_MAX)

    fun saveAdapterDelaySeconds(context: Context, seconds: Int) {
        preferences(context).edit()
            .putInt(KEY_ADAPTER_DELAY_SECONDS, seconds.coerceIn(ADAPTER_SECONDS_MIN, ADAPTER_SECONDS_MAX))
            .apply()
    }

    /**
     * True while the head unit's Bluetooth is off because this app turned it off for a CarPlay
     * session. Wireless startup turns it back on before the handshake, so this must not be
     * reported to the driver as "Bluetooth is off in the head unit's settings".
     */
    fun pausedByUs(context: Context): Boolean {
        if (!applied(context)) return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        return adapter?.isEnabled == false
    }

    /** Called when a CarPlay session is live. Returns at once; the drop runs on the guard's thread. */
    fun yieldForSession(context: Context, peerAddress: String?, log: (String) -> Unit = logcat) {
        val app = context.applicationContext
        val adapterMode = adapterEnabled(app)
        if (!adapterMode) {
            log("BT guard: the adapter switch is off; the head unit keeps its Bluetooth to itself")
            return
        }
        val state = Session(app, adapterMode, peerAddress, log)
        val delay = if (adapterMode) adapterDelaySeconds(app) * 1_000L else LINK_DELAY_MILLIS
        synchronized(lock) {
            pending?.cancel(false)
            ownershipRevision++
            recoveryPending = false
            session = state
            pending = worker.schedule({ apply(state) }, delay, TimeUnit.MILLISECONDS)
        }
        log(
            "BT guard armed mode=${if (adapterMode) MODE_ADAPTER else MODE_LINK} delayMs=$delay " +
                "peer=${peerAddress ?: "unknown"}",
        )
    }

    /**
     * Wireless handoff completed, so the bootstrap RFCOMM channel is already closed and the
     * adapter is no longer carrying anything this app needs: apply the yield now instead of
     * waiting out [adapterDelaySeconds]. Cancels the fallback timer so the drop happens once.
     */
    fun yieldNow() {
        val state = synchronized(lock) {
            val armed = session ?: return
            pending?.cancel(false)
            pending = null
            armed
        }
        worker.execute { apply(state) }
    }

    /** Puts the profiles back on the way out. Safe (and silent) when nothing was applied. */
    fun restore(context: Context, log: (String) -> Unit = logcat) {
        val app = context.applicationContext
        val (state, revision) = synchronized(lock) {
            ownershipRevision++
            pending?.cancel(false)
            pending = null
            reassert?.cancel(false)
            reassert = null
            session.also { session = null } to ownershipRevision
        }
        unregisterWatcher(app)
        if (state == null && !applied(app)) {
            synchronized(lock) { if (ownershipRevision == revision) recoveryPending = false }
            return
        }
        // Queued on the guard's thread so the decision cannot race a drop that is still in flight.
        worker.execute {
            try {
                restoreNow(app, state, revision, state?.log ?: log)
            } finally {
                synchronized(lock) { if (ownershipRevision == revision) recoveryPending = false }
            }
        }
    }

    /**
     * A previous process can die while the profiles are forbidden, and the policy persists in the
     * head unit's Bluetooth configuration. Startup calls this so the next run puts them back.
     *
     * Coalesce concurrent recovery requests, but allow another attempt after a timeout or refusal.
     * A live session still owns the state and restores it on the way out, so it is left alone.
     */
    fun recoverIfStale(context: Context, log: (String) -> Unit = logcat) {
        val app = context.applicationContext
        if (!applied(app)) return
        synchronized(lock) {
            if (session != null) return
            if (recoveryPending) return
            recoveryPending = true
        }
        log("BT guard: a previous run left Bluetooth recovery pending; restoring")
        restore(app, log)
    }

    /**
     * Wireless CarPlay's iAP2 handshake and its reconnects run over an RFCOMM channel of the head
     * unit's own adapter, so a reconnect that starts while adapter mode has it switched off cannot
     * succeed — that is the reported "the first reconnect fails, the second one works" pattern.
     * Called before a wireless connect (never for USB) so the adapter is asked for again instead of
     * relying on the session-end restore having won the race.
     *
     * @return the line to log, or null when the adapter was already on and nothing was done.
     */
    fun ensureAdapterOn(context: Context): String? {
        val app = context.applicationContext
        val adapter = adapter(app) ?: return "BT guard connect: no Bluetooth adapter on this head unit"
        if (adapter.isEnabled) return null
        val accepted = runCatching { adapter.enable() }.getOrDefault(false)
        return "BT guard connect: head unit Bluetooth was off; switching it back on accepted=$accepted"
    }

    /**
     * Wireless worker gate. Only an adapter paused by this guard may be enabled by restore().
     * Observe the asynchronous restore even while it is still queued and the radio remains OFF.
     * No hotspot or RFCOMM work may continue merely because enable() was accepted.
     */
    fun requireAdapterReadyForConnection(
        context: Context,
        isCurrent: () -> Boolean,
        log: (String) -> Unit = logcat,
    ) {
        if (!isCurrent()) throw java.io.InterruptedIOException("Bluetooth startup cancelled")
        val app = context.applicationContext
        val radio = adapter(app) ?: throw com.shilapi.xcertplay.transport.BluetoothRadioCheck.failure(
            com.shilapi.xcertplay.transport.BluetoothRadioCheck.NO_ADAPTER)
        val recoveryNeeded = applied(app)
        val ownedPause = recoveryNeeded && appliedMode(app) == MODE_ADAPTER
        if (recoveryNeeded) restore(app, log)
        val outcome = try {
            if (!ownedPause && radio.state != BluetoothAdapter.STATE_ON &&
                radio.state != BluetoothAdapter.STATE_TURNING_ON) {
                BluetoothAdapterRestore.Outcome.REJECTED
            } else {
                BluetoothAdapterRestore.awaitOn(
                    state = { radio.state },
                    requestEnable = null, // The guard worker alone issues any authorized enable.
                    isCurrent = isCurrent,
                )
            }
        } catch (error: RuntimeException) {
            log("BT guard connect: state verification failed ${error.javaClass.simpleName}")
            throw java.io.IOException("Bluetooth state could not be verified", error)
        }
        if (!isCurrent() || outcome == BluetoothAdapterRestore.Outcome.CANCELLED) {
            throw java.io.InterruptedIOException("Bluetooth startup cancelled")
        }
        if (outcome != BluetoothAdapterRestore.Outcome.ON) {
            log("BT guard connect: STATE_ON not confirmed outcome=$outcome ownedPause=$ownedPause")
            throw com.shilapi.xcertplay.transport.BluetoothRadioCheck.failure(
                com.shilapi.xcertplay.transport.BluetoothRadioCheck.RADIO_OFF)
        }
    }

    /** A switch moved. Re-applies inside a live session, undoes it when the switch is off. */
    fun preferenceChanged(
        context: Context,
        sessionActive: Boolean,
        peerAddress: String?,
        log: (String) -> Unit = logcat,
    ) {
        val app = context.applicationContext
        if (!adapterEnabled(app)) {
            restore(app, log)
            return
        }
        if (!sessionActive) {
            log("BT guard: saved; it applies to the next CarPlay session")
            return
        }
        restore(app, log)
        yieldForSession(app, peerAddress, log)
    }

    // ------------------------------------------------------------------ the drop

    private fun apply(state: Session) {
        val adapter = adapter(state.context)
        if (adapter == null) {
            state.log("BT guard: no Bluetooth adapter on this head unit")
            return
        }
        if (!adapter.isEnabled) {
            state.log("BT guard: head unit Bluetooth is already off; nothing to change")
            return
        }
        if (state.adapterMode) {
            synchronized(lock) {
                if (session !== state) return
                applyAdapterMode(state, adapter)
            }
            return
        }
        val targets = targets(state, adapter)
        state.targets = targets
        if (targets.isEmpty()) {
            state.log("BT guard link: no paired iPhone to drop; the head unit keeps its Bluetooth")
            return
        }
        val handles = acquireHandles(state.context, adapter, state.log)
        state.handles = handles
        state.log("BT guard link: services " + handles.joinToString(" ") { "${it.label}=${it.source}" })
        drop(state, handles, targets, "first drop", verbose = true)
        armReassert(state)
    }

    @Suppress("DEPRECATION")
    private fun applyAdapterMode(state: Session, adapter: BluetoothAdapter) {
        val accepted = try {
            adapter.disable()
        } catch (error: SecurityException) {
            state.log("BT guard adapter: the ROM refused to switch Bluetooth off")
            return
        } catch (error: RuntimeException) {
            state.log("BT guard adapter: switching Bluetooth off failed ${error.javaClass.simpleName}")
            return
        }
        if (accepted) markApplied(state.context, MODE_ADAPTER, emptyList())
        state.log("BT guard adapter: head unit Bluetooth off requested accepted=$accepted")
    }

    /**
     * Re-asserts the forbidden policy for [targets] and disconnects whatever is still up. Runs on
     * the first drop, on every profile state broadcast, and once per [REASSERT_PERIOD_MILLIS] —
     * a ROM that does not deliver the broadcasts still converges through the timer.
     */
    private fun drop(
        state: Session,
        handles: List<Handle>,
        targets: List<BluetoothDevice>,
        note: String,
        verbose: Boolean,
    ) {
        lastDropAt = SystemClock.elapsedRealtime()
        val line = StringBuilder("BT guard link: ").append(note)
        var dropped = 0
        for (device in targets) {
            line.append(' ').append(device.address)
            for (handle in handles) {
                val policy = applyPolicy(handle, device, POLICY_FORBIDDEN)
                val disconnect = callOnDevice(handle.profile, "disconnect", device)
                if (disconnect == "true") dropped++
                if (verbose) {
                    line.append(' ').append(handle.label).append('(').append(handle.source)
                        .append(") policy=").append(policy).append(" disconnect=").append(disconnect)
                }
            }
        }
        if (verbose) {
            markApplied(state.context, MODE_LINK, targets.map { it.address })
            state.log(line.toString())
        } else if (dropped > 0) {
            state.log(line.append(" dropped=").append(dropped).toString())
        }
    }

    private fun reassert(reason: String?) {
        val state = synchronized(lock) { session } ?: return
        if (state.adapterMode) return
        if (SystemClock.elapsedRealtime() - lastDropAt < REDROP_THROTTLE_MILLIS) return
        val adapter = adapter(state.context) ?: return
        if (!adapter.isEnabled) return
        var handles = state.handles
        if (handles == null || handles.all { it.profile == null }) {
            handles = acquireHandles(state.context, adapter, state.log)
            state.handles = handles
            state.log("BT guard link: services " + handles.joinToString(" ") { "${it.label}=${it.source}" })
        }
        val targets = state.targets.ifEmpty { targets(state, adapter).also { state.targets = it } }
        if (targets.isEmpty()) return
        drop(state, handles, targets, reason ?: "re-assert", verbose = reason != null)
    }

    private fun armReassert(state: Session) {
        synchronized(lock) {
            // The session may have ended while the drop was running; restore() then owns the clean-up.
            if (session !== state) return
            if (watcher == null) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        val reason = "profile state changed ${intent.action} state=$state"
                        worker.execute { reassert(reason) }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(ACTION_PROFILE_STATE)
                }
                val registered = runCatching {
                    registerReceiverCompat(state.context, receiver, filter)
                }
                if (registered.isSuccess) {
                    watcher = receiver
                } else {
                    state.log(
                        "BT guard link: profile broadcasts not watched " +
                            (registered.exceptionOrNull()?.javaClass?.simpleName ?: ""),
                    )
                }
            }
            if (reassert == null) {
                reassert = worker.scheduleAtFixedRate(
                    { reassert(null) },
                    REASSERT_PERIOD_MILLIS,
                    REASSERT_PERIOD_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreNow(context: Context, state: Session?, revision: Long, log: (String) -> Unit) {
        fun current() = synchronized(lock) { ownershipRevision == revision && session == null }
        if (!current()) return
        val adapter = adapter(context)
        if (adapter != null) state?.handles?.let { closeProxies(adapter, it) }
        if (!applied(context)) {
            // A session that ended inside the arming delay never touched anything.
            log("BT guard: nothing had been applied; the head unit keeps its Bluetooth")
            return
        }
        val mode = appliedMode(context)
        val addresses = storedTargets(context)
        if (adapter == null) {
            log("BT guard restore: no Bluetooth adapter; keeping the state to retry")
            return
        }
        if (mode == MODE_ADAPTER) {
            val outcome = try {
                BluetoothAdapterRestore.awaitOn(
                    state = { adapter.state },
                    requestEnable = {
                        synchronized(lock) { current() && adapter.enable() }
                    },
                    isCurrent = ::current,
                )
            } catch (error: RuntimeException) {
                log("BT guard restore: state verification failed ${error.javaClass.simpleName}; keeping the state to retry")
                return
            }
            val confirmed = try {
                synchronized(lock) {
                    if (outcome == BluetoothAdapterRestore.Outcome.ON && current() &&
                        adapter.state == BluetoothAdapter.STATE_ON) {
                        clearApplied(context)
                        true
                    } else false
                }
            } catch (error: RuntimeException) {
                log("BT guard restore: final state verification failed ${error.javaClass.simpleName}; keeping the state to retry")
                return
            }
            if (confirmed) log("BT guard restore: head unit Bluetooth STATE_ON confirmed")
            else log("BT guard restore: outcome=$outcome; keeping the state to retry")
            return
        }
        val devices = addresses.mapNotNull { remoteDevice(adapter, it) }
        if (devices.isEmpty()) {
            clearApplied(context)
            log("BT guard restore: no devices to release")
            return
        }
        val handles = acquireHandles(context, adapter, log)
        val line = StringBuilder("BT guard restore:")
        for (device in devices) {
            line.append(' ').append(device.address)
            for (handle in handles) {
                val policy = applyPolicy(handle, device, POLICY_ALLOWED)
                val connect = callOnDevice(handle.profile, "connect", device)
                line.append(' ').append(handle.label).append('(').append(handle.source)
                    .append(") policy=").append(policy).append(" connect=").append(connect)
            }
        }
        closeProxies(adapter, handles)
        clearApplied(context)
        log(line.append(" devices=").append(devices.size).toString())
    }

    private fun unregisterWatcher(context: Context) {
        val receiver = synchronized(lock) { watcher.also { watcher = null } } ?: return
        runCatching { context.unregisterReceiver(receiver) }
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiverCompat(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    // ------------------------------------------------------------------ targets

    private fun targets(state: Session, adapter: BluetoothAdapter): List<BluetoothDevice> {
        val bonded = try {
            adapter.bondedDevices.orEmpty()
        } catch (error: RuntimeException) {
            state.log("BT guard link: paired devices not readable ${error.javaClass.simpleName}")
            return emptyList()
        }
        val selected = LinkedHashMap<String, BluetoothDevice>()
        val peer = state.peerAddress?.let { address ->
            bonded.firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?: remoteDevice(adapter, address)
        }
        if (peer != null) selected[peer.address] = peer
        for (device in bonded) {
            val isIPhone = try {
                device.name?.contains("iPhone", ignoreCase = true) == true
            } catch (error: RuntimeException) {
                false
            }
            if (isIPhone) selected.putIfAbsent(device.address, device)
        }
        state.log(
            "BT guard link: bonded=${bonded.size} candidates=${selected.size} " +
                "peerKnown=${peer != null}",
        )
        return selected.values.toList()
    }

    private fun remoteDevice(adapter: BluetoothAdapter, address: String): BluetoothDevice? =
        runCatching { adapter.getRemoteDevice(address) }.getOrNull()

    // ------------------------------------------------------------------ profile reachability

    private fun acquireHandles(context: Context, adapter: BluetoothAdapter, log: (String) -> Unit): List<Handle> =
        listOf(A2DP to "a2dp", HEADSET to "hfp").map { (profileId, label) ->
            val proxy = proxyFor(context, adapter, profileId)
            if (proxy != null) {
                Handle(label, profileId, "proxy", proxy)
            } else {
                val binder = binderFor(profileId, label, log)
                if (binder != null) Handle(label, profileId, "binder", binder)
                else Handle(label, profileId, "none", null)
            }
        }

    /**
     * Path 1. `getProfileProxy` is public API; the callbacks are dispatched by the platform, so the
     * request has to leave from the main thread while this worker waits on the latch.
     */
    private fun proxyFor(context: Context, adapter: BluetoothAdapter, profileId: Int): BluetoothProfile? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<BluetoothProfile?>()
        val gaveUp = AtomicBoolean(false)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                if (gaveUp.get()) {
                    runCatching { adapter.closeProfileProxy(id, proxy) }
                } else {
                    holder.set(proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(id: Int) {
                latch.countDown()
            }
        }
        runCatching {
            main.post {
                val accepted = try {
                    adapter.getProfileProxy(context, listener, profileId)
                } catch (error: RuntimeException) {
                    false
                }
                if (!accepted) latch.countDown()
            }
        }
        val ready = try {
            latch.await(PROFILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!ready) {
            gaveUp.set(true)
            return null
        }
        return holder.get()
    }

    /**
     * Path 2. The profile services are ordinary binder services; asking `ServiceManager` for them
     * skips `getProfileProxy` and its callbacks. Names are listed from the ROM rather than assumed,
     * with the AOSP names as a backstop, so a renamed service still gets a chance.
     */
    private fun binderFor(profileId: Int, label: String, log: (String) -> Unit): Any? {
        val interfaceName = if (profileId == A2DP) "IBluetoothA2dp" else "IBluetoothHeadset"
        return try {
            val manager = Class.forName("android.os.ServiceManager")
            val listed = ArrayList<String>()
            for (entry in (manager.getMethod("listServices").invoke(null) as? Array<*>) ?: emptyArray<Any>()) {
                if (entry is String) listed.add(entry)
            }
            // Say what the ROM actually registers. The earlier build logged only the names it had
            // already decided to look for, so a renamed service was indistinguishable from an absent
            // one; both profiles are resolved from this same list, so one line answers both.
            val related = ArrayList<String>()
            for (name in listed) {
                val lower = name.lowercase()
                if (lower.contains("bluetooth") || lower.contains("a2dp") || lower.contains("headset") ||
                    lower.contains("handsfree") || lower.contains("hfp") || lower.contains("bt_")) {
                    related.add(name)
                }
            }
            log("BT guard link: $label ROM services=" + related.size + " [" + related.joinToString("|") + "]")
            val wanted = ArrayList<String>()
            for (name in related) {
                val lower = name.lowercase()
                val match = if (profileId == A2DP) {
                    lower.contains("a2dp")
                } else {
                    lower.contains("headset") || lower.contains("handsfree") || lower.contains("hfp")
                }
                if (match && !wanted.contains(name)) wanted.add(name)
            }
            // The AOSP name as a backstop for a ROM that hides the profile services from listServices.
            val backstop = if (profileId == A2DP) "bluetooth_a2dp" else "bluetooth_headset"
            if (!wanted.contains(backstop)) wanted.add(backstop)
            val getService = manager.getMethod("getService", String::class.java)
            val stub = Class.forName("android.bluetooth.$interfaceName\$Stub")
            val asInterface = stub.getMethod("asInterface", android.os.IBinder::class.java)
            var found: Any? = null
            for (name in wanted) {
                val binder = getService.invoke(null, name) as? android.os.IBinder ?: continue
                found = runCatching { asInterface.invoke(null, binder) }.getOrNull() ?: continue
                break
            }
            if (found == null) {
                log("BT guard link: no binder for " + label + " tried=" + wanted.joinToString("|"))
            }
            found
        } catch (error: Throwable) {
            log("BT guard link: no binder for $label ${error.javaClass.simpleName}")
            null
        }
    }

    private fun closeProxies(adapter: BluetoothAdapter, handles: List<Handle>) {
        for (handle in handles) {
            val proxy = handle.profile as? BluetoothProfile ?: continue
            if (handle.source == "proxy") {
                runCatching { adapter.closeProfileProxy(handle.profileId, proxy) }
            }
        }
    }

    // ------------------------------------------------------------------ reflection into the profiles

    /**
     * The policy call, which is what keeps the profile down. `setConnectionPolicy` is the modern
     * name and `setPriority` the pre-Android-9 one; both take the same values, and which one this
     * ROM carries is part of what the returned token reports.
     */
    private fun applyPolicy(handle: Handle, device: BluetoothDevice, value: Int): String {
        val modern = callOnDevice(handle.profile, "setConnectionPolicy", device, value)
        if (modern != "NoSuchMethodException") return modern
        return "setPriority:" + callOnDevice(handle.profile, "setPriority", device, value)
    }

    private fun callOnDevice(target: Any?, name: String, device: BluetoothDevice, value: Int? = null): String {
        if (target == null) return "no-service"
        return try {
            val method = if (value == null) {
                target.javaClass.getMethod(name, BluetoothDevice::class.java)
            } else {
                target.javaClass.getMethod(name, BluetoothDevice::class.java, Integer.TYPE)
            }
            method.isAccessible = true
            val result = if (value == null) method.invoke(target, device) else method.invoke(target, device, value)
            result?.toString() ?: "null"
        } catch (error: Throwable) {
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.cause ?: error
            cause.javaClass.simpleName
        }
    }

    // ------------------------------------------------------------------ state

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun markApplied(context: Context, mode: String, addresses: List<String>) {
        preferences(context).edit()
            .putBoolean(KEY_APPLIED, true)
            .putString(KEY_APPLIED_MODE, mode)
            .putString(KEY_APPLIED_TARGETS, addresses.joinToString(","))
            .apply()
    }

    private fun applied(context: Context): Boolean = preferences(context).getBoolean(KEY_APPLIED, false)

    private fun appliedMode(context: Context): String =
        preferences(context).getString(KEY_APPLIED_MODE, MODE_LINK) ?: MODE_LINK

    private fun storedTargets(context: Context): List<String> =
        preferences(context).getString(KEY_APPLIED_TARGETS, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun clearApplied(context: Context) {
        preferences(context).edit()
            .putBoolean(KEY_APPLIED, false)
            .remove(KEY_APPLIED_MODE)
            .remove(KEY_APPLIED_TARGETS)
            .apply()
    }

    private fun adapter(context: Context): BluetoothAdapter? = try {
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    } catch (error: RuntimeException) {
        Log.w(TAG, "Bluetooth adapter unavailable", error)
        null
    }
}
