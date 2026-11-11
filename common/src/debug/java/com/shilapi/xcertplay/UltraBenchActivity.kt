package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.shilapi.xcertplay.airplay.SimulatedVehicle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Debug source set only: the OEM-like display is a simulator, never an actual vehicle HUD. */
class UltraBenchActivity : Activity() {
    private lateinit var vehicle: SimulatedVehicle
    private lateinit var cluster: Cluster
    private var video: ClusterVideoTexture? = null
    private var page = 0
    private val mirrorKey = "ultra-bench"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicle = AirPlayPersistence.loadSimulatedVehicle(this) ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        cluster = Cluster()
        val picture = ClusterVideoTexture(this) { surface -> MapMirrors.set(mirrorKey, surface) }
        video = picture
        picture.setVideoSize(1920, 720)
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf("导航", "媒体", "车辆", "电话").forEachIndexed { index, title ->
                addView(Button(context).apply {
                    text = title; contentDescription = "仪表$title"; textSize = 12f
                    setPadding(0, 0, 0, 0)
                    setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
                    setOnClickListener { page = index; cluster.invalidate() }
                }, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        val controls = Button(this).apply {
            text = "模拟数据"; textSize = 11f; setTextColor(0xffa9d9fa.toInt())
            setBackgroundColor(Color.TRANSPARENT); setOnClickListener { controls() }
        }
        val annotate = Button(this).apply {
            text = "标注"; textSize = 11f; setTextColor(0xffffc45a.toInt())
            setBackgroundColor(Color.TRANSPARENT); setOnClickListener { cluster.annotate = !cluster.annotate }
        }
        val content = object : FrameLayout(this) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val k = minOf(width / 1920f, height / 720f)
                val x = ((width - 1920 * k) / 2).toInt(); val y = ((height - 720 * k) / 2).toInt()
                val w = (1920 * k).toInt(); val h = (720 * k).toInt()
                picture.layout(x, y, x + w, y + h); cluster.layout(x, y, x + w, y + h)
                for (n in 0 until tabs.childCount) (tabs.getChildAt(n) as Button).setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26 * k)
                controls.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24 * k)
                annotate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24 * k)
                // Tabs fill the right menu (G); the bench buttons sit inside the outline below A's right end.
                tabs.measure(MeasureSpec.makeMeasureSpec((72 * k).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((247 * k).toInt(), MeasureSpec.EXACTLY))
                controls.measure(MeasureSpec.makeMeasureSpec((160 * k).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((56 * k).toInt(), MeasureSpec.EXACTLY))
                annotate.measure(MeasureSpec.makeMeasureSpec((110 * k).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((56 * k).toInt(), MeasureSpec.EXACTLY))
                tabs.layout(x + (1739 * k).toInt(), y + (331 * k).toInt(), x + (1811 * k).toInt(), y + (578 * k).toInt())
                controls.layout(x + (1440 * k).toInt(), y + (120 * k).toInt(), x + (1600 * k).toInt(), y + (176 * k).toInt())
                annotate.layout(x + (1610 * k).toInt(), y + (120 * k).toInt(), x + (1720 * k).toInt(), y + (176 * k).toInt())
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            addView(picture); addView(cluster); addView(tabs); addView(controls); addView(annotate)
        }
        setContentView(content)
        registerReceiver(remote, IntentFilter(ACTION_SET), Context.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(remote) }
        video?.close(); MapMirrors.set(mirrorKey, null); super.onDestroy()
    }

    /**
     * Bench remote: `am broadcast -a <ACTION_SET> -p <package> --es set "leftTemperature=24.5,speed=60"` or
     * `--es scene CITY`. Lets a script move the simulated values while the iPhone shows them.
     */
    private val remote = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("scene")?.let { name ->
                SimulatedVehicle.Scene.entries.firstOrNull { it.name == name }?.let { vehicle.select(it) }
            }
            val pairs = intent.getStringExtra("set")?.split(',')?.mapNotNull { pair ->
                pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }.orEmpty()
            for ((key, value) in pairs) {
                val applied = when (key) {
                    "speed" -> value.toDoubleOrNull()?.let { vehicle.speed(it) } != null
                    else -> { var ok = false; vehicle.change { s -> remoteChange(s, key, value)?.also { ok = true } ?: s }; ok }
                }
                Log.i(TAG, "Bench remote $key=$value applied=$applied")
            }
        }
    }

    private fun remoteChange(s: SimulatedVehicle.Reading, key: String, value: String): SimulatedVehicle.Reading? = when (key) {
        "leftTemperature" -> value.toDoubleOrNull()?.let { s.copy(leftTemperature = it) }
        "rightTemperature" -> value.toDoubleOrNull()?.let { s.copy(rightTemperature = it) }
        "fan" -> value.toIntOrNull()?.let { s.copy(fan = it) }
        "ac" -> value.toBooleanStrictOrNull()?.let { s.copy(ac = it) }
        "seatHeating" -> value.toIntOrNull()?.let { s.copy(seatHeating = it) }
        "seatVentilation" -> value.toIntOrNull()?.let { s.copy(seatVentilation = it) }
        "radioKhz" -> value.toIntOrNull()?.let { s.copy(radioKhz = it) }
        "fuel" -> value.toIntOrNull()?.let { s.copy(fuel = it) }
        "range" -> value.toIntOrNull()?.let { s.copy(range = it) }
        "volume" -> value.toIntOrNull()?.let { s.copy(volume = it) }
        else -> null
    }

    /** Logs every change of the simulated values, whoever made it: the bench remote or a CarPlay write. */
    private var lastReading: SimulatedVehicle.Reading? = null
    private fun watch(s: SimulatedVehicle.Reading) {
        val before = lastReading; lastReading = s
        if (before == null || before == s) return
        val changed = SimulatedVehicle.Reading::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .mapNotNull { f -> f.isAccessible = true; val a = f.get(before); val b = f.get(s)
                if (a != b && f.name !in setOf("speed", "rpm", "trip", "odometer")) "${f.name}=$a->$b" else null }
        if (changed.isNotEmpty()) Log.i(TAG, "Bench vehicle changed ${changed.joinToString(" ")}")
    }

    private companion object {
        const val TAG = "DiPlay-Bench"
        const val ACTION_SET = "com.shilapi.xcertplay.BENCH_SET"
    }

    private fun controls() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 12, 28, 12) }
        val scenes = LinearLayout(this)
        listOf("驻车", "城市", "高速", "警告").forEachIndexed { n, name ->
            scenes.addView(Button(this).apply { text = name; setOnClickListener { vehicle.select(SimulatedVehicle.Scene.entries[n]) } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(scenes)
        val speed = TextView(this).apply { text = "车速：${vehicle.reading().speed.toInt()} km/h（拖动后保持）" }
        body.addView(speed)
        body.addView(SeekBar(this).apply {
            contentDescription = "模拟车速"; max = 240; progress = vehicle.reading().speed.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) { vehicle.speed(value.toDouble()); speed.text = "车速：$value km/h" }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
        val switches = LinearLayout(this)
        listOf("左转", "右转", "远光", "雨刷").forEachIndexed { n, name ->
            switches.addView(Button(this).apply { text = name; setOnClickListener {
                vehicle.change { when (n) { 0 -> it.copy(leftTurn = !it.leftTurn); 1 -> it.copy(rightTurn = !it.rightTurn)
                    2 -> it.copy(highBeam = !it.highBeam); else -> it.copy(wipers = !it.wipers) } }
            } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(switches)
        val climate = LinearLayout(this)
        listOf("温度 −", "温度 +", "风量", "A/C").forEachIndexed { n, name ->
            climate.addView(Button(this).apply { text = name; setOnClickListener {
                vehicle.change { when (n) {
                    0 -> it.copy(leftTemperature = (it.leftTemperature - .5).coerceAtLeast(16.0))
                    1 -> it.copy(leftTemperature = (it.leftTemperature + .5).coerceAtMost(28.0))
                    2 -> it.copy(fan = (it.fan + 1) % 8)
                    else -> it.copy(ac = !it.ac)
                } }
            } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(climate)
        val media = LinearLayout(this)
        listOf("FM −", "FM +", "播放/暂停", "模拟通话").forEachIndexed { n, name ->
            media.addView(Button(this).apply { text = name; setOnClickListener {
                vehicle.change { when (n) {
                    0 -> it.copy(radioKhz = (it.radioKhz - 100).coerceAtLeast(87500))
                    1 -> it.copy(radioKhz = (it.radioKhz + 100).coerceAtMost(108000))
                    2 -> it.copy(radioPlaying = !it.radioPlaying)
                    else -> it.copy(simulatedCall = !it.simulatedCall)
                } }
            } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(media)
        val volume = LinearLayout(this)
        listOf("音量 −", "音量 +", "静音").forEachIndexed { n, name ->
            volume.addView(Button(this).apply { text = name; setOnClickListener {
                vehicle.change { when (n) {
                    0 -> it.copy(volume = (it.volume - 5).coerceAtLeast(0))
                    1 -> it.copy(volume = (it.volume + 5).coerceAtMost(100))
                    else -> it.copy(muted = !it.muted)
                } }
            } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(volume)
        AlertDialog.Builder(this).setTitle("模拟数据 · 不控制真实车辆")
            .setView(ScrollView(this).apply { addView(body) }).setPositiveButton("完成", null).show()
    }

    /**
     * Lynk 05 cluster geometry from the user's photo-based layout pack (instrument_cluster_essentials_v3, 1920x720):
     * the shaped visible outline, opaque top (A) and bottom (H) backgrounds, and the B–G overlays drawn over the
     * full-canvas iPhone picture. Envelopes are estimates (about 10–15 px), not OEM artwork or a safe area.
     */
    private object CarLayout {
        const val OUTLINE = "M 17 280 C 14 236 22 214 48 194 C 90 156 225 106 402 42 C 473 14 545 7 620 6 " +
            "C 820 0 1170 0 1325 9 C 1415 15 1465 33 1520 53 C 1675 105 1820 151 1868 195 C 1895 219 1906 246 1905 292 " +
            "C 1903 376 1895 505 1876 568 C 1870 594 1854 609 1825 632 C 1767 677 1722 702 1670 707 " +
            "C 1460 716 475 716 275 707 C 221 705 186 693 143 667 C 100 641 63 609 52 583 C 30 531 18 375 17 280 Z"
        const val TOP = "M 402 42 C 473 14 545 7 620 6 C 820 0 1170 0 1325 9 C 1415 15 1465 33 1520 53 " +
            "C 1458 90 1411 108 1356 108 L 554 104 C 500 103 468 82 402 42 Z"
        const val CRUISE = "M 243 338 Q 243 333 248 333 L 291 333 L 291 329 Q 291 324 296 324 L 365 324 Q 371 324 371 330 " +
            "L 371 333 L 416 333 Q 422 333 422 338 L 422 395 Q 422 400 417 400 L 410 400 L 410 429 L 250 429 L 250 400 " +
            "L 248 400 Q 243 400 243 395 Z"
        const val MENU = "M 1735 247 L 1817 247 Q 1822 247 1822 252 L 1822 326 Q 1822 331 1817 331 L 1811 331 L 1811 571 " +
            "Q 1811 578 1804 578 L 1746 578 Q 1739 578 1739 571 L 1739 331 L 1735 331 Q 1730 331 1730 326 L 1730 252 " +
            "Q 1730 247 1735 247 Z"
        val RPM = RectF(675f, 104f, 1340f, 159f)
        val NAV = RectF(735f, 164f, 1180f, 273f)
        val SPEED = RectF(299f, 181f, 365f, 298f)
        val LANE = RectF(169f, 443f, 494f, 580f)
        val MENU_BOX = RectF(1730f, 247f, 1822f, 578f)
        val BOTTOM = RectF(0f, 620f, 1920f, 720f)
        /** Labelled envelopes for the annotation switch. */
        val ENVELOPES = listOf("B" to RPM, "C" to NAV, "D" to SPEED, "E" to RectF(243f, 324f, 422f, 429f), "F" to LANE,
            "G" to MENU_BOX)
    }

    private inner class Cluster : View(this@UltraBenchActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clock = SimpleDateFormat("HH:mm", Locale.ROOT)
        private val outline = androidx.core.graphics.PathParser.createPathFromPathData(CarLayout.OUTLINE)
        private val top = androidx.core.graphics.PathParser.createPathFromPathData(CarLayout.TOP)
        private val cruise = androidx.core.graphics.PathParser.createPathFromPathData(CarLayout.CRUISE)
        private val menu = androidx.core.graphics.PathParser.createPathFromPathData(CarLayout.MENU)
        private val dashed = DashPathEffect(floatArrayOf(14f, 9f), 0f)
        private val white = 0xfff4f6ed.toInt()
        private val green = 0xffb6f35a.toInt()
        private val blue = 0xff74d5fa.toInt()
        private val amber = 0xffffc45a.toInt()
        private val red = 0xffff6452.toInt()
        private val opaque = 0xff0b1116.toInt()
        private val translucent = 0x8c2a3236.toInt()
        var annotate = false
        private var spokenAt = 0L
        private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int = white,
            align: Paint.Align = Paint.Align.LEFT) {
            p.shader = null; p.pathEffect = null; p.style = Paint.Style.FILL; p.color = color; p.textSize = size
            p.textAlign = align
            p.typeface = Typeface.create("sans-serif-condensed", if (size >= 50) Typeface.BOLD else Typeface.NORMAL)
            c.drawText(value, x, y, p); p.textAlign = Paint.Align.LEFT
        }
        private fun line(c: Canvas, x: Float, y: Float, x2: Float, y2: Float, color: Int = white, width: Float = 3f) {
            p.shader = null; p.pathEffect = null; p.style = Paint.Style.STROKE; p.strokeWidth = width; p.color = color
            c.drawLine(x, y, x2, y2, p); p.style = Paint.Style.FILL
        }
        private fun box(c: Canvas, r: RectF, color: Int, radius: Float) {
            p.shader = null; p.pathEffect = null; p.style = Paint.Style.FILL; p.color = color
            c.drawRoundRect(r, radius, radius, p)
        }
        private fun fill(c: Canvas, path: Path, color: Int) {
            p.shader = null; p.pathEffect = null; p.style = Paint.Style.FILL; p.color = color; c.drawPath(path, p)
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val s = vehicle.reading(); val now = System.currentTimeMillis(); val blink = now % 1000 < 500
            watch(s)
            c.save(); c.scale(width / 1920f, height / 720f)
            // The panel's shaped edge hides everything outside the outline.
            c.save(); c.clipOutPath(outline); c.drawColor(Color.BLACK); c.restore()
            c.save(); c.clipPath(outline)
            if (!MapMirrors.streamActive || page != 0) fill(c, outline, 0xff07131e.toInt())
            // A: opaque top status background.
            fill(c, top, opaque)
            text(c, clock.format(Date(now)), 624f, 82f, 38f, align = Paint.Align.CENTER)
            text(c, if (s.headlights) "≋◖" else "◖", 865f, 78f, 32f, if (s.highBeam) blue else green, Paint.Align.CENTER)
            text(c, s.gear, 967f, 84f, 56f, align = Paint.Align.CENTER)
            if (s.parkingBrake) text(c, "(P)", 1058f, 80f, 32f, red, Paint.Align.CENTER)
            text(c, "${s.ambient}°C", 1314f, 82f, 32f, align = Paint.Align.CENTER)
            if (s.leftTurn && blink) text(c, "◀", 500f, 80f, 44f, green, Paint.Align.CENTER)
            if (s.rightTurn && blink) text(c, "▶", 1440f, 80f, 44f, green, Paint.Align.CENTER)
            // B: rpm ticks and glyphs, transparent between them.
            val r = CarLayout.RPM
            for (n in 0..7) {
                val x = r.left + n * 74f
                box(c, RectF(x, r.top + 6, x + 70, r.top + 12), if (n >= 6) red else 0xff758b94.toInt(), 0f)
                if (s.rpm > n * 1000) box(c, RectF(x, r.top + 6, x + 70 * ((s.rpm - n * 1000) / 1000f).coerceIn(0f, 1f), r.top + 12),
                    if (n >= 6) red else blue, 0f)
                text(c, "$n", x, r.top + 40, 22f, align = Paint.Align.CENTER)
            }
            text(c, "8", r.left + 8 * 74f, r.top + 40, 22f, align = Paint.Align.CENTER)
            text(c, "×1000", r.right - 44, r.top + 24, 16f); text(c, "r/min", r.right - 44, r.top + 44, 16f)
            // D: speed digits only, no backing plate.
            text(c, "${s.speed.toInt()}", CarLayout.SPEED.centerX(), CarLayout.SPEED.top + 82, 92f, align = Paint.Align.CENTER)
            text(c, "km/h", CarLayout.SPEED.centerX(), CarLayout.SPEED.bottom - 6, 22f, align = Paint.Align.CENTER)
            // E: cruise cards and caption.
            fill(c, cruise, translucent)
            text(c, if (s.cruise) "${s.cruiseSpeed}" else "--", 332f, 380f, 40f, if (s.cruise) green else white, Paint.Align.CENTER)
            text(c, "自适应巡航", 330f, 422f, 22f, if (s.cruise) green else white, Paint.Align.CENTER)
            // F: lane assist panel.
            val f = CarLayout.LANE
            box(c, f, translucent, 6f)
            line(c, f.centerX() - 70, f.bottom - 14, f.centerX() - 34, f.top + 14, if (s.laneAssist) blue else white)
            line(c, f.centerX() + 70, f.bottom - 14, f.centerX() + 34, f.top + 14, if (s.laneAssist) blue else white)
            box(c, RectF(f.centerX() - 18, f.bottom - 70, f.centerX() + 18, f.bottom - 14), white, 9f)
            for (n in 0 until s.following) line(c, f.centerX() - 16, f.bottom - 84 - n * 12, f.centerX() + 16, f.bottom - 84 - n * 12, green, 5f)
            // G: the tab buttons sit on this menu background.
            fill(c, menu, translucent)
            if (page == 0) {
                // C: navigation card.
                box(c, CarLayout.NAV, 0xa03d4440.toInt(), 5f)
                text(c, "进入翠屏西路", CarLayout.NAV.left + 24, CarLayout.NAV.top + 44, 27f)
                text(c, "310 m · 模拟转向", CarLayout.NAV.left + 24, CarLayout.NAV.top + 86, 26f)
                text(c, "↰", CarLayout.NAV.right - 60, CarLayout.NAV.top + 76, 54f)
                if (!MapMirrors.streamActive) text(c, "等待 iPhone 仪表视频", 960f, 400f, 36f, blue, Paint.Align.CENTER)
            } else {
                box(c, RectF(520f, 180f, 1700f, 600f), 0xd0182935.toInt(), 22f)
                when (page) {
                    1 -> {
                        text(c, "FM  ${String.format(Locale.ROOT, "%.1f", s.radioKhz / 1000.0)} MHz", 580f, 290f, 62f)
                        text(c, if (s.radioPlaying) "模拟电台 · 播放中" else "模拟电台 · 已暂停", 585f, 365f, 32f, blue)
                        text(c, "${if (s.muted) "静音" else "音量 ${s.volume}%"}   ·   不输出真实电台音频", 585f, 440f, 27f)
                    }
                    2 -> {
                        text(c, "车辆状态", 560f, 230f, 36f)
                        text(c, "胎压  ${s.pressures.joinToString(" / ")} kPa", 565f, 285f, 30f, if (s.pressures.any { it < 190 }) amber else white)
                        text(c, "水温 ${s.coolant}°C   油温 ${s.oil}°C   油量 ${s.fuel}%", 565f, 335f, 30f)
                        text(c, "空调  ${s.leftTemperature}° / ${s.rightTemperature}°   风量 ${s.fan}   A/C ${if (s.ac) "ON" else "OFF"}", 565f, 385f, 30f, blue)
                        text(c, "${if (s.doorOpen) "车门未关" else "车门已关"}   ${if (s.seatbelt) "安全带已系" else "请系安全带"}   座椅加热 ${s.seatHeating}/${s.passengerHeating}", 565f, 435f, 28f)
                        text(c, "${if (s.recirculation) "内循环" else "外循环"}   AUTO ${if (s.autoClimate) "ON" else "OFF"}   前除雾 ${if (s.defrost) "ON" else "OFF"}   后除雾 ${if (s.rearDefrost) "ON" else "OFF"}", 565f, 485f, 26f)
                        text(c, "总里程 ${s.odometer} km    小计 ${String.format(Locale.ROOT, "%.1f", s.trip)} km", 565f, 540f, 28f)
                    }
                    3 -> {
                        text(c, if (s.simulatedCall) "模拟通话中" else "电话已连接", 1110f, 365f, 54f, align = Paint.Align.CENTER)
                        text(c, "可在“模拟数据”中切换通话状态", 1110f, 440f, 28f, blue, Paint.Align.CENTER)
                    }
                }
            }
            // H: opaque bottom status bar, clipped to the outline above.
            box(c, CarLayout.BOTTOM, opaque, 0f)
            text(c, "⛽ ${s.range} km", 245f, 660f, 28f, if (s.fuel < 10) amber else white)
            line(c, 247f, 680f, 487f, 680f, 0xff56625d.toInt(), 8f)
            line(c, 247f, 680f, 247f + 240 * s.fuel / 100, 680f, if (s.fuel < 10) amber else white, 8f)
            if (s.engineWarning || !s.seatbelt || s.doorOpen || s.fuel < 10) {
                text(c, "⚠  ${listOfNotNull(if (s.doorOpen) "车门未关" else null, if (!s.seatbelt) "请系安全带" else null,
                    if (s.fuel < 10) "燃油不足" else null, if (s.engineWarning) "发动机检查" else null).joinToString("  ·  ")}",
                    880f, 672f, 26f, amber, Paint.Align.CENTER)
            } else {
                text(c, "模拟 · ${vehicle.scene().name} · 无车身信号", 880f, 672f, 22f, blue, Paint.Align.CENTER)
            }
            text(c, if (s.seatbelt) "安全带" else "未系", 1315f, 662f, 22f, if (s.seatbelt) white else red, Paint.Align.CENTER)
            text(c, s.driveMode, 1315f, 690f, 24f, align = Paint.Align.CENTER)
            text(c, "${s.coolant}°C", 1435f, 655f, 24f, if (s.coolant > 105) red else white)
            line(c, 1435f, 680f, 1675f, 680f, white, 4f)
            for (n in 0..4) line(c, 1435f + 60 * n, 672f, 1435f + 60 * n, 686f, if (n == 4) red else white, 4f)
            val mark = 1435 + 240 * ((s.coolant - 40) / 90f).coerceIn(0f, 1f)
            box(c, RectF(mark - 4, 670f, mark + 4, 690f), if (s.coolant > 105) red else blue, 1f)
            if (annotate) {
                p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.pathEffect = dashed; p.color = amber
                c.drawPath(top, p); c.drawRect(CarLayout.BOTTOM, p)
                for ((id, env) in CarLayout.ENVELOPES) { p.style = Paint.Style.STROKE; p.pathEffect = dashed; c.drawRect(env, p)
                    text(c, id, env.left + 6, env.top + 26, 24f, amber) }
            }
            c.restore()
            c.restore()
            if (now - spokenAt > 2000) {
                contentDescription = "模拟仪表，车速${s.speed.toInt()}公里每小时，挡位${s.gear}，转速${s.rpm}，续航${s.range}公里"
                spokenAt = now
            }
            if (isAttachedToWindow) postInvalidateDelayed(100)
        }
    }
}
