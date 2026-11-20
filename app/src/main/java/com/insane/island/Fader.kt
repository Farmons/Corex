package com.insane.island

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

// Плавное затухание перед паузой и нарастание после старта (через громкость музыки)
object Fader {
    private val h = Handler(Looper.getMainLooper())
    private var token = 0
    private var savedVol = -1

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("island", Context.MODE_PRIVATE)

    fun pause(ctx: Context, c: MediaController?) {
        c ?: return
        val p = prefs(ctx)
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        if (!p.getBoolean("fade", true) || !playing) { c.transportControls.pause(); return }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val my = ++token
        val orig = if (savedVol >= 0) savedVol else am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (orig <= 1) { c.transportControls.pause(); savedVol = -1; return }
        savedVol = orig
        val step = (p.getInt("fade_ms", 700).toLong() / orig).coerceAtLeast(30L)
        var v = orig
        val r = object : Runnable {
            override fun run() {
                if (my != token) return
                v -= 1
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceAtLeast(0), 0)
                if (v > 0) {
                    h.postDelayed(this, step)
                } else {
                    c.transportControls.pause()
                    h.postDelayed({
                        if (my == token) {
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, orig, 0)
                            savedVol = -1
                        }
                    }, 300)
                }
            }
        }
        h.post(r)
    }

    fun play(ctx: Context, c: MediaController?) {
        c ?: return
        val p = prefs(ctx)
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) return
        if (!p.getBoolean("fade", true)) { c.transportControls.play(); return }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val my = ++token
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val orig = if (savedVol >= 0) savedVol else cur
        if (orig <= 1) { c.transportControls.play(); savedVol = -1; return }
        savedVol = orig
        var v = if (cur >= orig) 0 else cur
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        c.transportControls.play()
        val step = (p.getInt("fade_ms", 700).toLong() / orig).coerceAtLeast(30L)
        val r = object : Runnable {
            override fun run() {
                if (my != token) return
                v += 1
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceAtMost(orig), 0)
                if (v < orig) h.postDelayed(this, step) else savedVol = -1
            }
        }
        h.postDelayed(r, step)
    }
}
