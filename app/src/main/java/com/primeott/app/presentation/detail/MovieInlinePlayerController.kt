package com.primeott.app.presentation.detail

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.primeott.app.R
import com.primeott.app.domain.model.StreamSource
import com.primeott.app.domain.model.SubtitleCue
import com.primeott.app.domain.model.SubtitleTrack
import com.primeott.app.presentation.player.PrimePlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class MovieInlinePlayerController(
    private val activity: AppCompatActivity,
    private val scope: LifecycleCoroutineScope,
    private val fontBold: Typeface,
    private val fontRegular: Typeface,
    private val onMinimizeToCover: () -> Unit,
    private val onFullscreenChanged: (Boolean) -> Unit,
    private val onServerSelected: (Int, StreamSource) -> Unit,
    private val onRequestRefreshSubtitles: (onResult: (List<SubtitleTrack>) -> Unit) -> Unit,
    private val onDownloadSubtitleTrack: suspend (SubtitleTrack) -> List<SubtitleCue>,
    private val onPickLocalSubtitleFile: () -> Unit,
    private val onSaveProgressTick: (positionMs: Long, durationMs: Long) -> Unit
) {

    companion object {
        private const val PREFS_SUB_STYLE = "prime_sub_style_prefs"
    }

    lateinit var playerContainer: FrameLayout
        private set
    lateinit var playerView: PlayerView
        private set

    private lateinit var tvSubtitleOverlay: TextView
    private lateinit var vTopEdgeSoftGradient: View
    private lateinit var vBottomPlayerSeparator: View

    private lateinit var controlsOverlay: FrameLayout
    private lateinit var btnMinimizePlayer: ImageView
    private lateinit var tvTopPlayerTitle: TextView
    private lateinit var btnTopPip: ImageView
    private lateinit var btnTopLock: ImageView
    private lateinit var btnTopServerOnly: TextView
    private lateinit var btnTopQualityOnly: TextView
    private lateinit var btnTopSubtitles: ImageView

    private lateinit var btnRewind15Container: FrameLayout
    private lateinit var btnRewind15BgCircle: View
    private lateinit var btnRewind15: ImageView

    private lateinit var centerActionSlot: FrameLayout
    private lateinit var btnCenterPlayPauseBgCircle: View
    private lateinit var btnCenterPlayPause: ImageView
    private lateinit var centerBufferingSpinner: ProgressBar

    private lateinit var btnForward15Container: FrameLayout
    private lateinit var btnForward15BgCircle: View
    private lateinit var btnForward15: ImageView

    private lateinit var holdSpeed2xPill: LinearLayout
    private lateinit var btnSkipIntro: LinearLayout

    private lateinit var gestureIndicatorCard: LinearLayout
    private lateinit var fullscreenLeftBrightnessBar: LinearLayout
    private lateinit var pbSideBrightness: ProgressBar
    private lateinit var tvSideBrightnessPct: TextView
    private lateinit var fullscreenRightVolumeBar: LinearLayout
    private lateinit var pbSideVolume: ProgressBar
    private lateinit var tvSideVolumePct: TextView
    private lateinit var ivGestureIcon: ImageView
    private lateinit var pbGestureLevel: ProgressBar
    private lateinit var tvGesturePercent: TextView

    private lateinit var tvElapsed: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvDuration: TextView
    private lateinit var btnInlineFullscreen: ImageView
    private lateinit var fullscreenBottomActionsRow: LinearLayout
    private lateinit var btnBottomSubtitles: LinearLayout
    private lateinit var btnBottomAudio: LinearLayout
    private lateinit var btnBottomSpeed: LinearLayout
    private lateinit var tvBottomSpeedLabel: TextView
    private lateinit var btnBottomAspect: LinearLayout
    private lateinit var tvBottomAspectLabel: TextView
    private lateinit var btnUnlockScreenOnly: LinearLayout

    var isFullscreen: Boolean = false
        private set
    private var isInPipMode: Boolean = false
    private var isControlsLocked: Boolean = false
    private var isUserSeeking: Boolean = false
    private var isHold2xActive: Boolean = false
    private var isBufferingNow: Boolean = true
    private var hasRenderedInitialFrameForStream: Boolean = false
    private var currentPlaybackSpeed: Float = 1.0f

    // اللون الديناميكي المأخوذ من ثيم الغلاف لتأثير ضغط الأزرار
    private var dynamicThemeAccentColor: Int = "#E50914".toColorInt()

    private var isCurrentMediaSeries: Boolean = false
    private var detectedIntroStartMs: Long = -1L
    private var detectedIntroEndMs: Long = -1L
    private var hasSkippedIntroForCurrentMedia: Boolean = false
    private var selectedQualityHeight: Int? = null

    private var availableSubtitles: List<SubtitleTrack> = emptyList()
    private var activeSubtitleCues: List<SubtitleCue> = emptyList()
    private var selectedSubtitleId: String = ""
    private var subtitleOffsetMs: Long = 0L
    private var subtitleTextSizeSp: Float = 16f
    private var subtitleTextColor: Int = Color.WHITE
    private var subtitleBgAlpha: Int = 0
    private var subtitleExtraLiftDp: Int = 0

    private var resizeModeIndex: Int = 0
    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeLabels = listOf("Fit", "Stretch", "Zoom")

    private var progressJob: Job? = null
    private var lastSavedTick: Int = 0
    private var attachedPlayer: Player? = null

    private val audioManager: AudioManager by lazy {
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var swipeMode: Int = 0
    private var swipeStartY: Float = 0f
    private var swipeStartX: Float = 0f
    private var initialBrightness: Float = 0.6f
    private var initialVolume: Float = 0f
    private var currentGestureValue: Float = 0f

    private val playerStateListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseVectorIcon(isPlaying)
            if (isPlaying && attachedPlayer?.playbackState == Player.STATE_READY) {
                setBufferingState(false)
                scheduleAutoHideIfPlaying()
            } else if (!isPlaying) {
                // عند الإيقاف المؤقت لا نخفي الأزرار تلقائياً
                controlsOverlay.removeCallbacks(autoHideRunnable)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    setBufferingState(true)
                }
                Player.STATE_READY -> {
                    setBufferingState(false)
                    val playing = attachedPlayer?.isPlaying == true
                    updatePlayPauseVectorIcon(playing)
                    if (playing) {
                        scheduleAutoHideIfPlaying()
                    }
                }
                Player.STATE_ENDED -> {
                    setBufferingState(false)
                    updatePlayPauseVectorIcon(false)
                }
                Player.STATE_IDLE -> {}
            }
        }
    }

    private val autoHideRunnable = Runnable {
        // لا نخفي التحكم أبداً إذا كان الفيديو ما يزال في طور التحميل (Buffering)
        if (!isBufferingNow && attachedPlayer?.isPlaying == true && !isUserSeeking) {
            hideControlsSmoothly()
        }
    }

    private val hideGestureIndicatorRunnable = Runnable {
        gestureIndicatorCard.animate()
            .alpha(0f)
            .setDuration(160L)
            .withEndAction { gestureIndicatorCard.visibility = View.GONE }
            .start()
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun dpF(value: Float): Float = value * activity.resources.displayMetrics.density

    private fun makeRoundedBg(
        fillColor: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 1f
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(fillColor)
            if (strokeColor != null) {
                setStroke(max(1, dpF(strokeWidthDp).toInt()), strokeColor)
            }
        }
    }

    fun bindFromXml(rootPlayerFrame: FrameLayout) {
        val prefs = activity.getSharedPreferences(PREFS_SUB_STYLE, Context.MODE_PRIVATE)
        subtitleTextSizeSp = prefs.getFloat("sub_size_sp", 16f).coerceIn(12f, 26f)
        subtitleTextColor = prefs.getInt("sub_text_color", Color.WHITE)
        subtitleBgAlpha = prefs.getInt("sub_bg_alpha", 0)
        subtitleExtraLiftDp = prefs.getInt("sub_extra_lift_dp", 0).coerceIn(-10, 90)

        playerContainer = rootPlayerFrame
        playerView = rootPlayerFrame.findViewById(R.id.playerView)
        vTopEdgeSoftGradient = rootPlayerFrame.findViewById(R.id.vTopEdgeSoftGradient)
        tvSubtitleOverlay = rootPlayerFrame.findViewById(R.id.tvSubtitleOverlay)
        holdSpeed2xPill = rootPlayerFrame.findViewById(R.id.holdSpeed2xPill)
        gestureIndicatorCard = rootPlayerFrame.findViewById(R.id.gestureIndicatorCard)
        ivGestureIcon = rootPlayerFrame.findViewById(R.id.ivGestureIcon)
        pbGestureLevel = rootPlayerFrame.findViewById(R.id.pbGestureLevel)
        tvGesturePercent = rootPlayerFrame.findViewById(R.id.tvGesturePercent)
        btnSkipIntro = rootPlayerFrame.findViewById(R.id.btnSkipIntro)

        controlsOverlay = rootPlayerFrame.findViewById(R.id.controlsOverlay)
        btnMinimizePlayer = rootPlayerFrame.findViewById(R.id.btnMinimizePlayer)
        tvTopPlayerTitle = rootPlayerFrame.findViewById(R.id.tvTopPlayerTitle)
        btnTopServerOnly = rootPlayerFrame.findViewById(R.id.btnTopServerOnly)
        btnTopQualityOnly = rootPlayerFrame.findViewById(R.id.btnTopQualityOnly)
        btnTopSubtitles = rootPlayerFrame.findViewById(R.id.btnTopSubtitles)
        btnTopPip = rootPlayerFrame.findViewById(R.id.btnTopPip)
        btnTopLock = rootPlayerFrame.findViewById(R.id.btnTopLock)

        btnRewind15Container = rootPlayerFrame.findViewById(R.id.btnRewind15Container)
        btnRewind15BgCircle = rootPlayerFrame.findViewById(R.id.btnRewind15BgCircle)
        btnRewind15 = rootPlayerFrame.findViewById(R.id.btnRewind15)

        centerActionSlot = rootPlayerFrame.findViewById(R.id.centerActionSlot)
        btnCenterPlayPauseBgCircle = rootPlayerFrame.findViewById(R.id.btnCenterPlayPauseBgCircle)
        btnCenterPlayPause = rootPlayerFrame.findViewById(R.id.btnCenterPlayPause)
        centerBufferingSpinner = rootPlayerFrame.findViewById(R.id.centerBufferingSpinner)

        btnForward15Container = rootPlayerFrame.findViewById(R.id.btnForward15Container)
        btnForward15BgCircle = rootPlayerFrame.findViewById(R.id.btnForward15BgCircle)
        btnForward15 = rootPlayerFrame.findViewById(R.id.btnForward15)

        tvElapsed = rootPlayerFrame.findViewById(R.id.tvElapsed)
        seekBar = rootPlayerFrame.findViewById(R.id.seekBar)
        tvDuration = rootPlayerFrame.findViewById(R.id.tvDuration)
        btnInlineFullscreen = rootPlayerFrame.findViewById(R.id.btnInlineFullscreen)
        fullscreenBottomActionsRow = rootPlayerFrame.findViewById(R.id.fullscreenBottomActionsRow)
        btnBottomSubtitles = rootPlayerFrame.findViewById(R.id.btnBottomSubtitles)
        btnBottomAudio = rootPlayerFrame.findViewById(R.id.btnBottomAudio)
        btnBottomSpeed = rootPlayerFrame.findViewById(R.id.btnBottomSpeed)
        tvBottomSpeedLabel = rootPlayerFrame.findViewById(R.id.tvBottomSpeedLabel)
        btnBottomAspect = rootPlayerFrame.findViewById(R.id.btnBottomAspect)
        tvBottomAspectLabel = rootPlayerFrame.findViewById(R.id.tvBottomAspectLabel)
        vBottomPlayerSeparator = rootPlayerFrame.findViewById(R.id.vBottomPlayerSeparator)
        btnUnlockScreenOnly = rootPlayerFrame.findViewById(R.id.btnUnlockScreenOnly)

        vTopEdgeSoftGradient.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf("#75000000".toColorInt(), Color.TRANSPARENT)
        )
        controlsOverlay.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf("#A6000000".toColorInt(), "#33000000".toColorInt(), "#BF000000".toColorInt())
        )
        vBottomPlayerSeparator.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.TRANSPARENT,
                "#45FFFFFF".toColorInt(),
                "#25FFFFFF".toColorInt(),
                Color.TRANSPARENT
            )
        )

        holdSpeed2xPill.background = makeRoundedBg("#D910131C".toColorInt(), 20f, "#4DFFFFFF".toColorInt(), 1f)
        rootPlayerFrame.findViewById<ImageView>(R.id.ivHoldFastIcon)
            .setImageDrawable(VectorIcons.createFastForwardDoubleArrowIcon(activity, Color.WHITE))

        gestureIndicatorCard.background = makeRoundedBg("#E010131C".toColorInt(), 20f, "#40FFFFFF".toColorInt(), 1f)

        btnSkipIntro.background = makeRoundedBg("#E612151E".toColorInt(), 18f, "#CCFFFFFF".toColorInt(), 1.1f)
        rootPlayerFrame.findViewById<ImageView>(R.id.ivSkipIntroArrow)
            .setImageDrawable(VectorIcons.createSkipNextIcon(activity, Color.WHITE))

        btnMinimizePlayer.background = makeRoundedBg("#2EFFFFFF".toColorInt(), 18f)
        btnMinimizePlayer.setOnClickListener {
            if (isFullscreen) applyFullscreenState(false) else onMinimizeToCover()
        }

        fullscreenLeftBrightnessBar = rootPlayerFrame.findViewById(R.id.fullscreenLeftBrightnessBar)
        pbSideBrightness = rootPlayerFrame.findViewById(R.id.pbSideBrightness)
        tvSideBrightnessPct = rootPlayerFrame.findViewById(R.id.tvSideBrightnessPct)
        fullscreenRightVolumeBar = rootPlayerFrame.findViewById(R.id.fullscreenRightVolumeBar)
        pbSideVolume = rootPlayerFrame.findViewById(R.id.pbSideVolume)
        tvSideVolumePct = rootPlayerFrame.findViewById(R.id.tvSideVolumePct)

        fullscreenLeftBrightnessBar.background = makeRoundedBg("#B310131C".toColorInt(), 18f, "#33FFFFFF".toColorInt(), 1f)
        fullscreenRightVolumeBar.background = makeRoundedBg("#B310131C".toColorInt(), 18f, "#33FFFFFF".toColorInt(), 1f)
        rootPlayerFrame.findViewById<ImageView>(R.id.ivSideBrightnessIcon)
            .setImageDrawable(VectorIcons.createBrightnessSunIcon(activity, Color.WHITE))
        rootPlayerFrame.findViewById<ImageView>(R.id.ivSideVolumeIcon)
            .setImageDrawable(VectorIcons.createAudioSpeakerIcon(activity, Color.WHITE))

        btnTopServerOnly.background = makeRoundedBg("#2EFFFFFF".toColorInt(), 8f, "#55FFFFFF".toColorInt(), 0.9f)
        btnTopQualityOnly.background = makeRoundedBg("#2EFFFFFF".toColorInt(), 8f, "#55FFFFFF".toColorInt(), 0.9f)
        val srvIcon = VectorIcons.createSmallServerStackIcon(activity, Color.WHITE).apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
        }
        val qIcon = VectorIcons.createSmallQualitySparkIcon(activity, Color.WHITE).apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
        }
        btnTopServerOnly.setCompoundDrawables(srvIcon, null, null, null)
        btnTopQualityOnly.setCompoundDrawables(qIcon, null, null, null)

        btnTopSubtitles.background = makeRoundedBg("#2EFFFFFF".toColorInt(), 8f, "#4DFFFFFF".toColorInt(), 0.8f)
        btnTopSubtitles.setImageDrawable(VectorIcons.createSubtitlesCcIcon(activity, Color.WHITE))

        btnTopPip.background = makeRoundedBg("#2EFFFFFF".toColorInt(), 8f, "#4DFFFFFF".toColorInt(), 0.8f)
        btnTopPip.setImageDrawable(VectorIcons.createPipMiniPlayerIcon(activity, Color.WHITE))
        btnTopPip.setOnClickListener { enterMiniPipMode() }

        btnTopLock.background = makeRoundedBg("#2EFFFFFF".toColorInt(), 8f, "#4DFFFFFF".toColorInt(), 0.8f)
        btnTopLock.setImageDrawable(VectorIcons.createLockIcon(activity, Color.WHITE, locked = false))
        btnTopLock.setOnClickListener {
            isControlsLocked = true
            hideControlsSmoothly()
            btnUnlockScreenOnly.visibility = View.VISIBLE
        }

        updateTouchCirclesThemeColor(dynamicThemeAccentColor)

        // أيقونات التقديم والترجيع مكتوب داخلها 15s بوضوح
        btnRewind15.setImageDrawable(VectorIcons.createCircularSeekArrowIcon(activity, isForward = false))
        btnCenterPlayPause.setImageDrawable(VectorIcons.createPlayPauseIcon(activity, isPlaying = true))
        btnForward15.setImageDrawable(VectorIcons.createCircularSeekArrowIcon(activity, isForward = true))

        btnInlineFullscreen.setImageDrawable(VectorIcons.createFullscreenIcon(activity, isFullscreen = false))
        btnInlineFullscreen.setOnClickListener { applyFullscreenState(!isFullscreen) }

        val chipBg = { makeRoundedBg("#24FFFFFF".toColorInt(), 16f, "#40FFFFFF".toColorInt(), 0.8f) }
        btnBottomSubtitles.background = chipBg()
        btnBottomAudio.background = chipBg()
        btnBottomSpeed.background = chipBg()
        btnBottomAspect.background = chipBg()

        rootPlayerFrame.findViewById<ImageView>(R.id.ivBottomSubIcon)
            .setImageDrawable(VectorIcons.createSubtitlesCcIcon(activity, Color.WHITE))
        rootPlayerFrame.findViewById<ImageView>(R.id.ivBottomAudioIcon)
            .setImageDrawable(VectorIcons.createAudioSpeakerIcon(activity, Color.WHITE))
        rootPlayerFrame.findViewById<ImageView>(R.id.ivBottomAspectIcon)
            .setImageDrawable(VectorIcons.createAspectRatioIcon(activity, Color.WHITE))

        btnBottomAspect.setOnClickListener {
            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
            playerView.resizeMode = resizeModes[resizeModeIndex]
            tvBottomAspectLabel.text = "Scale: ${resizeLabels[resizeModeIndex]}"
        }

        btnUnlockScreenOnly.background = makeRoundedBg("#CC000000".toColorInt(), 20f, "#99FFFFFF".toColorInt(), 1f)
        rootPlayerFrame.findViewById<ImageView>(R.id.ivUnlockIcon)
            .setImageDrawable(VectorIcons.createLockIcon(activity, Color.WHITE, locked = true))
        btnUnlockScreenOnly.setOnClickListener {
            isControlsLocked = false
            btnUnlockScreenOnly.visibility = View.GONE
            showControlsWithFade()
        }

        applySubtitleStyleToOverlay()
        animateSubtitlePositionForControls(controlsVisible = true, animate = false)
    }

    /**
     * تحديث لون الدوائر التفاعلية (Play/Pause و 15s) وشريط التقدم ليتطابق مع لون الثيم الديناميكي تحت المشغل
     */
    fun setDynamicThemeColor(themeColor: Int) {
        val vividAccent = ColorUtils.blendARGB(themeColor, Color.WHITE, 0.15f)
        dynamicThemeAccentColor = vividAccent
        if (this::btnCenterPlayPauseBgCircle.isInitialized) {
            updateTouchCirclesThemeColor(vividAccent)
        }
    }

    private fun updateTouchCirclesThemeColor(color: Int) {
        val circleAlphaColor = ColorUtils.setAlphaComponent(color, 195)
        val makeCircle = {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(circleAlphaColor)
            }
        }
        btnRewind15BgCircle.background = makeCircle()
        btnCenterPlayPauseBgCircle.background = makeCircle()
        btnForward15BgCircle.background = makeCircle()
    }

    private fun setBufferingState(buffering: Boolean) {
        isBufferingNow = buffering
        if (buffering) {
            btnCenterPlayPause.visibility = View.INVISIBLE
            centerBufferingSpinner.visibility = View.VISIBLE
            // إبقاء واجهة التحكم والـ Loader ظاهرة طوال فترة التحميل وعدم إخفائها
            controlsOverlay.removeCallbacks(autoHideRunnable)
            if (!controlsOverlay.isVisible && !isInPipMode && !isControlsLocked) {
                controlsOverlay.alpha = 1f
                controlsOverlay.visibility = View.VISIBLE
                animateSubtitlePositionForControls(controlsVisible = true, animate = true)
            }
        } else {
            centerBufferingSpinner.visibility = View.GONE
            btnCenterPlayPause.visibility = View.VISIBLE
        }
    }

    fun onFirstFrameRendered() {
        setBufferingState(false)
        updatePlayPauseVectorIcon(true)
        // نخفي الأزرار تلقائياً فقط عند بداية اشتغال الفيلم لأول مرة، وليس عند التقديم بـ 15s
        if (!hasRenderedInitialFrameForStream) {
            hasRenderedInitialFrameForStream = true
            controlsOverlay.removeCallbacks(autoHideRunnable)
            controlsOverlay.postDelayed(autoHideRunnable, 1200L)
        }
    }

    private fun scheduleAutoHideIfPlaying() {
        controlsOverlay.removeCallbacks(autoHideRunnable)
        if (!isBufferingNow && attachedPlayer?.isPlaying == true && !isUserSeeking) {
            controlsOverlay.postDelayed(autoHideRunnable, 3400L)
        }
    }

    private fun pulseCircleFeedback(circleView: View) {
        circleView.animate().cancel()
        circleView.alpha = 0.95f
        circleView.scaleX = 0.78f
        circleView.scaleY = 0.78f
        circleView.animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .alpha(0f)
            .setDuration(320L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateSubtitlePositionForControls(controlsVisible: Boolean, animate: Boolean = true) {
        val baseControlsLift = if (controlsVisible) {
            if (isFullscreen) -dpF(62f) else -dpF(34f)
        } else {
            0f
        }
        val userCustomLift = -dpF(subtitleExtraLiftDp.toFloat())
        val targetLiftY = baseControlsLift + userCustomLift

        tvSubtitleOverlay.animate().cancel()
        if (animate) {
            tvSubtitleOverlay.animate()
                .translationY(targetLiftY)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            tvSubtitleOverlay.translationY = targetLiftY
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun bindEngineAndActions(
        engine: PrimePlayerEngine,
        getProviders: () -> List<StreamSource>,
        getCurrentProviderIdx: () -> Int
    ) {
        centerActionSlot.setOnClickListener {
            pulseCircleFeedback(btnCenterPlayPauseBgCircle)
            val exo = engine.player
            if (exo != null && exo.isPlaying) {
                engine.pause()
                updatePlayPauseVectorIcon(false)
                controlsOverlay.removeCallbacks(autoHideRunnable)
            } else {
                engine.resume()
                updatePlayPauseVectorIcon(true)
                resetAutoHide()
            }
        }

        btnRewind15Container.setOnClickListener {
            resetAutoHide()
            pulseCircleFeedback(btnRewind15BgCircle)
            seekByDeltaMs(engine, -15_000L)
        }

        btnForward15Container.setOnClickListener {
            resetAutoHide()
            pulseCircleFeedback(btnForward15BgCircle)
            seekByDeltaMs(engine, 15_000L)
        }

        btnSkipIntro.setOnClickListener {
            val exo = engine.player ?: return@setOnClickListener
            hasSkippedIntroForCurrentMedia = true
            btnSkipIntro.visibility = View.GONE
            val dur = exo.duration
            val targetPos = if (detectedIntroEndMs > exo.currentPosition) {
                detectedIntroEndMs
            } else {
                exo.currentPosition + 80_000L
            }.let { t ->
                if (dur > 0) min(t, (dur - 5000L).coerceAtLeast(0L)) else t
            }
            exo.seekTo(targetPos)
            if (dur > 0) {
                seekBar.progress = ((targetPos * 1000L) / dur).toInt().coerceIn(0, 1000)
                tvElapsed.text = formatTimeMs(targetPos)
            }
        }

        btnTopServerOnly.setOnClickListener {
            showServersOnlyPopup(getProviders(), getCurrentProviderIdx())
        }

        btnTopQualityOnly.setOnClickListener {
            showQualityOnlyPopup(engine)
        }

        btnBottomAudio.setOnClickListener {
            showAudioTracksDialog(engine)
        }

        val openSubsAction = {
            showSubtitlesDialog()
        }
        btnTopSubtitles.setOnClickListener { openSubsAction() }
        btnBottomSubtitles.setOnClickListener { openSubsAction() }

        btnBottomSpeed.setOnClickListener {
            showPlaybackSpeedDialog(engine)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    resetAutoHide()
                    val exo = engine.player ?: return
                    val dur = exo.duration
                    if (dur > 0) {
                        tvElapsed.text = formatTimeMs((dur * p) / 1000L)
                    }
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {
                isUserSeeking = true
                controlsOverlay.removeCallbacks(autoHideRunnable)
            }
            override fun onStopTrackingTouch(s: SeekBar?) {
                isUserSeeking = false
                val exo = engine.player ?: return
                val dur = exo.duration
                if (dur > 0) {
                    exo.seekTo((dur * (s?.progress ?: 0)) / 1000L)
                }
                resetAutoHide()
            }
        })

        val gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isControlsLocked) {
                    btnUnlockScreenOnly.isVisible = !btnUnlockScreenOnly.isVisible
                } else {
                    if (controlsOverlay.isVisible && controlsOverlay.alpha > 0.5f && !isBufferingNow) {
                        hideControlsSmoothly()
                    } else {
                        showControlsWithFade()
                    }
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isControlsLocked) {
                    val width = playerContainer.width.coerceAtLeast(1)
                    if (e.x < width * 0.48f) {
                        resetAutoHide()
                        pulseCircleFeedback(btnRewind15BgCircle)
                        seekByDeltaMs(engine, -15_000L)
                    } else {
                        resetAutoHide()
                        pulseCircleFeedback(btnForward15BgCircle)
                        seekByDeltaMs(engine, 15_000L)
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isControlsLocked && swipeMode == 0 && engine.isPlaying()) {
                    isHold2xActive = true
                    engine.player?.playbackParameters = PlaybackParameters(2.0f)
                    holdSpeed2xPill.alpha = 0f
                    holdSpeed2xPill.visibility = View.VISIBLE
                    holdSpeed2xPill.animate().alpha(1f).setDuration(150L).start()
                }
            }
        })

        val touchHandler = View.OnTouchListener { v, ev ->
            gestureDetector.onTouchEvent(ev)

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeMode = 0
                    swipeStartX = ev.x
                    swipeStartY = ev.y
                    val winBright = activity.window.attributes.screenBrightness
                    initialBrightness = if (winBright in 0.01f..1.0f) winBright else 0.55f
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    initialVolume = curVol.toFloat() / maxVol.toFloat()
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isControlsLocked && !isHold2xActive) {
                        val dy = swipeStartY - ev.y
                        val dx = abs(ev.x - swipeStartX)
                        val touchSlop = dp(16)

                        if (swipeMode == 0 && abs(dy) > touchSlop && abs(dy) > dx * 1.35f) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            val width = playerView.width.coerceAtLeast(1)
                            swipeMode = if (swipeStartX < width * 0.5f) 1 else 2
                            swipeStartY = ev.y
                        }

                        if (swipeMode != 0) {
                            val height = playerView.height.coerceAtLeast(dp(180)).toFloat()
                            val deltaPercent = (swipeStartY - ev.y) / (height * 0.72f)

                            if (swipeMode == 1) {
                                currentGestureValue = (initialBrightness + deltaPercent).coerceIn(0.05f, 1.0f)
                                val lp = activity.window.attributes
                                lp.screenBrightness = currentGestureValue
                                activity.window.attributes = lp
                                showSwipeGestureIndicator(
                                    isBrightness = true,
                                    percent = (currentGestureValue * 100).toInt()
                                )
                            } else if (swipeMode == 2) {
                                currentGestureValue = (initialVolume + deltaPercent).coerceIn(0f, 1.0f)
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                val targetVol = (currentGestureValue * maxVol).toInt().coerceIn(0, maxVol)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                showSwipeGestureIndicator(
                                    isBrightness = false,
                                    percent = (currentGestureValue * 100).toInt()
                                )
                            }
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (swipeMode != 0) {
                        swipeMode = 0
                        playerContainer.removeCallbacks(hideGestureIndicatorRunnable)
                        playerContainer.postDelayed(hideGestureIndicatorRunnable, 450L)
                    }
                    if (isHold2xActive) {
                        isHold2xActive = false
                        engine.player?.playbackParameters = PlaybackParameters(currentPlaybackSpeed)
                        holdSpeed2xPill.animate().alpha(0f).setDuration(150L).withEndAction {
                            holdSpeed2xPill.visibility = View.GONE
                        }.start()
                    }
                }
            }
            true
        }

        playerView.setOnTouchListener(touchHandler)
        controlsOverlay.setOnTouchListener(touchHandler)
    }

    private fun showSwipeGestureIndicator(isBrightness: Boolean, percent: Int) {
        playerContainer.removeCallbacks(hideGestureIndicatorRunnable)
        val clamped = percent.coerceIn(0, 100)
        ivGestureIcon.setImageDrawable(
            if (isBrightness) {
                VectorIcons.createBrightnessSunIcon(activity, Color.WHITE)
            } else {
                VectorIcons.createAudioSpeakerIcon(activity, Color.WHITE)
            }
        )
        pbGestureLevel.progress = clamped
        tvGesturePercent.text = "$clamped%"
        if (isBrightness) {
            pbSideBrightness.progress = clamped
            tvSideBrightnessPct.text = "$clamped%"
        } else {
            pbSideVolume.progress = clamped
            tvSideVolumePct.text = "$clamped%"
        }
        if (!gestureIndicatorCard.isVisible) {
            gestureIndicatorCard.alpha = 1f
            gestureIndicatorCard.visibility = View.VISIBLE
        }
    }

    fun startProgressAndNetworkLoop(engine: PrimePlayerEngine) {
        progressJob?.cancel()

        progressJob = scope.launch {
            while (isActive) {
                delay(300)
                if (!playerContainer.isVisible) continue

                val p = engine.player
                if (p != null && p !== attachedPlayer) {
                    attachedPlayer?.removeListener(playerStateListener)
                    attachedPlayer = p
                    p.addListener(playerStateListener)
                    updatePlayPauseVectorIcon(p.isPlaying)
                }

                if (p == null) continue
                if (p.isPlaying && isBufferingNow && p.playbackState == Player.STATE_READY) {
                    setBufferingState(false)
                }

                val pos = p.currentPosition.coerceAtLeast(0L)
                val dur = p.duration

                if (dur > 0) {
                    if (!isUserSeeking && controlsOverlay.isVisible) {
                        seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                        seekBar.secondaryProgress = (p.bufferedPercentage * 10).coerceIn(0, 1000)
                        tvElapsed.text = formatTimeMs(pos)
                        tvDuration.text = formatTimeMs(dur)
                    }

                    val hasValidIntroWindow = detectedIntroStartMs >= 0L && detectedIntroEndMs > detectedIntroStartMs
                    val shouldShowSkipIntro = hasValidIntroWindow &&
                            !isInPipMode &&
                            !activity.isInPictureInPictureMode &&
                            !hasSkippedIntroForCurrentMedia &&
                            dur > 180_000L &&
                            pos in detectedIntroStartMs..detectedIntroEndMs

                    if (shouldShowSkipIntro && btnSkipIntro.visibility != View.VISIBLE) {
                        btnSkipIntro.alpha = 0f
                        btnSkipIntro.visibility = View.VISIBLE
                        btnSkipIntro.animate().alpha(1f).setDuration(220L).start()
                    } else if (!shouldShowSkipIntro && btnSkipIntro.visibility == View.VISIBLE) {
                        btnSkipIntro.visibility = View.GONE
                    }

                    lastSavedTick++
                    if (lastSavedTick >= 15 && pos > 5000L) {
                        lastSavedTick = 0
                        onSaveProgressTick(pos, dur)
                    }
                }

                if (selectedSubtitleId != "NONE" && activeSubtitleCues.isNotEmpty() && p.isPlaying) {
                    val syncedPos = (pos - subtitleOffsetMs).coerceAtLeast(0L)
                    val cue = activeSubtitleCues.firstOrNull { syncedPos in it.startMs..it.endMs }
                    if (cue != null) {
                        if (tvSubtitleOverlay.text.toString() != cue.text) {
                            tvSubtitleOverlay.text = cue.text
                        }
                        if (!tvSubtitleOverlay.isVisible) tvSubtitleOverlay.visibility = View.VISIBLE
                    } else if (tvSubtitleOverlay.isVisible) {
                        tvSubtitleOverlay.text = ""
                        tvSubtitleOverlay.visibility = View.GONE
                    }
                } else if (selectedSubtitleId == "NONE" && tvSubtitleOverlay.isVisible) {
                    tvSubtitleOverlay.visibility = View.GONE
                }
            }
        }
    }

    private fun showServersOnlyPopup(providers: List<StreamSource>, currentIdx: Int) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = dp(275)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#EE10131A".toColorInt(), 18f, "#33FFFFFF".toColorInt(), 1f)
            setPadding(dp(18), dp(16), dp(18), dp(14))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = TextView(activity).apply {
            text = "Servers"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = fontBold
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        card.addView(header)

        val divider = View(activity).apply {
            setBackgroundColor("#22FFFFFF".toColorInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(8)
            }
        }
        card.addView(divider)

        val scroll = ScrollView(activity)
        val listCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listCol)
        card.addView(scroll)

        providers.forEachIndexed { idx, srv ->
            val isSel = idx == currentIdx
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isSel) makeRoundedBg("#26FFFFFF".toColorInt(), 10f, "#55FFFFFF".toColorInt(), 1f) else null
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    btnTopServerOnly.text = srv.label.take(12)
                    selectedQualityHeight = null
                    onServerSelected(idx, srv)
                    dialog.dismiss()
                }
            }
            val nameTv = TextView(activity).apply {
                text = srv.label
                setTextColor(if (isSel) Color.WHITE else "#CBD5E1".toColorInt())
                textSize = 13.5f
                typeface = fontBold
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameTv)
            if (isSel) {
                val ivCheck = ImageView(activity).apply {
                    setImageDrawable(VectorIcons.createCheckMarkIcon(activity, Color.WHITE))
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                }
                row.addView(ivCheck)
            }
            listCol.addView(row)
        }

        dialog.setContentView(card)
        dialog.show()
        dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /**
     * نافذة اختيار الجودة مع قفل صارم للجودة المختارة (4K / 1080p / 720p) لكي لا تعود لـ 720p
     */
    private fun showQualityOnlyPopup(engine: PrimePlayerEngine) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = dp(260)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#EE10131A".toColorInt(), 18f, "#33FFFFFF".toColorInt(), 1f)
            setPadding(dp(18), dp(16), dp(18), dp(14))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = TextView(activity).apply {
            text = "Quality"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = fontBold
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        card.addView(header)

        val divider = View(activity).apply {
            setBackgroundColor("#22FFFFFF".toColorInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(8)
            }
        }
        card.addView(divider)

        val realQualities = engine.getAvailableQualityTracks()
        val detectedBadge = engine.getCurrentDetectedQualityLabel()

        val isAutoSelected = selectedQualityHeight == null
        val autoRow = TextView(activity).apply {
            text = "Auto ($detectedBadge)"
            setTextColor(if (isAutoSelected) Color.WHITE else "#CBD5E1".toColorInt())
            textSize = 13.5f
            typeface = fontBold
            background = if (isAutoSelected) makeRoundedBg("#26FFFFFF".toColorInt(), 10f, "#55FFFFFF".toColorInt(), 1f) else null
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                selectedQualityHeight = null
                engine.player?.let { exo ->
                    exo.trackSelectionParameters = exo.trackSelectionParameters
                        .buildUpon()
                        .clearVideoSizeConstraints()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .build()
                }
                btnTopQualityOnly.text = detectedBadge
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
            val shortBadge = when {
                qTrack.height >= 2160 -> "4K"
                qTrack.height >= 1080 -> "1080p"
                qTrack.height >= 720 -> "720p"
                else -> "${qTrack.height}p"
            }
            val isSel = selectedQualityHeight == qTrack.height
            val row = TextView(activity).apply {
                text = qLabel
                setTextColor(if (isSel) Color.WHITE else "#CBD5E1".toColorInt())
                textSize = 13.5f
                typeface = fontBold
                background = if (isSel) makeRoundedBg("#26FFFFFF".toColorInt(), 10f, "#55FFFFFF".toColorInt(), 1f) else null
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    selectedQualityHeight = qTrack.height
                    engine.selectVideoQualityHeight(qTrack.height)
                    // قفل الحد الأدنى والأعلى للجودة على ExoPlayer مباشرة لضمان عدم الرجوع لـ 720p
                    engine.player?.let { exo ->
                        exo.trackSelectionParameters = exo.trackSelectionParameters
                            .buildUpon()
                            .setMinVideoSize(qTrack.width.coerceAtLeast(1), qTrack.height)
                            .setMaxVideoSize(qTrack.width.coerceAtLeast(3840), qTrack.height)
                            .build()
                    }
                    btnTopQualityOnly.text = shortBadge
                    dialog.dismiss()
                }
            }
            card.addView(row)
        }

        dialog.setContentView(card)
        dialog.show()
        dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showAudioTracksDialog(engine: PrimePlayerEngine) {
        val exo = engine.player
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = dp(275)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#EE10131A".toColorInt(), 18f, "#33FFFFFF".toColorInt(), 1f)
            setPadding(dp(18), dp(16), dp(18), dp(14))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = TextView(activity).apply {
            text = "Audio Track"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = fontBold
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        card.addView(header)

        val audioGroups = exo?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO }.orEmpty()
        var addedCount = 0

        audioGroups.forEach { group ->
            for (tIdx in 0 until group.length) {
                val format = group.getTrackFormat(tIdx)
                val langCode = format.language.orEmpty()
                val label = format.label
                    ?: (if (langCode.isNotEmpty()) Locale(langCode).displayLanguage else "Track ${addedCount + 1}")
                val isSelected = group.isTrackSelected(tIdx)

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = if (isSelected) makeRoundedBg("#26FFFFFF".toColorInt(), 10f, "#55FFFFFF".toColorInt(), 1f) else null
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setOnClickListener {
                        exo?.let { player ->
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, tIdx))
                                .build()
                        }
                        Toast.makeText(activity, "Audio: $label", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
                val tv = TextView(activity).apply {
                    text = label
                    setTextColor(if (isSelected) Color.WHITE else "#CBD5E1".toColorInt())
                    textSize = 13.5f
                    typeface = fontBold
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(tv)
                if (isSelected) {
                    val ivCheck = ImageView(activity).apply {
                        setImageDrawable(VectorIcons.createCheckMarkIcon(activity, Color.WHITE))
                        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                    }
                    row.addView(ivCheck)
                }
                card.addView(row)
                addedCount++
            }
        }

        if (addedCount == 0) {
            val defaultRow = TextView(activity).apply {
                text = "Original Audio Track"
                setTextColor(Color.WHITE)
                textSize = 13.5f
                typeface = fontBold
                background = makeRoundedBg("#26FFFFFF".toColorInt(), 10f, "#55FFFFFF".toColorInt(), 1f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { dialog.dismiss() }
            }
            card.addView(defaultRow)
        }

        dialog.setContentView(card)
        dialog.show()
        dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showSubtitlesDialog() {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val screenW = activity.resources.displayMetrics.widthPixels
        val dialogWidthPx = min((screenW * 0.88f).toInt(), dp(360))

        val root = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_subtitles_sheet, null, false) as LinearLayout
        root.background = makeRoundedBg("#F0101218".toColorInt(), 20f, "#33FFFFFF".toColorInt(), 1f)

        val tabSwitchBox = root.findViewById<LinearLayout>(R.id.tabSwitchBox)
        tabSwitchBox.background = makeRoundedBg("#14FFFFFF".toColorInt(), 12f, "#22FFFFFF".toColorInt(), 1f)

        val tabTracksBtn = root.findViewById<TextView>(R.id.tabTracksBtn)
        val tabSettingsBtn = root.findViewById<TextView>(R.id.tabSettingsBtn)
        val btnClosePopup = root.findViewById<TextView>(R.id.btnClosePopup)
        val tracksPanel = root.findViewById<LinearLayout>(R.id.tracksPanel)
        val settingsPanel = root.findViewById<LinearLayout>(R.id.settingsPanel)

        val btnDisableSubs = root.findViewById<TextView>(R.id.btnDisableSubs)
        val btnUploadSrt = root.findViewById<TextView>(R.id.btnUploadSrt)
        val btnRefreshSubs = root.findViewById<TextView>(R.id.btnRefreshSubs)
        val filterRow = root.findViewById<LinearLayout>(R.id.filterRow)
        val tracksContainer = root.findViewById<LinearLayout>(R.id.tracksContainer)

        val sizeOptionsRow = root.findViewById<LinearLayout>(R.id.sizeOptionsRow)
        val bgOptionsRow = root.findViewById<LinearLayout>(R.id.bgOptionsRow)

        val positionRow = root.findViewById<LinearLayout>(R.id.positionRow)
        val tvPositionStatus = root.findViewById<TextView>(R.id.tvPositionStatus)
        val btnSubPosDown = root.findViewById<TextView>(R.id.btnSubPosDown)
        val btnSubPosReset = root.findViewById<TextView>(R.id.btnSubPosReset)
        val btnSubPosUp = root.findViewById<TextView>(R.id.btnSubPosUp)

        val syncRow = root.findViewById<LinearLayout>(R.id.syncRow)
        val tvSyncStatus = root.findViewById<TextView>(R.id.tvSyncStatus)
        val btnMinusHalf = root.findViewById<TextView>(R.id.btnMinusHalf)
        val btnResetSync = root.findViewById<TextView>(R.id.btnResetSync)
        val btnPlusHalf = root.findViewById<TextView>(R.id.btnPlusHalf)

        tabTracksBtn.background = makeRoundedBg(Color.WHITE, 10f)
        btnClosePopup.background = makeRoundedBg("#1FFFFFFF".toColorInt(), 16f)
        btnClosePopup.setOnClickListener { dialog.dismiss() }

        val isOff = selectedSubtitleId == "NONE"
        btnDisableSubs.setTextColor(if (isOff) "#0E1015".toColorInt() else Color.WHITE)
        btnDisableSubs.background = makeRoundedBg(
            if (isOff) Color.WHITE else "#1AFFFFFF".toColorInt(),
            10f,
            "#33FFFFFF".toColorInt(),
            0.8f
        )
        btnDisableSubs.setOnClickListener {
            selectedSubtitleId = "NONE"
            tvSubtitleOverlay.visibility = View.GONE
            dialog.dismiss()
        }

        btnUploadSrt.background = makeRoundedBg("#1AFFFFFF".toColorInt(), 10f, "#33FFFFFF".toColorInt(), 0.8f)
        btnUploadSrt.setOnClickListener {
            dialog.dismiss()
            onPickLocalSubtitleFile()
        }

        btnRefreshSubs.background = makeRoundedBg("#1AFFFFFF".toColorInt(), 10f, "#33FFFFFF".toColorInt(), 0.8f)

        val sizePresets = listOf(
            13f to "Small",
            16f to "Normal",
            20f to "Large",
            24f to "Max"
        )
        fun rebuildSizePills() {
            sizeOptionsRow.removeAllViews()
            sizePresets.forEach { (spVal, label) ->
                val isSel = abs(subtitleTextSizeSp - spVal) < 1.5f
                val pill = TextView(activity).apply {
                    text = label
                    textSize = 12f
                    typeface = fontBold
                    gravity = Gravity.CENTER
                    setTextColor(if (isSel) "#0E1015".toColorInt() else Color.WHITE)
                    background = makeRoundedBg(
                        if (isSel) Color.WHITE else "#1AFFFFFF".toColorInt(),
                        10f,
                        "#33FFFFFF".toColorInt()
                    )
                    setPadding(dp(6), dp(7), dp(6), dp(7))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(5)
                    }
                    setOnClickListener {
                        subtitleTextSizeSp = spVal
                        saveAndApplySubtitleStyle()
                        rebuildSizePills()
                    }
                }
                sizeOptionsRow.addView(pill)
            }
        }
        rebuildSizePills()

        val bgPresets = listOf(
            0 to "Shadow",
            130 to "Glass",
            215 to "Dark Box"
        )
        fun rebuildBgPills() {
            bgOptionsRow.removeAllViews()
            bgPresets.forEach { (alphaVal, label) ->
                val isSel = abs(subtitleBgAlpha - alphaVal) < 25
                val pill = TextView(activity).apply {
                    text = label
                    textSize = 12f
                    typeface = fontBold
                    gravity = Gravity.CENTER
                    setTextColor(if (isSel) "#0E1015".toColorInt() else Color.WHITE)
                    background = makeRoundedBg(
                        if (isSel) Color.WHITE else "#1AFFFFFF".toColorInt(),
                        10f,
                        "#33FFFFFF".toColorInt()
                    )
                    setPadding(dp(6), dp(7), dp(6), dp(7))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(5)
                    }
                    setOnClickListener {
                        subtitleBgAlpha = alphaVal
                        saveAndApplySubtitleStyle()
                        rebuildBgPills()
                    }
                }
                bgOptionsRow.addView(pill)
            }
        }
        rebuildBgPills()

        // التحكم في رفع وإنزال الترجمة (Vertical Position)
        positionRow.background = makeRoundedBg("#14FFFFFF".toColorInt(), 12f, "#28FFFFFF".toColorInt(), 1f)
        tvPositionStatus.text = String.format(Locale.US, "Position: %+ddp", subtitleExtraLiftDp)
        btnSubPosDown.background = makeRoundedBg("#24FFFFFF".toColorInt(), 8f)
        btnSubPosReset.background = makeRoundedBg(Color.WHITE, 8f)
        btnSubPosUp.background = makeRoundedBg("#24FFFFFF".toColorInt(), 8f)

        fun flashTap(v: TextView, isWhiteRest: Boolean = false, action: () -> Unit) {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(65L).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(95L).start()
            }.start()
            v.background = makeRoundedBg(Color.WHITE, 8f)
            v.setTextColor("#0E1015".toColorInt())
            v.postDelayed({
                v.background = makeRoundedBg(if (isWhiteRest) Color.WHITE else "#24FFFFFF".toColorInt(), 8f)
                v.setTextColor(if (isWhiteRest) "#0E1015".toColorInt() else Color.WHITE)
            }, 130L)
            action()
        }

        btnSubPosDown.setOnClickListener {
            flashTap(btnSubPosDown) {
                subtitleExtraLiftDp = (subtitleExtraLiftDp - 6).coerceIn(-10, 90)
                tvPositionStatus.text = String.format(Locale.US, "Position: %+ddp", subtitleExtraLiftDp)
                saveAndApplySubtitleStyle()
            }
        }
        btnSubPosReset.setOnClickListener {
            flashTap(btnSubPosReset, isWhiteRest = true) {
                subtitleExtraLiftDp = 0
                tvPositionStatus.text = "Position: +0dp"
                saveAndApplySubtitleStyle()
            }
        }
        btnSubPosUp.setOnClickListener {
            flashTap(btnSubPosUp) {
                subtitleExtraLiftDp = (subtitleExtraLiftDp + 6).coerceIn(-10, 90)
                tvPositionStatus.text = String.format(Locale.US, "Position: %+ddp", subtitleExtraLiftDp)
                saveAndApplySubtitleStyle()
            }
        }

        // التحكم في مزامنة الوقت (Time Sync)
        syncRow.background = makeRoundedBg("#14FFFFFF".toColorInt(), 12f, "#28FFFFFF".toColorInt(), 1f)
        tvSyncStatus.text = String.format(Locale.US, "Offset: %+.1fs", subtitleOffsetMs / 1000.0)
        btnMinusHalf.background = makeRoundedBg("#24FFFFFF".toColorInt(), 8f)
        btnResetSync.background = makeRoundedBg(Color.WHITE, 8f)
        btnPlusHalf.background = makeRoundedBg("#24FFFFFF".toColorInt(), 8f)

        btnMinusHalf.setOnClickListener {
            flashTap(btnMinusHalf) {
                subtitleOffsetMs -= 500L
                tvSyncStatus.text = String.format(Locale.US, "Offset: %+.1fs", subtitleOffsetMs / 1000.0)
            }
        }
        btnResetSync.setOnClickListener {
            flashTap(btnResetSync, isWhiteRest = true) {
                subtitleOffsetMs = 0L
                tvSyncStatus.text = "Offset: +0.0s"
            }
        }
        btnPlusHalf.setOnClickListener {
            flashTap(btnPlusHalf) {
                subtitleOffsetMs += 500L
                tvSyncStatus.text = String.format(Locale.US, "Offset: %+.1fs", subtitleOffsetMs / 1000.0)
            }
        }

        tabTracksBtn.setOnClickListener {
            tracksPanel.visibility = View.VISIBLE
            settingsPanel.visibility = View.GONE
            tabTracksBtn.background = makeRoundedBg(Color.WHITE, 10f)
            tabTracksBtn.setTextColor("#0E1015".toColorInt())
            tabSettingsBtn.background = null
            tabSettingsBtn.setTextColor("#B0BAC9".toColorInt())
        }
        tabSettingsBtn.setOnClickListener {
            tracksPanel.visibility = View.GONE
            settingsPanel.visibility = View.VISIBLE
            tabSettingsBtn.background = makeRoundedBg(Color.WHITE, 10f)
            tabSettingsBtn.setTextColor("#0E1015".toColorInt())
            tabTracksBtn.background = null
            tabTracksBtn.setTextColor("#B0BAC9".toColorInt())
        }

        var selectedLangFilter = "ALL"

        fun getCleanLanguageName(codeRaw: String, fallbackLabel: String): String {
            return when (codeRaw.take(2).lowercase(Locale.ROOT)) {
                "ar" -> "Arabic"
                "en" -> "English"
                "fr" -> "French"
                "es" -> "Spanish"
                "tr" -> "Turkish"
                "de" -> "German"
                "it" -> "Italian"
                "pt" -> "Portuguese"
                "ru" -> "Russian"
                else -> fallbackLabel.substringBefore("(").substringBefore("-").trim().ifEmpty { "Subtitle" }
            }
        }

        fun renderAvailableLanguageChipsAndTracks() {
            filterRow.removeAllViews()
            tracksContainer.removeAllViews()

            val groupedLangs = availableSubtitles.groupBy {
                it.languageCode.take(2).lowercase(Locale.ROOT).ifEmpty { "en" }
            }
            val dynamicFilters = mutableListOf("ALL" to "All")
            groupedLangs.forEach { (code, list) ->
                val cleanName = getCleanLanguageName(code, list.first().languageLabel)
                dynamicFilters.add(code to cleanName)
            }

            dynamicFilters.forEach { (code, label) ->
                val isCurrent = selectedLangFilter == code
                val chip = TextView(activity).apply {
                    text = label
                    textSize = 11f
                    typeface = fontBold
                    setTextColor(if (isCurrent) "#0E1015".toColorInt() else Color.WHITE)
                    background = makeRoundedBg(
                        if (isCurrent) Color.WHITE else "#16FFFFFF".toColorInt(),
                        14f,
                        "#2EFFFFFF".toColorInt()
                    )
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(6) }
                    setOnClickListener {
                        selectedLangFilter = code
                        renderAvailableLanguageChipsAndTracks()
                    }
                }
                filterRow.addView(chip)
            }

            val filtered = availableSubtitles.filter {
                selectedLangFilter == "ALL" || it.languageCode.lowercase(Locale.ROOT).startsWith(selectedLangFilter)
            }

            if (filtered.isEmpty()) {
                val emptyTv = TextView(activity).apply {
                    text = "No subtitles available."
                    setTextColor("#94A3B8".toColorInt())
                    textSize = 12.5f
                    typeface = fontRegular
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(24), dp(12), dp(24))
                }
                tracksContainer.addView(emptyTv)
            } else {
                val langCounter = mutableMapOf<String, Int>()
                filtered.forEach { track ->
                    val isSelected = track.id == selectedSubtitleId
                    val baseLang = getCleanLanguageName(track.languageCode, track.languageLabel)
                    val count = (langCounter[baseLang] ?: 0) + 1
                    langCounter[baseLang] = count
                    val cleanDisplayTitle = if (count == 1) baseLang else "$baseLang  #$count"

                    val itemLayout = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = if (isSelected) makeRoundedBg("#26FFFFFF".toColorInt(), 10f, "#55FFFFFF".toColorInt(), 1f) else null
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        setOnClickListener {
                            selectedSubtitleId = track.id
                            subtitleOffsetMs = 0L
                            scope.launch {
                                val parsed = onDownloadSubtitleTrack(track)
                                if (parsed.isNotEmpty()) {
                                    activeSubtitleCues = parsed
                                    tvSubtitleOverlay.visibility = View.VISIBLE
                                }
                            }
                            dialog.dismiss()
                        }
                    }

                    val langTv = TextView(activity).apply {
                        text = cleanDisplayTitle
                        setTextColor(if (isSelected) Color.WHITE else "#D0D7E2".toColorInt())
                        textSize = 13.5f
                        typeface = fontBold
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    itemLayout.addView(langTv)

                    if (isSelected) {
                        val ivCheck = ImageView(activity).apply {
                            setImageDrawable(VectorIcons.createCheckMarkIcon(activity, Color.WHITE))
                            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                        }
                        itemLayout.addView(ivCheck)
                    }
                    tracksContainer.addView(itemLayout)
                }
            }
        }

        btnRefreshSubs.setOnClickListener {
            btnRefreshSubs.text = "..."
            onRequestRefreshSubtitles { freshSubs ->
                availableSubtitles = freshSubs
                btnRefreshSubs.text = "Reload"
                renderAvailableLanguageChipsAndTracks()
            }
        }

        if (availableSubtitles.isEmpty()) {
            btnRefreshSubs.performClick()
        } else {
            renderAvailableLanguageChipsAndTracks()
        }

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(dialogWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun saveAndApplySubtitleStyle() {
        activity.getSharedPreferences(PREFS_SUB_STYLE, Context.MODE_PRIVATE).edit()
            .putFloat("sub_size_sp", subtitleTextSizeSp)
            .putInt("sub_text_color", subtitleTextColor)
            .putInt("sub_bg_alpha", subtitleBgAlpha)
            .putInt("sub_extra_lift_dp", subtitleExtraLiftDp)
            .apply()
        applySubtitleStyleToOverlay()
        animateSubtitlePositionForControls(controlsVisible = controlsOverlay.isVisible, animate = true)
    }

    private fun applySubtitleStyleToOverlay() {
        tvSubtitleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp)
        tvSubtitleOverlay.setTextColor(subtitleTextColor)
        if (subtitleBgAlpha <= 10) {
            tvSubtitleOverlay.background = null
            tvSubtitleOverlay.setShadowLayer(12f, 0f, 3.5f, Color.BLACK)
        } else {
            tvSubtitleOverlay.setShadowLayer(6f, 0f, 2f, Color.BLACK)
            tvSubtitleOverlay.background = makeRoundedBg(
                ColorUtils.setAlphaComponent("#0B0D13".toColorInt(), subtitleBgAlpha),
                8f
            )
        }
    }

    fun loadExternalSubtitleFromUri(uri: Uri) {
        scope.launch {
            val parsedCues = withContext(Dispatchers.IO) {
                parseLocalSrtOrVttUri(uri)
            }
            if (parsedCues.isNotEmpty()) {
                val localTrack = SubtitleTrack(
                    id = "local_user_sub_${System.currentTimeMillis()}",
                    languageCode = "custom",
                    languageLabel = "Custom Subtitle",
                    releaseName = "",
                    url = uri.toString()
                )
                availableSubtitles = listOf(localTrack) + availableSubtitles
                selectedSubtitleId = localTrack.id
                activeSubtitleCues = parsedCues
                subtitleOffsetMs = 0L
                tvSubtitleOverlay.visibility = View.VISIBLE
                Toast.makeText(activity, "Custom Subtitle Loaded", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "Invalid .SRT / .VTT file", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseLocalSrtOrVttUri(uri: Uri): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        return try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                val lines = reader.readLines()
                var i = 0
                val timeRegex = Regex("""(\d{1,2}:\d{2}:\d{2}[,.]\d{3}|\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{3}|\d{2}:\d{2}[,.]\d{3})""")
                while (i < lines.size) {
                    val line = lines[i].trim()
                    val match = timeRegex.find(line)
                    if (match != null) {
                        val startMs = parseTimestampToMs(match.groupValues[1])
                        val endMs = parseTimestampToMs(match.groupValues[2])
                        i++
                        val textBuilder = StringBuilder()
                        while (i < lines.size && lines[i].trim().isNotEmpty()) {
                            if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                            val cleanLine = lines[i].trim().replace(Regex("<[^>]*>"), "")
                            textBuilder.append(cleanLine)
                            i++
                        }
                        if (textBuilder.isNotEmpty() && endMs > startMs) {
                            cues.add(SubtitleCue(startMs, endMs, textBuilder.toString()))
                        }
                    } else {
                        i++
                    }
                }
            }
            cues
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseTimestampToMs(raw: String): Long {
        val normalized = raw.trim().replace(',', '.')
        val parts = normalized.split(":")
        return try {
            if (parts.size == 3) {
                val hrs = parts[0].toLong()
                val mins = parts[1].toLong()
                val secParts = parts[2].split(".")
                val secs = secParts[0].toLong()
                val millis = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                (hrs * 3600_000L) + (mins * 60_000L) + (secs * 1000L) + millis
            } else if (parts.size == 2) {
                val mins = parts[0].toLong()
                val secParts = parts[1].split(".")
                val secs = secParts[0].toLong()
                val millis = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                (mins * 60_000L) + (secs * 1000L) + millis
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun enterMiniPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            try {
                val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps?.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                        Process.myUid(),
                        activity.packageName
                    ) == AppOpsManager.MODE_ALLOWED
                } else true

                if (allowed) {
                    isInPipMode = true
                    controlsOverlay.visibility = View.GONE
                    btnSkipIntro.visibility = View.GONE
                    holdSpeed2xPill.visibility = View.GONE
                    gestureIndicatorCard.visibility = View.GONE
                    animateSubtitlePositionForControls(controlsVisible = false, animate = false)
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                    val entered = activity.enterPictureInPictureMode(params)
                    if (entered) return
                }
            } catch (_: Exception) {
            }
        }
        if (isFullscreen) {
            applyFullscreenState(false)
        }
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            controlsOverlay.visibility = View.GONE
            btnSkipIntro.visibility = View.GONE
            holdSpeed2xPill.visibility = View.GONE
            gestureIndicatorCard.visibility = View.GONE
            tvSubtitleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            tvSubtitleOverlay.setPadding(dp(6), dp(1), dp(6), dp(1))
            (tvSubtitleOverlay.layoutParams as? FrameLayout.LayoutParams)?.apply {
                setMargins(dp(6), 0, dp(6), dp(4))
                tvSubtitleOverlay.layoutParams = this
            }
            animateSubtitlePositionForControls(controlsVisible = false, animate = false)
        } else {
            tvSubtitleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp)
            tvSubtitleOverlay.setPadding(dp(12), dp(4), dp(12), dp(4))
            (tvSubtitleOverlay.layoutParams as? FrameLayout.LayoutParams)?.apply {
                setMargins(dp(20), 0, dp(20), dp(14))
                tvSubtitleOverlay.layoutParams = this
            }
            showControlsWithFade()
        }
    }

    private fun showPlaybackSpeedDialog(engine: PrimePlayerEngine) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val speeds = listOf(
            0.5f to "0.5x",
            0.75f to "0.75x",
            1.0f to "1x (Normal)",
            1.25f to "1.25x",
            1.5f to "1.5x",
            2.0f to "2x"
        )

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#EE10131A".toColorInt(), 16f, "#33FFFFFF".toColorInt(), 1f)
            setPadding(dp(22), dp(14), dp(22), dp(14))
        }

        speeds.forEach { (spd, label) ->
            val isSel = spd == currentPlaybackSpeed
            val tv = TextView(activity).apply {
                text = label
                textSize = 13.5f
                typeface = fontBold
                setTextColor(if (isSel) Color.WHITE else "#94A3B8".toColorInt())
                background = if (isSel) makeRoundedBg("#24FFFFFF".toColorInt(), 8f) else null
                setPadding(dp(14), dp(9), dp(24), dp(9))
                setOnClickListener {
                    currentPlaybackSpeed = spd
                    engine.player?.playbackParameters = PlaybackParameters(spd)
                    val shortLbl = if (spd == 1.0f) "1x" else "${spd}x"
                    tvBottomSpeedLabel.text = "Speed: $shortLbl"
                    dialog.dismiss()
                }
            }
            card.addView(tv)
        }

        dialog.setContentView(card)
        dialog.show()
    }

    fun applyFullscreenState(enterFullscreen: Boolean) {
        isFullscreen = enterFullscreen
        btnInlineFullscreen.setImageDrawable(
            VectorIcons.createFullscreenIcon(activity, isFullscreen = enterFullscreen)
        )
        onFullscreenChanged(enterFullscreen)

        if (enterFullscreen) {
            btnMinimizePlayer.visibility = View.VISIBLE
            btnTopServerOnly.visibility = View.VISIBLE
            btnTopQualityOnly.visibility = View.VISIBLE
            btnTopLock.visibility = View.VISIBLE
            fullscreenBottomActionsRow.visibility = View.VISIBLE
            fullscreenLeftBrightnessBar.visibility = View.VISIBLE
            fullscreenRightVolumeBar.visibility = View.VISIBLE
            vBottomPlayerSeparator.visibility = View.GONE

            playerContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            WindowInsetsControllerCompat(activity.window, playerContainer).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            WindowCompat.setDecorFitsSystemWindows(activity.window, true)
            WindowInsetsControllerCompat(activity.window, playerContainer).show(WindowInsetsCompat.Type.systemBars())

            // في المشغل الصغير: نخفي سهم الرجوع، ونبقي زر Server ظاهراً
            btnMinimizePlayer.visibility = View.GONE
            btnTopServerOnly.visibility = View.VISIBLE
            btnTopQualityOnly.visibility = View.VISIBLE
            btnTopLock.visibility = View.GONE
            fullscreenBottomActionsRow.visibility = View.GONE
            fullscreenLeftBrightnessBar.visibility = View.GONE
            fullscreenRightVolumeBar.visibility = View.GONE
            vBottomPlayerSeparator.visibility = View.VISIBLE

            resizeModeIndex = 0
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            tvBottomAspectLabel.text = "Scale: Fit"

            playerContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(215)
            )
        }
        playerContainer.requestLayout()
        animateSubtitlePositionForControls(controlsVisible = controlsOverlay.isVisible, animate = false)
        resetAutoHide()
    }

    fun setTitle(title: String) {
        tvTopPlayerTitle.text = title
    }

    fun setServerBadgeLabel(serverName: String) {
        btnTopServerOnly.text = serverName.take(12)
    }

    /**
     * تحديث شارة الجودة الحقيقية للسيرفر (لا نقوم بالكتابة فوقها إذا اختار المستخدم جودة محددة يدوياً)
     */
    fun onEngineQualityDetected(qualityBadge: String) {
        if (selectedQualityHeight == null) {
            btnTopQualityOnly.text = qualityBadge
        }
    }

    fun setLoadingState(visible: Boolean) {
        setBufferingState(visible)
    }

    fun resetForNewStream(isActiveMode: Boolean, isSeries: Boolean = false) {
        isCurrentMediaSeries = isSeries
        hasSkippedIntroForCurrentMedia = false
        hasRenderedInitialFrameForStream = false
        selectedQualityHeight = null
        btnSkipIntro.visibility = View.GONE

        // مسح سطح الفيديو القديم فوراً حتى لا تبقى أي صورة قديمة عالقة
        playerView.player = null

        if (isSeries) {
            detectedIntroStartMs = 15_000L
            detectedIntroEndMs = 90_000L
        } else {
            detectedIntroStartMs = -1L
            detectedIntroEndMs = -1L
        }

        tvElapsed.text = "00:00"
        tvDuration.text = "00:00"
        seekBar.progress = 0
        seekBar.secondaryProgress = 0
        tvSubtitleOverlay.text = ""
        tvSubtitleOverlay.visibility = View.GONE
        setBufferingState(isActiveMode)
    }

    fun updateAvailableSubtitles(
        subs: List<SubtitleTrack>,
        activeCues: List<SubtitleCue>? = null,
        selectedId: String? = null
    ) {
        availableSubtitles = subs
        if (activeCues != null) {
            activeSubtitleCues = activeCues
            if (!isCurrentMediaSeries) {
                val introCue = activeCues.firstOrNull { cue ->
                    cue.startMs in 5_000L..120_000L &&
                            (cue.text.contains("intro", ignoreCase = true) ||
                                    cue.text.contains("theme", ignoreCase = true) ||
                                    cue.text.contains("opening", ignoreCase = true))
                }
                if (introCue != null) {
                    detectedIntroStartMs = introCue.startMs
                    detectedIntroEndMs = (introCue.endMs + 45_000L).coerceAtMost(130_000L)
                }
            }
        }
        if (selectedId != null) selectedSubtitleId = selectedId
    }

    fun getAvailableSubtitles(): List<SubtitleTrack> = availableSubtitles
    fun getSelectedSubtitleId(): String = selectedSubtitleId
    fun getActiveSubtitleCues(): List<SubtitleCue> = activeSubtitleCues

    fun updatePlayPauseVectorIcon(isPlaying: Boolean) {
        btnCenterPlayPause.setImageDrawable(
            VectorIcons.createPlayPauseIcon(activity, isPlaying = isPlaying)
        )
    }

    fun showControlsWithFade() {
        if (isInPipMode) return
        controlsOverlay.removeCallbacks(autoHideRunnable)
        controlsOverlay.animate().cancel()

        val p = attachedPlayer
        if (p != null) {
            updatePlayPauseVectorIcon(p.isPlaying)
            val dur = p.duration
            val pos = p.currentPosition.coerceAtLeast(0L)
            if (dur > 0 && !isUserSeeking) {
                seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                seekBar.secondaryProgress = (p.bufferedPercentage * 10).coerceIn(0, 1000)
                tvElapsed.text = formatTimeMs(pos)
                tvDuration.text = formatTimeMs(dur)
            }
        }

        if (!controlsOverlay.isVisible) {
            controlsOverlay.alpha = 0f
            controlsOverlay.visibility = View.VISIBLE
        }
        controlsOverlay.animate()
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        animateSubtitlePositionForControls(controlsVisible = true, animate = true)
        scheduleAutoHideIfPlaying()
    }

    fun hideControlsSmoothly() {
        controlsOverlay.removeCallbacks(autoHideRunnable)
        if (!controlsOverlay.isVisible || isBufferingNow) return

        animateSubtitlePositionForControls(controlsVisible = false, animate = true)
        controlsOverlay.animate().cancel()
        controlsOverlay.animate()
            .alpha(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                controlsOverlay.visibility = View.GONE
                controlsOverlay.alpha = 1f
            }
            .start()
    }

    private fun resetAutoHide() {
        if (isInPipMode) return
        controlsOverlay.removeCallbacks(autoHideRunnable)
        if (!controlsOverlay.isVisible || controlsOverlay.alpha < 0.9f) {
            showControlsWithFade()
        } else {
            scheduleAutoHideIfPlaying()
        }
    }

    /**
     * التقديم والترجيع بـ 15 ثانية دون إخفاء الأزرار (يعيد ضبط المؤقت ليبقى ظاهراً 3.4 ثوانٍ إضافية)
     */
    private fun seekByDeltaMs(engine: PrimePlayerEngine, deltaMs: Long) {
        val exo = engine.player ?: return
        val dur = exo.duration
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        val newPos = if (dur > 0) min(target, dur) else target
        exo.seekTo(newPos)
        if (dur > 0) {
            seekBar.progress = ((newPos * 1000L) / dur).toInt().coerceIn(0, 1000)
            tvElapsed.text = formatTimeMs(newPos)
        }
        resetAutoHide()
    }

    private fun formatTimeMs(ms: Long): String {
        val totalSecs = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    fun release() {
        attachedPlayer?.removeListener(playerStateListener)
        attachedPlayer = null
        progressJob?.cancel()
        controlsOverlay.removeCallbacks(autoHideRunnable)
        playerContainer.removeCallbacks(hideGestureIndicatorRunnable)
    }
}