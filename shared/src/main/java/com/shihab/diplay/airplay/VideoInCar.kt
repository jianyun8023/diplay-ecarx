package com.shihab.diplay.airplay

/** Whether the latest video availability update was sent now or retained for a later AirPlay stage. */
internal enum class VideoPlaybackDelivery { SENT, QUEUED, UNCHANGED }

/**
 * Keeps the latest parked-state decision until both SETUP has enabled video playback and the
 * encrypted AirPlay event channel is ready. AirPlay establishes those asynchronously, so dropping
 * an early update can leave the iPhone in audio-only mode for the rest of the session.
 */
internal class VideoPlaybackAvailability(private val send: (Boolean) -> Boolean) {
    private val token = NativeAirPlayProtocol.availabilityCreate()

    @Synchronized fun setDesired(allowed: Boolean): VideoPlaybackDelivery = update(0, allowed)
    @Synchronized fun setFeatureEnabled(enabled: Boolean): VideoPlaybackDelivery = update(1, enabled)
    @Synchronized fun setEventReady(ready: Boolean): VideoPlaybackDelivery = update(2, ready)

    private fun update(event: Int, value: Boolean): VideoPlaybackDelivery =
        when (val action = NativeAirPlayProtocol.availability(token, event, value)) {
            -1 -> VideoPlaybackDelivery.QUEUED
            -2 -> VideoPlaybackDelivery.UNCHANGED
            else -> if (send(action != 0)) {
                NativeAirPlayProtocol.availability(token, 3, action != 0)
                VideoPlaybackDelivery.SENT
            } else VideoPlaybackDelivery.QUEUED
        }
}

/**
 * iOS 27 "video in car": while the car is parked, the iPhone hands the head unit a media URL and
 * drives playback; the head unit plays it in its own player. Observed with an iPhone on iOS 27 and
 * checked against Apple's CarPlay Simulator (Additional Tools for Xcode 27) and its AirPlay web app.
 *
 * - /info carries [info] (videoPlaybackInfo); SETUP then enables the [FEATURE] the iPhone proposes.
 * - The iPhone opens a [SETTINGS_CHANNEL_UUID] data stream (see VideoSettingsChannel) and, to play,
 *   a remote control session without a socket ([REMOTE_CONTROL_UUIDS], controlType 1). Its messages
 *   arrive as POST /command with X-Apple-StreamID and {params: {data: bplist}} and are answered the
 *   same way.
 * - requestUI [UI_URL] asks the car to show its player.
 *
 * Media whose key needs FairPlay (Apple TV+) does not play: Apple's receiver passes such key URLs to
 * the iPhone (unhandledURL) and answers its streamingKey with a FairPlay key request, which needs a
 * licensed FairPlay receiver. Plain http(s) media such as Safari's plays.
 */
object VideoInCar {
    const val FEATURE = "videoPlayback"
    const val UI_URL = "videoplayback:"
    const val SETTINGS_CHANNEL_UUID = "BB493F61-A6B8-4769-8D74-80C23A9F71C4"
    val REMOTE_CONTROL_UUIDS = setOf(
        "A6B27562-B43A-4F2D-B75F-82391E250194", // video setup
        "E3DC3EA6-E6C3-4B30-847C-B7ACFEBEA654", // overlay UI
    )

    /** CoreMedia errors Apple's receiver reports when an item cannot play. */
    const val ERROR_NETWORK = -17221
    const val ERROR_DECODER = -12911
    const val ERROR_INCOMPATIBLE_ASSET = -12927

    /** Whether video may play now; the host sets it from the car's gear (P only). */
    @Volatile var allowed = false

    /** A URL scheme an app could serve through its resource loader. */
    private val APP_SCHEME = Regex("[a-z][a-z0-9+.-]*")

    /** Android local resources and FairPlay keys must never be opened as remote video. */
    private val NOT_APP_SCHEMES = setOf("file", "content", "asset", "rawresource", "android.resource", "skd")

    /** Shared policy for queue items, playlist children and iPhone loader redirects. */
    fun isPlayableUrl(url: String, iphoneLoadsAppSchemes: Boolean = false): Boolean {
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme == "http" || scheme == "https" ||
            (iphoneLoadsAppSchemes && APP_SCHEME.matches(scheme) && scheme !in NOT_APP_SCHEMES)
    }

    fun featuresEx(legacyFeatures: Long): String = NativeAirPlayProtocol.video(9, listOf(legacyFeatures)) as String

    fun info(legacyFeatures: Long, allowed: Boolean): Map<String, Any?> = reply(0, legacyFeatures, allowed)

    /** A queued media item the car can play. */
    data class Item(val uuid: Any?, val url: String, val startMillis: Int)

    /**
     * The item of an insertPlayQueueItem message, or null when its media cannot play here. With
     * [iphoneLoadsAppSchemes] an app's own scheme (served by its resource loader) is accepted too, for a
     * player that asks the iPhone for such URLs (unhandledURL).
     */
    fun parseItem(message: Map<String, Any?>, iphoneLoadsAppSchemes: Boolean = false): Item? {
        val url = ((message["item"] as? Map<*, *>)?.get("Content-Location") as? String) ?: return null
        val values = NativeAirPlayProtocol.video(1, listOf(message, isPlayableUrl(url, iphoneLoadsAppSchemes))) as? List<*> ?: return null
        return Item(values[0], values[1] as String, values[2] as Int)
    }

    fun seekMillis(message: Map<String, Any?>): Int? = NativeAirPlayProtocol.video(2, listOf(message)) as Int?

    /** The car player's state for [playbackInfoResponse]. */
    data class PlayerState(
        val prepared: Boolean,
        val playing: Boolean,
        val positionSeconds: Double,
        val durationSeconds: Double,
        val bufferedSeconds: Double,
    )

    fun playbackInfoResponse(messageId: Any?, itemUuid: Any?, state: PlayerState?): Map<String, Any?> =
        reply(3, messageId, itemUuid, facts(state))

    fun seekResponse(messageId: Any?): Map<String, Any?> = reply(4, messageId)

    fun propertyResponse(messageId: Any?, key: Any?, state: PlayerState?): Map<String, Any?> =
        reply(5, messageId, key, facts(state))

    fun errorNotification(itemUuid: Any?, code: Int): Map<String, Any?> = reply(6, itemUuid, code)

    fun playbackStateNotification(playing: Boolean, itemUuid: Any?): Map<String, Any?> = reply(7, playing, itemUuid)

    fun cmTime(seconds: Double): Map<String, Any?> = reply(8, seconds)

    private fun facts(state: PlayerState?): List<Any>? = state?.let {
        listOf(it.prepared, it.playing, it.positionSeconds, it.durationSeconds, it.bufferedSeconds)
    }

    @Suppress("UNCHECKED_CAST")
    private fun reply(operation: Int, vararg arguments: Any?): Map<String, Any?> =
        NativeAirPlayProtocol.video(operation, arguments.toList()) as Map<String, Any?>
}
