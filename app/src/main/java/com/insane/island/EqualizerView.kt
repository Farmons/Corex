package com.insane.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

class EqualizerView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var phase = 0f
    private var anim: ValueAnimator? = null

    var color: Int = 0xFFFF8A3D.toInt()
        set(v) { field = v; invalidate() }

    var playing: Boolean = false
        set(v) {
            field = v
            if (v && isAttachedToWindow) start() else stop()
            invalidate()
        }

    private fun start() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 6.2832f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() {
        anim?.cancel()
        anim = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playing) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val n = 5
        val bw = width / (n * 2f - 1f)
        paint.strokeWidth = bw
        paint.color = color
        for (i in 0 until n) {
            val f = if (playing) 0.3f + 0.7f * abs(sin(phase + i * 1.3f)) else 0.3f
            val h = height * f
            val x = bw / 2f + i * bw * 2f
            c.drawLine(x, (height - h) / 2f + bw / 2f, x, (height + h) / 2f - bw / 2f, paint)
        }
    }
}
