package com.primeott.app.presentation.detail

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import androidx.cardview.widget.CardView
import androidx.core.content.res.ResourcesCompat
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class ContentDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VOD = "extra_vod_item"
        private const val SNIFFER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val INTRO_START_MS = 5_000L
        private const val INTRO_END_MS = 90_000L

        // اللون الابتدائي الهادئ عند أول لحظة لفتح الصفحة قبل انتشار لون الكوفر خلال أول 3 ثوانٍ
        private val FLIX_INITIAL_LEFT_COLOR = "#233242".toColorInt()
        private val FLIX_INITIAL_RIGHT_COLOR = "#000000".toColorInt()

        private val detailsCache = ConcurrentHashMap<String, MovieFullDetails>()
        private val paletteCache = ConcurrentHashMap<String, Pair<Int, Int>>()
        private val actorCreditsCache = ConcurrentHashMap<String, List<VodItem>>()
    }

    private sealed class NavHistoryEntry {
        data class MovieState(
            val vod: VodItem,
            val scrollY: Int
        ) : NavHistoryEntry()

        data class ActorState(
            val actor: CastMember,
            val credits: List<VodItem>,
            val filterMode: String,
            val sortByYearDesc: Boolean,
            val firstVisibleGridPos: Int
        ) : NavHistoryEntry()
    }

    private val navigationBackStack = ArrayDeque<NavHistoryEntry>()

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
    private var fullOverviewRawText: String = ""
    private var hasSkippedIntroForCurrentMedia: Boolean = false

    private var progressJob: Job? = null
    private var snifferTimeoutJob: Job? = null
    private var detailsFetchJob: Job? = null
    private var streamFetchJob: Job? = null

    private var currentLeftBrightColor: Int = FLIX_INITIAL_LEFT_COLOR
    private var currentRightDarkColor: Int = FLIX_INITIAL_RIGHT_COLOR
    private var colorExtractionAnimator: ValueAnimator? = null
    private lateinit var fullScreenAmbientDrawable: FlixVisionFullScreenAmbientDrawable

    private lateinit var fontProductBold: Typeface
    private lateinit var fontProductRegular: Typeface

    private var resizeModeIndex: Int = 0
    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )

    private lateinit var rootFrameWrapper: FrameLayout
    private lateinit var heroBackdropFixedContainer: FrameLayout
    private lateinit var ivHeroBackdrop: ImageView
    private lateinit var playTransitionOverlay: FrameLayout
    private lateinit var ivPlayTransitionPulse: ImageView
    private lateinit var vFullDynamicAmbientBg: View

    private lateinit var rootVerticalLayout: LinearLayout
    private lateinit var heroTopActionsRow: FrameLayout
    private lateinit var btnTopBackHero: ImageView
    private lateinit var btnFavBox: ImageView

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
    private lateinit var btnTopLock: ImageView
    private lateinit var btnTopServerHd: TextView
    private lateinit var btnTopSubtitles: ImageView

    private lateinit var btnRewind15: ImageView
    private lateinit var btnCenterPlayPause: ImageView
    private lateinit var btnForward15: ImageView
    private lateinit var tvSeekFeedbackBadge: TextView
    private lateinit var btnSkipIntro: LinearLayout

    private lateinit var tvElapsed: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvDuration: TextView
    private lateinit var btnInlineFullscreen: ImageView
    private lateinit var fullscreenBottomActionsRow: LinearLayout
    private lateinit var btnBottomSubtitles: LinearLayout
    private lateinit var btnBottomSpeed: LinearLayout
    private lateinit var tvBottomSpeedLabel: TextView
    private lateinit var btnBottomAspect: LinearLayout
    private lateinit var btnUnlockScreenOnly: LinearLayout

    private lateinit var detailsScrollView: ScrollView
    private lateinit var detailsMasterColumn: LinearLayout
    private lateinit var vHeroTopSpacer: View
    private lateinit var contentSheetColumn: LinearLayout

    private lateinit var tvMovieTitle: TextView
    private lateinit var tvMovieGenresLine: TextView
    private lateinit var metaPillsRow: LinearLayout
    private lateinit var btnPlayNowMain: LinearLayout
    private lateinit var tvPlayNowBtnLabel: TextView
    private lateinit var btnWatchPartyBox: LinearLayout
    private lateinit var btnTrailerBox: LinearLayout
    private lateinit var btnDownloadBox: FrameLayout

    private lateinit var seriesContainer: LinearLayout
    private lateinit var seasonTabsRow: LinearLayout
    private lateinit var rvEpisodes: RecyclerView

    private lateinit var tvOverviewText: TextView
    private lateinit var crewMetadataContainer: LinearLayout
    private lateinit var rowDirector: LinearLayout
    private lateinit var tvDirectorValue: TextView
    private lateinit var rowWriters: LinearLayout
    private lateinit var tvWritersValue: TextView

    private lateinit var tvCastHeader: TextView
    private lateinit var rvCast: RecyclerView
    private lateinit var tvRecommendedHeader: TextView
    private lateinit var rvRecommended: RecyclerView
    private lateinit var tvMoreLikeHeader: TextView
    private lateinit var rvMoreLike: RecyclerView

    private lateinit var actorPageOverlay: LinearLayout
    private lateinit var ivActorPageAvatar: ImageView
    private lateinit var tvActorPageName: TextView
    private lateinit var tvActorMoviesTab: TextView
    private lateinit var tvActorSeriesTab: TextView
    private lateinit var btnActorSort: ImageView
    private lateinit var actorPageSpinner: ProgressBar
    private lateinit var rvActorCreditsGrid: RecyclerView
    private var currentActiveActor: CastMember? = null
    private var currentActorCreditsList: List<VodItem> = emptyList()
    private var currentActorFilterMode: String = "ALL"
    private var currentActorSortByYearDesc: Boolean = true

    private var selectedQualityHeight: Int? = null
    private var pendingResumePositionMs: Long = 0L
    private var lastSavedTick: Int = 0

    private val autoHideRunnable = Runnable {
        controlsOverlay.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fontProductBold = runCatching {
            ResourcesCompat.getFont(this, R.font.product_sans_bold)
        }.getOrNull() ?: Typeface.create("sans-serif", Typeface.BOLD)

        fontProductRegular = runCatching {
            ResourcesCompat.getFont(this, R.font.product_sans_egular)
        }.getOrNull() ?: Typeface.create("sans-serif", Typeface.NORMAL)

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
                handleSmartBackNavigation()
            }
        })

        playerEngine = PrimePlayerEngine(
            context = this,
            scope = lifecycleScope,
            onSourceSwitched = { _, _ ->
                updatePlayPauseVectorIcon(true)
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
                    updatePlayPauseVectorIcon(true)
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
        loadVodItemIntoScreen(item, resetScroll = true)
        startPlayerProgressAndSubtitlesLoop()
    }

    private fun handleSmartBackNavigation() {
        when {
            isFullscreen -> {
                applyFullscreenState(false)
            }
            actorPageOverlay.isVisible -> {
                closeActorPageOverlayAndRestoreIfNeeded()
            }
            isPlayerModeActive -> {
                switchToPosterShowcaseMode()
            }
            navigationBackStack.isNotEmpty() -> {
                when (val prev = navigationBackStack.removeLast()) {
                    is NavHistoryEntry.ActorState -> {
                        restoreActorPageFromState(prev)
                    }
                    is NavHistoryEntry.MovieState -> {
                        loadVodItemIntoScreen(prev.vod, resetScroll = false, targetScrollY = prev.scrollY)
                    }
                }
            }
            else -> {
                finish()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

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

    /**
     * استخراج لون الغلاف (الجهة اليسرى بلون الكوفر المتناسق، والجهة اليمنى أسود ناعم مثل FlixVision)
     */
    private fun extractFlixVisionPalette(bitmap: Bitmap): Pair<Int, Int> {
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 40, 40, false)
            var rSum = 0f
            var gSum = 0f
            var bSum = 0f
            var weightTotal = 0f
            val hsv = FloatArray(3)

            for (y in 6 until 36) {
                for (x in 2 until 38) {
                    val pixel = scaled.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    Color.colorToHSV(pixel, hsv)
                    val sat = hsv[1]
                    val value = hsv[2]

                    if (value in 0.12f..0.92f && sat >= 0.10f) {
                        val weight = (sat * 2.5f) + (1f - abs(value - 0.50f))
                        rSum += r * weight
                        gSum += g * weight
                        bSum += b * weight
                        weightTotal += weight
                    }
                }
            }

            if (weightTotal <= 0.01f) {
                "#283646".toColorInt() to "#000000".toColorInt()
            } else {
                val baseColor = Color.rgb(
                    (rSum / weightTotal).toInt().coerceIn(0, 255),
                    (gSum / weightTotal).toInt().coerceIn(0, 255),
                    (bSum / weightTotal).toInt().coerceIn(0, 255)
                )
                Color.colorToHSV(baseColor, hsv)
                // توازن دقيق للإشباع والإضاءة ليطابق لقطة FlixVision (The Invite / Bad Apples)
                hsv[1] = hsv[1].coerceIn(0.45f, 0.78f)
                hsv[2] = hsv[2].coerceIn(0.32f, 0.44f)
                val leftBrightColor = Color.HSVToColor(hsv)
                val rightDarkColor = "#000000".toColorInt()

                leftBrightColor to rightDarkColor
            }
        } catch (_: Exception) {
            "#283646".toColorInt() to "#000000".toColorInt()
        }
    }

    /**
     * أنيميشن استخراج اللون خلال أول 3 ثوانٍ (3000ms) بنعومة تامة، ثم التوقف والاستقرار بدون أي قفزة
     */
    private fun startCoverColorExtractionAnimation(targetLeftColor: Int, targetRightColor: Int) {
        colorExtractionAnimator?.cancel()

        val evaluator = ArgbEvaluator()
        val startLeft = FLIX_INITIAL_LEFT_COLOR
        val startRight = FLIX_INITIAL_RIGHT_COLOR

        currentLeftBrightColor = startLeft
        currentRightDarkColor = startRight
        fullScreenAmbientDrawable.updateColors(
            leftBright = startLeft,
            rightDark = startRight,
            extractionFraction = 0f
        )

        colorExtractionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2800L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { va ->
                val f = va.animatedFraction
                val liveLeft = evaluator.evaluate(f, startLeft, targetLeftColor) as Int
                val liveRight = evaluator.evaluate(f, startRight, targetRightColor) as Int
                currentLeftBrightColor = liveLeft
                currentRightDarkColor = liveRight
                fullScreenAmbientDrawable.updateColors(
                    leftBright = liveLeft,
                    rightDark = liveRight,
                    extractionFraction = f
                )
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPrimeTvDetailLayout() {
        // وسط الكوفر يبقى صافياً تماماً، والتدرج السفلي يبدأ بنعومة عند 175dp ويندمج 100% عند نهاية اسم الفيلم (295dp)
        fullScreenAmbientDrawable = FlixVisionFullScreenAmbientDrawable(
            coverHeightPx = dpF(365f),
            bottomFadeStartAtRestPx = dpF(175f),
            fullOpaqueYAtRestPx = dpF(295f),
            scrollToHideDistancePx = dpF(195f)
        )

        rootFrameWrapper = FrameLayout(this).apply {
            setBackgroundColor("#000000".toColorInt())
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. الكوفر الثابت في الأعلى (365dp)
        heroBackdropFixedContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(365),
                Gravity.TOP
            )
        }

        ivHeroBackdrop = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        heroBackdropFixedContainer.addView(ivHeroBackdrop)

        playTransitionOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor("#77000000".toColorInt())
            alpha = 0f
            visibility = View.GONE
        }
        ivPlayTransitionPulse = ImageView(this).apply {
            setImageDrawable(VectorIcons.createPlayPauseIcon(this@ContentDetailActivity, isPlaying = false))
            background = makeRoundedBg("#E6FFFFFF".toColorInt(), 36f)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf("#08090E".toColorInt()))
            setPadding(dp(18), dp(18), dp(16), dp(18))
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER)
        }
        playTransitionOverlay.addView(ivPlayTransitionPulse)
        heroBackdropFixedContainer.addView(playTransitionOverlay)

        rootFrameWrapper.addView(heroBackdropFixedContainer)

        // 2. طبقة الألوان الديناميكية الموحدة (تندمج مع أسفل الكوفر وجوانبه اليمنى واليسرى بدون أي خط فاصل)
        vFullDynamicAmbientBg = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = fullScreenAmbientDrawable
        }
        rootFrameWrapper.addView(vFullDynamicAmbientBg)

        // 3. العمود الرئيسي
        rootVerticalLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        headlessSnifferWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            alpha = 0f
            visibility = View.GONE
        }
        rootFrameWrapper.addView(headlessSnifferWebView)

        // =========================================================================
        // A. MOVIE & SERIES INLINE PLAYER (بدون أي فراغ علوي)
        // =========================================================================
        playerContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(210)
            ).apply {
                topMargin = 0
                bottomMargin = 0
            }
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP
            )
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        playerContainer.addView(playerView)

        tvSubtitleOverlay = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                setMargins(dp(18), 0, dp(18), dp(32))
            }
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = fontProductBold
            setTextColor(Color.WHITE)
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
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            indeterminateTintList = ColorStateList.valueOf("#E50914".toColorInt())
        }
        tvLoadingStatus = TextView(this).apply {
            text = "Loading 1080p Stream..."
            setTextColor("#E2E8F0".toColorInt())
            textSize = 11.5f
            typeface = fontProductBold
            setPadding(0, dp(6), 0, 0)
        }
        loadingContainer.addView(loadingSpinner)
        loadingContainer.addView(tvLoadingStatus)
        playerContainer.addView(loadingContainer)

        tvSeekFeedbackBadge = TextView(this).apply {
            text = "+15s"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = fontProductBold
            background = makeRoundedBg("#B3000000".toColorInt(), 20f, "#66FFFFFF".toColorInt(), 1f)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                topMargin = dp(50)
            }
        }
        playerContainer.addView(tvSeekFeedbackBadge)

        btnSkipIntro = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#D912151E".toColorInt(), 20f, "#CCFFFFFF".toColorInt(), 1.2f)
            setPadding(dp(14), dp(7), dp(12), dp(7))
            visibility = View.GONE
            elevation = dpF(6f)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                setMargins(0, 0, dp(14), dp(44))
            }
            setOnClickListener {
                val exo = playerEngine.player ?: return@setOnClickListener
                hasSkippedIntroForCurrentMedia = true
                val target = max(exo.currentPosition + 80_000L, INTRO_END_MS)
                exo.seekTo(target.coerceAtMost(exo.duration.coerceAtLeast(target)))
                animate().alpha(0f).translationX(dpF(20f)).setDuration(200L).withEndAction {
                    visibility = View.GONE
                    alpha = 1f
                    translationX = 0f
                }.start()
                showSeekFeedbackToast("Intro Skipped")
            }
        }
        val tvSkipIntroLabel = TextView(this).apply {
            text = "Skip Intro"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = fontProductBold
        }
        val ivSkipIntroArrow = ImageView(this).apply {
            setImageDrawable(VectorIcons.createSkipNextIcon(this@ContentDetailActivity, Color.WHITE))
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                marginStart = dp(5)
            }
        }
        btnSkipIntro.addView(tvSkipIntroLabel)
        btnSkipIntro.addView(ivSkipIntroArrow)
        AnimUtils.attachPressBounce(btnSkipIntro)
        playerContainer.addView(btnSkipIntro)

        controlsOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf("#B3000000".toColorInt(), "#4D000000".toColorInt(), "#CC000000".toColorInt())
            )
            visibility = View.VISIBLE
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(12), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        btnMinimizePlayer = ImageView(this).apply {
            setImageResource(R.drawable.ic_back_chevron)
            background = makeRoundedBg("#40FFFFFF".toColorInt(), 18f)
            setPadding(dp(7), dp(7), dp(8), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
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
            textSize = 14f
            typeface = fontProductBold
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnTopLock = ImageView(this).apply {
            setImageDrawable(VectorIcons.createLockIcon(this@ContentDetailActivity, Color.WHITE, locked = false))
            background = makeRoundedBg("#33FFFFFF".toColorInt(), 8f, "#55FFFFFF".toColorInt(), 0.8f)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(6) }
            setOnClickListener {
                isControlsLocked = true
                controlsOverlay.visibility = View.GONE
                btnUnlockScreenOnly.visibility = View.VISIBLE
            }
        }

        btnTopServerHd = TextView(this).apply {
            text = "FHD"
            textSize = 11f
            typeface = fontProductBold
            setTextColor(Color.WHITE)
            background = makeRoundedBg("#33E50914".toColorInt(), 8f, "#99FF4757".toColorInt(), 1f)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { showPrimeServersAndQualityPopup() }
        }

        btnTopSubtitles = ImageView(this).apply {
            setImageDrawable(VectorIcons.createSubtitlesCcIcon(this@ContentDetailActivity, Color.WHITE))
            background = makeRoundedBg("#33FFFFFF".toColorInt(), 8f, "#55FFFFFF".toColorInt(), 0.8f)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener { showSubtitlesDialog() }
        }

        topBar.addView(btnMinimizePlayer)
        topBar.addView(tvTopPlayerTitle)
        topBar.addView(btnTopLock)
        topBar.addView(btnTopServerHd)
        topBar.addView(btnTopSubtitles)
        controlsOverlay.addView(topBar)

        val centerControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        btnRewind15 = ImageView(this).apply {
            setImageDrawable(VectorIcons.createSeek15Icon(this@ContentDetailActivity, isForward = false))
            background = makeRoundedBg("#660A0C12".toColorInt(), 24f, "#50FFFFFF".toColorInt(), 1f)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                marginEnd = dp(24)
            }
            setOnClickListener {
                resetAutoHide()
                seekByDeltaMs(-15_000L, "-15s")
            }
        }

        btnCenterPlayPause = ImageView(this).apply {
            setImageDrawable(VectorIcons.createPlayPauseIcon(this@ContentDetailActivity, isPlaying = true))
            background = makeRoundedBg("#CC0B0D14".toColorInt(), 30f, "#EEFFFFFF".toColorInt(), 1.4f)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
            setOnClickListener {
                resetAutoHide()
                if (playerEngine.isPlaying()) {
                    playerEngine.pause()
                    updatePlayPauseVectorIcon(false)
                } else {
                    playerEngine.resume()
                    updatePlayPauseVectorIcon(true)
                }
            }
        }

        btnForward15 = ImageView(this).apply {
            setImageDrawable(VectorIcons.createSeek15Icon(this@ContentDetailActivity, isForward = true))
            background = makeRoundedBg("#660A0C12".toColorInt(), 24f, "#50FFFFFF".toColorInt(), 1f)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                marginStart = dp(24)
            }
            setOnClickListener {
                resetAutoHide()
                seekByDeltaMs(15_000L, "+15s")
            }
        }

        AnimUtils.attachPressBounce(btnRewind15)
        AnimUtils.attachPressBounce(btnCenterPlayPause)
        AnimUtils.attachPressBounce(btnForward15)

        centerControlsRow.addView(btnRewind15)
        centerControlsRow.addView(btnCenterPlayPause)
        centerControlsRow.addView(btnForward15)
        controlsOverlay.addView(centerControlsRow)

        val bottomPlayerBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
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
            textSize = 11.5f
            typeface = fontProductBold
        }

        seekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf("#E50914".toColorInt())
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
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
            setTextColor("#CBD5E1".toColorInt())
            textSize = 11.5f
            typeface = fontProductBold
        }

        btnInlineFullscreen = ImageView(this).apply {
            setImageDrawable(VectorIcons.createFullscreenIcon(this@ContentDetailActivity, isFullscreen = false))
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

        btnBottomSubtitles = createPlayerBottomActionChip(
            icon = VectorIcons.createSubtitlesCcIcon(this, Color.WHITE),
            label = "Subtitles"
        ) { showSubtitlesDialog() }

        tvBottomSpeedLabel = TextView(this).apply {
            text = "Speed: 1x"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = fontProductBold
        }
        btnBottomSpeed = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#26FFFFFF".toColorInt(), 16f, "#40FFFFFF".toColorInt(), 0.8f)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            addView(tvBottomSpeedLabel)
            setOnClickListener { showPlaybackSpeedDialog() }
        }

        btnBottomAspect = createPlayerBottomActionChip(
            icon = VectorIcons.createAspectRatioIcon(this, Color.WHITE),
            label = "Scale"
        ) {
            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
            playerView.resizeMode = resizeModes[resizeModeIndex]
        }

        fullscreenBottomActionsRow.addView(btnBottomSubtitles)
        fullscreenBottomActionsRow.addView(btnBottomSpeed)
        fullscreenBottomActionsRow.addView(btnBottomAspect)
        bottomPlayerBar.addView(fullscreenBottomActionsRow)

        controlsOverlay.addView(bottomPlayerBar)
        playerContainer.addView(controlsOverlay)

        btnUnlockScreenOnly = LinearLayout(this).apply {
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
            val ivLock = ImageView(this@ContentDetailActivity).apply {
                setImageDrawable(VectorIcons.createLockIcon(this@ContentDetailActivity, Color.WHITE, locked = true))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
            }
            val tvUnlock = TextView(this@ContentDetailActivity).apply {
                text = "Tap to Unlock"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = fontProductBold
            }
            addView(ivLock)
            addView(tvUnlock)
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

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isControlsLocked) {
                    val width = playerView.width.coerceAtLeast(1)
                    if (e.x < width * 0.45f) {
                        seekByDeltaMs(-15_000L, "-15s")
                    } else if (e.x > width * 0.55f) {
                        seekByDeltaMs(15_000L, "+15s")
                    }
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
        // B. SCROLLABLE CONTENT SHEET OVER HERO COVER
        // =========================================================================
        detailsScrollView = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
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
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        vHeroTopSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(230)
            )
        }
        detailsMasterColumn.addView(vHeroTopSpacer)

        contentSheetColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(44))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        tvMovieTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = fontProductBold
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(10f, 0f, 3f, Color.BLACK)
        }
        contentSheetColumn.addView(tvMovieTitle)

        tvMovieGenresLine = TextView(this).apply {
            setTextColor("#D8E2EE".toColorInt())
            textSize = 12.5f
            typeface = fontProductRegular
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, dp(10))
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        contentSheetColumn.addView(tvMovieGenresLine)

        // 1. البطاقات الصغيرة (Rating | PG-13 | Year | Runtime | FHD)
        val pillsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        metaPillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pillsScroll.addView(metaPillsRow)
        contentSheetColumn.addView(pillsScroll)

        // 2. صف الأزرار الأربعة بالترتيب: Play → Party → Trailer → Download (كلها بيضاء مثل Play)
        val primaryActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16)
            }
        }

        val darkBtnContentTint = "#16181E".toColorInt()

        // 1) زر PLAY
        btnPlayNowMain = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_btn_play_white_luxury)
            elevation = dpF(3f)
            setPadding(dp(10), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1.05f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                triggerPlayTransitionAndStart()
            }
        }
        val ivPlayIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_play_watch_now)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(darkBtnContentTint))
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        }
        tvPlayNowBtnLabel = TextView(this).apply {
            text = "PLAY"
            setTextColor(darkBtnContentTint)
            textSize = 13.5f
            typeface = fontProductBold
            isSingleLine = true
            maxLines = 1
            setPadding(dp(6), 0, 0, 0)
        }
        btnPlayNowMain.addView(ivPlayIcon)
        btnPlayNowMain.addView(tvPlayNowBtnLabel)

        // 2) زر Party
        btnWatchPartyBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_btn_play_white_luxury)
            elevation = dpF(3f)
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 0.98f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { createWatchTogetherRoom() }
        }
        val ivPartyIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_events)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(darkBtnContentTint))
            layoutParams = LinearLayout.LayoutParams(dp(17), dp(17))
        }
        val tvPartyLabel = TextView(this).apply {
            text = "Party"
            setTextColor(darkBtnContentTint)
            textSize = 13.5f
            typeface = fontProductBold
            isSingleLine = true
            maxLines = 1
            setPadding(dp(6), 0, 0, 0)
        }
        btnWatchPartyBox.addView(ivPartyIcon)
        btnWatchPartyBox.addView(tvPartyLabel)

        // 3) زر Trailer
        btnTrailerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_btn_play_white_luxury)
            elevation = dpF(3f)
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1.02f).apply {
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
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(darkBtnContentTint))
            layoutParams = LinearLayout.LayoutParams(dp(17), dp(17))
        }
        val tvTrailerLabel = TextView(this).apply {
            text = "Trailer"
            setTextColor(darkBtnContentTint)
            textSize = 13.5f
            typeface = fontProductBold
            isSingleLine = true
            maxLines = 1
            setPadding(dp(6), 0, 0, 0)
        }
        btnTrailerBox.addView(ivTrailerIcon)
        btnTrailerBox.addView(tvTrailerLabel)

        // 4) زر Download (أيقونة مكبرة 23dp بدون نص)
        btnDownloadBox = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_btn_play_white_luxury)
            elevation = dpF(3f)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(44))
            setOnClickListener {
                handleDirectMovieDownload()
            }
        }
        val ivDownloadIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_download_cloud)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(darkBtnContentTint))
            layoutParams = FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER)
        }
        btnDownloadBox.addView(ivDownloadIcon)

        AnimUtils.attachPressBounce(btnPlayNowMain)
        AnimUtils.attachPressBounce(btnWatchPartyBox)
        AnimUtils.attachPressBounce(btnTrailerBox)
        AnimUtils.attachPressBounce(btnDownloadBox)

        primaryActionsRow.addView(btnPlayNowMain)
        primaryActionsRow.addView(btnWatchPartyBox)
        primaryActionsRow.addView(btnTrailerBox)
        primaryActionsRow.addView(btnDownloadBox)
        contentSheetColumn.addView(primaryActionsRow)

        // 3. قسم المسلسلات (Seasons & Episodes)
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112))
        }
        seriesContainer.addView(rvEpisodes)
        contentSheetColumn.addView(seriesContainer)

        // 4. القصة (Overview) مختصرة مع "... More"
        tvOverviewText = TextView(this).apply {
            setTextColor("#F1F5F9".toColorInt())
            textSize = 14f
            typeface = fontProductRegular
            setLineSpacing(dpF(3.5f), 1.04f)
            setPadding(0, dp(2), 0, dp(10))
            setOnClickListener { toggleOverviewExpansion() }
        }
        contentSheetColumn.addView(tvOverviewText)

        // 5. سطرا DIRECTOR و WRITTEN BY باللون الأبيض
        crewMetadataContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(6))
        }

        rowDirector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val tvDirectorLabel = TextView(this).apply {
            text = "DIRECTOR"
            setTextColor("#FFFFFF".toColorInt())
            textSize = 10.5f
            typeface = fontProductBold
            layoutParams = LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        tvDirectorValue = TextView(this).apply {
            setTextColor("#FFFFFF".toColorInt())
            textSize = 11.5f
            typeface = fontProductRegular
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowDirector.addView(tvDirectorLabel)
        rowDirector.addView(tvDirectorValue)

        rowWriters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvWritersLabel = TextView(this).apply {
            text = "WRITTEN BY"
            setTextColor("#FFFFFF".toColorInt())
            textSize = 10.5f
            typeface = fontProductBold
            layoutParams = LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        tvWritersValue = TextView(this).apply {
            setTextColor("#FFFFFF".toColorInt())
            textSize = 11.5f
            typeface = fontProductRegular
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowWriters.addView(tvWritersLabel)
        rowWriters.addView(tvWritersValue)

        crewMetadataContainer.addView(rowDirector)
        crewMetadataContainer.addView(rowWriters)
        contentSheetColumn.addView(crewMetadataContainer)

        // 6. قسم CAST
        tvCastHeader = TextView(this).apply {
            text = "CAST"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = fontProductBold
            letterSpacing = 0.04f
            setPadding(0, dp(12), 0, dp(10))
        }
        contentSheetColumn.addView(tvCastHeader)

        rvCast = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ContentDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            clipToPadding = false
            setHasFixedSize(true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(124))
        }
        contentSheetColumn.addView(rvCast)

        // 7. قسم RECOMMENDED
        tvRecommendedHeader = TextView(this).apply {
            text = "RECOMMENDED"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = fontProductBold
            letterSpacing = 0.04f
            setPadding(0, dp(16), 0, dp(10))
        }
        contentSheetColumn.addView(tvRecommendedHeader)

        rvRecommended = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ContentDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            clipToPadding = false
            setHasFixedSize(true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(196))
        }
        contentSheetColumn.addView(rvRecommended)

        // 8. قسم YOU MAY ALSO LIKE
        tvMoreLikeHeader = TextView(this).apply {
            text = "YOU MAY ALSO LIKE"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = fontProductBold
            letterSpacing = 0.04f
            setPadding(0, dp(16), 0, dp(10))
        }
        contentSheetColumn.addView(tvMoreLikeHeader)

        rvMoreLike = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ContentDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            clipToPadding = false
            setHasFixedSize(true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(196))
        }
        contentSheetColumn.addView(rvMoreLike)

        detailsMasterColumn.addView(contentSheetColumn)
        detailsScrollView.addView(detailsMasterColumn)
        rootVerticalLayout.addView(detailsScrollView)
        rootFrameWrapper.addView(rootVerticalLayout)

        heroTopActionsRow = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        btnTopBackHero = ImageView(this).apply {
            setImageResource(R.drawable.ic_back_chevron)
            background = makeRoundedBg("#4D080A10".toColorInt(), 21f, "#36FFFFFF".toColorInt(), 1f)
            setPadding(dp(10), dp(10), dp(11), dp(10))
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.START or Gravity.CENTER_VERTICAL)
            setOnClickListener { handleSmartBackNavigation() }
        }

        btnFavBox = ImageView(this).apply {
            setImageResource(R.drawable.ic_heart_outline)
            background = makeRoundedBg("#4D080A10".toColorInt(), 21f, "#36FFFFFF".toColorInt(), 1f)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.CENTER_VERTICAL)
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
        rootFrameWrapper.addView(heroTopActionsRow)

        buildFullScreenActorPageOverlay()
        rootFrameWrapper.addView(actorPageOverlay)

        detailsScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            fullScreenAmbientDrawable.updateScrollOffset(scrollY.toFloat())
        }

        setContentView(rootFrameWrapper)
    }

    private fun buildFullScreenActorPageOverlay() {
        actorPageOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#0B0D12".toColorInt())
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            visibility = View.GONE
            elevation = dpF(24f)
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = FrameLayout(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val btnActorBack = ImageView(this).apply {
            setImageResource(R.drawable.ic_back_chevron)
            background = makeRoundedBg("#1FFFFFFF".toColorInt(), 20f, "#33FFFFFF".toColorInt(), 1f)
            setPadding(dp(9), dp(9), dp(10), dp(9))
            layoutParams = FrameLayout.LayoutParams(dp(38), dp(38), Gravity.START or Gravity.CENTER_VERTICAL)
            setOnClickListener { handleSmartBackNavigation() }
        }
        topBar.addView(btnActorBack)
        actorPageOverlay.addView(topBar)

        val avatarWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(112)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(2)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor("#18202C".toColorInt())
                setStroke(dp(2), "#E6FFFFFF".toColorInt())
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        ivActorPageAvatar = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        avatarWrap.addView(ivActorPageAvatar)
        actorPageOverlay.addView(avatarWrap)

        tvActorPageName = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 23f
            typeface = fontProductBold
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        actorPageOverlay.addView(tvActorPageName)

        val statsAndSortRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(4), dp(20), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val tabsCenterBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        tvActorMoviesTab = TextView(this).apply {
            text = "Movies • 0"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = fontProductBold
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                currentActorFilterMode = if (currentActorFilterMode == "MOVIES") "ALL" else "MOVIES"
                refreshActorOverlayGrid()
            }
        }

        tvActorSeriesTab = TextView(this).apply {
            text = "Series • 0"
            setTextColor("#94A3B8".toColorInt())
            textSize = 15f
            typeface = fontProductBold
            setPadding(dp(18), dp(6), dp(12), dp(6))
            setOnClickListener {
                currentActorFilterMode = if (currentActorFilterMode == "SERIES") "ALL" else "SERIES"
                refreshActorOverlayGrid()
            }
        }

        tabsCenterBox.addView(tvActorMoviesTab)
        tabsCenterBox.addView(tvActorSeriesTab)

        btnActorSort = ImageView(this).apply {
            setImageDrawable(VectorIcons.createSortBarsIcon(this@ContentDetailActivity, Color.WHITE))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor("#1A202C".toColorInt())
                setStroke(dp(1), "#33FFFFFF".toColorInt())
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            setOnClickListener {
                currentActorSortByYearDesc = !currentActorSortByYearDesc
                Toast.makeText(
                    this@ContentDetailActivity,
                    if (currentActorSortByYearDesc) "Sorted by Release Year" else "Sorted by Rating",
                    Toast.LENGTH_SHORT
                ).show()
                refreshActorOverlayGrid()
            }
        }

        statsAndSortRow.addView(tabsCenterBox)
        statsAndSortRow.addView(btnActorSort)
        actorPageOverlay.addView(statsAndSortRow)

        val divider = View(this).apply {
            setBackgroundColor("#1FFFFFFF".toColorInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }
        actorPageOverlay.addView(divider)

        val gridContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        actorPageSpinner = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf("#E50914".toColorInt())
            layoutParams = FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)
        }
        gridContainer.addView(actorPageSpinner)

        rvActorCreditsGrid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@ContentDetailActivity, 3)
            clipToPadding = false
            setPadding(dp(8), dp(12), dp(8), dp(28))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        gridContainer.addView(rvActorCreditsGrid)
        actorPageOverlay.addView(gridContainer)
    }

    private fun openFlixVisionActorPage(actor: CastMember) {
        currentVod?.let { vod ->
            navigationBackStack.addLast(
                NavHistoryEntry.MovieState(
                    vod = vod,
                    scrollY = detailsScrollView.scrollY
                )
            )
        }

        currentActiveActor = actor
        currentActorFilterMode = "ALL"
        currentActorSortByYearDesc = true
        tvActorPageName.text = actor.name

        if (actor.profileUrl.isNotEmpty()) {
            Glide.with(this)
                .load(actor.profileUrl)
                .circleCrop()
                .placeholder(VectorIcons.createPersonAvatarPlaceholder(this))
                .into(ivActorPageAvatar)
        } else {
            ivActorPageAvatar.setImageDrawable(VectorIcons.createPersonAvatarPlaceholder(this))
        }

        actorPageOverlay.alpha = 0f
        actorPageOverlay.visibility = View.VISIBLE
        actorPageOverlay.animate().alpha(1f).setDuration(200L).start()

        val cached = actorCreditsCache[actor.id]
        if (cached != null) {
            actorPageSpinner.visibility = View.GONE
            currentActorCreditsList = cached
            updateActorTabsCounts(cached)
            refreshActorOverlayGrid()
        } else {
            actorPageSpinner.visibility = View.VISIBLE
            rvActorCreditsGrid.adapter = null
            tvActorMoviesTab.text = "Movies • ..."
            tvActorSeriesTab.text = "Series • ..."
            lifecycleScope.launch {
                val credits = withContext(Dispatchers.IO) {
                    PrimeVodExtractor.fetchActorCredits(actor.id)
                }
                actorCreditsCache[actor.id] = credits
                actorPageSpinner.visibility = View.GONE
                currentActorCreditsList = credits
                updateActorTabsCounts(credits)
                refreshActorOverlayGrid()
            }
        }
    }

    private fun updateActorTabsCounts(credits: List<VodItem>) {
        val mCount = credits.count { !it.isSeries }
        val sCount = credits.count { it.isSeries }
        tvActorMoviesTab.text = "Movies • $mCount"
        tvActorSeriesTab.text = "Series • $sCount"
    }

    private fun refreshActorOverlayGrid(restoreScrollPos: Int = 0) {
        val filtered = when (currentActorFilterMode) {
            "MOVIES" -> currentActorCreditsList.filter { !it.isSeries }
            "SERIES" -> currentActorCreditsList.filter { it.isSeries }
            else -> currentActorCreditsList
        }
        val sorted = if (currentActorSortByYearDesc) {
            filtered.sortedByDescending { it.year.toIntOrNull() ?: 0 }
        } else {
            filtered.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
        }

        tvActorMoviesTab.setTextColor(
            if (currentActorFilterMode == "SERIES") "#94A3B8".toColorInt() else Color.WHITE
        )
        tvActorSeriesTab.setTextColor(
            if (currentActorFilterMode == "SERIES") Color.WHITE else "#94A3B8".toColorInt()
        )

        rvActorCreditsGrid.adapter = ActorCreditsGridAdapter(
            items = sorted,
            boldFont = fontProductBold,
            regularFont = fontProductRegular
        ) { clickedMovie ->
            val actor = currentActiveActor
            if (actor != null) {
                val firstPos = (rvActorCreditsGrid.layoutManager as? GridLayoutManager)
                    ?.findFirstVisibleItemPosition() ?: 0
                navigationBackStack.addLast(
                    NavHistoryEntry.ActorState(
                        actor = actor,
                        credits = currentActorCreditsList,
                        filterMode = currentActorFilterMode,
                        sortByYearDesc = currentActorSortByYearDesc,
                        firstVisibleGridPos = firstPos
                    )
                )
            }
            actorPageOverlay.visibility = View.GONE
            loadVodItemIntoScreen(clickedMovie, resetScroll = true)
        }

        if (restoreScrollPos > 0) {
            rvActorCreditsGrid.scrollToPosition(restoreScrollPos)
        }
    }

    private fun restoreActorPageFromState(state: NavHistoryEntry.ActorState) {
        currentActiveActor = state.actor
        currentActorCreditsList = state.credits
        currentActorFilterMode = state.filterMode
        currentActorSortByYearDesc = state.sortByYearDesc

        tvActorPageName.text = state.actor.name
        if (state.actor.profileUrl.isNotEmpty()) {
            Glide.with(this)
                .load(state.actor.profileUrl)
                .circleCrop()
                .placeholder(VectorIcons.createPersonAvatarPlaceholder(this))
                .into(ivActorPageAvatar)
        } else {
            ivActorPageAvatar.setImageDrawable(VectorIcons.createPersonAvatarPlaceholder(this))
        }
        actorPageSpinner.visibility = View.GONE
        updateActorTabsCounts(state.credits)
        refreshActorOverlayGrid(restoreScrollPos = state.firstVisibleGridPos)
        actorPageOverlay.alpha = 1f
        actorPageOverlay.visibility = View.VISIBLE
    }

    private fun closeActorPageOverlayAndRestoreIfNeeded() {
        actorPageOverlay.animate().alpha(0f).setDuration(160L).withEndAction {
            actorPageOverlay.visibility = View.GONE
        }.start()

        val topEntry = navigationBackStack.lastOrNull()
        if (topEntry is NavHistoryEntry.MovieState && topEntry.vod.id == currentVod?.id) {
            val saved = navigationBackStack.removeLast() as NavHistoryEntry.MovieState
            detailsScrollView.post {
                detailsScrollView.scrollTo(0, saved.scrollY)
            }
        }
    }

    private fun createPlayerBottomActionChip(
        icon: Drawable,
        label: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#26FFFFFF".toColorInt(), 16f, "#40FFFFFF".toColorInt(), 0.8f)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }

            val iv = ImageView(this@ContentDetailActivity).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(6) }
            }
            val tv = TextView(this@ContentDetailActivity).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = fontProductBold
            }
            addView(iv)
            addView(tv)
            setOnClickListener { onClick() }
        }
    }

    private fun renderOverviewWithInlineMore(rawText: String, expanded: Boolean) {
        fullOverviewRawText = rawText.trim()
        val limit = 135
        if (fullOverviewRawText.length <= limit) {
            tvOverviewText.text = fullOverviewRawText
            return
        }

        if (!expanded) {
            val cutIndex = fullOverviewRawText.lastIndexOf(' ', limit).takeIf { it > 60 } ?: limit
            val shortText = fullOverviewRawText.substring(0, cutIndex).trimEnd('.', ',', ' ')
            val ssb = SpannableStringBuilder(shortText)
            val moreTag = "... More"
            val start = ssb.length
            ssb.append(moreTag)
            ssb.setSpan(
                ForegroundColorSpan("#01A3F8".toColorInt()),
                start + 3,
                ssb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ssb.setSpan(
                StyleSpan(Typeface.BOLD),
                start + 3,
                ssb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            tvOverviewText.text = ssb
        } else {
            val ssb = SpannableStringBuilder(fullOverviewRawText)
            val lessTag = "  Less"
            val start = ssb.length
            ssb.append(lessTag)
            ssb.setSpan(
                ForegroundColorSpan("#01A3F8".toColorInt()),
                start,
                ssb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ssb.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                ssb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            tvOverviewText.text = ssb
        }
    }

    private fun toggleOverviewExpansion() {
        if (fullOverviewRawText.length <= 135) return
        isOverviewExpanded = !isOverviewExpanded
        val transition = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(ChangeBounds())
            addTransition(Fade())
            duration = 240L
            interpolator = DecelerateInterpolator()
        }
        TransitionManager.beginDelayedTransition(detailsMasterColumn, transition)
        renderOverviewWithInlineMore(fullOverviewRawText, isOverviewExpanded)
    }

    private fun triggerPlayTransitionAndStart() {
        if (isPlayerModeActive) {
            activateInlinePlayerAndStart()
            return
        }

        playTransitionOverlay.visibility = View.VISIBLE
        playTransitionOverlay.alpha = 0f
        ivPlayTransitionPulse.scaleX = 0.65f
        ivPlayTransitionPulse.scaleY = 0.65f

        playTransitionOverlay.animate()
            .alpha(1f)
            .setDuration(180L)
            .start()

        ivPlayTransitionPulse.animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(220L)
            .setInterpolator(OvershootInterpolator(1.6f))
            .withEndAction {
                playTransitionOverlay.visibility = View.GONE
                activateInlinePlayerAndStart()
            }
            .start()
    }

    private fun activateInlinePlayerAndStart() {
        isPlayerModeActive = true
        fullScreenAmbientDrawable.setPlayerModeActive(true)

        val transition = TransitionSet().apply {
            addTransition(ChangeBounds())
            addTransition(Fade())
            duration = 260L
            interpolator = DecelerateInterpolator()
        }
        TransitionManager.beginDelayedTransition(rootFrameWrapper, transition)

        heroBackdropFixedContainer.visibility = View.GONE
        heroTopActionsRow.visibility = View.GONE
        vHeroTopSpacer.visibility = View.GONE
        playerContainer.visibility = View.VISIBLE
        detailsScrollView.smoothScrollTo(0, 0)

        if (resolvedDirectStreams.isNotEmpty()) {
            loadingContainer.visibility = View.GONE
            updatePlayPauseVectorIcon(true)
            playerEngine.playSources(playerView, resolvedDirectStreams.toList(), 0, pendingResumePositionMs)
        } else {
            loadingContainer.visibility = View.VISIBLE
            val vod = currentVod ?: return
            if (providerTargets.isEmpty()) {
                startStreamExtractionForEpisodeOrMovie(vod, selectedSeason, selectedEpisode, runWebViewNow = true)
            } else {
                loadProviderInHeadlessSniffer(currentProviderIndex)
            }
        }
    }

    private fun switchToPosterShowcaseMode() {
        isPlayerModeActive = false
        playerEngine.pause()
        fullScreenAmbientDrawable.setPlayerModeActive(false)

        val transition = TransitionSet().apply {
            addTransition(ChangeBounds())
            addTransition(Fade())
            duration = 260L
            interpolator = DecelerateInterpolator()
        }
        TransitionManager.beginDelayedTransition(rootFrameWrapper, transition)

        playerContainer.visibility = View.GONE
        vHeroTopSpacer.visibility = View.VISIBLE
        heroBackdropFixedContainer.visibility = View.VISIBLE
        heroTopActionsRow.visibility = View.VISIBLE
        ivHeroBackdrop.alpha = 1f
        detailsScrollView.smoothScrollTo(0, 0)
    }

    private fun seekByDeltaMs(deltaMs: Long, badgeLabel: String) {
        val exo = playerEngine.player ?: return
        val dur = exo.duration
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        exo.seekTo(if (dur > 0) min(target, dur) else target)
        showSeekFeedbackToast(badgeLabel)
    }

    private fun showSeekFeedbackToast(text: String) {
        tvSeekFeedbackBadge.text = text
        tvSeekFeedbackBadge.visibility = View.VISIBLE
        tvSeekFeedbackBadge.alpha = 1f
        tvSeekFeedbackBadge.scaleX = 0.85f
        tvSeekFeedbackBadge.scaleY = 0.85f
        tvSeekFeedbackBadge.animate().cancel()
        tvSeekFeedbackBadge.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150L)
            .withEndAction {
                tvSeekFeedbackBadge.animate()
                    .alpha(0f)
                    .setStartDelay(450L)
                    .setDuration(200L)
                    .withEndAction { tvSeekFeedbackBadge.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    private fun updatePlayPauseVectorIcon(isPlaying: Boolean) {
        btnCenterPlayPause.setImageDrawable(
            VectorIcons.createPlayPauseIcon(this, isPlaying = isPlaying)
        )
    }

    private fun handleDirectMovieDownload() {
        val directUrl = resolvedDirectStreams.firstOrNull()?.url
        if (!directUrl.isNullOrEmpty() && !directUrl.contains(".m3u8", ignoreCase = true)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(directUrl)))
        } else if (!directUrl.isNullOrEmpty()) {
            Toast.makeText(this, "Stream Ready (${btnTopServerHd.text}) — Opening Downloader...", Toast.LENGTH_SHORT).show()
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(directUrl)))
            }
        } else {
            Toast.makeText(this, "Preparing 1080p Direct Link... Tap again in 2 seconds", Toast.LENGTH_SHORT).show()
            val vod = currentVod ?: return
            if (providerTargets.isNotEmpty() && !hasLockedPrimaryStream) {
                loadProviderInHeadlessSniffer(currentProviderIndex)
            } else if (providerTargets.isEmpty()) {
                startStreamExtractionForEpisodeOrMovie(vod, selectedSeason, selectedEpisode, runWebViewNow = true)
            }
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
                if (isPlayerModeActive) {
                    playerEngine.playSources(playerView, resolvedDirectStreams.toList(), 0, pendingResumePositionMs)
                }
            } else {
                resolvedDirectStreams.add(newDirectSource)
                playerEngine.appendBackupSourcesSilently(listOf(newDirectSource))
            }
        }
    }

    private fun startStreamExtractionForEpisodeOrMovie(
        vod: VodItem,
        season: Int = 1,
        episode: Int = 1,
        runWebViewNow: Boolean = false
    ) {
        snifferTimeoutJob?.cancel()
        streamFetchJob?.cancel()
        hasLockedPrimaryStream = false
        hasSkippedIntroForCurrentMedia = false
        btnSkipIntro.visibility = View.GONE
        selectedQualityHeight = null
        playerEngine.pauseAndReleaseSocket()
        resolvedDirectStreams.clear()
        providerTargets = emptyList()

        headlessSnifferWebView.stopLoading()
        headlessSnifferWebView.loadUrl("about:blank")
        headlessSnifferWebView.onPause()
        headlessSnifferWebView.pauseTimers()

        tvElapsed.text = "0:00"
        tvDuration.text = "--:--"
        seekBar.progress = 0
        tvSubtitleOverlay.text = ""
        tvSubtitleOverlay.visibility = View.GONE
        loadingContainer.visibility = if (isPlayerModeActive) View.VISIBLE else View.GONE
        tvLoadingStatus.text = "Connecting to fastest server..."

        streamFetchJob = lifecycleScope.launch {
            val panelServers = withContext(Dispatchers.IO) {
                PrimeVodExtractor.fetchPanelConfiguredServers(
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
            }
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
                } else if (runWebViewNow || isPlayerModeActive) {
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

    private fun loadPosterAndExtractPalette(vod: VodItem) {
        val targetUrl = vod.posterUrl.ifEmpty { vod.backdropUrl }

        colorExtractionAnimator?.cancel()
        fullScreenAmbientDrawable.updateColors(
            leftBright = FLIX_INITIAL_LEFT_COLOR,
            rightDark = FLIX_INITIAL_RIGHT_COLOR,
            extractionFraction = 0f
        )

        if (targetUrl.isEmpty()) return

        val cachedPalette = paletteCache[vod.id]

        Glide.with(this)
            .asBitmap()
            .load(targetUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .dontAnimate()
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    ivHeroBackdrop.setImageBitmap(resource)
                    val extracted = cachedPalette ?: extractFlixVisionPalette(resource).also {
                        paletteCache[vod.id] = it
                    }
                    startCoverColorExtractionAnimation(extracted.first, extracted.second)
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    private fun loadVodItemIntoScreen(
        vod: VodItem,
        resetScroll: Boolean = true,
        targetScrollY: Int = 0
    ) {
        detailsFetchJob?.cancel()
        currentVod = vod
        selectedSeason = 1
        selectedEpisode = 1
        selectedSubtitleId = ""
        subtitleOffsetMs = 0L
        isOverviewExpanded = false
        hasSkippedIntroForCurrentMedia = false
        activeSubtitleCues = emptyList()
        availableSubtitles = emptyList()

        switchToPosterShowcaseMode()

        pendingResumePositionMs = SessionManager.getSavedPositionForVod(this, vod.id)
            .coerceAtLeast(vod.savedPositionMs)
        SessionManager.saveWatchProgress(this, vod, pendingResumePositionMs, vod.totalDurationMs)

        tvPlayNowBtnLabel.text = if (pendingResumePositionMs > 15_000L) "RESUME" else "PLAY"

        tvTopPlayerTitle.text = vod.title
        tvMovieTitle.text = vod.title

        val initialGenres = vod.category.ifEmpty { if (vod.isSeries) "TV Series • Drama" else "Movie • Action" }
        tvMovieGenresLine.text = initialGenres

        val initialPlot = vod.description.ifEmpty { "Loading synopsis..." }
        renderOverviewWithInlineMore(initialPlot, expanded = false)

        updateFavoriteIcon(vod.id)
        loadPosterAndExtractPalette(vod)

        renderSmallLuxuryInfoCards(
            rating = vod.rating,
            year = vod.year,
            runtime = if (vod.isSeries) "1 Season" else "1h 54m",
            ageBadge = if (vod.isSeries) "TV-14" else "PG-13",
            qualityBadge = "FHD"
        )

        val cachedDetails = detailsCache[vod.id]
        if (cachedDetails != null) {
            bindMovieFullDetailsToUi(vod, cachedDetails)
        } else {
            crewMetadataContainer.visibility = View.GONE
        }

        if (resetScroll) {
            detailsScrollView.post {
                detailsScrollView.scrollTo(0, 0)
                fullScreenAmbientDrawable.updateScrollOffset(0f)
            }
        } else if (targetScrollY > 0) {
            detailsScrollView.post {
                detailsScrollView.scrollTo(0, targetScrollY)
                fullScreenAmbientDrawable.updateScrollOffset(targetScrollY.toFloat())
            }
        }

        detailsFetchJob = lifecycleScope.launch {
            val details = cachedDetails ?: withContext(Dispatchers.IO) {
                OttRepository.fetchMovieOrSeriesFullDetails(vod)
            }
            detailsCache[vod.id] = details
            fullDetails = details
            if (cachedDetails == null) {
                bindMovieFullDetailsToUi(vod, details)
            }

            startStreamExtractionForEpisodeOrMovie(vod, 1, 1, runWebViewNow = false)

            withContext(Dispatchers.IO) {
                val subs = OttRepository.fetchRealSubtitlesForMovie(
                    vod.id,
                    details.imdbId,
                    vod.isSeries,
                    selectedSeason,
                    selectedEpisode
                )
                if (subs.isNotEmpty()) {
                    availableSubtitles = subs
                    autoSelectPreferredSubtitle()
                }
            }
        }
    }

    private fun bindMovieFullDetailsToUi(vod: VodItem, details: MovieFullDetails) {
        fullDetails = details
        val plot = details.overview.ifEmpty { vod.description }
        renderOverviewWithInlineMore(plot, isOverviewExpanded)

        val genresFormatted = if (details.genres.isNotEmpty()) {
            details.genres.joinToString(" • ")
        } else {
            vod.category.ifEmpty { if (vod.isSeries) "TV Series • Drama" else "Movie • Action" }
        }
        tvMovieGenresLine.text = genresFormatted

        if (details.cast.isNotEmpty()) {
            val directorName = details.cast.firstOrNull()?.name ?: "N/A"
            val writersNames = details.cast.drop(1).take(3).joinToString(", ") { it.name }.ifEmpty { directorName }
            tvDirectorValue.text = directorName
            tvWritersValue.text = writersNames
            crewMetadataContainer.visibility = View.VISIBLE
        } else {
            crewMetadataContainer.visibility = View.GONE
        }

        renderSmallLuxuryInfoCards(
            rating = details.rating.ifEmpty { vod.rating },
            year = details.year.ifEmpty { vod.year },
            runtime = if (vod.isSeries) "${details.numberOfSeasons} Seasons" else details.runtimeText,
            ageBadge = if (vod.isSeries) "TV-14" else "PG-13",
            qualityBadge = "FHD"
        )

        if (vod.isSeries) {
            seriesContainer.visibility = View.VISIBLE
            renderSeasonsAndEpisodes(vod.id, details.numberOfSeasons)
        } else {
            seriesContainer.visibility = View.GONE
        }

        if (details.cast.isNotEmpty()) {
            tvCastHeader.visibility = View.VISIBLE
            rvCast.visibility = View.VISIBLE
            rvCast.adapter = CastHorizontalAdapter(
                items = details.cast,
                boldFont = fontProductBold,
                regularFont = fontProductRegular
            ) { actor ->
                openFlixVisionActorPage(actor)
            }
        } else {
            tvCastHeader.visibility = View.GONE
            rvCast.visibility = View.GONE
        }

        rvRecommended.adapter = VodGridAdapter(details.recommended, isGrid = false) { clickedVod ->
            currentVod?.let { cur ->
                navigationBackStack.addLast(NavHistoryEntry.MovieState(cur, detailsScrollView.scrollY))
            }
            loadVodItemIntoScreen(clickedVod, resetScroll = true)
        }
        rvMoreLike.adapter = VodGridAdapter(details.similar, isGrid = false) { clickedVod ->
            currentVod?.let { cur ->
                navigationBackStack.addLast(NavHistoryEntry.MovieState(cur, detailsScrollView.scrollY))
            }
            loadVodItemIntoScreen(clickedVod, resetScroll = true)
        }
    }

    private fun renderSmallLuxuryInfoCards(
        rating: String,
        year: String,
        runtime: String,
        ageBadge: String,
        qualityBadge: String
    ) {
        metaPillsRow.removeAllViews()

        if (rating.isNotBlank()) {
            val ratingCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = makeRoundedBg("#2EFFC107".toColorInt(), 18f, "#85FFC107".toColorInt(), 0.9f)
                setPadding(dp(9), dp(3), dp(10), dp(3))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
            }
            val ivStar = ImageView(this).apply {
                setImageResource(R.drawable.ic_star_gold)
                layoutParams = LinearLayout.LayoutParams(dp(11), dp(11)).apply {
                    marginEnd = dp(4)
                }
            }
            val tvRatingVal = TextView(this).apply {
                text = rating
                setTextColor("#FFD700".toColorInt())
                textSize = 11f
                typeface = fontProductBold
            }
            ratingCard.addView(ivStar)
            ratingCard.addView(tvRatingVal)
            metaPillsRow.addView(ratingCard)
        }

        val extraCards = listOf(
            ageBadge to true,
            year to false,
            runtime to false,
            qualityBadge to false
        )

        extraCards.forEach { (label, isBlueAccent) ->
            if (label.isNotBlank()) {
                val pill = TextView(this).apply {
                    text = label
                    setTextColor(if (isBlueAccent) "#38BDF8".toColorInt() else "#F1F5F9".toColorInt())
                    textSize = 11f
                    typeface = fontProductBold
                    isSingleLine = true
                    background = makeRoundedBg(
                        fillColor = if (isBlueAccent) "#1C0284C7".toColorInt() else "#1FFFFFFF".toColorInt(),
                        radiusDp = 18f,
                        strokeColor = if (isBlueAccent) "#8038BDF8".toColorInt() else "#38FFFFFF".toColorInt(),
                        strokeWidthDp = 0.9f
                    )
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(6) }
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
                    if (i + 1 == seasonNum) "#E50914".toColorInt() else "#22FFFFFF".toColorInt(),
                    16f,
                    if (i + 1 == seasonNum) "#FF6B72".toColorInt() else "#38FFFFFF".toColorInt()
                )
            }
            lifecycleScope.launch {
                val episodes = withContext(Dispatchers.IO) {
                    PrimeVodExtractor.fetchSeasonEpisodes(seriesId, seasonNum)
                }
                rvEpisodes.adapter = EpisodesHorizontalAdapter(
                    items = episodes,
                    selectedEpNum = selectedEpisode,
                    boldFont = fontProductBold
                ) { ep ->
                    selectedEpisode = ep.episodeNumber
                    val vod = currentVod ?: return@EpisodesHorizontalAdapter
                    val epTitle = "${vod.title}  •  S${seasonNum}:E${ep.episodeNumber}"
                    tvTopPlayerTitle.text = epTitle
                    activateInlinePlayerAndStart()
                    startStreamExtractionForEpisodeOrMovie(vod, seasonNum, ep.episodeNumber, runWebViewNow = true)
                    lifecycleScope.launch(Dispatchers.IO) {
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
                textSize = 12f
                typeface = fontProductBold
                setTextColor(Color.WHITE)
                background = makeRoundedBg(
                    if (s == selectedSeason) "#E50914".toColorInt() else "#22FFFFFF".toColorInt(),
                    16f,
                    if (s == selectedSeason) "#FF6B72".toColorInt() else "#38FFFFFF".toColorInt()
                )
                setPadding(dp(14), dp(6), dp(14), dp(6))
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

    private fun startPlayerProgressAndSubtitlesLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(200)
                val p = playerEngine.player ?: continue
                if (p.isPlaying) {
                    loadingContainer.visibility = View.GONE
                    updatePlayPauseVectorIcon(true)
                }
                val pos = p.currentPosition.coerceAtLeast(0L)
                val dur = p.duration

                if (!isUserSeeking && dur > 0) {
                    seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                    tvElapsed.text = formatTimeMs(pos)
                    tvDuration.text = formatTimeMs(dur)

                    val shouldShowSkipIntro = isPlayerModeActive &&
                            !hasSkippedIntroForCurrentMedia &&
                            dur > 300_000L &&
                            pos in INTRO_START_MS..INTRO_END_MS

                    if (shouldShowSkipIntro && btnSkipIntro.visibility != View.VISIBLE) {
                        btnSkipIntro.alpha = 0f
                        btnSkipIntro.visibility = View.VISIBLE
                        btnSkipIntro.animate().alpha(1f).setDuration(250L).start()
                    } else if (!shouldShowSkipIntro && btnSkipIntro.visibility == View.VISIBLE) {
                        btnSkipIntro.visibility = View.GONE
                    }

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
            background = makeRoundedBg("#16181F".toColorInt(), 14f, "#2A2E3B".toColorInt())
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        if (providerTargets.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No servers configured in panel"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = fontProductRegular
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
                        text = "${server.label} • $shortQ"
                        textSize = 14f
                        typeface = fontProductBold
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
                    text = "${server.label} • $currentDetectedBadge"
                    textSize = 14f
                    typeface = fontProductBold
                    setTextColor("#E50914".toColorInt())
                    setPadding(dp(12), dp(11), dp(12), dp(11))
                    setOnClickListener { dialog.dismiss() }
                }
                card.addView(row)
            } else {
                val row = TextView(this).apply {
                    text = server.label
                    textSize = 14f
                    typeface = fontProductBold
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
            1.0f to "1x",
            1.25f to "1.25x",
            1.5f to "1.5x",
            2.0f to "2x"
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#16181F".toColorInt(), 12f, "#2A2E3B".toColorInt())
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        speeds.forEach { (spd, label) ->
            val isSel = spd == currentPlaybackSpeed
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                typeface = fontProductBold
                setTextColor(if (isSel) "#E50914".toColorInt() else Color.WHITE)
                setPadding(dp(16), dp(10), dp(28), dp(10))
                setOnClickListener {
                    currentPlaybackSpeed = spd
                    playerEngine.player?.playbackParameters = PlaybackParameters(spd)
                    tvBottomSpeedLabel.text = "Speed: $label"
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
            background = makeRoundedBg("#121318".toColorInt(), 16f, "#282B36".toColorInt())
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
            textSize = 16f
            typeface = fontProductBold
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val refreshBtn = TextView(this).apply {
            text = "Refresh"
            setTextColor("#E50914".toColorInt())
            textSize = 12f
            typeface = fontProductBold
            background = makeRoundedBg("#2A1215".toColorInt(), 8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        headerRow.addView(titleTv)
        headerRow.addView(refreshBtn)
        root.addView(headerRow)

        val syncBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg("#191C26".toColorInt(), 10f, "#2A2E3D".toColorInt())
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
            typeface = fontProductBold
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnMinusHalf = TextView(this).apply {
            text = "-0.5s"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = fontProductBold
            background = makeRoundedBg("#262B38".toColorInt(), 6f)
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
            typeface = fontProductBold
            background = makeRoundedBg("#E50914".toColorInt(), 6f)
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
            typeface = fontProductBold
            background = makeRoundedBg("#262B38".toColorInt(), 6f)
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
                typeface = fontProductBold
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
                    background = if (isSelected) makeRoundedBg("#261217".toColorInt(), 10f, "#E50914".toColorInt()) else null
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
                    typeface = fontProductBold
                }
                val badgeText = if (isWebDl) "100% WEB-DL Synced" else "OpenSubtitles"
                val relTv = TextView(this).apply {
                    text = "${track.releaseName} • $badgeText"
                    setTextColor(if (isWebDl) "#34D399".toColorInt() else "#94A3B8".toColorInt())
                    textSize = 11f
                    typeface = fontProductRegular
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
                availableSubtitles = withContext(Dispatchers.IO) {
                    OttRepository.fetchRealSubtitlesForMovie(
                        vod.id,
                        fullDetails?.imdbId.orEmpty(),
                        vod.isSeries,
                        selectedSeason,
                        selectedEpisode
                    )
                }
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
                typeface = fontProductBold
                setTextColor(Color.WHITE)
                background = makeRoundedBg(if (selectedLangFilter == code) "#E50914".toColorInt() else "#1C202B".toColorInt(), 16f)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    selectedLangFilter = code
                    for (i in 0 until filterRow.childCount) {
                        (filterRow.getChildAt(i) as? TextView)?.background = makeRoundedBg("#1C202B".toColorInt(), 16f)
                    }
                    background = makeRoundedBg("#E50914".toColorInt(), 16f)
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
        btnInlineFullscreen.setImageDrawable(
            VectorIcons.createFullscreenIcon(this, isFullscreen = enterFullscreen)
        )
        if (enterFullscreen) {
            isPlayerModeActive = true
            heroBackdropFixedContainer.visibility = View.GONE
            heroTopActionsRow.visibility = View.GONE
            vHeroTopSpacer.visibility = View.GONE
            playerContainer.visibility = View.VISIBLE
            detailsScrollView.visibility = View.GONE
            btnTopLock.visibility = View.VISIBLE
            fullscreenBottomActionsRow.visibility = View.VISIBLE

            playerContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, rootFrameWrapper).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, rootFrameWrapper).show(WindowInsetsCompat.Type.systemBars())

            playerContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(210)
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
        val resId = if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        btnFavBox.setImageResource(resId)
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
        detailsFetchJob?.cancel()
        streamFetchJob?.cancel()
        snifferTimeoutJob?.cancel()
        progressJob?.cancel()
        colorExtractionAnimator?.cancel()
        controlsOverlay.removeCallbacks(autoHideRunnable)
        headlessSnifferWebView.destroy()
        playerEngine.release()
        super.onDestroy()
    }
}

/**
 * محرك التدرج الناعم المطابق لـ FlixVision (صورة 2 - The Invite):
 * - يبقي وسط الكوفر صافياً تماماً بدون أي خط أفقي أو ضبابية.
 * - يبدأ التدرج السفلي بنعومة عند مستوى اسم الفيلم وينتهي بدمج كامل تحت اسم الفيلم وسطر التصنيف.
 * - يضع تدرجاً خفيفاً وناعماً على الحافتين اليسرى واليمنى للكوفر (اليمين داكن واليسار بلون الكوفر).
 * - عند التمرير للأعلى (`scrollY > 0`)، يصعد نفس التدرج بسلاسة ليغطي الكوفر تدريجياً، وعند النزول يعود لحالته الأصلية.
 */
class FlixVisionFullScreenAmbientDrawable(
    private val coverHeightPx: Float,
    private val bottomFadeStartAtRestPx: Float,
    private val fullOpaqueYAtRestPx: Float,
    private val scrollToHideDistancePx: Float
) : Drawable() {

    private val mainThemePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sideEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var leftBrightColor: Int = "#233242".toColorInt()
    private var rightDarkColor: Int = "#000000".toColorInt()
    private var extractionFraction: Float = 0f
    private var currentScrollY: Float = 0f
    private var isPlayerMode: Boolean = false

    fun updateColors(
        leftBright: Int,
        rightDark: Int,
        extractionFraction: Float
    ) {
        this.leftBrightColor = leftBright
        this.rightDarkColor = rightDark
        this.extractionFraction = extractionFraction.coerceIn(0f, 1f)
        invalidateSelf()
    }

    fun updateScrollOffset(scrollY: Float) {
        if (abs(currentScrollY - scrollY) > 0.5f) {
            currentScrollY = scrollY.coerceAtLeast(0f)
            invalidateSelf()
        }
    }

    fun setPlayerModeActive(active: Boolean) {
        if (isPlayerMode != active) {
            isPlayerMode = active
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat().coerceAtLeast(1f)
        val h = b.height().toFloat().coerceAtLeast(1f)

        val scrollFraction = if (isPlayerMode) {
            1f
        } else {
            (currentScrollY / scrollToHideDistancePx).coerceIn(0f, 1f)
        }

        val midBlend1 = ColorUtils.blendARGB(leftBrightColor, rightDarkColor, 0.28f)
        val midBlend2 = ColorUtils.blendARGB(leftBrightColor, rightDarkColor, 0.62f)
        val midBlend3 = ColorUtils.blendARGB(leftBrightColor, rightDarkColor, 0.86f)

        // 1. التدرج الأفقي السلس من اليسار (فاتح بلون الكوفر) إلى اليمين (داكن/أسود)
        val horizontalThemeShader = LinearGradient(
            0f,
            0f,
            w,
            0f,
            intArrayOf(
                leftBrightColor,
                midBlend1,
                midBlend2,
                midBlend3,
                rightDarkColor
            ),
            floatArrayOf(0f, 0.28f, 0.56f, 0.80f, 1f),
            Shader.TileMode.CLAMP
        )

        // 2. قناع الشفافية العمودي السلس (في وضع السكون يبقي وسط الكوفر صافياً تماماً، ويبدأ التدرج عند اسم الفيلم)
        val dynamicFadeStart = bottomFadeStartAtRestPx * (1f - scrollFraction)
        val dynamicOpaqueY = (fullOpaqueYAtRestPx * (1f - scrollFraction * 0.90f))
            .coerceAtLeast(dynamicFadeStart + 16f)

        val topCoverAlpha = (255f * scrollFraction).toInt().coerceIn(0, 255)
        val fadeStep1Alpha = (255f * (0.18f + 0.82f * scrollFraction)).toInt().coerceIn(0, 255)
        val fadeStep2Alpha = (255f * (0.52f + 0.48f * scrollFraction)).toInt().coerceIn(0, 255)
        val fadeStep3Alpha = (255f * (0.84f + 0.16f * scrollFraction)).toInt().coerceIn(0, 255)

        val s0 = (dynamicFadeStart / h).coerceIn(0f, 0.90f)
        val sEnd = (dynamicOpaqueY / h).coerceIn(s0 + 0.03f, 0.98f)
        val span = sEnd - s0

        val verticalAlphaMaskShader = LinearGradient(
            0f,
            0f,
            0f,
            h,
            intArrayOf(
                Color.argb(topCoverAlpha, 255, 255, 255),
                Color.argb(topCoverAlpha, 255, 255, 255),
                Color.argb(fadeStep1Alpha, 255, 255, 255),
                Color.argb(fadeStep2Alpha, 255, 255, 255),
                Color.argb(fadeStep3Alpha, 255, 255, 255),
                Color.argb(255, 255, 255, 255),
                Color.argb(255, 255, 255, 255)
            ),
            floatArrayOf(
                0f,
                s0,
                s0 + span * 0.28f,
                s0 + span * 0.58f,
                s0 + span * 0.82f,
                sEnd,
                1f
            ),
            Shader.TileMode.CLAMP
        )

        mainThemePaint.shader = ComposeShader(
            horizontalThemeShader,
            verticalAlphaMaskShader,
            PorterDuff.Mode.DST_IN
        )
        canvas.drawRect(0f, 0f, w, h, mainThemePaint)

        // 3. تدرج خفيف وناعم على الحافتين اليسرى واليمنى فوق الكوفر (كما في لقطة The Invite من FlixVision)
        if (!isPlayerMode && scrollFraction < 0.99f) {
            val coverBottom = min(h, coverHeightPx)
            val sideGrowth = 1f + (scrollFraction * 1.1f)

            // الحافة اليسرى (لون الكوفر الفاتح الخفيف على طرف الصورة الأيسر)
            val leftEdgeW = w * (0.16f * sideGrowth).coerceAtMost(0.45f)
            val leftAlpha = ((95 + 65 * extractionFraction) * (1f - scrollFraction * 0.5f)).toInt().coerceIn(0, 255)
            sideEdgePaint.shader = LinearGradient(
                0f,
                0f,
                leftEdgeW,
                0f,
                intArrayOf(
                    ColorUtils.setAlphaComponent(leftBrightColor, leftAlpha),
                    ColorUtils.setAlphaComponent(leftBrightColor, (leftAlpha * 0.35f).toInt()),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, leftEdgeW, coverBottom, sideEdgePaint)

            // الحافة اليمنى (تدرج داكن/أسود خفيف على طرف الصورة الأيمن)
            val rightEdgeW = w * (0.22f * sideGrowth).coerceAtMost(0.55f)
            val rightAlpha = (155 * (1f - scrollFraction * 0.4f)).toInt().coerceIn(0, 255)
            sideEdgePaint.shader = LinearGradient(
                w,
                0f,
                w - rightEdgeW,
                0f,
                intArrayOf(
                    ColorUtils.setAlphaComponent(rightDarkColor, rightAlpha),
                    ColorUtils.setAlphaComponent(rightDarkColor, (rightAlpha * 0.42f).toInt()),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(w - rightEdgeW, 0f, w, coverBottom, sideEdgePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        mainThemePaint.alpha = alpha
        sideEdgePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        mainThemePaint.colorFilter = colorFilter
        sideEdgePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

object VectorIcons {

    fun createPersonAvatarPlaceholder(context: Context): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (78 * d).toInt()
        return object : Drawable() {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = "#1F2937".toColorInt()
                style = Paint.Style.FILL
            }
            private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = "#64748B".toColorInt()
                style = Paint.Style.FILL
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val cx = w / 2f
                val cy = h / 2f
                val r = min(w, h) / 2f
                canvas.drawCircle(cx, cy, r, bgPaint)
                canvas.drawCircle(cx, h * 0.38f, r * 0.30f, fgPaint)
                val shouldersRect = RectF(w * 0.20f, h * 0.58f, w * 0.80f, h * 1.05f)
                canvas.drawArc(shouldersRect, 180f, 180f, true, fgPaint)
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) {
                bgPaint.alpha = alpha
                fgPaint.alpha = alpha
            }
            override fun setColorFilter(colorFilter: ColorFilter?) {
                bgPaint.colorFilter = colorFilter
                fgPaint.colorFilter = colorFilter
            }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createPlayPauseIcon(context: Context, isPlaying: Boolean): Drawable {
        val sizePx = (28 * context.resources.displayMetrics.density).toInt()
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }

            override fun draw(canvas: Canvas) {
                val b = bounds
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                if (isPlaying) {
                    val barW = w * 0.22f
                    val barH = h * 0.68f
                    val top = (h - barH) / 2f
                    val r = barW * 0.35f
                    val left1 = w * 0.22f
                    val left2 = w * 0.56f
                    canvas.drawRoundRect(RectF(left1, top, left1 + barW, top + barH), r, r, paint)
                    canvas.drawRoundRect(RectF(left2, top, left2 + barW, top + barH), r, r, paint)
                } else {
                    val path = Path().apply {
                        moveTo(w * 0.26f, h * 0.16f)
                        lineTo(w * 0.84f, h * 0.50f)
                        lineTo(w * 0.26f, h * 0.84f)
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createSeek15Icon(context: Context, isForward: Boolean): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (28 * d).toInt()
        return object : Drawable() {
            private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2.0f * d
                strokeCap = Paint.Cap.ROUND
            }
            private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 8.8f * d
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            override fun draw(canvas: Canvas) {
                val b = bounds
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                val pad = w * 0.12f
                val rect = RectF(pad, pad, w - pad, h - pad)

                if (isForward) {
                    canvas.drawArc(rect, -80f, 290f, false, arcPaint)
                    val tipX = w * 0.66f
                    val tipY = pad
                    val arrow = Path().apply {
                        moveTo(w * 0.48f, tipY - w * 0.11f)
                        lineTo(tipX, tipY)
                        lineTo(w * 0.48f, tipY + w * 0.11f)
                        close()
                    }
                    canvas.drawPath(arrow, arrowPaint)
                } else {
                    canvas.drawArc(rect, -100f, -290f, false, arcPaint)
                    val tipX = w * 0.34f
                    val tipY = pad
                    val arrow = Path().apply {
                        moveTo(w * 0.52f, tipY - w * 0.11f)
                        lineTo(tipX, tipY)
                        lineTo(w * 0.52f, tipY + w * 0.11f)
                        close()
                    }
                    canvas.drawPath(arrow, arrowPaint)
                }

                val textY = (h / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f) + (0.5f * d)
                canvas.drawText("15", w / 2f, textY, textPaint)
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) {
                arcPaint.alpha = alpha
                arrowPaint.alpha = alpha
                textPaint.alpha = alpha
            }
            override fun setColorFilter(colorFilter: ColorFilter?) {
                arcPaint.colorFilter = colorFilter
                arrowPaint.colorFilter = colorFilter
                textPaint.colorFilter = colorFilter
            }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createSkipNextIcon(context: Context, tintColor: Int): Drawable {
        val sizePx = (18 * context.resources.displayMetrics.density).toInt()
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.FILL
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val path = Path().apply {
                    moveTo(w * 0.15f, h * 0.18f)
                    lineTo(w * 0.65f, h * 0.50f)
                    lineTo(w * 0.15f, h * 0.82f)
                    close()
                }
                canvas.drawPath(path, paint)
                canvas.drawRoundRect(
                    RectF(w * 0.72f, h * 0.18f, w * 0.86f, h * 0.82f),
                    w * 0.04f,
                    w * 0.04f,
                    paint
                )
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createLockIcon(context: Context, tintColor: Int, locked: Boolean): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (20 * d).toInt()
        return object : Drawable() {
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = 1.8f * d
                strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val bodyRect = RectF(w * 0.22f, h * 0.45f, w * 0.78f, h * 0.86f)
                canvas.drawRoundRect(bodyRect, w * 0.10f, w * 0.10f, strokePaint)
                val shackleRect = RectF(w * 0.32f, h * 0.16f, w * 0.68f, h * 0.55f)
                canvas.drawArc(shackleRect, 180f, if (locked) 180f else 135f, false, strokePaint)
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) { strokePaint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { strokePaint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createSubtitlesCcIcon(context: Context, tintColor: Int): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (20 * d).toInt()
        return object : Drawable() {
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * d
            }
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                textSize = 7.8f * d
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val rect = RectF(w * 0.10f, h * 0.18f, w * 0.90f, h * 0.82f)
                canvas.drawRoundRect(rect, w * 0.14f, w * 0.14f, strokePaint)
                val ty = (h / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
                canvas.drawText("CC", w / 2f, ty, textPaint)
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) {
                strokePaint.alpha = alpha
                textPaint.alpha = alpha
            }
            override fun setColorFilter(colorFilter: ColorFilter?) {
                strokePaint.colorFilter = colorFilter
                textPaint.colorFilter = colorFilter
            }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createAspectRatioIcon(context: Context, tintColor: Int): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (20 * d).toInt()
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = 1.7f * d
                strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                canvas.drawRoundRect(RectF(w * 0.10f, h * 0.18f, w * 0.90f, h * 0.82f), w * 0.12f, w * 0.12f, paint)
                canvas.drawLine(w * 0.24f, h * 0.34f, w * 0.42f, h * 0.34f, paint)
                canvas.drawLine(w * 0.24f, h * 0.34f, w * 0.24f, h * 0.52f, paint)
                canvas.drawLine(w * 0.76f, h * 0.66f, w * 0.58f, h * 0.66f, paint)
                canvas.drawLine(w * 0.76f, h * 0.66f, w * 0.76f, h * 0.48f, paint)
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createFullscreenIcon(context: Context, isFullscreen: Boolean): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (20 * d).toInt()
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 1.8f * d
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val p = w * 0.16f
                val l = w * 0.36f
                if (!isFullscreen) {
                    canvas.drawLine(p, p, l, p, paint)
                    canvas.drawLine(p, p, p, l, paint)
                    canvas.drawLine(w - p, p, w - l, p, paint)
                    canvas.drawLine(w - p, p, w - p, l, paint)
                    canvas.drawLine(p, h - p, l, h - p, paint)
                    canvas.drawLine(p, h - p, p, h - l, paint)
                    canvas.drawLine(w - p, h - p, w - l, h - p, paint)
                    canvas.drawLine(w - p, h - p, w - p, h - l, paint)
                } else {
                    canvas.drawLine(p, l, l, l, paint)
                    canvas.drawLine(l, p, l, l, paint)
                    canvas.drawLine(w - p, l, w - l, l, paint)
                    canvas.drawLine(w - l, p, w - l, l, paint)
                    canvas.drawLine(p, h - l, l, h - l, paint)
                    canvas.drawLine(l, h - p, l, h - l, paint)
                    canvas.drawLine(w - p, h - l, w - l, h - l, paint)
                    canvas.drawLine(w - l, h - p, w - l, h - l, paint)
                }
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createSortBarsIcon(context: Context, tintColor: Int): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (20 * d).toInt()
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = 2.0f * d
                strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                canvas.drawLine(w * 0.18f, h * 0.30f, w * 0.82f, h * 0.30f, paint)
                canvas.drawLine(w * 0.18f, h * 0.50f, w * 0.62f, h * 0.50f, paint)
                canvas.drawLine(w * 0.18f, h * 0.70f, w * 0.42f, h * 0.70f, paint)
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }
}

class EpisodesHorizontalAdapter(
    private val items: List<SeriesEpisode>,
    private var selectedEpNum: Int,
    private val boldFont: Typeface,
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
            typeface = boldFont
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

class CastHorizontalAdapter(
    private val items: List<CastMember>,
    private val boldFont: Typeface,
    private val regularFont: Typeface,
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

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.MarginLayoutParams((96 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (12 * d).toInt()
            }
        }

        val avatarFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((78 * d).toInt(), (78 * d).toInt())
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
            typeface = boldFont
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, (7 * d).toInt(), 0, 0)
        }

        val tvRole = TextView(ctx).apply {
            setTextColor("#CBD5E1".toColorInt())
            textSize = 10.5f
            typeface = regularFont
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, (1 * d).toInt(), 0, 0)
        }

        root.addView(avatarFrame)
        root.addView(tvName)
        root.addView(tvRole)
        AnimUtils.attachPressBounce(root)
        return VH(root, avatarFrame, iv, tvName, tvRole)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val ctx = holder.itemView.context
        val placeholderDrawable = VectorIcons.createPersonAvatarPlaceholder(ctx)
        holder.tvName.text = c.name
        holder.tvRole.text = c.character
        if (c.profileUrl.isNotEmpty()) {
            Glide.with(ctx)
                .load(c.profileUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .circleCrop()
                .placeholder(placeholderDrawable)
                .error(placeholderDrawable)
                .into(holder.ivPhoto)
        } else {
            holder.ivPhoto.setImageDrawable(placeholderDrawable)
        }
        holder.root.setOnClickListener { onActorClick(c) }
    }
}

class ActorCreditsGridAdapter(
    private val items: List<VodItem>,
    private val boldFont: Typeface,
    private val regularFont: Typeface,
    private val onVodClick: (VodItem) -> Unit
) : RecyclerView.Adapter<ActorCreditsGridAdapter.VH>() {

    class VH(
        val root: LinearLayout,
        val ivPoster: ImageView,
        val tvTitle: TextView,
        val tvYear: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((5 * d).toInt(), (6 * d).toInt(), (5 * d).toInt(), (10 * d).toInt())
            }
        }

        val card = CardView(ctx).apply {
            radius = 10f * d
            cardElevation = 4f * d
            setCardBackgroundColor("#141720".toColorInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (158 * d).toInt()
            )
        }

        val ivPoster = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        card.addView(ivPoster)

        val tvTitle = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = boldFont
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding((2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt(), 0)
        }

        val tvYear = TextView(ctx).apply {
            setTextColor("#94A3B8".toColorInt())
            textSize = 11f
            typeface = regularFont
            maxLines = 1
            gravity = Gravity.CENTER
            setPadding(0, (1 * d).toInt(), 0, 0)
        }

        root.addView(card)
        root.addView(tvTitle)
        root.addView(tvYear)
        AnimUtils.attachPressBounce(root)
        return VH(root, ivPoster, tvTitle, tvYear)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvYear.text = item.year.ifEmpty { "2025" }
        val img = item.posterUrl.ifEmpty { item.backdropUrl }
        if (img.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(img)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(holder.ivPoster)
        }
        holder.root.setOnClickListener { onVodClick(item) }
    }
}