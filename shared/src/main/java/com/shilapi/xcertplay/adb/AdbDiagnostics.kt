package com.shilapi.xcertplay.adb

import android.content.Context
import android.os.Build

/**
 * What this head unit says about its own ADB daemon, and whether another OS runs beside Android.
 *
 * Two questions a driver cannot answer from someone else's box tool:
 *
 * 1. "ADB is on" — on *which* address? adbd listening on the loopback is reachable from an app on the
 *    head unit and from nothing else: no laptop on the Wi-Fi, no `adb connect`. adbd listening on
 *    every interface is the same daemon on the same port, and then a laptop can reach it too. The
 *    report prints the socket addresses so the two cases can be told apart instead of guessed.
 *
 * 2. Is the TCP port a one-off or persisted? `service.adb.tcp.port` is set by `adb tcpip 5555` and
 *    forgotten on reboot; `persist.adb.tcp.port` survives. A head unit that only ever carries the
 *    first one therefore needs a USB cable after every reboot, which is exactly what "I have to plug
 *    the laptop in first" looks like from the driver's seat.
 *
 * It also answers whether a hypervisor hosts a second OS next to Android. `/proc/cmdline` is the
 * only reliable judge: a second display is not, because a display driven by Android's own
 * Presentation and a display passed through from another OS look identical from `dumpsys display`.
 *
 * Every command is bounded. This runs on the report's worker thread, and a head unit whose adbd is
 * off must not be able to hold the export up.
 */
object AdbDiagnostics {
    private const val TIMEOUT_MS = 1_500

    /** 5555 in the little-endian hex `/proc/net/tcp` uses for ports. */
    private const val ADB_PORT_HEX = "15B3"

    fun report(context: Context): String = buildString {
        appendLine("--- ADB daemon ---")
        appendLine("board=${Build.BOARD} hardware=${Build.HARDWARE} device=${Build.DEVICE}")

        val adb = LocalAdb(AdbKeys.load(context))
        val access = runCatching { adb.connect(mayAsk = false) }
            .getOrDefault(LocalAdb.Access.UNREACHABLE)
        appendLine("our key: ${access.name}")
        if (access != LocalAdb.Access.READY) {
            appendLine("(no shell, so the fields below cannot be read)")
        } else {
            runCatching {
                adb.shell(
                    "getprop init.svc.adbd; getprop service.adb.tcp.port; getprop persist.adb.tcp.port",
                    TIMEOUT_MS,
                )
            }.getOrNull()?.trim()?.lines()?.let { lines ->
                appendLine("init.svc.adbd=${lines.getOrNull(0).orEmpty().ifBlank { "(unset)" }}")
                appendLine("service.adb.tcp.port=${lines.getOrNull(1).orEmpty().ifBlank { "(unset)" }}")
                appendLine("persist.adb.tcp.port=${lines.getOrNull(2).orEmpty().ifBlank { "(unset)" }}")
            }

            runCatching {
                adb.shell("grep -i \":$ADB_PORT_HEX \" /proc/net/tcp /proc/net/tcp6", TIMEOUT_MS)
            }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { out ->
                val rows = out.lines().filter { it.isNotBlank() }
                appendLine(
                    "listeners on 5555: ${rows.size} "
                        + "(local_address 00000000 = every interface, 0100007F = loopback only)",
                )
                rows.take(4).forEach { appendLine("  ${it.trim()}") }
            } ?: appendLine("listeners on 5555: none")

            appendLine()
            appendLine("--- Hypervisor / second OS ---")
            runCatching { adb.shell("cat /proc/cmdline", TIMEOUT_MS) }
                .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { appendLine("cmdline: $it") } ?: appendLine("cmdline: unreadable")

            val hits = runCatching {
                adb.shell("getprop | grep -iE \"qvm|qnx|hyper|multi_os|gunyah\"", TIMEOUT_MS)
            }.getOrNull()?.trim()?.lines()?.filter { it.isNotBlank() }.orEmpty()
            appendLine("props: ${if (hits.isEmpty()) "none" else hits.size.toString()}")
            hits.take(6).forEach { appendLine("  ${it.trim()}") }

            val channels = runCatching {
                adb.shell("ls /dev | grep -iE \"hab|vdev|virtio|smem|glink|rpmsg\"", TIMEOUT_MS)
            }.getOrNull()?.trim()?.lines()?.filter { it.isNotBlank() }.orEmpty()
            appendLine("channels: ${if (channels.isEmpty()) "none" else channels.joinToString(" ")}")
        }
        runCatching { adb.close() }
    }
}
