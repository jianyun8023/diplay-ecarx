package com.shilapi.xcertplay.mediacenter

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** ECARX service access and reflected contract; inspecting them never registers a media session. */
internal object MediaCenterProbe {
    const val PERMISSION = "ecarx.oem.permission.OPENAPI_MEDIACENTER_PERMISSION"
    const val SERVICE_PACKAGE = "ecarx.xsf.mediacenter"
    const val SERVICE_CLASS = "ecarx.xsf.mediacenter.MediaCenterService"
    const val SERVICE_ACTION = "ecarx.xsf.MEDIA_CENTER_SERVICE"
    val component get() = ComponentName(SERVICE_PACKAGE, SERVICE_CLASS)

    data class Access(val granted: Boolean, val serviceUsable: Boolean, val report: String)

    @Suppress("DEPRECATION")
    fun access(context: Context): Access {
        val pm = context.packageManager
        val declared = runCatching { pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(PERMISSION) == true }.getOrDefault(false)
        val granted = pm.checkPermission(PERMISSION, context.packageName) == PackageManager.PERMISSION_GRANTED
        val level = runCatching { pm.getPermissionInfo(PERMISSION, 0).protectionLevel }.getOrNull()
        val signature = runCatching { pm.checkSignatures(context.packageName, "android") }.getOrNull()
        val service = runCatching { pm.getServiceInfo(component, 0) }.getOrNull()
        val servicePermissionGranted = service?.permission?.let {
            pm.checkPermission(it, context.packageName) == PackageManager.PERMISSION_GRANTED
        } ?: true
        val usable = service != null && service.enabled && service.applicationInfo?.enabled != false &&
            service.exported && servicePermissionGranted
        return Access(granted, usable, buildString {
            append("MediaCenter permissionDeclared=$declared permissionGranted=$granted protectionLevel=${level ?: "unknown"}")
            append(" platformSignatureMatch=${signature == PackageManager.SIGNATURE_MATCH} signatureCheck=${signature ?: "unknown"}")
            append(" servicePresent=${service != null} serviceExported=${service?.exported ?: "unknown"}")
            append(" serviceEnabled=${service?.enabled ?: "unknown"} servicePermission=${service?.permission ?: "none"}")
            append(" servicePermissionGranted=$servicePermissionGranted")
        })
    }

    fun loader(context: Context): ClassLoader {
        val own = context.classLoader
        return try {
            Class.forName("com.ecarx.sdk.mediacenter.MediaCenterAPI", false, own); own
        } catch (_: ClassNotFoundException) {
            context.createPackageContext("com.ecarx.systemui.plugin", Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY).classLoader
        }
    }

    data class Types(val client: Class<*>, val wrapper: Class<*>, val playback: Class<*>,
        val playbackWrapper: Class<*>, val media: Class<*>, val helper: Class<*>, val service: Class<*>, val stub: Class<*>)

    fun types(loader: ClassLoader): Types {
        fun sdk(name: String) = Class.forName("com.ecarx.sdk.mediacenter.$name", false, loader)
        return Types(sdk("IMusicClient"), sdk("MusicClientWrapper"), sdk("IMusicPlaybackInfo"),
            sdk("PlaybackInfoWrapper"), sdk("IMediaInfo"), sdk("MediaBeanHelper"),
            Class.forName("ecarx.xsf.mediacenter.IMediaCenterSvc", false, loader),
            Class.forName("ecarx.xsf.mediacenter.IMediaCenterSvc\$Stub", false, loader))
    }

    data class Contract(val types: Types, val clientConstructor: Constructor<*>, val playbackConstructor: Constructor<*>,
        val register: Method, val unregister: Method, val sourceList: Method, val source: Method,
        val playlist: Method, val playback: Method, val progress: Method, val requestPlay: Method, val mediaList: Method,
        val asInterface: Method) {
        val artworkSupported: Boolean get() = supportsArtwork(types.playback)

        companion object {
            fun resolve(types: Types): Contract {
                require(types.client.isInterface && types.playback.isInterface && types.media.isInterface)
                val register = types.service.methods.filter { it.name == "registerMusic" && it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(types.wrapper) && !it.returnType.isPrimitive && it.returnType != Void.TYPE }
                    .distinctBy(::signature).single()
                val token = register.returnType
                fun method(name: String, vararg params: Class<*>) = types.service.getMethod(name, *params)
                val unregister = method("unregister", token).also { require(it.returnType == Boolean::class.javaPrimitiveType) }
                val request = method("requestPlay", token).also { require(it.returnType == Boolean::class.javaPrimitiveType) }
                val playback = types.service.methods.filter { it.name == "updateMusicPlaybackState" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == token && it.parameterTypes[1].isAssignableFrom(types.playbackWrapper) }
                    .distinctBy(::signature).single().also { require(it.returnType == Boolean::class.javaPrimitiveType) }
                val list = types.helper.getDeclaredMethod("getIMediaList", List::class.java).also {
                    require(Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.returnType)) }
                val iface = types.stub.getMethod("asInterface", android.os.IBinder::class.java).also {
                    require(Modifier.isStatic(it.modifiers) && types.service.isAssignableFrom(it.returnType)) }
                return Contract(types, types.wrapper.getDeclaredConstructor(types.client),
                    types.playbackWrapper.getDeclaredConstructor(types.playback), register, unregister,
                    method("updateMediaSourceTypeList", token, IntArray::class.java),
                    method("updateCurrentSourceType", token, Int::class.javaPrimitiveType!!),
                    method("updatePlaylist", token, Int::class.javaPrimitiveType!!, List::class.java),
                    playback, method("updateCurrentProgress", token, Long::class.javaPrimitiveType!!), request, list, iface)
            }
        }
    }

    internal fun supportsArtwork(type: Class<*>): Boolean = runCatching {
        type.getMethod("getArtwork").returnType == android.net.Uri::class.java
    }.getOrDefault(false)

    internal fun signature(method: Method): String = method.name + "(" +
        method.parameterTypes.joinToString(",") { it.name } + "):" + method.returnType.name
}
