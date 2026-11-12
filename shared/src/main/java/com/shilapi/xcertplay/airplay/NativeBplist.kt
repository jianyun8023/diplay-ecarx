package com.shilapi.xcertplay.airplay

/** Binary plist wire ownership; Java collections/strings remain the caller's platform boundary. */
internal object NativeBplist {
    @JvmStatic external fun encode(root: Any?): ByteArray
    @JvmStatic external fun decode(bytes: ByteArray): Any?
}
