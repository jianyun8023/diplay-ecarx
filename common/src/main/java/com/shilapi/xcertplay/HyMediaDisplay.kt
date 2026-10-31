package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** HY dashboard updates its ECARX source; only the HY coexistence mode may send this protocol. */
internal object HyMediaDisplay {
    fun publish(context: Context, nowPlaying: CarPlayNowPlaying, artworkUri: Uri?) {
        // HY 3.6.1 polls bitmap rowBytes and misses same-size covers; its dashboard accepts a URI.
        runCatching {
            context.sendBroadcast(Intent("com.hyphp.hywebview.HyMediaReceiver")
                .setPackage("com.hyphp.hywebview")
                .putExtra("method", "dashboard")
                .putExtra("getTrackName", nowPlaying.title.orEmpty())
                .putExtra("getArtistName", nowPlaying.artist.orEmpty())
                .putExtra("getAlbumName", nowPlaying.album.orEmpty())
                .putExtra("getDuration", (nowPlaying.durationMillis ?: 0L).toString())
                // Omitting the field makes HY retain the previous track's URI.
                .putExtra("getArtwork", artworkUri?.toString().orEmpty())
                .putExtra("appName", context.applicationInfo.loadLabel(context.packageManager).toString())
                .putExtra("pkgName", context.packageName)
                .putExtra("playState", if (nowPlaying.playing) "1" else "0"))
        }.onFailure { Log.w("DiPlay-MediaKeys", "HY display update failed", it) }
    }
}
