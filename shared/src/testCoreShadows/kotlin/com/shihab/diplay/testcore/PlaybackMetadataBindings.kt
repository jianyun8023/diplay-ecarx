package com.shihab.diplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(className = "com.shihab.diplay.media.PlaybackMetadataCore", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowPlaybackMetadataCore {
    companion object {
        @JvmStatic @Implementation fun nativeTrackChanged(old: LongArray, next: LongArray): Boolean =
            HostNative.call("media.PlaybackMetadataCore", "nativeTrackChanged", old, next) as Boolean
        @JvmStatic @Implementation fun nativeRecoveryCreate(): LongArray =
            HostNative.call("media.PlaybackMetadataCore", "nativeRecoveryCreate") as LongArray
        @JvmStatic @Implementation fun nativeRecoveryQuery(owner: Array<LongArray>, field: Int): Long =
            HostNative.call("media.PlaybackMetadataCore", "nativeRecoveryQuery", owner, field) as Long
        @JvmStatic @Implementation fun nativeRecoveryPrepare(owner: Array<LongArray>, action: Int, token: Long, info: LongArray, color: Boolean): Boolean =
            HostNative.call("media.PlaybackMetadataCore", "nativeRecoveryPrepare", owner, action, token, info, color) as Boolean
        @JvmStatic @Implementation fun nativeRecoveryComplete(owner: Array<LongArray>, action: Int, token: Long, allowed: Boolean): Boolean =
            HostNative.call("media.PlaybackMetadataCore", "nativeRecoveryComplete", owner, action, token, allowed) as Boolean
        @JvmStatic @Implementation fun nativeRecoveryFinished(owner: Array<LongArray>, token: Long) {
            HostNative.call("media.PlaybackMetadataCore", "nativeRecoveryFinished", owner, token)
        }
        @JvmStatic @Implementation fun nativeRecoveryEvents(owner: Array<LongArray>): LongArray =
            HostNative.call("media.PlaybackMetadataCore", "nativeRecoveryEvents", owner) as LongArray
        @JvmStatic @Implementation fun nativePublicationCreate(): LongArray =
            HostNative.call("media.PlaybackMetadataCore", "nativePublicationCreate") as LongArray
        @JvmStatic @Implementation fun nativePublicationPlan(owner: Array<LongArray>, info: LongArray, artwork: LongArray, selected: Boolean): Int =
            HostNative.call("media.PlaybackMetadataCore", "nativePublicationPlan", owner, info, artwork, selected) as Int
        @JvmStatic @Implementation fun nativePublicationCommit(owner: Array<LongArray>, info: LongArray, artwork: LongArray) {
            HostNative.call("media.PlaybackMetadataCore", "nativePublicationCommit", owner, info, artwork)
        }
        @JvmStatic @Implementation fun nativeArtworkCreate(): LongArray =
            HostNative.call("media.PlaybackMetadataCore", "nativeArtworkCreate") as LongArray
        @JvmStatic @Implementation fun nativeArtworkHash(bytes: ByteArray): LongArray =
            HostNative.call("media.PlaybackMetadataCore", "nativeArtworkHash", bytes) as LongArray
        @JvmStatic @Implementation fun nativeArtworkEvent(owner: Array<LongArray>, event: Int, session: Long, id: Long, version: Long, hash: LongArray): LongArray =
            HostNative.call("media.PlaybackMetadataCore", "nativeArtworkEvent", owner, event, session, id, version, hash) as LongArray
    }
}
