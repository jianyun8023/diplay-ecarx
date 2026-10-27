package com.ecarx.xui.adaptapi.input

import android.content.Context

/** Common does not inherit shared test classes; this minimal ROM surface joins adapter to activity. */
class Input {
    fun requestKeysInterception(keys: IntArray, callback: IKeyCallback): IntArray? {
        registrations += Registration(keys.clone(), callback)
        return null
    }

    fun releaseKeysInterception(keys: IntArray, callback: IKeyCallback) {
        val registration = registrations.single { it.callback === callback && it.keys.contentEquals(keys) }
        registration.active = false
        releases += 1
    }

    data class Registration(val keys: IntArray, val callback: IKeyCallback, var active: Boolean = true)

    companion object {
        val registrations = mutableListOf<Registration>()
        var releases = 0
        @JvmStatic fun create(context: Context): Input = Input()
        fun reset() {
            registrations.clear()
            releases = 0
        }
        fun press(code: Int): List<Boolean> = registrations.toList()
            .filter { it.active && code in it.keys }.map { it.callback.onKeyPressed(code) }
        fun release(code: Int): List<Boolean> = registrations.toList()
            .filter { it.active && code in it.keys }.map { it.callback.onKeyReleased(code) }
    }
}

interface IKeyCallback {
    fun onKeyPressed(keyCode: Int): Boolean
    fun onKeyReleased(keyCode: Int): Boolean
}
