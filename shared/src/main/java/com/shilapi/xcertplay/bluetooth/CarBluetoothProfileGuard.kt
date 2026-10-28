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
 * Optional media-only yield uses the head unit's A2DP_SINK role (11), keeps HFP and the radio
 * untouched, and journals the selected phone's original policy before changing it. Failed
 * restoration retains the journal for startup recovery.
 *
 * The removed fix9 experiment requested phone-side A2DP/HEADSET (2/1), timed out acquiring
 * proxies, and found no matching ServiceManager Binder. That does not establish whether the
 * car-side Sink service is available: AOSP acquires it through a bound service. The old link
 * code remains only for recovery of an older build's journal.
 *
 * The separate legacy adapter-off option must restore radio power before RFCOMM reconnect.
 */
public object CarBluetoothProfileGuard {

    private const val TAG = "DiPlay-BT-Yield"
    private const val PREFS = "diplay_bt_profile_guard"
    private const val KEY_ADAPTER_ENABLED = "yield_adapter"
    private const val KEY_MEDIA_ONLY = "yield_media_only"
    private const val KEY_MEDIA_POLICY = "media_original_policy"
    private const val KEY_MEDIA_CONNECTED = "media_was_connected"
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
    private const val MODE_MEDIA = "media"

    // Only legacy link recovery uses both phone-side profiles. New media yield never arms reassert.
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
        val mediaOnly: Boolean = false,
    ) {
        var mediaAttempted = false // guarded by lock; handoff and session-active can both call apply.
        var handles: List<Handle>? = null
        var targets: List<BluetoothDevice> = emptyList()
    }

    // ------------------------------------------------------------------ settings

    /** Adapter-off mode. It kills the wireless handshake while it holds, so it stays opt-in. */
    fun adapterEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ADAPTER_ENABLED, false)

    fun saveAdapterEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ADAPTER_ENABLED, enabled).apply {
            if (enabled) putBoolean(KEY_MEDIA_ONLY, false)
        }.apply()
    }

    fun mediaOnlyEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_MEDIA_ONLY, false)

    /** Takes effect on the next session; never changes a running call or CarPlay session. */
    fun saveMediaOnlyEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_MEDIA_ONLY, enabled).apply {
            if (enabled) putBoolean(KEY_ADAPTER_ENABLED, false)
        }.apply()
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
        if (!applied(context) || appliedMode(context) != MODE_ADAPTER) return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        return adapter?.isEnabled == false
    }

    /** Called when a CarPlay session is live. Returns at once; the drop runs on the guard's thread. */
    fun yieldForSession(context: Context, peerAddress: String?, mediaAudioAllowed: Boolean = true,
        log: (String) -> Unit = logcat) {
        val app = context.applicationContext
        val mediaOnly = mediaOnlyEnabled(app)
        val adapterMode = !mediaOnly && adapterEnabled(app)
        if (mediaOnly && !mediaAudioAllowed) {
            log("BT guard media: skipped; CarPlay audio is configured to use car Bluetooth")
            return
        }
        if (!adapterMode && !mediaOnly) {
            log("BT guard: the adapter switch is off; the head unit keeps its Bluetooth to itself")
            return
        }
        val state = Session(app, adapterMode, peerAddress, log, mediaOnly)
        val delay = if (mediaOnly) 0L else adapterDelaySeconds(app) * 1_000L
        synchronized(lock) {
            pending?.cancel(false)
            ownershipRevision++
            recoveryPending = false
            session = state
            pending = worker.schedule({ apply(state) }, delay, TimeUnit.MILLISECONDS)
        }
        log(
            "BT guard armed mode=${if (mediaOnly) MODE_MEDIA else MODE_ADAPTER} delayMs=$delay " +
                "peerKnown=${peerAddress != null}",
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
        yieldForSession(app, peerAddress, log = log)
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
        if (state.mediaOnly) {
            applyMediaOnly(state, adapter)
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

    /** No HFP calls, adapter power changes, guessed Binder services, or other paired phones. */
    private fun applyMediaOnly(state: Session, adapter: BluetoothAdapter) {
        synchronized(lock) {
            if (session !== state || state.mediaAttempted) return
            state.mediaAttempted = true
        }
        val device = runCatching {
            adapter.bondedDevices.firstOrNull { it.address.equals(state.peerAddress, ignoreCase = true) }
        }.getOrNull()
        if (device == null) {
            state.log("BT guard media: selected paired iPhone unavailable; unchanged")
            return
        }
        val proxy = proxyFor(state.context, adapter, A2dpSinkPolicy.PROFILE_ID, state.log) ?: return
        try {
            val access = A2dpSinkPolicy.from(proxy, state.log)
            if (access == null) {
                state.log("BT guard media: A2DP_SINK policy API unavailable; unchanged")
                return
            }
            synchronized(lock) {
                if (session !== state) return
                val prefs = preferences(state.context)
                val ownsMedia = applied(state.context) && appliedMode(state.context) == MODE_MEDIA &&
                    storedTargets(state.context) == listOf(device.address)
                if (applied(state.context) && !ownsMedia) {
                    state.log("BT guard media: previous recovery pending; unchanged")
                    return
                }
                val original = access.read(device)
                val connection = access.connectionState(device)
                // The API 28 setter can restore only OFF/ON, not AUTO_CONNECT=1000 or unknown=-1.
                if (original !in listOf(POLICY_FORBIDDEN, POLICY_ALLOWED) ||
                    connection !in listOf(BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED)) {
                    state.log("BT guard media: original policy/state not safely restorable" +
                        " policy=${original ?: "unreadable"} state=${connection ?: "unreadable"}; unchanged")
                    return
                }
                if (!ownsMedia && original == POLICY_FORBIDDEN) {
                    state.log("BT guard media: already disabled by user; unchanged")
                    return
                }
                if (!ownsMedia && !prefs.edit().putBoolean(KEY_APPLIED, true)
                    .putString(KEY_APPLIED_MODE, MODE_MEDIA).putString(KEY_APPLIED_TARGETS, device.address)
                    .putInt(KEY_MEDIA_POLICY, original!!)
                    .putBoolean(KEY_MEDIA_CONNECTED, connection == BluetoothProfile.STATE_CONNECTED).commit()) {
                    state.log("BT guard media: recovery state could not be saved; unchanged")
                    return
                }
                val accepted = original == POLICY_FORBIDDEN || access.write(device, POLICY_FORBIDDEN)
                val policy = access.read(device)
                val disconnect = if (accepted && policy == POLICY_FORBIDDEN &&
                    access.connectionState(device) == BluetoothProfile.STATE_CONNECTED) access.disconnect(device) else false
                state.log("BT guard media: A2DP_SINK policyAccepted=$accepted policyNow=${policy ?: "unknown"}" +
                    " disconnectRequested=$disconnect state=${access.connectionState(device) ?: "unknown"}" +
                    " phoneProfile=untouched adapterPower=untouched")
            }
        } finally {
            runCatching { adapter.closeProfileProxy(A2dpSinkPolicy.PROFILE_ID, proxy) }
        }
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
        if (mode == MODE_MEDIA) {
            restoreMediaOnly(context, adapter, ::current, log)
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

    private fun restoreMediaOnly(context: Context, adapter: BluetoothAdapter, current: () -> Boolean,
        log: (String) -> Unit) {
        val prefs = preferences(context)
        val address = storedTargets(context).singleOrNull()
        val original = prefs.getInt(KEY_MEDIA_POLICY, -1)
        if (address == null || original != POLICY_ALLOWED || !adapter.isEnabled) {
            log("BT guard media restore: saved policy or radio unavailable; keeping recovery pending")
            return
        }
        val device = remoteDevice(adapter, address) ?: return
        val proxy = proxyFor(context, adapter, A2dpSinkPolicy.PROFILE_ID, log) ?: return
        try {
            val access = A2dpSinkPolicy.from(proxy, log)
            if (access == null) {
                log("BT guard media restore: policy API unavailable; keeping recovery pending")
                return
            }
            synchronized(lock) {
                if (!current()) return
                // A single-link Sink may displace another phone when policy/connect is restored.
                val otherMedia = runCatching {
                    proxy.connectedDevices.any { !it.address.equals(address, ignoreCase = true) }
                }.getOrNull()
                if (otherMedia != false) {
                    log("BT guard media restore: another media device or unreadable connections; keeping recovery pending")
                    return
                }
                val policy = access.read(device)
                if (policy != POLICY_FORBIDDEN && policy != original) {
                    log("BT guard media restore: policy unreadable or changed; keeping recovery pending")
                    return
                }
                val accepted = policy == original || access.write(device, original)
                val confirmed = accepted && access.read(device) == original
                // Only restore a connection we actually interrupted, never a user-disabled media link.
                val connect = if (confirmed && policy == POLICY_FORBIDDEN &&
                    prefs.getBoolean(KEY_MEDIA_CONNECTED, false) &&
                    access.connectionState(device) == BluetoothProfile.STATE_DISCONNECTED) access.connect(device) else false
                if (confirmed) clearApplied(context)
                log("BT guard media restore: policyRestored=$confirmed connectRequested=$connect" +
                    " state=${access.connectionState(device) ?: "unknown"} phoneProfile=untouched" +
                    if (confirmed) "" else "; keeping recovery pending")
            }
        } finally {
            runCatching { adapter.closeProfileProxy(A2dpSinkPolicy.PROFILE_ID, proxy) }
        }
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
    private fun proxyFor(context: Context, adapter: BluetoothAdapter, profileId: Int,
        log: (String) -> Unit = logcat): BluetoothProfile? {
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
                    log("BT guard media: profile=$profileId request failed ${error.javaClass.simpleName}")
                    false
                }
                if (!accepted) {
                    log("BT guard media: profile=$profileId request rejected; unchanged")
                    latch.countDown()
                }
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
            log("BT guard media: profile=$profileId callback timed out; unchanged")
            return null
        }
        return holder.get()
    }

    /** Legacy recovery only. A vendor might publish a global Binder; AOSP binds profile services
     * through Context instead, so an empty ServiceManager search does not prove they are absent. */
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
            .remove(KEY_MEDIA_POLICY)
            .remove(KEY_MEDIA_CONNECTED)
            .apply()
    }

    private fun adapter(context: Context): BluetoothAdapter? = try {
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    } catch (error: RuntimeException) {
        Log.w(TAG, "Bluetooth adapter unavailable", error)
        null
    }
}
