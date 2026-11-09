package com.shilapi.xcertplay.orchestration

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.transport.BluetoothLinkCheck
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"

// Measured connects: 147–1714ms. Leave >4x headroom, without spending 15s on each dead socket.
internal const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 8_000L

internal fun diagnosticFailureClass(error: Throwable): String =
    error.javaClass.simpleName.take(80).replace(Regex("[^A-Za-z0-9_$]"), "?")

/** Bluetooth target selection and the blocking RFCOMM connect; cancellation belongs to the caller. */
internal class WirelessBluetoothConnection(
    private val appContext: Context,
    private val selectedAddress: String?,
    private val debugLog: (String) -> Unit,
    private val connectionDiagnostic: (String) -> Unit,
) {
    fun currentBluetoothAdapter(): BluetoothAdapter? =
        runCatching { appContext.getSystemService(BluetoothManager::class.java)?.adapter }
            .getOrNull() ?: runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()

    fun validateBluetoothTarget(adapter: BluetoothAdapter, target: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Bluetooth connect permission is not granted")
        }
        if (adapter.state != BluetoothAdapter.STATE_ON) {
            throw BluetoothRadioCheck.failure(BluetoothRadioCheck.RADIO_OFF)
        }
        if (adapter.bondedDevices.orEmpty().none { it.address.equals(target.address, ignoreCase = true) }) {
            throw ConnectionFailureException(ConnectionFailureCause.NO_PAIRED_IPHONE,
                "Selected Bluetooth target is no longer paired")
        }
    }

    fun selectWirelessBluetoothDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val bonded = adapter.bondedDevices.orEmpty()
        selectedAddress?.let { selected ->
            val picked = bonded.firstOrNull { it.address.equals(selected, ignoreCase = true) }
            if (picked != null) return picked
            // The picked phone is gone from this head unit's pairing list — a new phone, or the old
            // pairing was forgotten on one side. That is not a reason to stop: the ladder below
            // picks what is actually paired here, which is what "connect to the paired iPhone"
            // means once the picked one no longer exists.
            debugLog("wireless picked address=$selected is no longer paired; choosing automatically")
        }
        val iPhones = bonded.filter { device ->
            device.name?.contains("iPhone", ignoreCase = true) == true
        }
        val directlyConnectedIPhones = iPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "wireless Bluetooth bondedIPhones=${iPhones.size} " +
                "directlyConnected=${directlyConnectedIPhones.size}",
        )
        val connectedIPhones = if (directlyConnectedIPhones.isNotEmpty()) {
            directlyConnectedIPhones
        } else {
            val connectedAddresses = connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
            iPhones.filter { it.address in connectedAddresses }
        }
        if (connectedIPhones.size == 1) return connectedIPhones.single()
        if (connectedIPhones.size > 1) {
            throw ConnectionFailureException(ConnectionFailureCause.IPHONE_NOT_CHOSEN,
                "Multiple connected iPhones found: " +
                    connectedIPhones.joinToString { "${it.name ?: "iPhone"} (${it.address})" },
            )
        }
        if (iPhones.size == 1) return iPhones.single()
        if (iPhones.size > 1) {
            throw ConnectionFailureException(ConnectionFailureCause.IPHONE_NOT_CHOSEN,
                "Multiple bonded iPhones found and none is currently connected; " +
                    "connect one iPhone and retry",
            )
        }
        if (bonded.size == 1) return bonded.single()
        throw ConnectionFailureException(
            if (bonded.isEmpty()) ConnectionFailureCause.NO_PAIRED_IPHONE else ConnectionFailureCause.IPHONE_NOT_CHOSEN,
            "No unambiguous bonded iPhone found; pair one iPhone and retry",
        )
    }

    /**
     * The iPhone to try after the picked one refuses the RFCOMM link.
     *
     * Only a phone the head unit already has a live Bluetooth link to counts. "Another paired
     * iPhone" would be a guess, and a wrong guess costs a second full timeout where the original
     * error at least names the phone the driver picked. Null means there is no better candidate,
     * and the caller rethrows the original failure unchanged.
     */
    fun wirelessBluetoothFallback(adapter: BluetoothAdapter, tried: BluetoothDevice): BluetoothDevice? {
        val candidates = adapter.bondedDevices.orEmpty().filter { device ->
            device.name?.contains("iPhone", ignoreCase = true) == true &&
                !device.address.equals(tried.address, ignoreCase = true)
        }
        if (candidates.isEmpty()) return null
        val live = candidates.filter(::isBluetoothDeviceConnected).ifEmpty {
            // A head unit is the sink side of the phone's audio and calls, so the link that proves
            // "this iPhone is in the car" can sit in a hidden profile that `isConnected` misses.
            val byProfile = connectedBluetoothDevices(adapter)
            candidates.filter { device -> byProfile.any { it.address.equals(device.address, ignoreCase = true) } }
        }
        // Two live iPhones is exactly the ambiguity the caller must not resolve by guessing.
        return live.singleOrNull()
    }

    fun connectBluetoothSocket(
        socket: BluetoothSocket,
        address: String,
        isCurrent: () -> Boolean,
    ) {
        val abandoned = AtomicBoolean(false)
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        Thread(
            {
                try {
                    if (abandoned.get() || !isCurrent()) throw java.util.concurrent.CancellationException("Stale RFCOMM connect")
                    socket.connect()
                    if (abandoned.get() || !isCurrent()) runCatching { socket.close() }
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RFCOMM_CONNECT_TIMEOUT_MILLIS)
            var done = false
            while (!done && System.nanoTime() < deadline && isCurrent()) {
                done = connected.await(100, TimeUnit.MILLISECONDS)
            }
            if (!isCurrent()) {
                abandoned.set(true)
                runCatching { socket.close() }
                throw java.util.concurrent.CancellationException("Stale RFCOMM connect")
            }
            done
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            abandoned.set(true)
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            abandoned.set(true)
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms connecting RFCOMM to $address",
            )
        }
        val failure = result.get() ?: return
        if (failure is Error) throw failure
        if (BluetoothLinkCheck.socketServiceMissing(failure)) {
            connectionDiagnostic("Bluetooth socket manager missing failureClass=${failure.javaClass.simpleName}")
        }
        throw BluetoothLinkCheck.connectFailure(failure, address)
    }

    /** Reads cached service metadata only; it does not start/cancel discovery or require SCAN. */
    fun logBluetoothConnectionSnapshot(device: BluetoothDevice, point: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                connectionDiagnostic("Bluetooth snapshot point=$point unavailable reason=connect-permission")
                return
            }
            val bondState = device.bondState
            val bondName = when (bondState) {
                BluetoothDevice.BOND_NONE -> "NONE"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                BluetoothDevice.BOND_BONDED -> "BONDED"
                else -> "UNKNOWN"
            }
            val cachedServices = runCatching { device.uuids }
            val uuids = cachedServices.getOrNull()
            val service = UUID.fromString(IAP2_IPHONE_UUID)
            val activeAdapter = currentBluetoothAdapter()
            connectionDiagnostic(
                "Bluetooth snapshot point=$point enabled=${activeAdapter?.isEnabled} " +
                    "bondState=$bondState bondName=$bondName cachedServicesReadable=${cachedServices.isSuccess} " +
                    "cachedServiceCount=${uuids?.size ?: "unknown"} " +
                    "cachedIap2Service=${uuids?.any { it.uuid == service } ?: "unknown"}",
            )
        } catch (error: RuntimeException) {
            connectionDiagnostic("Bluetooth snapshot point=$point unavailable failureClass=${diagnosticFailureClass(error)}")
        }
    }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: ReflectiveOperationException) {
        false
    } catch (error: RuntimeException) {
        Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth connection state", error)
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
            // A head unit is the sink side of a phone's audio and calls, so the iPhone shows up under
            // these hidden profiles. Asked only when connected: an absent profile service never
            // answers the proxy request and would cost its full timeout.
            for (profile in intArrayOf(PROFILE_A2DP_SINK, PROFILE_HEADSET_CLIENT)) {
                val connected = try {
                    adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth profile $profile state", error)
                    false
                }
                if (connected) addAll(connectedBluetoothDevices(adapter, profile, BluetoothProfile::class.java))
            }
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(IphoneCarPlayConfiguration.TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    private companion object {
        // BluetoothProfile.A2DP_SINK and HEADSET_CLIENT, hidden from the public SDK.
        private const val PROFILE_A2DP_SINK = 11
        private const val PROFILE_HEADSET_CLIENT = 16
    }
}
