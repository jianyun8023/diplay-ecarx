package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The reflective face of the ECARX microphone arbitration policy.
 *
 * On 领克 / 亿咖通 (820A) head units the in-car microphone is a shared resource the platform
 * arbitrates: recording from a third-party app without asking can leave the CarPlay uplink muted,
 * because the factory navigation / voice assistant holds the mic. The reference app therefore asks
 * for the mic before recording and releases it afterwards (see its `requestMic` / `releaseMic` pair
 * and the `isNeedrequestMic` gate).
 *
 * The chain the reference app follows, recovered from its bytecode:
 * ```
 * com.ecarx.sdk.policy.PolicyAPI.get(Context)        -> PolicyAPI (also a static get())
 *   .getAudioPolicy()                                -> IAudioPolicy
 *   .isNeedrequestMic()                              -> boolean  (ask first; skip if the ROM refuses)
 *   .requestMic(int requestType, IMicResourceCallback) -> boolean
 *   .releaseMic(IMicResourceCallback)                -> boolean
 * IMicResourceCallback.onResume() / onAbort(int reason)
 * ```
 * REQUEST_TYPE_SYSTEM=1 / CALL=2 / RECORD=3. CarPlay telephony uplink uses CALL=2, mirroring the reference app.
 *
 * Everything here is reflection-only for the same reason as [EcarxDimApi]: these classes live in the
 * head-unit system image, not this APK, and a compile-time reference would crash class-init on any
 * other head unit. Every call tolerates absence and returns a [Result] with a diagnosable reason.
 */
internal object EcarxMicPolicy {
    private const val TAG = "DiPlay-Ecarx-Mic"

    const val REQUEST_TYPE_SYSTEM = 1
    const val REQUEST_TYPE_CALL = 2
    const val REQUEST_TYPE_RECORD = 3

    /** The callback the platform invokes when the mic is granted (`onResume`) or refused (`onAbort`). */
    interface Listener {
        fun onResume()
        fun onAbort(reason: Int)
    }

    data class Result(val ok: Boolean, val reason: String) {
        companion object {
            fun ok(what: String) = Result(true, what)
            fun failed(what: String) = Result(false, what)
        }
    }

    /**
     * A single held mic resource request. [release] hands the mic back; a failed or unsupported
     * request is a no-op. Callers keep it alive for exactly the recording span.
     */
    class Held internal constructor(
        private val policy: Any,
        private val callback: Any,
        private val listenerProxy: Any?,
        private val release: (Any, Any) -> Boolean,
    ) {
        var granted = false
            private set

        internal fun markGranted() {
            granted = true
        }

        fun release(): Result {
            if (listenerProxy == null) return Result.ok("no callback registered")
            return runCatching { release(policy, callback) }
                .fold(
                    { Result.ok("releaseMic") },
                    { Result.failed("releaseMic threw ${describe(it)}") },
                )
        }
    }

    /** Probes for the policy API and reports what this head unit exposes. */
    fun describe(context: Context): String = buildString {
        append("policyApi=").append(policyClass() != null)
        append(" audioPolicy=").append(audioPolicyClass() != null)
        append(" micCallback=").append(micCallbackClass() != null)
    }

    /**
     * Asks the platform whether this app should request the mic before recording. False on ROMs that
     * do not arbitrate, so callers can skip the request without losing anything.
     */
    fun isNeedRequestMic(context: Context): Boolean {
        val audioPolicy = audioPolicy(context) ?: return false
        val need = runCatching {
            lookup(audioPolicy, "isNeedrequestMic", emptyArray())?.invoke(audioPolicy) as? Boolean
        }.getOrNull() ?: return false
        return need
    }

    /**
     * Requests the mic for the recording span, returning a [Held] whose [Held.release] must be called
     * once recording stops. When the platform refuses, the callback reports `onAbort` and [Held.granted]
     * stays false; recording should then proceed directly (the reference app downgrades to direct recording).
     */
    fun requestMic(context: Context, requestType: Int, listener: Listener): Held? {
        val audioPolicy = audioPolicy(context) ?: return null
        val callbackClass = micCallbackClass() ?: return null
        val listenerProxy = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
            MicCallbackHandler(listener),
        )
        val accepted = runCatching {
            lookup(audioPolicy, "requestMic", arrayOf(Int::class.javaPrimitiveType!!, callbackClass))
                ?.invoke(audioPolicy, requestType, listenerProxy) as? Boolean
        }.getOrElse {
            Log.i(TAG, "requestMic failed: ${describe(it)}")
            return null
        }
        if (accepted != true) {
            Log.i(TAG, "requestMic declined requestType=$requestType")
            return null
        }
        val held = Held(audioPolicy, listenerProxy, listenerProxy) { policy, callback ->
            lookup(policy, "releaseMic", arrayOf(callbackClass))
                ?.invoke(policy, callback) as? Boolean ?: false
        }
        // The reference app treats a synchronous accept as granted; onResume may follow, but grant-on-accept is
        // what lets recording begin immediately.
        held.markGranted()
        return held
    }

    // ---------------------------------------------------------------- plumbing

    private fun policyClass(): Class<*>? = listOf(
        EcarxCapabilities.POLICY_API,
        EcarxCapabilities.EAS_POLICY_API,
    ).firstNotNullOfOrNull { runCatching { Class.forName(it) }.getOrNull() }

    private fun audioPolicyClass(): Class<*>? =
        runCatching { Class.forName(EcarxCapabilities.AUDIO_POLICY) }.getOrNull()

    private fun micCallbackClass(): Class<*>? =
        runCatching { Class.forName(EcarxCapabilities.MIC_CALLBACK) }.getOrNull()

    private fun audioPolicy(context: Context): Any? {
        val policyClass = policyClass() ?: return null
        // The reference app calls PolicyAPI.get(Context) (api.smali:31631) or createPolicyAPI(Context)
        // (:35703); the EAS alias also exposes a no-arg get(). Try in that order.
        val policy = runCatching {
            policyClass.getMethod("get", Context::class.java).invoke(null, context)
        }.getOrElse {
            runCatching {
                policyClass.getMethod("createPolicyAPI", Context::class.java).invoke(null, context)
            }.getOrElse {
                runCatching {
                    policyClass.getMethod("get").invoke(null)
                }.getOrElse {
                    Log.i(TAG, "PolicyAPI.get/createPolicyAPI failed: ${describe(it)}")
                    return null
                }
            }
        }
        return runCatching {
            lookup(policy, "getAudioPolicy", emptyArray())?.invoke(policy)
        }.getOrElse {
            Log.i(TAG, "getAudioPolicy failed: ${describe(it)}")
            null
        }
    }

    private fun lookup(target: Any, name: String, params: Array<Class<*>>): Method? =
        runCatching { target.javaClass.getMethod(name, *params) }
            .recoverCatching { target.javaClass.getDeclaredMethod(name, *params).also { it.isAccessible = true } }
            .getOrNull()

    private fun describe(error: Throwable): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    private class MicCallbackHandler(private val listener: Listener) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "onResume" -> {
                listener.onResume()
                null
            }
            "onAbort" -> {
                listener.onAbort((args?.firstOrNull() as? Int) ?: -1)
                null
            }
            "toString" -> "EcarxMicCallback"
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
