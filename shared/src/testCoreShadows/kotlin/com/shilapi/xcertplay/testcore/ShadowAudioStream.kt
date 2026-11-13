package com.shilapi.xcertplay.testcore

import com.shilapi.xcertplay.airplay.AudioStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow

/**
 * SDK port/setup/teardown fake only. No RTP parser, crypto or queue lives here. Receiver acceptance
 * uses real Android FDs in NativeAudioRouteDeviceTest; this fake never counts as native reception.
 */
@Implements(className = "com.shilapi.xcertplay.airplay.AudioStream", isInAndroidSdk = false)
class ShadowAudioStream {
    @RealObject private lateinit var real: AudioStream
    private val sockets = mutableListOf<DatagramSocket>()

    @Implementation fun listen(listener: AudioStream.Listener): Pair<Int, Int> {
        repeat(2) {
            val socket = DatagramSocket(null)
            sockets.add(socket)
            socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        }
        return sockets[0].localPort to sockets[1].localPort
    }

    @Implementation fun close() {
        sockets.forEach(DatagramSocket::close)
        sockets.clear()
        Shadow.directlyOn<Any?, AudioStream>(real, AudioStream::class.java, "close")
    }
}
