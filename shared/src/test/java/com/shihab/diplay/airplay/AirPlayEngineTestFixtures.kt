package com.shihab.diplay.airplay

import java.io.Closeable
import java.net.Socket
import org.junit.Assert.assertTrue

/**
 * Fixtures shared by the engine tests that drive [CarPlayMediaEngine.onScreen] and reach the
 * production listener through its private seams: a loopback-backed session, its shared secret and
 * the registry/listener reflection accessors.
 */

internal fun withSharedSecret(session: AirPlaySession) {
    session.pairVerify.handle(Tlv8Codec.encode(listOf(
        Tlv8Item(6, byteArrayOf(1)), Tlv8Item(3, AirPlayCrypto.x25519Generate().publicKey),
    )))
    assertTrue(session.sharedSecret != null)
}

@Suppress("UNCHECKED_CAST")
internal fun streamsOf(engine: CarPlayMediaEngine): Map<CarPlayMediaEngine.StreamKey, Closeable> =
    CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
        .get(engine) as Map<CarPlayMediaEngine.StreamKey, Closeable>

internal fun listenerOf(screen: ScreenStream): ScreenStream.Listener =
    ScreenStream::class.java.getDeclaredField("listener").apply { isAccessible = true }
        .get(screen) as ScreenStream.Listener

// onScreen needs the control peer's address.
internal fun connectedSocket(): Socket =
    java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
        Socket(server.inetAddress, server.localPort)
    }

internal fun testSession(
    listener: AirPlaySessionListener = object : AirPlaySessionListener {},
): AirPlaySession = AirPlaySession(
    socket = connectedSocket(),
    config = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "1.0",
        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
    ),
    identity = AirPlayIdentity.generate(),
    pairings = PairingStore(),
    mfi = null,
    listener = listener,
    media = object : AirPlayMediaHandler {},
)
