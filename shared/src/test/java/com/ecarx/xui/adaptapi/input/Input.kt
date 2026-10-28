package com.ecarx.xui.adaptapi.input

import android.content.Context

/** Reflection endpoint fixture; never packaged in an APK. */
class Input {
    var requested = intArrayOf()
    var released = intArrayOf()
    fun requestKeysInterception(keys: IntArray, callback: IKeyCallback): IntArray {
        requested = keys.copyOf()
        return intArrayOf()
    }
    fun releaseKeysInterception(keys: IntArray, callback: IKeyCallback) { released = keys.copyOf() }
    companion object {
        var instance = Input()
        @JvmStatic fun create(context: Context) = instance
    }
}

interface IKeyCallback {
    fun onKeyPressed(keyCode: Int): Boolean
    fun onKeyReleased(keyCode: Int): Boolean
}
