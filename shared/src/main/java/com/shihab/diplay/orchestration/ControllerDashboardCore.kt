package com.shihab.diplay.orchestration

/** Immutable native values and their platform identity are published together, never across I/O. */
internal class ControllerDashboardCore(initialUrl: String?) {
    private data class Snapshot(val token: LongArray, val session: Any? = null, val stream: Int = 0) {
        fun matches(session: Any, stream: Int) = this.session === session && this.stream == stream
    }
    data class SelectionPlan(val send: Boolean)
    data class RestorePlan(val url: String, val send: Boolean)
    data class VisibilityPlan(val send: Boolean)

    @Volatile private var snapshot = run {
        ControllerLifecycleCore.requireNative()
        Snapshot(NativeControllerDashboard.create(initialUrl))
    }

    fun selectionPlan(session: Any, stream: Int): SelectionPlan {
        val current = snapshot
        return SelectionPlan(NativeControllerDashboard.query(current.token, 0, current.matches(session, stream), false) != 0L)
    }

    @Synchronized fun commitSelection(url: String, succeeded: Boolean, current: Boolean): Boolean {
        val previous = snapshot
        snapshot = previous.copy(token = NativeControllerDashboard.transition(previous.token, 0, url, succeeded, current, 0))
        return NativeControllerDashboard.query(snapshot.token, 3, succeeded, current) != 0L
    }

    fun restorePlan(session: Any, stream: Int): RestorePlan? {
        val current = snapshot
        val url = NativeControllerDashboard.acceptedUrl(current.token) ?: return null
        return RestorePlan(url, NativeControllerDashboard.query(current.token, 0, current.matches(session, stream), false) != 0L)
    }

    @Synchronized fun commitRestore(succeeded: Boolean, current: Boolean, stream: Int) {
        val previous = snapshot
        snapshot = previous.copy(token = NativeControllerDashboard.transition(previous.token, 1, null, succeeded, current, stream))
    }

    @Synchronized fun visibilityPlan(session: Any, stream: Int, shown: Boolean, hasUrl: Boolean): VisibilityPlan {
        val previous = snapshot
        val token = NativeControllerDashboard.transition(previous.token, 2, null, previous.matches(session, stream), false, 0)
        snapshot = Snapshot(token, session, stream)
        return VisibilityPlan(NativeControllerDashboard.query(token, 1, shown, hasUrl) != 0L)
    }

    @Synchronized fun commitVisibility(shown: Boolean) {
        val previous = snapshot
        snapshot = previous.copy(token = NativeControllerDashboard.transition(previous.token, 3, null, shown, false, 0))
    }

    @Synchronized fun setPhysicalVisible(visible: Boolean, closed: Boolean) {
        val previous = snapshot
        snapshot = previous.copy(token = NativeControllerDashboard.transition(previous.token, 4, null, visible, closed, 0))
    }

    @Synchronized fun close() {
        val previous = snapshot
        snapshot = previous.copy(token = NativeControllerDashboard.transition(previous.token, 5, null, false, false, 0))
    }

    fun routeEpoch(session: Any, stream: Int, url: String?, closed: Boolean): Int? {
        val current = snapshot
        val result = NativeControllerDashboard.route(current.token, current.matches(session, stream), stream, url, closed)
        return if (result == NO_ROUTE) null else result.toInt()
    }

    private companion object { const val NO_ROUTE = 0x1_0000_0000L }
}

internal object NativeControllerDashboard {
    @JvmStatic external fun create(url: String?): LongArray
    @JvmStatic external fun transition(token: LongArray, operation: Int, url: String?, first: Boolean, second: Boolean, stream: Int): LongArray
    @JvmStatic external fun query(token: LongArray, operation: Int, first: Boolean, second: Boolean): Long
    @JvmStatic external fun acceptedUrl(token: LongArray): String?
    @JvmStatic external fun route(token: LongArray, sameStream: Boolean, stream: Int, url: String?, closed: Boolean): Long
    @JvmStatic external fun permits(url: String?, physical: Boolean, stream: Int, shown: Boolean, closed: Boolean): Boolean
}
