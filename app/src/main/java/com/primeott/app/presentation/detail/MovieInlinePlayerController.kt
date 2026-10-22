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
import android.net.TrafficStats
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
import com.primeott.app.presentation.common.AnimUtils
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
        private const val INTRO_START_MS = 10_000L
        private const val INTRO_END_MS = 95_000L
        private const val INTRO_SKIP_JUMP_MS = 85_000L
        private const val PREFS_SUB_STYLE = "prime_sub_style_prefs"
    }

    lateinit var playerContainer: FrameLayout
        private set
    lateinit var playerView: PlayerView
        private set

    private lateinit var tvSubtitleOverlay: TextView
    private lateinit var loadingContainer: LinearLayout
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var tvLoadingPercent: TextView
    private lateinit var tvNetworkSpeedBadge: TextView
    private lateinit var vTopEdgeSoftGradient: View

    private lateinit var controlsOverlay: FrameLayout
    private lateinit var btnMinimizePlayer: ImageView
    private lateinit var tvTopPlayerTitle: TextView
    private lateinit var btnTopPip: ImageView
    private lateinit var btnTopLock: ImageView
    private lateinit var btnTopServerOnly: TextView
    private lateinit var btnTopQualityOnly: TextView
    private lateinit var btnTopSubtitles: ImageView

    private lateinit var btnRewind15: ImageView
    private lateinit var btnCenterPlayPause: ImageView
    private lateinit var btnForward15: ImageView
    private lateinit var tvSeekFeedbackBadge: TextView
    private lateinit var holdSpeed2xPill: LinearLayout
    private lateinit var btnSkipIntro: LinearLayout

    // مؤشر الصوت (يمين) والإضاءة (يسار)
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
    private var currentPlaybackSpeed: Float = 1.0f
    private var hasSkippedIntroForCurrentMedia: Boolean = false
    private var selectedQualityHeight: Int? = null

    private var availableSubtitles: List<SubtitleTrack> = emptyList()
    private var activeSubtitleCues: List<SubtitleCue> = emptyList()
    private var selectedSubtitleId: String = ""
    private var subtitleOffsetMs: Long = 0L
    private var subtitleTextSizeSp: Float = 16f
    private var subtitleTextColor: Int = Color.WHITE
    private var subtitleBgAlpha: Int = 140

    private var resizeModeIndex: Int = 0
    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeLabels = listOf("Fit", "Stretch", "Zoom")

    private var progressJob: Job? = null
    private var lastRxBytes: Long = 0L
    private var lastRxTimestamp: Long = 0L
    private var lastSavedTick: Int = 0
    private var simulatedBufferPct: Int = 12
    private var attachedPlayer: Player? = null

    private val audioManager: AudioManager by lazy {
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var swipeMode: Int = 0 // 0 = None, 1 = Brightness (Left), 2 = Volume (Right)
    private var swipeStartY: Float = 0f
    private var swipeStartX: Float = 0f
    private var initialBrightness: Float = 0.6f
    private var initialVolume: Float = 0f
    private var currentGestureValue: Float = 0f

    private val playerStateListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseVectorIcon(isPlaying)
            if (isPlaying) {
                loadingContainer.visibility = View.GONE
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (!isInPipMode) {
                        loadingContainer.visibility = View.VISIBLE
                    }
                }
                Player.STATE_READY -> {
                    loadingContainer.visibility = View.GONE
                    updatePlayPauseVectorIcon(attachedPlayer?.isPlaying == true)
                }
                Player.STATE_ENDED -> {
                    loadingContainer.visibility = View.GONE
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
        subtitleBgAlpha = prefs.getInt("sub_bg_alpha", 140)

        playerContainer = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            clipChildren = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(210)
            )
        }

        // المشغل الصغير يبدأ دائماً بـ RESIZE_MODE_FIT بدون أي تقريب (Zoom)
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

        tvSubtitleOverlay = TextView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                setMargins(dp(20), 0, dp(20), dp(28))
            }
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp)
            typeface = fontBold
            setTextColor(subtitleTextColor)
            setShadowLayer(8f, 0f, 3f, Color.BLACK)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            visibility = View.GONE
        }
        applySubtitleStyleToOverlay()
        playerContainer.addView(tvSubtitleOverlay)

        // Loader أنيق يعرض نسبة التحميل المئوية + سرعة التحميل الحقيقية بدون جمل عشوائية
        loadingContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = makeRoundedBg("#B8080A10".toColorInt(), 20f, "#2EFFFFFF".toColorInt(), 1f)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        val spinnerFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        loadingSpinner = ProgressBar(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            indeterminateTintList = ColorStateList.valueOf("#E50914".toColorInt())
        }
        tvLoadingPercent = TextView(activity).apply {
            text = "25%"
            setTextColor(Color.WHITE)
            textSize = 10f
            typeface = fontBold
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        spinnerFrame.addView(loadingSpinner)
        spinnerFrame.addView(tvLoadingPercent)

        tvNetworkSpeedBadge = TextView(activity).apply {
            text = "0 KB/s"
            setTextColor("#CBD5E1".toColorInt())
            textSize = 10.5f
            typeface = fontBold
            setPadding(0, dp(4), 0, 0)
        }
        loadingContainer.addView(spinnerFrame)
        loadingContainer.addView(tvNetworkSpeedBadge)
        playerContainer.addView(loadingContainer)

        // كبسولة السرعة المضاعفة (2x Speed) بأيقونة Vector مرسومة وبدون أي إيموجي
        holdSpeed2xPill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#D90E121B".toColorInt(), 20f, "#80FFFFFF".toColorInt(), 1f)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
            elevation = dpF(8f)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(48)
            }
            val ivFast = ImageView(activity).apply {
                setImageDrawable(VectorIcons.createFastForwardDoubleArrowIcon(activity, "#38BDF8".toColorInt()))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
            }
            val tvLabel = TextView(activity).apply {
                text = "2x Speed"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = fontBold
            }
            addView(ivFast)
            addView(tvLabel)
        }
        playerContainer.addView(holdSpeed2xPill)

        // مؤشر الصوت (يمين) والإضاءة (يسار) أثناء السحب العمودي
        gestureIndicatorCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#D90E121B".toColorInt(), 22f, "#4DFFFFFF".toColorInt(), 1f)
            setPadding(dp(14), dp(8), dp(16), dp(8))
            visibility = View.GONE
            elevation = dpF(10f)
            layoutParams = FrameLayout.LayoutParams(
                dp(170),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(48)
            }
        }
        ivGestureIcon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
        }
        pbGestureLevel = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 50
            progressTintList = ColorStateList.valueOf("#E50914".toColorInt())
            progressBackgroundTintList = ColorStateList.valueOf("#33FFFFFF".toColorInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(5), 1f).apply { marginEnd = dp(8) }
        }
        tvGesturePercent = TextView(activity).apply {
            text = "50%"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = fontBold
        }
        gestureIndicatorCard.addView(ivGestureIcon)
        gestureIndicatorCard.addView(pbGestureLevel)
        gestureIndicatorCard.addView(tvGesturePercent)
        playerContainer.addView(gestureIndicatorCard)

        tvSeekFeedbackBadge = TextView(activity).apply {
            text = "+15s"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = fontBold
            background = makeRoundedBg("#BF000000".toColorInt(), 20f, "#55FFFFFF".toColorInt(), 1f)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                topMargin = dp(48)
            }
        }
        playerContainer.addView(tvSeekFeedbackBadge)

        btnSkipIntro = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#E610131C".toColorInt(), 20f, "#D9FFFFFF".toColorInt(), 1.2f)
            setPadding(dp(14), dp(7), dp(12), dp(7))
            visibility = View.GONE
            elevation = dpF(6f)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                setMargins(0, 0, dp(14), dp(42))
            }
        }
        val tvSkipIntroLabel = TextView(activity).apply {
            text = "Skip Intro"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = fontBold
        }
        val ivSkipIntroArrow = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createSkipNextIcon(activity, Color.WHITE))
            layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                marginStart = dp(6)
            }
        }
        btnSkipIntro.addView(tvSkipIntroLabel)
        btnSkipIntro.addView(ivSkipIntroArrow)
        AnimUtils.attachPressBounce(btnSkipIntro)
        playerContainer.addView(btnSkipIntro)

        // طبقة التحكم
        controlsOverlay = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf("#B3000000".toColorInt(), "#38000000".toColorInt(), "#CC000000".toColorInt())
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
            background = makeRoundedBg("#38FFFFFF".toColorInt(), 18f)
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

        // زر Server وزر Quality يظهران فقط في المشغل الكبير (Fullscreen) كما طلبت
        btnTopServerOnly = TextView(activity).apply {
            text = "Server"
            textSize = 11.5f
            typeface = fontBold
            setTextColor(Color.WHITE)
            background = makeRoundedBg("#33FFFFFF".toColorInt(), 8f, "#66FFFFFF".toColorInt(), 0.9f)
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
            background = makeRoundedBg("#33E50914".toColorInt(), 8f, "#99FF4757".toColorInt(), 1f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }

        // في المشغل الصغير يظهر فقط زر CC وزر Mini PiP
        btnTopSubtitles = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createSubtitlesCcIcon(activity, Color.WHITE))
            background = makeRoundedBg("#33FFFFFF".toColorInt(), 8f, "#55FFFFFF".toColorInt(), 0.8f)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) }
        }

        btnTopPip = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createPipMiniPlayerIcon(activity, Color.WHITE))
            background = makeRoundedBg("#33FFFFFF".toColorInt(), 8f, "#55FFFFFF".toColorInt(), 0.8f)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener {
                enterMiniPipMode()
            }
        }

        btnTopLock = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createLockIcon(activity, Color.WHITE, locked = false))
            background = makeRoundedBg("#33FFFFFF".toColorInt(), 8f, "#55FFFFFF".toColorInt(), 0.8f)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(8) }
            setOnClickListener {
                isControlsLocked = true
                controlsOverlay.visibility = View.GONE
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

        // أزرار الوسط بدون أي دوائر خلفية (Pure Clean Vector Icons)
        val centerControlsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        btnRewind15 = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createSeek15Icon(activity, isForward = false))
            background = null
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(32)
            }
        }

        btnCenterPlayPause = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createPlayPauseIcon(activity, isPlaying = true))
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }

        btnForward15 = ImageView(activity).apply {
            setImageDrawable(VectorIcons.createSeek15Icon(activity, isForward = true))
            background = null
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(32)
            }
        }

        AnimUtils.attachPressBounce(btnRewind15)
        AnimUtils.attachPressBounce(btnCenterPlayPause)
        AnimUtils.attachPressBounce(btnForward15)

        centerControlsRow.addView(btnRewind15)
        centerControlsRow.addView(btnCenterPlayPause)
        centerControlsRow.addView(btnForward15)
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
            text = "0:00"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = fontBold
        }

        seekBar = SeekBar(activity).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf("#E50914".toColorInt())
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        tvDuration = TextView(activity).apply {
            text = "--:--"
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

        // أزرار الشريط السفلي في وضع Fullscreen مرتبة ومنظمة بمسافات واضحة
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
            background = makeRoundedBg("#26FFFFFF".toColorInt(), 16f, "#45FFFFFF".toColorInt(), 0.8f)
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
            background = makeRoundedBg("#26FFFFFF".toColorInt(), 16f, "#45FFFFFF".toColorInt(), 0.8f)
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
                showControlsImmediately()
            }
        }
        playerContainer.addView(btnUnlockScreenOnly)

        return playerContainer
    }

    @SuppressLint("ClickableViewAccessibility")
    fun bindEngineAndActions(
        engine: PrimePlayerEngine,
        getProviders: () -> List<StreamSource>,
        getCurrentProviderIdx: () -> Int
    ) {
        btnCenterPlayPause.setOnClickListener {
            resetAutoHide()
            val exo = engine.player
            if (exo != null && exo.isPlaying) {
                engine.pause()
                updatePlayPauseVectorIcon(false)
            } else {
                engine.resume()
                updatePlayPauseVectorIcon(true)
            }
        }

        btnRewind15.setOnClickListener {
            resetAutoHide()
            seekByDeltaMs(engine, -15_000L, "-15s")
        }

        btnForward15.setOnClickListener {
            resetAutoHide()
            seekByDeltaMs(engine, 15_000L, "+15s")
        }

        // زر Skip Intro يقدم المقدمة بدقة (+85s) ويختفي فوراً
        btnSkipIntro.setOnClickListener {
            val exo = engine.player ?: return@setOnClickListener
            hasSkippedIntroForCurrentMedia = true
            btnSkipIntro.visibility = View.GONE
            val dur = exo.duration
            val targetPos = (exo.currentPosition + INTRO_SKIP_JUMP_MS).let { t ->
                if (dur > 0) min(t, (dur - 5000L).coerceAtLeast(0L)) else t
            }
            exo.seekTo(targetPos)
            if (dur > 0) {
                seekBar.progress = ((targetPos * 1000L) / dur).toInt().coerceIn(0, 1000)
                tvElapsed.text = formatTimeMs(targetPos)
            }
            showSeekFeedbackToast("Intro Skipped")
        }

        btnTopServerOnly.setOnClickListener {
            showServersOnlyPopup(getProviders(), getCurrentProviderIdx())
        }

        btnTopQualityOnly.setOnClickListener {
            showQualityOnlyPopup(engine)
        }

        val openAudioAction = {
            showAudioTracksDialog(engine)
        }
        btnBottomAudio.setOnClickListener { openAudioAction() }

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
                    exo.seekTo((dur * (s?.progress ?: 0)) / 1000L)
                }
            }
        })

        // الإيماءات: لمسة واحدة، لمستان للتقديم 15s، ضغط مطول لسرعة 2x، وسحب عمودي للصوت (يمين) والإضاءة (يسار)
        val gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isControlsLocked) {
                    btnUnlockScreenOnly.isVisible = !btnUnlockScreenOnly.isVisible
                } else {
                    if (controlsOverlay.isVisible) {
                        hideControlsSmoothly()
                    } else {
                        showControlsImmediately()
                    }
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isControlsLocked) {
                    val width = playerView.width.coerceAtLeast(1)
                    if (e.x < width * 0.45f) {
                        seekByDeltaMs(engine, -15_000L, "-15s")
                    } else if (e.x > width * 0.55f) {
                        seekByDeltaMs(engine, 15_000L, "+15s")
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
                    holdSpeed2xPill.animate().alpha(1f).setDuration(140L).start()
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
                        val touchSlop = dp(18)

                        if (swipeMode == 0 && abs(dy) > touchSlop && abs(dy) > dx * 1.4f) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            val width = playerView.width.coerceAtLeast(1)
                            swipeMode = if (swipeStartX < width * 0.5f) 1 else 2
                            swipeStartY = ev.y
                        }

                        if (swipeMode != 0) {
                            val height = playerView.height.coerceAtLeast(dp(180)).toFloat()
                            val deltaPercent = (swipeStartY - ev.y) / (height * 0.72f)

                            if (swipeMode == 1) {
                                // اليسار: التحكم في الإضاءة (Brightness)
                                currentGestureValue = (initialBrightness + deltaPercent).coerceIn(0.05f, 1.0f)
                                val lp = activity.window.attributes
                                lp.screenBrightness = currentGestureValue
                                activity.window.attributes = lp
                                showSwipeGestureIndicator(
                                    isBrightness = true,
                                    percent = (currentGestureValue * 100).toInt()
                                )
                            } else if (swipeMode == 2) {
                                // اليمين: التحكم في الصوت (Sound)
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
                        holdSpeed2xPill.animate().alpha(0f).setDuration(140L).withEndAction {
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
                VectorIcons.createBrightnessSunIcon(activity, "#FFD700".toColorInt())
            } else {
                VectorIcons.createAudioSpeakerIcon(activity, "#38BDF8".toColorInt())
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
        lastRxBytes = getSafeTotalRxBytes()
        lastRxTimestamp = System.currentTimeMillis()

        progressJob = scope.launch {
            while (isActive) {
                delay(400)
                if (!playerContainer.isVisible) continue

                val p = engine.player
                if (p != null && p !== attachedPlayer) {
                    attachedPlayer?.removeListener(playerStateListener)
                    attachedPlayer = p
                    p.addListener(playerStateListener)
                    updatePlayPauseVectorIcon(p.isPlaying)
                }

                if (loadingContainer.isVisible) {
                    updateLiveLoaderPercentAndSpeed(p)
                }

                if (p == null) continue
                if (p.isPlaying && loadingContainer.isVisible) {
                    loadingContainer.visibility = View.GONE
                }

                val pos = p.currentPosition.coerceAtLeast(0L)
                val dur = p.duration

                if (dur > 0) {
                    if (!isUserSeeking && controlsOverlay.isVisible) {
                        seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                        tvElapsed.text = formatTimeMs(pos)
                        tvDuration.text = formatTimeMs(dur)
                    }

                    // زر Skip Intro لا يظهر أبداً في وضع PiP
                    val shouldShowSkipIntro = !isInPipMode &&
                            !activity.isInPictureInPictureMode &&
                            !hasSkippedIntroForCurrentMedia &&
                            dur > 240_000L &&
                            pos in INTRO_START_MS..INTRO_END_MS

                    if (shouldShowSkipIntro && btnSkipIntro.visibility != View.VISIBLE) {
                        btnSkipIntro.alpha = 0f
                        btnSkipIntro.visibility = View.VISIBLE
                        btnSkipIntro.animate().alpha(1f).setDuration(220L).start()
                    } else if (!shouldShowSkipIntro && btnSkipIntro.visibility == View.VISIBLE) {
                        btnSkipIntro.visibility = View.GONE
                    }

                    lastSavedTick++
                    if (lastSavedTick >= 12 && pos > 5000L) {
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

    private fun getSafeTotalRxBytes(): Long {
        val uidBytes = TrafficStats.getUidRxBytes(Process.myUid())
        return if (uidBytes != TrafficStats.UNSUPPORTED.toLong() && uidBytes > 0L) {
            uidBytes
        } else {
            TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        }
    }

    private fun updateLiveLoaderPercentAndSpeed(player: Player?) {
        val now = System.currentTimeMillis()
        val currentBytes = getSafeTotalRxBytes()
        val elapsedMs = (now - lastRxTimestamp).coerceAtLeast(1L)
        if (elapsedMs >= 400L) {
            val deltaBytes = (currentBytes - lastRxBytes).coerceAtLeast(0L)
            val bytesPerSec = (deltaBytes * 1000L) / elapsedMs
            lastRxBytes = currentBytes
            lastRxTimestamp = now

            val speedStr = when {
                bytesPerSec >= 1_048_576L -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1_048_576.0)
                bytesPerSec >= 1024L -> String.format(Locale.US, "%d KB/s", bytesPerSec / 1024L)
                else -> "0 KB/s"
            }
            tvNetworkSpeedBadge.text = speedStr

            val realBuf = player?.bufferedPercentage ?: 0
            simulatedBufferPct = if (realBuf in 1..99) {
                realBuf
            } else {
                (simulatedBufferPct + if (bytesPerSec > 8000L) 14 else 6).coerceAtMost(96)
            }
            tvLoadingPercent.text = "$simulatedBufferPct%"
        }
    }

    /**
     * 1. نافذة اختيار السيرفر فقط (تظهر في المشغل الكبير)
     */
    private fun showServersOnlyPopup(providers: List<StreamSource>, currentIdx: Int) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = dp(280)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#141720".toColorInt(), 16f, "#2C3242".toColorInt(), 1f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = TextView(activity).apply {
            text = "Select Server"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = fontBold
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        card.addView(header)

        val divider = View(activity).apply {
            setBackgroundColor("#24FFFFFF".toColorInt())
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
                background = if (isSel) makeRoundedBg("#26E50914".toColorInt(), 10f, "#80E50914".toColorInt(), 1f) else null
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
                    setImageDrawable(VectorIcons.createCheckMarkIcon(activity, "#E50914".toColorInt()))
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
     * 2. نافذة اختيار الجودة فقط (تظهر في المشغل الكبير)
     */
    private fun showQualityOnlyPopup(engine: PrimePlayerEngine) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = dp(265)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#141720".toColorInt(), 16f, "#2C3242".toColorInt(), 1f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = TextView(activity).apply {
            text = "Stream Quality"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = fontBold
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        card.addView(header)

        val divider = View(activity).apply {
            setBackgroundColor("#24FFFFFF".toColorInt())
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
            setTextColor(if (isAutoSelected) "#E50914".toColorInt() else Color.WHITE)
            textSize = 13.5f
            typeface = fontBold
            background = if (isAutoSelected) makeRoundedBg("#24E50914".toColorInt(), 10f) else null
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
                setTextColor(if (isSel) "#E50914".toColorInt() else "#CBD5E1".toColorInt())
                textSize = 13.5f
                typeface = fontBold
                background = if (isSel) makeRoundedBg("#24E50914".toColorInt(), 10f) else null
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
     * 3. نافذة اختيار المسارات الصوتية (Audio Tracks)
     */
    private fun showAudioTracksDialog(engine: PrimePlayerEngine) {
        val exo = engine.player
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = dp(280)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#141720".toColorInt(), 16f, "#2C3242".toColorInt(), 1f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = TextView(activity).apply {
            text = "Audio Tracks"
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
                    ?: (if (langCode.isNotEmpty()) Locale(langCode).displayLanguage else "Audio Track ${addedCount + 1}")
                val channels = if (format.channelCount > 0) " • ${format.channelCount}ch" else ""
                val isSelected = group.isTrackSelected(tIdx)

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = if (isSelected) makeRoundedBg("#24E50914".toColorInt(), 10f) else null
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
                    text = "$label$channels"
                    setTextColor(if (isSelected) Color.WHITE else "#CBD5E1".toColorInt())
                    textSize = 13.5f
                    typeface = fontBold
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(tv)
                if (isSelected) {
                    val ivCheck = ImageView(activity).apply {
                        setImageDrawable(VectorIcons.createCheckMarkIcon(activity, "#E50914".toColorInt()))
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
                text = "Default Stream Audio"
                setTextColor("#E50914".toColorInt())
                textSize = 13.5f
                typeface = fontBold
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
     * 4. نافذة الترجمة المبنية من الصفر (10/10):
     * حجم مدمج وأنيق يناسب الوضع الصغير ووضع الشاشة الكاملة (Fullscreen) مع تبويبين واضحين:
     * [Tracks] لاختيار لغة الترجمة أو إيقافها أو رفع ملف خارجي
     * [Settings] للتحكم في حجم الخط ولونه وخلفيته ومزامنة الوقت
     */
    private fun showSubtitlesDialog() {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        val dialogWidthPx = min((screenW * 0.90f).toInt(), dp(380))
        val listHeightPx = min((screenH * 0.46f).toInt(), dp(195))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#12151E".toColorInt(), 18f, "#2E364A".toColorInt(), 1f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = ViewGroup.LayoutParams(dialogWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // الشريط العلوي: التبويبان [Tracks] و [Settings] + زر الإغلاق
        val topHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val tabSwitchBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = makeRoundedBg("#1A1F2C".toColorInt(), 12f, "#2B3245".toColorInt(), 1f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tabTracksBtn = TextView(activity).apply {
            text = "Subtitles (${availableSubtitles.size})"
            textSize = 12.5f
            typeface = fontBold
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = makeRoundedBg("#E50914".toColorInt(), 10f)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tabSettingsBtn = TextView(activity).apply {
            text = "Style & Sync"
            textSize = 12.5f
            typeface = fontBold
            setTextColor("#94A3B8".toColorInt())
            gravity = Gravity.CENTER
            background = null
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        tabSwitchBox.addView(tabTracksBtn)
        tabSwitchBox.addView(tabSettingsBtn)

        val btnClosePopup = TextView(activity).apply {
            text = "✕"
            setTextColor("#CBD5E1".toColorInt())
            textSize = 14f
            typeface = fontBold
            gravity = Gravity.CENTER
            background = makeRoundedBg("#1E2433".toColorInt(), 16f)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(10) }
            setOnClickListener { dialog.dismiss() }
        }

        topHeaderRow.addView(tabSwitchBox)
        topHeaderRow.addView(btnClosePopup)
        root.addView(topHeaderRow)

        // =====================================================
        // القسم الأول: قائمة الترجمات (Tracks Section)
        // =====================================================
        val tracksPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
        }

        // شريط الإجراءات السريعة: Off | Upload SRT | Refresh
        val quickActionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        val btnDisableSubs = TextView(activity).apply {
            val isOff = selectedSubtitleId == "NONE"
            text = "No Subtitles"
            textSize = 11.5f
            typeface = fontBold
            setTextColor(if (isOff) Color.WHITE else "#F87171".toColorInt())
            background = makeRoundedBg(
                if (isOff) "#E50914".toColorInt() else "#24F87171".toColorInt(),
                10f,
                "#66F87171".toColorInt(),
                0.8f
            )
            setPadding(dp(11), dp(6), dp(11), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                selectedSubtitleId = "NONE"
                tvSubtitleOverlay.visibility = View.GONE
                Toast.makeText(activity, "Subtitles Disabled", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        val btnUploadSrt = TextView(activity).apply {
            text = "+ Upload SRT/VTT"
            textSize = 11.5f
            typeface = fontBold
            setTextColor("#38BDF8".toColorInt())
            background = makeRoundedBg("#1A38BDF8".toColorInt(), 10f, "#6638BDF8".toColorInt(), 0.8f)
            setPadding(dp(11), dp(6), dp(11), dp(6))
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
            text = "Refresh"
            textSize = 11.5f
            typeface = fontBold
            setTextColor(Color.WHITE)
            background = makeRoundedBg("#24FFFFFF".toColorInt(), 10f)
            setPadding(dp(11), dp(6), dp(11), dp(6))
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
        // القسم الثاني: إعدادات الخط واللون والمزامنة (Style & Sync Section)
        // =====================================================
        val settingsPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }

        // 1. حجم الخط (Font Size)
        val lblSize = TextView(activity).apply {
            text = "TEXT SIZE"
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
            24f to "Extra"
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
                    setTextColor(Color.WHITE)
                    background = makeRoundedBg(
                        if (isSel) "#E50914".toColorInt() else "#1C2230".toColorInt(),
                        10f,
                        if (isSel) "#FF6B72".toColorInt() else "#33FFFFFF".toColorInt()
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

        // 2. لون النص (Text Color)
        val lblColor = TextView(activity).apply {
            text = "TEXT COLOR"
            setTextColor("#94A3B8".toColorInt())
            textSize = 10.5f
            typeface = fontBold
            setPadding(0, 0, 0, dp(6))
        }
        settingsPanel.addView(lblColor)

        val colorOptionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val colorPresets = listOf(
            Color.WHITE to "White",
            "#FFD700".toColorInt() to "Yellow",
            "#38BDF8".toColorInt() to "Cyan",
            "#4ADE80".toColorInt() to "Green"
        )

        fun rebuildColorPills() {
            colorOptionsRow.removeAllViews()
            colorPresets.forEach { (cVal, label) ->
                val isSel = subtitleTextColor == cVal
                val pill = TextView(activity).apply {
                    text = label
                    textSize = 12f
                    typeface = fontBold
                    gravity = Gravity.CENTER
                    setTextColor(cVal)
                    background = makeRoundedBg(
                        if (isSel) "#28FFFFFF".toColorInt() else "#1C2230".toColorInt(),
                        10f,
                        if (isSel) cVal else "#33FFFFFF".toColorInt(),
                        if (isSel) 1.5f else 1f
                    )
                    setPadding(dp(6), dp(7), dp(6), dp(7))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(5)
                    }
                    setOnClickListener {
                        subtitleTextColor = cVal
                        saveAndApplySubtitleStyle()
                        rebuildColorPills()
                    }
                }
                colorOptionsRow.addView(pill)
            }
        }
        rebuildColorPills()
        settingsPanel.addView(colorOptionsRow)

        // 3. خلفية الترجمة (Background Opacity)
        val lblBg = TextView(activity).apply {
            text = "BACKGROUND STYLE"
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
        val bgPresets = listOf(
            0 to "Transparent",
            135 to "Semi-Dark",
            220 to "Solid Box"
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
                    setTextColor(Color.WHITE)
                    background = makeRoundedBg(
                        if (isSel) "#E50914".toColorInt() else "#1C2230".toColorInt(),
                        10f,
                        if (isSel) "#FF6B72".toColorInt() else "#33FFFFFF".toColorInt()
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

        // 4. مزامنة التوقيت (Time Sync Offset)
        val syncRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#191F2D".toColorInt(), 12f, "#2C354D".toColorInt(), 1f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val tvSyncStatus = TextView(activity).apply {
            text = String.format(Locale.US, "Sync: %+.1fs", subtitleOffsetMs / 1000.0)
            setTextColor("#38BDF8".toColorInt())
            textSize = 12.5f
            typeface = fontBold
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnMinusHalf = TextView(activity).apply {
            text = "-0.5s"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = fontBold
            background = makeRoundedBg("#283044".toColorInt(), 8f)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                subtitleOffsetMs -= 500L
                tvSyncStatus.text = String.format(Locale.US, "Sync: %+.1fs", subtitleOffsetMs / 1000.0)
            }
        }
        val btnResetSync = TextView(activity).apply {
            text = "0.0s"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = fontBold
            background = makeRoundedBg("#E50914".toColorInt(), 8f)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                subtitleOffsetMs = 0L
                tvSyncStatus.text = "Sync: +0.0s"
            }
        }
        val btnPlusHalf = TextView(activity).apply {
            text = "+0.5s"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = fontBold
            background = makeRoundedBg("#283044".toColorInt(), 8f)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            setOnClickListener {
                subtitleOffsetMs += 500L
                tvSyncStatus.text = String.format(Locale.US, "Sync: %+.1fs", subtitleOffsetMs / 1000.0)
            }
        }
        syncRow.addView(tvSyncStatus)
        syncRow.addView(btnMinusHalf)
        syncRow.addView(btnResetSync)
        syncRow.addView(btnPlusHalf)
        settingsPanel.addView(syncRow)

        root.addView(settingsPanel)

        // التبديل السلس بين التبويبين
        tabTracksBtn.setOnClickListener {
            tracksPanel.visibility = View.VISIBLE
            settingsPanel.visibility = View.GONE
            tabTracksBtn.background = makeRoundedBg("#E50914".toColorInt(), 10f)
            tabTracksBtn.setTextColor(Color.WHITE)
            tabSettingsBtn.background = null
            tabSettingsBtn.setTextColor("#94A3B8".toColorInt())
        }
        tabSettingsBtn.setOnClickListener {
            tracksPanel.visibility = View.GONE
            settingsPanel.visibility = View.VISIBLE
            tabSettingsBtn.background = makeRoundedBg("#E50914".toColorInt(), 10f)
            tabSettingsBtn.setTextColor(Color.WHITE)
            tabTracksBtn.background = null
            tabTracksBtn.setTextColor("#94A3B8".toColorInt())
        }

        var selectedLangFilter = "ALL"

        fun renderAvailableLanguageChipsAndTracks() {
            filterRow.removeAllViews()
            tracksContainer.removeAllViews()
            tabTracksBtn.text = "Subtitles (${availableSubtitles.size})"

            // إظهار اللغات المتوفرة فعلياً فقط
            val groupedLangs = availableSubtitles.groupBy {
                it.languageCode.take(2).lowercase(Locale.ROOT).ifEmpty { "en" }
            }
            val dynamicFilters = mutableListOf("ALL" to "All (${availableSubtitles.size})")
            groupedLangs.forEach { (code, list) ->
                val readableName = when (code) {
                    "ar" -> "Arabic"
                    "en" -> "English"
                    "fr" -> "French"
                    "es" -> "Spanish"
                    "tr" -> "Turkish"
                    "de" -> "German"
                    else -> list.first().languageLabel.substringBefore(" ").ifEmpty { code.uppercase() }
                }
                dynamicFilters.add(code to "$readableName (${list.size})")
            }

            dynamicFilters.forEach { (code, label) ->
                val isCurrent = selectedLangFilter == code
                val chip = TextView(activity).apply {
                    text = label
                    textSize = 11f
                    typeface = fontBold
                    setTextColor(Color.WHITE)
                    background = makeRoundedBg(
                        if (isCurrent) "#E50914".toColorInt() else "#1C2230".toColorInt(),
                        14f,
                        if (isCurrent) "#FF6B72".toColorInt() else "#2EFFFFFF".toColorInt()
                    )
                    setPadding(dp(11), dp(5), dp(11), dp(5))
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
                    text = "No subtitle tracks found. Tap Refresh or Upload SRT."
                    setTextColor("#94A3B8".toColorInt())
                    textSize = 12.5f
                    typeface = fontRegular
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(24), dp(12), dp(24))
                }
                tracksContainer.addView(emptyTv)
            } else {
                filtered.forEach { track ->
                    val isSelected = track.id == selectedSubtitleId
                    val isWebDl = track.releaseName.contains("WEB", true) || track.releaseName.contains("Stream", true)
                    val itemLayout = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = if (isSelected) makeRoundedBg("#26E50914".toColorInt(), 10f, "#E50914".toColorInt()) else null
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        setOnClickListener {
                            selectedSubtitleId = track.id
                            subtitleOffsetMs = 0L
                            scope.launch {
                                val parsed = onDownloadSubtitleTrack(track)
                                if (parsed.isNotEmpty()) {
                                    activeSubtitleCues = parsed
                                    tvSubtitleOverlay.visibility = View.VISIBLE
                                    Toast.makeText(activity, "Subtitle: ${track.languageLabel}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(activity, "Try another subtitle track", Toast.LENGTH_SHORT).show()
                                }
                            }
                            dialog.dismiss()
                        }
                    }

                    val textCol = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val langTv = TextView(activity).apply {
                        text = track.languageLabel
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        typeface = fontBold
                    }
                    val badgeText = if (isWebDl) "100% Synced" else "OpenSubtitles"
                    val relTv = TextView(activity).apply {
                        text = "${track.releaseName} • $badgeText"
                        setTextColor(if (isWebDl) "#34D399".toColorInt() else "#94A3B8".toColorInt())
                        textSize = 10.5f
                        typeface = fontRegular
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    textCol.addView(langTv)
                    textCol.addView(relTv)
                    itemLayout.addView(textCol)

                    if (isSelected) {
                        val ivCheck = ImageView(activity).apply {
                            setImageDrawable(VectorIcons.createCheckMarkIcon(activity, "#E50914".toColorInt()))
                            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(8) }
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
                btnRefreshSubs.text = "Refresh"
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

    private fun applySubtitleStyleToOverlay() {
        tvSubtitleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp)
        tvSubtitleOverlay.setTextColor(subtitleTextColor)
        tvSubtitleOverlay.background = if (subtitleBgAlpha <= 5) {
            null
        } else {
            makeRoundedBg(ColorUtils.setAlphaComponent(Color.BLACK, subtitleBgAlpha), 8f)
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
                    languageLabel = "Local File (${parsedCues.size} lines)",
                    releaseName = "Uploaded from Device",
                    url = uri.toString()
                )
                availableSubtitles = listOf(localTrack) + availableSubtitles
                selectedSubtitleId = localTrack.id
                activeSubtitleCues = parsedCues
                subtitleOffsetMs = 0L
                tvSubtitleOverlay.visibility = View.VISIBLE
                Toast.makeText(activity, "Local Subtitle Loaded (${parsedCues.size} lines)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "Could not parse .SRT / .VTT file", Toast.LENGTH_LONG).show()
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
        } else {
            showControlsImmediately()
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
            background = makeRoundedBg("#141720".toColorInt(), 14f, "#2C3242".toColorInt())
            setPadding(dp(22), dp(14), dp(22), dp(14))
        }

        speeds.forEach { (spd, label) ->
            val isSel = spd == currentPlaybackSpeed
            val tv = TextView(activity).apply {
                text = label
                textSize = 13.5f
                typeface = fontBold
                setTextColor(if (isSel) "#E50914".toColorInt() else Color.WHITE)
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

    /**
     * التبديل السلس بين المشغل الصغير (يعرض CC و PiP فقط) والمشغل الكبير (يعرض Server, Quality, Audio, Speed, Scale, Lock)
     */
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

            // في المشغل الصغير، نخفي Server و Quality و Lock ونعيد الأبعاد إلى FIT غير المقربة
            btnTopServerOnly.visibility = View.GONE
            btnTopQualityOnly.visibility = View.GONE
            btnTopLock.visibility = View.GONE
            fullscreenBottomActionsRow.visibility = View.GONE

            resizeModeIndex = 0
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            tvBottomAspectLabel.text = "Scale: Fit"

            playerContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(210)
            )
        }
        playerContainer.requestLayout()
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
        if (visible && !isInPipMode) {
            simulatedBufferPct = 15
            tvLoadingPercent.text = "15%"
            loadingContainer.visibility = View.VISIBLE
        } else {
            loadingContainer.visibility = View.GONE
        }
    }

    fun resetForNewStream(isActiveMode: Boolean) {
        hasSkippedIntroForCurrentMedia = false
        selectedQualityHeight = null
        simulatedBufferPct = 12
        btnSkipIntro.visibility = View.GONE
        tvElapsed.text = "0:00"
        tvDuration.text = "--:--"
        seekBar.progress = 0
        tvSubtitleOverlay.text = ""
        tvSubtitleOverlay.visibility = View.GONE
        loadingContainer.visibility = if (isActiveMode && !isInPipMode) View.VISIBLE else View.GONE
        tvLoadingPercent.text = "12%"
        tvNetworkSpeedBadge.text = "0 KB/s"
    }

    fun updateAvailableSubtitles(
        subs: List<SubtitleTrack>,
        activeCues: List<SubtitleCue>? = null,
        selectedId: String? = null
    ) {
        availableSubtitles = subs
        if (activeCues != null) activeSubtitleCues = activeCues
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

    fun showControlsImmediately() {
        if (isInPipMode) return
        controlsOverlay.removeCallbacks(autoHideRunnable)
        controlsOverlay.animate().cancel()
        controlsOverlay.alpha = 1f
        controlsOverlay.visibility = View.VISIBLE
        val p = attachedPlayer
        if (p != null) {
            updatePlayPauseVectorIcon(p.isPlaying)
            val dur = p.duration
            val pos = p.currentPosition.coerceAtLeast(0L)
            if (dur > 0 && !isUserSeeking) {
                seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                tvElapsed.text = formatTimeMs(pos)
                tvDuration.text = formatTimeMs(dur)
            }
        }
        controlsOverlay.postDelayed(autoHideRunnable, 3200)
    }

    fun hideControlsSmoothly() {
        controlsOverlay.removeCallbacks(autoHideRunnable)
        controlsOverlay.animate()
            .alpha(0f)
            .setDuration(140L)
            .withEndAction {
                controlsOverlay.visibility = View.GONE
                controlsOverlay.alpha = 1f
            }
            .start()
    }

    private fun resetAutoHide() {
        if (isInPipMode) return
        controlsOverlay.removeCallbacks(autoHideRunnable)
        controlsOverlay.alpha = 1f
        controlsOverlay.visibility = View.VISIBLE
        controlsOverlay.postDelayed(autoHideRunnable, 3200)
    }

    private fun seekByDeltaMs(engine: PrimePlayerEngine, deltaMs: Long, badgeLabel: String) {
        val exo = engine.player ?: return
        val dur = exo.duration
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        val newPos = if (dur > 0) min(target, dur) else target
        exo.seekTo(newPos)
        if (dur > 0) {
            seekBar.progress = ((newPos * 1000L) / dur).toInt().coerceIn(0, 1000)
            tvElapsed.text = formatTimeMs(newPos)
        }
        showSeekFeedbackToast(badgeLabel)
    }

    private fun showSeekFeedbackToast(text: String) {
        tvSeekFeedbackBadge.text = text
        tvSeekFeedbackBadge.visibility = View.VISIBLE
        tvSeekFeedbackBadge.alpha = 1f
        tvSeekFeedbackBadge.scaleX = 0.88f
        tvSeekFeedbackBadge.scaleY = 0.88f
        tvSeekFeedbackBadge.animate().cancel()
        tvSeekFeedbackBadge.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120L)
            .withEndAction {
                tvSeekFeedbackBadge.animate()
                    .alpha(0f)
                    .setStartDelay(400L)
                    .setDuration(180L)
                    .withEndAction { tvSeekFeedbackBadge.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    private fun createPlayerBottomActionChip(
        icon: Drawable,
        label: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#26FFFFFF".toColorInt(), 16f, "#45FFFFFF".toColorInt(), 0.8f)
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
            String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%d:%02d", mins, secs)
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