package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.media.reference.KotlinMediaAudioBuffer

internal fun MediaAudioBuffer.kotlinStartBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int =
    KotlinMediaAudioBuffer.kotlinStartBytesFor(plannedStartBytes, actualCapacityBytes, writeChunkBytes)
