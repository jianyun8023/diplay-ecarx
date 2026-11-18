package com.shihab.diplay.media

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder

internal interface EcarxCarAudioGroups : AutoCloseable {
    /** Returns the starting navigation level if every group that duck can write is above zero. */
    fun markableNavigationVolume(): Int?
    fun navigationVolume(): Int?
    fun restoreNavigationVolume(value: Int)
}

internal class EcarxCarAudioVolume(
    context: Context,
    handler: Handler,
    private val report: (String) -> Unit,
) : EcarxCarAudioGroups {
    private var car: Any? = null
    @Volatile private var manager: Any? = null
    @Volatile private var closed = false

    init {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val audio = car ?: return
                if (closed) return
                manager = runCatching {
                    audio.javaClass.getMethod("getCarManager", String::class.java).invoke(audio, Context.AUDIO_SERVICE)
                }.getOrNull()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                manager = null
            }
        }
        car = if (context.checkSelfPermission("android.car.permission.CAR_CONTROL_AUDIO_VOLUME") == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                Class.forName("android.car.Car")
                    .getMethod("createCar", Context::class.java, ServiceConnection::class.java, Handler::class.java)
                    .invoke(null, context, connection, handler)
            }.getOrNull()
        } else null
        runCatching { car?.javaClass?.getMethod("connect")?.invoke(car) }
    }

    override fun markableNavigationVolume(): Int? {
        val audio = manager ?: return null
        return runCatching {
            val read = audio.javaClass.getMethod("getGroupVolume", Int::class.javaPrimitiveType)
            // The receiver may see a different focus-stack top by the time the broadcast arrives.
            val navigation = read.invoke(audio, NAVIGATION_GROUP) as Int
            navigation.takeIf { it > 0 && otherDuckGroups.all { group -> (read.invoke(audio, group) as Int) > 0 } }
        }.getOrNull()
    }

    override fun navigationVolume(): Int? {
        val audio = manager ?: return null
        return runCatching {
            audio.javaClass.getMethod("getGroupVolume", Int::class.javaPrimitiveType)
                .invoke(audio, NAVIGATION_GROUP) as Int
        }.getOrNull()
    }

    override fun restoreNavigationVolume(value: Int) {
        val audio = manager ?: return
        runCatching {
            val intType = Int::class.javaPrimitiveType
            audio.javaClass.getMethod("setGroupVolume", intType, intType, intType)
                .invoke(audio, NAVIGATION_GROUP, value, 0)
        }.onFailure { report("CarPlay audio volume restore failed: ${it.javaClass.simpleName}") }
    }

    override fun close() {
        closed = true
        manager = null
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
    }

    private companion object {
        const val NAVIGATION_GROUP = 1
        val otherDuckGroups = listOf(0, 2, 4)
    }
}
