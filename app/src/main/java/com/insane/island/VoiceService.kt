package com.insane.island

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaMetadata
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class VoiceService : Service() {
    companion object { @Volatile var running = false }

    private class Cmd(val name: String, val pkg: String? = null, val number: String? = null, val label: String? = null)

    // Слово «кортекс» необязательно (настраивается в приложении)
    private val wake = setOf("кортекс", "кортэкс", "картекс")
    private val commands = listOf(
        "дальше", "далее", "назад", "предыдущая", "стоп", "пауза", "старт", "играй", "продолжи",
        "лайк", "дизлайк", "громче", "тише", "вперед", "повтор", "название", "время",
        "ответить", "принять"
    )
    private val callVerbs = setOf("позвони", "позвонить", "набери", "вызови")
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
    private val appMap = LinkedHashMap<String, String>()       // как произносится -> пакет
    private val contactMap = LinkedHashMap<String, String>()   // как произносится -> номер

    private val prefs by lazy { getSharedPreferences("island", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var wl: PowerManager.WakeLock? = null
    private var record: AudioRecord? = null
    private var recThread: Thread? = null
    @Volatile private var listening = false
    private var btRouted = false
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
        // не даём процессору уснуть при выключенном экране
        if (wl == null) {
            wl = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cortex:voice")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (tts == null) tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale("ru") }
        if (!listening && recThread == null) Thread {
            try {
                val words = LinkedHashSet<String>()
                words.addAll(wake); words.addAll(commands)
                if (prefs.getBoolean("openapps", true)) {
                    buildApps()
                    words.add("открой")
                    appMap.keys.flatMap { it.split(" ") }.distinct().take(250).forEach { words.add(it) }
                }
                if (prefs.getBoolean("callcmd", true)) buildContacts() else contactMap.clear()
                if (contactMap.isNotEmpty()) {
                    words.addAll(callVerbs)
                    contactMap.keys.flatMap { it.split(" ") }.distinct().take(250).forEach { words.add(it) }
                }
                words.add("[unk]")
                val model = Model(File(filesDir, "model").absolutePath)
                val rec = Recognizer(model, 16000f, JSONArray(words.toList()).toString()).also { it.setWords(true) }
                startRecording(rec)
            } catch (e: Exception) { stopSelf() }
        }.also { recThread = it }.start()
        return START_STICKY
    }

    // ---------- захват звука (телефон / Bluetooth-гарнитура) ----------
    @Suppress("DEPRECATION")
    private fun routeBluetoothMic(am: AudioManager): AudioDeviceInfo? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            val d = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (d == null || !am.setCommunicationDevice(d)) null
            else {
                btRouted = true
                Thread.sleep(800)
                am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == d.type }
            }
        } else {
            am.startBluetoothSco(); am.isBluetoothScoOn = true; btRouted = true
            Thread.sleep(1200)
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }
    } catch (e: Exception) { null }

    private fun startRecording(rec: Recognizer) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val dev = if (prefs.getInt("mic", 0) == 1) routeBluetoothMic(am) else null
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 8000))
        if (dev != null) ar.setPreferredDevice(dev)
        ar.startRecording()
        record = ar
        listening = true
        val buf = ShortArray(1024)   // ~64 мс: быстрый отклик
        while (listening) {
            val n = ar.read(buf, 0, buf.size)
            if (n <= 0) continue
            if (rec.acceptWaveForm(buf, n)) { val r = rec.result; main.post { onResult(r) } }
            else { val p = rec.partialResult; main.post { onPartialResult(p) } }
        }
        try { ar.stop() } catch (_: Exception) {}
        ar.release()
        rec.close()
    }

    // ---------- справочники ----------
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

    // Контакты с русскими именами: «мама», «иван петров» и просто имя
    private fun buildContacts() {
        contactMap.clear()
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        val c = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null) ?: return
        c.use {
            var count = 0
            while (it.moveToNext() && count < 400) {
                val name = it.getString(0)?.lowercase()?.replace('ё', 'е') ?: continue
                val num = it.getString(1)?.filter { ch -> ch.isDigit() || ch == '+' } ?: continue
                if (num.isEmpty()) continue
                val clean = name.replace(Regex("[^а-я ]"), " ").trim().replace(Regex("\\s+"), " ")
                if (clean.isEmpty()) continue
                contactMap.putIfAbsent(clean, num)
                contactMap.putIfAbsent(clean.substringBefore(' '), num)
                count++
            }
        }
    }

    private fun say(s: String) { tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "cortex") }

    // ---------- разбор фразы ----------
    private fun parse(words: List<String>, needWake: Boolean, final: Boolean): Pair<Cmd, Int>? {
        val wi = words.indexOfFirst { it in wake }
        val start = if (wi >= 0) wi + 1 else { if (needWake) return null; 0 }
        val rest = words.drop(start)
        if (rest.isEmpty()) return null

        // позвонить контакту (только по окончательному результату, чтобы не звонить не тому)
        val vi = rest.indexOfFirst { it in callVerbs }
        if (vi >= 0 && contactMap.isNotEmpty() && prefs.getBoolean("callcmd", true)) {
            if (!final) return null
            if (wi < 0 && prefs.getBoolean("call_wake", false)) return null
            val name = rest.drop(vi + 1)
            for (n in name.size downTo 1) {
                val key = name.take(n).joinToString(" ")
                val num = contactMap[key] ?: continue
                return Cmd("позвони", number = num, label = key) to (start + vi)
            }
            return null
        }

        // открыть приложение
        val oi = rest.indexOf("открой")
        if (oi >= 0 && prefs.getBoolean("openapps", true)) {
            val name = rest.drop(oi + 1)
            if (name.isEmpty()) return null
            for (n in name.size downTo 1) {
                val key = name.take(n).joinToString(" ")
                val pkg = appMap[key] ?: continue
                if (!final && appMap.keys.any { it.startsWith("$key ") }) return null
                return Cmd("открой", pkg = pkg) to (start + oi)
            }
            return null
        }

        // без слова-активатора принимается только одиночная команда
        if (wi < 0 && rest.size != 1) return null
        val ci = rest.indexOfFirst { it in commands }
        if (ci < 0) return null
        return Cmd(rest[ci]) to (start + ci)
    }

    private fun onPartialResult(h: String?) {
        if (!prefs.getBoolean("fast", true)) return
        val text = JSONObject(h ?: return).optString("partial")
        val words = text.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return
        val r = parse(words, prefs.getBoolean("wake", false), false) ?: return
        if (fire(r.first)) fired = true
    }

    private fun onResult(h: String?) {
        if (fired) { fired = false; return }
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
        when (cmd.name) {
            "открой" -> openApp(cmd.pkg ?: return false)
            "позвони" -> callNumber(cmd.number ?: return false, cmd.label ?: "")
            else -> run(cmd.name)
        }
        IslandBus.onCommand?.invoke()
        return true
    }

    private fun openApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (_: Exception) {}
    }

    private fun callNumber(number: String, label: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            say("Нет разрешения на звонки"); return
        }
        try {
            say("Звоню $label")
            (getSystemService(TELECOM_SERVICE) as TelecomManager)
                .placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (_: SecurityException) {}
    }

    private fun vol(dir: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val step = prefs.getInt("vol_step", 2)
        val v = (am.getStreamVolume(AudioManager.STREAM_MUSIC) + dir * step).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
    }

    private fun run(cmd: String) {
        val c = MediaActions.controller(this)
        val t = c?.transportControls
        when (cmd) {
            "дальше", "далее" -> t?.skipToNext()
            "назад", "предыдущая" -> t?.skipToPrevious()
            "стоп", "пауза" -> Fader.pause(this, c)
            "старт", "играй", "продолжи" -> Fader.play(this, c)
            "лайк" -> c?.let { MediaActions.like(it) }
            "дизлайк" -> c?.let { MediaActions.dislike(it) }
            "громче" -> vol(+1)
            "тише" -> vol(-1)
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

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        running = false
        listening = false
        if (btRouted) {
            try {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else am.stopBluetoothSco()
            } catch (_: Exception) {}
        }
        tts?.shutdown()
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
