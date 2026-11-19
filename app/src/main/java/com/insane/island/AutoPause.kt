package com.insane.island

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper

// Автопауза: отключение наушников и звонки (входящий, разговор, VoIP)
object AutoPause {
    private var app: Context? = null
    private var am: AudioManager? = null
    private var devCb: AudioDeviceCallback? = null
    private var modeCb: Any? = null
    private var pausedByCall = false

    private val headTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET
    )

    fun start(c: Context) {
        if (am != null) return
        val ctx = c.applicationContext
        app = ctx
        val prefs = ctx.getSharedPreferences("island", Context.MODE_PRIVATE)
        val a = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am = a
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                if (!prefs.getBoolean("ap_headset", true)) return
                if (removed.any { it.type in headTypes && it.isSink }) pauseNow()
            }
        }
        devCb = cb
        a.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        if (Build.VERSION.SDK_INT >= 31) {
            val l = AudioManager.OnModeChangedListener { mode -> onMode(mode, prefs) }
            modeCb = l
            a.addOnModeChangedListener(ctx.mainExecutor, l)
        }
    }

    fun stop() {
        val a = am ?: return
        devCb?.let { a.unregisterAudioDeviceCallback(it) }
        if (Build.VERSION.SDK_INT >= 31) {
            (modeCb as? AudioManager.OnModeChangedListener)?.let { a.removeOnModeChangedListener(it) }
        }
        devCb = null; modeCb = null; am = null; app = null; pausedByCall = false
    }

    private fun onMode(mode: Int, prefs: android.content.SharedPreferences) {
        val inCall = mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
        if (inCall) {
            if (prefs.getBoolean("ap_call", true) && isPlaying()) { pauseNow(); pausedByCall = true }
        } else if (pausedByCall) {
            pausedByCall = false
            if (prefs.getBoolean("ap_resume", true)) resumeNow()
        }
    }

    private fun ctrl() = app?.let { MediaActions.controller(it) }
    private fun isPlaying() = ctrl()?.playbackState?.state == PlaybackState.STATE_PLAYING
    private fun pauseNow() { ctrl()?.transportControls?.pause() }
    private fun resumeNow() { ctrl()?.transportControls?.play() }
}
