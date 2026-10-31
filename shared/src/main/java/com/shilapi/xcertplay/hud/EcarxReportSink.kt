package com.shilapi.xcertplay.hud

import android.util.Log

/**
 * A logcat line plus, optionally, the same line into the session report.
 *
 * The report is the only channel a car without adb can return, and a HUD/projection change with no
 * lines in it is unfalsifiable on the road (`Log.i` never reaches the file). Lines logged before a
 * sink is attached are replayed to the first sink, bounded so a long early session cannot grow
 * without limit.
 */
internal class EcarxReportSink(private val tag: String) {
    private val lock = Any()
    private var sink: ((String) -> Unit)? = null
    private val pending = ArrayDeque<String>()

    fun attach(sink: (String) -> Unit) = synchronized(lock) {
        this.sink = sink
        pending.forEach(sink)
        pending.clear()
    }

    fun line(message: String) {
        Log.i(tag, message)
        synchronized(lock) {
            val current = sink
            if (current != null) {
                current(message)
            } else {
                while (pending.size >= 32) pending.removeFirst()
                pending.addLast(message)
            }
        }
    }
}
