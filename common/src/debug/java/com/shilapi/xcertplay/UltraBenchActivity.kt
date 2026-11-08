package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.os.Bundle
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
                    setTextColor(Color.WHITE); setBackgroundColor(0xc03b4241.toInt())
                    setOnClickListener { page = index; cluster.invalidate() }
                }, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        val controls = Button(this).apply {
            text = "模拟数据"; textSize = 11f; setTextColor(0xffa9d9fa.toInt())
            setBackgroundColor(Color.TRANSPARENT); setOnClickListener { controls() }
        }
        val content = object : FrameLayout(this) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val k = minOf(width / 1920f, height / 720f)
                val x = ((width - 1920 * k) / 2).toInt(); val y = ((height - 720 * k) / 2).toInt()
                val w = (1920 * k).toInt(); val h = (720 * k).toInt()
                picture.layout(x, y, x + w, y + h); cluster.layout(x, y, x + w, y + h)
                for (n in 0 until tabs.childCount) (tabs.getChildAt(n) as Button).setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26 * k)
                controls.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24 * k)
                tabs.measure(MeasureSpec.makeMeasureSpec((85 * k).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((300 * k).toInt(), MeasureSpec.EXACTLY))
                controls.measure(MeasureSpec.makeMeasureSpec((250 * k).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((70 * k).toInt(), MeasureSpec.EXACTLY))
                tabs.layout(x + (1800 * k).toInt(), y + (280 * k).toInt(), x + (1885 * k).toInt(), y + (580 * k).toInt())
                controls.layout(x + (1550 * k).toInt(), y + (5 * k).toInt(), x + (1800 * k).toInt(), y + (75 * k).toInt())
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            addView(picture); addView(cluster); addView(tabs); addView(controls)
        }
        setContentView(content)
    }

    override fun onDestroy() { video?.close(); MapMirrors.set(mirrorKey, null); super.onDestroy() }

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

    private inner class Cluster : View(this@UltraBenchActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clock = SimpleDateFormat("HH:mm", Locale.ROOT)
        private val white = 0xfff4f6ed.toInt()
        private val green = 0xffb6f35a.toInt()
        private val blue = 0xff74d5fa.toInt()
        private val amber = 0xffffc45a.toInt()
        private val red = 0xffff6452.toInt()
        private var spokenAt = 0L
        private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int = white) {
            p.shader = null; p.style = Paint.Style.FILL; p.color = color; p.textSize = size
            p.typeface = Typeface.create("sans-serif-condensed", if (size >= 50) Typeface.BOLD else Typeface.NORMAL)
            c.drawText(value, x, y, p)
        }
        private fun line(c: Canvas, x: Float, y: Float, x2: Float, y2: Float, color: Int = white, width: Float = 3f) {
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = width; p.color = color
            c.drawLine(x, y, x2, y2, p); p.style = Paint.Style.FILL
        }
        private fun box(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float = 12f) {
            p.shader = null; p.style = Paint.Style.FILL; p.color = color; c.drawRoundRect(x, y, x + w, y + h, radius, radius, p)
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val s = vehicle.reading(); val now = System.currentTimeMillis(); val blink = now % 1000 < 500
            c.save(); c.scale(width / 1920f, height / 720f)
            if (!MapMirrors.streamActive || page != 0) box(c, 0f, 0f, 1920f, 720f, 0xff07131e.toInt(), 0f)
            p.shader = LinearGradient(0f, 0f, 0f, 150f, 0xff060b15.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 1920f, 150f, p); p.shader = null
            box(c, 420f, 0f, 1080f, 82f, 0xd0353935.toInt(), 35f)
            text(c, clock.format(Date(now)), 475f, 56f, 38f)
            text(c, if (s.headlights) "≋◖" else "◖", 700f, 52f, 36f, if (s.highBeam) blue else green)
            text(c, s.gear, 905f, 62f, 62f)
            text(c, if (s.wipers) "雨刷 ON" else "雨刷", 1050f, 51f, 27f)
            text(c, "${s.ambient}°C", 1310f, 54f, 36f)
            if (s.leftTurn && blink) text(c, "◀", 315f, 60f, 50f, green)
            if (s.rightTurn && blink) text(c, "▶", 1515f, 60f, 50f, green)
            for (n in 0..7) {
                val x = 600f + n * 90
                box(c, x, 91f, 86f, 8f, if (n >= 6) red else 0xff758b94.toInt(), 0f)
                if (s.rpm > n * 1000) box(c, x, 91f, 86 * ((s.rpm - n * 1000) / 1000f).coerceIn(0f, 1f), 8f, if (n >= 6) red else blue, 0f)
                text(c, "$n", x - 6, 124f, 24f)
            }
            text(c, "8", 1315f, 124f, 24f); text(c, "×1000", 1360f, 103f, 18f); text(c, "r/min", 1360f, 124f, 18f)
            box(c, 35f, 205f, 300f, 390f, 0x85222e31.toInt(), 16f)
            text(c, "${s.speed.toInt()}", 92f, 350f, 128f); text(c, "km/h", 125f, 389f, 25f)
            line(c, 120f, 541f, 159f, 431f, if (s.laneAssist) blue else white)
            line(c, 264f, 541f, 227f, 431f, if (s.laneAssist) blue else white)
            box(c, 174f, 470f, 38f, 62f, white, 9f); box(c, 180f, 479f, 26f, 20f, 0xff48606d.toInt(), 5f)
            for (n in 0 until s.following) line(c, 176f, 454f - n * 12, 211f, 454f - n * 12, green, 5f)
            text(c, if (s.cruise) "自适应巡航 ${s.cruiseSpeed}" else "自适应巡航", 66f, 575f, 26f, if (s.cruise) green else white)
            if (page == 0) {
                box(c, 740f, 145f, 460f, 94f, 0xc03d4440.toInt(), 12f)
                text(c, "进入翠屏西路", 765f, 183f, 27f)
                text(c, "↰", 1070f, 222f, 54f); text(c, "310 m · 模拟转向", 770f, 221f, 26f)
                if (!MapMirrors.streamActive) text(c, "等待 iPhone 仪表视频", 710f, 385f, 36f, blue)
            } else {
                box(c, 390f, 160f, 1340f, 420f, 0xd0182935.toInt(), 22f)
                when (page) {
                    1 -> {
                        text(c, "FM  ${String.format(Locale.ROOT, "%.1f", s.radioKhz / 1000.0)} MHz", 470f, 270f, 62f)
                        text(c, if (s.radioPlaying) "模拟电台 · 播放中" else "模拟电台 · 已暂停", 475f, 345f, 32f, blue)
                        text(c, "${if (s.muted) "静音" else "音量 ${s.volume}%"}   ·   不输出真实电台音频", 475f, 420f, 27f)
                    }
                    2 -> {
                        text(c, "车辆状态", 445f, 215f, 36f)
                        text(c, "胎压  ${s.pressures.joinToString(" / ")} kPa", 450f, 275f, 31f, if (s.pressures.any { it < 190 }) amber else white)
                        text(c, "水温 ${s.coolant}°C   油温 ${s.oil}°C   油量 ${s.fuel}%", 450f, 330f, 31f)
                        text(c, "空调  ${s.leftTemperature}° / ${s.rightTemperature}°   风量 ${s.fan}   A/C ${if (s.ac) "ON" else "OFF"}", 450f, 385f, 31f, blue)
                        text(c, "${if (s.doorOpen) "车门未关" else "车门已关"}   ${if (s.seatbelt) "安全带已系" else "请系安全带"}   座椅加热 ${s.seatHeating}/${s.passengerHeating}", 450f, 435f, 29f)
                        text(c, "${if (s.recirculation) "内循环" else "外循环"}   AUTO ${if (s.autoClimate) "ON" else "OFF"}   前除雾 ${if (s.defrost) "ON" else "OFF"}   后除雾 ${if (s.rearDefrost) "ON" else "OFF"}", 450f, 485f, 27f)
                        text(c, "总里程 ${s.odometer} km    小计 ${String.format(Locale.ROOT, "%.1f", s.trip)} km", 450f, 540f, 29f)
                    }
                    3 -> {
                        text(c, if (s.simulatedCall) "模拟通话中" else "电话已连接", 640f, 315f, 54f)
                        text(c, "可在“模拟数据”中切换通话状态", 610f, 390f, 28f, blue)
                    }
                }
            }
            p.shader = LinearGradient(0f, 600f, 0f, 720f, Color.TRANSPARENT, 0xff04080b.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 600f, 1920f, 720f, p); p.shader = null
            if (s.engineWarning || !s.seatbelt || s.doorOpen || s.fuel < 10) {
                box(c, 420f, 594f, 1290f, 48f, 0xe04e301b.toInt(), 8f)
                text(c, "⚠  ${listOfNotNull(if (s.doorOpen) "车门未关" else null, if (!s.seatbelt) "请系安全带" else null,
                    if (s.fuel < 10) "燃油不足" else null, if (s.engineWarning) "发动机检查" else null).joinToString("   ·   ")}", 450f, 629f, 28f, amber)
            }
            text(c, "⛽ ${s.range} km", 40f, 678f, 31f, if (s.fuel < 10) amber else white)
            line(c, 42f, 697f, 310f, 697f, 0xff56625d.toInt(), 9f)
            line(c, 42f, 697f, 42f + 268 * s.fuel / 100, 697f, if (s.fuel < 10) amber else white, 9f)
            if (s.autoHold) { text(c, "AUTO", 370f, 675f, 22f, green); text(c, "HOLD", 370f, 698f, 22f, green) }
            if (s.parkingBrake) text(c, "(P)", 490f, 690f, 34f, red)
            text(c, "模拟 · ${vehicle.scene().name} · 无车身信号", 715f, 695f, 22f, blue)
            text(c, s.driveMode, 1460f, 690f, 28f)
            text(c, "${s.coolant}°C", 1610f, 675f, 25f, if (s.coolant > 105) red else white)
            line(c, 1610f, 696f, 1870f, 696f, white, 4f)
            for (n in 0..4) line(c, 1610f + 65 * n, 687f, 1610f + 65 * n, 700f, if (n == 4) red else white, 4f)
            val mark = 1610 + 260 * ((s.coolant - 40) / 90f).coerceIn(0f, 1f)
            box(c, mark - 4, 683f, 8f, 20f, if (s.coolant > 105) red else blue, 1f)
            c.restore()
            if (now - spokenAt > 2000) {
                contentDescription = "模拟仪表，车速${s.speed.toInt()}公里每小时，挡位${s.gear}，转速${s.rpm}，续航${s.range}公里"
                spokenAt = now
            }
            if (isAttachedToWindow) postInvalidateDelayed(100)
        }
    }
}
