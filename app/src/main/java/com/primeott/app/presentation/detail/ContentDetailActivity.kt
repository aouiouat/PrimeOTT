package com.primeott.app.presentation.detail

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.data.repository.PrimeVodExtractor
import com.primeott.app.data.repository.SeriesEpisode
import com.primeott.app.domain.model.CastMember
import com.primeott.app.domain.model.MovieFullDetails
import com.primeott.app.domain.model.StreamSource
import com.primeott.app.domain.model.SubtitleCue
import com.primeott.app.domain.model.SubtitleTrack
import com.primeott.app.domain.model.VodItem
import com.primeott.app.presentation.common.AnimUtils
import com.primeott.app.presentation.player.PrimePlayerEngine
import com.primeott.app.presentation.sections.VodGridAdapter
import com.primeott.app.presentation.watch.WatchRoomActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.Locale

@OptIn(UnstableApi::class)
class ContentDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VOD = "extra_vod_item"
        private const val SNIFFER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var playerEngine: PrimePlayerEngine
    private var currentVod: VodItem? = null
    private var fullDetails: MovieFullDetails? = null

    private val resolvedDirectStreams = mutableListOf<StreamSource>()
    private var providerTargets: List<StreamSource> = emptyList()
    private var currentProviderIndex: Int = 0
    private var hasLockedPrimaryStream: Boolean = false
    private var isPlayerModeActive: Boolean = false

    private var selectedSeason: Int = 1
    private var selectedEpisode: Int = 1

    private var availableSubtitles: List<SubtitleTrack> = emptyList()
    private var activeSubtitleCues: List<SubtitleCue> = emptyList()
    private var selectedSubtitleId: String = ""
    private var subtitleOffsetMs: Long = 0L

    private var currentPlaybackSpeed: Float = 1.0f
    private var isFullscreen: Boolean = false
    private var isControlsLocked: Boolean = false
    private var isUserSeeking: Boolean = false
    private var isOverviewExpanded: Boolean = false
    private var progressJob: Job? = null
    private var snifferTimeoutJob: Job? = null

    // اللون الديناميكي المستخرج من البوستر (يبدا بلون داكن فخم ويتبدل مع كل فيلم)
    private var currentAmbientDarkColor: Int = "#1A0D12".toColorInt()

    private var resizeModeIndex: Int = 0
    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )

    private lateinit var rootVerticalLayout: LinearLayout
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var headlessSnifferWebView: WebView
    private lateinit var tvSubtitleOverlay: TextView
    private lateinit var loadingContainer: LinearLayout
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var tvLoadingStatus: TextView
    private lateinit var controlsOverlay: FrameLayout
    private lateinit var btnMinimizePlayer: ImageView
    private lateinit var tvTopPlayerTitle: TextView
    private lateinit var btnTopLock: TextView
    private lateinit var btnTopServerHd: TextView
    private lateinit var btnTopSubtitles: TextView
    private lateinit var btnCenterPlayPause: TextView
    private lateinit var tvElapsed: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvDuration: TextView
    private lateinit var btnInlineFullscreen: TextView
    private lateinit var fullscreenBottomActionsRow: LinearLayout
    private lateinit var btnBottomSubtitles: TextView
    private lateinit var btnBottomSpeed: TextView
    private lateinit var btnBottomAspect: TextView
    private lateinit var btnUnlockScreenOnly: TextView

    // واجهة العرض السينمائية الجديدة (Hero Poster + Dynamic Palette Gradient)
    private lateinit var detailsScrollView: ScrollView
    private lateinit var detailsMasterColumn: LinearLayout
    private lateinit var heroPosterSection: FrameLayout
    private lateinit var ivHeroBackdrop: ImageView
    private lateinit var vPosterTopScrim: View
    private lateinit var vPosterBottomGradient: View
    private lateinit var btnTopBackHero: ImageView
    private lateinit var btnFavBox: ImageView
    private lateinit var tvMovieTitle: TextView
    private lateinit var tvMovieGenresLine: TextView

    private lateinit var dynamicThemeBody: LinearLayout
    private lateinit var metaPillsRow: LinearLayout
    private lateinit var btnPlayNowMain: LinearLayout
    private lateinit var tvPlayNowBtnLabel: TextView
    private lateinit var btnTrailerBox: LinearLayout
    private lateinit var btnDownloadBox: LinearLayout
    private lateinit var btnWatchPartyBox: LinearLayout

    private lateinit var seriesContainer: LinearLayout
    private lateinit var seasonTabsRow: LinearLayout
    private lateinit var rvEpisodes: RecyclerView

    private lateinit var vOverviewDivider: View
    private lateinit var tvOverviewText: TextView
    private lateinit var tvOverviewToggleMore: TextView
    private lateinit var tvInfoCrewLine: TextView

    private lateinit var tvCastHeader: TextView
    private lateinit var rvCast: RecyclerView
    private lateinit var tvRecommendedHeader: TextView
    private lateinit var rvRecommended: RecyclerView
    private lateinit var tvMoreLikeHeader: TextView
    private lateinit var rvMoreLike: RecyclerView

    private var selectedQualityHeight: Int? = null
    private var pendingResumePositionMs: Long = 0L
    private var lastSavedTick: Int = 0

    private val autoHideRunnable = Runnable {
        controlsOverlay.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // AUTO_BIND_WATCH_TOGETHER_BUTTONS
        window.decorView.post {
            this@ContentDetailActivity.bindAllWatchTogetherButtonsInDetail(window.decorView)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val item = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_VOD, VodItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_VOD)
        } ?: run {
            finish()
            return
        }
        currentVod = item

        buildPrimeTvDetailLayout()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isFullscreen -> applyFullscreenState(false)
                    isPlayerModeActive -> switchToPosterShowcaseMode()
                    else -> finish()
                }
            }
        })

        playerEngine = PrimePlayerEngine(
            context = this,
            scope = lifecycleScope,
            onSourceSwitched = { _, _ ->
                btnCenterPlayPause.text = "⏸"
            },
            onRealQualityDetected = { qualityBadge, _ ->
                runOnUiThread {
                    btnTopServerHd.text = qualityBadge
                }
            },
            onFirstFrameRendered = {
                runOnUiThread {
                    loadingContainer.visibility = View.GONE
                    controlsOverlay.visibility = View.GONE
                }
            },
            onPlaybackExhausted = {
                runOnUiThread {
                    hasLockedPrimaryStream = false
                    tryNextProviderInBackground()
                }
            }
        )

        setupInvisibleStreamSniffer()
        loadVodItemIntoScreen(item)
        startPlayerProgressAndSubtitlesLoop()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeRoundedBg(fillHex: String, radiusDp: Int, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillHex.toColorInt())
            if (strokeHex != null) {
                setStroke(dp(1), strokeHex.toColorInt())
            }
        }
    }

    /**
     * استخراج اللون الداكن (darkVibrant / darkMuted) من البوستر أوتوماتيكياً
     * يضمن دائماً أن الخلفية داكنة وفخمة وأن النص الأبيض واضح 100% مهما كان غلاف الفيلم فاتحاً
     */
    private fun extractDarkAmbientColorFromPoster(bitmap: Bitmap): Int {
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 36, 36, false)
            var rWeighted = 0f
            var gWeighted = 0f
            var bWeighted = 0f
            var weightSum = 0f
            val hsv = FloatArray(3)

            for (y in 6 until 34) {
                for (x in 2 until 34) {
                    val pixel = scaled.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    Color.colorToHSV(pixel, hsv)
                    val sat = hsv[1]
                    val value = hsv[2]
                    // نفضلو الألوان الغنية (S > 0.18) ونتجنبو الأبيض الخالص والأسود الخالص
                    if (sat >= 0.16f && value in 0.12f..0.92f) {
                        val weight = (sat * 2.2f) + (1f - kotlin.math.abs(value - 0.5f))
                        rWeighted += r * weight
                        gWeighted += g * weight
                        bWeighted += b * weight
                        weightSum += weight
                    }
                }
            }

            if (weightSum <= 0.01f) {
                "#18121E".toColorInt()
            } else {
                val avgColor = Color.rgb(
                    (rWeighted / weightSum).toInt().coerceIn(0, 255),
                    (gWeighted / weightSum).toInt().coerceIn(0, 255),
                    (bWeighted / weightSum).toInt().coerceIn(0, 255)
                )
                Color.colorToHSV(avgColor, hsv)
                // فرض درجة إشباع متوازنة (0.45..0.82) ودرجة إضاءة داكنة دائماً (0.24..0.34)
                hsv[1] = hsv[1].coerceIn(0.45f, 0.82f)
                hsv[2] = hsv[2].coerceIn(0.24f, 0.34f)
                Color.HSVToColor(hsv)
            }
        } catch (_: Exception) {
            "#18121E".toColorInt()
        }
    }

    /**
     * تطبيق التدرج اللوني الديناميكي (LinearGradient) من أسفل البوستر حتى اللون الأسود (#050508) في أسفل الشاشة
     */
    private fun applyDynamicAmbientTheme(newDarkColor: Int, animate: Boolean = true) {
        val startColor = currentAmbientDarkColor
        val targetColor = newDarkColor

        val updateGradients: (Int) -> Unit = { ambient ->
            val midDark = ColorUtils.blendARGB(ambient, "#060609".toColorInt(), 0.55f)
            val deepBlack = "#050508".toColorInt()

            // 1. تدرج أسفل البوستر من شفاف إلى اللون المستخرج من البوستر
            vPosterBottomGradient.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.TRANSPARENT,
                    ColorUtils.setAlphaComponent(ambient, 120),
                    ColorUtils.setAlphaComponent(ambient, 225),
                    ambient
                )
            )

            // 2. تدرج جسم الصفحة من اللون المستخرج إلى الأسود الخالص في الأسفل
            dynamicThemeBody.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    ambient,
                    midDark,
                    deepBlack,
                    deepBlack
                )
            )

            // 3. تلوين خفيف للخط الفاصل قبل Overview
            val accentLineColor = ColorUtils.blendARGB(ambient, Color.WHITE, 0.25f)
            vOverviewDivider.setBackgroundColor(ColorUtils.setAlphaComponent(accentLineColor, 75))
        }

        if (animate) {
            ValueAnimator.ofArgb(startColor, targetColor).apply {
                duration = 420L
                addUpdateListener { va ->
                    val c = va.animatedValue as Int
                    currentAmbientDarkColor = c
                    updateGradients(c)
                }
                start()
            }
        } else {
            currentAmbientDarkColor = targetColor
            updateGradients(targetColor)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPrimeTvDetailLayout() {
        val titleCondensedFont = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val mediumFont = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val regularFont = Typeface.create("sans-serif", Typeface.NORMAL)

        rootVerticalLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#050508".toColorInt())
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // =========================================================================
        // 1. INLINE VIDEO PLAYER CONTAINER (يظهر كي تضغط على PLAY)
        // =========================================================================
        playerContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(240)
            )
        }

        headlessSnifferWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            alpha = 0f
            visibility = View.INVISIBLE
        }
        rootVerticalLayout.addView(headlessSnifferWebView)

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerContainer.addView(playerView)

        tvSubtitleOverlay = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                setMargins(dp(20), 0, dp(20), dp(36))
            }
            gravity = Gravity.CENTER
            textSize = 15.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(8f, 0f, 3f, Color.BLACK)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
        }
        playerContainer.addView(tvSubtitleOverlay)

        loadingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        loadingSpinner = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            indeterminateTintList = ColorStateList.valueOf("#E50914".toColorInt())
        }
        tvLoadingStatus = TextView(this).apply {
            text = "Loading Fast 1080p Stream..."
            setTextColor("#CBD5E1".toColorInt())
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }
        loadingContainer.addView(loadingSpinner)
        loadingContainer.addView(tvLoadingStatus)
        playerContainer.addView(loadingContainer)

        controlsOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor("#66000000".toColorInt())
            visibility = View.VISIBLE
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(14), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        btnMinimizePlayer = ImageView(this).apply {
            setImageResource(R.drawable.ic_back_chevron)
            setPadding(dp(6), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener {
                if (isFullscreen) {
                    applyFullscreenState(false)
                } else {
                    switchToPosterShowcaseMode()
                }
            }
        }

        tvTopPlayerTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = mediumFont
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnTopLock = TextView(this).apply {
            text = "🔓"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
            setOnClickListener {
                isControlsLocked = true
                controlsOverlay.visibility = View.GONE
                btnUnlockScreenOnly.visibility = View.VISIBLE
            }
        }

        btnTopServerHd = TextView(this).apply {
            text = "FHD"
            textSize = 11f
            typeface = mediumFont
            setTextColor(Color.WHITE)
            background = makeRoundedBg("#26FFFFFF", 6, "#80FFFFFF")
            setPadding(dp(8), dp(3), dp(8), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener { showPrimeServersAndQualityPopup() }
        }

        btnTopSubtitles = TextView(this).apply {
            text = "CC"
            textSize = 12f
            typeface = mediumFont
            setTextColor(Color.WHITE)
            background = makeRoundedBg("#26FFFFFF", 6, "#66FFFFFF")
            setPadding(dp(8), dp(3), dp(8), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener { showSubtitlesDialog() }
        }

        topBar.addView(btnMinimizePlayer)
        topBar.addView(tvTopPlayerTitle)
        topBar.addView(btnTopLock)
        topBar.addView(btnTopServerHd)
        topBar.addView(btnTopSubtitles)
        controlsOverlay.addView(topBar)

        btnCenterPlayPause = TextView(this).apply {
            text = "⏸"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = makeRoundedBg("#66000000", 30, "#CCFF1E27")
            layoutParams = FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER)
            setOnClickListener {
                resetAutoHide()
                if (playerEngine.isPlaying()) {
                    playerEngine.pause()
                    text = "▶"
                } else {
                    playerEngine.resume()
                    text = "⏸"
                }
            }
        }
        controlsOverlay.addView(btnCenterPlayPause)

        val bottomPlayerBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        tvElapsed = TextView(this).apply {
            text = "0:00"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = mediumFont
        }

        seekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf("#E50914".toColorInt())
            thumbTintList = ColorStateList.valueOf("#E50914".toColorInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) resetAutoHide()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {
                    isUserSeeking = true
                    resetAutoHide()
                }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    isUserSeeking = false
                    val exo = playerEngine.player ?: return
                    val dur = exo.duration
                    if (dur > 0) {
                        exo.seekTo((dur * (s?.progress ?: 0)) / 1000L)
                    }
                }
            })
        }

        tvDuration = TextView(this).apply {
            text = "--:--"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = mediumFont
        }

        btnInlineFullscreen = TextView(this).apply {
            text = "⛶"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dp(10), dp(2), dp(4), dp(2))
            setOnClickListener { applyFullscreenState(!isFullscreen) }
        }

        seekRow.addView(tvElapsed)
        seekRow.addView(seekBar)
        seekRow.addView(tvDuration)
        seekRow.addView(btnInlineFullscreen)
        bottomPlayerBar.addView(seekRow)

        fullscreenBottomActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        btnBottomSubtitles = TextView(this).apply {
            text = "Subtitles & Sync"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = mediumFont
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { showSubtitlesDialog() }
        }

        btnBottomSpeed = TextView(this).apply {
            text = "Speed: Normal"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = mediumFont
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { showPlaybackSpeedDialog() }
        }

        btnBottomAspect = TextView(this).apply {
            text = "Aspect Ratio"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = mediumFont
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener {
                resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                playerView.resizeMode = resizeModes[resizeModeIndex]
            }
        }

        fullscreenBottomActionsRow.addView(btnBottomSubtitles)
        fullscreenBottomActionsRow.addView(btnBottomSpeed)
        fullscreenBottomActionsRow.addView(btnBottomAspect)
        bottomPlayerBar.addView(fullscreenBottomActionsRow)

        controlsOverlay.addView(bottomPlayerBar)
        playerContainer.addView(controlsOverlay)

        btnUnlockScreenOnly = TextView(this).apply {
            text = "🔒 Tap to Unlock"
            setTextColor(Color.WHITE)
            textSize = 12f
            background = makeRoundedBg("#AA000000", 8, "#FFFFFF")
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) }
            setOnClickListener {
                isControlsLocked = false
                visibility = View.GONE
                toggleControlsOverlay()
            }
        }
        playerContainer.addView(btnUnlockScreenOnly)

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isControlsLocked) {
                    btnUnlockScreenOnly.isVisible = !btnUnlockScreenOnly.isVisible
                } else {
                    toggleControlsOverlay()
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, ev ->
            gestureDetector.onTouchEvent(ev)
            true
        }
        controlsOverlay.setOnClickListener { toggleControlsOverlay() }
        rootVerticalLayout.addView(playerContainer)

        // =========================================================================
        // 2. CINEMATIC SCROLL VIEW (FlixVision Poster + Dynamic Palette Gradient)
        // =========================================================================
        detailsScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        detailsMasterColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // --- A. HERO POSTER SECTION (390dp) ---
        heroPosterSection = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(395)
            )
        }

        ivHeroBackdrop = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        heroPosterSection.addView(ivHeroBackdrop)

        // تضليل علوي خفيف باش تبان أيقونة الرجوع وأيقونة المفضلة واضحين
        vPosterTopScrim = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(95),
                Gravity.TOP
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf("#99000000".toColorInt(), Color.TRANSPARENT)
            )
        }
        heroPosterSection.addView(vPosterTopScrim)

        // التدرج السفلي للبوستر (يندمج مع اللون المستخرج من البوستر)
        vPosterBottomGradient = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(210),
                Gravity.BOTTOM
            )
        }
        heroPosterSection.addView(vPosterBottomGradient)

        // Top Bar فوق البوستر: زر الرجوع على اليسار + زر Favorite فقط على اليمين (بلا Search وبلا Settings)
        val heroTopActionsRow = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        btnTopBackHero = ImageView(this).apply {
            setImageResource(R.drawable.ic_back_chevron)
            background = makeRoundedBg("#450B0D14", 22, "#38FFFFFF")
            setPadding(dp(10), dp(10), dp(11), dp(10))
            layoutParams = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.START or Gravity.CENTER_VERTICAL)
            setOnClickListener { finish() }
        }

        btnFavBox = ImageView(this).apply {
            setImageResource(R.drawable.ic_heart_outline)
            background = makeRoundedBg("#450B0D14", 22, "#38FFFFFF")
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.END or Gravity.CENTER_VERTICAL)
            setOnClickListener {
                val id = currentVod?.id ?: return@setOnClickListener
                OttRepository.toggleFavorite(id, this@ContentDetailActivity)
                updateFavoriteIcon(id)
            }
        }
        AnimUtils.attachPressBounce(btnTopBackHero)
        AnimUtils.attachPressBounce(btnFavBox)

        heroTopActionsRow.addView(btnTopBackHero)
        heroTopActionsRow.addView(btnFavBox)
        heroPosterSection.addView(heroTopActionsRow)

        // العنوان الفخم + سطر الأنواع (Action • Sci-Fi • Drama) فوق التدرج أسفل الصورة
        val heroBottomTitleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            setPadding(dp(18), 0, dp(18), dp(10))
        }

        tvMovieTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = titleCondensedFont
            letterSpacing = 0.02f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(12f, 0f, 4f, Color.BLACK)
        }

        tvMovieGenresLine = TextView(this).apply {
            setTextColor("#E2E8F0".toColorInt())
            textSize = 13f
            typeface = mediumFont
            letterSpacing = 0.02f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }

        heroBottomTitleBox.addView(tvMovieTitle)
        heroBottomTitleBox.addView(tvMovieGenresLine)
        heroPosterSection.addView(heroBottomTitleBox)

        detailsMasterColumn.addView(heroPosterSection)

        // --- B. DYNAMIC GRADIENT BODY (من لون البوستر الداكن إلى الأسود #050508) ---
        dynamicThemeBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(42))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. الكبسولات الصغيرة (Small Info Cards: ★ Rating | Year | Runtime | FHD | PG-13)
        val pillsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        }
        metaPillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pillsScroll.addView(metaPillsRow)
        dynamicThemeBody.addView(pillsScroll)

        // 2. أزرار PLAY (أبيض فخم) + Trailer + Download + Party
        val primaryActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 3f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(22)
            }
        }

        // زر PLAY الأبيض الفخم عالي التباين
        btnPlayNowMain = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_btn_play_white_luxury)
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1.15f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                activateInlinePlayerAndStart()
            }
        }
        val ivPlayBlack = ImageView(this).apply {
            setImageResource(R.drawable.ic_play_watch_now)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf("#090A0F".toColorInt()))
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        tvPlayNowBtnLabel = TextView(this).apply {
            text = "PLAY"
            setTextColor("#090A0F".toColorInt())
            textSize = 15f
            typeface = titleCondensedFont
            letterSpacing = 0.05f
            setPadding(dp(7), 0, 0, 0)
        }
        btnPlayNowMain.addView(ivPlayBlack)
        btnPlayNowMain.addView(tvPlayNowBtnLabel)

        // زر Trailer الزجاجي الفخم
        btnTrailerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_btn_glass_secondary)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 0.95f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                val key = fullDetails?.trailerYoutubeKey.orEmpty()
                if (key.isNotEmpty()) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")))
                } else {
                    val q = Uri.encode("${currentVod?.title ?: ""} official trailer")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")))
                }
            }
        }
        val ivTrailerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_trailer_film)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        val tvTrailerLabel = TextView(this).apply {
            text = "Trailer"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = mediumFont
            setPadding(dp(6), 0, 0, 0)
        }
        btnTrailerBox.addView(ivTrailerIcon)
        btnTrailerBox.addView(tvTrailerLabel)

        // زر Download الزجاجي الفخم
        btnDownloadBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_btn_glass_secondary)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 0.9f)
            setOnClickListener {
                handleDirectMovieDownload()
            }
        }
        val ivDownloadIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_download_cloud)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        val tvDownloadLabel = TextView(this).apply {
            text = "Download"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = mediumFont
            setPadding(dp(5), 0, 0, 0)
        }
        btnDownloadBox.addView(ivDownloadIcon)
        btnDownloadBox.addView(tvDownloadLabel)

        // زر Watch Together الإضافي
        btnWatchPartyBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_btn_glass_secondary)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(48)).apply {
                marginStart = dp(8)
            }
            setOnClickListener { createWatchTogetherRoom() }
        }
        val ivPartyIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_events)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }
        btnWatchPartyBox.addView(ivPartyIcon)

        AnimUtils.attachPressBounce(btnPlayNowMain)
        AnimUtils.attachPressBounce(btnTrailerBox)
        AnimUtils.attachPressBounce(btnDownloadBox)
        AnimUtils.attachPressBounce(btnWatchPartyBox)

        primaryActionsRow.addView(btnPlayNowMain)
        primaryActionsRow.addView(btnTrailerBox)
        primaryActionsRow.addView(btnDownloadBox)
        primaryActionsRow.addView(btnWatchPartyBox)
        dynamicThemeBody.addView(primaryActionsRow)

        // 3. قسم المسلسلات (Seasons & Episodes - يظهر غير فـ المسلسلات)
        seriesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(16))
        }
        val seasonScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        seasonTabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        seasonScroll.addView(seasonTabsRow)
        seriesContainer.addView(seasonScroll)

        rvEpisodes = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ContentDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112))
        }
        seriesContainer.addView(rvEpisodes)
        dynamicThemeBody.addView(seriesContainer)

        // 4. خط فاصل أنيق + قسم Overview مفصول على الفوق
        vOverviewDivider = View(this).apply {
            setBackgroundColor("#26FFFFFF".toColorInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                bottomMargin = dp(16)
            }
        }
        dynamicThemeBody.addView(vOverviewDivider)

        val overviewHeader = TextView(this).apply {
            text = "Overview"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = titleCondensedFont
            letterSpacing = 0.03f
            setPadding(0, 0, 0, dp(6))
        }
        dynamicThemeBody.addView(overviewHeader)

        tvOverviewText = TextView(this).apply {
            setTextColor("#D8E0EC".toColorInt())
            textSize = 14f
            typeface = regularFont
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(4).toFloat(), 1.0f)
            setOnClickListener { toggleOverviewExpansion() }
        }
        dynamicThemeBody.addView(tvOverviewText)

        tvOverviewToggleMore = TextView(this).apply {
            text = "More"
            setTextColor("#38BDF8".toColorInt())
            textSize = 13f
            typeface = mediumFont
            setPadding(0, dp(4), 0, dp(8))
            setOnClickListener { toggleOverviewExpansion() }
        }
        dynamicThemeBody.addView(tvOverviewToggleMore)

        tvInfoCrewLine = TextView(this).apply {
            setTextColor("#94A3B8".toColorInt())
            textSize = 12f
            typeface = regularFont
            setPadding(0, dp(4), 0, dp(8))
        }
        dynamicThemeBody.addView(tvInfoCrewLine)

        // 5. قسم Cast (ما كبار ما صغار بتصميم شباب ومتوازن)
        tvCastHeader = TextView(this).apply {
            text = "Cast"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = titleCondensedFont
            letterSpacing = 0.03f
            setPadding(0, dp(14), 0, dp(10))
        }
        dynamicThemeBody.addView(tvCastHeader)

        rvCast = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ContentDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(122))
        }
        dynamicThemeBody.addView(rvCast)

        // 6. قسم Recommended
        tvRecommendedHeader = TextView(this).apply {
            text = "Recommended"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = titleCondensedFont
            letterSpacing = 0.03f
            setPadding(0, dp(18), 0, dp(10))
        }
        dynamicThemeBody.addView(tvRecommendedHeader)

        rvRecommended = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ContentDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(198))
        }
        dynamicThemeBody.addView(rvRecommended)

        // 7. قسم More Like This
        tvMoreLikeHeader = TextView(this).apply {
            text = "More Like This"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = titleCondensedFont
            letterSpacing = 0.03f
            setPadding(0, dp(18), 0, dp(10))
        }
        dynamicThemeBody.addView(tvMoreLikeHeader)

        rvMoreLike = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ContentDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(198))
        }
        dynamicThemeBody.addView(rvMoreLike)

        detailsMasterColumn.addView(dynamicThemeBody)
        detailsScrollView.addView(detailsMasterColumn)
        rootVerticalLayout.addView(detailsScrollView)

        setContentView(rootVerticalLayout)
        applyDynamicAmbientTheme("#1A0D12".toColorInt(), animate = false)
    }

    private fun toggleOverviewExpansion() {
        isOverviewExpanded = !isOverviewExpanded
        if (isOverviewExpanded) {
            tvOverviewText.maxLines = 30
            tvOverviewToggleMore.text = "Less"
        } else {
            tvOverviewText.maxLines = 4
            tvOverviewToggleMore.text = "More"
        }
    }

    /**
     * كي يضغط المستخدم على PLAY: نخبي الكوفر الكبير ونبين المشغل الفوق ونبدا التشغيل فوراً
     */
    private fun activateInlinePlayerAndStart() {
        isPlayerModeActive = true
        heroPosterSection.visibility = View.GONE
        playerContainer.visibility = View.VISIBLE
        detailsScrollView.smoothScrollTo(0, 0)

        if (resolvedDirectStreams.isNotEmpty()) {
            loadingContainer.visibility = View.GONE
            playerEngine.playSources(playerView, resolvedDirectStreams.toList(), 0, pendingResumePositionMs)
        } else {
            loadingContainer.visibility = View.VISIBLE
            val vod = currentVod ?: return
            if (providerTargets.isEmpty()) {
                startStreamExtractionForEpisodeOrMovie(vod, selectedSeason, selectedEpisode)
            } else {
                loadProviderInHeadlessSniffer(currentProviderIndex)
            }
        }
    }

    private fun switchToPosterShowcaseMode() {
        isPlayerModeActive = false
        playerEngine.pause()
        playerContainer.visibility = View.GONE
        heroPosterSection.visibility = View.VISIBLE
    }

    private fun handleDirectMovieDownload() {
        val directUrl = resolvedDirectStreams.firstOrNull()?.url
        if (!directUrl.isNullOrEmpty() && !directUrl.contains(".m3u8", ignoreCase = true)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(directUrl)))
        } else if (!directUrl.isNullOrEmpty()) {
            Toast.makeText(this, "Stream Ready (${btnTopServerHd.text}) — Opening External Downloader...", Toast.LENGTH_SHORT).show()
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(directUrl)))
            }
        } else {
            Toast.makeText(this, "Preparing 1080p Direct Link... Tap again in 2 seconds", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupInvisibleStreamSniffer() {
        headlessSnifferWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = SNIFFER_UA
            blockNetworkImage = true
            setSupportMultipleWindows(false)
        }

        headlessSnifferWebView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onStreamCaptured(streamUrl: String, pageOrigin: String) {
                if (streamUrl.startsWith("http") && !streamUrl.contains("doubleclick")) {
                    onRawStreamIntercepted(
                        url = streamUrl,
                        referer = "$pageOrigin/",
                        origin = pageOrigin,
                        userAgent = SNIFFER_UA
                    )
                }
            }
        }, "PrimeSnifferBridge")

        headlessSnifferWebView.webChromeClient = WebChromeClient()
        headlessSnifferWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return true
                val allowed = listOf("vidlink", "vixsrc", "vidsrc", "embed.su", "autoembed", "multiembed", "cloudnestra", "tmstr", "rabbitstream", "megacloud")
                return !allowed.any { target.contains(it, ignoreCase = true) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (hasLockedPrimaryStream || url == "about:blank") return
                val autoClickJs = """
                    (function() {
                        var count = 0;
                        var timer = setInterval(function() {
                            count++;
                            if (count > 20) { clearInterval(timer); return; }
                            var btns = document.querySelectorAll('button, .play-btn, .jw-icon-display, .vjs-big-play-button, [aria-label="Play"], #play-button, .plyr__control--overlaid');
                            for (var i = 0; i < btns.length; i++) {
                                try { btns[i].click(); } catch(e) {}
                            }
                            var v = document.querySelector('video');
                            if (v) {
                                v.muted = true;
                                try { v.play(); } catch(e) {}
                                if (v.src && v.src.indexOf('http') === 0) {
                                    window.PrimeSnifferBridge.onStreamCaptured(v.src, window.location.origin);
                                    clearInterval(timer);
                                }
                            }
                        }, 300);
                    })();
                """.trimIndent()
                view?.evaluateJavascript(autoClickJs, null)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (hasLockedPrimaryStream) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                val reqUrl = request?.url?.toString() ?: return null
                val lower = reqUrl.lowercase(Locale.ROOT)

                if (lower.contains("doubleclick") || lower.contains("popads") || lower.contains("adsterra") || lower.contains("googlesyndication") || lower.contains("analytics")) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }

                if ((lower.contains(".vtt") || lower.contains(".srt")) && !lower.contains("thumb") && !lower.contains("sprite")) {
                    val isAr = lower.contains("ar") || lower.contains("ara")
                    val subTrack = SubtitleTrack(
                        id = "stream_sync_${reqUrl.hashCode()}",
                        languageCode = if (isAr) "ar" else "en",
                        languageLabel = if (isAr) "Arabic (100% Stream Synced)" else "English (100% Stream Synced)",
                        releaseName = "Direct.Stream.Embedded.VTT",
                        url = reqUrl
                    )
                    runOnUiThread {
                        if (availableSubtitles.none { it.url == reqUrl }) {
                            availableSubtitles = listOf(subTrack) + availableSubtitles
                            if (isAr || activeSubtitleCues.isEmpty()) {
                                lifecycleScope.launch {
                                    val cues = OttRepository.downloadAndParseSubtitles(subTrack)
                                    if (cues.isNotEmpty()) {
                                        activeSubtitleCues = cues
                                        selectedSubtitleId = subTrack.id
                                    }
                                }
                            }
                        }
                    }
                }

                val isMediaManifest = (lower.contains(".m3u8") ||
                        lower.contains(".mp4") ||
                        lower.contains("/playlist/") ||
                        lower.contains("master.txt") ||
                        lower.contains("m3u8-proxy")) &&
                        !lower.contains("netnaija.film/wp-content") &&
                        !lower.contains("promo") &&
                        !lower.contains("trailer") &&
                        !lower.endsWith(".js") &&
                        !lower.endsWith(".css") &&
                        !lower.endsWith(".png") &&
                        !lower.endsWith(".jpg")

                if (isMediaManifest) {
                    val headers = request.requestHeaders
                    val currentProvider = providerTargets.getOrNull(currentProviderIndex)
                    val ref = headers["Referer"] ?: headers["referer"] ?: currentProvider?.referer ?: "https://vidlink.pro/"
                    val orig = headers["Origin"] ?: headers["origin"] ?: currentProvider?.origin ?: ""
                    val ua = headers["User-Agent"] ?: headers["user-agent"] ?: SNIFFER_UA
                    onRawStreamIntercepted(reqUrl, ref, orig, ua)
                }

                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun onRawStreamIntercepted(url: String, referer: String, origin: String, userAgent: String) {
        runOnUiThread {
            val providerLabel = providerTargets.getOrNull(currentProviderIndex)?.label ?: "Prime Server 1080p"
            val newDirectSource = StreamSource(
                label = providerLabel,
                url = url,
                referer = referer,
                origin = origin,
                userAgent = userAgent,
                isDirectStream = true
            )

            if (!hasLockedPrimaryStream) {
                hasLockedPrimaryStream = true
                snifferTimeoutJob?.cancel()
                headlessSnifferWebView.stopLoading()
                headlessSnifferWebView.loadUrl("about:blank")
                headlessSnifferWebView.onPause()
                headlessSnifferWebView.pauseTimers()

                resolvedDirectStreams.add(0, newDirectSource)
                tvLoadingStatus.text = "Starting 1080p Playback..."
                // نشغلو الفيديو مباشرة غير إذا كان المستخدم ضغط على PLAY
                if (isPlayerModeActive) {
                    playerEngine.playSources(playerView, resolvedDirectStreams.toList(), 0, pendingResumePositionMs)
                }
            } else {
                resolvedDirectStreams.add(newDirectSource)
                playerEngine.appendBackupSourcesSilently(listOf(newDirectSource))
            }
        }
    }

    private fun startStreamExtractionForEpisodeOrMovie(vod: VodItem, season: Int = 1, episode: Int = 1) {
        snifferTimeoutJob?.cancel()
        hasLockedPrimaryStream = false
        selectedQualityHeight = null
        playerEngine.pauseAndReleaseSocket()
        resolvedDirectStreams.clear()
        providerTargets = emptyList()

        tvElapsed.text = "0:00"
        tvDuration.text = "--:--"
        seekBar.progress = 0
        tvSubtitleOverlay.text = ""
        tvSubtitleOverlay.visibility = View.GONE
        loadingContainer.visibility = if (isPlayerModeActive) View.VISIBLE else View.GONE
        tvLoadingStatus.text = "Connecting to fastest server..."

        lifecycleScope.launch {
            val panelServers = PrimeVodExtractor.fetchPanelConfiguredServers(
                tmdbId = vod.id,
                imdbId = fullDetails?.imdbId.orEmpty(),
                title = vod.title,
                isSeries = vod.isSeries,
                season = season,
                episode = episode,
                onFastestDirectReady = { fastestServer ->
                    runOnUiThread {
                        val idx = providerTargets.indexOfFirst { it.label == fastestServer.label }.coerceAtLeast(0)
                        currentProviderIndex = idx
                        onRawStreamIntercepted(
                            url = fastestServer.url,
                            referer = fastestServer.referer,
                            origin = fastestServer.origin,
                            userAgent = fastestServer.userAgent
                        )
                    }
                }
            )
            providerTargets = panelServers

            if (panelServers.isEmpty()) {
                tvLoadingStatus.text = "No active servers in Admin Panel"
                loadingSpinner.visibility = View.GONE
                return@launch
            }

            if (!hasLockedPrimaryStream) {
                val firstDirectIdx = panelServers.indexOfFirst { it.isDirectStream }
                if (firstDirectIdx != -1) {
                    currentProviderIndex = firstDirectIdx
                    val s = panelServers[firstDirectIdx]
                    onRawStreamIntercepted(s.url, s.referer, s.origin, s.userAgent)
                } else {
                    loadProviderInHeadlessSniffer(0)
                }
            }
        }
    }

    private fun loadProviderInHeadlessSniffer(index: Int) {
        if (providerTargets.isEmpty() || hasLockedPrimaryStream) return
        currentProviderIndex = index.coerceIn(0, providerTargets.lastIndex)
        val target = providerTargets[currentProviderIndex]
        tvLoadingStatus.text = "Connecting to ${target.label}..."

        headlessSnifferWebView.onResume()
        headlessSnifferWebView.resumeTimers()
        val headers = if (target.referer.isNotEmpty()) mapOf("Referer" to target.referer) else emptyMap()
        headlessSnifferWebView.loadUrl(target.url, headers)

        snifferTimeoutJob?.cancel()
        snifferTimeoutJob = lifecycleScope.launch {
            delay(7000L)
            if (!hasLockedPrimaryStream && !playerEngine.isPlaying()) {
                tryNextProviderInBackground()
            }
        }
    }

    private fun tryNextProviderInBackground() {
        if (providerTargets.isEmpty()) return
        hasLockedPrimaryStream = false
        val nextIdx = (currentProviderIndex + 1) % providerTargets.size
        loadProviderInHeadlessSniffer(nextIdx)
    }

    /**
     * تحميل صورة البوستر واستخراج اللون الداكن منها أوتوماتيكياً لتلوين الخلفية المتدرجة
     */
    private fun loadPosterAndExtractPalette(vod: VodItem) {
        val imageUrl = vod.posterUrl.ifEmpty { vod.backdropUrl }
        if (imageUrl.isEmpty()) return

        Glide.with(this)
            .asBitmap()
            .load(imageUrl)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    ivHeroBackdrop.setImageBitmap(resource)
                    val darkColor = extractDarkAmbientColorFromPoster(resource)
                    applyDynamicAmbientTheme(darkColor, animate = true)
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
            })
    }

    private fun loadVodItemIntoScreen(vod: VodItem) {
        currentVod = vod
        selectedSeason = 1
        selectedEpisode = 1
        selectedSubtitleId = ""
        subtitleOffsetMs = 0L
        isOverviewExpanded = false
        activeSubtitleCues = emptyList()
        availableSubtitles = emptyList()

        // نرجعو للواجهة السينمائية الفخمة عند فتح فيلم جديد
        switchToPosterShowcaseMode()

        pendingResumePositionMs = SessionManager.getSavedPositionForVod(this, vod.id)
            .coerceAtLeast(vod.savedPositionMs)
        SessionManager.saveWatchProgress(this, vod, pendingResumePositionMs, vod.totalDurationMs)

        tvPlayNowBtnLabel.text = if (pendingResumePositionMs > 15_000L) "RESUME" else "PLAY"

        tvTopPlayerTitle.text = vod.title
        tvMovieTitle.text = vod.title
        val initialGenres = vod.category.ifEmpty { if (vod.isSeries) "TV Series • Drama" else "Movie • Action" }
        tvMovieGenresLine.text = initialGenres

        val initialPlot = vod.description.ifEmpty { "Loading synopsis and full movie details..." }
        tvOverviewText.text = initialPlot
        tvOverviewText.maxLines = 4
        tvOverviewToggleMore.visibility = if (initialPlot.length > 140) View.VISIBLE else View.GONE
        tvInfoCrewLine.text = "QUALITY: FHD 1080p  •  AUDIO: Multi-Track  •  SUBTITLES: Arabic / English"

        updateFavoriteIcon(vod.id)
        loadPosterAndExtractPalette(vod)

        renderSmallLuxuryInfoCards(
            rating = vod.rating,
            year = vod.year,
            runtime = if (vod.isSeries) "1 Season" else "1h 54m",
            ageBadge = if (vod.isSeries) "TV-14" else "PG-13",
            qualityBadge = "FHD"
        )

        // استخراج السيرفر في الخلفية باش كي يضغط PLAY يبدا طوول
        startStreamExtractionForEpisodeOrMovie(vod, 1, 1)

        lifecycleScope.launch {
            val subs = OttRepository.fetchRealSubtitlesForMovie(vod.id, "", vod.isSeries, 1, 1)
            if (subs.isNotEmpty()) {
                availableSubtitles = subs
                autoSelectPreferredSubtitle()
            }
        }

        lifecycleScope.launch {
            val details = OttRepository.fetchMovieOrSeriesFullDetails(vod)
            fullDetails = details
            tvOverviewText.text = details.overview
            tvOverviewToggleMore.visibility = if (details.overview.length > 140) View.VISIBLE else View.GONE

            // عرض الأنواع تحت اسم الفيلم بطريقة شابة كيما فـ الصور
            val genresFormatted = if (details.genres.isNotEmpty()) {
                details.genres.joinToString("  •  ")
            } else {
                initialGenres
            }
            tvMovieGenresLine.text = genresFormatted

            val castNames = details.cast.take(3).joinToString(", ") { it.name }
            if (castNames.isNotEmpty()) {
                tvInfoCrewLine.text = "STARRING:  $castNames\nSTREAM:  1080p FHD  •  Dolby Audio  •  Auto-Sync Subtitles"
            }

            renderSmallLuxuryInfoCards(
                rating = details.rating.ifEmpty { vod.rating },
                year = details.year.ifEmpty { vod.year },
                runtime = if (vod.isSeries) "${details.numberOfSeasons} Seasons" else details.runtimeText,
                ageBadge = if (vod.isSeries) "TV-14" else "PG-13",
                qualityBadge = "FHD 1080p"
            )

            if (details.imdbId.isNotEmpty()) {
                val fullSubs = OttRepository.fetchRealSubtitlesForMovie(vod.id, details.imdbId, vod.isSeries, selectedSeason, selectedEpisode)
                if (fullSubs.isNotEmpty()) {
                    availableSubtitles = fullSubs
                    autoSelectPreferredSubtitle()
                }
            }

            if (vod.isSeries) {
                seriesContainer.visibility = View.VISIBLE
                renderSeasonsAndEpisodes(vod.id, details.numberOfSeasons)
            } else {
                seriesContainer.visibility = View.GONE
            }

            if (details.cast.isNotEmpty()) {
                tvCastHeader.visibility = View.VISIBLE
                rvCast.visibility = View.VISIBLE
                rvCast.adapter = CastHorizontalAdapter(details.cast) { actor ->
                    showActorMoviesDialog(actor)
                }
            } else {
                tvCastHeader.visibility = View.GONE
                rvCast.visibility = View.GONE
            }

            rvRecommended.adapter = VodGridAdapter(details.recommended, isGrid = false) { clickedVod ->
                detailsScrollView.smoothScrollTo(0, 0)
                loadVodItemIntoScreen(clickedVod)
            }
            rvMoreLike.adapter = VodGridAdapter(details.similar, isGrid = false) { clickedVod ->
                detailsScrollView.smoothScrollTo(0, 0)
                loadVodItemIntoScreen(clickedVod)
            }
        }
    }

    /**
     * الكبسولات الصغار الفخمين (Rating ⭐ + Year + Time + Age Rating + FHD) كل وحدة فـ Card بتصميم شباب
     */
    private fun renderSmallLuxuryInfoCards(
        rating: String,
        year: String,
        runtime: String,
        ageBadge: String,
        qualityBadge: String
    ) {
        metaPillsRow.removeAllViews()
        val pillFont = Typeface.create("sans-serif-medium", Typeface.BOLD)

        // 1. Card التقييم الذهبي (★ 8.5)
        val ratingCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor("#33FFC107".toColorInt())
                setStroke(dp(1), "#80FFC107".toColorInt())
            }
            setPadding(dp(10), dp(6), dp(11), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
        val ivStar = ImageView(this).apply {
            setImageResource(R.drawable.ic_star_gold)
            layoutParams = LinearLayout.LayoutParams(dp(13), dp(13)).apply {
                marginEnd = dp(4)
            }
        }
        val tvRatingVal = TextView(this).apply {
            text = rating
            setTextColor("#FFD700".toColorInt())
            textSize = 12f
            typeface = pillFont
        }
        ratingCard.addView(ivStar)
        ratingCard.addView(tvRatingVal)
        metaPillsRow.addView(ratingCard)

        // 2. Cards لباقي المعلومات (السنة، الوقت، التصنيف العمري، الجودة)
        val extraCards = listOf(
            year to false,
            runtime to false,
            ageBadge to true,
            qualityBadge to false
        )

        extraCards.forEach { (label, isAccent) ->
            if (label.isNotBlank()) {
                val pill = TextView(this).apply {
                    text = label
                    setTextColor(if (isAccent) "#38BDF8".toColorInt() else "#F1F5F9".toColorInt())
                    textSize = 12f
                    typeface = pillFont
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(10).toFloat()
                        setColor(if (isAccent) "#1F0284C7".toColorInt() else "#22FFFFFF".toColorInt())
                        setStroke(
                            dp(1),
                            if (isAccent) "#7538BDF8".toColorInt() else "#38FFFFFF".toColorInt()
                        )
                    }
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(8) }
                }
                metaPillsRow.addView(pill)
            }
        }
    }

    private fun renderSeasonsAndEpisodes(seriesId: String, totalSeasons: Int) {
        seasonTabsRow.removeAllViews()
        val maxSeasons = totalSeasons.coerceIn(1, 15)

        fun loadSeason(seasonNum: Int) {
            selectedSeason = seasonNum
            for (i in 0 until seasonTabsRow.childCount) {
                val child = seasonTabsRow.getChildAt(i) as? TextView ?: continue
                child.background = makeRoundedBg(
                    if (i + 1 == seasonNum) "#E50914" else "#22FFFFFF",
                    10,
                    if (i + 1 == seasonNum) "#FF6B72" else "#38FFFFFF"
                )
            }
            lifecycleScope.launch {
                val episodes = PrimeVodExtractor.fetchSeasonEpisodes(seriesId, seasonNum)
                rvEpisodes.adapter = EpisodesHorizontalAdapter(episodes, selectedEpisode) { ep ->
                    selectedEpisode = ep.episodeNumber
                    val vod = currentVod ?: return@EpisodesHorizontalAdapter
                    tvTopPlayerTitle.text = "${vod.title} S${seasonNum}:E${ep.episodeNumber}"
                    activateInlinePlayerAndStart()
                    startStreamExtractionForEpisodeOrMovie(vod, seasonNum, ep.episodeNumber)
                    lifecycleScope.launch {
                        availableSubtitles = OttRepository.fetchRealSubtitlesForMovie(
                            vod.id,
                            fullDetails?.imdbId.orEmpty(),
                            true,
                            seasonNum,
                            ep.episodeNumber
                        )
                        activeSubtitleCues = emptyList()
                        selectedSubtitleId = ""
                        autoSelectPreferredSubtitle()
                    }
                }
            }
        }

        for (s in 1..maxSeasons) {
            val tab = TextView(this).apply {
                text = "Season $s"
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = makeRoundedBg(
                    if (s == selectedSeason) "#E50914" else "#22FFFFFF",
                    10,
                    if (s == selectedSeason) "#FF6B72" else "#38FFFFFF"
                )
                setPadding(dp(14), dp(7), dp(14), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener { loadSeason(s) }
            }
            seasonTabsRow.addView(tab)
        }
        loadSeason(1)
    }

    private suspend fun autoSelectPreferredSubtitle() {
        if (availableSubtitles.isEmpty()) return
        val arabicTracks = availableSubtitles.filter { it.languageCode == "ar" }
        val candidates = if (arabicTracks.isNotEmpty()) arabicTracks else availableSubtitles
        if (selectedSubtitleId.isEmpty() || activeSubtitleCues.isEmpty()) {
            for (track in candidates.take(4)) {
                val parsed = OttRepository.downloadAndParseSubtitles(track)
                if (parsed.size > 15) {
                    selectedSubtitleId = track.id
                    activeSubtitleCues = parsed
                    break
                }
            }
        }
    }

    private fun showActorMoviesDialog(actor: CastMember) {
        Toast.makeText(this, "Loading ${actor.name} movies...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val credits = PrimeVodExtractor.fetchActorCredits(actor.id)
            if (credits.isEmpty()) {
                Toast.makeText(this@ContentDetailActivity, "No additional credits found", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val titles = credits.map { "${it.title} (${it.year})  ★ ${it.rating}" }.toTypedArray()
            android.app.AlertDialog.Builder(this@ContentDetailActivity)
                .setTitle(actor.name)
                .setItems(titles) { _, which ->
                    detailsScrollView.smoothScrollTo(0, 0)
                    loadVodItemIntoScreen(credits[which])
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun startPlayerProgressAndSubtitlesLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(200)
                val p = playerEngine.player ?: continue
                if (p.isPlaying) {
                    loadingContainer.visibility = View.GONE
                    btnCenterPlayPause.text = "⏸"
                }
                val pos = p.currentPosition.coerceAtLeast(0L)
                val dur = p.duration

                if (!isUserSeeking && dur > 0) {
                    seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                    tvElapsed.text = formatTimeMs(pos)
                    tvDuration.text = formatTimeMs(dur)

                    lastSavedTick++
                    if (lastSavedTick >= 15 && pos > 5000L) {
                        lastSavedTick = 0
                        currentVod?.let { v ->
                            SessionManager.saveWatchProgress(this@ContentDetailActivity, v, pos, dur)
                        }
                    }
                }

                if (selectedSubtitleId != "NONE" && activeSubtitleCues.isNotEmpty() && p.isPlaying) {
                    val syncedPos = (pos - subtitleOffsetMs).coerceAtLeast(0L)
                    val cue = activeSubtitleCues.firstOrNull { syncedPos in it.startMs..it.endMs }
                    if (cue != null) {
                        tvSubtitleOverlay.text = cue.text
                        tvSubtitleOverlay.visibility = View.VISIBLE
                    } else {
                        tvSubtitleOverlay.text = ""
                    }
                } else if (selectedSubtitleId == "NONE") {
                    tvSubtitleOverlay.visibility = View.GONE
                }
            }
        }
    }

    private fun showPrimeServersAndQualityPopup() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = (290 * resources.displayMetrics.density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#16181F", 14, "#2A2E3B")
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        if (providerTargets.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No servers configured in panel"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            card.addView(emptyTv)
            dialog.setContentView(card)
            dialog.show()
            return
        }

        val realQualities = playerEngine.getAvailableQualityTracks()
        val currentDetectedBadge = playerEngine.getCurrentDetectedQualityLabel()

        providerTargets.forEachIndexed { srvIdx, server ->
            val isCurrentServer = (srvIdx == currentProviderIndex)

            if (isCurrentServer && realQualities.isNotEmpty()) {
                realQualities.forEach { qTrack ->
                    val shortQ = when {
                        qTrack.height >= 2160 -> "4K"
                        qTrack.height >= 1080 -> "1080p"
                        qTrack.height >= 720 -> "720p"
                        qTrack.height >= 480 -> "480p"
                        else -> "${qTrack.height}p"
                    }
                    val isSelectedQuality = (selectedQualityHeight == qTrack.height) ||
                            (selectedQualityHeight == null && qTrack == realQualities.first())

                    val row = TextView(this).apply {
                        text = "${server.label} $shortQ"
                        textSize = 14.5f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(if (isSelectedQuality) "#E50914".toColorInt() else Color.WHITE)
                        setPadding(dp(12), dp(11), dp(12), dp(11))
                        setOnClickListener {
                            selectedQualityHeight = qTrack.height
                            playerEngine.selectVideoQualityHeight(qTrack.height)
                            btnTopServerHd.text = shortQ
                            dialog.dismiss()
                        }
                    }
                    card.addView(row)
                }
            } else if (isCurrentServer) {
                val row = TextView(this).apply {
                    text = "${server.label} $currentDetectedBadge"
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor("#E50914".toColorInt())
                    setPadding(dp(12), dp(11), dp(12), dp(11))
                    setOnClickListener { dialog.dismiss() }
                }
                card.addView(row)
            } else {
                val row = TextView(this).apply {
                    text = server.label
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor("#CBD5E1".toColorInt())
                    setPadding(dp(12), dp(11), dp(12), dp(11))
                    setOnClickListener {
                        currentProviderIndex = srvIdx
                        selectedQualityHeight = null
                        hasLockedPrimaryStream = false
                        resolvedDirectStreams.clear()
                        loadingContainer.visibility = View.VISIBLE
                        if (server.isDirectStream) {
                            onRawStreamIntercepted(server.url, server.referer, server.origin, server.userAgent)
                        } else {
                            loadProviderInHeadlessSniffer(srvIdx)
                        }
                        dialog.dismiss()
                    }
                }
                card.addView(row)
            }
        }

        dialog.setContentView(card)
        dialog.show()
        dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showPlaybackSpeedDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val speeds = listOf(
            0.5f to "0.5x",
            0.75f to "0.75x",
            1.0f to "Normal",
            1.25f to "1.25x",
            1.5f to "1.5x",
            2.0f to "2x"
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#16181F", 12, "#2A2E3B")
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        speeds.forEach { (spd, label) ->
            val isSel = spd == currentPlaybackSpeed
            val tv = TextView(this).apply {
                text = label
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isSel) "#E50914".toColorInt() else Color.WHITE)
                setPadding(dp(16), dp(10), dp(28), dp(10))
                setOnClickListener {
                    currentPlaybackSpeed = spd
                    playerEngine.player?.playbackParameters = PlaybackParameters(spd)
                    btnBottomSpeed.text = "Speed: $label"
                    dialog.dismiss()
                }
            }
            card.addView(tv)
        }

        dialog.setContentView(card)
        dialog.show()
    }

    private fun showSubtitlesDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = (resources.displayMetrics.widthPixels * 0.92).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#121318", 16, "#282B36")
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val titleTv = TextView(this).apply {
            text = "Subtitles (${availableSubtitles.size})"
            setTextColor(Color.WHITE)
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val refreshBtn = TextView(this).apply {
            text = "Refresh"
            setTextColor("#E50914".toColorInt())
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            background = makeRoundedBg("#2A1215", 8)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        headerRow.addView(titleTv)
        headerRow.addView(refreshBtn)
        root.addView(headerRow)

        val syncBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#191C26", 10, "#2A2E3D")
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val tvSyncStatus = TextView(this).apply {
            text = String.format(Locale.US, "Sync: %+.1fs", subtitleOffsetMs / 1000.0)
            setTextColor("#38BDF8".toColorInt())
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnMinusHalf = TextView(this).apply {
            text = "-0.5s"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            background = makeRoundedBg("#262B38", 6)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                subtitleOffsetMs -= 500L
                tvSyncStatus.text = String.format(Locale.US, "Sync: %+.1fs", subtitleOffsetMs / 1000.0)
            }
        }
        val btnResetSync = TextView(this).apply {
            text = "0.0s"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            background = makeRoundedBg("#E50914", 6)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                subtitleOffsetMs = 0L
                tvSyncStatus.text = "Sync: +0.0s"
            }
        }
        val btnPlusHalf = TextView(this).apply {
            text = "+0.5s"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            background = makeRoundedBg("#262B38", 6)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                subtitleOffsetMs += 500L
                tvSyncStatus.text = String.format(Locale.US, "Sync: %+.1fs", subtitleOffsetMs / 1000.0)
            }
        }
        syncBar.addView(tvSyncStatus)
        syncBar.addView(btnMinusHalf)
        syncBar.addView(btnResetSync)
        syncBar.addView(btnPlusHalf)
        root.addView(syncBar)

        val filterScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        filterScroll.addView(filterRow)
        root.addView(filterScroll)

        val tracksScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230))
        }
        val tracksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        tracksScroll.addView(tracksContainer)
        root.addView(tracksScroll)

        var selectedLangFilter = "ALL"

        fun renderSubtitleRows() {
            tracksContainer.removeAllViews()
            titleTv.text = "Subtitles (${availableSubtitles.size})"

            val noSubRow = TextView(this).apply {
                text = if (selectedSubtitleId == "NONE") "✓  Disable Subtitles" else "   Disable Subtitles"
                setTextColor(if (selectedSubtitleId == "NONE") "#E50914".toColorInt() else "#94A3B8".toColorInt())
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener {
                    selectedSubtitleId = "NONE"
                    tvSubtitleOverlay.visibility = View.GONE
                    dialog.dismiss()
                }
            }
            tracksContainer.addView(noSubRow)

            val filtered = availableSubtitles.filter {
                selectedLangFilter == "ALL" || it.languageCode.startsWith(selectedLangFilter.lowercase(Locale.ROOT))
            }

            filtered.forEach { track ->
                val isSelected = track.id == selectedSubtitleId
                val isWebDl = track.releaseName.contains("WEB", true) || track.releaseName.contains("Stream", true)
                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = if (isSelected) makeRoundedBg("#261217", 10, "#E50914") else null
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    setOnClickListener {
                        selectedSubtitleId = track.id
                        subtitleOffsetMs = 0L
                        lifecycleScope.launch {
                            val parsed = OttRepository.downloadAndParseSubtitles(track)
                            if (parsed.isNotEmpty()) {
                                activeSubtitleCues = parsed
                                tvSubtitleOverlay.visibility = View.VISIBLE
                                Toast.makeText(this@ContentDetailActivity, "Synced: ${track.languageLabel}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@ContentDetailActivity, "Choose another track below", Toast.LENGTH_SHORT).show()
                            }
                        }
                        dialog.dismiss()
                    }
                }
                val langTv = TextView(this).apply {
                    text = "${track.languageLabel}  ${if (isSelected) "✓" else ""}"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                }
                val badgeText = if (isWebDl) "100% WEB-DL Synced" else "OpenSubtitles"
                val relTv = TextView(this).apply {
                    text = "${track.releaseName} • $badgeText"
                    setTextColor(if (isWebDl) "#34D399".toColorInt() else "#94A3B8".toColorInt())
                    textSize = 11f
                }
                itemLayout.addView(langTv)
                itemLayout.addView(relTv)
                tracksContainer.addView(itemLayout)
            }
        }

        refreshBtn.setOnClickListener {
            val vod = currentVod ?: return@setOnClickListener
            refreshBtn.text = "..."
            lifecycleScope.launch {
                availableSubtitles = OttRepository.fetchRealSubtitlesForMovie(
                    vod.id,
                    fullDetails?.imdbId.orEmpty(),
                    vod.isSeries,
                    selectedSeason,
                    selectedEpisode
                )
                refreshBtn.text = "Refresh"
                renderSubtitleRows()
            }
        }

        val filters = listOf(
            "ALL" to "All",
            "ar" to "Arabic",
            "en" to "English",
            "fr" to "French",
            "es" to "Spanish",
            "tr" to "Turkish"
        )
        filters.forEach { (code, name) ->
            val chip = TextView(this).apply {
                text = name
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = makeRoundedBg(if (selectedLangFilter == code) "#E50914" else "#1C202B", 16)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    selectedLangFilter = code
                    for (i in 0 until filterRow.childCount) {
                        (filterRow.getChildAt(i) as? TextView)?.background = makeRoundedBg("#1C202B", 16)
                    }
                    background = makeRoundedBg("#E50914", 16)
                    renderSubtitleRows()
                }
            }
            filterRow.addView(chip)
        }

        if (availableSubtitles.isEmpty()) {
            refreshBtn.performClick()
        } else {
            renderSubtitleRows()
        }

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun applyFullscreenState(enterFullscreen: Boolean) {
        isFullscreen = enterFullscreen
        if (enterFullscreen) {
            isPlayerModeActive = true
            heroPosterSection.visibility = View.GONE
            playerContainer.visibility = View.VISIBLE
            detailsScrollView.visibility = View.GONE
            btnTopLock.visibility = View.VISIBLE
            fullscreenBottomActionsRow.visibility = View.VISIBLE

            playerContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, rootVerticalLayout).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, rootVerticalLayout).show(WindowInsetsCompat.Type.systemBars())

            playerContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(240)
            )
            btnTopLock.visibility = View.GONE
            fullscreenBottomActionsRow.visibility = View.GONE
            detailsScrollView.visibility = View.VISIBLE
        }
        playerContainer.requestLayout()
        resetAutoHide()
    }

    private fun toggleControlsOverlay() {
        controlsOverlay.removeCallbacks(autoHideRunnable)
        if (controlsOverlay.visibility == View.VISIBLE) {
            controlsOverlay.visibility = View.GONE
        } else {
            controlsOverlay.visibility = View.VISIBLE
            controlsOverlay.postDelayed(autoHideRunnable, 3500)
        }
    }

    private fun resetAutoHide() {
        controlsOverlay.removeCallbacks(autoHideRunnable)
        controlsOverlay.visibility = View.VISIBLE
        controlsOverlay.postDelayed(autoHideRunnable, 3500)
    }

    private fun updateFavoriteIcon(id: String) {
        val isFav = OttRepository.isChannelFavorite(id)
        btnFavBox.setImageResource(
            if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        )
    }

    private fun createWatchTogetherRoom() {
        val vod = currentVod ?: return
        val streamUrl = resolvedDirectStreams.firstOrNull()?.url ?: providerTargets.firstOrNull()?.url ?: return
        lifecycleScope.launch {
            val (ok, codeOrErr) = OttRepository.createWatchRoom(
                deviceId = SessionManager.getDeviceId(this@ContentDetailActivity),
                userName = SessionManager.getUserName(this@ContentDetailActivity),
                roomName = vod.title.take(25),
                roomIcon = "🎬",
                isPrivate = false,
                password = "",
                allowGuestsControl = true,
                contentType = "VOD",
                streamId = vod.id,
                contentTitle = vod.title,
                contentPoster = vod.posterUrl,
                streamUrl = streamUrl
            )
            if (ok) {
                playerEngine.pauseAndReleaseSocket()
                startActivity(this@ContentDetailActivity.buildDirectWatchTogetherIntent().apply {
                    putExtra(WatchRoomActivity.EXTRA_ROOM_CODE, codeOrErr)
                })
            } else {
                Toast.makeText(this@ContentDetailActivity, codeOrErr, Toast.LENGTH_LONG).show()
            }
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

    override fun onPause() {
        super.onPause()
        val p = playerEngine.player
        val vod = currentVod
        if (p != null && vod != null && p.currentPosition > 5000L && p.duration > 10000L) {
            SessionManager.saveWatchProgress(this, vod, p.currentPosition, p.duration)
        }
        if (!isChangingConfigurations) {
            playerEngine.pause()
        }
    }

    override fun onDestroy() {
        snifferTimeoutJob?.cancel()
        progressJob?.cancel()
        controlsOverlay.removeCallbacks(autoHideRunnable)
        headlessSnifferWebView.destroy()
        playerEngine.release()
        super.onDestroy()
    }
}

class EpisodesHorizontalAdapter(
    private val items: List<SeriesEpisode>,
    private var selectedEpNum: Int,
    private val onEpisodeClick: (SeriesEpisode) -> Unit
) : RecyclerView.Adapter<EpisodesHorizontalAdapter.VH>() {

    class VH(
        val root: LinearLayout,
        val cardWrap: FrameLayout,
        val ivStill: ImageView,
        val tvEpTitle: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.MarginLayoutParams((148 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (10 * d).toInt()
            }
        }
        val cardWrap = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (82 * d).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * d
                setColor("#161822".toColorInt())
            }
            clipToOutline = true
        }
        val iv = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        cardWrap.addView(iv)
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding((4 * d).toInt(), (5 * d).toInt(), (4 * d).toInt(), 0)
        }
        root.addView(cardWrap)
        root.addView(tv)
        return VH(root, cardWrap, iv, tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = items[position]
        val isSel = ep.episodeNumber == selectedEpNum
        val d = holder.itemView.context.resources.displayMetrics.density
        holder.tvEpTitle.text = "E${ep.episodeNumber}: ${ep.name}"
        holder.tvEpTitle.setTextColor(if (isSel) "#FF1E27".toColorInt() else Color.WHITE)
        holder.cardWrap.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * d
            setColor("#161822".toColorInt())
            if (isSel) setStroke((1.5f * d).toInt(), "#E50914".toColorInt())
        }
        if (ep.stillUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context).load(ep.stillUrl).centerCrop().into(holder.ivStill)
        }
        holder.root.setOnClickListener {
            selectedEpNum = ep.episodeNumber
            notifyDataSetChanged()
            onEpisodeClick(ep)
        }
    }
}

/**
 * Cast Adapter (حجم متوسط ومتوازن 76dp مع حلقة زجاجية أنيقة كيما طلبت)
 */
class CastHorizontalAdapter(
    private val items: List<CastMember>,
    private val onActorClick: (CastMember) -> Unit = {}
) : RecyclerView.Adapter<CastHorizontalAdapter.VH>() {

    class VH(
        val root: LinearLayout,
        val avatarContainer: FrameLayout,
        val ivPhoto: ImageView,
        val tvName: TextView,
        val tvRole: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val nameFont = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val roleFont = Typeface.create("sans-serif", Typeface.NORMAL)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.MarginLayoutParams((94 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (12 * d).toInt()
            }
        }

        val avatarFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((76 * d).toInt(), (76 * d).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor("#18FFFFFF".toColorInt())
                setStroke((1.2f * d).toInt(), "#38FFFFFF".toColorInt())
            }
            setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
        }

        val iv = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        avatarFrame.addView(iv)

        val tvName = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = nameFont
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, (7 * d).toInt(), 0, 0)
        }

        val tvRole = TextView(ctx).apply {
            setTextColor("#94A3B8".toColorInt())
            textSize = 10.5f
            typeface = roleFont
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, (1 * d).toInt(), 0, 0)
        }

        root.addView(avatarFrame)
        root.addView(tvName)
        root.addView(tvRole)
        return VH(root, avatarFrame, iv, tvName, tvRole)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.tvName.text = c.name
        holder.tvRole.text = c.character
        if (c.profileUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(c.profileUrl)
                .circleCrop()
                .placeholder(android.R.drawable.ic_menu_myplaces)
                .into(holder.ivPhoto)
        } else {
            holder.ivPhoto.setImageResource(android.R.drawable.ic_menu_myplaces)
        }
        holder.root.setOnClickListener { onActorClick(c) }
    }
}


// === WATCH_TOGETHER_TOP_LEVEL_EXTENSIONS ===
fun ContentDetailActivity.bindAllWatchTogetherButtonsInDetail(root: android.view.View?) {
    if (root == null) return
    val idNames = listOf(
        "btnWatchTogether", "watchTogetherButton", "btnWatchParty",
        "btnWatchRoom", "cardWatchTogether", "llWatchTogether", "btnShareWatch"
    )
    for (name in idNames) {
        val id = resources.getIdentifier(name, "id", packageName)
        if (id != 0) {
            root.findViewById<android.view.View>(id)?.setOnClickListener {
                this@bindAllWatchTogetherButtonsInDetail.launchDirectMovieWatchTogether()
            }
        }
    }
    this.findAndHookWatchViewsRecursive(root)
}

fun ContentDetailActivity.findAndHookWatchViewsRecursive(v: android.view.View) {
    if (v is android.widget.TextView) {
        val t = v.text?.toString()?.lowercase(java.util.Locale.US) ?: ""
        if (t.contains("watch together") || t.contains("مشاهدة جماعية") || t.contains("المشاهدة الجماعية") || t.contains("غرفة مشاهدة") || t.contains("مع الأصدقاء")) {
            v.setOnClickListener { this@findAndHookWatchViewsRecursive.launchDirectMovieWatchTogether() }
            (v.parent as? android.view.View)?.let { parentView ->
                if (parentView.isClickable) {
                    parentView.setOnClickListener { this@findAndHookWatchViewsRecursive.launchDirectMovieWatchTogether() }
                }
            }
        }
    }
    if (v is android.view.ViewGroup) {
        for (idx in 0 until v.childCount) {
            this.findAndHookWatchViewsRecursive(v.getChildAt(idx))
        }
    }
}

fun ContentDetailActivity.extractCurrentMovieTitle(): String {
    val extras = intent?.extras
    if (extras != null) {
        for (k in listOf("title", "name", "stream_title", "channel_name", "movie_name", "EXTRA_TITLE", "EXTRA_NAME", "content_title")) {
            val v = extras.getString(k)
            if (!v.isNullOrBlank()) return v
        }
    }
    try {
        for (f in this.javaClass.declaredFields) {
            f.isAccessible = true
            val obj = f.get(this) ?: continue
            val fn = f.name.lowercase(java.util.Locale.US)
            if (obj is String && (fn.contains("title") || fn.contains("name")) && obj.isNotBlank() && !obj.startsWith("http")) {
                return obj
            }
        }
    } catch (_: Throwable) {}
    return title?.toString()?.takeIf { it.isNotBlank() } ?: "فيلم مشترك"
}

fun ContentDetailActivity.extractCurrentMovieStreamUrl(): String {
    val isImg = { u: String ->
        val l = u.lowercase(java.util.Locale.US).substringBefore("?")
        l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp") || l.endsWith(".gif") || l.contains("/images/") || l.contains("tmdb.org")
    }

    val extras = intent?.extras
    if (extras != null) {
        for (k in listOf("stream_url", "STREAM_URL", "url", "video_url", "movie_url", "content_url", "play_url", "direct_source", "EXTRA_STREAM_URL", "EXTRA_URL")) {
            val v = extras.get(k)?.toString() ?: ""
            if (v.startsWith("http") && !isImg(v)) return v
        }
    }

    try {
        for (m in this.javaClass.declaredMethods) {
            m.isAccessible = true
            val mn = m.name.lowercase(java.util.Locale.US)
            if (m.parameterCount == 0 && m.returnType == String::class.java && (mn.contains("url") || mn.contains("stream"))) {
                val res = (m.invoke(this) as? String) ?: ""
                if (res.startsWith("http") && !isImg(res)) return res
            }
        }
    } catch (_: Throwable) {}

    var discoveredStreamId = ""
    var discoveredExt = "mp4"

    try {
        for (field in this.javaClass.declaredFields) {
            field.isAccessible = true
            val value = field.get(this) ?: continue
            val fname = field.name.lowercase(java.util.Locale.US)
            if (value is String) {
                if (value.startsWith("http") && !isImg(value)) return value
                if ((fname.contains("streamid") || fname == "id" || fname == "contentid" || fname == "vodid") && value.isNotBlank() && value != "0") {
                    discoveredStreamId = value
                }
                if (fname.contains("ext") && value.isNotBlank() && value.length <= 5) {
                    discoveredExt = value.trimStart('.')
                }
            }
        }
    } catch (_: Throwable) {}

    val prefs = getSharedPreferences("prime_ott_prefs", android.content.Context.MODE_PRIVATE)
    val host = (prefs.getString("server_url", "http://kwick.cc:8080") ?: "http://kwick.cc:8080").trimEnd('/')
    val user = prefs.getString("username", "") ?: ""
    val pass = prefs.getString("password", "") ?: ""

    if (discoveredStreamId.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()) {
        return "$host/movie/$user/$pass/$discoveredStreamId.$discoveredExt"
    }

    return ""
}

fun ContentDetailActivity.buildDirectWatchTogetherIntent(): android.content.Intent {
    val movieTitle = extractCurrentMovieTitle()
    val directStreamUrl = extractCurrentMovieStreamUrl()
    val extras = intent?.extras
    var sid = ""
    if (extras != null) {
        for (k in extras.keySet()) {
            val v = extras.get(k)?.toString() ?: ""
            if ((k.contains("id", true) || k.contains("stream", true)) && v.isNotBlank() && v != "0") {
                sid = v
                break
            }
        }
    }
    return android.content.Intent(this, com.primeott.app.presentation.watch.WatchRoomActivity::class.java).apply {
        if (extras != null) putExtras(extras)
        if (directStreamUrl.isNotEmpty()) putExtra(com.primeott.app.presentation.watch.WatchRoomActivity.EXTRA_STREAM_URL, directStreamUrl)
        putExtra(com.primeott.app.presentation.watch.WatchRoomActivity.EXTRA_STREAM_TITLE, movieTitle)
        putExtra(com.primeott.app.presentation.watch.WatchRoomActivity.EXTRA_CHANNEL_NAME, movieTitle)
        putExtra(com.primeott.app.presentation.watch.WatchRoomActivity.EXTRA_ROOM_NAME, "غرفة " + movieTitle)
        putExtra(com.primeott.app.presentation.watch.WatchRoomActivity.EXTRA_IS_HOST, true)
        if (sid.isNotEmpty()) putExtra("stream_id", sid)
        putExtra("direct_movie_launch", true)
    }
}

fun ContentDetailActivity.launchDirectMovieWatchTogether() {
    try {
        startActivity(buildDirectWatchTogetherIntent())
    } catch (e: Throwable) {
        android.widget.Toast.makeText(this, "جاري فتح غرفة المشاهدة...", android.widget.Toast.LENGTH_SHORT).show()
    }
}

