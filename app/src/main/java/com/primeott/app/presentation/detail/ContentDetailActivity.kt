package com.primeott.app.presentation.detail

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
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
        private const val SNIFFER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var playerEngine: PrimePlayerEngine
    private var currentVod: VodItem? = null
    private var fullDetails: MovieFullDetails? = null

    private val resolvedDirectStreams = mutableListOf<StreamSource>()
    private var providerTargets: List<StreamSource> = emptyList()
    private var currentProviderIndex: Int = 0
    private var hasLockedPrimaryStream: Boolean = false
    private var selectedSeason: Int = 1
    private var selectedEpisode: Int = 1

    private var availableSubtitles: List<SubtitleTrack> = emptyList()
    private var activeSubtitleCues: List<SubtitleCue> = emptyList()
    private var selectedSubtitleId: String = ""
    // معايرة تزامن الترجمة بالمللي ثانية (+ أو - لتطابق الصوت 100%)
    private var subtitleOffsetMs: Long = 0L

    private var currentPlaybackSpeed: Float = 1.0f
    private var isFullscreen: Boolean = false
    private var isControlsLocked: Boolean = false
    private var isUserSeeking: Boolean = false
    private var progressJob: Job? = null
    private var snifferTimeoutJob: Job? = null
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

    private lateinit var detailsScrollView: ScrollView
    private lateinit var tvMovieTitle: TextView
    private lateinit var tvMovieRating: TextView
    private lateinit var metaPillsRow: LinearLayout
    private lateinit var btnFavBox: TextView
    private lateinit var btnDownloadBox: TextView
    private lateinit var btnTrailerBox: TextView
    private lateinit var btnWatchPartyBox: TextView
    private lateinit var seriesContainer: LinearLayout
    private lateinit var seasonTabsRow: LinearLayout
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var tvOverviewText: TextView
    private lateinit var tvCastHeader: TextView
    private lateinit var rvCast: RecyclerView
    private lateinit var tvRecommendedHeader: TextView
    private lateinit var rvRecommended: RecyclerView
    private lateinit var tvMoreLikeHeader: TextView
    private lateinit var rvMoreLike: RecyclerView

    // متغير يحفظ الجودات المختارة حالياً (null = أعلى جودة تلقائية)
    private var selectedQualityHeight: Int? = null

    // 1. متغيرات متابعة موضع الاستكمال والحفظ الدوري
    private var pendingResumePositionMs: Long = 0L
    private var lastSavedTick: Int = 0

    private val autoHideRunnable = Runnable {
        controlsOverlay.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                if (isFullscreen) {
                    applyFullscreenState(false)
                } else {
                    finish()
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

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPrimeTvDetailLayout() {
        rootVerticalLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#08080C".toColorInt())
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        playerContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(235)
            )
        }

        headlessSnifferWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            alpha = 0f
            visibility = View.INVISIBLE
        }
        playerContainer.addView(headlessSnifferWebView)

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
            indeterminateTintList = android.content.res.ColorStateList.valueOf("#E50914".toColorInt())
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
            setPadding(dp(14), dp(12), dp(14), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        tvTopPlayerTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnTopLock = TextView(this).apply {
            text = "🔓"
            textSize = 16f
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
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = makeRoundedBg("#22FFFFFF", 5, "#FFFFFF")
            setPadding(dp(8), dp(3), dp(8), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener { showPrimeServersAndQualityPopup() }
        }

        btnTopSubtitles = TextView(this).apply {
            text = "文A"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(6), dp(4))
            setOnClickListener { showSubtitlesDialog() }
        }

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
            setTypeface(typeface, Typeface.BOLD)
        }

        seekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf("#E50914".toColorInt())
            thumbTintList = android.content.res.ColorStateList.valueOf("#E50914".toColorInt())
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
            setTypeface(typeface, Typeface.BOLD)
        }

        btnInlineFullscreen = TextView(this).apply {
            text = "⛶"
            setTextColor(Color.WHITE)
            textSize = 17f
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
            text = "💬 Subtitle & Sync"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { showSubtitlesDialog() }
        }

        btnBottomSpeed = TextView(this).apply {
            text = "⏱ Normal"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { showPlaybackSpeedDialog() }
        }

        btnBottomAspect = TextView(this).apply {
            text = "⛶ Aspect Ratio"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
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

        detailsScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isVerticalScrollBarEnabled = false
        }

        val detailsBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(40))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        tvMovieTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        detailsBody.addView(tvMovieTitle)

        tvMovieRating = TextView(this).apply {
            setTextColor("#FFC107".toColorInt())
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(10))
        }
        detailsBody.addView(tvMovieRating)

        val pillsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        metaPillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pillsScroll.addView(metaPillsRow)
        detailsBody.addView(pillsScroll)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(16))
        }

        btnFavBox = TextView(this).apply {
            text = "♡"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = makeRoundedBg("#14161E", 10, "#2A2E3D")
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) }
            setOnClickListener {
                val id = currentVod?.id ?: return@setOnClickListener
                OttRepository.toggleFavorite(id, this@ContentDetailActivity)
                updateFavoriteIcon(id)
            }
        }

        btnDownloadBox = TextView(this).apply {
            text = "⬇"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = makeRoundedBg("#14161E", 10, "#2A2E3D")
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) }
            setOnClickListener {
                Toast.makeText(this@ContentDetailActivity, "⬇ Preparing 1080p Direct Download...", Toast.LENGTH_SHORT).show()
            }
        }

        btnTrailerBox = TextView(this).apply {
            text = "🎬 Trailer"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = makeRoundedBg("#14161E", 10, "#2A2E3D")
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(10) }
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

        btnWatchPartyBox = TextView(this).apply {
            text = "👥 Watch Together"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = makeRoundedBg("#1E293B", 10, "#38BDF8")
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
            setOnClickListener { createWatchTogetherRoom() }
        }

        actionsRow.addView(btnFavBox)
        actionsRow.addView(btnDownloadBox)
        actionsRow.addView(btnTrailerBox)
        actionsRow.addView(btnWatchPartyBox)
        detailsBody.addView(actionsRow)

        seriesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(14))
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110))
        }
        seriesContainer.addView(rvEpisodes)
        detailsBody.addView(seriesContainer)

        val overviewHeader = TextView(this).apply {
            text = "Overview"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
        }
        detailsBody.addView(overviewHeader)

        tvOverviewText = TextView(this).apply {
            setTextColor("#B0B8C4".toColorInt())
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }
        detailsBody.addView(tvOverviewText)

        tvCastHeader = TextView(this).apply {
            text = "Cast"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(10))
        }
        detailsBody.addView(tvCastHeader)

        rvCast = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ContentDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(125))
        }
        detailsBody.addView(rvCast)

        tvRecommendedHeader = TextView(this).apply {
            text = "Recommended"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(10))
        }
        detailsBody.addView(tvRecommendedHeader)

        rvRecommended = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ContentDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(195))
        }
        detailsBody.addView(rvRecommended)

        tvMoreLikeHeader = TextView(this).apply {
            text = "More Like This"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(10))
        }
        detailsBody.addView(tvMoreLikeHeader)

        rvMoreLike = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ContentDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(195))
        }
        detailsBody.addView(rvMoreLike)

        detailsScrollView.addView(detailsBody)
        rootVerticalLayout.addView(detailsScrollView)

        setContentView(rootVerticalLayout)
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
                        languageLabel = if (isAr) "⭐ Arabic (100% Stream Synced)" else "⭐ English (100% Stream Synced)",
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

    /**
     * قفل التشغيل على أول رابط شغال وإيقاف WebView الخفي تماماً لمنع أي لاغ أو إعادة تشغيل
     */
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
                // 3. تمرير pendingResumePositionMs للاستكمال من آخر دقيقة
                playerEngine.playSources(playerView, resolvedDirectStreams.toList(), 0, pendingResumePositionMs)
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
        loadingContainer.visibility = View.GONE
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

    private fun loadVodItemIntoScreen(vod: VodItem) {
        currentVod = vod
        selectedSeason = 1
        selectedEpisode = 1
        selectedSubtitleId = ""
        subtitleOffsetMs = 0L
        activeSubtitleCues = emptyList()
        availableSubtitles = emptyList()

        // 2. قراءة موضع التوقف المحفوظ وتسجيل الفيلم في سجل المشاهدة
        pendingResumePositionMs = SessionManager.getSavedPositionForVod(this, vod.id)
            .coerceAtLeast(vod.savedPositionMs)
        SessionManager.saveWatchProgress(this, vod, pendingResumePositionMs, vod.totalDurationMs)

        tvTopPlayerTitle.text = vod.title
        tvMovieTitle.text = vod.title
        tvMovieRating.text = "★ ${vod.rating}"
        tvOverviewText.text = vod.description
        updateFavoriteIcon(vod.id)
        renderMetadataPills(vod.year, "1h 45m", listOf(vod.category.ifEmpty { "Movie" }))

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
            renderMetadataPills(details.year, details.runtimeText, details.genres)

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

            rvRecommended.adapter = VodGridAdapter(details.recommended) { clickedVod ->
                detailsScrollView.smoothScrollTo(0, 0)
                loadVodItemIntoScreen(clickedVod)
            }

            rvMoreLike.adapter = VodGridAdapter(details.similar) { clickedVod ->
                detailsScrollView.smoothScrollTo(0, 0)
                loadVodItemIntoScreen(clickedVod)
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
                child.background = makeRoundedBg(if (i + 1 == seasonNum) "#E50914" else "#161822", 8)
            }
            lifecycleScope.launch {
                val episodes = PrimeVodExtractor.fetchSeasonEpisodes(seriesId, seasonNum)
                rvEpisodes.adapter = EpisodesHorizontalAdapter(episodes, selectedEpisode) { ep ->
                    selectedEpisode = ep.episodeNumber
                    val vod = currentVod ?: return@EpisodesHorizontalAdapter
                    tvTopPlayerTitle.text = "${vod.title} S${seasonNum}:E${ep.episodeNumber}"
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
                background = makeRoundedBg(if (s == selectedSeason) "#E50914" else "#161822", 8)
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
        Toast.makeText(this, "⏳ Loading ${actor.name} movies...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val credits = PrimeVodExtractor.fetchActorCredits(actor.id)
            if (credits.isEmpty()) {
                Toast.makeText(this@ContentDetailActivity, "No additional credits found", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val titles = credits.map { "${it.title} (${it.year})  ★ ${it.rating}" }.toTypedArray()
            android.app.AlertDialog.Builder(this@ContentDetailActivity)
                .setTitle("🎭 ${actor.name}")
                .setItems(titles) { _, which ->
                    detailsScrollView.smoothScrollTo(0, 0)
                    loadVodItemIntoScreen(credits[which])
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun renderMetadataPills(year: String, runtime: String, genres: List<String>) {
        metaPillsRow.removeAllViews()
        val items = mutableListOf("📅 $year", "🕒 $runtime")
        items.addAll(genres)
        items.forEach { label ->
            val pill = TextView(this).apply {
                text = label
                setTextColor("#E2E8F0".toColorInt())
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                background = makeRoundedBg("#14161E", 8, "#262B38")
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
            metaPillsRow.addView(pill)
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

                // 4. حفظ الموضع تلقائياً كل 3 ثوانٍ وتحديث الشريط
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
                    btnBottomSpeed.text = "⏱ $label"
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
            text = "💬 Subtitles (${availableSubtitles.size})"
            setTextColor(Color.WHITE)
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val refreshBtn = TextView(this).apply {
            text = "🔄 Refresh"
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
            text = String.format(Locale.US, "⏱ Sync: %+.1fs", subtitleOffsetMs / 1000.0)
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
                tvSyncStatus.text = String.format(Locale.US, "⏱ Sync: %+.1fs", subtitleOffsetMs / 1000.0)
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
                tvSyncStatus.text = "⏱ Sync: +0.0s"
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
                tvSyncStatus.text = String.format(Locale.US, "⏱ Sync: %+.1fs", subtitleOffsetMs / 1000.0)
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
            titleTv.text = "💬 Subtitles (${availableSubtitles.size})"

            val noSubRow = TextView(this).apply {
                text = if (selectedSubtitleId == "NONE") "✓  Disable Subtitles" else "    Disable Subtitles"
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
                                Toast.makeText(this@ContentDetailActivity, "✅ Synced: ${track.languageLabel}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@ContentDetailActivity, "⚠️ Choose another track below", Toast.LENGTH_SHORT).show()
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
                val badgeText = if (isWebDl) "⚡ 100% WEB-DL Synced" else "✓ OpenSubtitles"
                val relTv = TextView(this).apply {
                    text = "${track.releaseName}  $badgeText"
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
            refreshBtn.text = "⏳..."
            lifecycleScope.launch {
                availableSubtitles = OttRepository.fetchRealSubtitlesForMovie(
                    vod.id,
                    fullDetails?.imdbId.orEmpty(),
                    vod.isSeries,
                    selectedSeason,
                    selectedEpisode
                )
                refreshBtn.text = "🔄 Refresh"
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
                dp(235)
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
        btnFavBox.text = if (isFav) "♥" else "♡"
        btnFavBox.setTextColor(if (isFav) "#E50914".toColorInt() else Color.WHITE)
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
                startActivity(Intent(this@ContentDetailActivity, WatchRoomActivity::class.java).apply {
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
        // 4. حفظ الموضع عند الخروج من الشاشة أو الانتقال لتطبيق آخر
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
        val ivStill: ImageView,
        val tvEpTitle: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.MarginLayoutParams((145 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (10 * d).toInt()
            }
        }
        val iv = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (80 * d).toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor("#161822".toColorInt())
        }
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), 0)
        }
        root.addView(iv)
        root.addView(tv)
        return VH(root, iv, tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = items[position]
        val isSel = ep.episodeNumber == selectedEpNum
        holder.tvEpTitle.text = "E${ep.episodeNumber}: ${ep.name}"
        holder.tvEpTitle.setTextColor(if (isSel) "#E50914".toColorInt() else Color.WHITE)
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

class CastHorizontalAdapter(
    private val items: List<CastMember>,
    private val onActorClick: (CastMember) -> Unit = {}
) : RecyclerView.Adapter<CastHorizontalAdapter.VH>() {

    class VH(
        val root: LinearLayout,
        val ivPhoto: ImageView,
        val tvName: TextView,
        val tvRole: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.MarginLayoutParams((86 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (10 * d).toInt()
            }
        }
        val iv = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((68 * d).toInt(), (68 * d).toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val tvName = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, 0)
        }
        val tvRole = TextView(ctx).apply {
            setTextColor("#94A3B8".toColorInt())
            textSize = 10.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        root.addView(iv)
        root.addView(tvName)
        root.addView(tvRole)
        return VH(root, iv, tvName, tvRole)
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