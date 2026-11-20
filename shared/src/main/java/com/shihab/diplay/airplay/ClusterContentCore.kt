package com.shihab.diplay.airplay

/** Immutable native values; callers publish each snapshot with its platform routing identity. */
internal object ClusterContentCore {
    class Snapshot internal constructor(internal val token: LongArray, val stream: Int)
    data class Route(val stream: Int, val displayed: String?)
    data class Change(val snapshot: Snapshot, val accepted: Boolean, val sendUrl: String?,
        val needKeyFrame: Boolean, val rotateRouteToken: Boolean)

    fun create(initialUrl: String?): Snapshot {
        NativeMedia.requireAvailable()
        return Snapshot(NativeClusterContent.create(initialUrl), 0)
    }

    fun accepted(snapshot: Snapshot, stream: Int, initialUrl: String?): Change =
        change(snapshot, 1, stream = stream, url = initialUrl)
    fun clear(snapshot: Snapshot): Change = change(snapshot, 2)
    fun select(snapshot: Snapshot, url: String, send: Boolean, closed: Boolean, stream: Int, configured: Boolean): Change =
        change(snapshot, 3, stream, url, closed, configured, send)
    fun restore(snapshot: Snapshot, url: String, send: Boolean, closed: Boolean, configured: Boolean): Change =
        change(snapshot, 4, url = url, closed = closed, configured = configured, send = send)
    fun visibility(snapshot: Snapshot, shown: Boolean, closed: Boolean, stream: Int): Change =
        change(snapshot, if (shown) 5 else 6, stream, closed = closed)
    fun reapply(snapshot: Snapshot): Change = change(snapshot, 7)
    fun showAllowed(snapshot: Snapshot, closed: Boolean, stream: Int): Boolean =
        change(snapshot, 8, stream, closed = closed).accepted
    fun keyFrame(snapshot: Snapshot, uiSent: Boolean, closed: Boolean, stream: Int): Boolean =
        change(snapshot, 9, stream, closed = closed, uiSent = uiSent).needKeyFrame
    fun commitShow(snapshot: Snapshot, expectedStream: Int, url: String, closed: Boolean, stream: Int,
        uiSent: Boolean, frameSent: Boolean): Change =
        change(snapshot, 10, stream, url, closed, uiSent = uiSent, frameSent = frameSent, expectedStream = expectedStream)
    fun commitStop(snapshot: Snapshot, expectedStream: Int, closed: Boolean, sent: Boolean): Change =
        change(snapshot, 11, closed = closed, uiSent = sent, expectedStream = expectedStream)

    fun route(snapshot: Snapshot, closed: Boolean = false, stream: Int = snapshot.stream): Route? {
        val fields = NativeClusterContent.route(snapshot.token, closed, stream) ?: return null
        return Route(fields[0] as Int, fields[1] as String?)
    }

    private fun change(snapshot: Snapshot, event: Int, stream: Int = 0, url: String? = null,
        closed: Boolean = false, configured: Boolean = false, send: Boolean = false,
        uiSent: Boolean = false, frameSent: Boolean = false, expectedStream: Int = 0): Change {
        val flags = (if (closed) 1 else 0) or (if (configured) 2 else 0) or
            (if (send) 4 else 0) or (if (uiSent) 8 else 0) or (if (frameSent) 16 else 0)
        val fields = NativeClusterContent.change(snapshot.token, event, stream, url, flags, expectedStream)
        return Change(Snapshot(fields[0] as LongArray, fields[1] as Int), fields[2] as Boolean,
            fields[3] as String?, fields[4] as Boolean, fields[5] as Boolean)
    }
}

internal object NativeClusterContent {
    @JvmStatic external fun create(initialUrl: String?): LongArray
    @JvmStatic external fun change(token: LongArray, event: Int, stream: Int, url: String?, flags: Int, expectedStream: Int): Array<Any?>
    @JvmStatic external fun route(token: LongArray, closed: Boolean, stream: Int): Array<Any?>?
}
