package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * The reflective face of the ECARX XUI DIM API.
 *
 * Everything here is deliberately reflection-only:
 *  * the DIM classes are head-unit system classes, so a compile-time reference would turn a
 *    missing class into `NoClassDefFoundError` at class-init time on every other head unit;
 *  * ECARX ships no public SDK jar, so the method names and descriptors below are the ones
 *    recovered from the bytecode of the two shipped ECARX apps on this head unit.
 *
 * No call in this file may throw. Each returns [EcarxResult], whose `reason` is written to the
 * session log so a refusal on an unexpected firmware is diagnosable instead of silent.
 *
 * The contract below is the **real device** contract, dumped with `Class.getMethods()` on the car
 * by probe v2 (see 适配文件/亿咖通DIM-真机契约转储.md and 日志分析/v2-run2-1.txt). It supersedes the
 * `s1/r.smali` reading we started from — that one was a *implementation class* of
 * `INavigationInfo`, and it carries two extra public methods the interface never declared
 * (`getDayNightMode`, `getExtensionInfo`). The device interface has 13 getters, not 15.
 *
 * ```
 * DimInteraction.create(Context)                                        static -> DimInteraction (may be null)
 * DimInteraction.getNaviInteraction()                                   -> INaviInteraction
 * DimInteraction.getMediaInteraction()                                  -> IMediaInteraction
 * DimInteraction.getPhoneCallInteraction()                              -> IPhoneCallInteraction
 * DimInteraction.getShowPresentationOption()                            -> int   (the only read-back we found)
 * DimInteraction.setPresentationToDimSwitch(int,String,String,boolean)  <- 仪表投屏开关, returns void
 * INaviInteraction.updateNavigationInfo(INavigationInfo)
 * INaviInteraction.updateTurnByTurnArrow(int) | (String) | (Bitmap)   -- 三个重载
 * INaviInteraction.updateDistanceToNextGuidancePoint(int)
 * INaviInteraction.updateDistanceToDestination(int)
 * INaviInteraction.updateETA(int)
 * INaviInteraction.updateNextGuidancePointName(String)
 * INaviInteraction.updateLaneInfo(ILaneInfo[])
 * INaviInteraction.updateDriveDirection(int)   /  updateMuteState(int)
 * INaviInteraction.notifyTurnByTurnStarted()  /  notifyTurnByTurnStopped()
 * INaviInteraction$INavigationInfo getters (13 on the device):
 *        getNavigationTurnId():int         getDistanceToNextGuidancePoint():long
 *        getDistanceToDestination():long   getDrivingDirection():int
 *        getETA():long                     getLaneInfo():ILaneInfo[]
 *        getMuteState():int                getNavigationStatus():int
 *        getNextGuidancePointName():String getNavigationTurnSVG():String
 *        getHighwayExitInfo() / getRoadCameraInfo() / getServiceAreaInfo()
 * ```
 *
 * Not in the contract, because it does not exist on the target head unit:
 *  * `INaviInteraction.notifyNavigationStatus(int)` — absent (probe printed "未找到重载").
 *  * `ecarx.dimprotocol.DIMProtocolManager` — the class is not in the system image at all, so
 *    [setNaviMode] can only ever fail there, so it is never offered.
 *
 * The media half of the same API is called from the bytecode of the shipped ECARX toolkit
 * (`com/hyphp/hywebviewdcltools/l.smali`, `i.smali`, `m.smali`) rather than from a device dump, and
 * it is only four methods:
 * ```
 * IMediaInteraction.updateCurrentSourceType(int)              -- the toolkit sends 6, once
 * IMediaInteraction.updatePlaybackInfo(IMediaInteraction$IPlaybackInfo)
 * IMediaInteraction.updatePlaylist(int, List<IMediaInteraction$IMedia>)   -- one entry
 * IMediaInteraction.updateCurrentProgress(long)               -- milliseconds
 * ```
 * There is no `registerMediaInteractionCallback` on it: the feed is one-way, and steering-wheel
 * keys still arrive through Android's media session. Because no dump exists, these four are resolved
 * by name and arity ([callAny]) and their numeric payload fields are matched to whatever the
 * interface declared ([coerceNumber]) — see those two for why that is the honest choice here.
 *
 * **Return values carry no authority.** The probe showed a normal-signed build with every ECARX
 * permission denied gets byte-identical results — `create()`, `setPresentationToDimSwitch()` and
 * `updateNavigationInfo()` all report success. These are `void` methods and the platform does not
 * throw on refusal, so [EcarxResult.ok] means "the call was accepted for dispatch", never "the
 * cluster changed". Only the car can answer the latter.
 */
internal object EcarxDimApi {
    private const val TAG = "DiPlay-Ecarx-DIM"

    /**
     * A second destination for [report], set by the HUD bridge so every refusal in this file also
     * lands in the session report.
     *
     * A car has no adb, and "the call was never even attempted" is exactly the kind of fact that
     * otherwise exists only in logcat.
     */
    @Volatile private var reportSink: ((String) -> Unit)? = null

    fun attachReport(sink: (String) -> Unit) {
        reportSink = sink
    }

    /** logcat *and* the report; see [attachReport]. */
    private fun report(line: String) {
        Log.i(TAG, line)
        reportSink?.invoke(line)
    }

    /** The screen id the stock ECARX apps pass; both of them hardcode `1`. */
    const val DEFAULT_SCREEN_ID = 1

    data class EcarxResult(val ok: Boolean, val reason: String) {
        companion object {
            fun ok(what: String) = EcarxResult(true, what)
            fun failed(what: String) = EcarxResult(false, what)
        }
    }

    /** One navigation frame in ECARX vocabulary. `-1` means "this field has no new value". */
    data class Guidance(
        val turnId: Int = 0,
        val distanceToNextMeters: Long = -1,
        val distanceToDestinationMeters: Long = -1,
        val etaEpochSeconds: Long = -1,
        val drivingDirection: Int = 0,
        val status: Int = 0,
        val muteState: Int = 0,
        val dayNightMode: Int = 0,
        val nextGuidancePointName: String = "",
    )

    // ---------------------------------------------------------------- DimInteraction

    fun createDimInteraction(context: Context): Any? = runCatching {
        val cls = Class.forName(EcarxCapabilities.DIM_INTERACTION)
        cls.getMethod("create", Context::class.java).invoke(null, context)
    }.getOrElse {
        report("DimInteraction.create failed: ${describe(it)}")
        null
    }

    fun naviInteraction(dim: Any): Any? = read(dim, "getNaviInteraction")

    fun mediaInteraction(dim: Any): Any? = read(dim, "getMediaInteraction")

    fun phoneCallInteraction(dim: Any): Any? = read(dim, "getPhoneCallInteraction")

    /**
     * The 仪表投屏 switch. The head unit's own navigation app calls
     * `setPresentationToDimSwitch(1, "com.autonavi.amapauto", "com.autonavi.amapauto", show)`
     * (`ecarx/naviservice/a/g.smali`), i.e. it names the package whose window is to be mirrored.
     *
     * This project has always passed `""` for both strings: DiPlay renders CarPlay into a full-screen
     * surface rather than a named package's window, so there seemed to be no package to name. The
     * driver's verdict on that choice is that the cluster then shows a square picture which does not
     * fit its shape — so the argument is exposed instead of assumed. See
     * [EcarxOutputSettings.presentationPackage] for the three values offered.
     */
    fun setPresentationToDimSwitch(
        dim: Any,
        show: Boolean,
        screenId: Int = DEFAULT_SCREEN_ID,
        packageName: String = "",
    ): EcarxResult = call(
        dim,
        "setPresentationToDimSwitch",
        arrayOf(
            Int::class.javaPrimitiveType!!,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        ),
        arrayOf<Any?>(screenId, packageName, packageName, show),
        "setPresentationToDimSwitch($screenId,\"$packageName\",\"$packageName\",$show)",
    )

    /**
     * `DimInteraction.registerInteractionCallback(IInteractionCallback)`.
     *
     * The head unit's own navigation app registers one (`ecarx/naviservice/a/g.smali`, line 149), and
     * that is how the cluster's *own* questions reach the navigation side — the app's callback logs
     * `onDoInteractionAction ACTION_GET_FAVORITE`, i.e. the DIM asks the navigation app for data, it
     * does not only receive pushes. Without a registration those requests have no receiver here.
     *
     * Nothing is interpreted yet: the handler logs every call it gets and answers with the return
     * type's default value, so the first drive tells us which actions this head unit actually asks
     * for instead of us guessing the set.
     *
     * Returns the proxy to pass to `unregisterInteractionCallback`, or null when this head unit has
     * no such callback interface or the registration was refused.
     */
    fun registerInteractionCallback(dim: Any, label: String): Any? =
        registerCallback(dim, EcarxCapabilities.DIM_CALLBACK, "registerInteractionCallback", label, "dim")

    /**
     * `INaviInteraction.registerNavigationInteractionCallback(INavigationInteractionCallback)` — the
     * navigation-scoped half of the same idea. The stock app registers both (same file, line 146).
     */
    fun registerNavigationInteractionCallback(navi: Any, label: String): Any? =
        registerCallback(
            navi, EcarxCapabilities.NAVI_CALLBACK, "registerNavigationInteractionCallback", label, "navi",
        )

    private fun registerCallback(
        target: Any,
        interfaceName: String,
        method: String,
        label: String,
        scope: String,
    ): Any? {
        val itf = runCatching { Class.forName(interfaceName) }.getOrNull() ?: return null
        val proxy = runCatching {
            Proxy.newProxyInstance(itf.classLoader, arrayOf(itf), DimCallbackHandler(label, scope))
        }.getOrElse {
            report("$scope callback proxy failed: ${describe(it)}")
            return null
        }
        val result = call(target, method, arrayOf(itf), arrayOf<Any?>(proxy), method)
        if (!result.ok) {
            report("$method failed: ${result.reason}")
            return null
        }
        return proxy
    }

    // ---------------------------------------------------------------- INaviInteraction

    fun updateNavigationInfo(navi: Any, guidance: Guidance): EcarxResult {
        val itf = runCatching { Class.forName(EcarxCapabilities.NAVI_INFO) }.getOrNull()
            ?: return EcarxResult.failed("INavigationInfo class missing")
        val info = runCatching {
            Proxy.newProxyInstance(itf.classLoader, arrayOf(itf), NaviInfoHandler(guidance))
        }.getOrElse { return EcarxResult.failed("INavigationInfo proxy failed: ${describe(it)}") }
        return call(navi, "updateNavigationInfo", arrayOf(itf), arrayOf<Any?>(info), "updateNavigationInfo")
    }

    fun updateTurnByTurnArrow(navi: Any, arrow: Int): EcarxResult =
        callInt(navi, "updateTurnByTurnArrow", arrow)

    /**
     * Pushes a turn-arrow *drawing* as SVG markup — the overload the head unit's own navigation app
     * uses, and therefore the one the cluster is known to render.
     *
     * Recovered from `ecarx.naviservice` (the shipped "导航" app): its DIM bridge
     * `ecarx/naviservice/a/g.smali` logs `updateTurnByTurnArrow svg:%s` and then calls
     * `updateTurnByTurnArrow(Ljava/lang/String;)V`; the markup is built in
     * `ecarx/naviservice/widget/f.smali` as
     * `<svg width="…" height="…" xmlns="…">…<path d="…Z" stroke-width="…" stroke="…" fill="…"/></svg>`.
     * The drawing reaches that method through an internal event carrying the string produced by
     * `widget/g.a()`.
     *
     * That app never calls the `(Bitmap)` overload, so SVG is the channel with a working reference
     * implementation behind it.
     */
    fun updateTurnByTurnArrow(navi: Any, svg: String): EcarxResult =
        call(
            navi, "updateTurnByTurnArrow", arrayOf(String::class.java), arrayOf<Any?>(svg),
            "updateTurnByTurnArrow(svg ${svg.length}B)",
        )

    /**
     * Pushes a drawn arrow bitmap through the `(Bitmap)` overload — the fallback when the head unit
     * has no `(String)` overload. It is the code-table-free channel: the stock app feeds the int
     * overload from its own table (see 适配文件/ecarx-arrowId-码表.tsv), never from a counter, so a
     * locally drawn arrow is the honest way out when no mapping is recovered.
     */
    fun updateTurnByTurnArrow(navi: Any, arrow: android.graphics.Bitmap): EcarxResult =
        call(navi, "updateTurnByTurnArrow", arrayOf(android.graphics.Bitmap::class.java), arrayOf<Any?>(arrow),
            "updateTurnByTurnArrow(Bitmap)")

    /**
     * Which `updateTurnByTurnArrow` overloads this head unit actually declares, as `int`/`string`/
     * `bitmap` joined by `/`, or `none`.
     *
     * Worth a report line because the three are not interchangeable and the stock app only ever uses
     * two of them: knowing whether `string` is there says whether the cluster is being fed the same
     * channel it is fed by its own navigation app, or a fallback of ours.
     */
    fun arrowOverloads(target: Any?): String {
        if (target == null) return "none"
        val names = arrayOf("int", "string", "bitmap")
        val types = arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, String::class.java, android.graphics.Bitmap::class.java)
        val found = StringBuilder()
        for (i in names.indices) {
            if (lookup(target, "updateTurnByTurnArrow", arrayOf(types[i])) == null) continue
            if (found.isNotEmpty()) found.append('/')
            found.append(names[i])
        }
        return found.toString().ifEmpty { "none" }
    }

    /**
     * The declared method set of a DIM handle, for the report.
     *
     * Every call in this file is resolved by name, and the names came from somebody else's bytecode —
     * the shipped toolkit for the media half, the head unit's own navigation app for the guidance
     * half. That is enough to make the calls this project needs work, and not enough to know what
     * *else* the interface offers: the projection decision that decides whether the cluster's
     * navigation and media cards read the phone at all (see [EcarxProjectionChannel]) has no known
     * method on either interface. A car has no adb and no `javap`, so the interface is printed into
     * the session report once and read off the log instead of guessed at from a decompile.
     */
    fun surface(target: Any?, label: String): String {
        val type = target?.javaClass ?: return "$label=absent"
        return methodSurface(type, label)
    }

    /**
     * Whether the *cluster service's* own client library is reachable from a third-party app.
     *
     * `android.cevt.dimsupportservice` is the package the shipped toolkit binds
     * (`DimSupportManager`, see [EcarxProjectionChannel]); `se.cevt.dimsupportservice` is the service
     * those calls land on, and it is the only thing that owns `updateNavigationPhoneProjection` and
     * `updateMediaPhoneProjection`. If the client classes are on this head unit's class path, the
     * projection decision is a binder call rather than an MQTT frame — a much shorter road than the
     * one this project currently takes — so the presence test and `DimSupportManager`'s own method
     * list are both worth having in the report.
     */
    fun cevt(): String = buildString {
        for (name in CEVT_CLASSES) {
            val short = name.substringAfterLast('.')
            val type = runCatching { Class.forName(name) }.getOrNull()
            append(short).append('=')
            append(if (type == null) "absent" else runCatching { type.methods.size }.getOrDefault(0))
            append(' ')
        }
        val manager = runCatching { Class.forName(CEVT_CLASSES.first()) }.getOrNull()
        if (manager != null) append(methodSurface(manager, "DimSupportManager"))
    }

    private fun methodSurface(type: Class<*>, label: String): String {
        val names = runCatching { type.methods.toList() }.getOrElse { return "$label=<${describe(it)}>" }
            .filter { it.declaringClass != Any::class.java }
            .map { method ->
                method.name + "(" + method.parameterTypes.joinToString(",") { simple(it) } + ")" +
                    (if (method.returnType == Void.TYPE) "" else ":" + simple(method.returnType))
            }
            .distinct()
            .sorted()
        return "$label=${names.size}{${names.joinToString(" ")}}"
    }

    /** Simple names, so a payload type reads as `IPlaybackInfo` rather than its whole descriptor. */
    private fun simple(type: Class<*>): String = when {
        type.isArray -> simple(type.componentType) + "[]"
        else -> type.simpleName.ifEmpty { type.name.substringAfterLast('.') }
    }

    private val CEVT_CLASSES = arrayOf(
        "android.cevt.dimsupportservice.DimSupportManager",
        "android.cevt.dimsupportservice.DimSupportServiceHost",
        "android.cevt.dimsupportservice.INavigationCallback",
        "android.cevt.dimsupportservice.IMediaCallback",
        "android.cevt.dimsupportservice.IAudioCallback",
    )

    fun updateDistanceToNextGuidancePoint(navi: Any, meters: Long): EcarxResult =
        callInt(navi, "updateDistanceToNextGuidancePoint", meters.toInt())

    fun updateDistanceToDestination(navi: Any, meters: Long): EcarxResult =
        callInt(navi, "updateDistanceToDestination", meters.toInt())

    fun updateEta(navi: Any, epochSeconds: Long): EcarxResult =
        callInt(navi, "updateETA", epochSeconds.toInt())

    fun updateNextGuidancePointName(navi: Any, name: String): EcarxResult =
        call(navi, "updateNextGuidancePointName", arrayOf(String::class.java), arrayOf<Any?>(name),
            "updateNextGuidancePointName(\"$name\")")

    /** Clears the lane strip. An empty typed array is what the stock apps send when none remain. */
    fun updateLaneInfoEmpty(navi: Any): EcarxResult {
        val lane = runCatching { Class.forName(EcarxCapabilities.LANE_INFO) }.getOrNull()
            ?: return EcarxResult.failed("ILaneInfo class missing")
        val empty = runCatching { java.lang.reflect.Array.newInstance(lane, 0) }
            .getOrElse { return EcarxResult.failed("ILaneInfo[] allocation failed: ${describe(it)}") }
        // The declared parameter is ILaneInfo[]; take the type off the array we just built so the
        // lookup matches the interface method exactly.
        return call(navi, "updateLaneInfo", arrayOf(empty.javaClass), arrayOf<Any?>(empty), "updateLaneInfo(empty)")
    }

    fun notifyTurnByTurnStarted(navi: Any): EcarxResult = call0(navi, "notifyTurnByTurnStarted")

    fun notifyTurnByTurnStopped(navi: Any): EcarxResult = call0(navi, "notifyTurnByTurnStopped")

    // There is deliberately no `notifyNavigationStatus` here: the real device's INaviInteraction
    // does not declare it (probe v2 printed "未找到 notifyNavigationStatus / 1 个参数的重载").

    /**
     * `DimInteraction.getShowPresentationOption()` — the only read-back the probe turned up.
     *
     * The car needs it because every write path returns `void` and is not permission-checked: a
     * normal-signed build with all ECARX permissions denied gets the same "success" as the
     * platform-signed one (probe v2, see [EcarxResult]). So a write proves nothing by itself, and
     * this is the one call that might say what the cluster is actually set to.
     *
     * Nothing tells us how to read the int yet, so it is only surfaced, never interpreted.
     */
    fun showPresentationOption(dim: Any): Int? = runCatching {
        lookup(dim, "getShowPresentationOption", emptyArray())?.invoke(dim) as? Int
    }.getOrNull()

    // ---------------------------------------------------------------- IPhoneCallInteraction

    /** One call record in ECARX vocabulary. `-1`/empty means "no value", matching the stock apps. */
    data class CallInfo(
        val callId: Int = 0,
        val activeCallId: Int = 0,
        val callCount: Int = 0,
        val callStatus: Int = 0,
        val contactName: String = "",
        val contactNumber: String = "",
    )

    /**
     * Reports the current CarPlay call to the cluster's phone interaction. The reference app on this
     * head unit builds the same `ICallInfo` and calls `updateCallInfo` whenever a call's state
     * changes, so the instrument cluster can show the number / name / status.
     */
    fun updateCallInfo(phone: Any, info: CallInfo): EcarxResult {
        val itf = runCatching { Class.forName(EcarxCapabilities.CALL_INFO) }.getOrNull()
            ?: return EcarxResult.failed("ICallInfo class missing")
        val call = runCatching {
            Proxy.newProxyInstance(itf.classLoader, arrayOf(itf), CallInfoHandler(info))
        }.getOrElse { return EcarxResult.failed("ICallInfo proxy failed: ${describe(it)}") }
        return call(phone, "updateCallInfo", arrayOf(itf), arrayOf<Any?>(call), "updateCallInfo")
    }

    /**
     * Reports the connected mobile device state. The stock app calls
     * `updateConnectedMobileDeviceState(String, int, int)`; the first int is the device state and
     * the second a feature bitmap, both passed as zero by the shell for the plain connected case.
     */
    fun updateConnectedMobileDeviceState(phone: Any, name: String, state: Int = 0, feature: Int = 0): EcarxResult =
        call(
            phone,
            "updateConnectedMobileDeviceState",
            arrayOf(String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrayOf<Any?>(name, state, feature),
            "updateConnectedMobileDeviceState(\"$name\",$state,$feature)",
        )

    // ---------------------------------------------------------------- IMediaInteraction

    /**
     * One track as the cluster's media card sees it.
     *
     * There is deliberately no artwork field. The cluster service resolves cover art by handing the
     * `Uri` to `com.nostra13.universalimageloader`, which fetches `http`/`file`/`content` URLs and
     * nothing else — not a bitmap, and not the `bitmap://` scheme a naive implementation reaches
     * for. A track that exists only inside CarPlay's audio stream has no such URL, so the honest
     * answer is the `null` the artwork getters already return; a field that could never hold
     * anything else is worse than no field.
     */
    data class PlaybackInfo(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationMillis: Long = 0L,
        val playing: Boolean = false,
    )

    /**
     * The source type every stock call site passes — `updateCurrentSourceType(6)` and
     * `updatePlaylist(6, …)`. Its meaning was never recovered: `IMediaInteraction` ships no
     * constants and the toolkit never uses another value. Reproduced, not decoded.
     */
    const val MEDIA_SOURCE_TYPE = 6

    /**
     * Calls the cluster made into the payload object, by getter name.
     *
     * Written from [MediaItemHandler], which runs on whatever thread the platform calls it from, so
     * it is a concurrent map rather than a plain one; read only when building a report line.
     */
    private val MEDIA_READS = ConcurrentHashMap<String, Int>()

    /** `getPlaybackStatus()`; the only two values `MediaHandler.formatPlaybackStatus` understands. */
    private const val PLAYBACK_PLAYING = 1
    private const val PLAYBACK_PAUSED = 2

    /**
     * The payload interface the platform declared for `updatePlaybackInfo`, read off the method
     * signature so it cannot drift from a class name we typed.
     */
    fun playbackInfoInterface(media: Any): Class<*>? =
        parameterType(media, "updatePlaybackInfo")
            ?: runCatching { Class.forName(EcarxCapabilities.MEDIA_PLAYBACK_INFO) }.getOrNull()

    /**
     * `updatePlaylist`'s element interface. Unlike the track, its type is erased behind
     * `java.util.List`, so there is no signature to read and the class name is the only handle.
     */
    fun mediaItemInterface(): Class<*>? =
        runCatching { Class.forName(EcarxCapabilities.MEDIA_ITEM) }.getOrNull()

    private fun parameterType(target: Any, name: String): Class<*>? = runCatching {
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 }
            ?.parameterTypes?.get(0)
    }.getOrNull()

    /** Tells the cluster which source is playing. The toolkit sends it once, before the first track. */
    fun updateCurrentSourceType(media: Any, sourceType: Int): EcarxResult =
        callAny(
            media, "updateCurrentSourceType", arrayOf<Any?>(sourceType),
            "updateCurrentSourceType($sourceType)",
        )

    /** `updatePlaybackInfo(IMediaInteraction$IPlaybackInfo)` — the track on the card. */
    fun updatePlaybackInfo(media: Any, itf: Class<*>, info: PlaybackInfo): EcarxResult {
        val proxy = mediaProxy(itf, info) ?: return EcarxResult.failed("IPlaybackInfo proxy failed")
        return callAny(media, "updatePlaybackInfo", arrayOf<Any?>(proxy), "updatePlaybackInfo")
    }

    /**
     * `updatePlaylist(int, List)`. Its element is a *different* interface from the track —
     * `IMediaInteraction$IMedia`, whose getter names are a subset of `IPlaybackInfo`'s — so
     * [MediaItemHandler] answers both and the same payload serves here. One entry, as the toolkit
     * sends: CarPlay exposes the current item, not a queue.
     */
    fun updatePlaylist(media: Any, itf: Class<*>, sourceType: Int, info: PlaybackInfo): EcarxResult {
        val proxy = mediaProxy(itf, info) ?: return EcarxResult.failed("IMedia proxy failed")
        val list = ArrayList<Any>(1).apply { add(proxy) }
        return callAny(media, "updatePlaylist", arrayOf<Any?>(sourceType, list), "updatePlaylist($sourceType,1)")
    }

    /**
     * `updateCurrentProgress(long)`.
     *
     * The unit belongs to the caller: `MediaHandler.updateCurrentProgress` publishes the number it
     * was handed straight into `ihu/info/media/progress` under the key `"value"`, with no
     * arithmetic at all, and skips the message when it equals the last one. Milliseconds, as the
     * toolkit's `m.smali` sends.
     */
    fun updateCurrentProgress(media: Any, millis: Long): EcarxResult =
        callAny(media, "updateCurrentProgress", arrayOf<Any?>(millis), "updateCurrentProgress(${millis}ms)")

    /**
     * `updateMediaSourceTypeList(int[])` — declares which media sources this app can serve.
     *
     * The card is drawn from `getSelectedMediaSourceCache(sourceType)`, i.e. from the sources the
     * media service has been **told about**. `updateCurrentSourceType(6)` selects a source; it does
     * not introduce one. Neither the shipped toolkit nor any build of this bridge before this one
     * ever called this method, which fits the symptom exactly: every `updatePlaybackInfo` is
     * accepted, and the card still says 无媒体 because source 6 has never existed in its cache.
     */
    fun updateMediaSourceTypeList(media: Any, sourceTypes: IntArray): EcarxResult =
        callAny(
            media, "updateMediaSourceTypeList", arrayOf<Any?>(sourceTypes),
            "updateMediaSourceTypeList(${sourceTypes.joinToString(",")})",
        )

    /** `updateMediaMuteState(int)` — `0` is unmuted. The card dims itself on `1`. */
    fun updateMediaMuteState(media: Any, muted: Int): EcarxResult =
        callAny(media, "updateMediaMuteState", arrayOf<Any?>(muted), "updateMediaMuteState($muted)")

    /**
     * Which payload getters the cluster has actually called, and how often.
     *
     * This is the one measurement that separates "the cluster never read the track" from "it read
     * the track and drew nothing", and it needs no guesswork: `updatePlaybackInfo` hands the
     * platform an object that calls straight back into [MediaItemHandler]. An empty summary after a
     * whole session of accepted calls means the channel is not being consumed at all, whatever the
     * binder said.
     */
    fun mediaReads(): String {
        val snapshot = MEDIA_READS.entries.sortedByDescending { it.value }
        if (snapshot.isEmpty()) return "media reads=none"
        return "media reads=" + snapshot.take(10).joinToString(",") { "${it.key}x${it.value}" }
    }

    /** Clears the getter counters; called when a session starts so the line describes one drive. */
    fun resetMediaReads() = MEDIA_READS.clear()

    private fun mediaProxy(itf: Class<*>, info: PlaybackInfo): Any? = runCatching {
        Proxy.newProxyInstance(itf.classLoader, arrayOf(itf), MediaItemHandler(info))
    }.getOrElse {
        report("media payload proxy failed: ${describe(it)}")
        null
    }

    // ---------------------------------------------------------------- DIMProtocolManager

    fun dimProtocolManager(context: Context): Any? = runCatching {
        val cls = Class.forName(EcarxCapabilities.DIM_PROTOCOL_MANAGER)
        val getInstance = cls.getMethod("getInstance", Context::class.java)
        if (Modifier.isStatic(getInstance.modifiers)) {
            getInstance.invoke(null, context)
        } else {
            val ctor = cls.getDeclaredConstructor().also { it.isAccessible = true }
            getInstance.invoke(ctor.newInstance(), context)
        }
    }.getOrElse {
        report("DIMProtocolManager.getInstance failed: ${describe(it)}")
        null
    }

    /** `setNaviMode` — the switch that makes the cluster accept an external navigation source. */
    fun setNaviMode(manager: Any, mode: Int): EcarxResult = callInt(manager, "setNaviMode", mode)

    // ---------------------------------------------------------------- plumbing

    private fun call0(target: Any, name: String): EcarxResult =
        call(target, name, emptyArray(), emptyArray(), name)

    private fun callInt(target: Any, name: String, value: Int): EcarxResult =
        call(target, name, arrayOf(Int::class.javaPrimitiveType!!), arrayOf<Any?>(value), "$name($value)")

    private fun call(target: Any, name: String, params: Array<Class<*>>, args: Array<Any?>, label: String): EcarxResult {
        val method = lookup(target, name, params) ?: return EcarxResult.failed("$label: method not found")
        return runCatching { method.invoke(target, *args) }
            .fold({ EcarxResult.ok(label) }, { EcarxResult.failed("$label threw ${describe(it)}") })
    }

    /**
     * The answer for a getter on a payload proxy we do not implement: the declared return type's
     * zero. Shared by every handler in this file, because the platform does not only ask for the
     * fields we chose to fill in.
     */
    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    /**
     * [value] as the return type the interface actually declared.
     *
     * The navigation payloads are filled against the descriptors probe v2 dumped off the car, so
     * their handler can return a bare `Long` or `Int`. There is no such dump for the media payloads
     * — their getters come from the shipped toolkit's bytecode, where `getDuration` is `long` and
     * every status field is `int` — and a payload proxy throws `ClassCastException` when the answer
     * does not match the declaration. On these `void` binder calls that would surface as a silent
     * refusal, so the declaration is read instead of assumed.
     */
    private fun coerceNumber(type: Class<*>, value: Long): Any = when (type) {
        java.lang.Long.TYPE -> value
        java.lang.Integer.TYPE -> value.toInt()
        java.lang.Short.TYPE -> value.toShort()
        java.lang.Byte.TYPE -> value.toByte()
        java.lang.Float.TYPE -> value.toFloat()
        java.lang.Double.TYPE -> value.toDouble()
        else -> value
    }

    /**
     * Invokes one `IMediaInteraction` method by **name and arity**, not by descriptor, adjusting the
     * numeric argument to whatever the head unit declared. See [coerceNumber] for why the media half
     * cannot use exact descriptors the way the navigation half does.
     */
    private fun callAny(target: Any, name: String, args: Array<Any?>, label: String): EcarxResult {
        val method = runCatching {
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
        }.getOrNull() ?: return EcarxResult.failed("$label: method not found")
        val coerced = Array(args.size) { index -> coerceArgument(method.parameterTypes[index], args[index]) }
        return runCatching { method.invoke(target, *coerced) }
            .fold({ EcarxResult.ok(label) }, { EcarxResult.failed("$label threw ${describe(it)}") })
    }

    /** [value] as the declared parameter type. Only the two integer widths are ever mismatched. */
    private fun coerceArgument(type: Class<*>, value: Any?): Any? = when {
        value is Long && type == java.lang.Integer.TYPE -> value.toInt()
        value is Int && type == java.lang.Long.TYPE -> value.toLong()
        else -> value
    }

    private fun read(target: Any, name: String): Any? {
        val method = lookup(target, name, emptyArray()) ?: return null
        return runCatching { method.invoke(target) }.getOrElse {
            // getNaviInteraction is how the whole bridge gets its handle, so a refusal here has to
            // reach the report, not just logcat.
            report("$name failed: ${describe(it)}")
            null
        }
    }

    private fun lookup(target: Any, name: String, params: Array<Class<*>>): Method? =
        runCatching { target.javaClass.getMethod(name, *params) }
            .recoverCatching {
                target.javaClass.getDeclaredMethod(name, *params).also { it.isAccessible = true }
            }
            .getOrNull()

    /** Unwraps the reflection wrapper so the log line names the real failure. */
    private fun describe(error: Throwable): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    /** Answers the getter set the platform reads off [EcarxCapabilities.NAVI_INFO]. */
    private class NaviInfoHandler(private val g: Guidance) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getNavigationTurnId" -> g.turnId
            "getDistanceToNextGuidancePoint" -> g.distanceToNextMeters.coerceAtLeast(0L)
            "getDistanceToDestination" -> g.distanceToDestinationMeters.coerceAtLeast(0L)
            "getETA" -> g.etaEpochSeconds.coerceAtLeast(0L)
            "getDrivingDirection" -> g.drivingDirection
            "getNavigationStatus" -> g.status
            "getMuteState" -> g.muteState
            "getDayNightMode" -> g.dayNightMode
            "getNextGuidancePointName" -> g.nextGuidancePointName
            "getLaneInfo" -> null
            "getExtensionInfo" -> null
            "getHighwayExitInfo" -> null
            "getRoadCameraInfo" -> null
            "getServiceAreaInfo" -> null
            // The head unit's own navigation app returns an empty string here, never markup — it
            // pushes its drawing through updateTurnByTurnArrow(String) instead. Answering the
            // declared String type with `null` would hand the cluster a NPE waiting to happen.
            "getNavigationTurnSVG" -> ""
            // toString/hashCode/equals must stay sane so the platform can log the object.
            "toString" ->
                "EcarxNavigationInfo(turn=${g.turnId} next=${g.distanceToNextMeters} dest=${g.distanceToDestinationMeters})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> EcarxDimApi.defaultValue(method.returnType)
        }
    }

    /** Answers the getter set the platform reads off [EcarxCapabilities.CALL_INFO]. */
    private class CallInfoHandler(private val c: CallInfo) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getCallId" -> c.callId
            "getActiveCallId" -> c.activeCallId
            "getCallCount" -> c.callCount
            "getCallStatus" -> c.callStatus
            "getContactName" -> c.contactName
            "getContactNumber" -> c.contactNumber
            "getAvatar" -> null
            "toString" ->
                "EcarxCallInfo(id=${c.callId} status=${c.callStatus} name=${c.contactName} number=${c.contactNumber})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> EcarxDimApi.defaultValue(method.returnType)
        }
    }

    /**
     * Answers both media payload interfaces: `IPlaybackInfo` (the track on the card, 20 getters)
     * and `IMedia` (one queue entry, 14 of the same names). One handler because both are answered
     * from one [PlaybackInfo] and the names line up — the only difference is which getters exist.
     *
     * The toolkit fills the getters it does not care about with plausible-looking junk
     * (`"radioFrequency"`, `"lyricContent"`, a `getPlaybackStatus()` pinned at `1`), and whatever
     * the cluster republishes from that is read as fact. Ours answers the empty string instead, and
     * only reports what it actually has: title, artist, album, duration — plus the play/pause
     * state, which the toolkit never varies from "playing".
     */
    private class MediaItemHandler(private val info: PlaybackInfo) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            // The three Object methods are the JVM's, not the platform's: counting them would drown
            // the reading in `toString` calls from binder tracing.
            when (method.name) {
                "toString" -> return "EcarxMediaItem(\"${info.title}\" playing=${info.playing})"
                "hashCode" -> return System.identityHashCode(proxy)
                "equals" -> return proxy === args?.firstOrNull()
            }
            MEDIA_READS.merge(method.name, 1) { seen, add -> seen + add }
            return when (method.name) {
                "getTitle" -> info.title
                "getArtist" -> info.artist
                "getAlbum" -> info.album
                "getDuration" -> EcarxDimApi.coerceNumber(method.returnType, info.durationMillis)
                "getPlaybackStatus" -> EcarxDimApi.coerceNumber(
                    method.returnType,
                    (if (info.playing) EcarxDimApi.PLAYBACK_PLAYING else EcarxDimApi.PLAYBACK_PAUSED).toLong(),
                )
                "getSourceType" -> EcarxDimApi.coerceNumber(method.returnType, EcarxDimApi.MEDIA_SOURCE_TYPE.toLong())
                // No cover, and no local file to point at: see [PlaybackInfo]. All five are `Uri`.
                "getArtwork" -> null
                "getNextArtwork" -> null
                "getPreviousArtwork" -> null
                "getLyric" -> null
                "getMediaPath" -> null
                // Declared as `String`, so answer with one: a cluster that reads them gets an empty value
                // rather than the `null` its own code would trip over.
                "getCurrentLyricSentence" -> ""
                "getLyricContent" -> ""
                "getRadioFrequency" -> ""
                "getRadioStationName" -> ""
                "getUUID" -> ""
                "getFavoriteState" -> EcarxDimApi.coerceNumber(method.returnType, 0L)
                "getLoopMode" -> EcarxDimApi.coerceNumber(method.returnType, 0L)
                "getPlayingItemPositionInQueue" -> EcarxDimApi.coerceNumber(method.returnType, 0L)
                "getRadioMode" -> EcarxDimApi.coerceNumber(method.returnType, 0L)
                else -> EcarxDimApi.defaultValue(method.returnType)
            }
        }
    }

    /**
     * Receiver for the DIM's own calls (see [registerInteractionCallback]).
     *
     * It answers nothing useful on purpose: every call is logged with its name and arguments and
     * the return value is the declared return type's default. That turns the first drive into an
     * inventory of what this head unit asks for, which is the only way to learn the set — the
     * interface is not in any ECARX SDK we have.
     */
    private class DimCallbackHandler(private val label: String, private val scope: String) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "toString" -> return "EcarxDimCallback($scope/$label)"
                "hashCode" -> return System.identityHashCode(proxy)
                "equals" -> return proxy === args?.firstOrNull()
            }
            EcarxDimApi.report("callback $scope.$label ${method.name}(${java.util.Arrays.toString(args)})")
            return EcarxDimApi.defaultValue(method.returnType)
        }
    }
}
