package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Publishes CarPlay's now-playing state to an ECARX (亿咖通) instrument cluster — the media half of
 * the cluster work whose navigation half is [EcarxHudBridge].
 *
 * **Why the cluster had nothing to show.** Two independent reasons, and this class only fixes the
 * second one:
 *  * The card the cluster *did* show while CarPlay played was the head unit's own Bluetooth music
 *    source reading AVRCP metadata off the iPhone — not anything DiPlay sent. Turning the head
 *    unit's Bluetooth adapter off (the `yield_adapter` switch, used to stop that same source from
 *    pausing CarPlay audio) takes that card with it.
 *  * `EcarxDimApi.mediaInteraction` — the only way into the DIM media channel — had **no caller at
 *    all** since the day it was written. This class is that caller.
 *
 * The call sequence is the shipped ECARX toolkit's, recovered from
 * `com/hyphp/hywebviewdcltools/l.smali`, `i.smali` and `m.smali`: `updateCurrentSourceType(6)` once
 * per session, then `updatePlaybackInfo` plus `updatePlaylist`, and `updateCurrentProgress`
 * separately. `IMediaInteraction` declares no callback registration, so this is a one-way feed —
 * steering-wheel keys still reach CarPlay through Android's media session (`CarPlayMediaKeys`).
 *
 * What is recovered, what is ours, and what is deliberately missing:
 *  * **Recovered.** The four method names and their descriptors, `sourceType = 6`,
 *    `getPlaybackStatus()` ∈ {1, 2} (`MediaHandler.formatPlaybackStatus` maps 1 to `"play"`, 2 to
 *    `"pause"` and returns `null` for anything else), both payload interfaces, and the topics the
 *    cluster service republishes them on (`ihu/info/media/playback/status`, `.../progress`,
 *    `.../sources`).
 *  * **Ours.** The toolkit pins `getPlaybackStatus()` at `1` and never reports a pause. We send 2
 *    when the iPhone is paused: the cluster already understands it, so there is no reason to lie.
 *  * **Missing on purpose.** Artwork. The cluster service turns the payload's `Uri` into base64 TGA
 *    through `com.nostra13.universalimageloader`, which fetches `http`/`file`/`content` and nothing
 *    else; a track that exists only inside CarPlay's stream has no such URL. See
 *    [EcarxDimApi.PlaybackInfo].
 *
 * Off by default ([EcarxOutputSettings.mediaEnabled]) until the car confirms the cluster took it.
 * Independent of the navigation switch: this is a separate DIM channel with its own handle, its own
 * worker and its own failure, so a head unit whose guidance path never comes up can still show a
 * media card and the other way round.
 */
internal object EcarxMediaBridge {
    private const val TAG = "DiPlay-ECARX-MEDIA"

    /** How many lines are held for a report that has not attached yet; same seam as [EcarxHudBridge]. */
    private const val REPORT_BACKLOG = 24

    /**
     * Cheap re-send of the current track. The cluster service is torn down with the ignition and
     * comes back with it, and nothing tells a third-party app that it happened — the same reason the
     * projection switch has a watch of its own. Often enough to keep the card alive, rare enough not
     * to fill the head unit's MQTT log: the service publishes every frame it is handed.
     */
    private const val KEEPALIVE_SECONDS = 15

    private val lock = Any()
    private var context: Context? = null
    private var available = false
    private var dim: Any? = null
    private var media: Any? = null
    /** The payload interfaces, resolved once: `IPlaybackInfo` for the track, `IMedia` for the queue. */
    private var trackInterface: Class<*>? = null
    private var itemInterface: Class<*>? = null

    /** Where this bridge's lines go besides logcat; see [attachReport]. */
    private var reportSink: ((String) -> Unit)? = null
    private val unreported = ArrayDeque<String>()

    /** The newest frame the phone reported; `null` until it reports once. */
    private var wanted: CarPlayNowPlaying? = null
    /** What the cluster was last told, so an unchanged frame is not sent again. */
    private var pushed: EcarxDimApi.PlaybackInfo? = null
    private var sourceTypeSent = false
    private var secondsSincePush = 0
    private var lastProgress = -1L
    /** When [CarPlayNowPlaying.elapsedMillis] was measured, for extrapolating between updates. */
    private var progressMeasuredAt = 0L
    private var tickerStarted = false

    /**
     * Resolves the media handle once per process and starts the worker. Safe from any thread;
     * idempotent.
     *
     * The `DimInteraction` handle here is this object's own rather than [EcarxHudBridge]'s — see the
     * class comment. Both halves retry from their own ticker, so whichever becomes ready first does
     * not wait for the other.
     */
    fun initialize(appContext: Context) = synchronized(lock) {
        if (context != null) return@synchronized
        val app = appContext.applicationContext
        context = app
        available = EcarxOutputSettings.mediaSupported()
        if (!available) {
            report("DIM media interface absent, cluster media output disabled")
            return@synchronized
        }
        reclaimLocked()
        report(
            "DIM media ready media=${media != null} track=${trackInterface != null} " +
                "item=${itemInterface != null}",
        )
        startTickerLocked()
    }

    /**
     * Routes this bridge's lines into the session report as well as logcat.
     *
     * The report file is the only channel a car without adb can return, and a media-card change with
     * no lines in it is unfalsifiable on the road — `Log.i` never reaches the file. Idempotent and
     * safe from any thread; whatever was logged before the first session started is replayed.
     */
    fun attachReport(sink: (String) -> Unit) = synchronized(lock) {
        reportSink = sink
        unreported.forEach(sink)
        unreported.clear()
    }

    /** logcat *and* the report. Report lines carry no timestamp of their own; the writer adds one. */
    private fun report(line: String) {
        Log.i(TAG, line)
        val sink = reportSink
        if (sink != null) {
            sink(line)
            return
        }
        while (unreported.size >= REPORT_BACKLOG) unreported.removeFirst()
        unreported.addLast(line)
    }

    /**
     * The iPhone's retained now-playing state changed. May run on any thread.
     *
     * Nothing is pushed from here: the frame is recorded and the worker sends it, so the burst of
     * updates a track change produces costs one set of binder calls rather than one per field.
     */
    fun onNowPlaying(now: CarPlayNowPlaying) = synchronized(lock) {
        if (wanted?.elapsedMillis != now.elapsedMillis) progressMeasuredAt = SystemClock.elapsedRealtime()
        wanted = now
    }

    /** One-line state for the settings diagnostics block. */
    fun state(): String = synchronized(lock) {
        " media=" + (media != null) +
            " sourceType=" + sourceTypeSent +
            " pushed=" + (pushed != null) +
            " progress=" + lastProgress
    }

    private fun startTickerLocked() {
        if (tickerStarted) return
        tickerStarted = true
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diplay-ecarx-media").apply { isDaemon = true }
        }.scheduleAtFixedRate(::tick, 1, 1, TimeUnit.SECONDS)
    }

    private fun tick() = synchronized(lock) {
        if (!available) return@synchronized
        val app = context ?: return@synchronized
        if (dim == null || media == null) reclaimLocked()
        val handle = media ?: return@synchronized
        if (!EcarxOutputSettings.mediaEnabled(app)) {
            // The switch went off: leave the card saying "nothing is playing" rather than frozen on
            // the last track. `pushed = null` inside means this runs once, not once a second.
            if (pushed != null) clearLocked(handle)
            return@synchronized
        }
        val now = wanted ?: return@synchronized
        secondsSincePush += 1
        val info = frameFor(now)
        if (info != pushed || secondsSincePush >= KEEPALIVE_SECONDS) {
            pushLocked(handle, info)
            return@synchronized
        }
        pushProgressLocked(handle, now)
    }

    /**
     * (Re)resolves the media handle. `DimInteraction.create()` hands back `null` until the cluster
     * service is up, and a head unit switched on with the engine off — the state every one of them
     * boots in — is exactly that case. Unlike [EcarxCapabilities], which is a class-presence test
     * and cannot change, this handle can appear later, so the retry is the point.
     */
    private fun reclaimLocked(): Boolean {
        if (dim == null) dim = EcarxDimApi.createDimInteraction(context ?: return false)
        val interaction = dim ?: return false
        val hadMedia = media != null
        if (media == null) media = EcarxDimApi.mediaInteraction(interaction)
        val handle = media ?: return false
        if (trackInterface == null) trackInterface = EcarxDimApi.playbackInfoInterface(handle)
        if (itemInterface == null) itemInterface = EcarxDimApi.mediaItemInterface()
        if (!hadMedia) {
            report("DIM media handle acquired track=${trackInterface != null} item=${itemInterface != null}")
        }
        return true
    }

    /**
     * One cluster frame from one phone frame.
     *
     * The empty string, never `null`, for every text field: the payload interface declares them
     * `String` and a `null` there is a `NullPointerException` waiting for whoever reads it. The
     * phone's teardown frame (`CarPlayPlaybackStatus.clearAll`) is an all-empty frame with
     * `playing = false`, and comes out of here as exactly the blank card [clearLocked] sends.
     */
    private fun frameFor(now: CarPlayNowPlaying): EcarxDimApi.PlaybackInfo = EcarxDimApi.PlaybackInfo(
        title = now.title.orEmpty(),
        artist = now.artist.orEmpty(),
        album = now.album.orEmpty(),
        durationMillis = now.durationMillis ?: 0L,
        playing = now.playing,
    )

    private fun pushLocked(handle: Any, info: EcarxDimApi.PlaybackInfo) {
        if (!sendLocked(handle, info)) return
        pushed = info
        secondsSincePush = 0
        lastProgress = -1L
        report(
            "DIM media \"${info.title}\" by \"${info.artist}\" album=\"${info.album}\" " +
                "duration=${info.durationMillis}ms playing=${info.playing}",
        )
    }

    /**
     * Leaves the cluster with an empty, paused card and stops feeding it.
     *
     * There is no "clear" call on this API — the toolkit's own idle frame is a five-minute fake
     * track named after the box — so the closest honest state is the one a stopped player leaves
     * behind. Blank rather than the last track: once the switch is off or the phone has hung up,
     * that track is not "paused", it is gone.
     */
    private fun clearLocked(handle: Any) {
        if (sendLocked(handle, EcarxDimApi.PlaybackInfo())) report("DIM media cleared")
        pushed = null
    }

    /**
     * Sends one frame on all three calls the toolkit uses, in its order. Returns whether the track
     * itself was accepted; the playlist is best effort, because a head unit that lacks `IMedia`
     * should still get the card.
     */
    private fun sendLocked(handle: Any, info: EcarxDimApi.PlaybackInfo): Boolean {
        if (!sourceTypeSent) {
            val source = EcarxDimApi.updateCurrentSourceType(handle, EcarxDimApi.MEDIA_SOURCE_TYPE)
            if (!source.ok) {
                report("DIM media refused source type: ${source.reason}")
                return false
            }
            sourceTypeSent = true
            report("DIM media source type -> ${EcarxDimApi.MEDIA_SOURCE_TYPE}")
        }
        val track = trackInterface ?: return false
        val result = EcarxDimApi.updatePlaybackInfo(handle, track, info)
        if (!result.ok) {
            report("DIM media refused track: ${result.reason}")
            return false
        }
        itemInterface?.let { EcarxDimApi.updatePlaylist(handle, it, EcarxDimApi.MEDIA_SOURCE_TYPE, info) }
        return true
    }

    /**
     * Where the track is now, in milliseconds, or `-1` when the phone never reported a position.
     *
     * The iPhone sends `elapsedMillis` on play, pause and seek only — it does not stream a clock —
     * so passing the raw value through would leave the cluster's progress bar frozen between those
     * events. The gap is filled from the local monotonic clock, the same way `CarPlayMediaKeys`
     * extrapolates for Android's media session, and clamped to the reported duration so a late frame
     * cannot overrun the end of the track.
     */
    private fun progressMillis(now: CarPlayNowPlaying): Long {
        val base = now.elapsedMillis ?: return -1L
        val extrapolated = if (now.playing) {
            base + (SystemClock.elapsedRealtime() - progressMeasuredAt)
        } else {
            base
        }
        val duration = now.durationMillis ?: 0L
        return if (duration > 0) extrapolated.coerceIn(0L, duration) else extrapolated
    }

    /**
     * Sends the position only when it moved: the cluster service drops a repeat (`updateCurrentProgress:
     * Incoming progress same as cached progress`) and logs about it, and one line per second would
     * drown a 512 KB session report. The first position of each track is reported once anyway, so a
     * report still answers "did this channel work at all".
     */
    private fun pushProgressLocked(handle: Any, now: CarPlayNowPlaying) {
        val millis = progressMillis(now)
        if (millis < 0 || millis == lastProgress) return
        val first = lastProgress < 0
        val result = EcarxDimApi.updateCurrentProgress(handle, millis)
        if (!result.ok) {
            report("DIM media refused progress: ${result.reason}")
            return
        }
        lastProgress = millis
        if (first) report("DIM media progress ${millis}ms")
    }
}
