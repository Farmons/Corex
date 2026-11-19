package com.insane.island

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

// Контейнер острова: свайп вверх сворачивает, свайп вниз раскрывает.
class SwipeFrame(c: Context) : FrameLayout(c) {
    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null
    private var downY = 0f
    private var swiped = false
    private val slop = ViewConfiguration.get(c).scaledTouchSlop * 2

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.rawY; swiped = false }
            MotionEvent.ACTION_MOVE -> if (!swiped) {
                val dy = e.rawY - downY
                if (dy < -slop) { swiped = true; onSwipeUp?.invoke(); return true }
                if (dy > slop) { swiped = true; onSwipeDown?.invoke(); return true }
            }
        }
        return false
    }
}
