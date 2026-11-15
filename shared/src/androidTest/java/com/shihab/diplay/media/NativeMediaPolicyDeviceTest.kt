package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkDecodeResult
import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkNextDeliverable.Deliverable
import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkNextRequest.Request
import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkSubmitResult.Accepted
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
        val session = PlaybackMetadataCore.artworkStartSession(owner).session
        val hash = PlaybackMetadataCore.artworkHash(byteArrayOf(1, 2, 3))
        val old = (PlaybackMetadataCore.artworkSubmit(owner, session, 1, hash) as Accepted).version
        val request = PlaybackMetadataCore.artworkNextRequest(owner) as Request
        val newer = (PlaybackMetadataCore.artworkSubmit(owner, session, 1, hash) as Accepted).version
        assertTrue(PlaybackMetadataCore.artworkDecodeComplete(owner, true, old) is ArtworkDecodeResult.Dropped)
        val next = PlaybackMetadataCore.artworkNextRequest(owner) as Request
        assertEquals(newer, next.version)
        assertEquals(1, next.id)
        assertTrue(PlaybackMetadataCore.artworkIsCurrent(owner, session, next.id, next.version))
        assertEquals(old, request.version)
        val decoded = PlaybackMetadataCore.artworkDecodeComplete(owner, true, newer) as ArtworkDecodeResult.Stored
        assertEquals(newer, decoded.imageToken)
        val cached = (PlaybackMetadataCore.artworkSubmit(owner, session, 2, hash) as Accepted).version
        assertDelivery(owner, newer, 1, newer)
        assertDelivery(owner, cached, 2, newer)
        PlaybackMetadataCore.artworkStartSession(owner)
        assertFalse(PlaybackMetadataCore.artworkIsCurrent(owner, session, 2, cached))
    }

    @Test fun artworkCacheEvictsLeastRecentlyUsedContent() {
        assertTrue(NativeMedia.available)
        val owner = PlaybackMetadataCore.artworkCreate()
        val session = PlaybackMetadataCore.artworkStartSession(owner).session
        val hashes = (1..5).map { PlaybackMetadataCore.artworkHash(byteArrayOf(it.toByte())) }
        val images = mutableListOf<Long>()
        for (id in 1..4) {
            val version = (PlaybackMetadataCore.artworkSubmit(owner, session, id, hashes[id - 1]) as Accepted).version
            val request = PlaybackMetadataCore.artworkNextRequest(owner) as Request
            assertEquals(version, request.version)
            assertEquals(id, request.id)
            assertTrue(PlaybackMetadataCore.artworkIsCurrent(owner, session, id, version))
            val decoded = PlaybackMetadataCore.artworkDecodeComplete(owner, true, version) as ArtworkDecodeResult.Stored
            assertEquals(version, decoded.imageToken)
            assertDelivery(owner, version, id, version)
            images.add(version)
        }
        val touched = (PlaybackMetadataCore.artworkSubmit(owner, session, 10, hashes[0]) as Accepted).version
        assertDelivery(owner, touched, 10, images[0])
        val fifth = (PlaybackMetadataCore.artworkSubmit(owner, session, 5, hashes[4]) as Accepted).version
        val fifthRequest = PlaybackMetadataCore.artworkNextRequest(owner) as Request
        assertEquals(fifth, fifthRequest.version)
        assertEquals(5, fifthRequest.id)
        assertTrue(PlaybackMetadataCore.artworkIsCurrent(owner, session, 5, fifth))
        val decoded = PlaybackMetadataCore.artworkDecodeComplete(owner, true, fifth) as ArtworkDecodeResult.Stored
        assertEquals(fifth, decoded.imageToken)
        PlaybackMetadataCore.artworkNextDeliverable(owner)

        val retained = (PlaybackMetadataCore.artworkSubmit(owner, session, 11, hashes[0]) as Accepted).version
        assertDelivery(owner, retained, 11, images[0])
        val evicted = (PlaybackMetadataCore.artworkSubmit(owner, session, 12, hashes[1]) as Accepted).version
        val evictedRequest = PlaybackMetadataCore.artworkNextRequest(owner) as Request
        assertEquals(evicted, evictedRequest.version)
        assertEquals(12, evictedRequest.id)
        assertTrue(PlaybackMetadataCore.artworkIsCurrent(owner, session, 12, evicted))
    }

    private fun assertDelivery(owner: Array<LongArray>, version: Long, id: Int, imageToken: Long) {
        val result = PlaybackMetadataCore.artworkNextDeliverable(owner) as Deliverable
        assertEquals(version, result.version)
        assertEquals(id, result.id)
        assertEquals(imageToken, result.imageToken)
    }

    @Test fun publicationIgnoresTransferIdOnlyAfterExplicitCommit() {
        assertTrue(NativeMedia.available)
        val owner = PlaybackMetadataCore.publicationCreate()
        val track = CarPlayNowPlaying(title = "歌😀", artworkTransferId = 1, playing = true)
        repeat(2) {
            val plan = PlaybackMetadataCore.publicationPlan(owner, track, null, false)
            assertTrue(plan.requestPlay)
            assertTrue(plan.publishMetadata)
        }
        PlaybackMetadataCore.publicationCommit(owner, track, null)
        val transferOnly = PlaybackMetadataCore.publicationPlan(owner, track.copy(artworkTransferId = 2), null, false)
        assertFalse(transferOnly.requestPlay)
        assertFalse(transferOnly.publishMetadata)
        val selected = PlaybackMetadataCore.publicationPlan(owner, track, null, true)
        assertTrue(selected.requestPlay)
        assertFalse(selected.publishMetadata)
        val artwork = PlaybackMetadataCore.publicationPlan(owner, track, "封面😀", false)
        assertFalse(artwork.requestPlay)
        assertTrue(artwork.publishMetadata)
        val elapsed = PlaybackMetadataCore.publicationPlan(owner, track.copy(elapsedMillis = 1), null, false)
        assertFalse(elapsed.requestPlay)
        assertTrue(elapsed.publishMetadata)
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
