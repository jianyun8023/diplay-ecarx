package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The reflective face of the ECARX steering-wheel key interception, recovered from a third-party
 * app that runs on this head unit (see 适配文件/方控与语音助手按键-反编译结论.md).
 *
 * On 领克 / 亿咖通 (820A) head units the steering wheel does NOT deliver standard Android key codes
 * through a normal `Activity.dispatchKeyEvent`: the wheel reports keys in the ECARX 200000-offset
 * space and only surfaces them through the ROM's key-interception API. A third-party app that only
 * listens to standard key codes therefore never sees a wheel press (the exact "方控学习后按键无反应"
 * symptom). The reference app fixes this by registering the nine wheel keys with
 * `com.ecarx.xui.adaptapi.input.Input.requestKeysInterception(int[], IKeyCallback)`.
 *
 * Everything is reflection-only because `Input`/`IKeyCallback` live in the head-unit system image and
 * are never packaged in this APK (a compile-time reference would crash class-init elsewhere). Every
 * call tolerates absence; a head unit without the API simply yields no interception and the normal
 * key path is left untouched.
 */
object EcarxSwKeyIntercept {
    private const val TAG = "DiPlay-Ecarx-SwKey"

    /** The nine wheel keys the reference app intercepts (media.oneOsKey, media.smali:22626). */
    private val oneOsKey: IntArray = intArrayOf(
        0x30D98, // 200088 prev
        0x30D97, // 200087 next
        0x30D95, // 200085 play/pause
        0x30E27, // 200231 voice
        0x30DE4, // 200164 ?
        0x30ED0, // 200400 ?
        0x30D58, // 200024 volume+
        0x30D59, // 200025 volume-
        0x77,    // 119 ?
    )

    /**
     * [oneOsKey] by position. A bare key code says nothing in a log line, and the whole point of
     * printing the ROM's answer is to see *which* keys it kept for itself.
     */
    private val oneOsName: Array<String> = arrayOf(
        "prev", "next", "play", "voice", "k200164", "k200400", "vol+", "vol-", "k119",
    )

    /** A single wheel key press (or release) surfaced by the platform callback. */
    interface Listener {
        /** A wheel key went down; [keyCode] is in the ECARX 200000-offset space. Return true to consume. */
        fun onKeyPressed(keyCode: Int): Boolean
        fun onKeyReleased(keyCode: Int): Boolean
    }

    data class Result(val ok: Boolean, val reason: String)

    /**
     * Reflectively registers the nine wheel keys; the returned handle must be released on teardown.
     *
     * [label] only names the caller in diagnostics: the ROM's key-interception API is per-process,
     * so two live registrations (the settings screen and the CarPlay screen) can both answer one
     * press, and the log has to say which one did.
     */
    fun register(context: Context, listener: Listener, label: String = "app"): Handle? {
        val input = createInput(context) ?: return null
        val callbackClass = callbackClass() ?: return null
        val proxy = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
            CallbackHandler(listener),
        )
        val refused = runCatching {
            lookup(input, "requestKeysInterception", arrayOf(IntArray::class.java, callbackClass))
                ?.invoke(input, oneOsKey, proxy) as? IntArray
        }.getOrElse {
            Log.i(TAG, "requestKeysInterception failed: ${describe(it)}")
            return null
        }
        // The reference app treats a non-throwing call as accepted, but the returned int[] is the
        // ROM's actual answer: it lists the keys it refused, and a refused voice key is exactly what
        // leaves the factory HMI still answering the same press. Keep it, do not drop it on the floor.
        val handle = Handle(input, proxy, callbackClass, label, refused) { inputObj, callbackObj ->
            runCatching {
                lookup(inputObj, "releaseKeysInterception", arrayOf(IntArray::class.java, callbackClass))
                    ?.invoke(inputObj, oneOsKey, callbackObj)
            }.isSuccess
        }
        Log.i(TAG, "interception $label ${handle.describe()}")
        return handle
    }

    /** A held interception registration; [release] hands the keys back to the platform. */
    class Handle internal constructor(
        private val input: Any,
        private val callback: Any,
        private val callbackClass: Class<*>,
        val label: String,
        /** The keys the ROM refused to intercept, or null when it accepted the whole set. */
        val refused: IntArray?,
        private val release: (Any, Any) -> Boolean,
    ) {
        fun release() {
            runCatching { release(input, callback) }
        }

        /** One log line: which key set went out and what the ROM answered, each key named. */
        fun describe(): String {
            val refusedKeys = refused
            if (refusedKeys == null || refusedKeys.isEmpty()) return "keys=${oneOsKey.size} accepted=all"
            val answer = StringBuilder("refused=")
            var named = 0
            for (key in refusedKeys) {
                if (named > 0) answer.append('|')
                answer.append(nameFor(key))
                named += 1
            }
            return "keys=${oneOsKey.size} $answer"
        }
    }

    /** The name of [keyCode] when it is one of [oneOsKey]; the number itself otherwise. */
    private fun nameFor(keyCode: Int): String {
        var index = 0
        while (index < oneOsKey.size) {
            if (oneOsKey[index] == keyCode) return oneOsName[index]
            index += 1
        }
        return keyCode.toString()
    }

    // ---------------------------------------------------------------- plumbing

    private fun createInput(context: Context): Any? {
        val inputClass = runCatching { Class.forName("com.ecarx.xui.adaptapi.input.Input") }.getOrNull()
            ?: return null
        return runCatching {
            inputClass.getMethod("create", Context::class.java).invoke(null, context)
        }.getOrElse {
            Log.i(TAG, "Input.create failed: ${describe(it)}")
            null
        }
    }

    private fun callbackClass(): Class<*>? =
        runCatching { Class.forName("com.ecarx.xui.adaptapi.input.IKeyCallback") }.getOrNull()

    private fun lookup(target: Any, name: String, params: Array<Class<*>>): Method? =
        runCatching { target.javaClass.getMethod(name, *params) }
            .recoverCatching { target.javaClass.getDeclaredMethod(name, *params).also { it.isAccessible = true } }
            .getOrNull()

    private fun describe(error: Throwable): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    private class CallbackHandler(private val listener: Listener) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "onKeyPressed" -> listener.onKeyPressed((args?.firstOrNull() as? Int) ?: -1)
            "onKeyReleased" -> listener.onKeyReleased((args?.firstOrNull() as? Int) ?: -1)
            "toString" -> "EcarxSwKeyCallback"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                else -> null
            }
        }
    }
}
