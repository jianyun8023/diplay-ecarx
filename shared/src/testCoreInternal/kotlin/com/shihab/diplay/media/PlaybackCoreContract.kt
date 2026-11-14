package com.shihab.diplay.media

import com.shihab.diplay.hud.ClusterSongState
import com.shihab.diplay.hud.KotlinClusterSongState
import com.shihab.diplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import java.util.Random

/** The old Kotlin algorithms remain independent references for both host and Android JNI. */
object PlaybackCoreContract {
    fun verifyNowPlaying() {
        val actual = CarPlayPlaybackStatus()
        val reference = KotlinCarPlayPlaybackStatus()
        val random = Random(20261113)
        fun parameter(id: Int, bytes: ByteArray): ByteArray {
            val size = bytes.size + 4
            return byteArrayOf((size shr 8).toByte(), size.toByte(), (id shr 8).toByte(), id.toByte()) + bytes
        }
        repeat(600) { step ->
            val group = random.nextInt(2)
            val fields = if (group == 0) intArrayOf(1, 4, 6, 12, 26) else intArrayOf(0, 1, 7)
            val field = fields[random.nextInt(fields.size)]
            val bytes = when {
                field in intArrayOf(1, 6, 12) && group == 0 || field == 7 -> listOf(
                    "  音乐🎶  \u0000".toByteArray(), byteArrayOf(0), byteArrayOf(0xc3.toByte(), 0x28, 0),
                    "bad terminator".toByteArray(), "\u2002 song \u2002\u0000".toByteArray(),
                    byteArrayOf(65, 0, 66, 0),
                )[random.nextInt(6)]
                random.nextBoolean() -> ByteArray(if (field == 4 || group == 1 && field == 1) 4 else 1).also(random::nextBytes)
                else -> ByteArray(random.nextInt(7)).also(random::nextBytes)
            }
            val payload = parameter(group, parameter(field, bytes))
            val frame = Iap2Frame(if (step % 17 == 0) 0x5002 else 0x5001,
                if (step % 23 == 0) payload + byteArrayOf(0) else payload)
            assertEquals("update $step", reference.acceptUpdate(frame), actual.acceptUpdate(frame))
            assertEquals(reference.nowPlaying, actual.nowPlaying)
            if (step % 29 == 0) assertEquals(reference.clear(), actual.clear())
            if (step % 61 == 0) assertEquals(reference.clearAll(), actual.clearAll())
        }
    }

    /**
     * The Glance song projection shares the wire parser but keeps its own blank-title rules;
     * both implementations must agree frame by frame, including unchanged and cleared cards.
     */
    fun verifyGlanceSong() {
        val actual = ClusterSongState()
        val reference = KotlinClusterSongState()
        val random = Random(20261115)
        fun parameter(id: Int, bytes: ByteArray): ByteArray {
            val size = bytes.size + 4
            return byteArrayOf((size shr 8).toByte(), size.toByte(), (id shr 8).toByte(), id.toByte()) + bytes
        }
        fun group(id: Int, bytes: ByteArray): ByteArray = parameter(id, bytes)
        val text = listOf(
            "Numb".toByteArray(), "  ".toByteArray(), "".toByteArray(),
            "bad terminator".toByteArray(), "A\u0000B".toByteArray(),
            "\u2002\u2002".toByteArray(), "\u0000".toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28), "Linkin Park".toByteArray(),
        )
        repeat(600) { step ->
            val item = buildList {
                if (random.nextInt(4) > 0) add(parameter(1, text[random.nextInt(text.size)] + byteArrayOf(0)))
                if (random.nextBoolean()) add(parameter(12, text[random.nextInt(text.size)] + byteArrayOf(0)))
            }.fold(ByteArray(0)) { acc, part -> acc + part }
            val playback = if (random.nextBoolean()) {
                val status = byteArrayOf(random.nextInt(6).toByte())
                parameter(0, if (random.nextBoolean()) status else byteArrayOf(status[0], status[0]))
            } else ByteArray(0)
            val body = buildList {
                if (item.isNotEmpty()) add(group(0, item))
                if (playback.isNotEmpty()) add(group(1, playback))
                if (random.nextInt(9) == 0) add(parameter(6, ByteArray(1).also(random::nextBytes)))
            }.fold(ByteArray(0)) { acc, part -> acc + part }
            val frame = Iap2Frame(if (step % 17 == 0) 0x5002 else 0x5001,
                if (step % 23 == 0) body + byteArrayOf(0) else body)
            assertEquals("update $step", reference.accept(frame), actual.accept(frame))
            assertEquals("current $step", reference.current(), actual.current())
            if (step % 31 == 0) {
                reference.clear()
                actual.clear()
                assertEquals("cleared $step", reference.current(), actual.current())
            }
        }
    }

    fun verifyNavigation() {
        var now = 0L
        val actual = NavigationPlaybackTracker(nowMillis = { now })
        val reference = KotlinNavigationPlaybackTracker(nowMillis = { now })
        val tokens = MutableList(8) { actual.open() to reference.open() }
        val random = Random(20261114)
        repeat(600) {
            val index = random.nextInt(tokens.size)
            val (a, b) = tokens[index]
            when (random.nextInt(5)) {
                0 -> {
                    actual.close(a); reference.close(b)
                    actual.played(a, 14, 9999); reference.played(b, 14, 9999)
                    tokens[index] = actual.open() to reference.open()
                }
                1 -> now += random.nextInt(1100)
                else -> {
                    val stream = listOf(null, 14, 13, Int.MIN_VALUE)[random.nextInt(4)]
                    val buffered = random.nextInt(2000).toLong() - 10
                    val voice = random.nextBoolean()
                    actual.played(a, stream, buffered, voice)
                    reference.played(b, stream, buffered, voice)
                }
            }
            assertEquals(reference.snapshot(), actual.snapshot())
        }
    }
}
