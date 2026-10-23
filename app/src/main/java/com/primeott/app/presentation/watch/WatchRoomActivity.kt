package com.primeott.app.presentation.watch

import android.Manifest
import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.data.repository.PrimeVodExtractor
import com.primeott.app.domain.model.StreamSource
import com.primeott.app.domain.model.SubtitleCue
import com.primeott.app.domain.model.SubtitleTrack
import com.primeott.app.presentation.detail.VectorIcons
import com.primeott.app.presentation.player.PrimePlayerEngine
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WatchTogetherController(
    private val hostContext: Context,
    private val rootView: View,
    private val hostActivity: Activity?,
    private val isStandaloneActivity: Boolean
) {

    data class WatchRoomModel(
        var roomId: String,
        var roomCode: String,
        var roomName: String,
        var hostId: String,
        var hostName: String,
        var isPrivate: Boolean = false,
        var passwordHash: String = "",
        var maxParticipants: Int = 25,
        var onlineCount: Int = 1,
        var status: String = "LIVE",
        var streamId: String = "",
        var streamUrl: String = "",
        var streamReferer: String = "",
        var streamOrigin: String = "",
        var streamServerLabel: String = "REYZO 1",
        var streamUserAgent: String = SessionManager.CUSTOM_USER_AGENT,
        var streamTitle: String = "بث مباشر",
        var category: String = "قنوات مباشرة",
        var isMovie: Boolean = false,
        var isPlaying: Boolean = true,
        var playbackPositionMs: Long = 0L,
        val createdAt: Long = System.currentTimeMillis(),
        var expiresAt: Long = System.currentTimeMillis() + 3 * 3600_000L,
        var isChatLocked: Boolean = false
    )

    data class ParticipantModel(
        val userId: String,
        val userName: String,
        var role: String = "PARTICIPANT",
        var isMuted: Boolean = true,
        var isSpeaking: Boolean = false
    )

    data class ChatMessageModel(
        val msgId: String,
        val senderId: String,
        val senderName: String,
        val senderRole: String,
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val isSystemEvent: Boolean = false
    )

    data class MediaStreamOption(
        val id: String,
        val title: String,
        val subtitle: String,
        val streamUrl: String,
        val posterUrl: String = "",
        val rating: String = "7.5",
        val year: String = "2026",
        val referer: String = "",
        val isMovie: Boolean = false
    )

    data class RoomEventLogItem(
        val timestamp: Long,
        val type: String,
        val detail: String
    )

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OttRepository.okHttpClient

    private val posterBitmapCache = object : LruCache<String, android.graphics.Bitmap>(60) {}

    private var workerBaseUrl = SessionManager.CLOUDFLARE_API_URL
    private lateinit var localUserId: String
    private lateinit var localUserName: String
    private lateinit var authToken: String

    private val activeRoomsList = mutableListOf<WatchRoomModel>()
    private val filteredRoomsList = mutableListOf<WatchRoomModel>()
    private var currentLobbyFilter = "ALL"

    var currentRoom: WatchRoomModel? = null
    private val participantsMap = ConcurrentHashMap<String, ParticipantModel>()
    private val chatHistory = mutableListOf<ChatMessageModel>()
    private val eventLogs = mutableListOf<RoomEventLogItem>()
    private val blockedUserIds = ConcurrentHashMap.newKeySet<String>()

    private val allChannelsList = mutableListOf<MediaStreamOption>()
    private val allMoviesList = mutableListOf<MediaStreamOption>()
    private val displayedMediaList = mutableListOf<MediaStreamOption>()
    private var isShowingMoviesSubTab = false

    private var lastSyncedMessageId = 0L
    private var statPeakViewers = 1
    private var statTotalMessages = 0
    private var statSyncCount = 0
    private var isDestroyed = false

    private var webSocket: WebSocket? = null
    private var voiceChatManager: VoiceChatManager? = null

    private lateinit var playerEngine: PrimePlayerEngine
    private val currentProviderTargets = mutableListOf<StreamSource>()
    private val resolvedDirectStreams = mutableListOf<StreamSource>()
    private var currentProviderIndex = 0
    private var selectedQualityHeight: Int? = null
    private var isExtractingNewMovie = false
    private var activeMovieTmdbId: String = ""
    private var headlessSnifferWebView: WebView? = null
    private var snifferExtractionJob: Job? = null
    private var snifferTimeoutRunnable: Runnable? = null
    private var currentResizeModeIndex = 0
    private var isHandlingRemoteSync = false

    private var availableSubtitles: List<SubtitleTrack> = emptyList()
    private var activeSubtitleCues: List<SubtitleCue> = emptyList()
    private var selectedSubtitleId: String = ""
    private var subtitleOffsetMs: Long = 0L
    private var subtitleTextSizeSp: Float = 16f
    private var subtitleTextColor: Int = Color.WHITE
    private var subtitleBgAlpha: Int = 0
    private var subtitleExtraLiftDp: Int = 0

    private lateinit var playerFrameContainer: FrameLayout
    private lateinit var appCustomControlsOverlay: FrameLayout
    private lateinit var tvAppPlayerTitle: TextView
    private lateinit var btnAppServerPill: TextView
    private lateinit var btnAppQualityPill: TextView
    private lateinit var btnAppSubtitlesCc: ImageView
    private lateinit var btnAppPipIcon: ImageView
    private lateinit var btnAppRewind15: FrameLayout
    private lateinit var vAppRewindCircle: View
    private lateinit var btnAppPlayPauseSlot: FrameLayout
    private lateinit var vAppPlayPauseCircle: View
    private lateinit var ivAppCenterPlayPause: ImageView
    private lateinit var pbAppCenterBuffering: ProgressBar
    private lateinit var btnAppForward15: FrameLayout
    private lateinit var vAppForwardCircle: View
    private lateinit var tvAppElapsed: TextView
    private lateinit var sbAppRedSeekBar: SeekBar
    private lateinit var tvAppDuration: TextView
    private lateinit var btnAppFullscreenToggle: ImageView
    private lateinit var tvAppSubtitleOverlay: TextView
    private var isUserSeekingRedBar = false
    private var progressTickJob: Job? = null
    private var isFullscreenPlayer = false
    private var originalPlayerHeightPx = 0

    private lateinit var layoutLobbyContainer: LinearLayout
    private lateinit var layoutActiveRoomContainer: LinearLayout
    private lateinit var etJoinByCodeInput: EditText
    private lateinit var etRoomSearch: EditText
    private lateinit var tvLobbyStatsSummary: TextView
    private lateinit var rvActiveRooms: RecyclerView
    private lateinit var tvEmptyRoomsHint: View
    private lateinit var chipFilterAll: TextView
    private lateinit var chipFilterPublic: TextView
    private lateinit var chipFilterPrivate: TextView
    private lateinit var chipFilterChannels: TextView

    private lateinit var tvRoomHeaderTitle: TextView
    private lateinit var tvRoomCodeBadge: TextView
    private lateinit var tvRoomStatusBadge: TextView
    private lateinit var tvOnlineCountBadge: TextView
    private lateinit var btnHostControlsOpen: TextView
    private lateinit var btnCloseRoomHost: TextView
    private lateinit var layoutJoinRequestBanner: LinearLayout
    private fun applySubtitleStyleToOverlay() {
        if (!this::tvAppSubtitleOverlay.isInitialized) return
        tvAppSubtitleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp)
        tvAppSubtitleOverlay.setTextColor(subtitleTextColor)
        if (subtitleBgAlpha <= 10) {
            tvAppSubtitleOverlay.background = null
            tvAppSubtitleOverlay.setShadowLayer(12f, 0f, 3.5f, Color.BLACK)
        } else {
            tvAppSubtitleOverlay.setShadowLayer(6f, 0f, 2f, Color.BLACK)
            tvAppSubtitleOverlay.background = makeRoundedBg(
                ColorUtils.setAlphaComponent(Color.parseColor("#0B0D13"), subtitleBgAlpha),
                8f
            )
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
    private lateinit var watchPlayerView: PlayerView
    private lateinit var emojiFloatingOverlay: FrameLayout
    private lateinit var tvStreamStatusOverlay: TextView
    private lateinit var tvExpirationTimerBadge: TextView

    private lateinit var btnSyncPlayPause: TextView
    private lateinit var btnToggleMic: TextView
    private lateinit var btnToggleSpeaker: TextView
    private lateinit var tvVoiceSpeakingIndicator: TextView

    private lateinit var tabBtnChat: TextView
    private lateinit var tabBtnParticipants: TextView
    private lateinit var tabBtnMediaPicker: TextView
    private lateinit var tabBtnLogsStats: TextView

    private lateinit var panelTabChat: LinearLayout
    private lateinit var panelTabParticipants: LinearLayout
    private lateinit var panelTabMediaPicker: LinearLayout
    private lateinit var panelTabLogsStats: LinearLayout

    private lateinit var rvChatMessages: RecyclerView
    private lateinit var etChatMessageInput: EditText
    private lateinit var rvParticipantsList: RecyclerView
    private lateinit var rvMediaPickerList: RecyclerView
    private lateinit var etSearchMediaInRoom: EditText
    private lateinit var btnSubTabChannels: Button
    private lateinit var btnSubTabMatches: Button
    private lateinit var tvRoomDetailedStats: TextView
    private lateinit var rvRoomEventLogs: RecyclerView

    private lateinit var activeRoomsAdapter: ActiveRoomsAdapter
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var participantsAdapter: ParticipantsAdapter
    private lateinit var mediaPickerAdapter: MediaPickerAdapter
    private lateinit var eventLogsAdapter: EventLogsAdapter

    private val autoHideControlsRunnable = Runnable {
        if (playerEngine.isPlaying() && !isUserSeekingRedBar) {
            hideAppPlayerControlsSmoothly()
        }
    }

    private val syncAndHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            performCloudflareRoomSync()
            updateExpirationCountdownUI()
            mainHandler.postDelayed(this, 4000L)
        }
    }

    fun init() {
        initIdentityAndPreferences()
        bindViews()
        eliminateBottomEmptySpace()
        initializePrimePlayerEngine()
        buildIdenticalAppPlayerOverlayUI()
        setupRecyclerViews()
        setupLobbyListeners()
        setupRoomListeners()
        loadRealAppCatalog()
        fetchActiveRoomsDirectory(cleanOldSelfRooms = true)
        mainHandler.post(syncAndHeartbeatRunnable)
    }

    private fun initIdentityAndPreferences() {
        localUserId = SessionManager.getDeviceId(hostContext)
        localUserName = SessionManager.getUserName(hostContext).let {
            if (it == "Prime User" || it.isBlank()) "مشاهد_${localUserId.takeLast(4)}" else it
        }
        workerBaseUrl = SessionManager.CLOUDFLARE_API_URL
        VoiceSyncHelper.baseUrl = workerBaseUrl
        authToken = sha256("$localUserId:$localUserName:PRIME_OTT_SECRET").take(32)

        val prefs = hostContext.getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
        val blockedSet = prefs.getStringSet("blocked_users", emptySet()) ?: emptySet()
        blockedUserIds.addAll(blockedSet)

        val subPrefs = hostContext.getSharedPreferences("prime_sub_style_prefs", Context.MODE_PRIVATE)
        subtitleTextSizeSp = subPrefs.getFloat("sub_size_sp", 16f).coerceIn(12f, 26f)
        subtitleTextColor = subPrefs.getInt("sub_text_color", Color.WHITE)
        subtitleBgAlpha = subPrefs.getInt("sub_bg_alpha", 0)
        subtitleExtraLiftDp = subPrefs.getInt("sub_extra_lift_dp", 0).coerceIn(-10, 90)
    }

    private fun bindViews() {
        layoutLobbyContainer = rootView.findViewById(R.id.layoutLobbyContainer)
        layoutActiveRoomContainer = rootView.findViewById(R.id.layoutActiveRoomContainer)

        val btnLobbyBack = rootView.findViewById<View>(R.id.btnLobbyBack)
        btnLobbyBack.visibility = if (isStandaloneActivity) View.VISIBLE else View.GONE
        btnLobbyBack.setOnClickListener { hostActivity?.finish() }

        etJoinByCodeInput = rootView.findViewById(R.id.etJoinByCodeInput)
        etRoomSearch = rootView.findViewById(R.id.etRoomSearch)
        tvLobbyStatsSummary = rootView.findViewById(R.id.tvLobbyStatsSummary)
        rvActiveRooms = rootView.findViewById(R.id.rvActiveRooms)
        tvEmptyRoomsHint = rootView.findViewById(R.id.tvEmptyRoomsHint)
        chipFilterAll = rootView.findViewById(R.id.chipFilterAll)
        chipFilterPublic = rootView.findViewById(R.id.chipFilterPublic)
        chipFilterPrivate = rootView.findViewById(R.id.chipFilterPrivate)
        chipFilterChannels = rootView.findViewById(R.id.chipFilterChannels)

        tvRoomHeaderTitle = rootView.findViewById(R.id.tvRoomHeaderTitle)
        tvRoomCodeBadge = rootView.findViewById(R.id.tvRoomCodeBadge)
        tvRoomStatusBadge = rootView.findViewById(R.id.tvRoomStatusBadge)
        tvOnlineCountBadge = rootView.findViewById(R.id.tvOnlineCountBadge)
        btnHostControlsOpen = rootView.findViewById(R.id.btnHostControlsOpen)
        btnCloseRoomHost = rootView.findViewById(R.id.btnCloseRoomHost)
        layoutJoinRequestBanner = rootView.findViewById(R.id.layoutJoinRequestBanner)
        layoutJoinRequestBanner.visibility = View.GONE

        watchPlayerView = rootView.findViewById(R.id.watchPlayerView)
        watchPlayerView.useController = false
        playerFrameContainer = (watchPlayerView.parent as? FrameLayout) ?: FrameLayout(hostContext)

        emojiFloatingOverlay = rootView.findViewById(R.id.emojiFloatingOverlay)
        tvStreamStatusOverlay = rootView.findViewById(R.id.tvStreamStatusOverlay)
        tvStreamStatusOverlay.visibility = View.GONE
        tvExpirationTimerBadge = rootView.findViewById(R.id.tvExpirationTimerBadge)
        tvExpirationTimerBadge.visibility = View.GONE

        btnSyncPlayPause = rootView.findViewById(R.id.btnSyncPlayPause)
        btnToggleMic = rootView.findViewById(R.id.btnToggleMic)
        btnToggleSpeaker = rootView.findViewById(R.id.btnToggleSpeaker)
        tvVoiceSpeakingIndicator = rootView.findViewById(R.id.tvVoiceSpeakingIndicator)

        tabBtnChat = rootView.findViewById(R.id.tabBtnChat)
        tabBtnParticipants = rootView.findViewById(R.id.tabBtnParticipants)
        tabBtnMediaPicker = rootView.findViewById(R.id.tabBtnMediaPicker)
        tabBtnLogsStats = rootView.findViewById(R.id.tabBtnLogsStats)

        panelTabChat = rootView.findViewById(R.id.panelTabChat)
        panelTabParticipants = rootView.findViewById(R.id.panelTabParticipants)
        panelTabMediaPicker = rootView.findViewById(R.id.panelTabMediaPicker)
        panelTabLogsStats = rootView.findViewById(R.id.panelTabLogsStats)

        rvChatMessages = rootView.findViewById(R.id.rvChatMessages)
        etChatMessageInput = rootView.findViewById(R.id.etChatMessageInput)
        rvParticipantsList = rootView.findViewById(R.id.rvParticipantsList)
        rvMediaPickerList = rootView.findViewById(R.id.rvMediaPickerList)
        etSearchMediaInRoom = rootView.findViewById(R.id.etSearchMediaInRoom)
        btnSubTabChannels = rootView.findViewById(R.id.btnSubTabChannels)
        btnSubTabMatches = rootView.findViewById(R.id.btnSubTabMatches)

        btnSubTabChannels.text = "📺 القنوات المباشرة"
        btnSubTabMatches.text = "🎬 الأفلام"
        rootView.findViewById<TextView>(R.id.btnPickMatchOpen)?.text = "🎬 الأفلام"

        tvRoomDetailedStats = rootView.findViewById(R.id.tvRoomDetailedStats)
        rvRoomEventLogs = rootView.findViewById(R.id.rvRoomEventLogs)
    }

    private fun initializePrimePlayerEngine() {
        playerEngine = PrimePlayerEngine(
            context = hostContext,
            scope = controllerScope,
            onSourceSwitched = { idx, src ->
                mainHandler.post {
                    currentProviderIndex = idx
                    val srvLabel = src.label.ifBlank { "REYZO ${idx + 1}" }
                    currentRoom?.streamServerLabel = srvLabel
                    currentRoom?.streamUrl = src.url
                    currentRoom?.streamReferer = src.referer
                    currentRoom?.streamOrigin = src.origin
                    if (this::btnAppServerPill.isInitialized) {
                        btnAppServerPill.text = "  $srvLabel"
                    }
                }
            },
            onRealQualityDetected = { qualityLabel, _ ->
                mainHandler.post {
                    if (this::btnAppQualityPill.isInitialized && selectedQualityHeight == null) {
                        btnAppQualityPill.text = "  $qualityLabel"
                    }
                }
            },
            onFirstFrameRendered = {
                mainHandler.post {
                    setAppPlayerBuffering(false)
                    updateAppPlayPauseIcon(true)
                    scheduleAutoHideControls()
                }
            },
            onPlaybackExhausted = {
                mainHandler.post {
                    val nextIdx = currentProviderIndex + 1
                    if (nextIdx < currentProviderTargets.size) {
                        loadProviderInHeadlessSniffer(nextIdx)
                    } else {
                        setAppPlayerBuffering(false)
                    }
                }
            }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildIdenticalAppPlayerOverlayUI() {
        tvAppSubtitleOverlay = TextView(hostContext).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                setMargins(dp(20), 0, dp(20), dp(16))
            }
        }
        applySubtitleStyleToOverlay()
        playerFrameContainer.addView(tvAppSubtitleOverlay)

        appCustomControlsOverlay = FrameLayout(hostContext).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#B3000000"), Color.parseColor("#33000000"), Color.parseColor("#CC000000"))
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBarRow = LinearLayout(hostContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        tvAppPlayerTitle = TextView(hostContext).apply {
            text = "Spider-Man: Brand New Day"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        topBarRow.addView(tvAppPlayerTitle)

        val makeGlassChip = { radiusDp: Float ->
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(radiusDp)
                setColor(Color.parseColor("#2EFFFFFF"))
                setStroke(max(1, dpF(0.85f).toInt()), Color.parseColor("#4DFFFFFF"))
            }
        }

        btnAppServerPill = TextView(hostContext).apply {
            text = "  REYZO 1"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            background = makeGlassChip(10f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            val srvIcon = VectorIcons.createSmallServerStackIcon(hostContext, Color.WHITE).apply {
                setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            }
            setCompoundDrawables(srvIcon, null, null, null)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32)
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                resetAutoHideControls()
                showAppServersPopup()
            }
        }
        topBarRow.addView(btnAppServerPill)

        btnAppQualityPill = TextView(hostContext).apply {
            text = " 1080p"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = makeGlassChip(10f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32)
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                resetAutoHideControls()
                showAppQualityPopup()
            }
        }
        topBarRow.addView(btnAppQualityPill)

        btnAppSubtitlesCc = ImageView(hostContext).apply {
            background = makeGlassChip(10f)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setImageDrawable(VectorIcons.createSubtitlesCcIcon(hostContext, Color.WHITE))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(6) }
            setOnClickListener {
                resetAutoHideControls()
                showAppSubtitlesDialog()
            }
        }
        topBarRow.addView(btnAppSubtitlesCc)

        btnAppPipIcon = ImageView(hostContext).apply {
            background = makeGlassChip(10f)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setImageDrawable(VectorIcons.createPipMiniPlayerIcon(hostContext, Color.WHITE))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener {
                currentResizeModeIndex = (currentResizeModeIndex + 1) % 3
                watchPlayerView.resizeMode = when (currentResizeModeIndex) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                Toast.makeText(hostContext, "📐 تم تغيير أبعاد الشاشة", Toast.LENGTH_SHORT).show()
            }
        }
        topBarRow.addView(btnAppPipIcon)
        appCustomControlsOverlay.addView(topBarRow)

        val centerRow = LinearLayout(hostContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val makeRedTouchCircle = {
            View(hostContext).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ColorUtils.setAlphaComponent(Color.parseColor("#E50914"), 195))
                }
                alpha = 0f
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }

        btnAppRewind15 = FrameLayout(hostContext).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(28) }
        }
        vAppRewindCircle = makeRedTouchCircle()
        val ivRewind = ImageView(hostContext).apply {
            setImageDrawable(VectorIcons.createCircularSeekArrowIcon(hostContext, isForward = false))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        btnAppRewind15.addView(vAppRewindCircle)
        btnAppRewind15.addView(ivRewind)
        btnAppRewind15.setOnClickListener {
            pulseCircleFeedback(vAppRewindCircle)
            seekRoomPlayerByDeltaMs(-15_000L)
        }
        centerRow.addView(btnAppRewind15)

        btnAppPlayPauseSlot = FrameLayout(hostContext).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        }
        vAppPlayPauseCircle = makeRedTouchCircle()
        ivAppCenterPlayPause = ImageView(hostContext).apply {
            setImageDrawable(VectorIcons.createPlayPauseIcon(hostContext, isPlaying = true))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        pbAppCenterBuffering = ProgressBar(hostContext).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#E50914"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER)
        }
        btnAppPlayPauseSlot.addView(vAppPlayPauseCircle)
        btnAppPlayPauseSlot.addView(ivAppCenterPlayPause)
        btnAppPlayPauseSlot.addView(pbAppCenterBuffering)
        btnAppPlayPauseSlot.setOnClickListener {
            pulseCircleFeedback(vAppPlayPauseCircle)
            toggleSyncPlayPauseAction()
        }
        centerRow.addView(btnAppPlayPauseSlot)

        btnAppForward15 = FrameLayout(hostContext).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(28) }
        }
        vAppForwardCircle = makeRedTouchCircle()
        val ivForward = ImageView(hostContext).apply {
            setImageDrawable(VectorIcons.createCircularSeekArrowIcon(hostContext, isForward = true))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        btnAppForward15.addView(vAppForwardCircle)
        btnAppForward15.addView(ivForward)
        btnAppForward15.setOnClickListener {
            pulseCircleFeedback(vAppForwardCircle)
            seekRoomPlayerByDeltaMs(15_000L)
        }
        centerRow.addView(btnAppForward15)

        appCustomControlsOverlay.addView(centerRow)

        val bottomSeekRow = LinearLayout(hostContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        resetAutoHideControls()
                        val dur = playerEngine.player?.duration ?: 0L
                        if (dur > 0) {
                            tvAppElapsed.text = formatTimeMs((dur * p) / 1000L)
                        }
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {
                    isUserSeekingRedBar = true
                    appCustomControlsOverlay.removeCallbacks(autoHideControlsRunnable)
                }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    isUserSeekingRedBar = false
                    val room = currentRoom
                    val exo = playerEngine.player
                    if (room != null && exo != null && exo.duration > 0) {
                        if (room.hostId == localUserId) {
                            val targetPos = (exo.duration * (s?.progress ?: 0)) / 1000L
                            exo.seekTo(targetPos)
                            room.playbackPositionMs = targetPos
                            broadcastPlaybackSync()
                        } else {
                            Toast.makeText(hostContext, "⚠️ تقديم الفيديو متاح للمضيف فقط", Toast.LENGTH_SHORT).show()
                        }
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        }
        bottomSeekRow.addView(tvAppDuration)

        btnAppFullscreenToggle = ImageView(hostContext).apply {
            setImageDrawable(VectorIcons.createFullscreenIcon(hostContext, isFullscreen = false))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            setOnClickListener {
                toggleAppPlayerFullscreen(!isFullscreenPlayer)
            }
        }
        bottomSeekRow.addView(btnAppFullscreenToggle)

        appCustomControlsOverlay.addView(bottomSeekRow)
        playerFrameContainer.addView(appCustomControlsOverlay)

        val gestureDetector = GestureDetector(hostContext, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (appCustomControlsOverlay.isVisible && appCustomControlsOverlay.alpha > 0.5f) {
                    hideAppPlayerControlsSmoothly()
                } else {
                    showAppPlayerControlsWithFade()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = playerFrameContainer.width.coerceAtLeast(1)
                if (e.x < w * 0.48f) {
                    pulseCircleFeedback(vAppRewindCircle)
                    seekRoomPlayerByDeltaMs(-15_000L)
                } else {
                    pulseCircleFeedback(vAppForwardCircle)
                    seekRoomPlayerByDeltaMs(15_000L)
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

        startAppPlayerProgressAndSubtitleLoop()
    }

    private fun startAppPlayerProgressAndSubtitleLoop() {
        progressTickJob?.cancel()
        progressTickJob = controllerScope.launch {
            while (isActive && !isDestroyed) {
                delay(300)
                val p = playerEngine.player ?: continue

                updateAppPlayPauseIcon(p.isPlaying)
                if (p.playbackState == Player.STATE_BUFFERING) {
                    setAppPlayerBuffering(true)
                } else if (p.playbackState == Player.STATE_READY) {
                    setAppPlayerBuffering(false)
                }

                val pos = p.currentPosition.coerceAtLeast(0L)
                val dur = p.duration

                if (dur > 0 && !isUserSeekingRedBar) {
                    sbAppRedSeekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                    sbAppRedSeekBar.secondaryProgress = (p.bufferedPercentage * 10).coerceIn(0, 1000)
                    tvAppElapsed.text = formatTimeMs(pos)
                    tvAppDuration.text = formatTimeMs(dur)
                }

                if (selectedSubtitleId != "NONE" && activeSubtitleCues.isNotEmpty() && p.isPlaying) {
                    val syncedPos = (pos - subtitleOffsetMs).coerceAtLeast(0L)
                    val cue = activeSubtitleCues.firstOrNull { syncedPos in it.startMs..it.endMs }
                    if (cue != null) {
                        val cleanCueText = cleanAssAndHtmlTags(cue.text)
                        if (tvAppSubtitleOverlay.text.toString() != cleanCueText) {
                            tvAppSubtitleOverlay.text = cleanCueText
                        }
                        if (!tvAppSubtitleOverlay.isVisible) tvAppSubtitleOverlay.visibility = View.VISIBLE
                    } else if (tvAppSubtitleOverlay.isVisible) {
                        tvAppSubtitleOverlay.text = ""
                        tvAppSubtitleOverlay.visibility = View.GONE
                    }
                } else if (selectedSubtitleId == "NONE" && tvAppSubtitleOverlay.isVisible) {
                    tvAppSubtitleOverlay.visibility = View.GONE
                }
            }
        }
    }

    private fun cleanAssAndHtmlTags(raw: String): String {
        return raw
            .replace(Regex("""\{[^}]*\}"""), "")
            .replace(Regex("""[0-9a-zA-Z]*&H[0-9A-Fa-f]+&\\?[a-zA-Z0-9\\&]*\}?"""), "")
            .replace(Regex("""<[^>]*>"""), "")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .trim()
    }

    private fun setAppPlayerBuffering(buffering: Boolean) {
        if (!this::pbAppCenterBuffering.isInitialized) return
        if (buffering) {
            ivAppCenterPlayPause.visibility = View.INVISIBLE
            pbAppCenterBuffering.visibility = View.VISIBLE
        } else {
            pbAppCenterBuffering.visibility = View.GONE
            ivAppCenterPlayPause.visibility = View.VISIBLE
        }
    }

    private fun updateAppPlayPauseIcon(isPlaying: Boolean) {
        if (!this::ivAppCenterPlayPause.isInitialized) return
        ivAppCenterPlayPause.setImageDrawable(
            VectorIcons.createPlayPauseIcon(hostContext, isPlaying = isPlaying)
        )
        btnSyncPlayPause.text = if (isPlaying) "⏸️ إيقاف مؤقت" else "▶️ تشغيل متزامن"
    }

    private fun showAppPlayerControlsWithFade() {
        appCustomControlsOverlay.removeCallbacks(autoHideControlsRunnable)
        appCustomControlsOverlay.animate().cancel()
        if (!appCustomControlsOverlay.isVisible) {
            appCustomControlsOverlay.alpha = 0f
            appCustomControlsOverlay.visibility = View.VISIBLE
        }
        appCustomControlsOverlay.animate()
            .alpha(1f)
            .setDuration(200L)
            .start()
        scheduleAutoHideControls()
    }

    private fun hideAppPlayerControlsSmoothly() {
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

    private fun resetAutoHideControls() {
        appCustomControlsOverlay.removeCallbacks(autoHideControlsRunnable)
        if (!appCustomControlsOverlay.isVisible || appCustomControlsOverlay.alpha < 0.9f) {
            showAppPlayerControlsWithFade()
        } else {
            scheduleAutoHideControls()
        }
    }

    private fun scheduleAutoHideControls() {
        appCustomControlsOverlay.removeCallbacks(autoHideControlsRunnable)
        if (playerEngine.isPlaying() && !isUserSeekingRedBar) {
            appCustomControlsOverlay.postDelayed(autoHideControlsRunnable, 3400L)
        }
    }

    private fun seekRoomPlayerByDeltaMs(deltaMs: Long) {
        val room = currentRoom ?: return
        if (room.hostId != localUserId) {
            Toast.makeText(hostContext, "⚠️ التحكم في الوقت متاح للمضيف فقط", Toast.LENGTH_SHORT).show()
            return
        }
        val exo = playerEngine.player ?: return
        val dur = exo.duration
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        val newPos = if (dur > 0) min(target, dur) else target
        exo.seekTo(newPos)
        room.playbackPositionMs = newPos
        if (dur > 0) {
            sbAppRedSeekBar.progress = ((newPos * 1000L) / dur).toInt().coerceIn(0, 1000)
            tvAppElapsed.text = formatTimeMs(newPos)
        }
        broadcastPlaybackSync()
        resetAutoHideControls()
    }

    private fun toggleAppPlayerFullscreen(enterFullscreen: Boolean) {
        val act = hostActivity ?: return
        isFullscreenPlayer = enterFullscreen
        btnAppFullscreenToggle.setImageDrawable(
            VectorIcons.createFullscreenIcon(hostContext, isFullscreen = enterFullscreen)
        )

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
                lp.height = if (originalPlayerHeightPx > 0) originalPlayerHeightPx else dp(225)
                playerFrameContainer.layoutParams = lp
            }
        }
    }

    private fun showAppServersPopup() {
        val providers = if (currentProviderTargets.isNotEmpty()) {
            currentProviderTargets
        } else {
            playerEngine.getSources()
        }
        if (providers.isEmpty()) {
            Toast.makeText(hostContext, "السيرفر الحالي شغال", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(hostContext)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = dp(275)
        val card = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg(Color.parseColor("#EE10131A"), 18f, Color.parseColor("#33FFFFFF"), 1f)
            setPadding(dp(18), dp(16), dp(18), dp(14))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = TextView(hostContext).apply {
            text = "Servers"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        card.addView(header)

        val scroll = ScrollView(hostContext)
        val listCol = LinearLayout(hostContext).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listCol)
        card.addView(scroll)

        providers.forEachIndexed { idx, srv ->
            val isSel = idx == currentProviderIndex
            val row = LinearLayout(hostContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isSel) makeRoundedBg(Color.parseColor("#26FFFFFF"), 10f, Color.parseColor("#55FFFFFF"), 1f) else null
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    dialog.dismiss()
                    switchServerInRoom(idx, srv)
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

    private fun switchServerInRoom(index: Int, target: StreamSource) {
        val room = currentRoom ?: return
        if (room.hostId != localUserId) {
            Toast.makeText(hostContext, "⚠️ تغيير السيرفر متاح للمضيف فقط", Toast.LENGTH_SHORT).show()
            return
        }
        currentProviderIndex = index
        selectedQualityHeight = null
        btnAppServerPill.text = "  ${target.label.ifBlank { "REYZO ${index + 1}" }}"
        setAppPlayerBuffering(true)

        if (target.isDirectStream || isLikelyDirectStreamUrl(target.url)) {
            playStreamSourceInEngine(target, room.streamTitle, seekMs = playerEngine.player?.currentPosition ?: 0L)
            broadcastPlaybackSync()
        } else {
            loadProviderInHeadlessSniffer(index)
        }
    }

    private fun showAppQualityPopup() {
        val dialog = Dialog(hostContext)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = dp(260)
        val card = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg(Color.parseColor("#EE10131A"), 18f, Color.parseColor("#33FFFFFF"), 1f)
            setPadding(dp(18), dp(16), dp(18), dp(14))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = TextView(hostContext).apply {
            text = "Quality"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(10))
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
            background = if (isAutoSelected) makeRoundedBg(Color.parseColor("#26FFFFFF"), 10f, Color.parseColor("#55FFFFFF"), 1f) else null
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                selectedQualityHeight = null
                playerEngine.selectVideoQualityHeight(null)
                btnAppQualityPill.text = "  $detectedBadge"
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
                background = if (isSel) makeRoundedBg(Color.parseColor("#26FFFFFF"), 10f, Color.parseColor("#55FFFFFF"), 1f) else null
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    selectedQualityHeight = qTrack.height
                    playerEngine.selectVideoQualityHeight(qTrack.height)
                    btnAppQualityPill.text = "  ${qTrack.height}p"
                    dialog.dismiss()
                }
            }
            card.addView(row)
        }

        dialog.setContentView(card)
        dialog.show()
        dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showAppSubtitlesDialog() {
        val dialog = Dialog(hostContext)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val screenW = hostContext.resources.displayMetrics.widthPixels
        val dialogWidthPx = min((screenW * 0.88f).toInt(), dp(360))

        val root = LayoutInflater.from(hostContext)
            .inflate(R.layout.dialog_subtitles_sheet, null, false) as LinearLayout
        root.background = makeRoundedBg(Color.parseColor("#F0101218"), 20f, Color.parseColor("#33FFFFFF"), 1f)

        val tabSwitchBox = root.findViewById<LinearLayout>(R.id.tabSwitchBox)
        tabSwitchBox.background = makeRoundedBg(Color.parseColor("#14FFFFFF"), 12f, Color.parseColor("#22FFFFFF"), 1f)

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

        tabTracksBtn.background = makeRoundedBg(Color.WHITE, 10f)
        btnClosePopup.background = makeRoundedBg(Color.parseColor("#1FFFFFFF"), 16f)
        btnClosePopup.setOnClickListener { dialog.dismiss() }
        btnUploadSrt.visibility = View.GONE

        val isOff = selectedSubtitleId == "NONE"
        btnDisableSubs.setTextColor(if (isOff) Color.parseColor("#0E1015") else Color.WHITE)
        btnDisableSubs.background = makeRoundedBg(
            if (isOff) Color.WHITE else Color.parseColor("#1AFFFFFF"),
            10f,
            Color.parseColor("#33FFFFFF"),
            0.8f
        )
        btnDisableSubs.setOnClickListener {
            selectedSubtitleId = "NONE"
            tvAppSubtitleOverlay.visibility = View.GONE
            dialog.dismiss()
        }

        tabTracksBtn.setOnClickListener {
            tracksPanel.visibility = View.VISIBLE
            settingsPanel.visibility = View.GONE
            tabTracksBtn.background = makeRoundedBg(Color.WHITE, 10f)
            tabTracksBtn.setTextColor(Color.parseColor("#0E1015"))
            tabSettingsBtn.background = null
            tabSettingsBtn.setTextColor(Color.parseColor("#B0BAC9"))
        }
        tabSettingsBtn.setOnClickListener {
            tracksPanel.visibility = View.GONE
            settingsPanel.visibility = View.VISIBLE
            tabSettingsBtn.background = makeRoundedBg(Color.WHITE, 10f)
            tabSettingsBtn.setTextColor(Color.parseColor("#0E1015"))
            tabTracksBtn.background = null
            tabTracksBtn.setTextColor(Color.parseColor("#B0BAC9"))
        }

        fun renderTracksList() {
            filterRow.removeAllViews()
            tracksContainer.removeAllViews()
            if (availableSubtitles.isEmpty()) {
                val emptyTv = TextView(hostContext).apply {
                    text = "لا توجد ترجمات خارجية متاحة حالياً"
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(24), dp(12), dp(24))
                }
                tracksContainer.addView(emptyTv)
            } else {
                availableSubtitles.forEach { track ->
                    val isSelected = track.id == selectedSubtitleId
                    val itemLayout = LinearLayout(hostContext).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = if (isSelected) makeRoundedBg(Color.parseColor("#26FFFFFF"), 10f, Color.parseColor("#55FFFFFF"), 1f) else null
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        setOnClickListener {
                            selectedSubtitleId = track.id
                            subtitleOffsetMs = 0L
                            controllerScope.launch {
                                val parsed = withContext(Dispatchers.IO) {
                                    downloadAndParseSubtitleDirect(track.url)
                                }
                                if (parsed.isNotEmpty()) {
                                    activeSubtitleCues = parsed
                                    tvAppSubtitleOverlay.visibility = View.VISIBLE
                                }
                            }
                            dialog.dismiss()
                        }
                    }
                    val langTv = TextView(hostContext).apply {
                        text = track.languageLabel.ifBlank { track.languageCode }
                        setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#D0D7E2"))
                        textSize = 13.5f
                        setTypeface(typeface, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    itemLayout.addView(langTv)
                    tracksContainer.addView(itemLayout)
                }
            }
        }

        btnRefreshSubs.setOnClickListener {
            val tmdbId = activeMovieTmdbId.ifEmpty { currentRoom?.streamId ?: "" }
            if (tmdbId.isNotEmpty()) {
                controllerScope.launch {
                    val subs = withContext(Dispatchers.IO) {
                        fetchSubtitlesDirectFromApi(tmdbId)
                    }
                    availableSubtitles = subs
                    renderTracksList()
                }
            }
        }

        renderTracksList()
        if (availableSubtitles.isEmpty()) {
            btnRefreshSubs.performClick()
        }

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(dialogWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun fetchSubtitlesDirectFromApi(cleanTmdbId: String): List<SubtitleTrack> {
        val list = mutableListOf<SubtitleTrack>()
        try {
            val apiUrl = "${SessionManager.CLOUDFLARE_API_URL}/api/v2/subtitles?tmdb_id=$cleanTmdbId&type=movie"
            val req = Request.Builder().url(apiUrl).header("User-Agent", SessionManager.CUSTOM_USER_AGENT).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val bodyStr = resp.body?.string()?.trim() ?: return@use
                val arr = if (bodyStr.startsWith("[")) {
                    org.json.JSONArray(bodyStr)
                } else {
                    JSONObject(bodyStr).optJSONArray("subtitles") ?: org.json.JSONArray()
                }
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val subUrl = o.optString("url", "")
                    if (subUrl.isBlank()) continue
                    val langCode = o.optString("language", o.optString("lang", "ar"))
                    val langLabel = o.optString("display", o.optString("label", langCode))
                    list.add(
                        SubtitleTrack(
                            id = o.optString("id", "sub_$i"),
                            languageCode = langCode,
                            languageLabel = langLabel,
                            releaseName = o.optString("release", ""),
                            url = subUrl
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun downloadAndParseSubtitleDirect(url: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        try {
            val req = Request.Builder().url(url).header("User-Agent", SessionManager.CUSTOM_USER_AGENT).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val text = resp.body?.string() ?: return emptyList()
                val lines = text.lines()
                var i = 0
                val timeRegex = Regex("""(\d{1,2}:\d{2}:\d{2}[,.]\d{3}|\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{3}|\d{2}:\d{2}[,.]\d{3})""")
                while (i < lines.size) {
                    val line = lines[i].trim()
                    val match = timeRegex.find(line)
                    if (match != null) {
                        val startMs = parseSubTimeMs(match.groupValues[1])
                        val endMs = parseSubTimeMs(match.groupValues[2])
                        i++
                        val sb = StringBuilder()
                        while (i < lines.size && lines[i].trim().isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append(lines[i].trim())
                            i++
                        }
                        if (sb.isNotEmpty() && endMs > startMs) {
                            cues.add(SubtitleCue(startMs, endMs, cleanAssAndHtmlTags(sb.toString())))
                        }
                    } else {
                        i++
                    }
                }
            }
        } catch (_: Exception) {}
        return cues
    }

    private fun parseSubTimeMs(raw: String): Long {
        val norm = raw.trim().replace(',', '.')
        val parts = norm.split(":")
        return try {
            if (parts.size == 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val secParts = parts[2].split(".")
                val s = secParts[0].toLong()
                val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                (h * 3600_000L) + (m * 60_000L) + (s * 1000L) + ms
            } else if (parts.size == 2) {
                val m = parts[0].toLong()
                val secParts = parts[1].split(".")
                val s = secParts[0].toLong()
                val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                (m * 60_000L) + (s * 1000L) + ms
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun loadSubtitlesForMovieAutomatically(tmdbId: String) {
        val cleanId = tmdbId.replace("tmdb_movie_", "").replace("tmdb_tv_", "").replace("tmdb_", "").trim()
        if (cleanId.isEmpty() || cleanId == "0") return
        activeMovieTmdbId = cleanId

        controllerScope.launch {
            val subs: List<SubtitleTrack> = withContext(Dispatchers.IO) {
                fetchSubtitlesDirectFromApi(cleanId)
            }
            availableSubtitles = subs
            val preferredArabic = subs.firstOrNull { track ->
                track.languageCode.startsWith("ar", ignoreCase = true) ||
                track.languageLabel.contains("Arab", ignoreCase = true) ||
                track.languageLabel.contains("عرب", ignoreCase = true)
            } ?: subs.firstOrNull()

            if (preferredArabic != null) {
                val cues: List<SubtitleCue> = withContext(Dispatchers.IO) {
                    downloadAndParseSubtitleDirect(preferredArabic.url)
                }
                if (cues.isNotEmpty()) {
                    selectedSubtitleId = preferredArabic.id
                    activeSubtitleCues = cues
                    tvAppSubtitleOverlay.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun eliminateBottomEmptySpace() {
        val bottomPad = if (isStandaloneActivity) dp(6) else dp(56)
        rootView.setPadding(rootView.paddingLeft, rootView.paddingTop, rootView.paddingRight, 0)
        layoutActiveRoomContainer.setPadding(
            layoutActiveRoomContainer.paddingLeft,
            layoutActiveRoomContainer.paddingTop,
            layoutActiveRoomContainer.paddingRight,
            bottomPad
        )
        (layoutActiveRoomContainer.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0

        val panels = listOf(panelTabChat, panelTabMediaPicker, panelTabParticipants, panelTabLogsStats)
        for (p in panels) {
            p.setPadding(p.paddingLeft, p.paddingTop, p.paddingRight, dp(4))
            (p.layoutParams as? LinearLayout.LayoutParams)?.apply {
                height = 0
                weight = 1f
                bottomMargin = 0
            }
        }
        (panelTabChat.parent as? View)?.let { parentContainer ->
            parentContainer.setPadding(parentContainer.paddingLeft, parentContainer.paddingTop, parentContainer.paddingRight, 0)
            (parentContainer.layoutParams as? LinearLayout.LayoutParams)?.apply {
                height = 0
                weight = 1f
                bottomMargin = 0
            }
        }
        (rvChatMessages.layoutParams as? LinearLayout.LayoutParams)?.apply {
            height = 0
            weight = 1f
            bottomMargin = dp(4)
        }
    }

    private fun setupRecyclerViews() {
        activeRoomsAdapter = ActiveRoomsAdapter(filteredRoomsList) { room -> attemptJoinRoom(room) }
        rvActiveRooms.layoutManager = LinearLayoutManager(hostContext)
        rvActiveRooms.adapter = activeRoomsAdapter

        chatAdapter = ChatAdapter(chatHistory) { msg -> showUserInteractionDialog(msg.senderId, msg.senderName) }
        rvChatMessages.layoutManager = LinearLayoutManager(hostContext)
        rvChatMessages.adapter = chatAdapter

        participantsAdapter = ParticipantsAdapter(
            getList = { participantsMap.values.sortedByDescending { it.role == "HOST" } }
        ) { participant -> showParticipantManagementDialog(participant) }
        rvParticipantsList.layoutManager = LinearLayoutManager(hostContext)
        rvParticipantsList.adapter = participantsAdapter

        mediaPickerAdapter = MediaPickerAdapter(displayedMediaList) { mediaOption -> selectMediaForRoom(mediaOption) }
        updateMediaPickerLayoutManager()
        rvMediaPickerList.adapter = mediaPickerAdapter

        eventLogsAdapter = EventLogsAdapter(eventLogs)
        rvRoomEventLogs.layoutManager = LinearLayoutManager(hostContext)
        rvRoomEventLogs.adapter = eventLogsAdapter
    }

    private fun updateMediaPickerLayoutManager() {
        if (isShowingMoviesSubTab) {
            val currentLm = rvMediaPickerList.layoutManager
            if (currentLm !is GridLayoutManager || currentLm.spanCount != 3) {
                rvMediaPickerList.layoutManager = GridLayoutManager(hostContext, 3)
            }
        } else {
            val currentLm = rvMediaPickerList.layoutManager
            if (currentLm is GridLayoutManager || currentLm == null) {
                rvMediaPickerList.layoutManager = LinearLayoutManager(hostContext)
            }
        }
    }

    private fun showMoviesGridModalDialog() {
        val dialog = Dialog(hostContext, android.R.style.Theme_Black_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val screenHeight = hostContext.resources.displayMetrics.heightPixels

        val modalRoot = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10131C"))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.parseColor("#2A3042"))
            }
        }

        val headerRow = LinearLayout(hostContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val tvHeader = TextView(hostContext).apply {
            text = "Search Movies"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSwitchToChannels = TextView(hostContext).apply {
            text = "📺 القنوات"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#262B3A"))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
            setOnClickListener {
                dialog.dismiss()
                isShowingMoviesSubTab = false
                refreshMediaPickerList()
                switchRoomInternalTab("MEDIA")
            }
        }
        val btnClose = TextView(hostContext).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#262B3A"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(tvHeader)
        headerRow.addView(btnSwitchToChannels)
        headerRow.addView(btnClose)
        modalRoot.addView(headerRow)

        val etSearchModal = EditText(hostContext).apply {
            hint = "🔍  ابحث عن أي فيلم..."
            setHintTextColor(Color.parseColor("#8C96A8"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181C28"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#32394E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        modalRoot.addView(etSearchModal)

        val modalMoviesList = mutableListOf<MediaStreamOption>().apply { addAll(allMoviesList) }
        lateinit var modalAdapter: MediaPickerAdapter
        modalAdapter = MediaPickerAdapter(modalMoviesList) { chosen ->
            dialog.dismiss()
            selectMediaForRoom(chosen)
        }

        val rvModalGrid = RecyclerView(hostContext).apply {
            layoutManager = GridLayoutManager(hostContext, 3)
            adapter = modalAdapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (screenHeight * 0.68).toInt()
            )
        }
        modalRoot.addView(rvModalGrid)

        val footerRow = LinearLayout(hostContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val btnCancel = Button(hostContext).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#212634"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) }
            setOnClickListener { dialog.dismiss() }
        }
        val btnSearch = Button(hostContext).apply {
            text = "Search"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E50914"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) }
        }
        footerRow.addView(btnCancel)
        footerRow.addView(btnSearch)
        modalRoot.addView(footerRow)

        var searchJob: Job? = null
        val triggerModalSearch = { queryRaw: String ->
            val q = queryRaw.trim()
            searchJob?.cancel()
            if (q.isEmpty()) {
                modalMoviesList.clear()
                modalMoviesList.addAll(allMoviesList)
                modalAdapter.notifyDataSetChanged()
            } else {
                val localFiltered = allMoviesList.filter { it.title.contains(q, ignoreCase = true) }
                modalMoviesList.clear()
                modalMoviesList.addAll(localFiltered)
                modalAdapter.notifyDataSetChanged()

                if (q.length >= 2) {
                    searchJob = controllerScope.launch {
                        delay(220)
                        val tmdbRes = withContext(Dispatchers.IO) {
                            OttRepository.searchTmdbCatalog(q, "MOVIES")
                        }
                        if (tmdbRes.isNotEmpty()) {
                            modalMoviesList.clear()
                            for (vod in tmdbRes) {
                                modalMoviesList.add(
                                    MediaStreamOption(
                                        id = vod.id,
                                        title = vod.title,
                                        subtitle = "🎬 فيلم (${vod.year}) • ⭐ ${vod.rating}",
                                        streamUrl = "",
                                        posterUrl = vod.posterUrl,
                                        rating = vod.rating.ifBlank { "7.5" },
                                        year = vod.year,
                                        isMovie = true
                                    )
                                )
                            }
                            modalAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }

        etSearchModal.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                triggerModalSearch(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSearch.setOnClickListener { triggerModalSearch(etSearchModal.text?.toString() ?: "") }

        dialog.setContentView(modalRoot)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val width = (hostContext.resources.displayMetrics.widthPixels * 0.96).toInt()
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun setupLobbyListeners() {
        rootView.findViewById<View>(R.id.btnBlockedUsersLobby).setOnClickListener { showBlockedUsersDialog() }
        rootView.findViewById<View>(R.id.btnCreateRoomOpen).setOnClickListener { showCreateRoomDialog() }
        rootView.findViewById<View>(R.id.btnRefreshRooms).setOnClickListener {
            Toast.makeText(hostContext, "🔄 جاري تحديث الغرف النشطة...", Toast.LENGTH_SHORT).show()
            fetchActiveRoomsDirectory(cleanOldSelfRooms = false)
        }

        rootView.findViewById<View>(R.id.btnJoinByCodeSubmit).setOnClickListener {
            val raw = etJoinByCodeInput.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(hostContext, "🔑 أدخل رمز الغرفة أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            joinRoomByCodeOrLink(raw)
        }

        etRoomSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { applyRoomSearchAndFilters() }
            override fun afterTextChanged(s: Editable?) {}
        })

        val filterClick = View.OnClickListener { v ->
            currentLobbyFilter = when (v.id) {
                R.id.chipFilterPublic -> "PUBLIC"
                R.id.chipFilterPrivate -> "PRIVATE"
                R.id.chipFilterChannels -> "CHANNELS"
                else -> "ALL"
            }
            updateFilterChipsUI()
            applyRoomSearchAndFilters()
        }
        chipFilterAll.setOnClickListener(filterClick)
        chipFilterPublic.setOnClickListener(filterClick)
        chipFilterPrivate.setOnClickListener(filterClick)
        chipFilterChannels.setOnClickListener(filterClick)
    }

    private fun updateFilterChipsUI() {
        val chips = listOf(
            chipFilterAll to "ALL",
            chipFilterPublic to "PUBLIC",
            chipFilterPrivate to "PRIVATE",
            chipFilterChannels to "CHANNELS"
        )
        for ((chip, key) in chips) {
            if (currentLobbyFilter == key) {
                chip.setBackgroundResource(R.drawable.wt_bg_badge_live)
                chip.setTextColor(Color.WHITE)
            } else {
                chip.setBackgroundResource(R.drawable.wt_bg_badge_dark)
                chip.setTextColor(Color.parseColor("#D0D7E2"))
            }
        }
    }

    private fun applyRoomSearchAndFilters() {
        val query = etRoomSearch.text.toString().trim().lowercase(Locale.getDefault())
        filteredRoomsList.clear()

        for (room in activeRoomsList) {
            val matchesFilter = when (currentLobbyFilter) {
                "PUBLIC" -> !room.isPrivate
                "PRIVATE" -> room.isPrivate
                "CHANNELS" -> !room.isMovie
                else -> true
            }
            if (!matchesFilter) continue

            val matchesQuery = query.isEmpty() ||
                room.roomName.lowercase(Locale.getDefault()).contains(query) ||
                room.hostName.lowercase(Locale.getDefault()).contains(query) ||
                room.streamTitle.lowercase(Locale.getDefault()).contains(query) ||
                room.roomCode.lowercase(Locale.getDefault()).contains(query)

            if (matchesQuery) filteredRoomsList.add(room)
        }
        activeRoomsAdapter.notifyDataSetChanged()
        tvEmptyRoomsHint.visibility = if (filteredRoomsList.isEmpty()) View.VISIBLE else View.GONE
        val totalViewers = filteredRoomsList.sumOf { it.onlineCount }
        tvLobbyStatsSummary.text = "${filteredRoomsList.size} غرف نشطة • $totalViewers متصل"
    }

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun showCreateRoomDialog() {
        val container = ScrollView(hostContext)
        val layout = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 32, 44, 20)
            setBackgroundColor(Color.parseColor("#111726"))
        }
        container.addView(layout)

        val etName = EditText(hostContext).apply {
            hint = "اسم الغرفة"
            setText("غرفة $localUserName")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        layout.addView(etName)

        val rgPrivacy = RadioGroup(hostContext).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 16, 0, 8)
        }
        val rbPublic = RadioButton(hostContext).apply {
            text = "🌐 غرفة عامة"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        val rbPrivate = RadioButton(hostContext).apply {
            text = "🔒 غرفة خاصة"
            setTextColor(Color.WHITE)
        }
        rgPrivacy.addView(rbPublic)
        rgPrivacy.addView(rbPrivate)
        layout.addView(rgPrivacy)

        val etPassword = EditText(hostContext).apply {
            hint = "كلمة مرور الغرفة الخاصة"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            visibility = View.GONE
        }
        layout.addView(etPassword)

        rgPrivacy.setOnCheckedChangeListener { _, checkedId ->
            etPassword.visibility = if (checkedId == rbPrivate.id) View.VISIBLE else View.GONE
        }

        AlertDialog.Builder(hostContext)
            .setTitle("➕ إنشاء غرفة مشاهدة جديدة")
            .setView(container)
            .setPositiveButton("فتح الغرفة الآن") { _, _ ->
                val title = etName.text.toString().trim().ifEmpty { "غرفة $localUserName" }
                val isPriv = rbPrivate.isChecked
                val rawPass = etPassword.text.toString().trim()

                controllerScope.launch {
                    val (ok, serverCode) = OttRepository.createWatchRoom(
                        deviceId = localUserId,
                        userName = localUserName,
                        roomName = title,
                        roomIcon = "🎬",
                        isPrivate = isPriv,
                        password = rawPass,
                        allowGuestsControl = true,
                        contentType = "VOD",
                        streamId = "0",
                        contentTitle = "اختر فيلماً أو قناة للبدء",
                        contentPoster = "",
                        streamUrl = ""
                    )

                    val finalCode = if (ok && serverCode.isNotEmpty()) serverCode else generateRoomCode()
                    val newRoom = WatchRoomModel(
                        roomId = finalCode,
                        roomCode = finalCode,
                        roomName = title,
                        hostId = localUserId,
                        hostName = localUserName,
                        isPrivate = isPriv,
                        passwordHash = if (isPriv && rawPass.isNotEmpty()) sha256(rawPass) else "",
                        onlineCount = 1,
                        status = "LIVE",
                        streamUrl = "",
                        streamTitle = "اختر فيلماً أو قناة للبدء",
                        isPlaying = false
                    )
                    enterWatchRoom(newRoom, isHost = true)
                    showMoviesGridModalDialog()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun joinRoomByCodeOrLink(input: String, password: String = "") {
        var extractedCode = input.uppercase(Locale.US)
        if (input.contains("code=")) {
            extractedCode = Uri.parse(input).getQueryParameter("code")?.uppercase(Locale.US) ?: extractedCode
        } else if (input.contains("room=")) {
            extractedCode = Uri.parse(input).getQueryParameter("room") ?: extractedCode
        }
        extractedCode = extractedCode.replace("#", "").trim()

        Toast.makeText(hostContext, "🔍 جاري الاتصال بالغرفة #$extractedCode...", Toast.LENGTH_SHORT).show()

        controllerScope.launch {
            val res = OttRepository.joinWatchRoom(
                codeOrId = extractedCode,
                deviceId = localUserId,
                userName = localUserName,
                password = password
            )
            if (res.status == "success" && res.room != null) {
                val r = res.room
                val (parsedUrl, parsedRef) = unpackUrlAndReferer(r.streamUrl)
                val model = WatchRoomModel(
                    roomId = r.roomId.ifEmpty { r.roomCode },
                    roomCode = r.roomCode.ifEmpty { extractedCode },
                    roomName = r.roomName,
                    hostId = if (r.isHost) localUserId else "HOST_${r.roomCode}",
                    hostName = r.hostName,
                    isPrivate = r.isPrivate,
                    onlineCount = res.participants.size.coerceAtLeast(1),
                    status = if (r.isPlaying) "LIVE" else "PAUSED",
                    streamId = r.streamId,
                    streamUrl = parsedUrl,
                    streamReferer = parsedRef,
                    streamTitle = r.contentTitle.ifEmpty { "بث مشترك" },
                    isMovie = r.contentType == "VOD",
                    isPlaying = r.isPlaying,
                    playbackPositionMs = r.playbackPositionMs
                )
                enterWatchRoom(model, isHost = r.isHost)
                applyServerSyncResult(res)
            } else if (res.requirePassword) {
                promptServerRoomPassword(extractedCode)
            } else {
                Toast.makeText(hostContext, "❌ لم يتم العثور على الغرفة", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun promptServerRoomPassword(roomCode: String) {
        val input = EditText(hostContext).apply {
            hint = "أدخل كلمة مرور الغرفة..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(hostContext)
            .setTitle("🔒 غرفة خاصة محمية")
            .setView(input)
            .setPositiveButton("انضمام") { _, _ ->
                joinRoomByCodeOrLink(roomCode, input.text.toString().trim())
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun attemptJoinRoom(room: WatchRoomModel) {
        joinRoomByCodeOrLink(room.roomCode)
    }

    fun enterWatchRoom(room: WatchRoomModel, isHost: Boolean) {
        currentRoom = room
        lastSyncedMessageId = 0L
        layoutLobbyContainer.visibility = View.GONE
        layoutActiveRoomContainer.visibility = View.VISIBLE
        eliminateBottomEmptySpace()

        chatHistory.clear()
        chatAdapter.notifyDataSetChanged()
        eventLogs.clear()
        participantsMap.clear()

        if (isHost) {
            room.hostId = localUserId
            room.hostName = localUserName
            participantsMap[localUserId] = ParticipantModel(localUserId, localUserName, "HOST", isMuted = true)
        } else {
            participantsMap[room.hostId] = ParticipantModel(room.hostId, room.hostName, "HOST", isMuted = true)
            participantsMap[localUserId] = ParticipantModel(localUserId, localUserName, "PARTICIPANT", isMuted = true)
        }

        appendSystemChatAndLog("🎬 مرحباً بك في «${room.roomName}» | الرمز: #${room.roomCode}")
        updateRoomHeaderAndPermissionsUI()
        switchRoomInternalTab("CHAT")

        tvAppPlayerTitle.text = room.streamTitle

        if (room.streamUrl.isNotBlank()) {
            val initialSource = StreamSource(
                label = room.streamServerLabel.ifBlank { "REYZO 1" },
                url = room.streamUrl,
                referer = room.streamReferer,
                origin = room.streamOrigin.ifBlank { extractOriginFromUrl(room.streamReferer.ifBlank { room.streamUrl }) },
                userAgent = room.streamUserAgent,
                isDirectStream = true
            )
            playStreamSourceInEngine(initialSource, room.streamTitle, room.playbackPositionMs)
            if (room.streamId.isNotBlank()) {
                loadSubtitlesForMovieAutomatically(room.streamId)
                fetchBackupServersInBackground(room.streamId, room.streamTitle)
            }
        } else {
            playerEngine.pauseAndReleaseSocket()
            setAppPlayerBuffering(false)
        }

        connectRoomWebSocket(room.roomCode)
        initVoiceEngineForRoom(room.roomCode)
        updateDetailedStatsUI()
    }

    private fun fetchBackupServersInBackground(tmdbId: String, title: String) {
        val cleanId = tmdbId.replace("tmdb_movie_", "").replace("tmdb_tv_", "").replace("tmdb_", "").trim()
        if (cleanId.isEmpty() || cleanId == "0") return
        controllerScope.launch {
            val servers = withContext(Dispatchers.IO) {
                PrimeVodExtractor.fetchPanelConfiguredServers(
                    tmdbId = cleanId,
                    imdbId = "",
                    title = title,
                    isSeries = false,
                    season = 1,
                    episode = 1
                )
            }
            if (servers.isNotEmpty()) {
                currentProviderTargets.clear()
                currentProviderTargets.addAll(servers)
                val firstName = servers.first().label.ifBlank { "REYZO 1" }
                btnAppServerPill.text = "  $firstName"
            }
        }
    }

    private fun updateRoomHeaderAndPermissionsUI() {
        val room = currentRoom ?: return
        val isHost = room.hostId == localUserId
        val privacyIcon = if (room.isPrivate) "🔒" else "🌐"

        tvRoomHeaderTitle.text = "$privacyIcon ${room.roomName} • 👑 ${room.hostName}"
        tvRoomCodeBadge.text = "#${room.roomCode} 📋"
        tvOnlineCountBadge.text = "👥 ${participantsMap.size}/${room.maxParticipants}"

        tvRoomStatusBadge.text = when (room.status) {
            "PAUSED" -> "⏸️ متوقف"
            "BUFFERING" -> "⏳ مزامنة..."
            else -> "🔴 LIVE"
        }

        btnHostControlsOpen.visibility = if (isHost) View.VISIBLE else View.GONE
        btnCloseRoomHost.visibility = if (isHost) View.VISIBLE else View.GONE
        participantsAdapter.notifyDataSetChanged()
    }

    private fun playStreamSourceInEngine(source: StreamSource, title: String, seekMs: Long = 0L) {
        val (cleanUrl, embeddedRef) = unpackUrlAndReferer(source.url.trim())
        if (cleanUrl.isEmpty()) return

        val finalRef = source.referer.ifBlank { embeddedRef.ifBlank { extractOriginFromUrl(cleanUrl) } }
        val finalOrigin = source.origin.ifBlank { extractOriginFromUrl(finalRef).ifBlank { extractOriginFromUrl(cleanUrl) } }

        val readySource = source.copy(
            url = cleanUrl,
            referer = finalRef,
            origin = finalOrigin,
            userAgent = source.userAgent.ifBlank { SessionManager.CUSTOM_USER_AGENT },
            isDirectStream = true
        )

        tvAppPlayerTitle.text = title
        btnAppServerPill.text = "  ${readySource.label.ifBlank { "REYZO 1" }}"
        setAppPlayerBuffering(true)
        showAppPlayerControlsWithFade()

        val allToPlay = mutableListOf(readySource)
        resolvedDirectStreams.forEach { s ->
            if (s.url != readySource.url) allToPlay.add(s)
        }

        playerEngine.playSources(
            playerView = watchPlayerView,
            sources = allToPlay,
            startIndex = 0,
            startPositionMs = seekMs
        )
    }

    private fun extractOriginFromUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun packUrlAndReferer(url: String, referer: String): String {
        val clean = url.substringBefore("|Referer=")
        return if (referer.isNotBlank()) "$clean|Referer=$referer" else clean
    }

    private fun unpackUrlAndReferer(raw: String): Pair<String, String> {
        if (raw.contains("|Referer=")) {
            val parts = raw.split("|Referer=", limit = 2)
            return Pair(parts[0].trim(), parts.getOrNull(1)?.trim() ?: "")
        }
        return Pair(raw.trim(), "")
    }

    private fun loadRealAppCatalog() {
        controllerScope.launch {
            val homeCatalog = withContext(Dispatchers.IO) {
                OttRepository.fetchHomeSections()
            }

            allMoviesList.clear()
            val moviesCombined = (
                homeCatalog.trendingToday +
                homeCatalog.popularMovies +
                homeCatalog.topRatedMovies +
                homeCatalog.actionMovies
            ).filter { !it.isSeries }.distinctBy { it.id }

            for (vod in moviesCombined) {
                allMoviesList.add(
                    MediaStreamOption(
                        id = vod.id,
                        title = vod.title,
                        subtitle = "🎬 فيلم (${vod.year}) • ⭐ ${vod.rating}",
                        streamUrl = "",
                        posterUrl = vod.posterUrl,
                        rating = vod.rating.ifBlank { "7.5" },
                        year = vod.year,
                        isMovie = true
                    )
                )
            }
            refreshMediaPickerList()

            val channels = withContext(Dispatchers.IO) {
                OttRepository.fetchChannelsByCategory("ALL")
            }
            allChannelsList.clear()
            for (ch in channels) {
                val url = ch.streamUrls.firstOrNull() ?: ch.namedSources.firstOrNull()?.url ?: continue
                allChannelsList.add(
                    MediaStreamOption(
                        id = ch.id,
                        title = ch.name,
                        subtitle = "📺 قناة بث مباشر",
                        streamUrl = url,
                        isMovie = false
                    )
                )
            }
            refreshMediaPickerList()
        }
    }

    private var inlineSearchJob: Job? = null

    private fun refreshMediaPickerList() {
        updateMediaPickerLayoutManager()
        val query = etSearchMediaInRoom.text.toString().trim()
        val lowerQuery = query.lowercase(Locale.getDefault())
        val source = if (isShowingMoviesSubTab) allMoviesList else allChannelsList

        btnSubTabMatches.setBackgroundResource(if (isShowingMoviesSubTab) R.drawable.wt_bg_btn_primary else R.drawable.wt_bg_badge_dark)
        btnSubTabMatches.setTextColor(Color.WHITE)

        btnSubTabChannels.setBackgroundResource(if (!isShowingMoviesSubTab) R.drawable.wt_bg_btn_primary else R.drawable.wt_bg_badge_dark)
        btnSubTabChannels.setTextColor(Color.WHITE)

        displayedMediaList.clear()
        for (item in source) {
            if (lowerQuery.isEmpty() ||
                item.title.lowercase(Locale.getDefault()).contains(lowerQuery) ||
                item.subtitle.lowercase(Locale.getDefault()).contains(lowerQuery)
            ) {
                displayedMediaList.add(item)
            }
        }
        mediaPickerAdapter.notifyDataSetChanged()

        if (isShowingMoviesSubTab && query.length >= 2) {
            inlineSearchJob?.cancel()
            inlineSearchJob = controllerScope.launch {
                delay(240)
                val results = withContext(Dispatchers.IO) {
                    OttRepository.searchTmdbCatalog(query, "MOVIES")
                }
                if (etSearchMediaInRoom.text.toString().trim().equals(query, true) && results.isNotEmpty()) {
                    displayedMediaList.clear()
                    for (vod in results) {
                        displayedMediaList.add(
                            MediaStreamOption(
                                id = vod.id,
                                title = vod.title,
                                subtitle = "🎬 فيلم (${vod.year}) • ⭐ ${vod.rating}",
                                streamUrl = "",
                                posterUrl = vod.posterUrl,
                                rating = vod.rating.ifBlank { "7.5" },
                                year = vod.year,
                                isMovie = true
                            )
                        )
                    }
                    mediaPickerAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun selectMediaForRoom(media: MediaStreamOption) {
        val room = currentRoom ?: return
        val role = participantsMap[localUserId]?.role ?: "PARTICIPANT"
        if (room.hostId != localUserId && role != "MODERATOR") {
            Toast.makeText(hostContext, "⚠️ تغيير المحتوى متاح للمضيف فقط", Toast.LENGTH_SHORT).show()
            return
        }

        switchRoomInternalTab("CHAT")

        playerEngine.pauseAndReleaseSocket()
        tvAppPlayerTitle.text = media.title
        tvAppElapsed.text = "00:00"
        tvAppDuration.text = "00:00"
        sbAppRedSeekBar.progress = 0
        tvAppSubtitleOverlay.text = ""
        tvAppSubtitleOverlay.visibility = View.GONE
        activeSubtitleCues = emptyList()
        availableSubtitles = emptyList()
        setAppPlayerBuffering(true)
        showAppPlayerControlsWithFade()

        room.streamTitle = media.title
        room.streamId = media.id
        room.streamUrl = ""
        room.playbackPositionMs = 0L

        if (media.isMovie && media.streamUrl.isEmpty()) {
            extractAndPlayMovieIdenticalToApp(media)
        } else {
            isExtractingNewMovie = false
            val chSource = StreamSource(
                label = "LIVE TV",
                url = media.streamUrl,
                referer = media.referer,
                isDirectStream = true
            )
            applyResolvedStreamSourceToRoom(chSource, media.title, isMovie = false)
        }
    }

    private fun isLikelyDirectStreamUrl(rawUrl: String): Boolean {
        val u = rawUrl.lowercase(Locale.US)
        if (u.contains(".js") || u.contains(".css") || u.contains(".png") ||
            u.contains(".jpg") || u.contains(".webp") || u.contains(".svg") ||
            u.contains(".woff") || u.contains("doubleclick") || u.contains("googlesyndication")
        ) return false

        return u.contains(".m3u8") ||
            u.contains(".mp4") ||
            u.contains("/playlist/") ||
            u.contains("/hls/") ||
            u.contains("master.txt") ||
            u.contains("mime=video")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun extractAndPlayMovieIdenticalToApp(media: MediaStreamOption) {
        isExtractingNewMovie = true
        snifferExtractionJob?.cancel()
        cleanupHeadlessSniffer()
        currentProviderTargets.clear()
        resolvedDirectStreams.clear()
        currentProviderIndex = 0

        val cleanTmdbId = media.id
            .replace("tmdb_movie_", "")
            .replace("tmdb_tv_", "")
            .replace("tmdb_", "")
            .trim()

        loadSubtitlesForMovieAutomatically(cleanTmdbId)

        var hasDispatchedFastest = false

        snifferExtractionJob = controllerScope.launch {
            val configured = PrimeVodExtractor.fetchPanelConfiguredServers(
                tmdbId = cleanTmdbId,
                imdbId = "",
                title = media.title,
                isSeries = false,
                season = 1,
                episode = 1,
                onFastestDirectReady = { fastestStream ->
                    mainHandler.post {
                        if (!isDestroyed && !hasDispatchedFastest) {
                            hasDispatchedFastest = true
                            resolvedDirectStreams.add(fastestStream)
                            applyResolvedStreamSourceToRoom(fastestStream, media.title, isMovie = true)
                        }
                    }
                }
            )

            currentProviderTargets.clear()
            currentProviderTargets.addAll(configured)

            if (configured.isNotEmpty()) {
                btnAppServerPill.text = "  ${configured.first().label.ifBlank { "REYZO 1" }}"
            }

            if (hasDispatchedFastest) {
                val backupDirects = configured.filter { it.isDirectStream }
                playerEngine.appendBackupSourcesSilently(backupDirects)
                return@launch
            }

            val firstDirectIdx = configured.indexOfFirst { it.isDirectStream && it.url.isNotBlank() }
            if (firstDirectIdx != -1) {
                currentProviderIndex = firstDirectIdx
                val directSrv = configured[firstDirectIdx]
                resolvedDirectStreams.add(directSrv)
                applyResolvedStreamSourceToRoom(directSrv, media.title, isMovie = true)
                return@launch
            }

            if (configured.isNotEmpty()) {
                loadProviderInHeadlessSniffer(0)
            } else {
                isExtractingNewMovie = false
                setAppPlayerBuffering(false)
                Toast.makeText(hostContext, "⚠️ لا توجد سيرفرات مفعلة لهذا الفيلم حالياً", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun loadProviderInHeadlessSniffer(index: Int) {
        if (isDestroyed || currentProviderTargets.isEmpty()) return
        currentProviderIndex = index.coerceIn(0, currentProviderTargets.lastIndex)
        val target = currentProviderTargets[currentProviderIndex]

        btnAppServerPill.text = "  ${target.label.ifBlank { "REYZO ${currentProviderIndex + 1}" }}"
        setAppPlayerBuffering(true)

        if (target.isDirectStream || isLikelyDirectStreamUrl(target.url)) {
            resolvedDirectStreams.add(target)
            applyResolvedStreamSourceToRoom(target, currentRoom?.streamTitle ?: "", isMovie = true)
            return
        }

        cleanupHeadlessSniffer()

        var streamCapturedForThisTarget = false
        val wv = WebView(hostContext)
        headlessSnifferWebView = wv

        if (rootView is ViewGroup) {
            val hiddenParams = FrameLayout.LayoutParams(dp(320), dp(180)).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            wv.alpha = 0.01f
            rootView.addView(wv, 0, hiddenParams)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = target.userAgent.ifBlank {
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }
        }

        val onStreamSniffed = { rawUrl: String, refererHeader: String ->
            if (!streamCapturedForThisTarget && !isDestroyed && isLikelyDirectStreamUrl(rawUrl)) {
                streamCapturedForThisTarget = true
                val originHost = extractOriginFromUrl(refererHeader.ifBlank { target.url })
                val capturedSource = StreamSource(
                    label = target.label.ifBlank { "REYZO ${currentProviderIndex + 1}" },
                    url = rawUrl,
                    referer = refererHeader.ifBlank { target.url },
                    origin = originHost,
                    userAgent = wv.settings.userAgentString ?: SessionManager.CUSTOM_USER_AGENT,
                    isDirectStream = true
                )
                mainHandler.post {
                    cleanupHeadlessSniffer()
                    resolvedDirectStreams.add(0, capturedSource)
                    applyResolvedStreamSourceToRoom(capturedSource, currentRoom?.streamTitle ?: "", isMovie = true)
                }
            }
        }

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun processVideo(url: String) {
                onStreamSniffed(url, target.url)
            }
        }, "ateb")

        val hookJs = """
            (function() {
                try {
                    const origOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        if (typeof url === 'string' && (url.includes('.m3u8') || url.includes('.mp4') || url.includes('/playlist/'))) {
                            window.ateb.processVideo(url);
                        }
                        return origOpen.apply(this, arguments);
                    };
                    const origFetch = window.fetch;
                    window.fetch = function(input) {
                        const u = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                        if (u && (u.includes('.m3u8') || u.includes('.mp4') || u.includes('/playlist/'))) {
                            window.ateb.processVideo(u);
                        }
                        return origFetch.apply(this, arguments);
                    };
                    var btns = document.querySelectorAll('.play, .vjs-big-play-button, .jw-icon-display, button, [aria-label*="Play"], #play, .play-btn, #player');
                    for (var i = 0; i < btns.length; i++) { btns[i].click(); }
                    var vids = document.querySelectorAll('video');
                    for (var j = 0; j < vids.length; j++) {
                        vids[j].muted = true;
                        vids[j].play();
                        if (vids[j].src && vids[j].src.startsWith('http')) {
                            window.ateb.processVideo(vids[j].src);
                        }
                    }
                    if (document.body) document.body.click();
                } catch(e) {}
            })();
        """.trimIndent()

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!streamCapturedForThisTarget && !isDestroyed) {
                    view?.evaluateJavascript(hookJs, null)
                    mainHandler.postDelayed({ if (!streamCapturedForThisTarget && !isDestroyed) view?.evaluateJavascript(hookJs, null) }, 1200L)
                    mainHandler.postDelayed({ if (!streamCapturedForThisTarget && !isDestroyed) view?.evaluateJavascript(hookJs, null) }, 2600L)
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                if (!streamCapturedForThisTarget && isLikelyDirectStreamUrl(reqUrl)) {
                    val reqRef = request.requestHeaders["Referer"] ?: target.url
                    onStreamSniffed(reqUrl, reqRef)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        val headers = HashMap<String, String>()
        if (target.referer.isNotEmpty()) {
            headers["Referer"] = target.referer
        } else {
            headers["Referer"] = extractOriginFromUrl(target.url)
        }
        wv.loadUrl(target.url, headers)

        snifferTimeoutRunnable = Runnable {
            if (!streamCapturedForThisTarget && !isDestroyed) {
                if (currentProviderIndex + 1 < currentProviderTargets.size) {
                    loadProviderInHeadlessSniffer(currentProviderIndex + 1)
                } else {
                    isExtractingNewMovie = false
                    setAppPlayerBuffering(false)
                }
            }
        }
        mainHandler.postDelayed(snifferTimeoutRunnable!!, 7000L)
    }

    private fun cleanupHeadlessSniffer() {
        snifferTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        snifferTimeoutRunnable = null
        try {
            headlessSnifferWebView?.let { wv ->
                wv.stopLoading()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        } catch (_: Exception) {}
        headlessSnifferWebView = null
    }

    private fun applyResolvedStreamSourceToRoom(
        source: StreamSource,
        title: String,
        isMovie: Boolean
    ) {
        val room = currentRoom ?: return
        isExtractingNewMovie = false
        room.streamUrl = source.url
        room.streamReferer = source.referer
        room.streamOrigin = source.origin
        room.streamServerLabel = source.label.ifBlank { "REYZO 1" }
        room.streamUserAgent = source.userAgent.ifBlank { SessionManager.CUSTOM_USER_AGENT }
        room.streamTitle = title
        room.isMovie = isMovie
        room.isPlaying = true
        room.playbackPositionMs = 0L
        room.status = "LIVE"

        switchRoomInternalTab("CHAT")
        playStreamSourceInEngine(source, title, seekMs = 0L)
        broadcastPlaybackSync()
        appendSystemChatAndLog("🎬 تم تشغيل: $title (${room.streamServerLabel})")
        updateRoomHeaderAndPermissionsUI()
    }

    private fun toggleSyncPlayPauseAction() {
        val room = currentRoom ?: return
        if (room.hostId != localUserId) {
            Toast.makeText(hostContext, "⚠️ التحكم في البث متاح للمضيف فقط", Toast.LENGTH_SHORT).show()
            return
        }
        val exo = playerEngine.player ?: return
        if (exo.isPlaying) {
            playerEngine.pause()
            room.isPlaying = false
            room.status = "PAUSED"
            updateAppPlayPauseIcon(false)
        } else {
            playerEngine.resume()
            room.isPlaying = true
            room.status = "LIVE"
            updateAppPlayPauseIcon(true)
        }
        room.playbackPositionMs = exo.currentPosition
        broadcastPlaybackSync()
        updateRoomHeaderAndPermissionsUI()
    }

    private fun setupRoomListeners() {
        rootView.findViewById<View>(R.id.btnLeaveRoom).setOnClickListener { confirmLeaveRoom() }
        btnCloseRoomHost.setOnClickListener { confirmCloseRoomByHost() }
        btnHostControlsOpen.setOnClickListener { showHostControlsCenterDialog() }
        tvRoomCodeBadge.setOnClickListener { copyInviteCodeAndLink(shareSheet = false) }
        rootView.findViewById<View>(R.id.btnInviteShare).setOnClickListener { copyInviteCodeAndLink(shareSheet = true) }
        tvOnlineCountBadge.setOnClickListener { switchRoomInternalTab("PARTICIPANTS") }

        btnSyncPlayPause.setOnClickListener { toggleSyncPlayPauseAction() }

        rootView.findViewById<View>(R.id.btnForceSyncNow).setOnClickListener {
            val room = currentRoom ?: return@setOnClickListener
            if (room.hostId == localUserId) {
                broadcastPlaybackSync()
                Toast.makeText(hostContext, "✅ تمت مزامنة المشغل لدى الجميع", Toast.LENGTH_SHORT).show()
            } else {
                val src = StreamSource(
                    label = room.streamServerLabel,
                    url = room.streamUrl,
                    referer = room.streamReferer,
                    origin = room.streamOrigin,
                    isDirectStream = true
                )
                playStreamSourceInEngine(src, room.streamTitle, room.playbackPositionMs)
                Toast.makeText(hostContext, "🔄 تمت المزامنة مع المضيف", Toast.LENGTH_SHORT).show()
            }
        }

        rootView.findViewById<View>(R.id.btnPickChannelOpen).setOnClickListener {
            isShowingMoviesSubTab = false
            refreshMediaPickerList()
            switchRoomInternalTab("MEDIA")
        }

        rootView.findViewById<View>(R.id.btnPickMatchOpen).setOnClickListener {
            showMoviesGridModalDialog()
        }

        rootView.findViewById<View>(R.id.btnAspectRatioToggle).setOnClickListener {
            currentResizeModeIndex = (currentResizeModeIndex + 1) % 3
            watchPlayerView.resizeMode = when (currentResizeModeIndex) {
                1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            Toast.makeText(hostContext, "📐 تم تغيير أبعاد العرض", Toast.LENGTH_SHORT).show()
        }

        rootView.findViewById<View>(R.id.btnRoomStatsOpen).setOnClickListener { switchRoomInternalTab("LOGS") }

        btnToggleMic.setOnClickListener { toggleMicrophoneState() }
        btnToggleSpeaker.setOnClickListener {
            val vm = voiceChatManager ?: return@setOnClickListener
            val newMuted = !vm.isSpeakerMuted()
            vm.setSpeakerMuted(newMuted)
            btnToggleSpeaker.text = if (newMuted) "🔇" else "🔊"
        }

        val emojiMap = mapOf(
            R.id.btnEmojiFire to "🔥",
            R.id.btnEmojiHeart to "❤️",
            R.id.btnEmojiLaugh to "😂",
            R.id.btnEmojiClap to "👏",
            R.id.btnEmojiSoccer to "🍿",
            R.id.btnEmojiGoal to "🎬",
            R.id.btnEmojiShock to "😱",
            R.id.btnEmojiParty to "🎉"
        )
        for ((viewId, emojiStr) in emojiMap) {
            rootView.findViewById<View>(viewId).setOnClickListener { sendEmojiReaction(emojiStr) }
        }

        tabBtnChat.setOnClickListener { switchRoomInternalTab("CHAT") }
        tabBtnParticipants.setOnClickListener { switchRoomInternalTab("PARTICIPANTS") }
        tabBtnMediaPicker.setOnClickListener { showMoviesGridModalDialog() }
        tabBtnLogsStats.setOnClickListener { switchRoomInternalTab("LOGS") }

        rootView.findViewById<View>(R.id.btnSendChatMessage).setOnClickListener { sendChatMessage() }

        rootView.findViewById<View>(R.id.btnMuteAllParticipants).setOnClickListener {
            val room = currentRoom ?: return@setOnClickListener
            if (room.hostId != localUserId) return@setOnClickListener
            controllerScope.launch {
                OttRepository.manageWatchRoom(room.roomId, localUserId, "MUTE_ALL")
            }
            broadcastRoomEvent("MUTE_ALL", JSONObject())
            appendSystemChatAndLog("🔇 قام المضيف بكتم الميكروفون للجميع")
        }

        btnSubTabChannels.setOnClickListener {
            isShowingMoviesSubTab = false
            refreshMediaPickerList()
        }
        btnSubTabMatches.setOnClickListener {
            showMoviesGridModalDialog()
        }
        rootView.findViewById<View>(R.id.btnCustomStreamUrl).setOnClickListener { promptCustomStreamUrlDialog() }

        etSearchMediaInRoom.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshMediaPickerList() }
            override fun afterTextChanged(s: Editable?) {}
        })

        rootView.findViewById<View>(R.id.btnExtendRoomTime).setOnClickListener {
            val room = currentRoom ?: return@setOnClickListener
            if (room.hostId != localUserId) return@setOnClickListener
            room.expiresAt += 3600_000L
            updateExpirationCountdownUI()
            appendSystemChatAndLog("⏱️ تم تمديد وقت الغرفة ساعة إضافية")
        }
        rootView.findViewById<View>(R.id.btnClearEventLogs).setOnClickListener {
            eventLogs.clear()
            eventLogsAdapter.notifyDataSetChanged()
        }
    }

    private fun switchRoomInternalTab(tab: String) {
        panelTabMediaPicker.visibility = if (tab == "MEDIA") View.VISIBLE else View.GONE
        panelTabChat.visibility = if (tab == "CHAT") View.VISIBLE else View.GONE
        panelTabParticipants.visibility = if (tab == "PARTICIPANTS") View.VISIBLE else View.GONE
        panelTabLogsStats.visibility = if (tab == "LOGS") View.VISIBLE else View.GONE

        val tabs = listOf(
            tabBtnMediaPicker to "MEDIA",
            tabBtnChat to "CHAT",
            tabBtnParticipants to "PARTICIPANTS",
            tabBtnLogsStats to "LOGS"
        )
        for ((btn, key) in tabs) {
            if (tab == key) {
                btn.setBackgroundResource(R.drawable.wt_bg_badge_live)
                btn.setTextColor(Color.WHITE)
            } else {
                btn.background = null
                btn.setTextColor(Color.parseColor("#9AA5B8"))
            }
        }
    }

    private fun sendChatMessage() {
        val room = currentRoom ?: return
        val text = etChatMessageInput.text.toString().trim()
        if (text.isEmpty()) return
        etChatMessageInput.setText("")

        val role = if (room.hostId == localUserId) "HOST" else "PARTICIPANT"
        val msg = ChatMessageModel("MSG-" + System.currentTimeMillis(), localUserId, localUserName, role, text)
        appendChatMessage(msg)

        controllerScope.launch {
            OttRepository.sendRoomMessage(room.roomId, localUserId, text, "TEXT")
        }

        val payload = JSONObject().apply {
            put("msgId", msg.msgId)
            put("senderId", msg.senderId)
            put("senderName", msg.senderName)
            put("senderRole", msg.senderRole)
            put("text", msg.text)
        }
        broadcastRoomEvent("CHAT_MSG", payload)
    }

    private fun appendChatMessage(msg: ChatMessageModel) {
        if (blockedUserIds.contains(msg.senderId)) return
        if (chatHistory.any { it.msgId == msg.msgId }) return
        chatHistory.add(msg)
        if (!msg.isSystemEvent) statTotalMessages++
        chatAdapter.notifyItemInserted(chatHistory.size - 1)
        rvChatMessages.scrollToPosition(chatHistory.size - 1)
    }

    private fun appendSystemChatAndLog(text: String) {
        val sysMsg = ChatMessageModel("SYS-" + System.nanoTime(), "SYSTEM", "النظام", "SYSTEM", text, isSystemEvent = true)
        chatHistory.add(sysMsg)
        chatAdapter.notifyItemInserted(chatHistory.size - 1)
        rvChatMessages.scrollToPosition(chatHistory.size - 1)
        eventLogs.add(0, RoomEventLogItem(System.currentTimeMillis(), "EVENT", text))
        if (this::eventLogsAdapter.isInitialized) eventLogsAdapter.notifyDataSetChanged()
    }

    private fun sendEmojiReaction(emoji: String) {
        val room = currentRoom ?: return
        spawnFloatingEmoji(emoji)
        controllerScope.launch {
            OttRepository.sendRoomMessage(room.roomId, localUserId, emoji, "REACTION")
        }
        broadcastRoomEvent("EMOJI", JSONObject().put("emoji", emoji))
    }

    private fun spawnFloatingEmoji(emoji: String) {
        val tv = TextView(hostContext).apply {
            text = emoji
            textSize = 24f
            setTextColor(Color.WHITE)
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            marginStart = Random.nextInt(30, (emojiFloatingOverlay.width.coerceAtLeast(260) - 60).coerceAtLeast(80))
            bottomMargin = 14
        }
        emojiFloatingOverlay.addView(tv, params)
        val animY = ObjectAnimator.ofFloat(tv, "translationY", 0f, -380f).apply { duration = 1900L }
        val animAlpha = ObjectAnimator.ofFloat(tv, "alpha", 1f, 0f).apply { duration = 1900L }
        animY.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { emojiFloatingOverlay.removeView(tv) }
        })
        animY.start()
        animAlpha.start()
    }

    private fun initVoiceEngineForRoom(roomCode: String) {
        voiceChatManager?.release()
        voiceChatManager = VoiceChatManager(
            context = hostContext,
            roomId = roomCode,
            userId = localUserId,
            userName = localUserName
        ) { isSpeaking, _ ->
            mainHandler.post {
                participantsMap[localUserId]?.isSpeaking = isSpeaking
                tvVoiceSpeakingIndicator.text = if (isSpeaking) "🟢 يتحدث..." else "صوت نقي"
            }
        }
        if (ContextCompat.checkSelfPermission(hostContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceChatManager?.startVoiceChat()
        }
    }

    private fun toggleMicrophoneState() {
        val vm = voiceChatManager ?: return
        if (ContextCompat.checkSelfPermission(hostContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            hostActivity?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.RECORD_AUDIO), 901) }
            return
        }
        vm.startVoiceChat()
        val newMuted = !vm.isMuted()
        vm.setMuted(newMuted)
        participantsMap[localUserId]?.isMuted = newMuted
        if (newMuted) {
            btnToggleMic.text = "🎤 مكتوم"
            btnToggleMic.setTextColor(Color.parseColor("#FF8A80"))
        } else {
            btnToggleMic.text = "🎙️ متصل"
            btnToggleMic.setTextColor(Color.parseColor("#00E676"))
        }
        participantsAdapter.notifyDataSetChanged()
    }

    private fun broadcastPlaybackSync() {
        val room = currentRoom ?: return
        if (isExtractingNewMovie || room.streamUrl.isBlank()) return
        val player = playerEngine.player
        statSyncCount++
        val packedStream = packUrlAndReferer(room.streamUrl, room.streamReferer)
        val payload = JSONObject().apply {
            put("stream_url", packedStream)
            put("content_title", room.streamTitle)
            put("is_playing", player?.isPlaying ?: room.isPlaying)
            put("playback_position_ms", player?.currentPosition ?: room.playbackPositionMs)
        }
        controllerScope.launch {
            OttRepository.syncWatchRoom(
                roomId = room.roomId,
                deviceId = localUserId,
                lastMessageId = lastSyncedMessageId,
                isMuted = voiceChatManager?.isMuted() ?: true,
                inVoice = true,
                playbackUpdate = payload
            )
        }
        broadcastRoomEvent("PLAYBACK_SYNC", payload)
        updateDetailedStatsUI()
    }

    private fun performCloudflareRoomSync() {
        val room = currentRoom ?: return
        if (isExtractingNewMovie) return

        controllerScope.launch {
            val playbackObj = if (room.hostId == localUserId && room.streamUrl.isNotBlank()) {
                JSONObject().apply {
                    put("stream_url", packUrlAndReferer(room.streamUrl, room.streamReferer))
                    put("content_title", room.streamTitle)
                    put("is_playing", playerEngine.isPlaying())
                    put("playback_position_ms", playerEngine.player?.currentPosition ?: 0L)
                }
            } else null

            val res = OttRepository.syncWatchRoom(
                roomId = room.roomId,
                deviceId = localUserId,
                lastMessageId = lastSyncedMessageId,
                isMuted = voiceChatManager?.isMuted() ?: true,
                inVoice = true,
                playbackUpdate = playbackObj
            )
            if (res.status == "success") {
                applyServerSyncResult(res)
            }
        }
    }

    private fun applyServerSyncResult(res: com.primeott.app.data.repository.RoomSyncResult) {
        val room = currentRoom ?: return
        val srvRoom = res.room ?: return

        if (res.participants.isNotEmpty()) {
            participantsMap.clear()
            for (p in res.participants) {
                participantsMap[p.deviceId] = ParticipantModel(
                    userId = p.deviceId,
                    userName = p.userName,
                    role = p.role,
                    isMuted = p.isMuted
                )
            }
            room.onlineCount = participantsMap.size
            statPeakViewers = statPeakViewers.coerceAtLeast(room.onlineCount)
        }

        for (m in res.messages) {
            if (m.id > lastSyncedMessageId) lastSyncedMessageId = m.id
            if (m.senderId == localUserId) continue
            if (m.messageType == "REACTION") {
                spawnFloatingEmoji(m.content)
            } else {
                appendChatMessage(
                    ChatMessageModel(
                        msgId = "SRV_${m.id}",
                        senderId = m.senderId,
                        senderName = m.senderName,
                        senderRole = "PARTICIPANT",
                        text = m.content,
                        timestamp = if (m.createdAt > 0) m.createdAt else System.currentTimeMillis()
                    )
                )
            }
        }

        if (room.hostId != localUserId && !isExtractingNewMovie) {
            isHandlingRemoteSync = true
            try {
                val (srvCleanUrl, srvRef) = unpackUrlAndReferer(srvRoom.streamUrl)
                if (srvCleanUrl.isNotEmpty() && srvCleanUrl != room.streamUrl) {
                    room.streamUrl = srvCleanUrl
                    room.streamReferer = srvRef
                    room.streamTitle = srvRoom.contentTitle.ifEmpty { room.streamTitle }
                    val guestSource = StreamSource(
                        label = room.streamServerLabel,
                        url = room.streamUrl,
                        referer = room.streamReferer,
                        isDirectStream = true
                    )
                    playStreamSourceInEngine(guestSource, room.streamTitle, srvRoom.playbackPositionMs)
                } else {
                    playerEngine.player?.let { player ->
                        if (player.isPlaying != srvRoom.isPlaying) {
                            player.playWhenReady = srvRoom.isPlaying
                        }
                        if (srvRoom.playbackPositionMs > 0 && abs(player.currentPosition - srvRoom.playbackPositionMs) > 3500L) {
                            player.seekTo(srvRoom.playbackPositionMs)
                        }
                    }
                }
            } finally {
                mainHandler.postDelayed({ isHandlingRemoteSync = false }, 350L)
            }
        }

        updateRoomHeaderAndPermissionsUI()
        updateDetailedStatsUI()
    }

    private fun fetchActiveRoomsDirectory(cleanOldSelfRooms: Boolean = false) {
        controllerScope.launch {
            val rooms = OttRepository.fetchActiveWatchRooms()
            activeRoomsList.clear()

            for (r in rooms) {
                val rId = r.roomId.ifEmpty { r.roomCode }
                val isMyAbandonedRoom = cleanOldSelfRooms &&
                    currentRoom == null &&
                    (r.hostName == localUserName || r.hostName == "Prime User") &&
                    r.participantCount <= 1

                if (isMyAbandonedRoom) {
                    launch { OttRepository.manageWatchRoom(rId, localUserId, "CLOSE_ROOM") }
                    continue
                }

                activeRoomsList.add(
                    WatchRoomModel(
                        roomId = rId,
                        roomCode = r.roomCode,
                        roomName = r.roomName,
                        hostId = "",
                        hostName = r.hostName,
                        isPrivate = r.isPrivate,
                        onlineCount = r.participantCount.coerceAtLeast(1),
                        status = "LIVE",
                        streamUrl = "",
                        streamTitle = r.contentTitle.ifEmpty { "بث مشترك" },
                        isMovie = r.contentType == "VOD"
                    )
                )
            }
            applyRoomSearchAndFilters()
        }
    }

    private fun promptCustomStreamUrlDialog() {
        val room = currentRoom ?: return
        if (room.hostId != localUserId) return
        val layout = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val etTitle = EditText(hostContext).apply { hint = "اسم الفيلم أو القناة" }
        val etUrl = EditText(hostContext).apply { hint = "رابط البث المباشر (m3u8 / mp4)" }
        layout.addView(etTitle)
        layout.addView(etUrl)
        AlertDialog.Builder(hostContext)
            .setTitle("🔗 تشغيل رابط بث خارجي")
            .setView(layout)
            .setPositiveButton("تشغيل ومزامنة") { _, _ ->
                val url = etUrl.text.toString().trim()
                val title = etTitle.text.toString().trim().ifEmpty { "بث خارجي" }
                if (url.isNotEmpty()) {
                    val customSrc = StreamSource(label = "CUSTOM", url = url, isDirectStream = true)
                    applyResolvedStreamSourceToRoom(customSrc, title, isMovie = true)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showParticipantManagementDialog(target: ParticipantModel) {
        val room = currentRoom ?: return
        if (target.userId == localUserId) return
        val isMeHost = room.hostId == localUserId
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val isLocallyMuted = voiceChatManager?.isParticipantMutedLocally(target.userId) == true
        options.add(if (isLocallyMuted) "🔊 إلغاء الكتم المحلي" else "🔇 كتم صوت هذا العضو لدي")
        actions.add {
            voiceChatManager?.setParticipantMutedLocally(target.userId, !isLocallyMuted)
        }

        if (isMeHost) {
            options.add("👢 طرد من الغرفة (Kick)")
            actions.add {
                controllerScope.launch {
                    OttRepository.manageWatchRoom(room.roomId, localUserId, "KICK", target.userId)
                }
                participantsMap.remove(target.userId)
                updateRoomHeaderAndPermissionsUI()
            }
        }

        AlertDialog.Builder(hostContext)
            .setTitle("إدارة العضو: ${target.userName}")
            .setItems(options.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showUserInteractionDialog(targetId: String, targetName: String) {
        if (targetId == "SYSTEM" || targetId == localUserId) return
        val existing = participantsMap[targetId] ?: ParticipantModel(targetId, targetName)
        showParticipantManagementDialog(existing)
    }

    private fun showBlockedUsersDialog() {
        if (blockedUserIds.isEmpty()) {
            Toast.makeText(hostContext, "قائمة المحظورين فارغة", Toast.LENGTH_SHORT).show()
            return
        }
        val arr = blockedUserIds.toTypedArray()
        AlertDialog.Builder(hostContext)
            .setTitle("🚫 المحظورون (اضغط لإلغاء الحظر)")
            .setItems(arr) { _, which ->
                blockedUserIds.remove(arr[which])
                Toast.makeText(hostContext, "✅ تم إلغاء الحظر", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showHostControlsCenterDialog() {
        val room = currentRoom ?: return
        if (room.hostId != localUserId) return
        val items = arrayOf(
            if (room.isChatLocked) "🔓 فتح الدردشة الكتابية" else "🔒 قفل الدردشة الكتابية",
            if (room.isPrivate) "🌐 تحويل الغرفة إلى عامة" else "🔒 تحويل الغرفة إلى خاصة"
        )
        AlertDialog.Builder(hostContext)
            .setTitle("⚙️ إعدادات المضيف")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> room.isChatLocked = !room.isChatLocked
                    1 -> {
                        room.isPrivate = !room.isPrivate
                        updateRoomHeaderAndPermissionsUI()
                    }
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun copyInviteCodeAndLink(shareSheet: Boolean) {
        val room = currentRoom ?: return
        val inviteText = "🎬 انضم لغرفة «${room.roomName}» لمشاهدة «${room.streamTitle}»! رمز الغرفة: ${room.roomCode}"
        val clipboard = hostContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("WatchTogetherInvite", room.roomCode))
        Toast.makeText(hostContext, "📋 تم نسخ رمز الغرفة (#${room.roomCode})", Toast.LENGTH_SHORT).show()
        if (shareSheet) {
            runCatching {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, inviteText)
                }
                hostContext.startActivity(Intent.createChooser(shareIntent, "مشاركة دعوة الغرفة:"))
            }
        }
    }

    fun confirmLeaveRoom() {
        if (isFullscreenPlayer) {
            toggleAppPlayerFullscreen(false)
            return
        }
        val room = currentRoom ?: return
        val isHost = room.hostId == localUserId
        AlertDialog.Builder(hostContext)
            .setTitle("🚪 مغادرة الغرفة")
            .setMessage(if (isHost) "هل تريد مغادرة وإغلاق غرفة «${room.roomName}»؟" else "هل تريد مغادرة غرفة «${room.roomName}»؟")
            .setPositiveButton("مغادرة") { _, _ -> leaveCurrentRoom(closePermanently = isHost) }
            .setNegativeButton("البقاء", null)
            .show()
    }

    private fun confirmCloseRoomByHost() {
        val room = currentRoom ?: return
        if (room.hostId != localUserId) return
        AlertDialog.Builder(hostContext)
            .setTitle("⛔ إغلاق الغرفة نهائياً")
            .setMessage("سيتم إغلاق الغرفة لجميع المشاركين.")
            .setPositiveButton("إغلاق الغرفة") { _, _ -> leaveCurrentRoom(closePermanently = true) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun leaveCurrentRoom(closePermanently: Boolean) {
        if (isFullscreenPlayer) toggleAppPlayerFullscreen(false)
        val room = currentRoom
        if (room != null) {
            val action = if (closePermanently || room.hostId == localUserId) "CLOSE_ROOM" else "LEAVE"
            controllerScope.launch {
                OttRepository.manageWatchRoom(room.roomId, localUserId, action)
                fetchActiveRoomsDirectory(cleanOldSelfRooms = false)
            }
        }
        cleanupHeadlessSniffer()
        playerEngine.pauseAndReleaseSocket()
        voiceChatManager?.stopVoiceChat()
        try { webSocket?.close(1000, "Leave") } catch (_: Exception) {}
        webSocket = null
        currentRoom = null
        layoutActiveRoomContainer.visibility = View.GONE
        layoutLobbyContainer.visibility = View.VISIBLE
    }

    private fun connectRoomWebSocket(roomId: String) {
        try { webSocket?.cancel() } catch (_: Exception) {}
        val wsUrl = workerBaseUrl.replace("https://", "wss://").replace("http://", "ws://") +
            "/ws/room?roomId=$roomId&userId=$localUserId&token=$authToken"
        val req = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                mainHandler.post { handleIncomingRealtimeEvent(text) }
            }
        })
    }

    private fun broadcastRoomEvent(type: String, payload: JSONObject) {
        val room = currentRoom ?: return
        val envelope = JSONObject().apply {
            put("type", type)
            put("roomId", room.roomId)
            put("senderId", localUserId)
            put("senderName", localUserName)
            put("data", payload)
        }
        try { webSocket?.send(envelope.toString()) } catch (_: Exception) {}
    }

    private fun handleIncomingRealtimeEvent(rawJson: String) {
        try {
            val env = JSONObject(rawJson)
            val type = env.optString("type")
            val senderId = env.optString("senderId")
            val senderName = env.optString("senderName", "مشاهد")
            val data = env.optJSONObject("data") ?: JSONObject()
            if (senderId == localUserId || blockedUserIds.contains(senderId)) return

            when (type) {
                "CHAT_MSG" -> appendChatMessage(
                    ChatMessageModel(
                        data.optString("msgId", "WS_${System.nanoTime()}"),
                        senderId,
                        senderName,
                        data.optString("senderRole", "PARTICIPANT"),
                        data.optString("text")
                    )
                )
                "EMOJI" -> spawnFloatingEmoji(data.optString("emoji", "🔥"))
            }
        } catch (_: Exception) {}
    }

    private fun updateExpirationCountdownUI() {
        val room = currentRoom ?: return
        val rem = (room.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val hrs = TimeUnit.MILLISECONDS.toHours(rem)
        val mins = TimeUnit.MILLISECONDS.toMinutes(rem) % 60
        val secs = TimeUnit.MILLISECONDS.toSeconds(rem) % 60
        tvExpirationTimerBadge.text = String.format(Locale.US, "⏳ %02d:%02d:%02d", hrs, mins, secs)
    }

    private fun updateDetailedStatsUI() {
        val room = currentRoom ?: return
        val up = ((System.currentTimeMillis() - room.createdAt) / 60_000L).coerceAtLeast(1)
        tvRoomDetailedStats.text = "📊 الغرفة: #${room.roomCode} | المتصلون: ${participantsMap.size} | الذروة: $statPeakViewers | الرسائل: $statTotalMessages | المزامنة: $statSyncCount | مدة النشاط: $up د"
    }

    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        if (data != null) {
            val code = data.getQueryParameter("code") ?: data.getQueryParameter("room")
            if (!code.isNullOrEmpty()) {
                joinRoomByCodeOrLink(code)
                return
            }
        }

        val passedUrl = intent.getStringExtra(WatchRoomActivity.EXTRA_STREAM_URL)
            ?: intent.getStringExtra("stream_url")
            ?: intent.getStringExtra("url")
            ?: ""

        val passedStreamId = intent.getStringExtra("stream_id") ?: ""
        val passedReferer = intent.getStringExtra("stream_referer") ?: ""
        val passedUa = intent.getStringExtra("stream_ua") ?: SessionManager.CUSTOM_USER_AGENT

        val passedTitle = intent.getStringExtra(WatchRoomActivity.EXTRA_STREAM_TITLE)
            ?: intent.getStringExtra(WatchRoomActivity.EXTRA_CHANNEL_NAME)
            ?: intent.getStringExtra("title")
            ?: ""

        val passedRoomName = intent.getStringExtra(WatchRoomActivity.EXTRA_ROOM_NAME) ?: ""

        val passedCode = intent.getStringExtra(WatchRoomActivity.EXTRA_ROOM_CODE)
            ?: intent.getStringExtra(WatchRoomActivity.EXTRA_ROOM_ID)
            ?: ""

        val isDirectMovie = intent.getBooleanExtra("direct_movie_launch", false)
        val isHostFlag = intent.getBooleanExtra(WatchRoomActivity.EXTRA_IS_HOST, true)

        if (isDirectMovie || passedUrl.isNotEmpty() || passedStreamId.isNotEmpty()) {
            val code = passedCode.ifEmpty { generateRoomCode() }
            val titleToUse = passedTitle.ifEmpty { "فيلم مشترك" }
            val rName = passedRoomName.ifEmpty { "غرفة $titleToUse" }

            val directRoom = WatchRoomModel(
                roomId = code,
                roomCode = code,
                roomName = rName,
                hostId = localUserId,
                hostName = localUserName,
                isPrivate = false,
                onlineCount = 1,
                status = "LIVE",
                streamId = passedStreamId,
                streamUrl = passedUrl,
                streamReferer = passedReferer,
                streamUserAgent = passedUa,
                streamTitle = titleToUse,
                category = "🎬 مشاهدة جماعية",
                isMovie = true,
                isPlaying = passedUrl.isNotEmpty()
            )
            enterWatchRoom(directRoom, isHost = isHostFlag)

            if (passedUrl.isNotEmpty()) {
                broadcastPlaybackSync()
            } else if (passedStreamId.isNotEmpty()) {
                extractAndPlayMovieIdenticalToApp(
                    MediaStreamOption(
                        id = passedStreamId,
                        title = titleToUse,
                        subtitle = "🎬 فيلم",
                        streamUrl = "",
                        isMovie = true
                    )
                )
            }
        } else if (passedCode.isNotEmpty()) {
            joinRoomByCodeOrLink(passedCode)
        }
    }

    fun release() {
        isDestroyed = true
        val room = currentRoom
        if (room != null && room.hostId == localUserId) {
            GlobalScope.launch(Dispatchers.IO) {
                OttRepository.manageWatchRoom(room.roomId, localUserId, "CLOSE_ROOM")
            }
        }
        progressTickJob?.cancel()
        snifferExtractionJob?.cancel()
        cleanupHeadlessSniffer()
        controllerScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        try { webSocket?.close(1000, "Destroy") } catch (_: Exception) {}
        voiceChatManager?.release()
        playerEngine.release()
    }

    private fun dp(value: Int): Int =
        (value * hostContext.resources.displayMetrics.density).toInt()

    private fun dpF(value: Float): Float =
        value * hostContext.resources.displayMetrics.density

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

    private fun loadPosterIntoImageView(url: String, imageView: ImageView) {
        imageView.tag = url
        if (url.isBlank()) {
            imageView.setImageDrawable(null)
            imageView.setBackgroundColor(Color.parseColor("#1D2230"))
            return
        }
        val cached = posterBitmapCache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        imageView.setImageDrawable(null)
        imageView.setBackgroundColor(Color.parseColor("#1D2230"))
        controllerScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                posterBitmapCache.put(url, bmp)
                                withContext(Dispatchers.Main) {
                                    if (imageView.tag == url) {
                                        imageView.setImageBitmap(bmp)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    inner class ActiveRoomsAdapter(
        private val items: List<WatchRoomModel>,
        private val onJoinClick: (WatchRoomModel) -> Unit
    ) : RecyclerView.Adapter<ActiveRoomsAdapter.VH>() {
        inner class VH(val root: LinearLayout, val tvTitle: TextView, val tvMeta: TextView, val tvStream: TextView, val btnJoin: AppCompatButton) : RecyclerView.ViewHolder(root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.wt_bg_card)
                setPadding(28, 22, 28, 22)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 14 }
            }
            val tvTitle = TextView(parent.context).apply { setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, Typeface.BOLD) }
            val tvStream = TextView(parent.context).apply { setTextColor(Color.parseColor("#80D8FF")); textSize = 12f; setPadding(0, 6, 0, 8) }
            val bottomRow = LinearLayout(parent.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val tvMeta = TextView(parent.context).apply { setTextColor(Color.parseColor("#9AA5B8")); textSize = 11f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            val btnJoin = AppCompatButton(parent.context).apply {
                setBackgroundResource(R.drawable.wt_bg_btn_primary)
                setTextColor(Color.WHITE)
                textSize = 11f
                isAllCaps = false
                setPadding(28, 0, 28, 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 76)
            }
            bottomRow.addView(tvMeta); bottomRow.addView(btnJoin)
            card.addView(tvTitle); card.addView(tvStream); card.addView(bottomRow)
            return VH(card, tvTitle, tvMeta, tvStream, btnJoin)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            val lockBadge = if (r.isPrivate) "🔒 خاصة" else "🌐 عامة"
            holder.tvTitle.text = "${r.roomName}  •  $lockBadge (#${r.roomCode})"
            holder.tvStream.text = "🎬 ${r.streamTitle}"
            holder.tvMeta.text = "👑 ${r.hostName}   •   🟢 ${r.onlineCount} متصل الآن"
            holder.btnJoin.text = "دخول الغرفة ⬅"
            holder.btnJoin.setOnClickListener { onJoinClick(r) }
            holder.root.setOnClickListener { onJoinClick(r) }
        }
        override fun getItemCount(): Int = items.size
    }

    inner class ChatAdapter(
        private val items: List<ChatMessageModel>,
        private val onMessageClick: (ChatMessageModel) -> Unit
    ) : RecyclerView.Adapter<ChatAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setPadding(20, 12, 20, 12); textSize = 12f; setBackgroundResource(R.drawable.wt_bg_card)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6 }
            }
            return VH(tv)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = items[position]
            val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(m.timestamp))
            if (m.isSystemEvent) {
                holder.tv.setTextColor(Color.parseColor("#FFE082"))
                holder.tv.text = "[$timeStr] ${m.text}"
            } else {
                val badge = if (m.senderRole == "HOST") "👑 " else "👤 "
                holder.tv.setTextColor(Color.WHITE)
                holder.tv.text = "[$timeStr] $badge${m.senderName}: ${m.text}"
                holder.tv.setOnClickListener { onMessageClick(m) }
            }
        }
        override fun getItemCount(): Int = items.size
    }

    inner class ParticipantsAdapter(
        private val getList: () -> List<ParticipantModel>,
        private val onItemClick: (ParticipantModel) -> Unit
    ) : RecyclerView.Adapter<ParticipantsAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setPadding(22, 14, 22, 14); textSize = 12f; setTextColor(Color.WHITE); setBackgroundResource(R.drawable.wt_bg_card)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
            }
            return VH(tv)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = getList()[position]
            val roleBadge = if (p.role == "HOST") "👑 مضيف" else "مشاهد"
            val micBadge = if (p.isSpeaking) "🟢 يتحدث الآن..." else if (p.isMuted) "🎤 مكتوم" else "🎙️ متصل"
            holder.tv.text = "${p.userName} ($roleBadge)   •   $micBadge"
            holder.tv.setOnClickListener { onItemClick(p) }
        }
        override fun getItemCount(): Int = getList().size
    }

    inner class MediaPickerAdapter(
        private val items: List<MediaStreamOption>,
        private val onSelect: (MediaStreamOption) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class MoviePosterVH(
            val cardRoot: FrameLayout,
            val ivPoster: ImageView,
            val tvRatingBadge: TextView,
            val tvMovieTitle: TextView
        ) : RecyclerView.ViewHolder(cardRoot)

        inner class ChannelRowVH(
            val rowRoot: LinearLayout,
            val tvIcon: TextView,
            val tvTitle: TextView,
            val tvSub: TextView,
            val btnPlay: AppCompatButton
        ) : RecyclerView.ViewHolder(rowRoot)

        override fun getItemViewType(position: Int): Int {
            return if (items[position].isMovie) 1 else 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val card = FrameLayout(parent.context).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1A1E29"))
                        cornerRadius = dp(16).toFloat()
                    }
                    clipToOutline = true
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(182)
                    ).apply {
                        setMargins(dp(5), dp(6), dp(5), dp(6))
                    }
                }

                val ivPoster = ImageView(parent.context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                val bottomScrim = View(parent.context).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(Color.parseColor("#EE0B0D14"), Color.parseColor("#880B0D14"), Color.TRANSPARENT)
                    )
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(82),
                        Gravity.BOTTOM
                    )
                }

                val tvRating = TextView(parent.context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 10.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#CC141720"))
                        cornerRadius = dp(10).toFloat()
                    }
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END
                    ).apply {
                        setMargins(dp(6), dp(6), dp(6), dp(6))
                    }
                }

                val tvTitle = TextView(parent.context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(8), dp(4), dp(8), dp(8))
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.START
                    )
                }

                card.addView(ivPoster)
                card.addView(bottomScrim)
                card.addView(tvRating)
                card.addView(tvTitle)
                return MoviePosterVH(card, ivPoster, tvRating, tvTitle)
            } else {
                val row = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.wt_bg_card)
                    setPadding(20, 16, 20, 16)
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 10 }
                }
                val tvIcon = TextView(parent.context).apply {
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.wt_bg_badge_dark)
                    setPadding(18, 14, 18, 14)
                }
                val textCol = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = 16
                        marginEnd = 12
                    }
                }
                val tvTitle = TextView(parent.context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                }
                val tvSub = TextView(parent.context).apply {
                    setTextColor(Color.parseColor("#80D8FF"))
                    textSize = 11f
                    setPadding(0, 4, 0, 0)
                }
                textCol.addView(tvTitle)
                textCol.addView(tvSub)
                val btnPlay = AppCompatButton(parent.context).apply {
                    setBackgroundResource(R.drawable.wt_bg_btn_primary)
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    isAllCaps = false
                    text = "▶️ تشغيل"
                    setPadding(24, 0, 24, 0)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 72)
                }
                row.addView(tvIcon)
                row.addView(textCol)
                row.addView(btnPlay)
                return ChannelRowVH(row, tvIcon, tvTitle, tvSub, btnPlay)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is MoviePosterVH) {
                holder.tvMovieTitle.text = item.title
                holder.tvRatingBadge.text = "★ ${item.rating}"
                loadPosterIntoImageView(item.posterUrl, holder.ivPoster)
                holder.cardRoot.setOnClickListener { onSelect(item) }
            } else if (holder is ChannelRowVH) {
                holder.tvIcon.text = "📺"
                holder.tvTitle.text = item.title
                holder.tvSub.text = item.subtitle
                holder.btnPlay.setOnClickListener { onSelect(item) }
                holder.rowRoot.setOnClickListener { onSelect(item) }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    inner class EventLogsAdapter(private val items: List<RoomEventLogItem>) : RecyclerView.Adapter<EventLogsAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(TextView(parent.context).apply { setPadding(14, 8, 14, 8); textSize = 11f; setTextColor(Color.parseColor("#B0BEC5")) })
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val log = items[position]
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(log.timestamp))
            holder.tv.text = "[$timeStr] [${log.type}] ${log.detail}"
        }
        override fun getItemCount(): Int = items.size
    }
}

class WatchRoomActivity : AppCompatActivity() {

    private var controller: WatchTogetherController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch_room)
        val root = findViewById<View>(R.id.rootWatchTogether)
        controller = WatchTogetherController(this, root, this, isStandaloneActivity = true).also {
            it.init()
            it.handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller?.handleIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (controller?.currentRoom != null) {
            controller?.confirmLeaveRoom()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_ROOM_NAME = "room_name"
        const val EXTRA_ROOM_PASS = "room_pass"
        const val EXTRA_ROOM_PASSWORD = "room_password"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_STREAM_TITLE = "stream_title"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_IS_HOST = "is_host"
        const val EXTRA_IS_PRIVATE = "is_private"
    }
}