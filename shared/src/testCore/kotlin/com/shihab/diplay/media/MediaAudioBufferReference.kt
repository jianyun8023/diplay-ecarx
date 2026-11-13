package com.shihab.diplay.media

import com.shihab.diplay.media.reference.KotlinMediaAudioBuffer

internal fun MediaAudioBuffer.kotlinStartBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int =
    KotlinMediaAudioBuffer.kotlinStartBytesFor(plannedStartBytes, actualCapacityBytes, writeChunkBytes)
