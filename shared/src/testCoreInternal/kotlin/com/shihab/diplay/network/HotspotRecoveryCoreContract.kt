package com.shihab.diplay.network

import java.net.InetAddress
import org.junit.Assert.assertEquals

object HotspotRecoveryCoreContract {
    fun verifyReadiness() {
        val address = InetAddress.getByName("192.168.49.1")
        fun snapshot(index: Int): HotspotNetworkSnapshot = HotspotNetworkSnapshot(
            if (index == 0) emptyList() else listOf(HotspotInterfaceSnapshot("ap0", index, true, listOf(address), true)),
            setOf("ap0"), emptySet(), null,
        )
        for (sequence in listOf(listOf(1), listOf(0, 1), listOf(1, 0, 1), listOf(1, 2), listOf(0))) {
            fun run(reference: Boolean): List<Any?> {
                var now = 0L
                var count = 0
                val events = mutableListOf<Any?>()
                val sample = { events.add("sample"); snapshot(sequence[minOf(count++, sequence.lastIndex)]) }
                val cancelled = { false }
                val pause: (Long) -> Unit = { now += it; events.add("pause:$it") }
                val clock = { events.add("clock:$now"); now }
                val log: (String) -> Unit = { events.add(it) }
                val result = runCatching {
                    if (reference) KotlinManualHotspotReadiness(sample, cancelled, pause, clock, log).await(1100)
                    else ManualHotspotReadiness(sample, cancelled, pause, clock, log).await(1100)
                }
                events.add(result.getOrNull())
                events.add(result.exceptionOrNull()?.javaClass?.name)
                events.add(result.exceptionOrNull()?.message)
                return events
            }
            assertEquals("sequence=$sequence", run(true), run(false))
        }
    }
    fun verifyRadioRecovery() {
        for (sequence in listOf(listOf(false, true), listOf(false), listOf<Boolean?>(null), listOf(true))) {
            for (cancelAt in listOf(-1, 0, 2)) {
                fun run(reference: Boolean): List<Any?> {
                    var now = 0L
                    var count = 0
                    val events = mutableListOf<Any?>()
                    val state = { sequence[minOf(count++, sequence.lastIndex)].also { events.add("state:$it") } }
                    val current = { cancelAt < 0 || count < cancelAt }
                    val clock = { events.add("clock:$now"); now }
                    val pause: (Long) -> Unit = { now += it; events.add("pause:$it") }
                    val log: (String) -> Unit = { events.add(it) }
                    val result = if (reference) KotlinCarHotspotReturn.await(state, current, 1100, clock, pause, log).name
                    else CarHotspotReturn.await(state, current, 1100, clock, pause, log).name
                    events.add(result)
                    return events
                }
                assertEquals("sequence=$sequence cancel=$cancelAt", run(true), run(false))
            }
        }
        KotlinCarRadioOutage.clear(); CarRadioOutage.clear()
        for (marked in listOf(-1L, 0L, 1L, Long.MAX_VALUE)) {
            KotlinCarRadioOutage.mark(marked); CarRadioOutage.mark(marked)
            for (now in listOf(-1L, 0L, 1L, 9L, 10L, Long.MAX_VALUE, Long.MIN_VALUE)) {
                assertEquals(KotlinCarRadioOutage.active(now, 10), CarRadioOutage.active(now, 10))
            }
        }
        KotlinCarRadioOutage.clear(); CarRadioOutage.clear()
        assertEquals(KotlinCarRadioOutage.active(0, 10), CarRadioOutage.active(0, 10))
    }
}
