package com.ecarx.xui.adaptapi.input

import android.content.Context

/** Reflection endpoint fixture; never packaged in an APK. */
class Input {
    var requested = intArrayOf()
    var released = intArrayOf()
    var callback: IKeyCallback? = null
    fun requestKeysInterception(keys: IntArray, callback: IKeyCallback): IntArray {
        requested = keys.copyOf()
        this.callback = callback
        return keys.filter { it != 200400 && it != 119 }.toIntArray()
    }
    fun abandonKeysInterception(callback: IKeyCallback): Boolean {
        if (this.callback !== callback) return false
        this.callback = null
        released = requested.copyOf()
        return true
    }
    companion object {
        var instance = Input()
        @JvmStatic fun create(context: Context) = instance
    }
}

interface IKeyCallback {
    fun onKeyPressed(keyCode: Int): Boolean
    fun onKeyReleased(keyCode: Int): Boolean
}
