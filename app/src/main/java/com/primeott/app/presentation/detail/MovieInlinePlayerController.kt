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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.text.TextUtils
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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

    // أزرار الوسط + الـ Loader الدائري في نفس مكان Play/Pause بالضبط كما في الفيديو
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

    // مؤشر الصوت والإضاءة في أعلى الشاشة
    private lateinit var gestureIndicatorCard: LinearLayout
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
    private var currentPlaybackSpeed: Float = 1.0f

    // إعدادات Skip Intro الذكية
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
    // 0 = Transparent + Strong Black Shadow, 130 = Frosted Glass, 215 = Dark Box
    private var subtitleBgAlpha: Int = 0

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
            if (isPlaying) {
                setBufferingState(false)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    setBufferingState(true)
                }
                Player.STATE_READY -> {
                    setBufferingState(false)
                    updatePlayPauseVectorIcon(attachedPlayer?.isPlaying == true)
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
        hideControlsSmoothly()
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

    @SuppressLint("ClickableViewAccessibility")
    fun buildPlayerView(): FrameLayout {
        val prefs = activity.getSharedPreferences(PREFS_SUB_STYLE, Context.MODE_PRIVATE)
        subtitleTextSizeSp = prefs.getFloat("sub_size_sp", 16f).coerceIn(12f, 26f)
        subtitleTextColor = prefs.getInt("sub_text_color", Color.WHITE)
        subtitleBgAlpha = prefs.getInt("sub_bg_alpha", 0)

        playerContainer = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            clipChildren = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(215)
            )
        }

        playerView = PlayerView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerContainer.addView(playerView)

        vTopEdgeSoftGradient = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42),
                Gravity.TOP
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf("#75000000".toColorInt(), Color.TRANSPARENT)
            )
        }
        playerContainer.addView(vTopEdgeSoftGradient)

        // الترجمة (تتحرك للأعلى تلقائياً عند ظهور شريط التحكم وتعود للأسفل عند اختفائه)
        tvSubtitleOverlay = TextView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                setMargins(dp(20), 0, dp(20), dp(14))
            }
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp)
            typeface = fontBold
            setTextColor(subtitleTextColor)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            visibility = View.GONE
        }
        applySubtitleStyleToOverlay()
        playerContainer.addView(tvSubtitleOverlay)

        // كبسولة السرعة المضاعفة (2x Speed) في الأعلى
        holdSpeed2xPill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#D910131C".toColorInt(), 20f, "#4DFFFFFF".toColorInt(), 1f)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            visibility = View.GONE
            elevation = dpF(8f)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(10)
            }
            val ivFast = ImageView(activity).apply {
                setImageDrawable(VectorIcons.createFastForwardDoubleArrowIcon(activity, Color.WHITE))
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(5) }
            }
            val tvLabel = TextView(activity).apply {
                text = "2x Speed"
                setTextColor(Color.WHITE)
                textSize = 11.5f
                typeface = fontBold
            }
            addView(ivFast)
            addView(tvLabel)
        }
        playerContainer.addView(holdSpeed2xPill)

        // مؤشر الصوت (يمين) والإضاءة (يسار) مثبت في أعلى المشغل (topMargin = 10dp) لكي لا ينزل لوسط الشاشة
        gestureIndicatorCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#E010131C".toColorInt(), 20f, "#40FFFFFF".toColorInt(), 1f)
            setPadding(dp(12), dp(6), dp(14), dp(6))
            visibility = View.GONE
            elevation = dpF(10f)
            layoutParams = FrameLayout.LayoutParams(
                dp(155),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(10)
            }
        }
        ivGestureIcon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(8) }
        }
        pbGestureLevel = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 50
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf("#38FFFFFF".toColorInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(4), 1f).apply { marginEnd = dp(8) }
        }
        tvGesturePercent = TextView(activity).apply {
            text = "50%"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = fontBold
        }
        gestureIndicatorCard.addView(ivGestureIcon)
        gestureIndicatorCard.addView(pbGestureLevel)
        gestureIndicatorCard.addView(tvGesturePercent)
        playerContainer.addView(gestureIndicatorCard)

        btnSkipIntro = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#E612151E".toColorInt(), 18f, "#CCFFFFFF".toColorInt(), 1.1f)
            setPadding(dp(14), dp(6), dp(12), dp(6))
            visibility = View.GONE
            elevation = dpF(6f)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                setMargins(0, 0, dp(14), dp(44))
            }
        }
        val tvSkipIntroLabel = TextView(activity).apply {
            text = "Skip Intro"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = fontBold
        }
        val ivSkipIntroArrow = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createSkipNextIcon(activity, Color.WHITE))
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                marginStart = dp(5)
            }
        }
        btnSkipIntro.addView(tvSkipIntroLabel)
        btnSkipIntro.addView(ivSkipIntroArrow)
        playerContainer.addView(btnSkipIntro)

        // =========================================================================
        // طبقة التحكم (تظهر وتختفي بـ Fade ناعم، وتحتوي على الـ Loader في مكان Play/Pause)
        // =========================================================================
        controlsOverlay = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf("#A6000000".toColorInt(), "#33000000".toColorInt(), "#BF000000".toColorInt())
            )
            visibility = View.VISIBLE
        }

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        btnMinimizePlayer = ImageView(activity).apply {
            setImageResource(R.drawable.ic_back_chevron)
            background = makeRoundedBg("#2EFFFFFF".toColorInt(), 18f)
            setPadding(dp(7), dp(7), dp(8), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener {
                if (isFullscreen) {
                    applyFullscreenState(false)
                } else {
                    onMinimizeToCover()
                }
            }
        }

        tvTopPlayerTitle = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = fontBold
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnTopServerOnly = TextView(activity).apply {
            text = "Server"
            textSize = 11.5f
            typeface = fontBold
            setTextColor(Color.WHITE)
            background = makeRoundedBg("#2EFFFFFF".toColorInt(), 8f, "#55FFFFFF".toColorInt(), 0.9f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }

        btnTopQualityOnly = TextView(activity).apply {
            text = "FHD"
            textSize = 11.5f
            typeface = fontBold
            setTextColor(Color.WHITE)
            background = makeRoundedBg("#33E50914".toColorInt(), 8f, "#88FF4757".toColorInt(), 1f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }

        btnTopSubtitles = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createSubtitlesCcIcon(activity, Color.WHITE))
            background = makeRoundedBg("#2EFFFFFF".toColorInt(), 8f, "#4DFFFFFF".toColorInt(), 0.8f)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) }
        }

        btnTopPip = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createPipMiniPlayerIcon(activity, Color.WHITE))
            background = makeRoundedBg("#2EFFFFFF".toColorInt(), 8f, "#4DFFFFFF".toColorInt(), 0.8f)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener {
                enterMiniPipMode()
            }
        }

        btnTopLock = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createLockIcon(activity, Color.WHITE, locked = false))
            background = makeRoundedBg("#2EFFFFFF".toColorInt(), 8f, "#4DFFFFFF".toColorInt(), 0.8f)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(8) }
            setOnClickListener {
                isControlsLocked = true
                hideControlsSmoothly()
                btnUnlockScreenOnly.visibility = View.VISIBLE
            }
        }

        topBar.addView(btnMinimizePlayer)
        topBar.addView(tvTopPlayerTitle)
        topBar.addView(btnTopServerOnly)
        topBar.addView(btnTopQualityOnly)
        topBar.addView(btnTopSubtitles)
        topBar.addView(btnTopPip)
        topBar.addView(btnTopLock)
        controlsOverlay.addView(topBar)

        // =========================================================================
        // أزرار الوسط المطابقة للفيديو 100%:
        // [Rewind]   |   [Play/Pause أو Loader دائري أبيض في نفس المكان]   |   [Forward]
        // مع الدائرة الحمراء التفاعلية عند اللمس كما في الفيديو
        // =========================================================================
        val centerControlsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        // 1. زر الترجيع (يسار)
        btnRewind15Container = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply {
                marginEnd = dp(24)
            }
        }
        btnRewind15BgCircle = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor("#B38B0000".toColorInt())
            }
            alpha = 0f
            scaleX = 0.7f
            scaleY = 0.7f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        btnRewind15 = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createCircularSeekArrowIcon(activity, isForward = false))
            setPadding(dp(15), dp(15), dp(15), dp(15))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        btnRewind15Container.addView(btnRewind15BgCircle)
        btnRewind15Container.addView(btnRewind15)

        // 2. خانة الوسط الموحدة (يظهر فيها إما Play/Pause أو الـ Loader الأبيض الدائري، ولا يظهران معاً أبداً!)
        centerActionSlot = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(74))
        }
        btnCenterPlayPauseBgCircle = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor("#B38B0000".toColorInt())
            }
            alpha = 0f
            scaleX = 0.7f
            scaleY = 0.7f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        btnCenterPlayPause = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createPlayPauseIcon(activity, isPlaying = true))
            setPadding(dp(18), dp(18), dp(18), dp(18))
            visibility = View.INVISIBLE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        centerBufferingSpinner = ProgressBar(activity).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER)
        }
        centerActionSlot.addView(btnCenterPlayPauseBgCircle)
        centerActionSlot.addView(btnCenterPlayPause)
        centerActionSlot.addView(centerBufferingSpinner)

        // 3. زر التقديم (يمين)
        btnForward15Container = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply {
                marginStart = dp(24)
            }
        }
        btnForward15BgCircle = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor("#B38B0000".toColorInt())
            }
            alpha = 0f
            scaleX = 0.7f
            scaleY = 0.7f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        btnForward15 = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createCircularSeekArrowIcon(activity, isForward = true))
            setPadding(dp(15), dp(15), dp(15), dp(15))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        btnForward15Container.addView(btnForward15BgCircle)
        btnForward15Container.addView(btnForward15)

        centerControlsRow.addView(btnRewind15Container)
        centerControlsRow.addView(centerActionSlot)
        centerControlsRow.addView(btnForward15Container)
        controlsOverlay.addView(centerControlsRow)

        val bottomPlayerBar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val seekRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        tvElapsed = TextView(activity).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = fontBold
        }

        seekBar = SeekBar(activity).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf("#E50914".toColorInt())
            secondaryProgressTintList = ColorStateList.valueOf("#55FFFFFF".toColorInt())
            thumbTintList = ColorStateList.valueOf("#E50914".toColorInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        tvDuration = TextView(activity).apply {
            text = "00:00"
            setTextColor("#CBD5E1".toColorInt())
            textSize = 11.5f
            typeface = fontBold
        }

        btnInlineFullscreen = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createFullscreenIcon(activity, isFullscreen = false))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginStart = dp(6)
            }
            setOnClickListener { applyFullscreenState(!isFullscreen) }
        }

        seekRow.addView(tvElapsed)
        seekRow.addView(seekBar)
        seekRow.addView(tvDuration)
        seekRow.addView(btnInlineFullscreen)
        bottomPlayerBar.addView(seekRow)

        fullscreenBottomActionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        btnBottomSubtitles = createPlayerBottomActionChip(
            icon = VectorIcons.createSubtitlesCcIcon(activity, Color.WHITE),
            label = "Subtitles"
        ) {}

        btnBottomAudio = createPlayerBottomActionChip(
            icon = VectorIcons.createAudioSpeakerIcon(activity, Color.WHITE),
            label = "Audio"
        ) {}

        tvBottomSpeedLabel = TextView(activity).apply {
            text = "Speed: 1x"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = fontBold
        }
        btnBottomSpeed = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#24FFFFFF".toColorInt(), 16f, "#40FFFFFF".toColorInt(), 0.8f)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            addView(tvBottomSpeedLabel)
        }

        tvBottomAspectLabel = TextView(activity).apply {
            text = "Scale: Fit"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = fontBold
        }
        btnBottomAspect = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#24FFFFFF".toColorInt(), 16f, "#40FFFFFF".toColorInt(), 0.8f)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            val ivScale = ImageView(activity).apply {
                setImageDrawable(VectorIcons.createAspectRatioIcon(activity, Color.WHITE))
                layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(6) }
            }
            addView(ivScale)
            addView(tvBottomAspectLabel)
            setOnClickListener {
                resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                playerView.resizeMode = resizeModes[resizeModeIndex]
                tvBottomAspectLabel.text = "Scale: ${resizeLabels[resizeModeIndex]}"
            }
        }

        fullscreenBottomActionsRow.addView(btnBottomSubtitles)
        fullscreenBottomActionsRow.addView(btnBottomAudio)
        fullscreenBottomActionsRow.addView(btnBottomSpeed)
        fullscreenBottomActionsRow.addView(btnBottomAspect)
        bottomPlayerBar.addView(fullscreenBottomActionsRow)

        controlsOverlay.addView(bottomPlayerBar)
        playerContainer.addView(controlsOverlay)

        // الخط الفاصل السفلي بين المشغل واسم الفيلم تحته
        vBottomPlayerSeparator = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2),
                Gravity.BOTTOM
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.TRANSPARENT,
                    "#45FFFFFF".toColorInt(),
                    "#25FFFFFF".toColorInt(),
                    Color.TRANSPARENT
                )
            )
        }
        playerContainer.addView(vBottomPlayerSeparator)

        btnUnlockScreenOnly = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#CC000000".toColorInt(), 20f, "#99FFFFFF".toColorInt(), 1f)
            setPadding(dp(12), dp(7), dp(14), dp(7))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) }
            val ivLock = ImageView(activity).apply {
                setImageDrawable(VectorIcons.createLockIcon(activity, Color.WHITE, locked = true))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
            }
            val tvUnlock = TextView(activity).apply {
                text = "Tap to Unlock"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = fontBold
            }
            addView(ivLock)
            addView(tvUnlock)
            setOnClickListener {
                isControlsLocked = false
                visibility = View.GONE
                showControlsWithFade()
            }
        }
        playerContainer.addView(btnUnlockScreenOnly)

        animateSubtitlePositionForControls(controlsVisible = true, animate = false)
        return playerContainer
    }

    /**
     * تبديل سلس بين زر Play/Pause والـ Loader الدائري الأبيض في نفس المكان بالضبط كما في الفيديو
     */
    private fun setBufferingState(buffering: Boolean) {
        isBufferingNow = buffering
        if (buffering) {
            btnCenterPlayPause.visibility = View.INVISIBLE
            centerBufferingSpinner.visibility = View.VISIBLE
            if (!controlsOverlay.isVisible && !isInPipMode && !isControlsLocked) {
                showControlsWithFade()
            }
        } else {
            centerBufferingSpinner.visibility = View.GONE
            btnCenterPlayPause.visibility = View.VISIBLE
        }
    }

    /**
     * وميض الدائرة الحمراء خلف زر الوسط أو التقديم/الترجيع عند الضغط كما في الفيديو المرفق
     */
    private fun pulseRedCircleFeedback(circleView: View) {
        circleView.animate().cancel()
        circleView.alpha = 0.95f
        circleView.scaleX = 0.78f
        circleView.scaleY = 0.78f
        circleView.animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .alpha(0f)
            .setDuration(340L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * رفع الترجمة فوق شريط التقدم عند ظهور التحكم وإعادتها للأسفل عند اختفائه
     */
    private fun animateSubtitlePositionForControls(controlsVisible: Boolean, animate: Boolean = true) {
        val targetLiftY = if (controlsVisible) {
            if (isFullscreen) -dpF(64f) else -dpF(34f)
        } else {
            0f
        }
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
            resetAutoHide()
            pulseRedCircleFeedback(btnCenterPlayPauseBgCircle)
            val exo = engine.player
            if (exo != null && exo.isPlaying) {
                engine.pause()
                updatePlayPauseVectorIcon(false)
            } else {
                engine.resume()
                updatePlayPauseVectorIcon(true)
            }
        }

        btnRewind15Container.setOnClickListener {
            resetAutoHide()
            pulseRedCircleFeedback(btnRewind15BgCircle)
            seekByDeltaMs(engine, -15_000L)
        }

        btnForward15Container.setOnClickListener {
            resetAutoHide()
            pulseRedCircleFeedback(btnForward15BgCircle)
            seekByDeltaMs(engine, 15_000L)
        }

        // زر Skip Intro يقدم بدقة لنهاية المقدمة الفعلية
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
            setBufferingState(true)
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
                resetAutoHide()
            }
            override fun onStopTrackingTouch(s: SeekBar?) {
                isUserSeeking = false
                val exo = engine.player ?: return
                val dur = exo.duration
                if (dur > 0) {
                    setBufferingState(true)
                    exo.seekTo((dur * (s?.progress ?: 0)) / 1000L)
                }
            }
        })

        val gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isControlsLocked) {
                    btnUnlockScreenOnly.isVisible = !btnUnlockScreenOnly.isVisible
                } else {
                    if (controlsOverlay.isVisible && controlsOverlay.alpha > 0.5f) {
                        hideControlsSmoothly()
                    } else {
                        showControlsWithFade()
                    }
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isControlsLocked) {
                    val width = playerView.width.coerceAtLeast(1)
                    if (e.x < width * 0.45f) {
                        showControlsWithFade()
                        pulseRedCircleFeedback(btnRewind15BgCircle)
                        seekByDeltaMs(engine, -15_000L)
                    } else if (e.x > width * 0.55f) {
                        showControlsWithFade()
                        pulseRedCircleFeedback(btnForward15BgCircle)
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
        if (!gestureIndicatorCard.isVisible) {
            gestureIndicatorCard.alpha = 1f
            gestureIndicatorCard.visibility = View.VISIBLE
        }
    }

    fun startProgressAndNetworkLoop(engine: PrimePlayerEngine) {
        progressJob?.cancel()

        progressJob = scope.launch {
            while (isActive) {
                delay(350)
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

                    // إظهار Skip Intro فقط إذا كان مسلسلاً (أو تم اكتشاف مقدمة فعلية)
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
                    if (lastSavedTick >= 14 && pos > 5000L) {
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

    /**
     * 1. نافذة اختيار السيرفر بتصميم زجاجي داكن فخم
     */
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

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
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
     * 2. نافذة اختيار الجودة بتصميم زجاجي داكن فخم
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

    /**
     * 3. نافذة اختيار الصوت بتصميم زجاجي داكن فخم
     */
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

    /**
     * 4. نافذة الترجمة بتصميم زجاجي داكن موحد وفخم (Monochrome Dark Frosted Glass):
     * - بدون أي ألوان مختلطة
     * - بدون أي نص WEB-DL أو أسماء ملفات طويلة (تعرض اسم اللغة النظيف فقط)
     * - تحتوي على خيار Shadow (خلفية شفافة مع ظل أسود قوي حول النص)
     */
    private fun showSubtitlesDialog() {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        val dialogWidthPx = min((screenW * 0.88f).toInt(), dp(360))
        val listHeightPx = min((screenH * 0.44f).toInt(), dp(185))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#F0101218".toColorInt(), 20f, "#33FFFFFF".toColorInt(), 1f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = ViewGroup.LayoutParams(dialogWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // شريط التبويبين الزجاجي الموحد
        val topHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val tabSwitchBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = makeRoundedBg("#14FFFFFF".toColorInt(), 12f, "#22FFFFFF".toColorInt(), 1f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tabTracksBtn = TextView(activity).apply {
            text = "Languages"
            textSize = 12.5f
            typeface = fontBold
            setTextColor("#0E1015".toColorInt())
            gravity = Gravity.CENTER
            background = makeRoundedBg(Color.WHITE, 10f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tabSettingsBtn = TextView(activity).apply {
            text = "Appearance"
            textSize = 12.5f
            typeface = fontBold
            setTextColor("#B0BAC9".toColorInt())
            gravity = Gravity.CENTER
            background = null
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        tabSwitchBox.addView(tabTracksBtn)
        tabSwitchBox.addView(tabSettingsBtn)

        val btnClosePopup = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = fontBold
            gravity = Gravity.CENTER
            background = makeRoundedBg("#1FFFFFFF".toColorInt(), 16f)
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(10) }
            setOnClickListener { dialog.dismiss() }
        }

        topHeaderRow.addView(tabSwitchBox)
        topHeaderRow.addView(btnClosePopup)
        root.addView(topHeaderRow)

        // =====================================================
        // التبويب 1: قائمة اللغات النظيفة (بدون WEB-DL1080p...)
        // =====================================================
        val tracksPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
        }

        val quickActionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        val btnDisableSubs = TextView(activity).apply {
            val isOff = selectedSubtitleId == "NONE"
            text = "Off"
            textSize = 11.5f
            typeface = fontBold
            setTextColor(if (isOff) "#0E1015".toColorInt() else Color.WHITE)
            background = makeRoundedBg(
                if (isOff) Color.WHITE else "#1AFFFFFF".toColorInt(),
                10f,
                "#33FFFFFF".toColorInt(),
                0.8f
            )
            setPadding(dp(14), dp(6), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                selectedSubtitleId = "NONE"
                tvSubtitleOverlay.visibility = View.GONE
                dialog.dismiss()
            }
        }

        val btnUploadSrt = TextView(activity).apply {
            text = "+ Local File"
            textSize = 11.5f
            typeface = fontBold
            setTextColor(Color.WHITE)
            background = makeRoundedBg("#1AFFFFFF".toColorInt(), 10f, "#33FFFFFF".toColorInt(), 0.8f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            gravity = Gravity.CENTER
            setOnClickListener {
                dialog.dismiss()
                onPickLocalSubtitleFile()
            }
        }

        val btnRefreshSubs = TextView(activity).apply {
            text = "Reload"
            textSize = 11.5f
            typeface = fontBold
            setTextColor(Color.WHITE)
            background = makeRoundedBg("#1AFFFFFF".toColorInt(), 10f, "#33FFFFFF".toColorInt(), 0.8f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        quickActionsRow.addView(btnDisableSubs)
        quickActionsRow.addView(btnUploadSrt)
        quickActionsRow.addView(btnRefreshSubs)
        tracksPanel.addView(quickActionsRow)

        val filterScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val filterRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        filterScroll.addView(filterRow)
        tracksPanel.addView(filterScroll)

        val tracksScroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeightPx)
        }
        val tracksContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        tracksScroll.addView(tracksContainer)
        tracksPanel.addView(tracksScroll)
        root.addView(tracksPanel)

        // =====================================================
        // التبويب 2: المظهر والمزامنة (Appearance & Sync)
        // =====================================================
        val settingsPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }

        val lblSize = TextView(activity).apply {
            text = "FONT SIZE"
            setTextColor("#94A3B8".toColorInt())
            textSize = 10.5f
            typeface = fontBold
            setPadding(0, 0, 0, dp(6))
        }
        settingsPanel.addView(lblSize)

        val sizeOptionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
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
        settingsPanel.addView(sizeOptionsRow)

        val lblBg = TextView(activity).apply {
            text = "TEXT BACKGROUND STYLE"
            setTextColor("#94A3B8".toColorInt())
            textSize = 10.5f
            typeface = fontBold
            setPadding(0, 0, 0, dp(6))
        }
        settingsPanel.addView(lblBg)

        val bgOptionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        // خيار Shadow (شفاف + ظل أسود واضح حول الحروف) + Frosted Glass + Dark Box
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
        settingsPanel.addView(bgOptionsRow)

        val syncRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#14FFFFFF".toColorInt(), 12f, "#28FFFFFF".toColorInt(), 1f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val tvSyncStatus = TextView(activity).apply {
            text = String.format(Locale.US, "Offset: %+.1fs", subtitleOffsetMs / 1000.0)
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = fontBold
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnMinusHalf = TextView(activity).apply {
            text = "-0.5s"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = fontBold
            background = makeRoundedBg("#24FFFFFF".toColorInt(), 8f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                subtitleOffsetMs -= 500L
                tvSyncStatus.text = String.format(Locale.US, "Offset: %+.1fs", subtitleOffsetMs / 1000.0)
            }
        }
        val btnResetSync = TextView(activity).apply {
            text = "Reset"
            setTextColor("#0E1015".toColorInt())
            textSize = 11.5f
            typeface = fontBold
            background = makeRoundedBg(Color.WHITE, 8f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                subtitleOffsetMs = 0L
                tvSyncStatus.text = "Offset: +0.0s"
            }
        }
        val btnPlusHalf = TextView(activity).apply {
            text = "+0.5s"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = fontBold
            background = makeRoundedBg("#24FFFFFF".toColorInt(), 8f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                subtitleOffsetMs += 500L
                tvSyncStatus.text = String.format(Locale.US, "Offset: %+.1fs", subtitleOffsetMs / 1000.0)
            }
        }
        syncRow.addView(tvSyncStatus)
        syncRow.addView(btnMinusHalf)
        syncRow.addView(btnResetSync)
        syncRow.addView(btnPlusHalf)
        settingsPanel.addView(syncRow)

        root.addView(settingsPanel)

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
            .apply()
        applySubtitleStyleToOverlay()
    }

    /**
     * تطبيق ستايل الترجمة: في وضع Shadow (Transparent) نضع ظل أسود قوي وواضح حول النص بدون خلفية
     */
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
            animateSubtitlePositionForControls(controlsVisible = false, animate = false)
        } else {
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
            btnTopServerOnly.visibility = View.VISIBLE
            btnTopQualityOnly.visibility = View.VISIBLE
            btnTopLock.visibility = View.VISIBLE
            fullscreenBottomActionsRow.visibility = View.VISIBLE
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

            btnTopServerOnly.visibility = View.GONE
            btnTopQualityOnly.visibility = View.GONE
            btnTopLock.visibility = View.GONE
            fullscreenBottomActionsRow.visibility = View.GONE
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

    fun setQualityBadgeLabel(qualityBadge: String) {
        btnTopQualityOnly.text = qualityBadge
    }

    fun setLoadingState(visible: Boolean) {
        setBufferingState(visible)
    }

    /**
     * تهيئة المشغل لفيلم أو حلقة جديدة مع تحديد نافذة الـ Intro بدقة (تتفعل للمسلسلات فقط أو عند اكتشاف مقدمة)
     */
    fun resetForNewStream(isActiveMode: Boolean, isSeries: Boolean = false) {
        isCurrentMediaSeries = isSeries
        hasSkippedIntroForCurrentMedia = false
        selectedQualityHeight = null
        btnSkipIntro.visibility = View.GONE

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
            // فحص ما إذا كانت الترجمة تحتوي على إشارة مقدمة [Intro] أو موسيقى البداية في الدقائق الأولى
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

    /**
     * إظهار التحكم بـ Fade-In ناعم + رفع الترجمة فوق شريط التقدم
     */
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
        controlsOverlay.postDelayed(autoHideRunnable, 3400)
    }

    /**
     * إخفاء التحكم بـ Fade-Out ناعم + إعادة الترجمة لمكانها الطبيعي في الأسفل
     */
    fun hideControlsSmoothly() {
        controlsOverlay.removeCallbacks(autoHideRunnable)
        if (!controlsOverlay.isVisible) return

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
            controlsOverlay.postDelayed(autoHideRunnable, 3400)
        }
    }

    private fun seekByDeltaMs(engine: PrimePlayerEngine, deltaMs: Long) {
        val exo = engine.player ?: return
        val dur = exo.duration
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        val newPos = if (dur > 0) min(target, dur) else target
        setBufferingState(true)
        exo.seekTo(newPos)
        if (dur > 0) {
            seekBar.progress = ((newPos * 1000L) / dur).toInt().coerceIn(0, 1000)
            tvElapsed.text = formatTimeMs(newPos)
        }
    }

    private fun createPlayerBottomActionChip(
        icon: Drawable,
        label: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#24FFFFFF".toColorInt(), 16f, "#40FFFFFF".toColorInt(), 0.8f)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }

            val iv = ImageView(activity).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(6) }
            }
            val tv = TextView(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = fontBold
            }
            addView(iv)
            addView(tv)
            setOnClickListener { onClick() }
        }
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