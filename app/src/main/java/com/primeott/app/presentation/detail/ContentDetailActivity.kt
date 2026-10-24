package com.primeott.app.presentation.detail

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
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
import com.primeott.app.domain.model.SubtitleTrack
import com.primeott.app.domain.model.VodItem
import com.primeott.app.presentation.common.AnimUtils
import com.primeott.app.presentation.player.PrimePlayerEngine
import com.primeott.app.presentation.sections.VodGridAdapter
import com.primeott.app.presentation.watch.WatchRoomActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@OptIn(UnstableApi::class)
class ContentDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VOD = "extra_vod_item"
        private const val SNIFFER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private const val TMDB_API_KEY = "8476a7ab80ad76f0936744df0430e67c"

        private val FLIX_INITIAL_LEFT_COLOR = "#233242".toColorInt()
        private val FLIX_INITIAL_RIGHT_COLOR = "#000000".toColorInt()

        private val detailsCache = ConcurrentHashMap<String, MovieFullDetails>()
        private val paletteCache = ConcurrentHashMap<String, Pair<Int, Int>>()
        private val distinctCoverCache = ConcurrentHashMap<String, String>()
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
    private lateinit var moviePlayerController: MovieInlinePlayerController

    private var currentVod: VodItem? = null
    private var fullDetails: MovieFullDetails? = null

    private val resolvedDirectStreams = mutableListOf<StreamSource>()
    private var providerTargets: List<StreamSource> = emptyList()
    private var currentProviderIndex: Int = 0
    private var hasLockedPrimaryStream: Boolean = false
    private var isPlayerModeActive: Boolean = false

    private var selectedSeason: Int = 1
    private var selectedEpisode: Int = 1

    private var isOverviewExpanded: Boolean = false
    private var fullOverviewRawText: String = ""

    private var snifferTimeoutJob: Job? = null
    private var detailsFetchJob: Job? = null
    private var streamFetchJob: Job? = null
    private var coverResolveJob: Job? = null

    private var currentLeftBrightColor: Int = FLIX_INITIAL_LEFT_COLOR
    private var currentRightDarkColor: Int = FLIX_INITIAL_RIGHT_COLOR
    private var colorExtractionAnimator: ValueAnimator? = null
    private lateinit var fullScreenAmbientDrawable: FlixVisionFullScreenAmbientDrawable

    private lateinit var fontProductBold: Typeface
    private lateinit var fontProductRegular: Typeface

    private lateinit var rootFrameWrapper: FrameLayout
    private lateinit var heroBackdropFixedContainer: FrameLayout
    private lateinit var ivHeroBackdrop: ImageView
    private lateinit var vFullDynamicAmbientBg: View

    private lateinit var rootVerticalLayout: LinearLayout
    private lateinit var heroTopActionsRow: FrameLayout
    private lateinit var btnTopBackHero: ImageView
    private lateinit var btnFavBox: ImageView
    private lateinit var headlessSnifferWebView: WebView

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

    private var pendingResumePositionMs: Long = 0L

    private val localSubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            moviePlayerController.loadExternalSubtitleFromUri(uri)
        }
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

        setContentView(R.layout.activity_content_detail)

        moviePlayerController = MovieInlinePlayerController(
            activity = this,
            scope = lifecycleScope,
            fontBold = fontProductBold,
            fontRegular = fontProductRegular,
            onMinimizeToCover = { switchToPosterShowcaseMode(saveCurrentPlayerPos = true, animateTransition = true) },
            onFullscreenChanged = { enterFull ->
                if (enterFull) {
                    heroBackdropFixedContainer.visibility = View.GONE
                    heroTopActionsRow.visibility = View.GONE
                    vHeroTopSpacer.visibility = View.GONE
                    detailsScrollView.visibility = View.GONE
                } else {
                    detailsScrollView.visibility = View.VISIBLE
                }
            },
            onServerSelected = { srvIdx, server ->
                currentProviderIndex = srvIdx
                hasLockedPrimaryStream = false
                resolvedDirectStreams.clear()
                moviePlayerController.setLoadingState(true)
                if (server.isDirectStream) {
                    onRawStreamIntercepted(server.url, server.referer, server.origin, server.userAgent)
                } else {
                    loadProviderInHeadlessSniffer(srvIdx)
                }
            },
            onRequestRefreshSubtitles = { onResult ->
                val vod = currentVod
                if (vod != null) {
                    lifecycleScope.launch {
                        val fresh = withContext(Dispatchers.IO) {
                            OttRepository.fetchRealSubtitlesForMovie(
                                vod.id,
                                fullDetails?.imdbId.orEmpty(),
                                vod.isSeries,
                                selectedSeason,
                                selectedEpisode
                            )
                        }
                        onResult(fresh)
                    }
                } else {
                    onResult(emptyList())
                }
            },
            onDownloadSubtitleTrack = { track ->
                withContext(Dispatchers.IO) {
                    OttRepository.downloadAndParseSubtitles(track)
                }
            },
            onPickLocalSubtitleFile = {
                runCatching { localSubtitleLauncher.launch("*/*") }
            },
            onSaveProgressTick = { pos, dur ->
                currentVod?.let { v ->
                    pendingResumePositionMs = pos
                    SessionManager.saveWatchProgress(this@ContentDetailActivity, v, pos, dur)
                }
            }
        )

        bindXmlViewsAndListeners()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleSmartBackNavigation()
            }
        })

        playerEngine = PrimePlayerEngine(
            context = this,
            scope = lifecycleScope,
            onSourceSwitched = { _, src ->
                moviePlayerController.setServerBadgeLabel(src.label)
                moviePlayerController.updatePlayPauseVectorIcon(true)
            },
            onRealQualityDetected = { qualityBadge, _ ->
                runOnUiThread {
                    moviePlayerController.onEngineQualityDetected(qualityBadge)
                }
            },
            onFirstFrameRendered = {
                runOnUiThread {
                    moviePlayerController.onFirstFrameRendered()
                }
            },
            onPlaybackExhausted = {
                runOnUiThread {
                    hasLockedPrimaryStream = false
                    tryNextProviderInBackground()
                }
            }
        )

        moviePlayerController.bindEngineAndActions(
            engine = playerEngine,
            getProviders = { providerTargets },
            getCurrentProviderIdx = { currentProviderIndex }
        )

        setupInvisibleStreamSniffer()
        loadVodItemIntoScreen(item, resetScroll = true, animatePageEntry = false)
        moviePlayerController.startProgressAndNetworkLoop(playerEngine)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        moviePlayerController.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    private fun handleSmartBackNavigation() {
        when {
            moviePlayerController.isFullscreen -> {
                moviePlayerController.applyFullscreenState(false)
            }
            actorPageOverlay.isVisible -> {
                closeActorPageOverlayAndRestoreIfNeeded()
            }
            isPlayerModeActive -> {
                switchToPosterShowcaseMode(saveCurrentPlayerPos = true, animateTransition = true)
            }
            navigationBackStack.isNotEmpty() -> {
                when (val prev = navigationBackStack.removeLast()) {
                    is NavHistoryEntry.ActorState -> {
                        restoreActorPageFromState(prev)
                    }
                    is NavHistoryEntry.MovieState -> {
                        loadVodItemIntoScreen(
                            vod = prev.vod,
                            resetScroll = false,
                            targetScrollY = prev.scrollY,
                            animatePageEntry = true
                        )
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

    private fun bindXmlViewsAndListeners() {
        fullScreenAmbientDrawable = FlixVisionFullScreenAmbientDrawable(
            coverHeightPx = dpF(365f),
            bottomFadeStartAtRestPx = dpF(170f),
            fullOpaqueYAtRestPx = dpF(285f),
            scrollToHideDistancePx = dpF(195f)
        )

        rootFrameWrapper = findViewById(R.id.rootFrameWrapper)
        heroBackdropFixedContainer = findViewById(R.id.heroBackdropFixedContainer)
        ivHeroBackdrop = findViewById(R.id.ivHeroBackdrop)
        vFullDynamicAmbientBg = findViewById(R.id.vFullDynamicAmbientBg)
        vFullDynamicAmbientBg.background = fullScreenAmbientDrawable

        headlessSnifferWebView = findViewById(R.id.headlessSnifferWebView)
        rootVerticalLayout = findViewById(R.id.rootVerticalLayout)

        val includedPlayerFrame = findViewById<FrameLayout>(R.id.includedMoviePlayer)
        moviePlayerController.bindFromXml(includedPlayerFrame)

        detailsScrollView = findViewById(R.id.detailsScrollView)
        detailsMasterColumn = findViewById(R.id.detailsMasterColumn)
        vHeroTopSpacer = findViewById(R.id.vHeroTopSpacer)
        contentSheetColumn = findViewById(R.id.contentSheetColumn)

        tvMovieTitle = findViewById(R.id.tvMovieTitle)
        tvMovieGenresLine = findViewById(R.id.tvMovieGenresLine)
        metaPillsRow = findViewById(R.id.metaPillsRow)

        btnPlayNowMain = findViewById(R.id.btnPlayNowMain)
        tvPlayNowBtnLabel = findViewById(R.id.tvPlayNowBtnLabel)
        btnWatchPartyBox = findViewById(R.id.btnWatchPartyBox)
        btnTrailerBox = findViewById(R.id.btnTrailerBox)
        btnDownloadBox = findViewById(R.id.btnDownloadBox)

        seriesContainer = findViewById(R.id.seriesContainer)
        seasonTabsRow = findViewById(R.id.seasonTabsRow)
        rvEpisodes = findViewById(R.id.rvEpisodes)
        rvEpisodes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        tvOverviewText = findViewById(R.id.tvOverviewText)
        crewMetadataContainer = findViewById(R.id.crewMetadataContainer)
        rowDirector = findViewById(R.id.rowDirector)
        tvDirectorValue = findViewById(R.id.tvDirectorValue)
        rowWriters = findViewById(R.id.rowWriters)
        tvWritersValue = findViewById(R.id.tvWritersValue)

        tvCastHeader = findViewById(R.id.tvCastHeader)
        rvCast = findViewById(R.id.rvCast)
        rvCast.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvCast.setHasFixedSize(true)

        tvRecommendedHeader = findViewById(R.id.tvRecommendedHeader)
        rvRecommended = findViewById(R.id.rvRecommended)
        rvRecommended.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvRecommended.setHasFixedSize(true)

        tvMoreLikeHeader = findViewById(R.id.tvMoreLikeHeader)
        rvMoreLike = findViewById(R.id.rvMoreLike)
        rvMoreLike.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvMoreLike.setHasFixedSize(true)

        heroTopActionsRow = findViewById(R.id.heroTopActionsRow)
        btnTopBackHero = findViewById(R.id.btnTopBackHero)
        btnFavBox = findViewById(R.id.btnFavBox)

        btnTopBackHero.background = makeRoundedBg("#4D080A10".toColorInt(), 21f, "#36FFFFFF".toColorInt(), 1f)
        btnFavBox.background = makeRoundedBg("#4D080A10".toColorInt(), 21f, "#36FFFFFF".toColorInt(), 1f)

        btnTopBackHero.setOnClickListener { handleSmartBackNavigation() }
        btnFavBox.setOnClickListener {
            val vod = currentVod ?: return@setOnClickListener
            OttRepository.toggleFavorite(vod.id, this)
            val nowFav = OttRepository.isChannelFavorite(vod.id)
            updateFavoriteIcon(vod.id)
            val msg = if (nowFav) {
                "Added \"${vod.title}\" to Favorites"
            } else {
                "Removed \"${vod.title}\" from Favorites"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnPlayNowMain.setOnClickListener { activateInlinePlayerSmoothly() }
        btnWatchPartyBox.setOnClickListener { createWatchTogetherRoom() }
        btnTrailerBox.setOnClickListener {
            val key = fullDetails?.trailerYoutubeKey.orEmpty()
            if (key.isNotEmpty()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")))
            } else {
                val q = Uri.encode("${currentVod?.title ?: ""} official trailer")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")))
            }
        }
        btnDownloadBox.setOnClickListener { handleDirectMovieDownload() }
        tvOverviewText.setOnClickListener { toggleOverviewExpansion() }

        AnimUtils.attachPressBounce(btnTopBackHero)
        AnimUtils.attachPressBounce(btnFavBox)
        AnimUtils.attachPressBounce(btnPlayNowMain)
        AnimUtils.attachPressBounce(btnWatchPartyBox)
        AnimUtils.attachPressBounce(btnTrailerBox)
        AnimUtils.attachPressBounce(btnDownloadBox)

        actorPageOverlay = findViewById(R.id.actorPageOverlay)
        val btnActorBack = findViewById<ImageView>(R.id.btnActorBack)
        val actorAvatarWrap = findViewById<FrameLayout>(R.id.actorAvatarWrap)
        ivActorPageAvatar = findViewById(R.id.ivActorPageAvatar)
        tvActorPageName = findViewById(R.id.tvActorPageName)
        tvActorMoviesTab = findViewById(R.id.tvActorMoviesTab)
        tvActorSeriesTab = findViewById(R.id.tvActorSeriesTab)
        btnActorSort = findViewById(R.id.btnActorSort)
        actorPageSpinner = findViewById(R.id.actorPageSpinner)
        rvActorCreditsGrid = findViewById(R.id.rvActorCreditsGrid)
        rvActorCreditsGrid.layoutManager = GridLayoutManager(this, 3)

        btnActorBack.background = makeRoundedBg("#1FFFFFFF".toColorInt(), 20f, "#33FFFFFF".toColorInt(), 1f)
        btnActorBack.setOnClickListener { handleSmartBackNavigation() }

        actorAvatarWrap.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor("#18202C".toColorInt())
            setStroke(dp(2), "#E6FFFFFF".toColorInt())
        }

        btnActorSort.setImageDrawable(VectorIcons.createSortBarsIcon(this, Color.WHITE))
        btnActorSort.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor("#1A202C".toColorInt())
            setStroke(dp(1), "#33FFFFFF".toColorInt())
        }

        tvActorMoviesTab.setOnClickListener {
            currentActorFilterMode = if (currentActorFilterMode == "MOVIES") "ALL" else "MOVIES"
            refreshActorOverlayGrid()
        }
        tvActorSeriesTab.setOnClickListener {
            currentActorFilterMode = if (currentActorFilterMode == "SERIES") "ALL" else "SERIES"
            refreshActorOverlayGrid()
        }
        btnActorSort.setOnClickListener {
            currentActorSortByYearDesc = !currentActorSortByYearDesc
            Toast.makeText(
                this,
                if (currentActorSortByYearDesc) "Sorted by Release Year" else "Sorted by Rating",
                Toast.LENGTH_SHORT
            ).show()
            refreshActorOverlayGrid()
        }

        detailsScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            fullScreenAmbientDrawable.updateScrollOffset(scrollY.toFloat())
        }
    }

    /**
     * خوارزمية استخراج الألوان الديناميكية الدقيقة (72-Hue Swatch Histogram)
     * تلتقط اللون الحقيقي الطاغي في الغلاف (أخضر، ذهبي، سماوي، قرمزي، بنفسجي، برتقالي، أزرق، إلخ)
     * دون خلط الألوان المتقابلة مع بعضها.
     */
    private fun extractFlixVisionPalette(bitmap: Bitmap): Pair<Int, Int> {
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
            val numBins = 72
            val binWeights = FloatArray(numBins)
            val binCosH = FloatArray(numBins)
            val binSinH = FloatArray(numBins)
            val binSatSum = FloatArray(numBins)
            val binValSum = FloatArray(numBins)

            var coolMutedR = 0f
            var coolMutedG = 0f
            var coolMutedB = 0f
            var coolMutedWeight = 0f
            var colorfulPixelCount = 0

            val hsv = FloatArray(3)

            for (y in 4 until 62) {
                val verticalFactor = when {
                    y >= 32 -> 1.85f
                    y >= 18 -> 1.15f
                    else -> 0.70f
                }
                for (x in 2 until 62) {
                    val pixel = scaled.getPixel(x, y)
                    if (Color.alpha(pixel) < 180) continue

                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    Color.colorToHSV(pixel, hsv)

                    val hue = hsv[0]
                    val sat = hsv[1]
                    val value = hsv[2]

                    if (value in 0.12f..0.75f) {
                        val w = (0.15f + sat) * verticalFactor
                        coolMutedR += r * w
                        coolMutedG += g * w
                        coolMutedB += b * w
                        coolMutedWeight += w
                    }

                    // Netjahlo el abyad w el gris el fateh bezzaf (kima Disclosure Day) bach ma ydirch green ghalet
                    if (value < 0.10f || (value > 0.76f && sat < 0.32f) || sat < 0.18f) continue

                    // Naqso men lawn el hait el beige w el bachra
                    val skinOrBeigePenalty = if (hue in 15f..50f && sat < 0.65f && value > 0.32f) {
                        0.12f
                    } else {
                        1.0f
                    }

                    if (sat >= 0.24f && value in 0.12f..0.72f) {
                        colorfulPixelCount++
                    }

                    val depthFactor = if (value in 0.12f..0.58f) 1.35f else 0.75f
                    val weight = (sat * sat * 2.2f + sat * 0.5f) * depthFactor * verticalFactor * skinOrBeigePenalty

                    val bin = ((hue / 5f).toInt()).coerceIn(0, numBins - 1)
                    val rad = Math.toRadians(hue.toDouble())
                    binWeights[bin] += weight
                    binCosH[bin] += (cos(rad).toFloat() * weight)
                    binSinH[bin] += (sin(rad).toFloat() * weight)
                    binSatSum[bin] += sat * weight
                    binValSum[bin] += value * weight
                }
            }

            var bestBin = -1
            var bestClusterScore = 0f
            for (i in 0 until numBins) {
                val prev = (i - 1 + numBins) % numBins
                val next = (i + 1) % numBins
                val clusterScore = binWeights[i] + (binWeights[prev] * 0.45f) + (binWeights[next] * 0.45f)
                if (clusterScore > bestClusterScore) {
                    bestClusterScore = clusterScore
                    bestBin = i
                }
            }

            // Ila kanet el soura bida/gris barda (kima Disclosure Day) ma fihach alwan qwiya
            if (colorfulPixelCount < 95 || bestBin == -1 || bestClusterScore < 0.05f) {
                return if (coolMutedWeight > 0.01f) {
                    val avgR = (coolMutedR / coolMutedWeight).toInt().coerceIn(0, 255)
                    val avgG = (coolMutedG / coolMutedWeight).toInt().coerceIn(0, 255)
                    val avgB = (coolMutedB / coolMutedWeight).toInt().coerceIn(0, 255)
                    Color.colorToHSV(Color.rgb(avgR, avgG, avgB), hsv)
                    // Nkhaliwh Slate Blue/Grey ghameq kima FlixVision f Disclosure Day
                    if (hsv[1] < 0.22f) {
                        hsv[0] = 212f
                        hsv[1] = 0.34f
                    } else {
                        hsv[1] = hsv[1].coerceIn(0.24f, 0.42f)
                    }
                    hsv[2] = hsv[2].coerceIn(0.22f, 0.30f)
                    Color.HSVToColor(hsv) to "#000000".toColorInt()
                } else {
                    "#222D3A".toColorInt() to "#000000".toColorInt()
                }
            }

            val prev = (bestBin - 1 + numBins) % numBins
            val next = (bestBin + 1 + numBins) % numBins
            val totalW = (binWeights[bestBin] + binWeights[prev] * 0.45f + binWeights[next] * 0.45f).coerceAtLeast(0.001f)
            val avgCos = (binCosH[bestBin] + binCosH[prev] * 0.45f + binCosH[next] * 0.45f) / totalW
            val avgSin = (binSinH[bestBin] + binSinH[prev] * 0.45f + binSinH[next] * 0.45f) / totalW

            var dominantHue = Math.toDegrees(kotlin.math.atan2(avgSin.toDouble(), avgCos.toDouble())).toFloat()
            if (dominantHue < 0f) dominantHue += 360f

            val rawSat = (binSatSum[bestBin] + binSatSum[prev] * 0.45f + binSatSum[next] * 0.45f) / totalW
            val rawVal = (binValSum[bestBin] + binValSum[prev] * 0.45f + binValSum[next] * 0.45f) / totalW

            val finalSat = (rawSat * 1.05f).coerceIn(0.32f, 0.82f)
            val finalVal = (rawVal * 0.78f).coerceIn(0.24f, 0.38f)

            val leftBrightColor = Color.HSVToColor(floatArrayOf(dominantHue, finalSat, finalVal))
            leftBrightColor to "#000000".toColorInt()
        } catch (_: Exception) {
            "#233242".toColorInt() to "#000000".toColorInt()
        }
    }

    private fun startCoverColorExtractionAnimation(targetLeftColor: Int, targetRightColor: Int) {
        colorExtractionAnimator?.cancel()

        moviePlayerController.setDynamicThemeColor(targetLeftColor)

        val evaluator = ArgbEvaluator()
        val startLeft = currentLeftBrightColor
        val startRight = currentRightDarkColor

        colorExtractionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 0L // Yebda direct bla ma yestenah 2s f static color
            duration = 1500L // Animation tban b salasa kima FlixVision
            interpolator = DecelerateInterpolator(1.4f)
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

        actorPageOverlay.animate().cancel()
        actorPageOverlay.alpha = 0f
        actorPageOverlay.translationY = dpF(36f)
        actorPageOverlay.visibility = View.VISIBLE
        actorPageOverlay.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(280L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()

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
            loadVodItemIntoScreen(clickedMovie, resetScroll = true, animatePageEntry = true)
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
        actorPageOverlay.animate().cancel()
        actorPageOverlay.alpha = 0f
        actorPageOverlay.translationY = dpF(28f)
        actorPageOverlay.visibility = View.VISIBLE
        actorPageOverlay.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun closeActorPageOverlayAndRestoreIfNeeded() {
        actorPageOverlay.animate().cancel()
        actorPageOverlay.animate()
            .alpha(0f)
            .translationY(dpF(32f))
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                actorPageOverlay.visibility = View.GONE
                actorPageOverlay.translationY = 0f
            }
            .start()

        val topEntry = navigationBackStack.lastOrNull()
        if (topEntry is NavHistoryEntry.MovieState && topEntry.vod.id == currentVod?.id) {
            val saved = navigationBackStack.removeLast() as NavHistoryEntry.MovieState
            detailsScrollView.post {
                detailsScrollView.scrollTo(0, saved.scrollY)
            }
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

    /**
     * فتح المشغل بدون تحريك alpha على حاوية الـ SurfaceView لمنع تجمّد الصورة نهائياً
     */
    private fun activateInlinePlayerSmoothly() {
        isPlayerModeActive = true
        fullScreenAmbientDrawable.setPlayerModeActive(true)

        btnPlayNowMain.visibility = View.GONE
        vHeroTopSpacer.visibility = View.GONE
        heroTopActionsRow.visibility = View.GONE
        heroBackdropFixedContainer.visibility = View.GONE

        val pContainer = moviePlayerController.playerContainer
        pContainer.alpha = 1f
        pContainer.translationY = 0f
        pContainer.visibility = View.VISIBLE
        detailsScrollView.smoothScrollTo(0, 0)

        if (resolvedDirectStreams.isNotEmpty()) {
            moviePlayerController.playerView.onResume()
            if (playerEngine.player != null && moviePlayerController.playerView.player == playerEngine.player) {
                moviePlayerController.setLoadingState(false)
                playerEngine.resume()
                moviePlayerController.updatePlayPauseVectorIcon(true)
            } else {
                moviePlayerController.setLoadingState(true)
                playerEngine.playSources(
                    moviePlayerController.playerView,
                    resolvedDirectStreams.toList(),
                    0,
                    pendingResumePositionMs
                )
            }
        } else {
            moviePlayerController.setLoadingState(true)
            val vod = currentVod ?: return
            if (providerTargets.isEmpty()) {
                startStreamExtractionForEpisodeOrMovie(vod, selectedSeason, selectedEpisode, runWebViewNow = true)
            } else {
                loadProviderInHeadlessSniffer(currentProviderIndex)
            }
        }
    }

    private fun switchToPosterShowcaseMode(saveCurrentPlayerPos: Boolean, animateTransition: Boolean) {
        if (saveCurrentPlayerPos && isPlayerModeActive) {
            playerEngine.player?.let { exo ->
                if (exo.currentPosition > 5000L && exo.duration > 10000L) {
                    pendingResumePositionMs = exo.currentPosition
                    currentVod?.let { v ->
                        SessionManager.saveWatchProgress(this, v, exo.currentPosition, exo.duration)
                    }
                }
            }
        }
        isPlayerModeActive = false
        playerEngine.pause()
        moviePlayerController.updatePlayPauseVectorIcon(false)
        fullScreenAmbientDrawable.setPlayerModeActive(false)

        val pContainer = moviePlayerController.playerContainer
        pContainer.visibility = View.GONE
        vHeroTopSpacer.visibility = View.VISIBLE

        btnPlayNowMain.visibility = View.VISIBLE
        tvPlayNowBtnLabel.text = if (pendingResumePositionMs > 15_000L) "RESUME" else "PLAY"

        heroBackdropFixedContainer.animate().cancel()
        heroTopActionsRow.animate().cancel()
        ivHeroBackdrop.alpha = 1f

        if (animateTransition) {
            heroBackdropFixedContainer.alpha = 0f
            heroBackdropFixedContainer.scaleX = 1.03f
            heroBackdropFixedContainer.scaleY = 1.03f
            heroBackdropFixedContainer.visibility = View.VISIBLE
            heroBackdropFixedContainer.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240L)
                .setInterpolator(DecelerateInterpolator())
                .start()

            heroTopActionsRow.alpha = 0f
            heroTopActionsRow.visibility = View.VISIBLE
            heroTopActionsRow.animate()
                .alpha(1f)
                .setDuration(220L)
                .start()
        } else {
            heroBackdropFixedContainer.alpha = 1f
            heroBackdropFixedContainer.scaleX = 1f
            heroBackdropFixedContainer.scaleY = 1f
            heroBackdropFixedContainer.visibility = View.VISIBLE
            heroTopActionsRow.alpha = 1f
            heroTopActionsRow.visibility = View.VISIBLE
        }

        detailsScrollView.smoothScrollTo(0, 0)
    }

    private fun handleDirectMovieDownload() {
        val directUrl = resolvedDirectStreams.firstOrNull()?.url
        if (!directUrl.isNullOrEmpty() && !directUrl.contains(".m3u8", ignoreCase = true)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(directUrl)))
        } else if (!directUrl.isNullOrEmpty()) {
            Toast.makeText(this, "Opening External Downloader...", Toast.LENGTH_SHORT).show()
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
                        languageLabel = if (isAr) "Arabic" else "English",
                        releaseName = "",
                        url = reqUrl
                    )
                    runOnUiThread {
                        val existing = moviePlayerController.getAvailableSubtitles()
                        if (existing.none { it.url == reqUrl }) {
                            val updated = listOf(subTrack) + existing
                            moviePlayerController.updateAvailableSubtitles(updated)
                            if (isAr || moviePlayerController.getActiveSubtitleCues().isEmpty()) {
                                lifecycleScope.launch {
                                    val cues = withContext(Dispatchers.IO) {
                                        OttRepository.downloadAndParseSubtitles(subTrack)
                                    }
                                    if (cues.isNotEmpty()) {
                                        moviePlayerController.updateAvailableSubtitles(updated, cues, subTrack.id)
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
     * منطق التقاط البث الأصلي المستقر مع دعم السيرفرات الاحتياطية بسلاسة
     */
    private fun onRawStreamIntercepted(url: String, referer: String, origin: String, userAgent: String) {
        runOnUiThread {
            val providerLabel = providerTargets.getOrNull(currentProviderIndex)?.label ?: "Server 1"
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
                moviePlayerController.setServerBadgeLabel(providerLabel)

                if (isPlayerModeActive) {
                    playerEngine.playSources(
                        moviePlayerController.playerView,
                        resolvedDirectStreams.toList(),
                        0,
                        pendingResumePositionMs
                    )
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
        playerEngine.pauseAndReleaseSocket()
        resolvedDirectStreams.clear()
        providerTargets = emptyList()

        headlessSnifferWebView.stopLoading()
        headlessSnifferWebView.loadUrl("about:blank")
        headlessSnifferWebView.onPause()
        headlessSnifferWebView.pauseTimers()

        moviePlayerController.resetForNewStream(
            isActiveMode = isPlayerModeActive,
            isSeries = vod.isSeries
        )

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
                moviePlayerController.setLoadingState(false)
                return@launch
            }

            moviePlayerController.setServerBadgeLabel(panelServers.first().label)

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
        moviePlayerController.setServerBadgeLabel(target.label)
        moviePlayerController.setLoadingState(isPlayerModeActive)

        headlessSnifferWebView.onResume()
        headlessSnifferWebView.resumeTimers()
        val headers = if (target.referer.isNotEmpty()) mapOf("Referer" to target.referer) else emptyMap()
        headlessSnifferWebView.loadUrl(target.url, headers)

        snifferTimeoutJob?.cancel()
        snifferTimeoutJob = lifecycleScope.launch {
            delay(6500L)
            if (!hasLockedPrimaryStream && !playerEngine.isPlaying()) {
                tryNextProviderInBackground()
            }
        }
    }

    private fun tryNextProviderInBackground() {
        if (providerTargets.isEmpty()) return
        hasLockedPrimaryStream = false
        val nextIdx = (currentProviderIndex + 1) % providerTargets.size
        val nextServer = providerTargets[nextIdx]
        currentProviderIndex = nextIdx
        moviePlayerController.setServerBadgeLabel(nextServer.label)

        if (nextServer.isDirectStream) {
            onRawStreamIntercepted(nextServer.url, nextServer.referer, nextServer.origin, nextServer.userAgent)
        } else {
            loadProviderInHeadlessSniffer(nextIdx)
        }
    }

    private fun upgradeTmdbImageQuality(url: String, preferredSize: String = "w1280"): String {
        if (!url.contains("image.tmdb.org/t/p/")) return url
        return url.replace(Regex("/t/p/w\\d+/"), "/t/p/$preferredSize/")
    }

    private fun renderCoverUrlAndExtractPalette(vodId: String, coverUrl: String) {
        if (coverUrl.isEmpty()) return
        val cacheKey = "${vodId}_${coverUrl.hashCode()}"
        val cachedPalette = paletteCache[cacheKey]

        Glide.with(this)
            .asBitmap()
            .load(coverUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .dontAnimate()
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (currentVod?.id != vodId || isDestroyed || isFinishing) return
                    ivHeroBackdrop.setImageBitmap(resource)
                    val extracted = cachedPalette ?: extractFlixVisionPalette(resource).also {
                        paletteCache[cacheKey] = it
                    }
                    startCoverColorExtractionAnimation(extracted.first, extracted.second)
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    /**
     * جلب غلاف داخلي مختلف لصفحة التفاصيل (مثل FlixVision في parseMovie: يستعمل backdrop_path w1280 أو غلاف بديل من TMDB)
     * حتى يتغير الغلاف عن البوستر الخارجي ويتحدث اللون الديناميكي معه
     */
    private fun loadPosterAndExtractPalette(vod: VodItem) {
        coverResolveJob?.cancel()
        colorExtractionAnimator?.cancel()
        ivHeroBackdrop.setImageDrawable(null)
        fullScreenAmbientDrawable.updateColors(
            leftBright = FLIX_INITIAL_LEFT_COLOR,
            rightDark = FLIX_INITIAL_RIGHT_COLOR,
            extractionFraction = 0f
        )

        val outsideFileName = vod.posterUrl.substringAfterLast("/", "")
        val cachedDistinctCover = distinctCoverCache[vod.id]

        val hasDistinctBackdrop = vod.backdropUrl.isNotEmpty() &&
                !vod.backdropUrl.endsWith(outsideFileName)

        val initialCoverUrl = when {
            !cachedDistinctCover.isNullOrEmpty() -> cachedDistinctCover
            hasDistinctBackdrop -> upgradeTmdbImageQuality(vod.backdropUrl, "w1280")
            vod.posterUrl.isNotEmpty() -> upgradeTmdbImageQuality(vod.posterUrl, "w780")
            else -> vod.backdropUrl
        }

        if (initialCoverUrl.isNotEmpty()) {
            renderCoverUrlAndExtractPalette(vod.id, initialCoverUrl)
        }

        // إذا لم يكن هناك غلاف داخلي مختلف بعد، نجلبه من TMDB مباشرة ليظهر غلاف مختلف عن البوستر الخارجي
        if (cachedDistinctCover.isNullOrEmpty() && !hasDistinctBackdrop) {
            coverResolveJob = lifecycleScope.launch {
                val fetchedCover = withContext(Dispatchers.IO) {
                    fetchDistinctTmdbCoverUrl(vod.id, vod.isSeries, outsideFileName)
                }
                if (!fetchedCover.isNullOrEmpty() && currentVod?.id == vod.id) {
                    distinctCoverCache[vod.id] = fetchedCover
                    renderCoverUrlAndExtractPalette(vod.id, fetchedCover)
                }
            }
        }
    }

    private fun fetchDistinctTmdbCoverUrl(
        rawId: String,
        isSeries: Boolean,
        outsideFileName: String
    ): String? {
        val cleanId = rawId.filter { it.isDigit() }
        if (cleanId.isEmpty()) return null
        val typePath = if (isSeries) "tv" else "movie"
        val apiUrl = "https://api.themoviedb.org/3/$typePath/$cleanId?api_key=$TMDB_API_KEY&append_to_response=images"

        return try {
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)

            // 1. كما يفعل FlixVision في JsonUtils.parseMovie: أخذ backdrop_path بدقة w1280
            val backdropPath = json.optString("backdrop_path", "").trim()
            if (backdropPath.isNotEmpty() && backdropPath != "null" && !backdropPath.endsWith(outsideFileName)) {
                return "https://image.tmdb.org/t/p/w1280$backdropPath"
            }

            // 2. البحث في قائمة backdrops أو posters الإضافية عن صورة مختلفة عن البوستر الخارجي
            val imagesObj = json.optJSONObject("images")
            if (imagesObj != null) {
                val backdrops = imagesObj.optJSONArray("backdrops")
                if (backdrops != null) {
                    for (i in 0 until backdrops.length()) {
                        val filePath = backdrops.optJSONObject(i)?.optString("file_path").orEmpty()
                        if (filePath.isNotEmpty() && !filePath.endsWith(outsideFileName)) {
                            return "https://image.tmdb.org/t/p/w1280$filePath"
                        }
                    }
                }
                val posters = imagesObj.optJSONArray("posters")
                if (posters != null) {
                    for (i in 0 until posters.length()) {
                        val filePath = posters.optJSONObject(i)?.optString("file_path").orEmpty()
                        if (filePath.isNotEmpty() && !filePath.endsWith(outsideFileName)) {
                            return "https://image.tmdb.org/t/p/w780$filePath"
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun loadVodItemIntoScreen(
        vod: VodItem,
        resetScroll: Boolean = true,
        targetScrollY: Int = 0,
        animatePageEntry: Boolean = false
    ) {
        detailsFetchJob?.cancel()

        val previousVod = currentVod
        if (previousVod != null && previousVod.id != vod.id && isPlayerModeActive) {
            playerEngine.player?.let { exo ->
                if (exo.currentPosition > 5000L && exo.duration > 10000L) {
                    SessionManager.saveWatchProgress(this, previousVod, exo.currentPosition, exo.duration)
                }
            }
        }

        isPlayerModeActive = false
        playerEngine.pauseAndReleaseSocket()

        switchToPosterShowcaseMode(saveCurrentPlayerPos = false, animateTransition = false)

        currentVod = vod
        selectedSeason = 1
        selectedEpisode = 1
        isOverviewExpanded = false
        moviePlayerController.updateAvailableSubtitles(emptyList(), emptyList(), "")

        val savedForThisMovie = SessionManager.getSavedPositionForVod(this, vod.id)
            .coerceAtLeast(vod.savedPositionMs)
        pendingResumePositionMs = if (savedForThisMovie > 15_000L) savedForThisMovie else 0L

        tvPlayNowBtnLabel.text = if (pendingResumePositionMs > 15_000L) "RESUME" else "PLAY"

        moviePlayerController.setTitle(vod.title)
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

        if (animatePageEntry) {
            ivHeroBackdrop.alpha = 0f
            ivHeroBackdrop.animate().alpha(1f).setDuration(280L).start()

            contentSheetColumn.alpha = 0f
            contentSheetColumn.translationY = dpF(22f)
            contentSheetColumn.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280L)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
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

            startStreamExtractionForEpisodeOrMovie(vod, 1, 1, runWebViewNow = true)

            withContext(Dispatchers.IO) {
                val subs = OttRepository.fetchRealSubtitlesForMovie(
                    vod.id,
                    details.imdbId,
                    vod.isSeries,
                    selectedSeason,
                    selectedEpisode
                )
                if (subs.isNotEmpty()) {
                    moviePlayerController.updateAvailableSubtitles(subs)
                    autoSelectPreferredSubtitle(subs)
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
            loadVodItemIntoScreen(clickedVod, resetScroll = true, animatePageEntry = true)
        }
        rvMoreLike.adapter = VodGridAdapter(details.similar, isGrid = false) { clickedVod ->
            currentVod?.let { cur ->
                navigationBackStack.addLast(NavHistoryEntry.MovieState(cur, detailsScrollView.scrollY))
            }
            loadVodItemIntoScreen(clickedVod, resetScroll = true, animatePageEntry = true)
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
                    moviePlayerController.setTitle(epTitle)
                    activateInlinePlayerSmoothly()
                    startStreamExtractionForEpisodeOrMovie(vod, seasonNum, ep.episodeNumber, runWebViewNow = true)
                    lifecycleScope.launch(Dispatchers.IO) {
                        val subs = OttRepository.fetchRealSubtitlesForMovie(
                            vod.id,
                            fullDetails?.imdbId.orEmpty(),
                            true,
                            seasonNum,
                            ep.episodeNumber
                        )
                        moviePlayerController.updateAvailableSubtitles(subs, emptyList(), "")
                        autoSelectPreferredSubtitle(subs)
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

    private suspend fun autoSelectPreferredSubtitle(subs: List<SubtitleTrack>) {
        if (subs.isEmpty()) return
        val arabicTracks = subs.filter { it.languageCode.startsWith("ar", true) }
        val candidates = if (arabicTracks.isNotEmpty()) arabicTracks else subs
        if (moviePlayerController.getSelectedSubtitleId().isEmpty() ||
            moviePlayerController.getActiveSubtitleCues().isEmpty()
        ) {
            for (track in candidates.take(4)) {
                val parsed = OttRepository.downloadAndParseSubtitles(track)
                if (parsed.size > 15) {
                    withContext(Dispatchers.Main) {
                        moviePlayerController.updateAvailableSubtitles(subs, parsed, track.id)
                    }
                    break
                }
            }
        }
    }

    private fun updateFavoriteIcon(id: String) {
        val isFav = OttRepository.isChannelFavorite(id)
        val resId = if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        btnFavBox.setImageResource(resId)
    }

   private fun createWatchTogetherRoom() {
        val vod = currentVod ?: return

        // نبعثو الرابط الواجد غير إذا كان الفيلم ديجا شاعل بالـ Play، وإلا نخلو الغرفة تجبدو مباشرة كيما اختيار الأفلام
        val workingStream = if (isPlayerModeActive) resolvedDirectStreams.firstOrNull() else null
        val directStream = workingStream?.url.orEmpty()
        val refererUrl = workingStream?.referer ?: "https://vidlink.pro/"
        val customUa = workingStream?.userAgent ?: SNIFFER_UA

        Toast.makeText(this, "🎬 جاري فتح غرفة المشاهدة...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val (ok, codeOrErr) = OttRepository.createWatchRoom(
                deviceId = SessionManager.getDeviceId(this@ContentDetailActivity),
                userName = SessionManager.getUserName(this@ContentDetailActivity),
                roomName = "غرفة ${vod.title.take(22)}",
                roomIcon = "🎬",
                isPrivate = false,
                password = "",
                allowGuestsControl = true,
                contentType = "VOD",
                streamId = vod.id,
                contentTitle = vod.title,
                contentPoster = vod.posterUrl,
                streamUrl = directStream
            )

            playerEngine.pauseAndReleaseSocket()

            startActivity(Intent(this@ContentDetailActivity, WatchRoomActivity::class.java).apply {
                putExtra(WatchRoomActivity.EXTRA_ROOM_CODE, if (ok) codeOrErr else "")
                putExtra(WatchRoomActivity.EXTRA_ROOM_NAME, "غرفة ${vod.title.take(22)}")
                putExtra(WatchRoomActivity.EXTRA_STREAM_URL, directStream)
                putExtra(WatchRoomActivity.EXTRA_STREAM_TITLE, vod.title)
                putExtra(WatchRoomActivity.EXTRA_IS_HOST, true)
                putExtra("direct_movie_launch", true)
                putExtra("stream_id", vod.id)
                putExtra("stream_referer", refererUrl)
                putExtra("stream_ua", customUa)
            })
        }
    }

    private fun launchRoomWithDirectStream(vod: VodItem, streamUrl: String, referer: String, ua: String) {
        lifecycleScope.launch {
            val (ok, codeOrErr) = OttRepository.createWatchRoom(
                deviceId = SessionManager.getDeviceId(this@ContentDetailActivity),
                userName = SessionManager.getUserName(this@ContentDetailActivity),
                roomName = "غرفة ${vod.title.take(22)}",
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

            playerEngine.pauseAndReleaseSocket()

            startActivity(Intent(this@ContentDetailActivity, WatchRoomActivity::class.java).apply {
                putExtra(WatchRoomActivity.EXTRA_ROOM_CODE, if (ok) codeOrErr else "")
                putExtra(WatchRoomActivity.EXTRA_ROOM_NAME, "غرفة ${vod.title.take(22)}")
                putExtra(WatchRoomActivity.EXTRA_STREAM_URL, streamUrl)
                putExtra(WatchRoomActivity.EXTRA_STREAM_TITLE, vod.title)
                putExtra(WatchRoomActivity.EXTRA_IS_HOST, true)
                putExtra("direct_movie_launch", true)
                putExtra("stream_id", vod.id)
                putExtra("stream_referer", referer)
                putExtra("stream_ua", ua)
            })
        }
    }

    
    override fun onPause() {
        super.onPause()
        val p = playerEngine.player
        val vod = currentVod
        if (isPlayerModeActive && p != null && vod != null && p.currentPosition > 5000L && p.duration > 10000L) {
            pendingResumePositionMs = p.currentPosition
            SessionManager.saveWatchProgress(this, vod, p.currentPosition, p.duration)
        }
        if (!isChangingConfigurations && !isInPictureInPictureMode) {
            playerEngine.pause()
            moviePlayerController.updatePlayPauseVectorIcon(false)
        }
    }

    override fun onResume() {
        super.onResume()
        val isPlayingNow = playerEngine.player?.isPlaying == true
        moviePlayerController.updatePlayPauseVectorIcon(isPlayingNow)
    }

    override fun onDestroy() {
        detailsFetchJob?.cancel()
        streamFetchJob?.cancel()
        snifferTimeoutJob?.cancel()
        coverResolveJob?.cancel()
        colorExtractionAnimator?.cancel()
        moviePlayerController.release()
        headlessSnifferWebView.destroy()
        playerEngine.release()
        super.onDestroy()
    }
}

class FlixVisionFullScreenAmbientDrawable(
    private val coverHeightPx: Float,
    private val bottomFadeStartAtRestPx: Float,
    private val fullOpaqueYAtRestPx: Float,
    private val scrollToHideDistancePx: Float
) : Drawable() {

    private val mainThemePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

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

        val midBlend1 = ColorUtils.blendARGB(leftBrightColor, rightDarkColor, 0.25f)
        val midBlend2 = ColorUtils.blendARGB(leftBrightColor, rightDarkColor, 0.54f)
        val midBlend3 = ColorUtils.blendARGB(leftBrightColor, rightDarkColor, 0.82f)

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
            floatArrayOf(0f, 0.26f, 0.54f, 0.80f, 1f),
            Shader.TileMode.CLAMP
        )

        val dynamicFadeStart = bottomFadeStartAtRestPx * (1f - scrollFraction)
        val dynamicOpaqueY = (fullOpaqueYAtRestPx * (1f - scrollFraction * 0.90f))
            .coerceAtLeast(dynamicFadeStart + 16f)

        // El shadow yabda fouq el isem w yakmal 100% taht el isem direct kima kan f code ta3ek w kima FlixVision
        val topCoverAlpha = (255f * scrollFraction).toInt().coerceIn(0, 255)
        val fadeStep1Alpha = (255f * (0.18f + 0.82f * scrollFraction)).toInt().coerceIn(0, 255)
        val fadeStep2Alpha = (255f * (0.52f + 0.48f * scrollFraction)).toInt().coerceIn(0, 255)
        val fadeStep3Alpha = (255f * (0.85f + 0.15f * scrollFraction)).toInt().coerceIn(0, 255)

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

        // El hawaf (Edges Foga + Gauche + Droite) b nafs el color ta3 li taht (leftBrightColor)
        if (!isPlayerMode && scrollFraction < 0.98f) {
            val fadeOutFactor = 1f - scrollFraction * 0.65f
            val coverBottom = min(h, coverHeightPx)

            // 1. El Hafa El Fouqaniya (Top Edge kima f FlixVision Screen 2) b nafs color ta3 li taht
            val topEdgeH = coverHeightPx * 0.34f
            val topAlpha = (155 * fadeOutFactor).toInt().coerceIn(0, 255)
            edgeGlowPaint.shader = LinearGradient(
                0f,
                0f,
                0f,
                topEdgeH,
                intArrayOf(
                    ColorUtils.setAlphaComponent(leftBrightColor, topAlpha),
                    ColorUtils.setAlphaComponent(leftBrightColor, (topAlpha * 0.55f).toInt()),
                    ColorUtils.setAlphaComponent(leftBrightColor, (topAlpha * 0.18f).toInt()),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.38f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, topEdgeH, edgeGlowPaint)

            // 2. El Hafa 3la Gauche (khfifa fel taraf bach yakhroj chwiya color men tam bla ma yghatti el cover)
            val leftEdgeW = w * 0.18f
            val leftAlpha = ((115 + 45 * extractionFraction) * fadeOutFactor).toInt().coerceIn(0, 255)
            edgeGlowPaint.shader = LinearGradient(
                0f,
                0f,
                leftEdgeW,
                0f,
                intArrayOf(
                    ColorUtils.setAlphaComponent(leftBrightColor, leftAlpha),
                    ColorUtils.setAlphaComponent(leftBrightColor, (leftAlpha * 0.48f).toInt()),
                    ColorUtils.setAlphaComponent(leftBrightColor, (leftAlpha * 0.14f).toInt()),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.36f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, leftEdgeW, coverBottom, edgeGlowPaint)

            // 3. El Hafa 3la Droite (b nafs el color mkhallat m3a rightDarkColor)
            val rightEdgeW = w * 0.20f
            val rightEdgeColor = ColorUtils.blendARGB(leftBrightColor, rightDarkColor, 0.55f)
            val rightAlpha = (135 * fadeOutFactor).toInt().coerceIn(0, 255)
            edgeGlowPaint.shader = LinearGradient(
                w,
                0f,
                w - rightEdgeW,
                0f,
                intArrayOf(
                    ColorUtils.setAlphaComponent(rightEdgeColor, rightAlpha),
                    ColorUtils.setAlphaComponent(rightEdgeColor, (rightAlpha * 0.48f).toInt()),
                    ColorUtils.setAlphaComponent(rightEdgeColor, (rightAlpha * 0.14f).toInt()),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.36f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(w - rightEdgeW, 0f, w, coverBottom, edgeGlowPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        mainThemePaint.alpha = alpha
        edgeGlowPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        mainThemePaint.colorFilter = colorFilter
        edgeGlowPaint.colorFilter = colorFilter
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
        val d = context.resources.displayMetrics.density
        val sizePx = (36 * d).toInt()
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
                    val barH = h * 0.74f
                    val top = (h - barH) / 2f
                    val r = barW * 0.18f
                    val left1 = w * 0.20f
                    val left2 = w * 0.58f
                    canvas.drawRoundRect(RectF(left1, top, left1 + barW, top + barH), r, r, paint)
                    canvas.drawRoundRect(RectF(left2, top, left2 + barW, top + barH), r, r, paint)
                } else {
                    val path = Path().apply {
                        moveTo(w * 0.24f, h * 0.14f)
                        lineTo(w * 0.86f, h * 0.50f)
                        lineTo(w * 0.24f, h * 0.86f)
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

    fun createCircularSeekArrowIcon(context: Context, isForward: Boolean): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (38 * d).toInt()
        return object : Drawable() {
            private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2.2f * d
                strokeCap = Paint.Cap.ROUND
            }
            private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2.2f * d
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 11.2f * d
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val pad = w * 0.12f
                val arcRect = RectF(pad, pad + h * 0.04f, w - pad, h - pad + h * 0.04f)

                if (isForward) {
                    canvas.drawArc(arcRect, -65f, 295f, false, ringPaint)
                    val cx = w * 0.56f
                    val cy = pad + h * 0.04f
                    val head = Path().apply {
                        moveTo(cx - w * 0.10f, cy - h * 0.09f)
                        lineTo(cx + w * 0.04f, cy)
                        lineTo(cx - w * 0.10f, cy + h * 0.09f)
                    }
                    canvas.drawPath(head, headPaint)
                } else {
                    canvas.drawArc(arcRect, -115f, -295f, false, ringPaint)
                    val cx = w * 0.44f
                    val cy = pad + h * 0.04f
                    val head = Path().apply {
                        moveTo(cx + w * 0.10f, cy - h * 0.09f)
                        lineTo(cx - w * 0.04f, cy)
                        lineTo(cx + w * 0.10f, cy + h * 0.09f)
                    }
                    canvas.drawPath(head, headPaint)
                }

                val textY = (h * 0.54f) - ((numPaint.descent() + numPaint.ascent()) / 2f)
                canvas.drawText("15", w / 2f, textY, numPaint)
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) {
                ringPaint.alpha = alpha
                headPaint.alpha = alpha
                numPaint.alpha = alpha
            }
            override fun setColorFilter(colorFilter: ColorFilter?) {
                ringPaint.colorFilter = colorFilter
                headPaint.colorFilter = colorFilter
                numPaint.colorFilter = colorFilter
            }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createFastForwardDoubleArrowIcon(context: Context, tintColor: Int): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (18 * d).toInt()
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.FILL
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val p1 = Path().apply {
                    moveTo(w * 0.08f, h * 0.20f)
                    lineTo(w * 0.50f, h * 0.50f)
                    lineTo(w * 0.08f, h * 0.80f)
                    close()
                }
                val p2 = Path().apply {
                    moveTo(w * 0.50f, h * 0.20f)
                    lineTo(w * 0.92f, h * 0.50f)
                    lineTo(w * 0.50f, h * 0.80f)
                    close()
                }
                canvas.drawPath(p1, paint)
                canvas.drawPath(p2, paint)
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createBrightnessSunIcon(context: Context, tintColor: Int): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (20 * d).toInt()
        return object : Drawable() {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.FILL
            }
            private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * d
                strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val cx = w / 2f
                val cy = h / 2f
                val coreR = min(w, h) * 0.22f
                canvas.drawCircle(cx, cy, coreR, fillPaint)

                val innerR = min(w, h) * 0.31f
                val outerR = min(w, h) * 0.44f
                for (i in 0 until 8) {
                    val angle = Math.toRadians((i * 45).toDouble())
                    val x1 = cx + (innerR * cos(angle)).toFloat()
                    val y1 = cy + (innerR * sin(angle)).toFloat()
                    val x2 = cx + (outerR * cos(angle)).toFloat()
                    val y2 = cy + (outerR * sin(angle)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, rayPaint)
                }
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) {
                fillPaint.alpha = alpha
                rayPaint.alpha = alpha
            }
            override fun setColorFilter(colorFilter: ColorFilter?) {
                fillPaint.colorFilter = colorFilter
                rayPaint.colorFilter = colorFilter
            }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createCheckMarkIcon(context: Context, tintColor: Int): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (16 * d).toInt()
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = 2.0f * d
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val path = Path().apply {
                    moveTo(w * 0.18f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.76f)
                    lineTo(w * 0.84f, h * 0.24f)
                }
                canvas.drawPath(path, paint)
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
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

    fun createAudioSpeakerIcon(context: Context, tintColor: Int): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (20 * d).toInt()
        return object : Drawable() {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.FILL
            }
            private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * d
                strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val speaker = Path().apply {
                    moveTo(w * 0.14f, h * 0.38f)
                    lineTo(w * 0.30f, h * 0.38f)
                    lineTo(w * 0.52f, h * 0.20f)
                    lineTo(w * 0.52f, h * 0.80f)
                    lineTo(w * 0.30f, h * 0.62f)
                    lineTo(w * 0.14f, h * 0.62f)
                    close()
                }
                canvas.drawPath(speaker, fillPaint)
                canvas.drawArc(RectF(w * 0.45f, h * 0.30f, w * 0.72f, h * 0.70f), -45f, 90f, false, wavePaint)
                canvas.drawArc(RectF(w * 0.38f, h * 0.16f, w * 0.88f, h * 0.84f), -45f, 90f, false, wavePaint)
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) {
                fillPaint.alpha = alpha
                wavePaint.alpha = alpha
            }
            override fun setColorFilter(colorFilter: ColorFilter?) {
                fillPaint.colorFilter = colorFilter
                wavePaint.colorFilter = colorFilter
            }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createPipMiniPlayerIcon(context: Context, tintColor: Int): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (20 * d).toInt()
        return object : Drawable() {
            private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * d
            }
            private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.FILL
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                canvas.drawRoundRect(RectF(w * 0.10f, h * 0.18f, w * 0.90f, h * 0.82f), w * 0.10f, w * 0.10f, outerPaint)
                canvas.drawRoundRect(RectF(w * 0.48f, h * 0.46f, w * 0.82f, h * 0.74f), w * 0.06f, w * 0.06f, innerPaint)
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) {
                outerPaint.alpha = alpha
                innerPaint.alpha = alpha
            }
            override fun setColorFilter(colorFilter: ColorFilter?) {
                outerPaint.colorFilter = colorFilter
                innerPaint.colorFilter = colorFilter
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

    fun createSmallServerStackIcon(context: Context, tintColor: Int): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (18 * d).toInt()
        return object : Drawable() {
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * d
            }
            private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.FILL
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val r = w * 0.12f
                canvas.drawRoundRect(RectF(w * 0.10f, h * 0.15f, w * 0.90f, h * 0.45f), r, r, strokePaint)
                canvas.drawCircle(w * 0.28f, h * 0.30f, w * 0.06f, dotPaint)
                canvas.drawRoundRect(RectF(w * 0.10f, h * 0.55f, w * 0.90f, h * 0.85f), r, r, strokePaint)
                canvas.drawCircle(w * 0.28f, h * 0.70f, w * 0.06f, dotPaint)
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) {
                strokePaint.alpha = alpha
                dotPaint.alpha = alpha
            }
            override fun setColorFilter(colorFilter: ColorFilter?) {
                strokePaint.colorFilter = colorFilter
                dotPaint.colorFilter = colorFilter
            }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createSmallQualitySparkIcon(context: Context, tintColor: Int): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (13 * d).toInt()
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * d
                strokeCap = Paint.Cap.ROUND
            }
            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                canvas.drawLine(w * 0.15f, h * 0.30f, w * 0.85f, h * 0.30f, paint)
                canvas.drawLine(w * 0.15f, h * 0.70f, w * 0.85f, h * 0.70f, paint)
                canvas.drawCircle(w * 0.38f, h * 0.30f, w * 0.12f, paint)
                canvas.drawCircle(w * 0.65f, h * 0.70f, w * 0.12f, paint)
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