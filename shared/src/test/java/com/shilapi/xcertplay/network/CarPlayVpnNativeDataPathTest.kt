package com.shilapi.xcertplay.network

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** The real attachment owns selection and cleanup; platform adapters do not perform USB I/O here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [
    NcmDataPathVpnBuilderShadow::class, NcmDataPathUsbShadow::class,
    NcmDataPathFallbackShadow::class, NcmDataPathListenerShadow::class,
])
class CarPlayVpnNativeDataPathTest {
    @Before fun reset() { NcmDataPathBoundary.reset() }

    @Test fun absentPreferenceStartsNativeAndDetachReleasesTheOwnedLink() {
        attach(preference = null, expected = listOf("native-start", "listener", "usb-close"))
    }

    @Test fun explicitFalseSkipsNativeAndKeepsThePlatformBridge() {
        attach(preference = false, expected = listOf("fallback-start", "listener", "fallback-close", "usb-close"))
    }

    @Test fun nativeStartupFailureFallsBackUsingTheSameTunnel() {
        NcmDataPathBoundary.nativeStarts = false
        attach(preference = null, expected = listOf("native-start", "fallback-start", "listener", "fallback-close", "usb-close"))
    }

    @Test fun failureAfterNativeHandoffReleasesTheLinkAndTunnel() {
        NcmDataPathBoundary.listenerFailure = IOException("listener rejected")
        attach(preference = true, expected = listOf("native-start", "listener", "usb-close"))
    }

    private fun attach(preference: Boolean?, expected: List<String>) {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        val service = controller.get()
        val pipe = ParcelFileDescriptor.createPipe()
        val tunDescriptor = pipe[0].fileDescriptor
        NcmDataPathBoundary.tun = pipe[0]
        val preferences = service.getSharedPreferences(NcmUsbBridge.NATIVE_DATA_PATH_PREFS, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        preference?.let { preferences.edit().putBoolean(NcmUsbBridge.NATIVE_DATA_PATH_KEY, it).commit() }
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        val ncm = NcmUsbBridge(connection, endpoint(0x04), endpoint(0x85), null, emptyList(), null)
        try {
            val result = service.attach(ncm, "fe80::1234", ByteArray(6),
                AirPlayConfig("test", "00:00:00:00:00:01", "00:00:00:00:00:02", "1", AirPlayDisplayConfig(800, 480), port = 0),
                AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
            if (NcmDataPathBoundary.listenerFailure != null) {
                assertEquals(CarPlayVpnService.AttachResult.Failed("listener rejected"), result)
            } else {
                assertEquals(CarPlayVpnService.AttachResult.Started, result)
                assertTrue(service.isAttached())
                assertTrue("The attachment retains its original TUN", tunDescriptor.valid())
                service.detach()
            }
            assertFalse(service.isAttached())
            assertFalse("The attachment closes its TUN", tunDescriptor.valid())
            assertEquals(expected, NcmDataPathBoundary.calls)
            service.detach()
            assertEquals("Repeated detach must not close the link again", expected, NcmDataPathBoundary.calls)
        } finally {
            controller.destroy()
            ncm.close()
            pipe.forEach { it.close() }
        }
    }
}

object NcmDataPathBoundary {
    lateinit var tun: ParcelFileDescriptor
    var nativeStarts = true
    var listenerFailure: IOException? = null
    val calls = mutableListOf<String>()
    fun reset() { nativeStarts = true; listenerFailure = null; calls.clear() }
}

@Implements(VpnService.Builder::class)
class NcmDataPathVpnBuilderShadow {
    @RealObject private lateinit var builder: VpnService.Builder
    @Implementation fun addAddress(address: String, prefixLength: Int): VpnService.Builder = builder
    @Implementation fun addRoute(address: String, prefixLength: Int): VpnService.Builder = builder
    @Implementation fun addAllowedApplication(packageName: String): VpnService.Builder = builder
    @Implementation fun establish(): ParcelFileDescriptor = NcmDataPathBoundary.tun
}

@Implements(NcmUsbBridge::class, isInAndroidSdk = false)
class NcmDataPathUsbShadow {
    @RealObject private lateinit var bridge: NcmUsbBridge
    @Implementation fun startNative(tun: ParcelFileDescriptor, hostMac: ByteArray,
        onDiagnosticLine: (String) -> Unit, onError: (Throwable) -> Unit): Boolean {
        assertTrue(tun === NcmDataPathBoundary.tun)
        assertTrue(tun.fileDescriptor.valid())
        NcmDataPathBoundary.calls += "native-start"
        return NcmDataPathBoundary.nativeStarts
    }
    @Implementation fun close() {
        NcmDataPathBoundary.calls += "usb-close"
        Shadow.directlyOn<Unit, NcmUsbBridge>(bridge, NcmUsbBridge::class.java, "close")
    }
}

@Implements(Ipv6NcmBridge::class, isInAndroidSdk = false)
class NcmDataPathFallbackShadow {
    @RealObject private lateinit var bridge: Ipv6NcmBridge
    @Implementation fun start() {
        assertTrue(ReflectionHelpers.getField<ParcelFileDescriptor>(bridge, "tun") === NcmDataPathBoundary.tun)
        assertTrue(NcmDataPathBoundary.tun.fileDescriptor.valid())
        NcmDataPathBoundary.calls += "fallback-start"
    }
    @Implementation fun close() {
        NcmDataPathBoundary.calls += "fallback-close"
        ReflectionHelpers.getField<NcmUsbBridge>(bridge, "ncm").close()
        ReflectionHelpers.getField<ParcelFileDescriptor>(bridge, "tun").close()
    }
}

@Implements(AirPlayPortSelector::class, isInAndroidSdk = false)
class NcmDataPathListenerShadow {
    @Implementation fun bind(address: InetAddress, preferredPort: Int, fallbackPorts: Iterable<Int>,
        onFallback: (Int, Int) -> Unit): ServerSocket {
        NcmDataPathBoundary.calls += "listener"
        NcmDataPathBoundary.listenerFailure?.let { throw it }
        return ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    }
}
