package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/** GKUI Policy lifecycle; see docs/ECARX_SDK_CONTEXT.zh-CN.md for the target's server limitation. */
internal object EcarxMicPolicy {
    private const val TAG = "DiPlay-Ecarx-Mic"
    private const val READY_TIMEOUT_MS = 1_500L
    const val REQUEST_TYPE_CALL = 2

    interface Listener {
        fun onResume()
        fun onAbort(requestType: Int)
        fun onFallback(reason: String)
        fun onDiagnostic(line: String)
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    // The SDK retains init callbacks for the process lifetime. Register one without retaining a sink.
    private var policyApi: Any? = null
    private var ready: Boolean? = null
    private val requests = mutableSetOf<Held>() // main thread only

    fun describe(): String = buildString {
        append("policyApi=").append(EcarxCapabilities.classPresent(EcarxCapabilities.POLICY_API))
        append(" audioPolicy=").append(EcarxCapabilities.classPresent(EcarxCapabilities.AUDIO_POLICY))
        append(" micCallback=").append(EcarxCapabilities.classPresent(EcarxCapabilities.MIC_CALLBACK))
    }

    /** Returns a cancellable request, not a grant. Only onResume authorizes capture. */
    fun requestMic(context: Context, requestType: Int, listener: Listener): Held? {
        val app = context.applicationContext
        val type = runCatching { Class.forName(EcarxCapabilities.POLICY_API) }.getOrNull() ?: return null
        val api = runCatching { type.getMethod("get", Context::class.java).invoke(null, app) }
            .onFailure { Log.i(TAG, "PolicyAPI unavailable: $it") }.getOrNull() ?: return null
        return Held(requestType, listener).also { held ->
            main.post {
                if (held.closed.get()) return@post
                requests.add(held)
                main.postDelayed(held.timeout, READY_TIMEOUT_MS)
                held.report("initializing")
                if (policyApi == null) {
                    runCatching {
                        policyApi = api
                        val callback = callback(EcarxCapabilities.POLICY_CALLBACK) { name, args ->
                            if (name == "onAPIReady") {
                                ready = args!![0] as Boolean
                                requests.toList().forEach { it.onReady(ready == true) }
                            }
                        }
                        type.getMethod("init", Context::class.java, callback.javaClass.interfaces.single())
                            .invoke(policyApi, app, callback)
                    }.onFailure {
                        policyApi = null
                        ready = null
                        requests.toList().forEach { request -> request.fallback("init failed: $it") }
                    }
                } else ready?.let(held::onReady)
            }
        }
    }

    class Held internal constructor(private val requestType: Int, private var listener: Listener?) {
        internal val closed = AtomicBoolean(false)
        internal val timeout = Runnable { fallback("API/grant timeout") }
        private var releaseMic: (() -> Boolean)? = null

        internal fun report(message: String) {
            Log.i(TAG, message)
            runCatching { listener?.onDiagnostic("Audio: ECARX mic $message") }
        }

        internal fun onReady(isReady: Boolean) {
            if (closed.get()) return
            report("onAPIReady ready=$isReady")
            if (!isReady) {
                fallback("API not ready")
                return
            }
            if (releaseMic != null) return
            runCatching {
                // initPolicy() replaces the audio wrapper; never cache getAudioPolicy() before readiness.
                val api = policyApi ?: error("Policy API unavailable")
                val audio = Class.forName(EcarxCapabilities.POLICY_API).getMethod("getAudioPolicy").invoke(api)
                    ?: error("Audio policy unavailable")
                val type = Class.forName(EcarxCapabilities.AUDIO_POLICY)
                val callback = callback(EcarxCapabilities.MIC_CALLBACK) { name, args ->
                    if (!closed.get() && releaseMic != null) {
                        when (name) {
                            "onResume" -> {
                                main.removeCallbacks(timeout)
                                report("onResume requestType=CALL(2) granted=true")
                                listener?.onResume()
                            }
                            "onAbort" -> {
                                main.removeCallbacks(timeout)
                                val abortedType = args!![0] as Int
                                report("onAbort requestType=$abortedType granted=false")
                                listener?.onAbort(abortedType)
                            }
                        }
                    }
                }
                val callbackType = callback.javaClass.interfaces.single()
                val accepted = type.getMethod("requestMic", Int::class.javaPrimitiveType, callbackType)
                    .invoke(audio, requestType, callback) == true
                report("request requestType=CALL(2) accepted=$accepted granted=${if (accepted) "unknown" else "false"}")
                if (accepted) {
                    releaseMic = { type.getMethod("releaseMic", callbackType).invoke(audio, callback) == true }
                } else fallback("request declined or unsupported")
            }.onFailure { fallback("request failed: $it") }
        }

        internal fun fallback(reason: String) {
            if (closed.compareAndSet(false, true)) finish(reason)
        }

        /** Cancel immediately, including queued grants; vendor cleanup runs on its callback thread. */
        fun release() {
            if (closed.compareAndSet(false, true)) main.post { finish(null) }
        }

        private fun finish(fallbackReason: String?) {
            main.removeCallbacks(timeout)
            requests.remove(this)
            val release = releaseMic
            releaseMic = null
            val result = runCatching { release?.invoke() ?: true }
            report("released held=${release != null} ok=${result.getOrDefault(false)}${result.exceptionOrNull()?.let { " error=$it" } ?: ""}")
            val target = listener
            listener = null
            if (fallbackReason != null) {
                Log.i(TAG, "recording directly: $fallbackReason")
                runCatching { target?.onDiagnostic("Audio: ECARX mic fallback reason=$fallbackReason") }
                target?.onFallback(fallbackReason)
            }
        }
    }

    /** Both SDK callback interfaces are void; marshal Binder callbacks before touching request state. */
    private fun callback(name: String, onCall: (String, Array<out Any?>?) -> Unit): Any {
        val type = Class.forName(name)
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "DiPlayPolicyCallback($name)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> { main.post { onCall(method.name, args) }; null }
            }
        }
    }
}
