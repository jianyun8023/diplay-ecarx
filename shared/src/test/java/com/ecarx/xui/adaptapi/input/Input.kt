package com.ecarx.xui.adaptapi.input

import android.content.Context

/** Reflection endpoint fixture; never packaged in an APK. */
class Input {
    var requested = intArrayOf()
    var released = intArrayOf()
    var callback: IKeyCallback? = null
    var requests = 0
    var abandons = 0
    fun requestKeysInterception(keys: IntArray, callback: IKeyCallback): IntArray {
        requests++
        requested = keys.copyOf()
        this.callback = callback
        return keys.filter { it != 200400 && it != 119 }.toIntArray()
    }
    fun abandonKeysInterception(callback: IKeyCallback): Boolean {
        if (this.callback !== callback) return false
        abandons++
        this.callback = null
        released = requested.copyOf()
        return true
    }
    companion object {
        var instance = Input()
        var createFails = false
        var creates = 0
        @JvmStatic fun create(context: Context): Input? {
            creates++
            return if (createFails) null else instance
        }
    }
}

interface IKeyCallback {
    fun onKeyPressed(keyCode: Int): Boolean
    fun onKeyReleased(keyCode: Int): Boolean
}
