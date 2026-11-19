package com.insane.island

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class VoiceService : Service(), RecognitionListener {
    companion object { @Volatile var running = false }

    private class Cmd(val name: String, val pkg: String? = null)

    // Слово «кортекс» необязательно (настраивается в приложении)
    private val wake = setOf("кортекс", "кортэкс", "картекс")
    private val commands = listOf(
        "дальше", "далее", "назад", "предыдущая", "стоп", "пауза", "старт", "играй", "продолжи",
        "лайк", "дизлайк", "громче", "тише", "вперед", "повтор", "название", "время",
        "ответить", "принять"
    )
    // Как называть приложения с английскими названиями
    private val aliases = mapOf(
        "com.google.android.youtube" to "ютуб", "org.telegram.messenger" to "телеграм",
        "org.telegram.messenger.web" to "телеграм", "com.whatsapp" to "ватсап",
        "com.instagram.android" to "инстаграм", "com.zhiliaoapp.musically" to "тикток",
        "com.ss.android.ugc.trill" to "тикток", "com.vkontakte.android" to "вк",
        "com.android.chrome" to "хром", "com.spotify.music" to "спотифай",
        "com.discord" to "дискорд", "com.android.settings" to "настройки",
        "ru.yandex.music" to "яндекс музыка", "com.google.android.apps.maps" to "карты",
        "com.google.android.gm" to "почта", "com.google.android.apps.photos" to "фото"
    )
    private val appMap = LinkedHashMap<String, String>()   // как произносится -> пакет

    private val prefs by lazy { getSharedPreferences("island", MODE_PRIVATE) }
    private var speech: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var last = 0L
    private var fired = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        running = true
        val ch = "voice"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Голос CORTEX", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("CORTEX слушает")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(2, n)
        if (tts == null) tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale("ru") }
        if (speech == null) Thread {
            try {
                val words = LinkedHashSet<String>()
                words.addAll(wake); words.addAll(commands)
                if (prefs.getBoolean("openapps", true)) {
                    buildApps()
                    words.add("открой")
                    appMap.keys.flatMap { it.split(" ") }.distinct().take(250).forEach { words.add(it) }
                }
                words.add("[unk]")
                val model = Model(File(filesDir, "model").absolutePath)
                val rec = Recognizer(model, 16000f, JSONArray(words.toList()).toString()).also { it.setWords(true) }
                speech = SpeechService(rec, 16000f).also { it.startListening(this) }
            } catch (e: Exception) { stopSelf() }
        }.start()
        return START_STICKY
    }

    // Список приложений для команды «открой …»
    private fun buildApps() {
        appMap.clear()
        val pm = packageManager
        val list = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        for (ri in list) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            val label = ri.loadLabel(pm).toString().lowercase().replace('ё', 'е').trim()
            val names = mutableListOf<String>()
            if (label.any { it in 'а'..'я' } && label.none { it.isDigit() }) names.add(label)
            aliases[pkg]?.let { names.add(it) }
            for (nm in names) {
                val clean = nm.replace(Regex("[^а-я ]"), " ").trim().replace(Regex("\\s+"), " ")
                if (clean.isNotEmpty()) appMap.putIfAbsent(clean, pkg)
            }
        }
    }

    private fun say(s: String) { tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "cortex") }

    // Разбор фразы: команда и индекс слова (для проверки уверенности)
    private fun parse(words: List<String>, needWake: Boolean, final: Boolean): Pair<Cmd, Int>? {
        val wi = words.indexOfFirst { it in wake }
        val start = if (wi >= 0) wi + 1 else { if (needWake) return null; 0 }
        val rest = words.drop(start)
        if (rest.isEmpty()) return null

        val oi = rest.indexOf("открой")
        if (oi >= 0 && prefs.getBoolean("openapps", true)) {
            val name = rest.drop(oi + 1)
            if (name.isEmpty()) return null
            for (n in name.size downTo 1) {
                val key = name.take(n).joinToString(" ")
                val pkg = appMap[key] ?: continue
                // в частичном результате ждём конец, если есть более длинное название
                if (!final && appMap.keys.any { it.startsWith("$key ") }) return null
                return Cmd("открой", pkg) to (start + oi)
            }
            return null
        }

        // без слова-активатора принимается только одиночная команда
        if (wi < 0 && rest.size != 1) return null
        val ci = rest.indexOfFirst { it in commands }
        if (ci < 0) return null
        return Cmd(rest[ci]) to (start + ci)
    }

    // Мгновенная реакция по частичному результату (до паузы в речи)
    override fun onPartialResult(h: String?) {
        if (!prefs.getBoolean("fast", true)) return
        val text = JSONObject(h ?: return).optString("partial")
        val words = text.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return
        val r = parse(words, prefs.getBoolean("wake", false), false) ?: return
        if (fire(r.first)) fired = true
    }

    override fun onResult(h: String?) {
        if (fired) { fired = false; return }      // уже выполнено по частичному результату
        val j = JSONObject(h ?: return)
        val arr = j.optJSONArray("result")
        val words: List<String>
        val conf: List<Double>
        if (arr != null && arr.length() > 0) {
            words = (0 until arr.length()).map { arr.getJSONObject(it).optString("word") }
            conf = (0 until arr.length()).map { arr.getJSONObject(it).optDouble("conf", 1.0) }
        } else {
            words = j.optString("text").split(" ").filter { it.isNotBlank() }
            conf = words.map { 1.0 }
        }
        if (words.isEmpty()) return
        val r = parse(words, prefs.getBoolean("wake", false), true) ?: return
        if (conf[r.second] < prefs.getInt("conf", 70) / 100.0) return
        fire(r.first)
    }

    private fun fire(cmd: Cmd): Boolean {
        val now = System.currentTimeMillis()
        if (now - last < 900) return false
        last = now
        if (cmd.name == "открой") openApp(cmd.pkg ?: return false) else run(cmd.name)
        return true
    }

    private fun openApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (_: Exception) {}
    }

    private fun run(cmd: String) {
        val c = MediaActions.controller(this)
        val t = c?.transportControls
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        when (cmd) {
            "дальше", "далее" -> t?.skipToNext()
            "назад", "предыдущая" -> t?.skipToPrevious()
            "стоп", "пауза" -> t?.pause()
            "старт", "играй", "продолжи" -> t?.play()
            "лайк" -> c?.let { MediaActions.like(it) }
            "дизлайк" -> c?.let { MediaActions.dislike(it) }
            "громче" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            "тише" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            "вперед" -> t?.seekTo((c?.playbackState?.position ?: 0L) + 15000)
            "повтор" -> t?.seekTo(0)
            "название" -> {
                val m = c?.metadata
                val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
                val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                say(if (title == null) "Сейчас ничего не играет" else "Играет $title" + (artist?.let { ", $it" } ?: ""))
            }
            "время" -> say("Сейчас " + SimpleDateFormat("H mm", Locale("ru")).format(Date()))
            "ответить", "принять" -> answerCall()
        }
    }

    private fun answerCall() {
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return
        try {
            (getSystemService(TELECOM_SERVICE) as TelecomManager).acceptRingingCall()
        } catch (_: SecurityException) {}
    }

    override fun onFinalResult(h: String?) {}
    override fun onError(e: Exception?) {}
    override fun onTimeout() {}

    override fun onDestroy() { running = false; speech?.shutdown(); tts?.shutdown(); super.onDestroy() }
}
