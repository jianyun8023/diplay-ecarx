package com.shihab.diplay.orchestration

import java.util.Random
import org.junit.Assert.assertEquals

/** Independent model of the pre-migration Controller fields and their original update sites. */
private class KotlinControllerDashboard(initialUrl: String?) {
    var accepted = initialUrl
    var shown = true
    var physical = false
    var epoch = 0
    var pair: Pair<Any, Int>? = null
    fun send(session: Any, stream: Int) = shown || pair != session to stream
    fun select(url: String, succeeded: Boolean, current: Boolean): Boolean {
        val sent = succeeded && current
        if (sent) { accepted = url; epoch++ }
        return sent
    }
    fun restore(succeeded: Boolean, current: Boolean, stream: Int) {
        if (succeeded && current && stream > 0) epoch++
    }
    fun visibility(session: Any, stream: Int, next: Boolean, hasUrl: Boolean): Boolean {
        if (pair != session to stream) { pair = session to stream; shown = true }
        return !(next == shown && (!next || hasUrl))
    }
    fun visibilityCommitted(next: Boolean) { shown = next; epoch++ }
    fun output(visible: Boolean, closed: Boolean) {
        val next = visible && !closed
        if (physical != next) { physical = next; epoch++ }
    }
    fun route(session: Any, stream: Int, url: String?, closed: Boolean): Int? =
        if (!closed && physical && stream > 0 && send(session, stream) &&
            (url == "maps:/car/instrumentcluster/map" || url == "maps:/car/instrumentcluster")) epoch else null
}

internal object ControllerDashboardContract {
    fun verifyTransitions() {
        val urls = listOf(null, "", "maps:/car/instrumentcluster/map", "maps:/car/instrumentcluster",
            "maps:/car/instrumentcluster/turnbyturn", "unknown", "\u0000\uD800x\uDC00\uFFFF")
        val sessions = listOf(Any(), Any())
        val random = Random(20261116)
        for (initial in urls) {
            val old = KotlinControllerDashboard(initial)
            val core = ControllerDashboardCore(initial)
            repeat(600) {
                val session = sessions[random.nextInt(2)]
                val stream = random.nextInt(4) - 1
                val first = random.nextBoolean()
                val second = random.nextBoolean()
                when (random.nextInt(4)) {
                    0 -> {
                        val url = urls[1 + random.nextInt(urls.size - 1)]!!
                        assertEquals(old.send(session, stream), core.selectionPlan(session, stream).send)
                        assertEquals(old.select(url, first, second), core.commitSelection(url, first, second))
                    }
                    1 -> { old.restore(first, second, stream); core.commitRestore(first, second, stream) }
                    2 -> {
                        val expected = old.visibility(session, stream, first, second)
                        assertEquals(expected, core.visibilityPlan(session, stream, first, second).send)
                        if (expected && random.nextBoolean()) {
                            old.visibilityCommitted(first); core.commitVisibility(first)
                        }
                    }
                    else -> { old.output(first, second); core.setPhysicalVisible(first, second) }
                }
                for (candidate in sessions) for (id in -1..2) {
                    val restore = core.restorePlan(candidate, id)
                    assertEquals(old.accepted, restore?.url)
                    if (restore != null) assertEquals(old.send(candidate, id), restore.send)
                    for (url in urls) for (closed in listOf(false, true)) {
                        assertEquals(old.route(candidate, id, url, closed), core.routeEpoch(candidate, id, url, closed))
                    }
                }
            }
        }
    }
}
