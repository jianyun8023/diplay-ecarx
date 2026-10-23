package com.shilapi.xcertplay.adb

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * A lazily opened adb shell on the head unit for background reads. It never asks for approval, so
 * the car's dialog cannot appear while driving; a refused or missing adbd is retried every 30 s.
 */
internal class LocalAdbShell(private val tag: String) {
    private var adb: LocalAdb? = null
    private var retryAtMillis = 0L
    private var unavailableLogged = false

    /** The result of the latest connection attempt; READY with a null [run] means the link failed. */
    @Volatile var lastAccess: LocalAdb.Access? = null
        private set

    @Synchronized
    fun run(context: Context, command: String): String? = run(context, command, null)

    /** Same as [run], with a larger bounded read window for a command that is slow to answer. */
    @Synchronized
    fun run(context: Context, command: String, readTimeoutMillis: Int?): String? {
        val now = SystemClock.elapsedRealtime()
        if (now < retryAtMillis) return null
        val client = adb ?: LocalAdb(AdbKeys.load(context)).also { adb = it }
        val access = client.connect(mayAsk = false)
        lastAccess = access
        if (access != LocalAdb.Access.READY) {
            retryAtMillis = now + RETRY_MILLIS
            if (!unavailableLogged) {
                unavailableLogged = true
                Log.w(tag, "ADB access $access")
            }
            return null
        }
        unavailableLogged = false
        return if (readTimeoutMillis == null) client.shell(command) else client.shell(command, readTimeoutMillis)
    }

    @Synchronized
    fun close() {
        adb?.close()
        adb = null
    }

    private companion object {
        const val RETRY_MILLIS = 30_000L
    }
}
