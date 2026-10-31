package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayPlaybackStatusTest {
    @Test
    fun realCarReconnectPositionsAreAlreadyScaledOnTheIncomingWire() {
        val status = CarPlayPlaybackStatus()
        // Complete elapsed fields from 2026-10-31 log lines 103357, 107759, 191900, 192333.
        // Also accept an intentional restart to zero; a blanket x1000 "repair" would break it.
        for ((hex, expected) in listOf(
            "000c00010008000100032898" to 207_000L,
            "000c000100080001000000cf" to 207L,
            "000c0001000800010003c8c0" to 248_000L,
            "000c000100080001000000f8" to 248L,
            "000c00010008000100000000" to 0L,
        )) {
            val payload = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertEquals(expected, status.acceptUpdate(Iap2Frame(0x5001, payload))?.elapsedMillis)
        }
        status.clearAll()
        assertNull(status.nowPlaying.elapsedMillis)
    }

    private fun update(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) =
        Iap2Messages.buildRaw(CarPlayPlaybackStatus.NOW_PLAYING_UPDATE, block)

    @Test
    fun reportsOnlyChangesOfThePlaybackStatus() {
        val status = CarPlayPlaybackStatus()

        assertEquals(true, status.accept(update { group(1) { u8(0, 1) } }))
        assertNull(status.accept(update { group(1) { u8(0, 1) } }))
        // Elapsed time alone, as the iPhone sends it every half second: no status, no change.
        assertNull(status.accept(update { group(1) { u32(1, 120_706L) } }))
        assertEquals(false, status.accept(update { group(1) { u8(0, 2) } }))
    }

    @Test
    fun otherMessagesAndSessionEnd() {
        val status = CarPlayPlaybackStatus()
        assertNull(status.accept(Iap2Messages.buildRaw(0x5201) { group(1) { u8(0, 1) } }))
        assertNull(status.clear())

        status.accept(update { group(1) { u8(0, 1) } })
        assertEquals(false, status.clear())
    }

    @Test
    fun retainsIncrementalNowPlayingMetadata() {
        val status = CarPlayPlaybackStatus()

        assertEquals(
            CarPlayNowPlaying(
                title = "Dreams",
                album = "Rumours",
                artist = "Fleetwood Mac",
                durationMillis = 257_000,
            ),
            status.acceptUpdate(update {
                group(0) {
                    string(1, "Dreams")
                    u32(4, 257_000)
                    string(6, "Rumours")
                    string(12, "Fleetwood Mac")
                }
            }),
        )
        assertEquals(
            CarPlayNowPlaying(
                title = "Dreams",
                album = "Rumours",
                artist = "Fleetwood Mac",
                sourceApp = "Music",
                durationMillis = 257_000,
                elapsedMillis = 12_500,
                playing = true,
            ),
            status.acceptUpdate(update {
                group(1) {
                    u8(0, 1)
                    u32(1, 12_500)
                    string(7, "Music")
                }
            }),
        )
    }

    @Test
    fun emptyStringsClearFieldsAndArtworkIdIsRetained() {
        val status = CarPlayPlaybackStatus()
        status.acceptUpdate(update { group(0) { string(1, "Song") } })

        val updated = status.acceptUpdate(update {
            group(0) {
                string(1, "")
                u8(26, 0x81)
            }
        })

        assertNull(updated?.title)
        assertEquals(0x81, updated?.artworkTransferId)
        assertEquals(CarPlayNowPlaying(), status.clearAll())
    }
}
