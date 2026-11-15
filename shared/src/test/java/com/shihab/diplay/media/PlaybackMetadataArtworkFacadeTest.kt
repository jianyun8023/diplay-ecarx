package com.shihab.diplay.media

import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkDecodeResult
import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkDeliverable
import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkNextDeliverable
import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkNextRequest
import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkRetention
import com.shihab.diplay.media.PlaybackMetadataCore.ArtworkSubmitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real host core and checks every artwork event's named result against the
 * `session/playback/artwork_queue.rs` contract the former LongArray readers encoded:
 * start/clear (0/1), submit (2), next request (3), decode completion (4), next deliverable (5)
 * and currency (6).
 */
class PlaybackMetadataArtworkFacadeTest {
    private val empty = ArtworkRetention(emptySet(), emptyList(), emptySet())
    private fun hash(vararg bytes: Byte) = PlaybackMetadataCore.artworkHash(bytes)

    @Test fun startSessionNumbersSessionsAndRejectsTheOldOne() {
        val state = PlaybackMetadataCore.artworkCreate()
        val first = PlaybackMetadataCore.artworkStartSession(state)
        assertEquals(1L, first.session)
        assertEquals(empty, first.retention)
        val accepted = PlaybackMetadataCore.artworkSubmit(state, first.session, 3, hash(1)) as ArtworkSubmitResult.Accepted
        assertEquals(1L, accepted.version)
        assertEquals(setOf(1L), accepted.retention.pendingVersions)
        val second = PlaybackMetadataCore.artworkStartSession(state)
        assertEquals(2L, second.session)
        assertEquals(empty, second.retention)
        assertTrue(PlaybackMetadataCore.artworkSubmit(state, first.session, 4, hash(1)) is ArtworkSubmitResult.Rejected)
    }

    @Test fun submitNextRequestAndCurrencyFollowTheQueueContract() {
        val state = PlaybackMetadataCore.artworkCreate()
        val session = PlaybackMetadataCore.artworkStartSession(state)
        assertTrue(PlaybackMetadataCore.artworkSubmit(state, session.session, 256, hash(1)) is ArtworkSubmitResult.Rejected)
        val accepted = PlaybackMetadataCore.artworkSubmit(state, session.session, 3, hash(1)) as ArtworkSubmitResult.Accepted
        assertEquals(1L, accepted.version)
        assertEquals(setOf(1L), accepted.retention.pendingVersions)
        val request = PlaybackMetadataCore.artworkNextRequest(state) as ArtworkNextRequest.Request
        assertEquals(1L, request.version)
        assertEquals(3, request.id)
        assertTrue(request.retention.pendingVersions.isEmpty())
        assertEquals(emptyList<ArtworkDeliverable>(), request.retention.deliverables)
        assertTrue(PlaybackMetadataCore.artworkIsCurrent(state, session.session, 3, request.version))
        assertFalse(PlaybackMetadataCore.artworkIsCurrent(state, session.session, 4, request.version))
        assertFalse(PlaybackMetadataCore.artworkIsCurrent(state, session.session, 3, request.version + 1))
        val stored = PlaybackMetadataCore.artworkDecodeComplete(state, decoded = true, version = request.version) as ArtworkDecodeResult.Stored
        assertEquals(1L, stored.imageToken)
        assertEquals(listOf(ArtworkDeliverable(1L, 3, 1L)), stored.retention.deliverables)
        assertEquals(setOf(1L), stored.retention.cachedTokens)
    }

    @Test fun identicalContentPopsTheSecondCopyAsACacheHit() {
        val state = PlaybackMetadataCore.artworkCreate()
        val session = PlaybackMetadataCore.artworkStartSession(state)
        PlaybackMetadataCore.artworkSubmit(state, session.session, 1, hash(9, 9))
        val second = PlaybackMetadataCore.artworkSubmit(state, session.session, 2, hash(9, 9)) as ArtworkSubmitResult.Accepted
        assertEquals(setOf(1L, 2L), second.retention.pendingVersions)
        val request = PlaybackMetadataCore.artworkNextRequest(state) as ArtworkNextRequest.Request
        assertEquals(1L, request.version)
        assertEquals(1, request.id)
        val stored = PlaybackMetadataCore.artworkDecodeComplete(state, decoded = true, version = request.version) as ArtworkDecodeResult.Stored
        assertEquals(1L, stored.imageToken)
        val hit = PlaybackMetadataCore.artworkNextRequest(state) as ArtworkNextRequest.CacheHit
        assertEquals(listOf(ArtworkDeliverable(1L, 1, 1L), ArtworkDeliverable(2L, 2, 1L)), hit.retention.deliverables)
        assertEquals(setOf(1L), hit.retention.cachedTokens)
    }

    @Test fun failedDecodeQueuesTheNullPlaceholderDeliverable() {
        val state = PlaybackMetadataCore.artworkCreate()
        val session = PlaybackMetadataCore.artworkStartSession(state)
        PlaybackMetadataCore.artworkSubmit(state, session.session, 7, hash(3))
        val request = PlaybackMetadataCore.artworkNextRequest(state) as ArtworkNextRequest.Request
        val failed = PlaybackMetadataCore.artworkDecodeComplete(state, decoded = false, version = request.version) as ArtworkDecodeResult.Failed
        assertEquals(listOf(ArtworkDeliverable(1L, 7, null)), failed.retention.deliverables)
        assertTrue(failed.retention.pendingVersions.isEmpty())
        assertTrue(failed.retention.cachedTokens.isEmpty())
        val deliverable = PlaybackMetadataCore.artworkNextDeliverable(state) as ArtworkNextDeliverable.Deliverable
        assertEquals(1L, deliverable.version)
        assertEquals(7, deliverable.id)
        assertEquals(null, deliverable.imageToken)
        assertTrue(deliverable.retention.deliverables.isEmpty())
        assertTrue(PlaybackMetadataCore.artworkNextDeliverable(state) is ArtworkNextDeliverable.Idle)
    }

    @Test fun supersededAndAbsentInFlightDecodesAreDropped() {
        val state = PlaybackMetadataCore.artworkCreate()
        val session = PlaybackMetadataCore.artworkStartSession(state)
        PlaybackMetadataCore.artworkSubmit(state, session.session, 8, hash(4))
        PlaybackMetadataCore.artworkNextRequest(state) as ArtworkNextRequest.Request
        // A resubmit for the same id supersedes the in-flight version: its decode result is dropped.
        val resent = PlaybackMetadataCore.artworkSubmit(state, session.session, 8, hash(5)) as ArtworkSubmitResult.Accepted
        assertTrue(PlaybackMetadataCore.artworkDecodeComplete(state, decoded = true, version = 1L) is ArtworkDecodeResult.Dropped)
        val request = PlaybackMetadataCore.artworkNextRequest(state) as ArtworkNextRequest.Request
        assertEquals(resent.version, request.version)
        assertEquals(8, request.id)
        val stored = PlaybackMetadataCore.artworkDecodeComplete(state, decoded = true, version = request.version) as ArtworkDecodeResult.Stored
        assertEquals(request.version, stored.imageToken)
        // With nothing in flight any more, decode completion drops too.
        assertTrue(PlaybackMetadataCore.artworkDecodeComplete(state, decoded = true, version = request.version) is ArtworkDecodeResult.Dropped)
    }

    @Test fun deliverablesComeOutOneAtATimeUntilIdle() {
        val state = PlaybackMetadataCore.artworkCreate()
        val session = PlaybackMetadataCore.artworkStartSession(state)
        assertTrue(PlaybackMetadataCore.artworkNextDeliverable(state) is ArtworkNextDeliverable.Idle)
        PlaybackMetadataCore.artworkSubmit(state, session.session, 5, hash(2))
        val request = PlaybackMetadataCore.artworkNextRequest(state) as ArtworkNextRequest.Request
        val stored = PlaybackMetadataCore.artworkDecodeComplete(state, decoded = true, version = request.version) as ArtworkDecodeResult.Stored
        val deliverable = PlaybackMetadataCore.artworkNextDeliverable(state) as ArtworkNextDeliverable.Deliverable
        assertEquals(request.version, deliverable.version)
        assertEquals(5, deliverable.id)
        assertEquals(stored.imageToken, deliverable.imageToken)
        assertTrue(deliverable.retention.deliverables.isEmpty())
        assertTrue(PlaybackMetadataCore.artworkNextDeliverable(state) is ArtworkNextDeliverable.Idle)
    }

    @Test fun clearSessionEmptiesEveryTableAndStopsCurrency() {
        val state = PlaybackMetadataCore.artworkCreate()
        val session = PlaybackMetadataCore.artworkStartSession(state)
        PlaybackMetadataCore.artworkSubmit(state, session.session, 1, hash(6))
        PlaybackMetadataCore.artworkNextRequest(state) as ArtworkNextRequest.Request
        assertEquals(empty, PlaybackMetadataCore.artworkClearSession(state))
        assertTrue(PlaybackMetadataCore.artworkSubmit(state, session.session, 2, hash(6)) is ArtworkSubmitResult.Rejected)
        assertFalse(PlaybackMetadataCore.artworkIsCurrent(state, session.session, 1, 1L))
    }
}
