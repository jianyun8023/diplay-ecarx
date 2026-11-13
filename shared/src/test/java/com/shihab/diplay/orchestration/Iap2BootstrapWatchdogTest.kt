package com.shihab.diplay.orchestration

import com.shihab.diplay.transport.Iap2BootstrapPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2BootstrapWatchdogTest {
    private var now = 0L
    private var scheduledDelay: Long? = null
    private var timer: (() -> Unit)? = null
    private val stalls = mutableListOf<Iap2BootstrapStall>()

    private fun watchdog() = Iap2BootstrapWatchdog(
        schedule = { delay, action ->
            scheduledDelay = delay
            timer = action
            val cancel: () -> Unit = { timer = null }
            cancel
        },
        onTimeout = { stalls += it },
        nowNanos = { now * NANOS_PER_MILLISECOND },
    )

    @Test fun nothingIsArmedUntilTheFirstCheckpoint() {
        watchdog()
        assertNull(timer)
        assertTrue(stalls.isEmpty())
    }

    @Test fun rfcommArmsTheCsmBudget() {
        val watch = watchdog()
        watch.mark(Iap2BootstrapPhase.RFCOMM_CONNECTED)
        assertEquals(Iap2BootstrapPolicy.RFCOMM_TO_CSM_MILLIS, scheduledDelay)
        assertTrue(stalls.isEmpty())
    }

    @Test fun eachCheckpointRearmsWithItsOwnBudget() {
        val watch = watchdog()
        watch.mark(Iap2BootstrapPhase.RFCOMM_CONNECTED)
        assertEquals(Iap2BootstrapPolicy.RFCOMM_TO_CSM_MILLIS, scheduledDelay)
        watch.mark(Iap2BootstrapPhase.CSM_ESTABLISHED)
        assertEquals(Iap2BootstrapPolicy.CSM_TO_MFI_MILLIS, scheduledDelay)
        watch.mark(Iap2BootstrapPhase.MFi_AUTHENTICATED)
        assertEquals(Iap2BootstrapPolicy.MFI_TO_PROGRESS_MILLIS, scheduledDelay)
        watch.mark(Iap2BootstrapPhase.WIRELESS_CARPLAY_AVAILABLE)
        assertEquals(Iap2BootstrapPolicy.PARTIAL_PROGRESS_MILLIS, scheduledDelay)
        watch.mark(Iap2BootstrapPhase.TRANSPORT_IDENTIFIED)
        assertEquals(Iap2BootstrapPolicy.PARTIAL_PROGRESS_MILLIS, scheduledDelay)
    }

    @Test fun theCsmBudgetCoversTheObservedLongTail() {
        // Real-car CSM bring-up has been observed as late as 34.6 s; the budget must not fire before
        // then, and the blocking safety net must sit above the watchdog budget.
        assertTrue(Iap2BootstrapPolicy.RFCOMM_TO_CSM_MILLIS > 34_626L)
        assertTrue(WirelessBootstrapRecovery.CSM_TIMEOUT_MILLIS > Iap2BootstrapPolicy.RFCOMM_TO_CSM_MILLIS)
        val watch = watchdog()
        watch.mark(Iap2BootstrapPhase.RFCOMM_CONNECTED)
        now = 34_626
        watch.mark(Iap2BootstrapPhase.CSM_ESTABLISHED)
        assertTrue(stalls.isEmpty())
        assertEquals(Iap2BootstrapPolicy.CSM_TO_MFI_MILLIS, scheduledDelay)
    }

    @Test fun startSessionHandsOffAndTunnelBecomesTerminal() {
        val watch = watchdog()
        for (phase in listOf(
            Iap2BootstrapPhase.RFCOMM_CONNECTED,
            Iap2BootstrapPhase.CSM_ESTABLISHED,
            Iap2BootstrapPhase.MFi_AUTHENTICATED,
            Iap2BootstrapPhase.WIRELESS_CARPLAY_AVAILABLE,
            Iap2BootstrapPhase.TRANSPORT_IDENTIFIED,
            Iap2BootstrapPhase.START_SESSION_SENT,
        )) {
            watch.mark(phase)
        }
        // AirPlay TCP and the handoff watchdog own the tunnel; no bootstrap timer past StartSession.
        assertNull(timer)
        assertFalse(watch.finished)
        watch.mark(Iap2BootstrapPhase.TUNNEL_ESTABLISHED)
        assertTrue(watch.finished)
        assertTrue(stalls.isEmpty())
    }

    @Test fun aJumpedCheckpointAdvancesStraightToTheNextMissingBudget() {
        val watch = watchdog()
        watch.mark(Iap2BootstrapPhase.RFCOMM_CONNECTED)
        // CSM never reported on its own; a jump to MFi must next wait for availability.
        watch.mark(Iap2BootstrapPhase.MFi_AUTHENTICATED)
        assertEquals(65_000L, scheduledDelay)
    }

    @Test fun anEarlierCheckpointCannotMoveTheLimitBackwards() {
        val watch = watchdog()
        watch.mark(Iap2BootstrapPhase.RFCOMM_CONNECTED)
        watch.mark(Iap2BootstrapPhase.CSM_ESTABLISHED)
        watch.mark(Iap2BootstrapPhase.MFi_AUTHENTICATED)
        val armed = scheduledDelay
        // A repeated/out-of-order earlier checkpoint must not rewind the budget.
        watch.mark(Iap2BootstrapPhase.CSM_ESTABLISHED)
        assertEquals(armed, scheduledDelay)
    }

    @Test fun aStallBeforeCsmIsABluetoothLinkFailure() {
        val watch = watchdog()
        watch.mark(Iap2BootstrapPhase.RFCOMM_CONNECTED)
        now = Iap2BootstrapPolicy.RFCOMM_TO_CSM_MILLIS
        timer!!()
        assertEquals(1, stalls.size)
        val stall = stalls.single()
        assertEquals(Iap2BootstrapPhase.RFCOMM_CONNECTED, stall.reached)
        assertEquals(Iap2BootstrapPhase.CSM_ESTABLISHED, stall.missing)
        assertEquals(ConnectionFailureCause.WIRELESS_BLUETOOTH_LINK_FAILED, stall.cause)
        assertEquals(Iap2BootstrapPolicy.RFCOMM_TO_CSM_MILLIS, stall.waitedMillis)
        assertTrue(watch.finished)
    }

    @Test fun aStallAfterCsmIsAPhoneNotRespondingFailure() {
        val watch = watchdog()
        watch.mark(Iap2BootstrapPhase.RFCOMM_CONNECTED)
        now = 1_000
        watch.mark(Iap2BootstrapPhase.CSM_ESTABLISHED)
        now = 1_000 + 30_000
        timer!!()
        val stall = stalls.single()
        assertEquals(Iap2BootstrapPhase.CSM_ESTABLISHED, stall.reached)
        assertEquals(Iap2BootstrapPhase.MFi_AUTHENTICATED, stall.missing)
        assertEquals(ConnectionFailureCause.WIRELESS_PHONE_NOT_RESPONDING, stall.cause)
        assertEquals(30_000L, stall.waitedMillis)
    }

    @Test fun terminateSuppressesTheQueuedTimeout() {
        val watch = watchdog()
        watch.mark(Iap2BootstrapPhase.RFCOMM_CONNECTED)
        val queued = timer!!
        assertTrue(watch.terminate())
        assertTrue(watch.finished)
        queued()
        assertTrue(stalls.isEmpty())
        assertFalse(watch.terminate())
    }

    @Test fun aReplacedTimerFromAnEarlierCheckpointCannotExpire() {
        val watch = watchdog()
        watch.mark(Iap2BootstrapPhase.RFCOMM_CONNECTED)
        val firstTimer = timer!!
        watch.mark(Iap2BootstrapPhase.CSM_ESTABLISHED)
        val secondTimer = timer!!
        // The first timer belongs to the RFCOMM stage and was superseded by the CSM stage.
        now = Iap2BootstrapPolicy.RFCOMM_TO_CSM_MILLIS
        firstTimer()
        assertTrue(stalls.isEmpty())
        now = Iap2BootstrapPolicy.RFCOMM_TO_CSM_MILLIS + Iap2BootstrapPolicy.CSM_TO_MFI_MILLIS
        secondTimer()
        assertEquals(1, stalls.size)
    }

    @Test fun aMarkAfterAFailureIsIgnored() {
        val watch = watchdog()
        watch.mark(Iap2BootstrapPhase.RFCOMM_CONNECTED)
        now = Iap2BootstrapPolicy.RFCOMM_TO_CSM_MILLIS
        timer!!()
        watch.mark(Iap2BootstrapPhase.CSM_ESTABLISHED)
        assertEquals(Iap2BootstrapPolicy.RFCOMM_TO_CSM_MILLIS, scheduledDelay)
        assertEquals(1, stalls.size)
    }

    @Test fun aTimerQueuedForThePreviousStageCannotReportAfterStartSession() {
        val watch = watchdog()
        for (phase in listOf(
            Iap2BootstrapPhase.RFCOMM_CONNECTED,
            Iap2BootstrapPhase.CSM_ESTABLISHED,
            Iap2BootstrapPhase.MFi_AUTHENTICATED,
            Iap2BootstrapPhase.WIRELESS_CARPLAY_AVAILABLE,
            Iap2BootstrapPhase.TRANSPORT_IDENTIFIED,
        )) {
            watch.mark(phase)
        }
        val queuedForTransport = timer!!
        watch.mark(Iap2BootstrapPhase.START_SESSION_SENT)
        queuedForTransport()
        assertTrue(stalls.isEmpty())
        assertFalse(watch.finished)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
