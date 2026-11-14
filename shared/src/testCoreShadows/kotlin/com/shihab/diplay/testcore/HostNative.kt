@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.shihab.diplay.testcore

import com.shihab.diplay.iap2.wire.Iap2ProtocolException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/** JVM tests share one real host library; only bootstrap types cross the sandbox boundary. */
internal object HostNative {
    private val hostLoader = HostNative::class.java.classLoader!!.parent
    private val loaded by lazy {
        val media = Class.forName("com.shihab.diplay.airplay.NativeMedia", true, hostLoader)
        val instance = media.getField("INSTANCE").get(null)
        check(media.getMethod("getAvailable").invoke(instance) == true) {
            "The host Rust core was not prepared for this JVM test"
        }
        true
    }

    fun requireCore(): Boolean = loaded

    fun call(owner: String, name: String, vararg arguments: Any?): Any? {
        check(loaded)
        val type = Class.forName("com.shihab.diplay.$owner", true, hostLoader)
        val method = type.declaredMethods.single { it.name == name && Modifier.isStatic(it.modifiers) }.apply { isAccessible = true }
        return try {
            method.invoke(null, *arguments)
        } catch (failure: InvocationTargetException) {
            val cause = failure.targetException
            // This one application exception is part of the wire API; preserve its sandbox identity.
            if (cause.javaClass.name == Iap2ProtocolException::class.java.name) {
                throw Iap2ProtocolException(cause.message ?: "", cause.cause)
            }
            throw cause
        }
    }

    fun component(value: Any, index: Int): Any? = value.javaClass.getMethod("component$index").invoke(value)
}
