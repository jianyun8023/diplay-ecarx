package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMediaPolicyDeviceTest {
    @Test fun trackIdentityAndRecoveryRejectReentrantCancellation() {
        assertTrue(NativeMedia.available)
        val track = CarPlayNowPlaying(title = "曲目😀", album = "专辑", artworkTransferId = 7,
            sourceApp = "音乐", durationMillis = 180_000, elapsedMillis = 40_000, playing = true)
        assertFalse(PlaybackMetadataCore.trackChanged(track, track.copy(title = "新标题", artworkTransferId = 8)))
        assertTrue(PlaybackMetadataCore.trackChanged(track, track.copy(elapsedMillis = 1_000)))
        assertTrue(PlaybackMetadataCore.trackChanged(track, track.copy(album = "另一张")))

        val owner = PlaybackMetadataCore.recoveryCreate()
        assertTrue(PlaybackMetadataCore.recoveryPrepare(owner, 0, 0, track, false))
        assertTrue(PlaybackMetadataCore.recoveryComplete(owner, 0, 0, true))
        val stale = PlaybackMetadataCore.recoveryQuery(owner, 0)
        assertTrue(PlaybackMetadataCore.recoveryPrepare(owner, 1, stale, track, false))
        assertFalse(PlaybackMetadataCore.recoveryPrepare(owner, 0, 0, track.copy(playing = false), false))
        assertFalse(PlaybackMetadataCore.recoveryComplete(owner, 1, stale, true))
        assertArrayEquals(longArrayOf(2), PlaybackMetadataCore.recoveryEvents(owner))
        assertArrayEquals(longArrayOf(), PlaybackMetadataCore.recoveryEvents(owner))

        assertTrue(PlaybackMetadataCore.recoveryPrepare(owner, 0, 0, track, false))
        assertTrue(PlaybackMetadataCore.recoveryComplete(owner, 0, 0, true))
        val current = PlaybackMetadataCore.recoveryQuery(owner, 0)
        assertTrue(PlaybackMetadataCore.recoveryPrepare(owner, 1, current, track, false))
        assertTrue(PlaybackMetadataCore.recoveryComplete(owner, 1, current, true))
        assertTrue(PlaybackMetadataCore.recoveryPrepare(owner, 3, current, track, false))
        assertTrue(PlaybackMetadataCore.recoveryComplete(owner, 3, current, true))
        PlaybackMetadataCore.recoveryFinished(owner, current)
        assertFalse(PlaybackMetadataCore.recoveryPrepare(owner, 0, 0, track, false))
    }

    @Test fun artworkQueueDeduplicatesContentAndRejectsStaleVersions() {
        assertTrue(NativeMedia.available)
        val owner = PlaybackMetadataCore.artworkCreate()
        val session = PlaybackMetadataCore.artworkEvent(owner, 0)[0]
        val hash = PlaybackMetadataCore.artworkHash(byteArrayOf(1, 2, 3))
        val old = PlaybackMetadataCore.artworkEvent(owner, 2, session, 1, hash = hash)[0]
        val request = PlaybackMetadataCore.artworkEvent(owner, 3)
        val newer = PlaybackMetadataCore.artworkEvent(owner, 2, session, 1, hash = hash)[0]
        assertArrayEquals(longArrayOf(0, 0, 0), PlaybackMetadataCore.artworkEvent(owner, 4, id = 1, version = old).copyOf(3))
        assertArrayEquals(longArrayOf(newer, 1, session), PlaybackMetadataCore.artworkEvent(owner, 3).copyOf(3))
        assertEquals(old, request[0])
        assertArrayEquals(longArrayOf(2, newer, 0), PlaybackMetadataCore.artworkEvent(owner, 4, id = 1, version = newer).copyOf(3))
        val cached = PlaybackMetadataCore.artworkEvent(owner, 2, session, 2, hash = hash)[0]
        assertArrayEquals(longArrayOf(newer, 1, newer), PlaybackMetadataCore.artworkEvent(owner, 5).copyOf(3))
        assertArrayEquals(longArrayOf(cached, 2, newer), PlaybackMetadataCore.artworkEvent(owner, 5).copyOf(3))
        PlaybackMetadataCore.artworkEvent(owner, 0)
        assertEquals(0L, PlaybackMetadataCore.artworkEvent(owner, 6, session, 2, cached)[0])
    }

    @Test fun artworkCacheEvictsLeastRecentlyUsedContent() {
        assertTrue(NativeMedia.available)
        val owner = PlaybackMetadataCore.artworkCreate()
        val session = PlaybackMetadataCore.artworkEvent(owner, 0)[0]
        val hashes = (1..5).map { PlaybackMetadataCore.artworkHash(byteArrayOf(it.toByte())) }
        val images = mutableListOf<Long>()
        for (id in 1..4) {
            val version = PlaybackMetadataCore.artworkEvent(owner, 2, session, id.toLong(), hash = hashes[id - 1])[0]
            assertArrayEquals(longArrayOf(version, id.toLong(), session), PlaybackMetadataCore.artworkEvent(owner, 3).copyOf(3))
            assertArrayEquals(longArrayOf(2, version, 0), PlaybackMetadataCore.artworkEvent(owner, 4, id = 1, version = version).copyOf(3))
            assertArrayEquals(longArrayOf(version, id.toLong(), version), PlaybackMetadataCore.artworkEvent(owner, 5).copyOf(3))
            images.add(version)
        }
        val touched = PlaybackMetadataCore.artworkEvent(owner, 2, session, 10, hash = hashes[0])[0]
        assertArrayEquals(longArrayOf(touched, 10, images[0]), PlaybackMetadataCore.artworkEvent(owner, 5).copyOf(3))
        val fifth = PlaybackMetadataCore.artworkEvent(owner, 2, session, 5, hash = hashes[4])[0]
        assertArrayEquals(longArrayOf(fifth, 5, session), PlaybackMetadataCore.artworkEvent(owner, 3).copyOf(3))
        assertArrayEquals(longArrayOf(2, fifth, 0), PlaybackMetadataCore.artworkEvent(owner, 4, id = 1, version = fifth).copyOf(3))
        PlaybackMetadataCore.artworkEvent(owner, 5)

        val retained = PlaybackMetadataCore.artworkEvent(owner, 2, session, 11, hash = hashes[0])[0]
        assertArrayEquals(longArrayOf(retained, 11, images[0]), PlaybackMetadataCore.artworkEvent(owner, 5).copyOf(3))
        val evicted = PlaybackMetadataCore.artworkEvent(owner, 2, session, 12, hash = hashes[1])[0]
        assertArrayEquals(longArrayOf(evicted, 12, session), PlaybackMetadataCore.artworkEvent(owner, 3).copyOf(3))
    }

    @Test fun publicationIgnoresTransferIdOnlyAfterExplicitCommit() {
        assertTrue(NativeMedia.available)
        val owner = PlaybackMetadataCore.publicationCreate()
        val track = CarPlayNowPlaying(title = "歌😀", artworkTransferId = 1, playing = true)
        assertEquals(3, PlaybackMetadataCore.publicationPlan(owner, track, null, false))
        assertEquals(3, PlaybackMetadataCore.publicationPlan(owner, track, null, false))
        PlaybackMetadataCore.publicationCommit(owner, track, null)
        assertEquals(0, PlaybackMetadataCore.publicationPlan(owner, track.copy(artworkTransferId = 2), null, false))
        assertEquals(1, PlaybackMetadataCore.publicationPlan(owner, track, null, true))
        assertEquals(2, PlaybackMetadataCore.publicationPlan(owner, track, "封面😀", false))
        assertEquals(2, PlaybackMetadataCore.publicationPlan(owner, track.copy(elapsedMillis = 1), null, false))
    }

    @Test fun captureRecoveryKeepsIndependentAttemptBudgets() {
        assertTrue(NativeMedia.available)
        val state = longArrayOf(0, 0, 100)
        assertEquals(1, NativeAudioScheduler.captureRecovery(state, 1, 0, 0))
        assertEquals(1, NativeAudioScheduler.captureRecovery(state, 1, 0, 0))
        for (attempt in 1..3) {
            assertEquals(attempt, NativeAudioScheduler.captureRecovery(state, 1, 0, 0))
            assertEquals(attempt, NativeAudioScheduler.captureRecovery(state, 2, 0, 0))
        }
        assertEquals(0, NativeAudioScheduler.captureRecovery(state, 1, 0, 0))
        assertEquals(0, NativeAudioScheduler.captureRecovery(state, 3, 1_000_099, 1))
        for (attempt in 1..3) {
            assertEquals(attempt, NativeAudioScheduler.captureRecovery(state, 3, 100L + attempt * 1_000_000L, 1))
        }
        assertEquals(0, NativeAudioScheduler.captureRecovery(state, 3, 4_000_100, 1))
        NativeAudioScheduler.captureRecovery(state, 0, 5_000_000, 0)
        assertEquals(0, NativeAudioScheduler.captureRecovery(state, 3, 6_000_000, 1))
    }

    @Test fun navigationFocusWaitsForOverlappingAudioAndStopsAfterClose() {
        assertTrue(NativeMedia.available)
        val state = LongArray(3)
        assertEquals(0, NativeAudioScheduler.promptFocus(state, 0, false, false))
        assertEquals(1, NativeAudioScheduler.promptFocus(state, 0, true, false))
        assertEquals(30_000, NativeAudioScheduler.promptFocus(state, 5, true, true))
        assertEquals(1, NativeAudioScheduler.promptFocus(state, 2, true, true))
        assertEquals(1, NativeAudioScheduler.promptFocus(state, 2, true, true))
        assertEquals(60_000, NativeAudioScheduler.promptFocus(state, 6, true, true))
        assertEquals(0, NativeAudioScheduler.promptFocus(state, 1, true, true))
        assertEquals(0, NativeAudioScheduler.promptFocus(state, 3, true, true))
        assertEquals(400, NativeAudioScheduler.promptFocus(state, 3, true, true))
        assertEquals(1_000, NativeAudioScheduler.promptFocus(state, 1, true, true))
        assertEquals(1, NativeAudioScheduler.promptFocus(state, 4, true, true))
        assertEquals(0, NativeAudioScheduler.promptFocus(state, 4, true, true))
        assertEquals(0, NativeAudioScheduler.promptFocus(state, 2, true, false))
    }
}
