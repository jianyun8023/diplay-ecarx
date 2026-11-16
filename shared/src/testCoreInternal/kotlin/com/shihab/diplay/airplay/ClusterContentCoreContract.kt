package com.shihab.diplay.airplay

import java.util.Random
import org.junit.Assert.*

/** The pre-migration Kotlin rules, independent of native tokens and operation numbers. */
private class KotlinClusterContent(initial: String?) {
    data class Content(val stream: Int, val requested: String?, val displayed: String?, val shown: Boolean = true)
    data class Plan(val accepted: Boolean = false, val sendUrl: String? = null,
        val needKeyFrame: Boolean = false, val rotateRouteToken: Boolean = false)
    private var retained = initial
    var content: Content? = null
        private set

    fun accepted(stream: Int, initial: String?): Plan {
        content = Content(stream, retained, initial)
        return Plan(accepted = true, rotateRouteToken = true)
    }
    fun clear(): Plan {
        content = null
        return Plan(accepted = true)
    }
    fun select(url: String, send: Boolean, closed: Boolean, stream: Int, configured: Boolean): Plan {
        val current = content ?: return Plan()
        if (closed || current.stream != stream || !configured) return Plan()
        retained = url
        content = current.copy(requested = url)
        return Plan(accepted = true, sendUrl = url.takeIf { send })
    }
    fun restore(url: String, send: Boolean, closed: Boolean, configured: Boolean): Plan {
        if (closed || !configured) return Plan()
        retained = url
        val current = content?.copy(requested = url)?.also { content = it }
        if (current == null || !send || (current.shown && current.displayed == url)) return Plan(accepted = true)
        return Plan(accepted = true, sendUrl = url)
    }
    fun visibility(shown: Boolean, closed: Boolean, stream: Int): Plan {
        if (closed || stream <= 0) return Plan()
        val current = content ?: return Plan()
        if (!shown) return Plan(accepted = true)
        return Plan(accepted = current.requested != null, sendUrl = current.requested)
    }
    fun reapply(): Plan {
        val current = content ?: return Plan()
        val requested = current.requested ?: return Plan()
        return if (current.shown && requested != current.displayed) Plan(true, requested) else Plan()
    }
    fun showAllowed(closed: Boolean, stream: Int): Boolean = content?.let { !closed && stream == it.stream } ?: false
    fun keyFrame(uiSent: Boolean, closed: Boolean, stream: Int): Boolean =
        content?.let { uiSent && stream == it.stream && !closed } ?: false
    fun commitShow(expected: Int, url: String, closed: Boolean, stream: Int, uiSent: Boolean, sent: Boolean): Plan {
        val current = content ?: return Plan()
        if (closed || current.stream != expected || stream != expected) {
            if (uiSent) content = current.copy(displayed = null)
            return Plan(rotateRouteToken = uiSent)
        }
        if (uiSent) content = current.copy(displayed = if (sent) url else null, shown = true)
        return Plan(accepted = sent, rotateRouteToken = uiSent)
    }
    fun commitStop(expected: Int, closed: Boolean, sent: Boolean): Plan {
        val current = content ?: return Plan()
        if (closed || current.stream != expected) {
            if (sent) content = current.copy(displayed = null, shown = false)
            return Plan(rotateRouteToken = sent)
        }
        if (sent) content = current.copy(shown = false)
        return Plan(accepted = sent)
    }
}

internal object ClusterContentCoreContract {
    fun transitionsMatchIndependentKotlin() {
        val urls = listOf("map", "turn", "", "a\u0000b", "\ud800", "\udc00", "\ud800x\udc00", "地图🗺")
        val random = Random(0x434c5553544552)
        for (initial in listOf(null) + urls) {
            val reference = KotlinClusterContent(initial)
            var snapshot = ClusterContentCore.create(initial)
            repeat(250) { step ->
                val previous = snapshot
                val previousToken = snapshot.token.clone()
                val previousRoute = ClusterContentCore.route(snapshot)
                val url = urls[random.nextInt(urls.size)]
                val stream = random.nextInt(7) - 2
                val expected = random.nextInt(7) - 2
                val closed = random.nextInt(5) == 0
                val configured = random.nextInt(5) != 0
                val send = random.nextBoolean()
                val uiSent = random.nextBoolean()
                val frameSent = uiSent && random.nextBoolean()
                val (actual, wanted) = when (random.nextInt(9)) {
                    0 -> ClusterContentCore.accepted(snapshot, stream, initial) to reference.accepted(stream, initial)
                    1 -> ClusterContentCore.clear(snapshot) to reference.clear()
                    2 -> ClusterContentCore.select(snapshot, url, send, closed, stream, configured) to
                        reference.select(url, send, closed, stream, configured)
                    3 -> ClusterContentCore.restore(snapshot, url, send, closed, configured) to
                        reference.restore(url, send, closed, configured)
                    4 -> ClusterContentCore.visibility(snapshot, send, closed, stream) to reference.visibility(send, closed, stream)
                    5 -> ClusterContentCore.reapply(snapshot) to reference.reapply()
                    6 -> ClusterContentCore.commitShow(snapshot, expected, url, closed, stream, uiSent, frameSent) to
                        reference.commitShow(expected, url, closed, stream, uiSent, frameSent)
                    7 -> ClusterContentCore.commitStop(snapshot, expected, closed, uiSent) to reference.commitStop(expected, closed, uiSent)
                    else -> ClusterContentCore.accepted(snapshot, stream, url) to reference.accepted(stream, url)
                }
                val context = "initial=$initial step=$step"
                assertEquals(context, wanted.accepted, actual.accepted)
                assertEquals(context, wanted.sendUrl, actual.sendUrl)
                assertEquals(context, wanted.needKeyFrame, actual.needKeyFrame)
                assertEquals(context, wanted.rotateRouteToken, actual.rotateRouteToken)
                snapshot = actual.snapshot
                assertEquals(context, reference.content?.stream ?: 0, snapshot.stream)
                assertEquals(context, reference.content?.let { ClusterContentCore.Route(it.stream, it.displayed) },
                    ClusterContentCore.route(snapshot))
                assertEquals(context, reference.reapply().sendUrl, ClusterContentCore.reapply(snapshot).sendUrl)
                assertEquals(context, reference.visibility(true, false, 1).sendUrl,
                    ClusterContentCore.visibility(snapshot, true, false, 1).sendUrl)
                assertEquals(context, reference.showAllowed(closed, stream), ClusterContentCore.showAllowed(snapshot, closed, stream))
                assertEquals(context, reference.keyFrame(uiSent, closed, stream), ClusterContentCore.keyFrame(snapshot, uiSent, closed, stream))
                assertEquals(context, reference.content?.takeIf { !closed && it.stream == stream }
                    ?.let { ClusterContentCore.Route(it.stream, it.displayed) }, ClusterContentCore.route(snapshot, closed, stream))
                assertArrayEquals("Already published snapshots must remain immutable", previousToken, previous.token)
                assertEquals(previousRoute, ClusterContentCore.route(previous))
            }
        }
    }

    fun retainedUtf16AndLateResultsKeepTheOldContract() {
        val initial = "\ud800\u0000初始"
        val selection = "\udc00\uffff地图🗺"
        var snapshot = ClusterContentCore.create(initial)
        val seed = ClusterContentCore.restore(snapshot, selection, true, false, true)
        assertTrue(seed.accepted)
        assertNull(seed.sendUrl)
        snapshot = ClusterContentCore.accepted(seed.snapshot, 1, initial).snapshot
        assertEquals(initial, ClusterContentCore.route(snapshot)?.displayed)
        assertEquals(selection, ClusterContentCore.reapply(snapshot).sendUrl)
        val partial = ClusterContentCore.commitShow(snapshot, 1, selection, false, 1, true, false)
        assertFalse(partial.accepted)
        assertTrue(partial.rotateRouteToken)
        assertNull(ClusterContentCore.route(partial.snapshot)?.displayed)
        assertEquals(selection, ClusterContentCore.reapply(partial.snapshot).sendUrl)
        val delivered = ClusterContentCore.commitShow(partial.snapshot, 1, selection, false, 1, true, true)
        assertEquals(selection, ClusterContentCore.route(delivered.snapshot)?.displayed)
        assertNull(ClusterContentCore.restore(delivered.snapshot, selection, true, false, true).sendUrl)
        val stopped = ClusterContentCore.commitStop(delivered.snapshot, 1, false, true)
        assertTrue(stopped.accepted)
        assertFalse(stopped.rotateRouteToken)
        assertNull(ClusterContentCore.reapply(stopped.snapshot).sendUrl)
        assertEquals(selection, ClusterContentCore.restore(stopped.snapshot, selection, true, false, true).sendUrl)
        snapshot = ClusterContentCore.accepted(ClusterContentCore.clear(stopped.snapshot).snapshot, 2, initial).snapshot
        assertEquals(selection, ClusterContentCore.reapply(snapshot).sendUrl)
        val late = ClusterContentCore.commitShow(snapshot, 1, selection, false, 2, true, true)
        assertFalse(late.accepted)
        assertTrue(late.rotateRouteToken)
        assertNull(ClusterContentCore.route(late.snapshot)?.displayed)
        assertEquals(initial, ClusterContentCore.route(snapshot)?.displayed)
    }
}
