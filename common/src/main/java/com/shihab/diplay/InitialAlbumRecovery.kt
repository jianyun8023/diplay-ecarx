package com.shihab.diplay

import com.shihab.diplay.media.CarPlayNowPlaying
import com.shihab.diplay.media.PlaybackMetadataCore

/** Android executes the retry callback between the core's prepare and completion steps. */
internal class InitialAlbumRecovery {
    private val owner = PlaybackMetadataCore.recoveryCreate()
    val pendingToken: Long get() = PlaybackMetadataCore.recoveryQuery(owner, 0)
    val timerPending: Boolean get() = PlaybackMetadataCore.recoveryQuery(owner, 1) != 0L

    fun takeEvents(): List<String> = PlaybackMetadataCore.recoveryEvents(owner).map {
        "cancelled reason=${when (it.toInt()) { 1 -> "trackChanged"; 2 -> "paused"; 3 -> "colorReady"; else -> "settingDisabled" }}"
    }

    private fun action(action: Int, token: Long, info: CarPlayNowPlaying, color: Boolean, allowRetry: () -> Boolean): Boolean {
        if (!PlaybackMetadataCore.recoveryPrepare(owner, action, token, info, color)) return false
        return PlaybackMetadataCore.recoveryComplete(owner, action, token, allowRetry())
    }

    fun observe(info: CarPlayNowPlaying, hasColor: Boolean, allowRetry: () -> Boolean = { true }): Boolean =
        action(0, 0, info, hasColor, allowRetry)
    fun beginRequest(token: Long, info: CarPlayNowPlaying, hasColor: Boolean, allowRetry: () -> Boolean = { true }): Boolean =
        action(1, token, info, hasColor, allowRetry)
    fun stillNeeded(token: Long, info: CarPlayNowPlaying, hasColor: Boolean, allowRetry: () -> Boolean = { true }): Boolean =
        action(2, token, info, hasColor, allowRetry)
    fun claimSend(token: Long, info: CarPlayNowPlaying, hasColor: Boolean, allowRetry: () -> Boolean = { true }): Boolean =
        action(3, token, info, hasColor, allowRetry)
    fun finished(token: Long) = PlaybackMetadataCore.recoveryFinished(owner, token)
}
