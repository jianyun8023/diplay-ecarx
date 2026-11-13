package com.shihab.diplay

import android.os.SystemClock
import android.view.KeyEvent
import java.io.IOException
import kotlin.concurrent.thread

/** Android 9's input command logs before injection. HY's support service runs it as UID 1000. */
internal object HySiriInputLog {
    private val down = Regex(
        """I/Input\s*\(\s*(?:1000|system):\s*\d+\): injectKeyEvent: KeyEvent \{ action=ACTION_DOWN, """ +
            """keyCode=([^,]+), scanCode=0, [^\r\n]*?repeatCount=0, eventTime=(\d+), """ +
            """downTime=\d+, deviceId=-1, source=0x101 \}""",
    )

    fun parse(line: String, since: Long, group: HySiriKey): Long? {
        val match = down.matchEntire(line) ?: return null
        if (KeyEvent.keyCodeFromString(match.groupValues[1]) !in group.codes) return null
        return match.groupValues[2].toLongOrNull()?.takeIf { it >= since }
    }

    fun start(group: HySiriKey, onPress: (Process, Long) -> Unit): Process {
        val since = SystemClock.uptimeMillis()
        val wall = System.currentTimeMillis()
        val startTime = "${wall / 1000}.${(wall % 1000).toString().padStart(3, '0')}"
        val process = ProcessBuilder("/system/bin/logcat", "-b", "main", "-v", "brief,uid",
            "-T", startTime, "Input:I", "*:S").start()
        thread(name = "diplay-hy-siri", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) parse(line, since, group)?.let { onPress(process, it) }
                }
            } catch (_: IOException) {
                // Closing the session also closes logcat's pipe.
            } finally {
                process.destroy()
            }
        }
        return process
    }
}
