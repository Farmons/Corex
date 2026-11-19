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

    // Команда работает только одной фразой: «кортекс <команда>»
    private val wake = setOf("кортекс", "кортэкс", "картекс")
    private val commands = listOf(
        "дальше", "далее", "назад", "предыдущая", "стоп", "пауза", "старт", "играй", "продолжи",
        "лайк", "дизлайк", "громче", "тише", "вперед", "повтор", "название", "время",
        "ответить", "принять"
    )

    private var speech: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var last = 0L

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
                val model = Model(File(filesDir, "model").absolutePath)
                val grammar = JSONArray((wake + commands + "[unk]").toList()).toString()
                speech = SpeechService(Recognizer(model, 16000f, grammar), 16000f).also { it.startListening(this) }
            } catch (e: Exception) { stopSelf() }
        }.start()
        return START_STICKY
    }

    private fun say(s: String) { tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "cortex") }

    override fun onResult(h: String?) {
        val words = JSONObject(h ?: return).optString("text").split(" ").filter { it.isNotBlank() }
        val wi = words.indexOfFirst { it in wake }
        if (wi < 0) return
        val cmd = words.drop(wi + 1).firstOrNull { it in commands } ?: return
        val now = System.currentTimeMillis()
        if (now - last < 1000) return
        last = now
        run(cmd)
    }

    @Suppress("DEPRECATION")
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
    override fun onPartialResult(h: String?) {}
    override fun onError(e: Exception?) {}
    override fun onTimeout() {}

    override fun onDestroy() { running = false; speech?.shutdown(); tts?.shutdown(); super.onDestroy() }
}
