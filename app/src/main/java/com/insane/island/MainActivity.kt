package com.insane.island

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import android.widget.Switch
import android.app.AlertDialog
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : Activity() {

    private val BG = 0xFF08080C.toInt()
    private val CARD = 0xFF14111D.toInt()
    private val STROKE = 0xFF2B2145.toInt()
    private val PURPLE = 0xFF8B5CF6.toInt()
    private val PURPLE2 = 0xFFA855F7.toInt()
    private val LIGHT = 0xFFC4B5FD.toInt()
    private val MUTED = 0xFF9A93B5.toInt()
    private val GREEN = 0xFF34D399.toInt()
    private val RED = 0xFFF87171.toInt()
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    private var density = 1f
    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var st1: TextView
    private lateinit var st2: TextView
    private lateinit var diag: TextView
    private lateinit var dot3: View
    private lateinit var dot4: View
    private lateinit var st3: TextView
    private lateinit var st4: TextView
    private val modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"

    private fun dp(v: Int) = (v * density).toInt()

    private fun shape(color: Int, radius: Int, strokeColor: Int = 0, strokeW: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeW > 0) setStroke(dp(strokeW), strokeColor)
        }

    private fun lp(top: Int = 0) =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 22, STROKE, 1)
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    // kind: 0 = залитая фиолетовая, 1 = с фиолетовой рамкой, 2 = тёмная
    private fun button(t: String, kind: Int, onClick: () -> Unit): TextView {
        val base: GradientDrawable = when (kind) {
            0 -> GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(PURPLE, PURPLE2))
                .apply { cornerRadius = dp(16).toFloat() }
            1 -> shape(0x00000000, 16, PURPLE, 1)
            else -> shape(0xFF1C1829.toInt(), 16, STROKE, 1)
        }
        return TextView(this).apply {
            text = t
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun statusRow(title: String): Triple<LinearLayout, View, TextView> {
        val dot = View(this)
        val st = TextView(this).apply { textSize = 14f }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(12) })
            addView(label(title, 16f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(st)
        }
        return Triple(row, dot, st)
    }

    private fun setStatus(dot: View, st: TextView, ok: Boolean) {
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (ok) GREEN else RED)
        }
        st.text = if (ok) "включено" else "выключено"
        st.setTextColor(if (ok) GREEN else RED)
    }

    private fun pairRow(a: TextView, b: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        density = resources.displayMetrics.density
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.setBackgroundColor(BG)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(28))
        }

        // ---------- шапка ----------
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_fg)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(22).toFloat())
                }
            }
            clipToOutline = true
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Остров", 32f, Color.WHITE, true))
            addView(label("Музыкальный остров у камеры", 14f, LIGHT))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(16) })
        }
        col.addView(header)

        // ---------- состояние ----------
        val c1 = card()
        c1.addView(label("СОСТОЯНИЕ", 12f, MUTED, true))
        val r1 = statusRow("Поверх других окон")
        val r2 = statusRow("Доступ к уведомлениям")
        dot1 = r1.second; st1 = r1.third
        dot2 = r2.second; st2 = r2.third
        c1.addView(r1.first, lp(14))
        c1.addView(r2.first, lp(14))
        col.addView(c1, lp(28))

        // ---------- настройка ----------
        col.addView(label("НАСТРОЙКА", 12f, MUTED, true), lp(26))
        col.addView(button("1   Разрешить поверх окон", 1) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, lp(10))
        col.addView(button("2   Доступ к уведомлениям", 1) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, lp(10))
        col.addView(button("Включить остров", 0) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала шаг 1", Toast.LENGTH_SHORT).show()
            } else {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                startForegroundService(Intent(this, IslandService::class.java))
            }
        }, lp(18))
        col.addView(button("Выключить остров", 2) {
            stopService(Intent(this, IslandService::class.java))
        }, lp(10))

        col.addView(button("Скрыть / показать остров", 2) {
            startForegroundService(Intent(this, IslandService::class.java).setAction(IslandService.ACTION_TOGGLE))
        }, lp(10))

        // ---------- голос CORTEX ----------
        val cv = card()
        cv.addView(label("ГОЛОС CORTEX", 12f, MUTED, true))
        val r3 = statusRow("Микрофон и звонки")
        val r4 = statusRow("Голосовая модель")
        dot3 = r3.second; st3 = r3.third
        dot4 = r4.second; st4 = r4.third
        cv.addView(r3.first, lp(14))
        cv.addView(r4.first, lp(14))
        cv.addView(button("Разрешить микрофон и звонки", 1) {
            requestPermissions(arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.POST_NOTIFICATIONS
            ), 2)
        }, lp(14))
        cv.addView(button("Скачать голосовую модель (~45 МБ)", 1) { downloadModel() }, lp(10))
        cv.addView(button("Включить голос", 0) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Сначала разреши микрофон", Toast.LENGTH_SHORT).show()
            } else if (!File(filesDir, "model/am").exists()) {
                Toast.makeText(this, "Сначала скачай модель", Toast.LENGTH_SHORT).show()
            } else {
                startForegroundService(Intent(this, VoiceService::class.java))
            }
        }, lp(14))
        cv.addView(button("Выключить голос", 2) { stopService(Intent(this, VoiceService::class.java)) }, lp(10))
        cv.addView(
            label("Говори команду: «лайк», «дальше», «стоп»…\nслово «кортекс» перед командой тоже работает.\nдальше · назад · стоп · старт · лайк · дизлайк\nгромче · тише · вперед · повтор · название · время\nответить (на звонок)", 13f, MUTED),
            lp(14)
        )
        toggle(cv, "Требовать слово «кортекс»", "wake", false)
        slider(cv, "Порог уверенности голоса", "conf", 30, 95, 70, " %", null)
        col.addView(cv, lp(26))

        // ---------- настройка острова ----------
        val c2 = card()
        c2.addView(label("НАСТРОЙКА ОСТРОВА", 12f, MUTED, true))
        slider(c2, "Положение сверху", "y", -10, 80, 6, " dp", null)
        slider(c2, "Сдвиг влево / вправо", "x", -150, 150, 0, " dp", null)
        slider(c2, "Ширина", "w", 80, 320, 140, " dp", null)
        slider(c2, "Высота", "h", 28, 60, 38, " dp", null)
        slider(c2, "Прозрачность", "alpha", 30, 100, 100, " %", null)
        slider(c2, "Прятать после паузы", "pause", 0, 120, 20, " с", "никогда")
        slider(c2, "Авто-сворачивание карточки", "collapse", 0, 30, 0, " с", "выкл")
        toggle(c2, "Эквалайзер на острове", "eq", true)
        toggle(c2, "Цвет от обложки трека", "autocolor", true)
        c2.addView(button("Не показывать для приложений…", 2) { pickExcluded() }, lp(18))
        c2.addView(button("Сбросить настройки острова", 2) { resetIsland() }, lp(10))
        col.addView(c2, lp(26))

        // ---------- диагностика ----------
        col.addView(button("Диагностика плеера", 2) {
            diag.text = collectDiag()
            diag.visibility = View.VISIBLE
        }, lp(26))
        diag = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(LIGHT)
            setTextIsSelectable(true)
            background = shape(CARD, 16, STROKE, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        col.addView(diag, lp(10))

        col.addView(
            label("Чтобы остров не пропадал: Настройки → Батарея → Остров → «Без ограничений» и включи автозапуск.", 13f, MUTED),
            lp(22)
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(col)
        })
    }

    private fun slider(parent: LinearLayout, title: String, key: String, min: Int, max: Int, def: Int, unit: String, zero: String?) {
        val p = getSharedPreferences("island", Context.MODE_PRIVATE)
        val value = label("", 14f, LIGHT)
        fun show(v: Int) { value.text = if (zero != null && v == 0) zero else "$v$unit" }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label(title, 15f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(value)
        }
        val start = p.getInt(key, def).coerceIn(min, max)
        show(start)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = start - min
            progressTintList = ColorStateList.valueOf(PURPLE)
            thumbTintList = ColorStateList.valueOf(PURPLE2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, pr: Int, user: Boolean) {
                    show(pr + min)
                    if (user) p.edit().putInt(key, pr + min).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        parent.addView(head, lp(16))
        parent.addView(bar, lp(4))
    }

    private fun toggle(parent: LinearLayout, title: String, key: String, def: Boolean) {
        val p = getSharedPreferences("island", Context.MODE_PRIVATE)
        val on = intArrayOf(android.R.attr.state_checked)
        val sw = Switch(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = p.getBoolean(key, def)
            thumbTintList = ColorStateList(arrayOf(on, intArrayOf()), intArrayOf(PURPLE2, MUTED))
            trackTintList = ColorStateList(arrayOf(on, intArrayOf()), intArrayOf(0x668B5CF6, 0x33FFFFFF))
            setOnCheckedChangeListener { _, c -> p.edit().putBoolean(key, c).apply() }
        }
        parent.addView(sw, lp(16))
    }

    private fun pickExcluded() {
        val p = getSharedPreferences("island", Context.MODE_PRIVATE)
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val cur = (p.getString("excluded", "ru.yandex.music") ?: "").split(",").filter { it.isNotBlank() }.toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in cur }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Не показывать на острове")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on ->
                if (on) cur.add(apps[i].first) else cur.remove(apps[i].first)
            }
            .setPositiveButton("Готово") { _, _ -> p.edit().putString("excluded", cur.joinToString(",")).apply() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun resetIsland() {
        getSharedPreferences("island", Context.MODE_PRIVATE).edit()
            .remove("y").remove("x").remove("w").remove("h").remove("alpha").remove("pause")
            .remove("collapse").remove("eq").remove("autocolor").remove("excluded").apply()
        recreate()
    }

    private fun collectDiag(): String {
        return try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(ComponentName(this, MediaListener::class.java))
            if (list.isEmpty()) return "Нет активных плееров. Включи музыку и нажми ещё раз."
            list.joinToString("\n\n") { c ->
                val ca = c.playbackState?.customActions?.joinToString { "${it.action} / ${it.name}" } ?: "-"
                val na = MediaListener.acts.filterKeys { it.startsWith(c.packageName + "/") }
                    .values.flatten().joinToString { it.title?.toString() ?: "?" }
                "Плеер: ${c.packageName}\nКнопки сессии: $ca\nКнопки уведомления: $na"
            }
        } catch (e: SecurityException) {
            "Нет доступа к уведомлениям (шаг 2)."
        }
    }

    private fun change(key: String, delta: Int, min: Int, max: Int) {
        val p = getSharedPreferences("island", Context.MODE_PRIVATE)
        val def = if (key == "y") 6 else 140
        p.edit().putInt(key, (p.getInt(key, def) + delta).coerceIn(min, max)).apply()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        onResume()
    }

    private fun downloadModel() {
        Toast.makeText(this, "Скачиваю модель…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val dir = File(filesDir, "model").apply { deleteRecursively(); mkdirs() }
                ZipInputStream(URL(modelUrl).openStream()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val rel = e.name.substringAfter('/')
                        if (rel.isNotEmpty()) {
                            val f = File(dir, rel)
                            if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                        }
                        e = z.nextEntry
                    }
                }
                runOnUiThread { Toast.makeText(this, "Модель готова", Toast.LENGTH_SHORT).show(); onResume() }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, "Ошибка: ${ex.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        val ov = Settings.canDrawOverlays(this)
        val ls = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        setStatus(dot1, st1, ov)
        setStatus(dot2, st2, ls)
        val perms = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        setStatus(dot3, st3, perms)
        setStatus(dot4, st4, File(filesDir, "model/am").exists())
    }
}
