package com.primeott.app.presentation.watch

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.primeott.app.domain.model.StreamSource
import com.primeott.app.presentation.detail.VectorIcons
import com.primeott.app.presentation.player.PrimePlayerEngine
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.min

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WatchRoomPlayerUI(
    private val hostContext: Context,
    private val rootView: View,
    private val hostActivity: Activity?,
    private val watchPlayerView: PlayerView,
    private val playerFrameContainer: FrameLayout,
    private val playerEngine: PrimePlayerEngine,
    private val subtitleManager: WatchRoomSubtitleManager,
    private val scope: CoroutineScope,
    private val onTogglePlayPause: () -> Unit,
    private val onSeekByDeltaMs: (Long) -> Unit,
    private val onSeekToExactMs: (Long) -> Unit,
    private val onServerSelected: (Int, StreamSource) -> Unit,
    private val getProviderTargets: () -> List<StreamSource>,
    private val getCurrentProviderIndex: () -> Int,
    private val getCurrentStreamId: () -> String,
    private val onPlayStateTextUpdate: (Boolean) -> Unit
) {
    lateinit var appCustomControlsOverlay: FrameLayout
    lateinit var tvAppPlayerTitle: TextView
    lateinit var btnAppServerPill: TextView
    lateinit var btnAppQualityPill: TextView
    lateinit var btnAppSubtitlesCc: ImageView
    lateinit var btnAppPipIcon: ImageView
    lateinit var btnAppRewind15: FrameLayout
    lateinit var vAppRewindCircle: View
    lateinit var btnAppPlayPauseSlot: FrameLayout
    lateinit var vAppPlayPauseCircle: View
    lateinit var ivAppCenterPlayPause: ImageView
    lateinit var pbAppCenterBuffering: ProgressBar
    lateinit var btnAppForward15: FrameLayout
    lateinit var vAppForwardCircle: View
    lateinit var tvAppElapsed: TextView
    lateinit var sbAppRedSeekBar: SeekBar
    lateinit var tvAppDuration: TextView
    lateinit var btnAppFullscreenToggle: ImageView

    var selectedQualityHeight: Int? = null
    var isFullscreenPlayer = false
    var currentResizeModeIndex = 0
    private var isUserSeekingRedBar = false
    private var progressTickJob: Job? = null
    private var originalPlayerHeightPx = 0
    private var isDestroyed = false

    private val autoHideControlsRunnable = Runnable {
        if (playerEngine.isPlaying() && !isUserSeekingRedBar) {
            hideControlsSmoothly()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun buildOverlayUI() {
        subtitleManager.attachSubtitleOverlay(playerFrameContainer)

        appCustomControlsOverlay = FrameLayout(hostContext).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#B3000000"), Color.parseColor("#33000000"), Color.parseColor("#CC000000"))
            )
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val topBarRow = LinearLayout(hostContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 10), WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 6))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }

        tvAppPlayerTitle = TextView(hostContext).apply {
            text = "Prime Player"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = WatchRoomUtils.dp(hostContext, 8) }
        }
        topBarRow.addView(tvAppPlayerTitle)

        val makeGlassChip = { radiusDp: Float ->
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = WatchRoomUtils.dpF(hostContext, radiusDp)
                setColor(Color.parseColor("#2EFFFFFF"))
                setStroke(max(1, WatchRoomUtils.dpF(hostContext, 0.85f).toInt()), Color.parseColor("#4DFFFFFF"))
            }
        }

        btnAppServerPill = TextView(hostContext).apply {
            text = "  REYZO 1"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            background = makeGlassChip(10f)
            setPadding(WatchRoomUtils.dp(hostContext, 10), WatchRoomUtils.dp(hostContext, 5), WatchRoomUtils.dp(hostContext, 10), WatchRoomUtils.dp(hostContext, 5))
            val srvIcon = VectorIcons.createSmallServerStackIcon(hostContext, Color.WHITE).apply { setBounds(0, 0, intrinsicWidth, intrinsicHeight) }
            setCompoundDrawables(srvIcon, null, null, null)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, WatchRoomUtils.dp(hostContext, 32)).apply { marginEnd = WatchRoomUtils.dp(hostContext, 6) }
            setOnClickListener { resetAutoHideControls(); showServersPopup() }
        }
        topBarRow.addView(btnAppServerPill)

        btnAppQualityPill = TextView(hostContext).apply {
            text = " 1080p"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = makeGlassChip(10f)
            setPadding(WatchRoomUtils.dp(hostContext, 10), WatchRoomUtils.dp(hostContext, 5), WatchRoomUtils.dp(hostContext, 10), WatchRoomUtils.dp(hostContext, 5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, WatchRoomUtils.dp(hostContext, 32)).apply { marginEnd = WatchRoomUtils.dp(hostContext, 6) }
            setOnClickListener { resetAutoHideControls(); showQualityPopup() }
        }
        topBarRow.addView(btnAppQualityPill)

        btnAppSubtitlesCc = ImageView(hostContext).apply {
            background = makeGlassChip(10f)
            setPadding(WatchRoomUtils.dp(hostContext, 7), WatchRoomUtils.dp(hostContext, 7), WatchRoomUtils.dp(hostContext, 7), WatchRoomUtils.dp(hostContext, 7))
            setImageDrawable(VectorIcons.createSubtitlesCcIcon(hostContext, Color.WHITE))
            layoutParams = LinearLayout.LayoutParams(WatchRoomUtils.dp(hostContext, 32), WatchRoomUtils.dp(hostContext, 32)).apply { marginEnd = WatchRoomUtils.dp(hostContext, 6) }
            setOnClickListener { resetAutoHideControls(); subtitleManager.showSubtitlesDialog(getCurrentStreamId()) }
        }
        topBarRow.addView(btnAppSubtitlesCc)

        btnAppPipIcon = ImageView(hostContext).apply {
            background = makeGlassChip(10f)
            setPadding(WatchRoomUtils.dp(hostContext, 7), WatchRoomUtils.dp(hostContext, 7), WatchRoomUtils.dp(hostContext, 7), WatchRoomUtils.dp(hostContext, 7))
            setImageDrawable(VectorIcons.createPipMiniPlayerIcon(hostContext, Color.WHITE))
            layoutParams = LinearLayout.LayoutParams(WatchRoomUtils.dp(hostContext, 32), WatchRoomUtils.dp(hostContext, 32))
            setOnClickListener { toggleAspectRatio() }
        }
        topBarRow.addView(btnAppPipIcon)
        appCustomControlsOverlay.addView(topBarRow)

        val centerRow = LinearLayout(hostContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }

        val makeRedTouchCircle = {
            View(hostContext).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ColorUtils.setAlphaComponent(Color.parseColor("#E50914"), 195))
                }
                alpha = 0f
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }

        btnAppRewind15 = FrameLayout(hostContext).apply { layoutParams = LinearLayout.LayoutParams(WatchRoomUtils.dp(hostContext, 52), WatchRoomUtils.dp(hostContext, 52)).apply { marginEnd = WatchRoomUtils.dp(hostContext, 28) } }
        vAppRewindCircle = makeRedTouchCircle()
        val ivRewind = ImageView(hostContext).apply {
            setImageDrawable(VectorIcons.createCircularSeekArrowIcon(hostContext, isForward = false))
            setPadding(WatchRoomUtils.dp(hostContext, 8), WatchRoomUtils.dp(hostContext, 8), WatchRoomUtils.dp(hostContext, 8), WatchRoomUtils.dp(hostContext, 8))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        btnAppRewind15.addView(vAppRewindCircle); btnAppRewind15.addView(ivRewind)
        btnAppRewind15.setOnClickListener { pulseCircleFeedback(vAppRewindCircle); onSeekByDeltaMs(-15_000L) }
        centerRow.addView(btnAppRewind15)

        btnAppPlayPauseSlot = FrameLayout(hostContext).apply { layoutParams = LinearLayout.LayoutParams(WatchRoomUtils.dp(hostContext, 64), WatchRoomUtils.dp(hostContext, 64)) }
        vAppPlayPauseCircle = makeRedTouchCircle()
        ivAppCenterPlayPause = ImageView(hostContext).apply {
            setImageDrawable(VectorIcons.createPlayPauseIcon(hostContext, isPlaying = true))
            setPadding(WatchRoomUtils.dp(hostContext, 10), WatchRoomUtils.dp(hostContext, 10), WatchRoomUtils.dp(hostContext, 10), WatchRoomUtils.dp(hostContext, 10))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        pbAppCenterBuffering = ProgressBar(hostContext).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#E50914"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(WatchRoomUtils.dp(hostContext, 46), WatchRoomUtils.dp(hostContext, 46), Gravity.CENTER)
        }
        btnAppPlayPauseSlot.addView(vAppPlayPauseCircle); btnAppPlayPauseSlot.addView(ivAppCenterPlayPause); btnAppPlayPauseSlot.addView(pbAppCenterBuffering)
        btnAppPlayPauseSlot.setOnClickListener { pulseCircleFeedback(vAppPlayPauseCircle); onTogglePlayPause() }
        centerRow.addView(btnAppPlayPauseSlot)

        btnAppForward15 = FrameLayout(hostContext).apply { layoutParams = LinearLayout.LayoutParams(WatchRoomUtils.dp(hostContext, 52), WatchRoomUtils.dp(hostContext, 52)).apply { marginStart = WatchRoomUtils.dp(hostContext, 28) } }
        vAppForwardCircle = makeRedTouchCircle()
        val ivForward = ImageView(hostContext).apply {
            setImageDrawable(VectorIcons.createCircularSeekArrowIcon(hostContext, isForward = true))
            setPadding(WatchRoomUtils.dp(hostContext, 8), WatchRoomUtils.dp(hostContext, 8), WatchRoomUtils.dp(hostContext, 8), WatchRoomUtils.dp(hostContext, 8))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        btnAppForward15.addView(vAppForwardCircle); btnAppForward15.addView(ivForward)
        btnAppForward15.setOnClickListener { pulseCircleFeedback(vAppForwardCircle); onSeekByDeltaMs(15_000L) }
        centerRow.addView(btnAppForward15)

        appCustomControlsOverlay.addView(centerRow)

        val bottomSeekRow = LinearLayout(hostContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 4), WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 8))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }

        tvAppElapsed = TextView(hostContext).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
        }
        bottomSeekRow.addView(tvAppElapsed)

        sbAppRedSeekBar = SeekBar(hostContext).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(Color.parseColor("#E50914"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#E50914"))
            secondaryProgressTintList = ColorStateList.valueOf(Color.parseColor("#66FFFFFF"))
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#3DFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = WatchRoomUtils.dp(hostContext, 6); marginEnd = WatchRoomUtils.dp(hostContext, 6) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        resetAutoHideControls()
                        val dur = playerEngine.player?.duration ?: 0L
                        if (dur > 0) tvAppElapsed.text = WatchRoomUtils.formatTimeMs((dur * p) / 1000L)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {
                    isUserSeekingRedBar = true
                    appCustomControlsOverlay.removeCallbacks(autoHideControlsRunnable)
                }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    isUserSeekingRedBar = false
                    val exo = playerEngine.player
                    if (exo != null && exo.duration > 0) {
                        val targetPos = (exo.duration * (s?.progress ?: 0)) / 1000L
                        onSeekToExactMs(targetPos)
                    }
                    resetAutoHideControls()
                }
            })
        }
        bottomSeekRow.addView(sbAppRedSeekBar)

        tvAppDuration = TextView(hostContext).apply {
            text = "00:00"
            setTextColor(Color.parseColor("#D0D7E2"))
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = WatchRoomUtils.dp(hostContext, 10) }
        }
        bottomSeekRow.addView(tvAppDuration)

        btnAppFullscreenToggle = ImageView(hostContext).apply {
            setImageDrawable(VectorIcons.createFullscreenIcon(hostContext, isFullscreen = false))
            setPadding(WatchRoomUtils.dp(hostContext, 4), WatchRoomUtils.dp(hostContext, 4), WatchRoomUtils.dp(hostContext, 4), WatchRoomUtils.dp(hostContext, 4))
            layoutParams = LinearLayout.LayoutParams(WatchRoomUtils.dp(hostContext, 26), WatchRoomUtils.dp(hostContext, 26))
            setOnClickListener { toggleFullscreen(!isFullscreenPlayer) }
        }
        bottomSeekRow.addView(btnAppFullscreenToggle)

        appCustomControlsOverlay.addView(bottomSeekRow)
        playerFrameContainer.addView(appCustomControlsOverlay)

        val gestureDetector = GestureDetector(hostContext, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (appCustomControlsOverlay.isVisible && appCustomControlsOverlay.alpha > 0.5f) {
                    hideControlsSmoothly()
                } else {
                    showControlsWithFade()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = playerFrameContainer.width.coerceAtLeast(1)
                if (e.x < w * 0.48f) {
                    pulseCircleFeedback(vAppRewindCircle)
                    onSeekByDeltaMs(-15_000L)
                } else {
                    pulseCircleFeedback(vAppForwardCircle)
                    onSeekByDeltaMs(15_000L)
                }
                return true
            }
        })

        val touchListener = View.OnTouchListener { _, ev ->
            gestureDetector.onTouchEvent(ev)
            true
        }
        watchPlayerView.setOnTouchListener(touchListener)
        appCustomControlsOverlay.setOnTouchListener(touchListener)

        startProgressLoop()
    }

    private fun startProgressLoop() {
        progressTickJob?.cancel()
        progressTickJob = scope.launch {
            while (isActive && !isDestroyed) {
                delay(300)
                val p = playerEngine.player ?: continue

                updatePlayPauseIcon(p.isPlaying)
                if (p.playbackState == Player.STATE_BUFFERING) {
                    setBuffering(true)
                } else if (p.playbackState == Player.STATE_READY) {
                    setBuffering(false)
                }

                val pos = p.currentPosition.coerceAtLeast(0L)
                val dur = p.duration

                if (dur > 0 && !isUserSeekingRedBar) {
                    sbAppRedSeekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                    sbAppRedSeekBar.secondaryProgress = (p.bufferedPercentage * 10).coerceIn(0, 1000)
                    tvAppElapsed.text = WatchRoomUtils.formatTimeMs(pos)
                    tvAppDuration.text = WatchRoomUtils.formatTimeMs(dur)
                }

                subtitleManager.updateSubtitleTick(pos, p.isPlaying)
            }
        }
    }

    fun updateServerPillText(text: String) {
        if (this::btnAppServerPill.isInitialized) {
            btnAppServerPill.text = text
        }
    }

    fun updateQualityPillText(text: String) {
        if (this::btnAppQualityPill.isInitialized) {
            btnAppQualityPill.text = text
        }
    }

    fun updatePlayerTitleText(title: String) {
        if (this::tvAppPlayerTitle.isInitialized) {
            tvAppPlayerTitle.text = title
        }
    }

    fun toggleAspectRatio() {
        currentResizeModeIndex = (currentResizeModeIndex + 1) % 3
        watchPlayerView.resizeMode = when (currentResizeModeIndex) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        Toast.makeText(hostContext, "📐 تم تغيير أبعاد العرض", Toast.LENGTH_SHORT).show()
    }

    fun setBuffering(buffering: Boolean) {
        if (!this::pbAppCenterBuffering.isInitialized) return
        if (buffering) {
            ivAppCenterPlayPause.visibility = View.INVISIBLE
            pbAppCenterBuffering.visibility = View.VISIBLE
        } else {
            pbAppCenterBuffering.visibility = View.GONE
            ivAppCenterPlayPause.visibility = View.VISIBLE
        }
    }

    fun updatePlayPauseIcon(isPlaying: Boolean) {
        if (!this::ivAppCenterPlayPause.isInitialized) return
        ivAppCenterPlayPause.setImageDrawable(VectorIcons.createPlayPauseIcon(hostContext, isPlaying = isPlaying))
        onPlayStateTextUpdate(isPlaying)
    }

    fun resetProgressUI() {
        if (!this::tvAppElapsed.isInitialized) return
        tvAppElapsed.text = "00:00"
        tvAppDuration.text = "00:00"
        sbAppRedSeekBar.progress = 0
        subtitleManager.resetSubtitles()
    }

    fun updateSeekProgressUI(newPos: Long, dur: Long) {
        if (dur > 0 && this::sbAppRedSeekBar.isInitialized) {
            sbAppRedSeekBar.progress = ((newPos * 1000L) / dur).toInt().coerceIn(0, 1000)
            tvAppElapsed.text = WatchRoomUtils.formatTimeMs(newPos)
        }
    }

    private fun pulseCircleFeedback(circleView: View) {
        circleView.animate().cancel()
        circleView.alpha = 0.95f
        circleView.scaleX = 0.78f
        circleView.scaleY = 0.78f
        circleView.animate()
            .scaleX(1.08f)
            .scaleY(1.08f)
            .alpha(0f)
            .setDuration(300L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun showControlsWithFade() {
        if (!this::appCustomControlsOverlay.isInitialized) return
        appCustomControlsOverlay.removeCallbacks(autoHideControlsRunnable)
        appCustomControlsOverlay.animate().cancel()
        if (!appCustomControlsOverlay.isVisible) {
            appCustomControlsOverlay.alpha = 0f
            appCustomControlsOverlay.visibility = View.VISIBLE
        }
        appCustomControlsOverlay.animate().alpha(1f).setDuration(200L).start()
        scheduleAutoHideControls()
    }

    fun hideControlsSmoothly() {
        if (!this::appCustomControlsOverlay.isInitialized) return
        appCustomControlsOverlay.removeCallbacks(autoHideControlsRunnable)
        if (!appCustomControlsOverlay.isVisible) return
        appCustomControlsOverlay.animate().cancel()
        appCustomControlsOverlay.animate()
            .alpha(0f)
            .setDuration(200L)
            .withEndAction {
                appCustomControlsOverlay.visibility = View.GONE
                appCustomControlsOverlay.alpha = 1f
            }
            .start()
    }

    fun resetAutoHideControls() {
        if (!this::appCustomControlsOverlay.isInitialized) return
        appCustomControlsOverlay.removeCallbacks(autoHideControlsRunnable)
        if (!appCustomControlsOverlay.isVisible || appCustomControlsOverlay.alpha < 0.9f) {
            showControlsWithFade()
        } else {
            scheduleAutoHideControls()
        }
    }

    fun scheduleAutoHideControls() {
        if (!this::appCustomControlsOverlay.isInitialized) return
        appCustomControlsOverlay.removeCallbacks(autoHideControlsRunnable)
        if (playerEngine.isPlaying() && !isUserSeekingRedBar) {
            appCustomControlsOverlay.postDelayed(autoHideControlsRunnable, 3400L)
        }
    }

    fun toggleFullscreen(enterFullscreen: Boolean) {
        val act = hostActivity ?: return
        isFullscreenPlayer = enterFullscreen
        btnAppFullscreenToggle.setImageDrawable(VectorIcons.createFullscreenIcon(hostContext, isFullscreen = enterFullscreen))

        val lp = playerFrameContainer.layoutParams
        if (lp != null && originalPlayerHeightPx == 0 && lp.height > 0) {
            originalPlayerHeightPx = lp.height
        }

        if (enterFullscreen) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(act.window, false)
            WindowInsetsControllerCompat(act.window, rootView).hide(WindowInsetsCompat.Type.systemBars())
            if (lp != null) {
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                playerFrameContainer.layoutParams = lp
            }
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            WindowCompat.setDecorFitsSystemWindows(act.window, true)
            WindowInsetsControllerCompat(act.window, rootView).show(WindowInsetsCompat.Type.systemBars())
            if (lp != null) {
                lp.height = if (originalPlayerHeightPx > 0) originalPlayerHeightPx else WatchRoomUtils.dp(hostContext, 225)
                playerFrameContainer.layoutParams = lp
            }
        }
    }

    private fun showServersPopup() {
        val providers = getProviderTargets().ifEmpty { playerEngine.getSources() }
        if (providers.isEmpty()) {
            Toast.makeText(hostContext, "السيرفر الحالي شغال", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(hostContext)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = WatchRoomUtils.dp(hostContext, 275)
        val card = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            background = WatchRoomUtils.makeRoundedBg(hostContext, Color.parseColor("#EE10131A"), 18f, Color.parseColor("#33FFFFFF"), 1f)
            setPadding(WatchRoomUtils.dp(hostContext, 18), WatchRoomUtils.dp(hostContext, 16), WatchRoomUtils.dp(hostContext, 18), WatchRoomUtils.dp(hostContext, 14))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = TextView(hostContext).apply {
            text = "Servers"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(WatchRoomUtils.dp(hostContext, 4), 0, WatchRoomUtils.dp(hostContext, 4), WatchRoomUtils.dp(hostContext, 10))
        }
        card.addView(header)

        val scroll = ScrollView(hostContext)
        val listCol = LinearLayout(hostContext).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listCol)
        card.addView(scroll)

        val currentIdx = getCurrentProviderIndex()
        providers.forEachIndexed { idx, srv ->
            val isSel = idx == currentIdx
            val row = LinearLayout(hostContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isSel) WatchRoomUtils.makeRoundedBg(hostContext, Color.parseColor("#26FFFFFF"), 10f, Color.parseColor("#55FFFFFF"), 1f) else null
                setPadding(WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 10), WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 10))
                setOnClickListener {
                    dialog.dismiss()
                    onServerSelected(idx, srv)
                }
            }
            val nameTv = TextView(hostContext).apply {
                text = srv.label.ifBlank { "REYZO ${idx + 1}" }
                setTextColor(if (isSel) Color.WHITE else Color.parseColor("#CBD5E1"))
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameTv)
            if (isSel) {
                val ivCheck = ImageView(hostContext).apply {
                    setImageDrawable(VectorIcons.createCheckMarkIcon(hostContext, Color.WHITE))
                    layoutParams = LinearLayout.LayoutParams(WatchRoomUtils.dp(hostContext, 16), WatchRoomUtils.dp(hostContext, 16))
                }
                row.addView(ivCheck)
            }
            listCol.addView(row)
        }

        dialog.setContentView(card)
        dialog.show()
        dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showQualityPopup() {
        val dialog = Dialog(hostContext)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = WatchRoomUtils.dp(hostContext, 260)
        val card = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            background = WatchRoomUtils.makeRoundedBg(hostContext, Color.parseColor("#EE10131A"), 18f, Color.parseColor("#33FFFFFF"), 1f)
            setPadding(WatchRoomUtils.dp(hostContext, 18), WatchRoomUtils.dp(hostContext, 16), WatchRoomUtils.dp(hostContext, 18), WatchRoomUtils.dp(hostContext, 14))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = TextView(hostContext).apply {
            text = "Quality"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(WatchRoomUtils.dp(hostContext, 4), 0, WatchRoomUtils.dp(hostContext, 4), WatchRoomUtils.dp(hostContext, 10))
        }
        card.addView(header)

        val realQualities = playerEngine.getAvailableQualityTracks()
        val detectedBadge = playerEngine.getCurrentDetectedQualityLabel()

        val isAutoSelected = selectedQualityHeight == null
        val autoRow = TextView(hostContext).apply {
            text = "Auto ($detectedBadge)"
            setTextColor(if (isAutoSelected) Color.WHITE else Color.parseColor("#CBD5E1"))
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            background = if (isAutoSelected) WatchRoomUtils.makeRoundedBg(hostContext, Color.parseColor("#26FFFFFF"), 10f, Color.parseColor("#55FFFFFF"), 1f) else null
            setPadding(WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 10), WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 10))
            setOnClickListener {
                selectedQualityHeight = null
                playerEngine.selectVideoQualityHeight(null)
                updateQualityPillText("  $detectedBadge")
                dialog.dismiss()
            }
        }
        card.addView(autoRow)

        realQualities.forEach { qTrack ->
            val qLabel = when {
                qTrack.height >= 2160 -> "4K Ultra HD (2160p)"
                qTrack.height >= 1080 -> "Full HD (1080p)"
                qTrack.height >= 720 -> "HD (720p)"
                qTrack.height >= 480 -> "SD (480p)"
                else -> "${qTrack.height}p"
            }
            val isSel = selectedQualityHeight == qTrack.height
            val row = TextView(hostContext).apply {
                text = qLabel
                setTextColor(if (isSel) Color.WHITE else Color.parseColor("#CBD5E1"))
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                background = if (isSel) WatchRoomUtils.makeRoundedBg(hostContext, Color.parseColor("#26FFFFFF"), 10f, Color.parseColor("#55FFFFFF"), 1f) else null
                setPadding(WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 10), WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 10))
                setOnClickListener {
                    selectedQualityHeight = qTrack.height
                    playerEngine.selectVideoQualityHeight(qTrack.height)
                    updateQualityPillText("  ${qTrack.height}p")
                    dialog.dismiss()
                }
            }
            card.addView(row)
        }

        dialog.setContentView(card)
        dialog.show()
        dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun release() {
        isDestroyed = true
        progressTickJob?.cancel()
        if (this::appCustomControlsOverlay.isInitialized) {
            appCustomControlsOverlay.removeCallbacks(autoHideControlsRunnable)
        }
    }
}