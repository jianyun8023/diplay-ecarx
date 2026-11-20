package com.shihab.diplay.orchestration

import com.shihab.diplay.airplay.AirPlayConfig
import com.shihab.diplay.airplay.AirPlayDisplayConfig
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.airplay.AirPlayMediaHandler
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.airplay.PairingStore
import com.shihab.diplay.iap2.session.Iap2Session
import com.shihab.diplay.mfi.MfiAuthenticator
import com.shihab.diplay.mfi.MfiCertificateType
import com.shihab.diplay.network.CarPlayVpnService
import com.shihab.diplay.transport.BlockingDuplexByteStream
import com.shihab.diplay.transport.Iap2IdentificationConfig
import com.shihab.diplay.transport.Iap2LinkEngine
import com.shihab.diplay.transport.Iap2WirelessCarPlayEndpoint
import com.shihab.diplay.transport.Iap2WirelessIdentification
import com.shihab.diplay.transport.Iap2WirelessSecurity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * The runtime tunnel channel is a resource publication: [Iap2Session] is handed to the single-thread
 * tunnel executor, but the query [CarPlayController.WirelessRun.runtimeChannel] must only name the
 * channel whose control loop actually took the executor over. These tests drive the interleaving with
 * a manual executor and scripted streams so the facts are ordered, never timed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessTunnelPublicationTest {
    private val debugLogs = mutableListOf<String>()
    private val debugLogLock = Object()

    private val identification = Iap2IdentificationConfig(
        name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
        firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3,
        wireless = Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "test"),
    )
    private val endpoint = Iap2WirelessCarPlayEndpoint(
        ssid = "test", passphrase = "passphrase", channel = 1,
        security = Iap2WirelessSecurity.WPA_WPA2, ipAddresses = listOf("192.168.1.2"),
        airPlayPort = 7000, deviceIdentifier = "device", publicKey = "public-key", sourceVersion = "1",
    )

    private val controller = CarPlayController(
        RuntimeEnvironment.getApplication(),
        CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = identification,
        ),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(),
        object : AirPlaySessionListener {
            override val debugLogsEnabled = true
            override fun onDebugLog(message: String) {
                synchronized(debugLogLock) {
                    debugLogs += message
                    debugLogLock.notifyAll()
                }
            }
        },
        object : AirPlayMediaHandler {
            override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) = Unit
        },
        {},
    )
    private val runType = controller.javaClass.declaredClasses.single { it.simpleName == "WirelessRun" }

    @After
    fun close() {
        field(controller, "wirelessRun")?.let { closeRun(it) }
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
    }

    /**
     * The recorded baseline defect: the handler published B while the executor still served A, so the
     * query already answered B. A single-thread executor cannot serve two loops, and the query must keep
     * naming the loop that is actually reading and writing the tunnel.
     */
    @Test
    fun queuedReplacementIsNotPublishedWhileTheRunningTunnelStillExecutesControlIo() {
        val executor = ManualTunnelExecutor()
        val run = prepareRun(executor)
        val streamA = ScriptedTunnelStream(syncAcknowledgement())
        val streamB = ScriptedTunnelStream(syncAcknowledgement())
        var channelA: Iap2Session? = null
        var threadA: Thread? = null
        try {
            assertTrue(startTunnel(run, streamA))
            val taskA = requireNotNull(executor.takeNext()) { "the first tunnel task must be queued" }
            threadA = runTask(taskA, "tunnel-a")
            assertTrue("A's executor task must reach its control loop", awaitDebugLogCount(READY, 1))
            channelA = field(run, "wirelessTunnelChannel") as? Iap2Session
            assertNotNull("A's channel must be published once it takes over", channelA)
            assertSame("the published channel must be A's stream", streamA, streamOf(channelA))
            assertTrue("A must be writing control frames", streamA.awaitSendCount(2))
            assertSame("the query must name the channel executing control I/O", channelA, runtimeChannel(run))
            assertEquals(1, debugLogCount(STARTING))

            assertTrue(startTunnel(run, streamB))
            val taskB = requireNotNull(executor.takeNext()) { "the replacement task must be queued" }
            assertNotNull(taskB)

            // B was accepted but its task is deliberately not running: this is the interleaving.
            val observed = runtimeChannel(run)
            val observedStream = streamOf(observed)
            val executing = streamOf(channelA)
            println(
                "TUNNEL-PUBLICATION observed=" + streamLabel(observedStream, streamA, streamB) +
                    " expected=" + streamLabel(executing, streamA, streamB) +
                    " readyTraces=" + debugLogCount(READY) +
                    " startTraces=" + debugLogCount(STARTING),
            )
            assertEquals("the queued task must not have entered its control loop", 1, debugLogCount(READY))
            assertNotSame(
                "a queued tunnel must not be published as the runtime channel " +
                    "(observed=" + streamLabel(observedStream, streamA, streamB) +
                    " expected=" + streamLabel(executing, streamA, streamB) + ")",
                streamB,
                observedStream,
            )
            assertSame(
                "the query must keep naming the channel actually executing control I/O " +
                    "(observed=" + streamLabel(observedStream, streamA, streamB) +
                    " expected=" + streamLabel(executing, streamA, streamB) + ")",
                channelA,
                observed,
            )
            assertEquals(
                "only the channel that took over may log a control start " +
                    "(observed=" + debugLogCount(STARTING) + " startTraces)",
                1,
                debugLogCount(STARTING),
            )
        } finally {
            channelA?.let { closeQuietly(it) }
            closeRun(run)
            streamA.close()
            streamB.close()
            executor.shutdownNow()
            threadA?.join(TASK_JOIN_MILLIS)
        }
    }

    /** Accept-and-replace: the running channel is replaced only when its task takes the executor. */
    @Test
    fun takeoverPublishesTheReplacementAndTheReplacedLoopsLateReadyAndExitCannotChangeIt() {
        val executor = ManualTunnelExecutor()
        val run = prepareRun(executor)
        val streamA = ScriptedTunnelStream(syncAcknowledgement())
        val streamB = ScriptedTunnelStream(syncAcknowledgement())
        var channelA: Iap2Session? = null
        var channelB: Iap2Session? = null
        var threadA: Thread? = null
        var threadB: Thread? = null
        // A live AirPlay session keeps a channel-closed report from retiring the whole run.
        val airPlaySession = AirPlaySession(
            java.net.Socket(),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
        )
        field(controller, "activeSession", airPlaySession)
        try {
            assertTrue(startTunnel(run, streamA))
            threadA = runTask(requireNotNull(executor.takeNext()), "tunnel-a")
            assertTrue(awaitDebugLogCount(READY, 1))
            channelA = field(run, "wirelessTunnelChannel") as? Iap2Session
            assertSame(streamA, streamOf(channelA))

            assertTrue(startTunnel(run, streamB))
            threadB = runTask(requireNotNull(executor.takeNext()), "tunnel-b")
            assertTrue("B's takeover must enter its control loop", awaitDebugLogCount(READY, 2))
            channelB = runtimeChannel(run)
            assertNotNull(channelB)
            assertSame("the runtime channel must be B after takeover", streamB, streamOf(channelB))
            assertNotSame("takeover must replace the published channel", channelA, channelB)

            // A's late readiness arrives after B already owns the executor.
            runType.getDeclaredMethod("onWirelessTunnelReady", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(run, 1)
            val bSends = streamB.sendCount
            closeQuietly(requireNotNull(channelA))
            threadA!!.join(TASK_JOIN_MILLIS)
            assertFalse("A's control loop must have exited", threadA!!.isAlive)

            assertSame("A's late readiness and exit must not unpublish B", channelB, runtimeChannel(run))
            assertSame(streamB, streamOf(runtimeChannel(run)))
            assertEquals("A's exit must not write B's stream", bSends, streamB.sendCount)
            assertFalse("A's exit must not release B's stream", streamB.released)
        } finally {
            channelA?.let { closeQuietly(it) }
            channelB?.let { closeQuietly(it) }
            closeRun(run)
            streamA.close()
            streamB.close()
            executor.shutdownNow()
            threadA?.join(TASK_JOIN_MILLIS)
            threadB?.join(TASK_JOIN_MILLIS)
            airPlaySession.close()
        }
    }

    @Test
    fun executorRejectionReleasesTheAcceptedStreamAndLeavesNoRuntimeChannel() {
        val executor = ManualTunnelExecutor()
        val run = prepareRun(executor)
        val stream = ScriptedTunnelStream(syncAcknowledgement())
        try {
            executor.rejectNextSubmission()
            assertFalse("a rejected control loop must not report acceptance", startTunnel(run, stream))
            assertNull("a rejected tunnel must never be published", runtimeChannel(run))
            assertTrue("the rejected stream must be released", stream.awaitReleased())
            assertEquals("the rejected stream must be released exactly once", 1, stream.releaseCount)
            assertEquals(1, debugLogCount("iAP2 tunnel executor rejected the link"))
            assertTrue(
                "a rejected tunnel must leave no queued publication",
                pendingTunnelChannels(run).isEmpty(),
            )
        } finally {
            closeRun(run)
            stream.close()
        }
    }

    @Test
    fun runClosedWhileTheTunnelIsQueuedLeavesNoStaleChannelAndDrainsWithoutControlIo() {
        val executor = ManualTunnelExecutor()
        val run = prepareRun(executor)
        val stream = ScriptedTunnelStream(syncAcknowledgement())
        var task: Runnable? = null
        try {
            assertTrue(startTunnel(run, stream))
            task = requireNotNull(executor.takeNext()) { "the tunnel task must be queued" }
            assertFalse(stream.released)

            closeRun(run)
            assertNull("a closed run must clear the runtime channel", runtimeChannel(run))
            assertTrue("closing the run must release the never-started tunnel", stream.awaitReleased())
            assertEquals(1, stream.releaseCount)
            assertTrue("closing the run must sweep every queued publication", pendingTunnelChannels(run).isEmpty())
            val sendsAfterClose = stream.sendCount

            // A drained executor would still run the queued task (or the real one drops it); either way
            // the task itself must not start control I/O for a closed run.
            runTaskAndWait(requireNotNull(task))
            assertEquals("the drained task must not write the released stream", sendsAfterClose, stream.sendCount)
            assertEquals("the drained task must not release the stream twice", 1, stream.releaseCount)
            assertNull(runtimeChannel(run))
        } finally {
            executor.shutdownNow()
            closeRun(run)
            stream.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------------

    private fun prepareRun(executor: ManualTunnelExecutor): Any {
        field(controller, "tunnelExecutor", executor)
        field(controller, "mfiSession", MfiSession(FakeMfiAuthenticator(), null))
        val run = run(1)
        field(run, "wirelessRuntimeIdentification", identification)
        field(run, "wirelessAirPlayEndpoint", endpoint)
        return run
    }

    private fun run(generation: Int): Any {
        assertEquals(generation, lifecycle().beginWireless())
        val run = runType.getDeclaredConstructor(CarPlayController::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(controller, generation)
        field(controller, "wirelessRun", run)
        return run
    }

    private fun startTunnel(run: Any, stream: BlockingDuplexByteStream): Boolean =
        runType.getDeclaredMethod("startWirelessTunnelControl", BlockingDuplexByteStream::class.java)
            .apply { isAccessible = true }.invoke(run, stream) as Boolean

    private fun runtimeChannel(run: Any): Iap2Session? =
        runType.getDeclaredMethod("runtimeChannel").apply { isAccessible = true }.invoke(run) as Iap2Session?

    private fun pendingTunnelChannels(run: Any): List<*> = try {
        val publication = run.javaClass.getDeclaredField("pendingTunnelChannels").apply { isAccessible = true }
        (publication.get(run) as Collection<*>).toList()
    } catch (_: NoSuchFieldException) {
        // The baseline publishes at handler time and keeps no pending publication list.
        emptyList<Any>()
    }

    private fun closeRun(run: Any) = runType.getDeclaredMethod(
        "closeWirelessStack", CarPlayVpnService::class.java, Int::class.javaObjectType,
    ).apply { isAccessible = true }.invoke(run, null, null)

    private fun lifecycle(): ControllerLifecycleCore = field(controller, "lifecycle") as ControllerLifecycleCore

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun field(owner: Any, name: String, value: Any) =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)

    /** The stream a session reads and writes; identity proves which channel the query names. */
    private fun streamOf(channel: Iap2Session?): BlockingDuplexByteStream? {
        if (channel == null) return null
        val csm = channel.javaClass.getDeclaredField("channel").apply { isAccessible = true }.get(channel)
        val link = csm.javaClass.getDeclaredField("link").apply { isAccessible = true }.get(csm)
        return link.javaClass.getDeclaredField("underlying").apply { isAccessible = true }.get(link)
            as BlockingDuplexByteStream
    }

    private fun streamLabel(stream: BlockingDuplexByteStream?, a: ScriptedTunnelStream, b: ScriptedTunnelStream): String =
        when (stream) {
            null -> "none"
            a -> "streamA"
            b -> "streamB"
            else -> "other"
        }

    private fun runTask(task: Runnable, name: String): Thread =
        Thread(task, name).apply { isDaemon = true; start() }

    private fun runTaskAndWait(task: Runnable) {
        val thread = runTask(task, "tunnel-drain")
        thread.join(TASK_JOIN_MILLIS)
        assertFalse("the drained tunnel task must return without a running control loop", thread.isAlive)
    }

    private fun closeQuietly(channel: Iap2Session) {
        try {
            channel.close()
        } catch (_: Exception) {
            // The test asserts ownership/identity, not protocol teardown failures.
        }
    }

    private fun debugLogCount(fragment: String): Int =
        synchronized(debugLogLock) { debugLogs.count { it.contains(fragment) } }

    private fun awaitDebugLogCount(fragment: String, expected: Int, timeoutMillis: Long = SIGNAL_TIMEOUT_MILLIS): Boolean {
        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        synchronized(debugLogLock) {
            while (debugLogs.count { it.contains(fragment) } < expected) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) return false
                debugLogLock.wait(remainingNanos / NANOS_PER_MILLI, (remainingNanos % NANOS_PER_MILLI).toInt())
            }
            return true
        }
    }

    private class FakeMfiAuthenticator : MfiAuthenticator {
        override fun protocolMajor(): Int = 2
        override fun readCertificate(maximumOutputLength: Int): ByteArray =
            ByteArray(maximumOutputLength.coerceAtMost(1))
        override fun signChallenge(challenge: ByteArray): ByteArray = ByteArray(64)
        override val certificateType: MfiCertificateType = MfiCertificateType.MFI
    }

    /**
     * A tunnel executor whose tasks are only run when the test says so. The real executor is a
     * single-thread executor; queuing without running is exactly the interleaving this test needs.
     */
    private class ManualTunnelExecutor : AbstractExecutorService() {
        private val lock = Object()
        private val queued = ArrayDeque<Runnable>()
        private var rejecting = false
        private var shutdown = false

        fun takeNext(): Runnable? = synchronized(lock) { if (queued.isEmpty()) null else queued.removeFirst() }

        fun rejectNextSubmission() {
            synchronized(lock) { rejecting = true }
        }

        override fun execute(command: Runnable) {
            synchronized(lock) {
                if (shutdown || rejecting) {
                    rejecting = false
                    throw RejectedExecutionException("test executor rejected the command")
                }
                queued += command
            }
        }

        override fun shutdown() {
            synchronized(lock) { shutdown = true }
        }

        override fun shutdownNow(): MutableList<Runnable> = synchronized(lock) {
            shutdown = true
            val pending = queued.toMutableList()
            queued.clear()
            pending
        }

        override fun isShutdown(): Boolean = synchronized(lock) { shutdown }
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    /**
     * The byte stream the tunnel link owns. It answers the accessory-initiated synchronization once with
     * a canned ack, then honors the caller's receive timeout, so a control loop blocks without sleeps.
     */
    private class ScriptedTunnelStream(private val syncAcknowledgement: ByteArray) : BlockingDuplexByteStream {
        private val lock = Object()
        private val sent = mutableListOf<ByteArray>()
        private var acknowledgements = 1
        private var releases = 0

        val sendCount: Int get() = synchronized(lock) { sent.size }
        val releaseCount: Int get() = synchronized(lock) { releases }
        val released: Boolean get() = synchronized(lock) { releases > 0 }

        override fun send(data: ByteArray) {
            synchronized(lock) {
                if (releases > 0) return
                sent += data.copyOf()
                lock.notifyAll()
            }
        }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            synchronized(lock) {
                if (releases > 0) return EOF
                if (acknowledgements > 0) {
                    acknowledgements -= 1
                    return syncAcknowledgement
                }
                if (timeoutMillis <= 0L) return null
                val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
                while (releases == 0) {
                    val remainingNanos = deadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0L) return null
                    lock.wait(remainingNanos / NANOS_PER_MILLI, (remainingNanos % NANOS_PER_MILLI).toInt())
                }
                return EOF
            }
        }

        override fun close() {
            synchronized(lock) {
                releases += 1
                lock.notifyAll()
            }
        }

        fun awaitSendCount(count: Int, timeoutMillis: Long = SIGNAL_TIMEOUT_MILLIS): Boolean =
            await(timeoutMillis) { sent.size >= count }

        fun awaitReleased(timeoutMillis: Long = SIGNAL_TIMEOUT_MILLIS): Boolean =
            await(timeoutMillis) { releases > 0 }

        private fun await(timeoutMillis: Long = SIGNAL_TIMEOUT_MILLIS, predicate: () -> Boolean): Boolean {
            val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
            synchronized(lock) {
                while (!predicate()) {
                    val remainingNanos = deadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0L) return false
                    lock.wait(remainingNanos / NANOS_PER_MILLI, (remainingNanos % NANOS_PER_MILLI).toInt())
                }
                return true
            }
        }

        private companion object {
            val EOF = ByteArray(0)
        }
    }

    private companion object {
        const val READY = "IAP2 READY [wireless-tunnel] ready=true"
        const val STARTING = "wireless iAP2 runtime tunnel control starting"
        const val NANOS_PER_MILLI = 1_000_000L
        const val SIGNAL_TIMEOUT_MILLIS = 5_000L
        const val TASK_JOIN_MILLIS = 5_000L

        /** A peer synchronization ack matching the tunnel link's own negotiation. */
        fun syncAcknowledgement(): ByteArray {
            val synchronization = Iap2LinkEngine.SynchronizationPayload(
                maxOutgoing = 8,
                maxLength = 4096,
                // Long timers: a parked control loop must not retransmit while the test holds it.
                retransmissionTimeoutMillis = 60_000,
                acknowledgementTimeoutMillis = 60_000,
                maxRetransmissions = 4,
                maxAcknowledgements = 3,
                sessions = listOf(
                    Iap2LinkEngine.SessionDescriptor(Iap2LinkEngine.CONTROL_SESSION_ID, 0, 2),
                    Iap2LinkEngine.SessionDescriptor(Iap2LinkEngine.FILE_TRANSFER_SESSION_ID, 1, 2),
                ),
            )
            return packet(0xc0, 1, 99, 0, synchronization.encode())
        }

        fun packet(control: Int, sequence: Int, acknowledgement: Int, sessionId: Int, payload: ByteArray): ByteArray {
            val length = 10 + payload.size
            val header = byteArrayOf(
                0xff.toByte(), 0x5a, (length ushr 8).toByte(), length.toByte(),
                control.toByte(), sequence.toByte(), acknowledgement.toByte(), sessionId.toByte(), 0,
            )
            header[8] = checksum(header.copyOf(8)).toByte()
            return header + payload + checksum(payload).toByte()
        }

        fun checksum(bytes: ByteArray): Int = (-bytes.sumOf { it.toInt() and 0xff }) and 0xff
    }
}
