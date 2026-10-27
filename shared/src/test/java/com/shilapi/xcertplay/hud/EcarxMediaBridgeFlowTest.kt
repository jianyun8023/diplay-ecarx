package com.shilapi.xcertplay.hud

import com.ecarx.xui.adaptapi.diminteraction.DimInteraction
import com.ecarx.xui.adaptapi.diminteraction.FixtureLog
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

/** Software dispatch evidence only; neither a physical cluster nor its speakers run here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class EcarxMediaBridgeFlowTest {
    private lateinit var flow: EcarxMediaFlowFixture

    @Before fun setUp() {
        flow = EcarxMediaFlowFixture(RuntimeEnvironment.getApplication())
    }

    @After fun tearDown() = flow.close()

    @Test fun coverUriIsSentThroughPlaybackAndPlaylistAndClearedWithTheSession() {
        flow.initialize()
        val cover = android.net.Uri.parse("http://127.0.0.1:41742/cover.png")
        val now = com.shilapi.xcertplay.media.CarPlayNowPlaying(title = "With cover", artworkTransferId = 7, playing = true)
        NavigationOutputs.onNowPlaying(now, cover)
        flow.tick()
        assertEquals(cover, flow.lastTrack()["getArtwork"])
        val items = flow.calls("updatePlaylist").last().payload["items"] as List<*>
        assertEquals(cover, (items.single() as Map<*, *>)["getArtwork"])
        NavigationOutputs.onNowPlaying(now.copy(title = "Next", artworkTransferId = 8))
        flow.tick()
        assertNull(flow.lastTrack()["getArtwork"])
        flow.disconnect()
        flow.tick()
        assertNull(flow.lastTrack()["getArtwork"])
    }

    @Test fun appOpenAcquiresMediaOnceWithoutNavigationOrPhoneHandles()
 {
        DimInteraction.nullNavi = true
        DimInteraction.nullPhone = true
        flow.initialize()
        flow.initialize()
        assertEquals(listOf("create", "getMediaInteraction"), FixtureLog.snapshot())
        assertTrue(flow.methods().isEmpty())
        flow.song()
        assertTrue("Input must not call OEM synchronously", flow.methods().isEmpty())
        flow.tick()
        assertEquals("Dreams", flow.lastTrack()["getTitle"])
    }

    @Test fun realIap2MetadataRegistersSourceAndPublishesPlaylistPlaybackThenProgress() {
        flow.initialize()
        flow.song()
        flow.tick()
        assertEquals(
            listOf("updateCurrentSourceType", "updateMediaSourceTypeList", "updateMediaMuteState",
                "updatePlaybackInfo", "updatePlaylist"), flow.methods(),
        )
        assertEquals(6, flow.calls("updateCurrentSourceType").single().payload["sourceType"])
        assertEquals(listOf(6), flow.calls("updateMediaSourceTypeList").single().payload["sourceTypes"])
        assertEquals(0, flow.calls("updateMediaMuteState").single().payload["muted"])
        assertTrack(flow.lastTrack(), "Dreams", 1)
        val playlist = flow.calls("updatePlaylist").single().payload
        assertEquals(6, playlist["sourceType"])
        @Suppress("UNCHECKED_CAST")
        val item = (playlist["items"] as List<Map<String, Any?>>).single()
        for ((key, value) in item) assertEquals("Playlist field $key", flow.lastTrack()[key], value)
        assertEquals("com.apple.Music", flow.phone.nowPlaying.sourceApp)
        assertEquals(6, item["getSourceType"])
        flow.tick()
        assertEquals(12_500L, flow.calls("updateCurrentProgress").single().payload["millis"])
        assertEquals("updateCurrentProgress", flow.methods().last())
        assertTrue(flow.reports.any { "DIM media progress 12500ms" in it })
    }

    @Test fun incrementalPauseResumeAndSeekRetainTrackAndExtrapolateOnlyWhilePlaying() {
        flow.initialize()
        flow.song()
        flow.tick()
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_000))
        flow.tick()
        assertEquals(14_500L, flow.calls("updateCurrentProgress").last().payload["millis"])
        flow.update { group(1) { u8(0, 2); u32(1, 15_000L) } }
        flow.tick()
        assertTrack(flow.lastTrack(), "Dreams", 2)
        ShadowSystemClock.advanceBy(Duration.ofMillis(5_000))
        flow.tick()
        flow.tick()
        assertEquals(listOf(14_500L, 15_000L), flow.calls("updateCurrentProgress").map { it.payload["millis"] })
        flow.update { group(1) { u32(1, 256_500L); u8(0, 1) } }
        flow.tick()
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_000))
        flow.tick()
        assertEquals(257_000L, flow.calls("updateCurrentProgress").last().payload["millis"])
        assertEquals(1, flow.calls("updateCurrentSourceType").size)
        assertEquals(1, flow.calls("updateMediaSourceTypeList").size)
    }

    @Test fun trackChangesCollapseToNewestFrameAndResetItsProgress() {
        flow.initialize()
        flow.song(playing = false)
        flow.tick()
        flow.tick()
        flow.song("Intermediate", playing = false, elapsed = 99_000L)
        flow.song("New song", playing = false, elapsed = 1_000L)
        flow.tick()
        flow.tick()
        assertEquals(listOf("Dreams", "New song"), flow.calls("updatePlaybackInfo").map { it.payload["getTitle"] })
        assertEquals(listOf(12_500L, 1_000L), flow.calls("updateCurrentProgress").map { it.payload["millis"] })
        assertEquals(2, flow.calls("updatePlaylist").size)
        assertEquals(1, flow.calls("updateCurrentSourceType").size)
    }

    @Test fun emptyTitleAndUnknownFieldsProduceSafeBlankTextAndNoInventedPosition() {
        flow.initialize()
        flow.update { group(0) { string(1, "Song"); string(12, "Singer") } }
        flow.tick()
        flow.update { group(0) { string(1, "   "); string(12, "") } }
        flow.tick()
        assertBlankTrack(flow.lastTrack())
        repeat(3) { flow.tick() }
        assertTrue(flow.calls("updateCurrentProgress").isEmpty())
        assertEquals(2, flow.calls("updatePlaybackInfo").size)
    }

    @Test fun disconnectClearsQueuedOldTrackAndLateTicksNeverResurrectIt() {
        flow.initialize()
        flow.song()
        flow.tick()
        flow.song("Queued before disconnect")
        flow.disconnect()
        flow.tick()
        assertBlankTrack(flow.lastTrack())
        repeat(3) { flow.tick() }
        assertEquals(listOf("Dreams", ""), flow.calls("updatePlaybackInfo").map { it.payload["getTitle"] })
        assertTrue(flow.calls("updateCurrentProgress").isEmpty())
        assertEquals(listOf(""), playlistTitles())
    }

    @Test fun disconnectClearsCardAndReleasesPreviouslyPublishedMediaProjection() {
        flow.initialize()
        flow.song()
        flow.tick()
        assertEquals(listOf("carPlayMedia"), projectionStatuses())
        flow.disconnect()
        flow.tick()
        assertBlankTrack(flow.lastTrack())
        assertEquals(
            "A cleared card must also relinquish its previously published media source",
            listOf("carPlayMedia", "noProjection"), projectionStatuses(),
        )
        repeat(15) { flow.tick() }
        assertEquals("noProjection", projectionStatuses().last())
    }

    @Test fun pausedAndUntitledTrackKeepsMediaProjection() {
        flow.initialize()
        flow.song(title = "", playing = false)
        flow.tick()
        assertTrack(flow.lastTrack(), "", 2)
        assertEquals(listOf("carPlayMedia"), projectionStatuses())
        flow.tick()
        assertEquals(listOf("carPlayMedia"), projectionStatuses())
    }

    @Test fun disconnectReleasesOnlyMediaClaimWhileNavigationKeepsPriority() {
        flow.initialize()
        flow.song()
        flow.tick()
        assertEquals(listOf("carPlayMedia"), projectionStatuses())
        EcarxProjectionChannel.setNavigationProjection(true)
        assertEquals(listOf("carPlayMedia", "carPlayNavigation"), projectionStatuses())

        flow.disconnect()
        flow.tick()

        assertBlankTrack(flow.lastTrack())
        assertEquals(listOf("carPlayMedia", "carPlayNavigation"), projectionStatuses())
        EcarxProjectionChannel.setNavigationProjection(false)
        assertEquals(listOf("carPlayMedia", "carPlayNavigation", "noProjection"), projectionStatuses())
    }

    @Test fun disconnectThenNewMetadataBeforePendingTickPublishesOnlyNewestFrame() {
        flow.initialize()
        flow.song("Old phone", playing = false, elapsed = 60_000L)
        flow.tick()
        flow.disconnect()
        flow.song("New phone", playing = false, elapsed = 500L)
        // A pending tick reads wanted when it runs; it does not retain the previous session frame.
        flow.tick()
        flow.tick()
        assertEquals(listOf("Old phone", "New phone"), flow.calls("updatePlaybackInfo").map { it.payload["getTitle"] })
        assertEquals(500L, flow.calls("updateCurrentProgress").single().payload["millis"])
        assertEquals(listOf("New phone"), playlistTitles())
    }

    @Test fun disablingOutputClearsOnceIgnoresLateInputAndReenablePublishesLatestTrack() {
        flow.initialize()
        flow.song()
        flow.tick()
        EcarxOutputSettings.setMediaEnabled(flow.context, false)
        flow.tick()
        assertBlankTrack(flow.lastTrack())
        flow.song("Arrived while disabled", playing = false)
        repeat(4) { flow.tick() }
        assertEquals(2, flow.calls("updatePlaybackInfo").size)
        assertTrue(flow.calls("updateCurrentProgress").isEmpty())
        EcarxOutputSettings.setMediaEnabled(flow.context, true)
        flow.initialize()
        flow.tick()
        assertEquals("Arrived while disabled", flow.lastTrack()["getTitle"])
        assertEquals(1, FixtureLog.calls().count { it.method == "create" })
    }

    @Test fun disabledAtAppOpenDoesNotAcquireHandlesUntilEnabled() {
        EcarxOutputSettings.setMediaEnabled(flow.context, false)
        flow.initialize()
        flow.song()
        flow.tick()
        assertTrue(FixtureLog.calls().isEmpty())
        EcarxOutputSettings.setMediaEnabled(flow.context, true)
        flow.initialize()
        flow.tick()
        assertEquals("Dreams", flow.lastTrack()["getTitle"])
    }

    @Test fun keepaliveResendsTrackAtFifteenTicksButDoesNotRegisterSourceAgain() {
        flow.initialize()
        flow.song(playing = false)
        flow.tick()
        repeat(14) { flow.tick() }
        assertEquals(1, flow.calls("updatePlaybackInfo").size)
        flow.tick()
        assertEquals(2, flow.calls("updatePlaybackInfo").size)
        assertEquals(2, flow.calls("updatePlaylist").size)
        assertEquals(1, flow.calls("updateCurrentSourceType").size)
        assertEquals(1, flow.calls("updateMediaSourceTypeList").size)
        repeat(14) { flow.tick() }
        assertTrue(flow.reports.any { it.startsWith("media counters") })
    }

    @Test fun missingDimAndMediaHandlesRetryAndPublishNewestRetainedInput() {
        DimInteraction.nullCreate = true
        flow.initialize()
        flow.song("Before service")
        flow.tick()
        assertTrue(flow.methods().isEmpty())
        DimInteraction.nullCreate = false
        DimInteraction.nullMedia = true
        flow.tick()
        assertTrue(flow.methods().isEmpty())
        flow.song("After service")
        DimInteraction.nullMedia = false
        DimInteraction.nullNavi = true
        flow.tick()
        assertEquals("After service", flow.lastTrack()["getTitle"])
        assertEquals(3, FixtureLog.calls().count { it.method == "create" })
        assertEquals(2, FixtureLog.calls().count { it.method == "getMediaInteraction" })
        assertFalse(FixtureLog.snapshot().contains("getNaviInteraction"))
    }

    @Test fun createAndHandleExceptionsAreContainedAndRetriedOnNextTick() {
        FixtureLog.failOn("create")
        flow.initialize()
        flow.song()
        flow.tick()
        assertTrue(flow.methods().isEmpty())
        FixtureLog.clearFailures()
        FixtureLog.failOn("getMediaInteraction")
        flow.tick()
        assertTrue(flow.methods().isEmpty())
        FixtureLog.clearFailures()
        flow.tick()
        assertEquals("Dreams", flow.lastTrack()["getTitle"])
    }

    @Test fun refusedSourceSelectionStopsFrameAndRetriesBeforePlayback() {
        flow.initialize()
        flow.song()
        FixtureLog.failOn("updateCurrentSourceType")
        flow.tick()
        assertEquals(listOf("updateCurrentSourceType"), flow.methods())
        assertTrue(flow.reports.any { "refused source type" in it && "IllegalStateException" in it })
        FixtureLog.clearFailures()
        flow.tick()
        assertEquals(2, flow.calls("updateCurrentSourceType").size)
        assertTrack(flow.lastTrack(), "Dreams", 1)
    }

    @Test fun sourceListFailureIsBestEffortAndRetriedOnNextChangedTrack() {
        flow.initialize()
        flow.song()
        FixtureLog.failOn("updateMediaSourceTypeList")
        flow.tick()
        assertTrack(flow.lastTrack(), "Dreams", 1)
        assertTrue(flow.calls("updateMediaMuteState").isEmpty())
        assertTrue(flow.reports.any { "source list refused" in it })
        FixtureLog.clearFailures()
        flow.song("Second song")
        flow.tick()
        assertEquals(2, flow.calls("updateMediaSourceTypeList").size)
        assertEquals(1, flow.calls("updateCurrentSourceType").size)
        assertEquals(1, flow.calls("updateMediaMuteState").size)
        assertEquals("Second song", flow.lastTrack()["getTitle"])
    }

    @Test fun muteAndPlaylistFailuresDoNotSuppressPlaybackOrProgress() {
        flow.initialize()
        flow.song(playing = false)
        FixtureLog.failOn("updateMediaMuteState")
        FixtureLog.failOn("updatePlaylist")
        flow.tick()
        flow.tick()
        assertTrack(flow.lastTrack(), "Dreams", 2)
        assertEquals(1, flow.calls("updatePlaybackInfo").size)
        assertEquals(12_500L, flow.calls("updateCurrentProgress").single().payload["millis"])
        assertTrue(EcarxMediaBridge.state().contains("pushed=true"))
    }

    @Test fun refusedPlaybackRetriesNewestTrackWithoutPlaylistOrProjectionForFailedFrame() {
        flow.initialize()
        flow.song()
        FixtureLog.failOn("updatePlaybackInfo")
        flow.tick()
        assertTrue(flow.calls("updatePlaylist").isEmpty())
        assertTrue(flow.flushProjection().isEmpty())
        assertTrue(flow.reports.any { "refused track" in it })
        flow.song("Latest song")
        FixtureLog.clearFailures()
        flow.tick()
        assertEquals("Latest song", flow.lastTrack()["getTitle"])
        assertEquals(listOf("Latest song"), playlistTitles())
        assertEquals(1, flow.calls("updateCurrentSourceType").size)
    }

    @Test fun refusedProgressIsRetriedWithoutResendingMetadata() {
        flow.initialize()
        flow.song(playing = false)
        flow.tick()
        FixtureLog.failOn("updateCurrentProgress")
        flow.tick()
        assertTrue(EcarxMediaBridge.state().contains("progress=-1"))
        assertTrue(flow.reports.any { "refused progress" in it })
        FixtureLog.clearFailures()
        flow.tick()
        assertEquals(listOf(12_500L, 12_500L), flow.calls("updateCurrentProgress").map { it.payload["millis"] })
        assertEquals(1, flow.calls("updatePlaybackInfo").size)
        assertTrue(EcarxMediaBridge.state().contains("progress=12500"))
    }

    @Test fun absentPlaylistInterfaceStillPublishesTrackAndProgress() {
        flow.initialize()
        flow.bridgeField("itemInterface", null)
        flow.song(playing = false)
        flow.tick()
        flow.tick()
        assertTrack(flow.lastTrack(), "Dreams", 2)
        assertTrue(flow.calls("updatePlaylist").isEmpty())
        assertEquals(12_500L, flow.calls("updateCurrentProgress").single().payload["millis"])
    }

    @Test fun successfulTrackClaimsMediaProjectionAndNavigationReleaseRestoresMediaOwnership() {
        flow.initialize()
        flow.song()
        flow.tick()
        assertEquals(listOf("carPlayMedia"), projectionStatuses())
        EcarxProjectionChannel.setNavigationProjection(true)
        assertEquals(listOf("carPlayMedia", "carPlayNavigation"), projectionStatuses())
        EcarxProjectionChannel.setNavigationProjection(false)
        assertEquals(listOf("carPlayMedia", "carPlayNavigation", "carPlayMedia"), projectionStatuses())
        EcarxOutputSettings.setMediaEnabled(flow.context, false)
        flow.tick()
        assertEquals("noProjection", projectionStatuses().last())
        assertTrue(EcarxProjectionChannel.state().contains("projection=noProjection"))
        assertTrue(flow.reports.any { "projection carPlayMedia -> ok" in it })
    }

    @Test fun queuedProjectionAfterDisableCannotRestoreOldMediaClaimOverNewState() {
        flow.initialize()
        flow.song()
        flow.tick()
        EcarxOutputSettings.setMediaEnabled(flow.context, false)
        flow.tick()
        // Both updates were queued; drain must resolve the current off state rather than the old on.
        assertTrue(flow.flushProjection().isEmpty())
        EcarxOutputSettings.setMediaEnabled(flow.context, true)
        flow.song("New phone")
        flow.tick()
        assertEquals(listOf("carPlayMedia"), projectionStatuses())
        assertEquals("New phone", flow.lastTrack()["getTitle"])
    }

    private fun projectionStatuses() = flow.flushProjection().map { (topic, payload) ->
        assertEquals("ihu/info/phoneprojection", topic)
        assertTrue(payload.startsWith("{\"statusType\":\"") && payload.endsWith("\"}"))
        payload.removePrefix("{\"statusType\":\"").removeSuffix("\"}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun playlistTitles() = (flow.calls("updatePlaylist").last().payload["items"] as List<Map<String, Any?>>)
        .map { it["getTitle"] }

    private fun assertTrack(info: Map<String, Any?>, title: String, status: Int) {
        assertEquals(title, info["getTitle"])
        assertEquals("Fleetwood Mac", info["getArtist"])
        assertEquals("Rumours", info["getAlbum"])
        assertEquals(257_000L, info["getDuration"])
        assertEquals(status, info["getPlaybackStatus"])
        assertEquals(6, info["getSourceType"])
        assertEmptyOptionalFields(info)
    }

    private fun assertBlankTrack(info: Map<String, Any?>) {
        for (field in listOf("getTitle", "getArtist", "getAlbum")) assertEquals(field, "", info[field])
        assertEquals(0L, info["getDuration"])
        assertEquals(2, info["getPlaybackStatus"])
        assertEquals(6, info["getSourceType"])
        assertEmptyOptionalFields(info)
    }

    private fun assertEmptyOptionalFields(info: Map<String, Any?>) {
        for (field in listOf("getArtwork", "getNextArtwork", "getPreviousArtwork", "getLyric", "getMediaPath")) {
            assertTrue("Getter $field was not read", info.containsKey(field))
            assertNull(field, info[field])
        }
        for (field in listOf("getCurrentLyricSentence", "getLyricContent", "getRadioFrequency", "getRadioStationName", "getUUID")) {
            assertEquals(field, "", info[field])
        }
        for (field in listOf("getFavoriteState", "getLoopMode", "getPlayingItemPositionInQueue", "getRadioMode")) {
            assertEquals(field, 0, info[field])
        }
    }
}
