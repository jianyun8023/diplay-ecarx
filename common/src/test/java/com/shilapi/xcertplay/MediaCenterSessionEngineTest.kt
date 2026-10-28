package com.shilapi.xcertplay

import android.net.Uri
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.mediacenter.MediaCenterSessionEngine
import com.shilapi.xcertplay.mediacenter.MediaCenterTransport
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class MediaCenterSessionEngineTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Fake(val id: String, val events: MutableList<String>) : MediaCenterTransport {
        override var registered = false
        var alive = true
        override val connected get() = alive
        var claims = 0
        var publishes = 0
        var accepted = true
        var granted = true
        var clean = true
        var connectFailure = false
        var clearFailure = false
        var onRegister: () -> Unit = {}
        var onClaim: () -> Unit = {}
        override fun connect() { events += "connect:$id"; if (connectFailure) throw SecurityException() }
        override fun register(now: CarPlayNowPlaying, artwork: Uri?) { events += "register:$id"; onRegister(); registered = true }
        override fun requestPlay(): Boolean { claims++; onClaim(); return granted }
        override fun publish(now: CarPlayNowPlaying, artwork: Uri?): Boolean { publishes++; return accepted }
        override fun clear() { events += "clear:$id"; if (clearFailure) throw IllegalStateException() }
        override fun unregister(): Boolean { events += "unregister:$id"; if (clean) registered = false; return clean }
        override fun close() { events += "close:$id" }
    }
    private class Fixture {
        val queue = Queue()
        val events = mutableListOf<String>()
        val instances = mutableMapOf<Any, Fake>()
        var clock = 1L
        val engine = MediaCenterSessionEngine(queue, { owner, _ -> instances.getValue(owner) }, { clock })
        fun fake(id: String): Any = Any().also { instances[it] = Fake(id, events) }
        fun frame(owner: Any, playing: Boolean = true, source: String = "music", valid: () -> Boolean = { true }) =
            MediaCenterSessionEngine.Desired(owner, CarPlayNowPlaying(title = "real track", playing = playing, sourceApp = source), null, valid)
        fun update(value: MediaCenterSessionEngine.Desired?) { engine.update(value); queue.drain() }
    }
    @Test fun noOwnerOrEmptyMediaNeverConnects() {
        val f = Fixture(); val owner = f.fake("a")
        f.update(null)
        f.update(MediaCenterSessionEngine.Desired(owner, CarPlayNowPlaying(), null) { true })
        f.update(f.frame(owner, valid = { false }))
        assertTrue(f.events.isEmpty())
    }
    @Test fun pausedRealMediaRegistersWithoutClaimingOrSuppressingDirectKeys() {
        val f = Fixture(); val a = f.fake("a")
        f.update(f.frame(a, false))
        assertTrue(f.instances.getValue(a).registered)
        assertEquals(0, f.instances.getValue(a).claims)
        assertFalse(f.engine.ownsMediaKeys(a))
    }
    @Test fun repeatedFramesAndTicksNeverReclaimPlayingSource() {
        val f = Fixture(); val a = f.fake("a")
        f.update(f.frame(a))
        repeat(20) { f.engine.tick(); f.update(f.frame(a)) }
        assertEquals(1, f.instances.getValue(a).claims)
        assertTrue(f.engine.ownsMediaKeys(a))
    }
    @Test fun pauseResumeAndSourceChangeEachRequestOnce() {
        val f = Fixture(); val a = f.fake("a")
        f.update(f.frame(a)); f.update(f.frame(a, false)); f.update(f.frame(a)); f.update(f.frame(a, source = "other"))
        assertEquals(3, f.instances.getValue(a).claims)
    }
    @Test fun rejectedPlaybackOrClaimPreservesDirectKeys() {
        val f = Fixture(); val a = f.fake("a")
        f.instances.getValue(a).accepted = false
        f.update(f.frame(a)); assertFalse(f.engine.ownsMediaKeys(a)); assertFalse(f.engine.ownsCallbacks(a))
        val g = Fixture(); val b = g.fake("b"); g.instances.getValue(b).granted = false
        g.update(g.frame(b)); assertFalse(g.engine.ownsMediaKeys(b)); assertTrue(g.engine.ownsCallbacks(b))
    }
    @Test fun explicitSourceSelectionClaimsOnceButClaimEchoDoesNotLoop() {
        val f = Fixture(); val a = f.fake("a")
        f.update(f.frame(a))
        f.instances.getValue(a).onClaim = { f.engine.select(a) }
        f.engine.select(a); f.queue.drain()
        assertEquals(2, f.instances.getValue(a).claims)
        assertTrue(f.queue.tasks.isEmpty())
    }
    @Test fun switchingOwnersUnregistersOriginalBeforeRegisteringNext() {
        val f = Fixture(); val a = f.fake("a"); val b = f.fake("b")
        f.update(f.frame(a)); f.update(f.frame(b))
        assertTrue(f.events.indexOf("unregister:a") < f.events.indexOf("register:b"))
        assertFalse(f.engine.ownsCallbacks(a)); assertTrue(f.engine.ownsCallbacks(b))
        f.engine.stop(a); f.queue.drain(); assertTrue(f.engine.ownsCallbacks(b))
    }
    @Test fun emptyOrInvalidMediaClearsAndUnregistersWithoutPlaceholder() {
        val f = Fixture(); val a = f.fake("a"); var valid = true
        f.update(f.frame(a, valid = { valid }))
        valid = false; f.engine.tick(); f.queue.drain()
        assertEquals(listOf("connect:a", "register:a", "clear:a", "unregister:a", "close:a"), f.events)
        assertFalse(f.engine.ownsCallbacks(a))
    }
    @Test fun failedCleanupBlocksAllNewOwnersAndDoesNotFallBack() {
        val f = Fixture(); val a = f.fake("a"); val b = f.fake("b")
        f.instances.getValue(a).clean = false
        f.update(f.frame(a)); f.update(f.frame(b))
        repeat(3) { f.engine.tick(); f.queue.drain() }
        assertTrue(f.engine.snapshot().uncertain)
        assertFalse(f.events.contains("connect:b"))
        assertEquals(1, f.events.count { it == "unregister:a" })
    }
    @Test fun lateRegistrationTokenIsCleanedUsingOldTransportNotDiscarded() {
        val f = Fixture(); val a = f.fake("a"); val b = f.fake("b")
        f.instances.getValue(a).onRegister = { f.engine.update(f.frame(b)) }
        f.update(f.frame(a))
        assertTrue(f.events.indexOf("unregister:a") < f.events.indexOf("register:b"))
        assertFalse(f.instances.getValue(a).registered)
        assertTrue(f.instances.getValue(b).registered)
    }
    @Test fun registerTimeoutRemainsUncertainUntilLateTokenCleanupAndDoesNotRetrySameSession() {
        val f = Fixture(); val a = f.fake("a")
        f.instances.getValue(a).onRegister = {
            f.clock += 9_000; f.engine.tick()
            assertTrue(f.engine.snapshot().uncertain)
            assertFalse(f.engine.ownsCallbacks(a))
        }
        f.update(f.frame(a))
        repeat(5) { f.engine.tick(); f.queue.drain() }
        assertEquals(1, f.events.count { it == "register:a" })
        assertEquals(1, f.events.count { it == "unregister:a" })
        assertFalse(f.instances.getValue(a).registered)
    }
    @Test fun permissionFailureIsVisibleAndNeverRetriedByTicker() {
        val f = Fixture(); val a = f.fake("a")
        f.instances.getValue(a).connectFailure = true
        f.update(f.frame(a)); repeat(3) { f.engine.tick(); f.queue.drain() }
        assertEquals(1, f.events.count { it == "connect:a" })
        assertFalse(f.events.contains("register:a"))
        assertEquals("UNAVAILABLE", f.engine.snapshot().phase)
    }
    @Test fun clearFailureStillAttemptsMandatoryUnregister() {
        val f = Fixture(); val a = f.fake("a")
        f.instances.getValue(a).clearFailure = true
        f.update(f.frame(a)); f.engine.stop(a); f.queue.drain()
        assertTrue(f.events.contains("unregister:a")); assertFalse(f.engine.snapshot().uncertain)
    }
    @Test fun disconnectedServiceImmediatelyRestoresDirectKeysAndDoesNotAutoRegisterAgain() {
        val f = Fixture(); val a = f.fake("a")
        f.update(f.frame(a)); assertTrue(f.engine.ownsMediaKeys(a))
        f.instances.getValue(a).alive = false
        assertFalse(f.engine.ownsMediaKeys(a))
        f.engine.tick(); f.queue.drain()
        assertTrue(f.events.contains("unregister:a"))
        f.instances.getValue(a).alive = true
        f.engine.tick(); f.queue.drain()
        assertEquals(1, f.events.count { it == "register:a" })
    }
    @Test fun manyUpdatesQueueOnlyOneWorkerAndLatestOwnerWins() {
        val f = Fixture(); val a = f.fake("a"); val b = f.fake("b")
        repeat(100) { f.engine.update(f.frame(a)) }
        f.engine.update(f.frame(b))
        assertEquals(1, f.queue.tasks.size)
        f.queue.drain()
        assertFalse(f.events.contains("connect:a")); assertTrue(f.events.contains("register:b"))
    }
}
