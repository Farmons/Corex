package com.insane.island

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class IslandService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.insane.island.TOGGLE"
    }

    private var hiddenByUser = false

    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var compact: FrameLayout
    private lateinit var handle: FrameLayout
    private val excluded = setOf("ru.yandex.music")
    private lateinit var compactArt: ImageView
    private lateinit var eq: EqualizerView
    private lateinit var card: LinearLayout
    private lateinit var cardArt: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var seek: SeekBar
    private lateinit var tCur: TextView
    private lateinit var tEnd: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnLike: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var expanded = false
    private var liked = false
    private var dragging = false
    private var attached = false
    private var duration = 0L
    private var lastArt: Bitmap? = null
    private var lastTitle: String? = null
    private val pausedHide = Runnable { hide() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyPrefs() }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { refresh() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { refresh() }
        override fun onSessionDestroyed() { handler.post { reload() } }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            if (expanded) handler.postDelayed(this, 500)
        }
    }

    private val collapse = object : Runnable {
        override fun run() {
            if (dragging) handler.postDelayed(this, 2000) else setExpanded(false)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun View.round(r: Int) {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, r.toFloat())
            }
        }
        clipToOutline = true
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE && this::root.isInitialized) {
            hiddenByUser = !hiddenByUser
            saveMode()
            applyMode()
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startFg()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("island", Context.MODE_PRIVATE)
        buildViews()
        lp = WindowManager.LayoutParams(
            WRAP, WRAP,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        expanded = prefs.getBoolean("expanded", false)
        hiddenByUser = prefs.getBoolean("hidden", false)
        applyMode()
        root.visibility = View.GONE
        wm.addView(root, lp)
        attached = true
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        MediaListener.onChanged = { handler.post { refresh() } }
        initSessions()
    }

    override fun onDestroy() {
        MediaListener.onChanged = null
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {}
        controller?.unregisterCallback(cb)
        if (attached) wm.removeView(root)
        attached = false
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("island", "Остров", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, "island")
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle("Остров работает")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_headphones),
                    "Скрыть / показать остров",
                    PendingIntent.getService(
                        this, 1,
                        Intent(this, IslandService::class.java).setAction(ACTION_TOGGLE),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun buildViews() {
        // ---------- компактная плашка ----------
        compact = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(19).toFloat()
                setStroke(dp(1), 0xFF2A2A2A.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(dp(140), dp(38))
            setOnClickListener { setExpanded(true) }
            setOnLongClickListener { openPlayer(); true }
        }
        compactArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            round(dp(8))
        }
        compact.addView(
            compactArt,
            FrameLayout.LayoutParams(dp(28), dp(28), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { leftMargin = dp(6) }
        )
        eq = EqualizerView(this)
        compact.addView(
            eq,
            FrameLayout.LayoutParams(dp(22), dp(18), Gravity.END or Gravity.CENTER_VERTICAL)
                .apply { rightMargin = dp(16) }
        )

        // ---------- раскрытая карточка ----------
        val screenW = resources.displayMetrics.widthPixels
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(14))
            layoutParams = FrameLayout.LayoutParams(screenW - dp(10), WRAP)
            visibility = View.GONE
            setOnClickListener { openPlayer() }
            setOnLongClickListener { setExpanded(false); true }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { openPlayer() }
        }
        cardArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            round(dp(12))
        }
        head.addView(cardArt, LinearLayout.LayoutParams(dp(56), dp(56)))

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        artist = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(title)
        texts.addView(artist)
        head.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(14) })

        val hp = ImageView(this).apply {
            setImageResource(R.drawable.ic_headphones)
            setColorFilter(Color.WHITE)
        }
        head.addView(hp, LinearLayout.LayoutParams(dp(24), dp(24)).apply { gravity = Gravity.TOP })
        card.addView(head)

        // ---------- прогресс ----------
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tCur = TextView(this).apply { setTextColor(0x99FFFFFF.toInt()); textSize = 14f; text = "00:00" }
        tEnd = TextView(this).apply { setTextColor(0x99FFFFFF.toInt()); textSize = 14f; text = "00:00" }
        seek = SeekBar(this).apply {
            max = 1000
            val rad = dp(2).toFloat()
            val track = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = rad; setSize(dp(10), dp(3)) }
            val prog = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = rad; setSize(dp(10), dp(3)) }
            val layer = LayerDrawable(arrayOf(track, ClipDrawable(prog, Gravity.START, ClipDrawable.HORIZONTAL)))
            layer.setId(0, android.R.id.background)
            layer.setId(1, android.R.id.progress)
            progressDrawable = layer
            thumb = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = rad; setSize(dp(10), dp(4)) }
            thumbOffset = 0
            splitTrack = false
            setPadding(dp(14), 0, dp(14), 0)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && duration > 0) tCur.text = fmt(duration * p / 1000)
            }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                if (duration > 0) controller?.transportControls?.seekTo(duration * s.progress / 1000)
            }
        })
        seekRow.addView(tCur)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, dp(36), 1f))
        seekRow.addView(tEnd)
        card.addView(seekRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP)
            .apply { topMargin = dp(14) })

        // ---------- кнопки ----------
        val ctrl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun button(res: Int, pad: Int, onClick: () -> Unit): ImageButton {
            val b = ImageButton(this).apply {
                setImageResource(res)
                setColorFilter(Color.WHITE)
                background = null
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
                setOnClickListener { onClick() }
            }
            ctrl.addView(b, LinearLayout.LayoutParams(0, dp(64), 1f))
            return b
        }
        // дизлайк = пропустить трек
        button(R.drawable.ic_dislike, 18) { doDislike() }
        button(R.drawable.ic_prev, 16) { controller?.transportControls?.skipToPrevious() }
        btnPlay = button(R.drawable.ic_pause, 12) {
            val c = controller
            if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                c.transportControls.pause()
            } else {
                c?.transportControls?.play()
            }
        }
        button(R.drawable.ic_next, 16) { controller?.transportControls?.skipToNext() }
        btnLike = button(R.drawable.ic_heart, 18) { doLike() }
        card.addView(ctrl)

        root = SwipeFrame(this).apply {
            onSwipeUp = { swipeUp() }
            onSwipeDown = { swipeDown() }
        }
        handle = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(72), dp(26))
            setOnClickListener { unhide() }
        }
        val bar = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0x99FFFFFF.toInt())
                cornerRadius = dp(3).toFloat()
            }
        }
        handle.addView(bar, FrameLayout.LayoutParams(dp(44), dp(5), Gravity.CENTER))
        root.addView(compact)
        root.addView(handle)
        root.addView(card)
        applyColor(0xFFC8742F.toInt())
    }

    private fun applyPrefs() {
        lp.y = when {
            hiddenByUser -> dp(prefs.getInt("y", 6)) + dp(34)
            expanded -> dp(4)
            else -> dp(prefs.getInt("y", 6))
        }
        compact.layoutParams = compact.layoutParams.apply { width = dp(prefs.getInt("w", 140)) }
        if (attached) wm.updateViewLayout(root, lp)
    }

    private fun setExpanded(v: Boolean) {
        if (hiddenByUser) return
        expanded = v
        saveMode()
        handler.removeCallbacks(ticker)
        card.animate().cancel()
        val cw = card.layoutParams.width.toFloat()
        card.pivotX = cw / 2f
        card.pivotY = 0f
        if (v) {
            compact.visibility = View.GONE
            card.visibility = View.VISIBLE
            card.scaleX = (dp(140).toFloat() / cw).coerceIn(0.2f, 1f)
            card.scaleY = if (card.height > 0) (dp(38).toFloat() / card.height).coerceIn(0.08f, 1f) else 0.3f
            card.alpha = 0f
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260)
                .setInterpolator(OvershootInterpolator(0.8f)).start()
            for (i in 0 until card.childCount) {
                val ch = card.getChildAt(i)
                ch.alpha = 0f
                ch.animate().alpha(1f).setStartDelay(110).setDuration(200).start()
            }
            handler.post(ticker)
        } else {
            for (i in 0 until card.childCount) {
                card.getChildAt(i).animate().alpha(0f).setStartDelay(0).setDuration(100).start()
            }
            card.animate().scaleX(0.5f).scaleY(0.3f).alpha(0f).setDuration(180).withEndAction {
                if (!expanded) {
                    card.visibility = View.GONE
                    compact.visibility = View.VISIBLE
                }
            }.start()
        }
        applyPrefs()
    }

    private fun openPlayer() {
        val c = controller
        setExpanded(false)
        if (c == null) return
        try {
            val i = packageManager.getLaunchIntentForPackage(c.packageName)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            }
        } catch (_: Exception) {}
        try { c.sessionActivity?.send() } catch (_: Exception) {}
    }

    private fun saveMode() {
        prefs.edit().putBoolean("expanded", expanded).putBoolean("hidden", hiddenByUser).apply()
    }

    // показывает ровно то состояние, которое запомнено: скрыт / маленький / большой
    private fun applyMode() {
        card.animate().cancel()
        for (i in 0 until card.childCount) card.getChildAt(i).alpha = 1f
        card.alpha = 1f
        card.scaleX = 1f
        card.scaleY = 1f
        handler.removeCallbacks(ticker)
        if (hiddenByUser) {
            card.visibility = View.GONE
            compact.visibility = View.GONE
            handle.visibility = View.VISIBLE
        } else {
            handle.visibility = View.GONE
            if (expanded) {
                compact.visibility = View.GONE
                card.visibility = View.VISIBLE
                handler.post(ticker)
            } else {
                card.visibility = View.GONE
                compact.visibility = View.VISIBLE
            }
        }
        applyPrefs()
    }

    private fun userHide() {
        hiddenByUser = true
        saveMode()
        applyMode()
    }

    private fun unhide() {
        hiddenByUser = false
        saveMode()
        applyMode()
    }

    private fun swipeUp() {
        if (hiddenByUser) return
        if (expanded) setExpanded(false) else userHide()
    }

    private fun swipeDown() {
        if (hiddenByUser) unhide() else if (!expanded) setExpanded(true)
    }

    // состояние (большой / маленький) НЕ сбрасываем: просто прячем окно, пока нет музыки
    private fun hide() {
        handler.removeCallbacks(ticker)
        root.visibility = View.GONE
    }

    // ---------- лайк / дизлайк ----------
    private val dislikeRe = Regex("dislike|дизлайк|не нрав|ban|hide|скрыть|thumb.?down", RegexOption.IGNORE_CASE)
    private val likeRe = Regex("like|love|heart|favou?rite|нрав|избран|добав|сохран|save|thumb.?up|\\badd\\b|remove|удал|убрат", RegexOption.IGNORE_CASE)
    private val unlikeRe = Regex("unlike|remove|удал|убрат", RegexOption.IGNORE_CASE)

    private fun tintLike() {
        btnLike.setColorFilter(if (liked) 0xFFFF4D6A.toInt() else Color.WHITE)
    }

    private fun findLike(c: MediaController): PlaybackState.CustomAction? =
        c.playbackState?.customActions?.firstOrNull {
            val t = "${it.action} ${it.name}"
            likeRe.containsMatchIn(t) && !dislikeRe.containsMatchIn(t)
        }

    private fun notifActions(c: MediaController): List<Notification.Action> =
        MediaListener.acts.filterKeys { it.startsWith(c.packageName + "/") }.values.flatten()

    private fun doLike() {
        val c = controller ?: return
        // 1) кнопка лайка, которую плеер отдаёт в медиа-сессию
        val ca = findLike(c)
        if (ca != null) {
            c.transportControls.sendCustomAction(ca, ca.extras)
            return
        }
        // 2) кнопка лайка из уведомления плеера
        val na = notifActions(c).firstOrNull {
            val t = it.title?.toString() ?: ""
            likeRe.containsMatchIn(t) && !dislikeRe.containsMatchIn(t)
        }
        liked = !liked
        tintLike()
        if (na != null) {
            try { na.actionIntent?.send() } catch (_: Exception) {}
            return
        }
        // 3) обычный рейтинг-«сердечко»
        try { c.transportControls.setRating(Rating.newHeartRating(liked)) } catch (_: Exception) {}
    }

    private fun doDislike() {
        val c = controller ?: return
        val da = c.playbackState?.customActions?.firstOrNull {
            dislikeRe.containsMatchIn("${it.action} ${it.name}")
        }
        if (da != null) {
            c.transportControls.sendCustomAction(da, da.extras)
            return
        }
        val na = notifActions(c).firstOrNull { dislikeRe.containsMatchIn(it.title?.toString() ?: "") }
        if (na != null) {
            try { na.actionIntent?.send() } catch (_: Exception) {}
            return
        }
        c.transportControls.skipToNext()
    }

    // ---------- музыка ----------
    private fun initSessions() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(this, MediaListener::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn)
            pick(msm.getActiveSessions(cn))
        } catch (e: SecurityException) {
            // нет доступа к уведомлениям — пробуем снова через 3 секунды
            handler.postDelayed({ initSessions() }, 3000)
        }
    }

    private fun reload() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            pick(msm.getActiveSessions(ComponentName(this, MediaListener::class.java)))
        } catch (_: SecurityException) {}
    }

    private fun pick(list: List<MediaController>?) {
        val allowed = list?.filter { it.packageName !in excluded }
        val best = allowed?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: allowed?.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(cb)
            controller = best
            best?.registerCallback(cb, handler)
            liked = false
            tintLike()
        }
        refresh()
    }

    private fun refresh() {
        val c = controller
        val md = c?.metadata
        val st = c?.playbackState
        if (c == null || md == null || st == null ||
            st.state == PlaybackState.STATE_STOPPED || st.state == PlaybackState.STATE_NONE
        ) {
            hide()
            return
        }
        title.text = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        artist.text = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val newTitle = title.text.toString()
        val trackChanged = lastTitle != null && newTitle != lastTitle &&
            st.state == PlaybackState.STATE_PLAYING
        lastTitle = newTitle
        duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (art != null && art !== lastArt) {
            lastArt = art
            compactArt.setImageBitmap(art)
            cardArt.setImageBitmap(art)
            applyColor(Bitmap.createScaledBitmap(art, 1, 1, true).getPixel(0, 0))
        }

        val playing = st.state == PlaybackState.STATE_PLAYING
        eq.playing = playing
        handler.removeCallbacks(pausedHide)
        if (!playing) handler.postDelayed(pausedHide, 20000)

        // --- проверяем, стоит ли уже лайк ---
        md.getRating(MediaMetadata.METADATA_KEY_USER_RATING)?.let { r ->
            if (r.isRated) {
                liked = r.hasHeart() || r.isThumbUp
                tintLike()
            }
        }
        val la = findLike(c)
        if (la != null) {
            liked = unlikeRe.containsMatchIn("${la.action} ${la.name}")
            tintLike()
        } else {
            notifActions(c).firstOrNull {
                val t = it.title?.toString() ?: ""
                likeRe.containsMatchIn(t) && !dislikeRe.containsMatchIn(t)
            }?.let {
                liked = unlikeRe.containsMatchIn(it.title?.toString() ?: "")
                tintLike()
            }
        }
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        root.visibility = View.VISIBLE
        updateProgress()
    }

    private fun updateProgress() {
        val st = controller?.playbackState ?: return
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        if (duration > 0) pos = pos.coerceIn(0L, duration)
        tCur.text = fmt(pos)
        tEnd.text = fmt(duration)
        if (!dragging) seek.progress = if (duration > 0) (pos * 1000 / duration).toInt() else 0
    }

    private fun applyColor(base: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1f)
        hsv[2] = hsv[2].coerceIn(0.55f, 0.95f)
        val main = Color.HSVToColor(hsv)
        val dark = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 0.12f))
        val baseBg = GradientDrawable().apply {
            setColor(0xFF050507.toInt())
            cornerRadius = dp(30).toFloat()
        }
        val glow = GradientDrawable().apply {
            setGradientType(GradientDrawable.RADIAL_GRADIENT)
            setGradientCenter(0.5f, 1.15f)
            setGradientRadius(dp(300).toFloat())
            setColors(intArrayOf((main and 0x00FFFFFF) or (0xCC shl 24), Color.TRANSPARENT))
            cornerRadius = dp(30).toFloat()
        }
        card.background = LayerDrawable(arrayOf(baseBg, glow))
        hsv[2] = 1f
        hsv[1] = (hsv[1] * 0.8f)
        eq.color = Color.HSVToColor(hsv)
    }
}
