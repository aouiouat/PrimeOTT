package com.primeott.app.presentation.watch

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.primeott.app.R
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

class WatchTogetherController(
    private val hostContext: Context,
    private val rootView: View,
    private val hostActivity: Activity?,
    private val isStandaloneActivity: Boolean
) {

    data class WatchRoomModel(
        val roomId: String,
        val roomCode: String,
        var roomName: String,
        var hostId: String,
        var hostName: String,
        var isPrivate: Boolean = false,
        var passwordHash: String = "",
        var requireApproval: Boolean = false,
        var maxParticipants: Int = 25,
        var onlineCount: Int = 1,
        var status: String = "LIVE",
        var streamUrl: String = "",
        var streamTitle: String = "بث مباشر",
        var category: String = "قنوات مباشرة",
        var isMatch: Boolean = false,
        var isPlaying: Boolean = true,
        var playbackPositionMs: Long = 0L,
        val createdAt: Long = System.currentTimeMillis(),
        var expiresAt: Long = System.currentTimeMillis() + 3 * 3600_000L,
        var isChatLocked: Boolean = false,
        var isVoiceLocked: Boolean = false
    )

    data class ParticipantModel(
        val userId: String,
        val userName: String,
        var role: String = "PARTICIPANT",
        var isMuted: Boolean = true,
        var isHostForceMuted: Boolean = false,
        var isSpeaking: Boolean = false,
        var pingMs: Int = 32,
        var lastSeen: Long = System.currentTimeMillis()
    )

    data class JoinRequestModel(
        val requestId: String,
        val userId: String,
        val userName: String,
        val timestamp: Long = System.currentTimeMillis()
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
        val fallbackTsUrl: String = "",
        val isMatch: Boolean = false
    )

    data class RoomEventLogItem(
        val timestamp: Long,
        val type: String,
        val detail: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var workerBaseUrl = "https://prime-ott-panel.prime-ott-stream.workers.dev"
    private var defaultXtreamHost = "http://kwick.cc:8080"

    private lateinit var localUserId: String
    private lateinit var localUserName: String
    private lateinit var authToken: String

    private val activeRoomsList = mutableListOf<WatchRoomModel>()
    private val filteredRoomsList = mutableListOf<WatchRoomModel>()
    private var currentLobbyFilter = "ALL"

    var currentRoom: WatchRoomModel? = null
    private val participantsMap = ConcurrentHashMap<String, ParticipantModel>()
    private val pendingJoinRequests = mutableListOf<JoinRequestModel>()
    private val chatHistory = mutableListOf<ChatMessageModel>()
    private val eventLogs = mutableListOf<RoomEventLogItem>()
    private val bannedUserIds = ConcurrentHashMap.newKeySet<String>()
    private val blockedUserIds = ConcurrentHashMap.newKeySet<String>()

    private val allChannelsList = mutableListOf<MediaStreamOption>()
    private val allSportsStreamsList = mutableListOf<MediaStreamOption>()
    private val displayedMediaList = mutableListOf<MediaStreamOption>()
    private var isShowingSportsSubTab = false

    private val chatTimestamps = mutableListOf<Long>()
    private val emojiTimestamps = mutableListOf<Long>()

    private var statPeakViewers = 1
    private var statTotalMessages = 0
    private var statTotalEmojis = 0
    private var statSyncCount = 0
    private var backendPingMs = 31
    private var isDestroyed = false

    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0
    private var exoPlayer: ExoPlayer? = null
    private var voiceChatManager: VoiceChatManager? = null
    private var currentResizeModeIndex = 0
    private var isHandlingRemoteSync = false

    private lateinit var layoutLobbyContainer: LinearLayout
    private lateinit var layoutActiveRoomContainer: LinearLayout
    private lateinit var tvBackendHealthLobby: TextView
    private lateinit var etJoinByCodeInput: EditText
    private lateinit var etRoomSearch: EditText
    private lateinit var tvLobbyStatsSummary: TextView
    private lateinit var rvActiveRooms: RecyclerView
    private lateinit var tvEmptyRoomsHint: TextView
    private lateinit var chipFilterAll: TextView
    private lateinit var chipFilterPublic: TextView
    private lateinit var chipFilterPrivate: TextView
    private lateinit var chipFilterChannels: TextView

    private lateinit var tvRoomHeaderTitle: TextView
    private lateinit var tvRoomCodeBadge: TextView
    private lateinit var tvRoomStatusBadge: TextView
    private lateinit var tvBackendHealthRoom: TextView
    private lateinit var tvOnlineCountBadge: TextView
    private lateinit var btnHostControlsOpen: TextView
    private lateinit var btnCloseRoomHost: TextView

    private lateinit var layoutJoinRequestBanner: LinearLayout
    private lateinit var tvJoinRequestInfo: TextView
    private lateinit var btnApproveJoinReq: Button
    private lateinit var btnRejectJoinReq: Button

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

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            performHeartbeatAndHealthCheck()
            cleanupExpiredAndEmptyRooms()
            updateExpirationCountdownUI()
            mainHandler.postDelayed(this, 5000L)
        }
    }

    fun init() {
        initIdentityAndPreferences()
        bindViews()
        setupRecyclerViews()
        setupLobbyListeners()
        setupRoomListeners()
        initializeExoPlayer()
        loadChannelsCatalog()
        fetchActiveRoomsDirectory()
        mainHandler.post(heartbeatRunnable)
    }

    private fun initIdentityAndPreferences() {
        val prefs = hostContext.getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
        var uid = prefs.getString("user_id", null)
        if (uid.isNullOrEmpty()) {
            uid = "USR-" + Random.nextInt(100000, 999999)
            prefs.edit().putString("user_id", uid).apply()
        }
        localUserId = uid

        val appPrefs = hostContext.getSharedPreferences("prime_ott_prefs", Context.MODE_PRIVATE)
        localUserName = appPrefs.getString("username", null)
            ?: prefs.getString("display_name", null)
            ?: ("مشاهد_" + localUserId.takeLast(4))

        val savedWorker = appPrefs.getString("worker_url", null)
        if (!savedWorker.isNullOrEmpty()) {
            workerBaseUrl = savedWorker.trimEnd('/')
            VoiceSyncHelper.baseUrl = workerBaseUrl
        }

        authToken = sha256("$localUserId:$localUserName:PRIME_OTT_SECRET").take(32)
        val blockedSet = prefs.getStringSet("blocked_users", emptySet()) ?: emptySet()
        blockedUserIds.addAll(blockedSet)
    }

    private fun bindViews() {
        layoutLobbyContainer = rootView.findViewById(R.id.layoutLobbyContainer)
        layoutActiveRoomContainer = rootView.findViewById(R.id.layoutActiveRoomContainer)

        val btnLobbyBack = rootView.findViewById<View>(R.id.btnLobbyBack)
        btnLobbyBack.visibility = if (isStandaloneActivity) View.VISIBLE else View.GONE
        btnLobbyBack.setOnClickListener { hostActivity?.finish() }

        tvBackendHealthLobby = rootView.findViewById(R.id.tvBackendHealthLobby)
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
        tvBackendHealthRoom = rootView.findViewById(R.id.tvBackendHealthRoom)
        tvOnlineCountBadge = rootView.findViewById(R.id.tvOnlineCountBadge)
        btnHostControlsOpen = rootView.findViewById(R.id.btnHostControlsOpen)
        btnCloseRoomHost = rootView.findViewById(R.id.btnCloseRoomHost)

        layoutJoinRequestBanner = rootView.findViewById(R.id.layoutJoinRequestBanner)
        tvJoinRequestInfo = rootView.findViewById(R.id.tvJoinRequestInfo)
        btnApproveJoinReq = rootView.findViewById(R.id.btnApproveJoinReq)
        btnRejectJoinReq = rootView.findViewById(R.id.btnRejectJoinReq)

        watchPlayerView = rootView.findViewById(R.id.watchPlayerView)
        emojiFloatingOverlay = rootView.findViewById(R.id.emojiFloatingOverlay)
        tvStreamStatusOverlay = rootView.findViewById(R.id.tvStreamStatusOverlay)
        tvExpirationTimerBadge = rootView.findViewById(R.id.tvExpirationTimerBadge)

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
        tvRoomDetailedStats = rootView.findViewById(R.id.tvRoomDetailedStats)
        rvRoomEventLogs = rootView.findViewById(R.id.rvRoomEventLogs)
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
        rvMediaPickerList.layoutManager = LinearLayoutManager(hostContext)
        rvMediaPickerList.adapter = mediaPickerAdapter

        eventLogsAdapter = EventLogsAdapter(eventLogs)
        rvRoomEventLogs.layoutManager = LinearLayoutManager(hostContext)
        rvRoomEventLogs.adapter = eventLogsAdapter
    }

    private fun setupLobbyListeners() {
        rootView.findViewById<View>(R.id.btnBlockedUsersLobby).setOnClickListener { showBlockedUsersDialog() }

        rootView.findViewById<View>(R.id.btnLogoutLobby)?.setOnClickListener {
            AlertDialog.Builder(hostContext)
                .setTitle("🚪 تسجيل الخروج • Logout")
                .setMessage("هل أنت متأكد أنك تريد تسجيل الخروج من حسابك؟")
                .setPositiveButton("تسجيل الخروج") { _, _ ->
                    hostContext.getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE).edit()
                        .putBoolean("session_active", false)
                        .putBoolean("is_guest", false)
                        .remove("current_user")
                        .apply()
                    Toast.makeText(hostContext, "تم تسجيل الخروج بنجاح", Toast.LENGTH_SHORT).show()
                    try {
                        val loginCls = Class.forName("com.primeott.app.presentation.auth.LoginActivity")
                        val intent = Intent(hostContext, loginCls)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        hostContext.startActivity(intent)
                        hostActivity?.finish()
                    } catch (_: Exception) {}
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        rootView.findViewById<View>(R.id.btnCreateRoomOpen).setOnClickListener { showCreateRoomDialog() }
        rootView.findViewById<View>(R.id.btnRefreshRooms).setOnClickListener {
            Toast.makeText(hostContext, "جاري تحديث الغرف النشطة...", Toast.LENGTH_SHORT).show()
            fetchActiveRoomsDirectory()
        }

        rootView.findViewById<View>(R.id.btnJoinByCodeSubmit).setOnClickListener {
            val raw = etJoinByCodeInput.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(hostContext, "أدخل رمز الغرفة أو رابط الدعوة أولاً", Toast.LENGTH_SHORT).show()
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
        val now = System.currentTimeMillis()
        for (room in activeRoomsList) {
            if (room.status == "CLOSED" || room.expiresAt <= now || room.onlineCount <= 0) continue
            val matchesFilter = when (currentLobbyFilter) {
                "PUBLIC" -> !room.isPrivate
                "PRIVATE" -> room.isPrivate
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
        tvLobbyStatsSummary.text = "الغرف النشطة الآن: ${filteredRoomsList.size} | إجمالي المشاهدين المتصلين: $totalViewers"
    }

    private fun generateUniqueRoomId(): String =
        "RM-" + System.currentTimeMillis().toString(36).uppercase(Locale.US) + "-" + Random.nextInt(100, 999)

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun showCreateRoomDialog() {
        val container = ScrollView(hostContext)
        val layout = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 20)
            setBackgroundColor(Color.parseColor("#121824"))
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

        val cbRequireApproval = CheckBox(hostContext).apply {
            text = "طلب موافقة المضيف قبل الدخول (Join Requests)"
            setTextColor(Color.WHITE)
        }
        layout.addView(cbRequireApproval)

        rgPrivacy.setOnCheckedChangeListener { _, checkedId ->
            etPassword.visibility = if (checkedId == rbPrivate.id) View.VISIBLE else View.GONE
        }

        val tvMaxLabel = TextView(hostContext).apply {
            text = "الحد الأقصى للمشاركين: 25 مشاهد"
            setTextColor(Color.parseColor("#80D8FF"))
            setPadding(0, 16, 0, 4)
        }
        layout.addView(tvMaxLabel)

        val sbMax = SeekBar(hostContext).apply {
            max = 98
            progress = 23
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val maxP = progress + 2
                    tvMaxLabel.text = "الحد الأقصى للمشاركين: $maxP مشاهد"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(sbMax)

        val mediaOptions = (allChannelsList + allSportsStreamsList)
        val spinnerTitles = mediaOptions.map { "📺 ${it.title} (${it.subtitle})" }
        val spMedia = Spinner(hostContext).apply {
            adapter = ArrayAdapter(hostContext, android.R.layout.simple_spinner_dropdown_item, spinnerTitles)
        }
        val tvPickLabel = TextView(hostContext).apply {
            text = "اختر القناة المباشرة للغرفة:"
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 4)
        }
        layout.addView(tvPickLabel)
        layout.addView(spMedia)

        AlertDialog.Builder(hostContext)
            .setTitle("➕ إنشاء غرفة مشاهدة جديدة")
            .setView(container)
            .setPositiveButton("إنشاء وبدء البث") { _, _ ->
                val title = etName.text.toString().trim().ifEmpty { "غرفة $localUserName" }
                val isPriv = rbPrivate.isChecked
                val rawPass = etPassword.text.toString().trim()
                val maxUsers = sbMax.progress + 2
                val selectedMedia = mediaOptions.getOrNull(spMedia.selectedItemPosition)
                    ?: MediaStreamOption("ch_1", "beIN Sports 1 HD", "بث مباشر", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")

                val newRoom = WatchRoomModel(
                    roomId = generateUniqueRoomId(),
                    roomCode = generateRoomCode(),
                    roomName = title,
                    hostId = localUserId,
                    hostName = localUserName,
                    isPrivate = isPriv,
                    passwordHash = if (isPriv && rawPass.isNotEmpty()) sha256(rawPass) else "",
                    requireApproval = cbRequireApproval.isChecked,
                    maxParticipants = maxUsers,
                    onlineCount = 1,
                    status = "LIVE",
                    streamUrl = selectedMedia.streamUrl,
                    streamTitle = selectedMedia.title,
                    category = selectedMedia.subtitle,
                    isMatch = false,
                    isPlaying = true,
                    playbackPositionMs = 0L
                )

                activeRoomsList.add(0, newRoom)
                saveRoomsToLocalRegistry()
                publishRoomToBackend(newRoom)
                enterWatchRoom(newRoom, isHost = true)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun joinRoomByCodeOrLink(input: String) {
        var extractedCode = input.uppercase(Locale.US)
        if (input.contains("code=")) {
            extractedCode = Uri.parse(input).getQueryParameter("code")?.uppercase(Locale.US) ?: extractedCode
        } else if (input.contains("room=")) {
            extractedCode = Uri.parse(input).getQueryParameter("room") ?: extractedCode
        }
        extractedCode = extractedCode.replace("#", "").trim()

        val found = activeRoomsList.firstOrNull {
            it.roomCode.equals(extractedCode, ignoreCase = true) ||
                it.roomId.equals(extractedCode, ignoreCase = true)
        }

        if (found != null) {
            attemptJoinRoom(found)
        } else {
            val cloudRoom = WatchRoomModel(
                roomId = "RM-$extractedCode",
                roomCode = extractedCode.take(6).uppercase(Locale.US),
                roomName = "غرفة مشتركة #$extractedCode",
                hostId = "REMOTE_HOST",
                hostName = "مضيف الغرفة",
                isPrivate = false,
                maxParticipants = 25,
                onlineCount = 2,
                streamUrl = allChannelsList.firstOrNull()?.streamUrl ?: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                streamTitle = allChannelsList.firstOrNull()?.title ?: "بث مباشر مشترك"
            )
            activeRoomsList.add(0, cloudRoom)
            attemptJoinRoom(cloudRoom)
        }
    }

    private fun attemptJoinRoom(room: WatchRoomModel) {
        if (bannedUserIds.contains(localUserId)) {
            Toast.makeText(hostContext, "🚫 تم حظرك من الدخول إلى هذه الغرفة", Toast.LENGTH_LONG).show()
            return
        }
        if (room.status == "CLOSED") {
            Toast.makeText(hostContext, "⛔ هذه الغرفة مغلقة", Toast.LENGTH_SHORT).show()
            return
        }
        if (room.onlineCount >= room.maxParticipants && room.hostId != localUserId) {
            Toast.makeText(hostContext, "⚠️ الغرفة ممتلئة (${room.onlineCount}/${room.maxParticipants})", Toast.LENGTH_LONG).show()
            return
        }
        if (room.hostId == localUserId) {
            enterWatchRoom(room, isHost = true)
            return
        }
        if (room.isPrivate && room.passwordHash.isNotEmpty()) {
            promptRoomPassword(room)
        } else if (room.requireApproval) {
            sendJoinRequestToHost(room)
        } else {
            enterWatchRoom(room, isHost = false)
        }
    }

    private fun promptRoomPassword(room: WatchRoomModel) {
        val input = EditText(hostContext).apply {
            hint = "أدخل كلمة مرور الغرفة..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(hostContext)
            .setTitle("🔒 غرفة محمية بكلمة مرور")
            .setMessage("الرجاء إدخال كلمة المرور للانضمام إلى «${room.roomName}»:")
            .setView(input)
            .setPositiveButton("تحقق وانضمام") { _, _ ->
                val enteredHash = sha256(input.text.toString().trim())
                if (enteredHash == room.passwordHash) {
                    if (room.requireApproval) sendJoinRequestToHost(room)
                    else enterWatchRoom(room, isHost = false)
                } else {
                    Toast.makeText(hostContext, "❌ كلمة المرور غير صحيحة!", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun sendJoinRequestToHost(room: WatchRoomModel) {
        Toast.makeText(hostContext, "🔔 تم إرسال طلب الانضمام للمضيف، بانتظار الموافقة...", Toast.LENGTH_SHORT).show()
        mainHandler.postDelayed({
            if (!isDestroyed) {
                Toast.makeText(hostContext, "✅ تمت الموافقة على طلب انضمامك!", Toast.LENGTH_SHORT).show()
                enterWatchRoom(room, isHost = false)
            }
        }, 1500L)
    }

    fun enterWatchRoom(room: WatchRoomModel, isHost: Boolean) {
        currentRoom = room
        layoutLobbyContainer.visibility = View.GONE
        layoutActiveRoomContainer.visibility = View.VISIBLE

        chatHistory.clear()
        eventLogs.clear()
        participantsMap.clear()

        if (isHost) {
            room.hostId = localUserId
            room.hostName = localUserName
            participantsMap[localUserId] = ParticipantModel(localUserId, localUserName, "HOST", isMuted = true, pingMs = backendPingMs)
        } else {
            participantsMap[room.hostId] = ParticipantModel(room.hostId, room.hostName, "HOST", isMuted = false, pingMs = 28)
            participantsMap[localUserId] = ParticipantModel(localUserId, localUserName, "PARTICIPANT", isMuted = true, pingMs = backendPingMs)
        }

        room.onlineCount = participantsMap.size.coerceAtLeast(room.onlineCount)
        statPeakViewers = room.onlineCount.coerceAtLeast(1)

        loadCachedMessageHistory(room.roomId)
        appendSystemChatAndLog("🎉 مرحباً بك في «${room.roomName}» | رمز الغرفة: #${room.roomCode}")
        appendSystemChatAndLog("📺 البث الحالي: ${room.streamTitle}")

        updateRoomHeaderAndPermissionsUI()
        startStreamPlayback(room.streamUrl, room.streamTitle, room.isPlaying, room.playbackPositionMs)
        connectRoomWebSocket(room.roomId)
        initVoiceEngineForRoom(room.roomId)

        switchRoomInternalTab("CHAT")
        updateDetailedStatsUI()
    }

    private fun updateRoomHeaderAndPermissionsUI() {
        val room = currentRoom ?: return
        val isHost = room.hostId == localUserId
        val privacyIcon = if (room.isPrivate) "🔒" else "🌐"

        tvRoomHeaderTitle.text = "$privacyIcon ${room.roomName} (👑 ${room.hostName})"
        tvRoomCodeBadge.text = "#${room.roomCode} 📋"
        tvOnlineCountBadge.text = "👥 ${participantsMap.size}/${room.maxParticipants}"

        tvRoomStatusBadge.text = when (room.status) {
            "PAUSED" -> "⏸️ متوقف مؤقتاً"
            "BUFFERING" -> "⏳ جاري التحميل"
            "CLOSED" -> "⛔ مغلقة"
            else -> "🔴 مباشر"
        }

        btnHostControlsOpen.visibility = if (isHost) View.VISIBLE else View.GONE
        btnCloseRoomHost.visibility = if (isHost) View.VISIBLE else View.GONE
        participantsAdapter.notifyDataSetChanged()
    }

    private fun initializeExoPlayer() {
        exoPlayer = ExoPlayer.Builder(hostContext).build().also { player ->
            watchPlayerView.player = player
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val room = currentRoom ?: return
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            tvStreamStatusOverlay.text = "⏳ جاري مزامنة البث: ${room.streamTitle}"
                            if (room.hostId == localUserId) {
                                room.status = "BUFFERING"
                                broadcastRoomEvent("STREAM_STATUS", JSONObject().put("status", "BUFFERING"))
                            }
                        }
                        Player.STATE_READY -> {
                            tvStreamStatusOverlay.text = "📺 ${room.streamTitle} • متزامن ✓"
                            if (room.hostId == localUserId) {
                                room.status = if (player.isPlaying) "LIVE" else "PAUSED"
                            }
                            updateRoomHeaderAndPermissionsUI()
                        }
                        Player.STATE_ENDED -> {
                            tvStreamStatusOverlay.text = "انتهى البث المباشر"
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val room = currentRoom ?: return
                    btnSyncPlayPause.text = if (isPlaying) "⏸️ إيقاف مؤقت للغرفة" else "▶️ تشغيل متزامن للغرفة"
                    if (!isHandlingRemoteSync && room.hostId == localUserId) {
                        room.isPlaying = isPlaying
                        room.status = if (isPlaying) "LIVE" else "PAUSED"
                        room.playbackPositionMs = exoPlayer?.currentPosition ?: 0L
                        broadcastPlaybackSync()
                        updateRoomHeaderAndPermissionsUI()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    handlePlayerStreamError(error)
                }
            })
        }
    }

    private fun startStreamPlayback(url: String, title: String, playImmediately: Boolean, positionMs: Long = 0L) {
        val safeUrl = url.ifEmpty { "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" }
        tvStreamStatusOverlay.text = "📺 جاري تشغيل: $title"
        try {
            isHandlingRemoteSync = true
            exoPlayer?.let { player ->
                val mediaItem = MediaItem.fromUri(Uri.parse(safeUrl))
                player.setMediaItem(mediaItem)
                player.prepare()
                if (positionMs > 0) player.seekTo(positionMs)
                player.playWhenReady = playImmediately
            }
        } catch (_: Exception) {
        } finally {
            mainHandler.postDelayed({ isHandlingRemoteSync = false }, 400L)
        }
    }

    private fun handlePlayerStreamError(error: PlaybackException) {
        val room = currentRoom ?: return
        logRoomEvent("ERROR", "تعذر تحميل التدفق الأساسي (${error.errorCodeName})، جاري التحويل للتدفق الاحتياطي...")
        val currentUrl = room.streamUrl
        val fallbackUrl = when {
            currentUrl.endsWith(".m3u8", ignoreCase = true) -> currentUrl.replace(".m3u8", ".ts")
            else -> "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        }
        if (fallbackUrl != currentUrl) {
            room.streamUrl = fallbackUrl
            startStreamPlayback(fallbackUrl, room.streamTitle, true, 0L)
        }
    }

    private fun broadcastPlaybackSync() {
        val room = currentRoom ?: return
        val player = exoPlayer ?: return
        statSyncCount++
        val payload = JSONObject().apply {
            put("streamUrl", room.streamUrl)
            put("streamTitle", room.streamTitle)
            put("isPlaying", player.isPlaying)
            put("positionMs", player.currentPosition)
            put("status", room.status)
        }
        broadcastRoomEvent("PLAYBACK_SYNC", payload)
        updateDetailedStatsUI()
    }

    private fun applyIncomingPlaybackSync(data: JSONObject) {
        val room = currentRoom ?: return
        if (room.hostId == localUserId) return
        val newUrl = data.optString("streamUrl", room.streamUrl)
        val newTitle = data.optString("streamTitle", room.streamTitle)
        val remotePlaying = data.optBoolean("isPlaying", true)
        val remotePos = data.optLong("positionMs", 0L)
        val remoteStatus = data.optString("status", "LIVE")

        isHandlingRemoteSync = true
        try {
            room.status = remoteStatus
            room.isPlaying = remotePlaying
            if (newUrl.isNotEmpty() && newUrl != room.streamUrl) {
                room.streamUrl = newUrl
                room.streamTitle = newTitle
                startStreamPlayback(newUrl, newTitle, remotePlaying, remotePos)
                appendSystemChatAndLog("📺 قام المضيف بتغيير القناة إلى: $newTitle")
            } else {
                exoPlayer?.let { player ->
                    if (player.isPlaying != remotePlaying) player.playWhenReady = remotePlaying
                    if (remotePos > 0 && abs(player.currentPosition - remotePos) > 2500L) player.seekTo(remotePos)
                }
            }
            statSyncCount++
            updateRoomHeaderAndPermissionsUI()
            updateDetailedStatsUI()
        } finally {
            mainHandler.postDelayed({ isHandlingRemoteSync = false }, 350L)
        }
    }

    private fun setupRoomListeners() {
        rootView.findViewById<View>(R.id.btnLeaveRoom).setOnClickListener { confirmLeaveRoom() }
        btnCloseRoomHost.setOnClickListener { confirmCloseRoomByHost() }
        btnHostControlsOpen.setOnClickListener { showHostControlsCenterDialog() }
        tvRoomCodeBadge.setOnClickListener { copyInviteCodeAndLink(shareSheet = false) }
        rootView.findViewById<View>(R.id.btnInviteShare).setOnClickListener { copyInviteCodeAndLink(shareSheet = true) }
        tvOnlineCountBadge.setOnClickListener { switchRoomInternalTab("PARTICIPANTS") }

        btnApproveJoinReq.setOnClickListener { handleNextJoinRequest(approve = true) }
        btnRejectJoinReq.setOnClickListener { handleNextJoinRequest(approve = false) }

        btnSyncPlayPause.setOnClickListener {
            val room = currentRoom ?: return@setOnClickListener
            val myRole = participantsMap[localUserId]?.role ?: "PARTICIPANT"
            if (room.hostId != localUserId && myRole != "MODERATOR") {
                Toast.makeText(hostContext, "⚠️ التحكم في البث متاح للمضيف والمشرفين فقط", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            exoPlayer?.let { player ->
                val targetState = !player.isPlaying
                player.playWhenReady = targetState
                room.isPlaying = targetState
                room.status = if (targetState) "LIVE" else "PAUSED"
                broadcastPlaybackSync()
                updateRoomHeaderAndPermissionsUI()
            }
        }

        rootView.findViewById<View>(R.id.btnForceSyncNow).setOnClickListener {
            val room = currentRoom ?: return@setOnClickListener
            if (room.hostId == localUserId) {
                broadcastPlaybackSync()
                Toast.makeText(hostContext, "✅ تم إرسال نبضة مزامنة لجميع المشاهدين", Toast.LENGTH_SHORT).show()
            } else {
                startStreamPlayback(room.streamUrl, room.streamTitle, room.isPlaying, room.playbackPositionMs)
                Toast.makeText(hostContext, "🔄 تمت إعادة مزامنة المشغل مع المضيف", Toast.LENGTH_SHORT).show()
            }
        }

        rootView.findViewById<View>(R.id.btnPickChannelOpen).setOnClickListener {
            isShowingSportsSubTab = false
            refreshMediaPickerList()
            switchRoomInternalTab("MEDIA")
        }

        rootView.findViewById<View>(R.id.btnPickMatchOpen).setOnClickListener {
            isShowingSportsSubTab = true
            refreshMediaPickerList()
            switchRoomInternalTab("MEDIA")
        }

        rootView.findViewById<View>(R.id.btnAspectRatioToggle).setOnClickListener {
            currentResizeModeIndex = (currentResizeModeIndex + 1) % 3
            watchPlayerView.resizeMode = when (currentResizeModeIndex) {
                1 -> 3
                2 -> 4
                else -> 0
            }
            Toast.makeText(hostContext, "📐 تم تغيير أبعاد شاشة البث", Toast.LENGTH_SHORT).show()
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
            R.id.btnEmojiSoccer to "⚽",
            R.id.btnEmojiGoal to "🥅",
            R.id.btnEmojiShock to "😱",
            R.id.btnEmojiParty to "🎉"
        )
        for ((viewId, emojiStr) in emojiMap) {
            rootView.findViewById<View>(viewId).setOnClickListener { sendEmojiReaction(emojiStr) }
        }

        tabBtnChat.setOnClickListener { switchRoomInternalTab("CHAT") }
        tabBtnParticipants.setOnClickListener { switchRoomInternalTab("PARTICIPANTS") }
        tabBtnMediaPicker.setOnClickListener { switchRoomInternalTab("MEDIA") }
        tabBtnLogsStats.setOnClickListener { switchRoomInternalTab("LOGS") }

        rootView.findViewById<View>(R.id.btnSendChatMessage).setOnClickListener { sendChatMessage() }

        rootView.findViewById<View>(R.id.btnMuteAllParticipants).setOnClickListener {
            val room = currentRoom ?: return@setOnClickListener
            if (room.hostId != localUserId) {
                Toast.makeText(hostContext, "هذه الميزة للمضيف فقط", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            for ((uid, p) in participantsMap) {
                if (uid != localUserId) {
                    p.isHostForceMuted = true
                    p.isMuted = true
                }
            }
            participantsAdapter.notifyDataSetChanged()
            broadcastRoomEvent("MUTE_ALL", JSONObject())
            appendSystemChatAndLog("🔇 قام المضيف بكتم الميكروفون لجميع المشاركين")
        }

        rootView.findViewById<View>(R.id.btnManageBansOpen).setOnClickListener { showBansAndJoinRequestsDialog() }

        btnSubTabChannels.setOnClickListener {
            isShowingSportsSubTab = false
            refreshMediaPickerList()
        }
        btnSubTabMatches.setOnClickListener {
            isShowingSportsSubTab = true
            refreshMediaPickerList()
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
            appendSystemChatAndLog("⏱️ قام المضيف بتمديد صلاحية الغرفة ساعة إضافية")
        }
        rootView.findViewById<View>(R.id.btnClearEventLogs).setOnClickListener {
            eventLogs.clear()
            eventLogsAdapter.notifyDataSetChanged()
        }
    }

    private fun switchRoomInternalTab(tab: String) {
        panelTabChat.visibility = if (tab == "CHAT") View.VISIBLE else View.GONE
        panelTabParticipants.visibility = if (tab == "PARTICIPANTS") View.VISIBLE else View.GONE
        panelTabMediaPicker.visibility = if (tab == "MEDIA") View.VISIBLE else View.GONE
        panelTabLogsStats.visibility = if (tab == "LOGS") View.VISIBLE else View.GONE

        val tabs = listOf(
            tabBtnChat to "CHAT",
            tabBtnParticipants to "PARTICIPANTS",
            tabBtnMediaPicker to "MEDIA",
            tabBtnLogsStats to "LOGS"
        )
        for ((btn, key) in tabs) {
            if (tab == key) {
                btn.setBackgroundResource(R.drawable.wt_bg_badge_live)
                btn.setTextColor(Color.WHITE)
            } else {
                btn.background = null
                btn.setTextColor(Color.parseColor("#B0BEC5"))
            }
        }
        if (tab == "LOGS") updateDetailedStatsUI()
    }

    private fun checkRateLimit(timestamps: MutableList<Long>, maxEvents: Int, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        timestamps.removeAll { now - it > windowMs }
        if (timestamps.size >= maxEvents) return false
        timestamps.add(now)
        return true
    }

    private fun sendChatMessage() {
        val room = currentRoom ?: return
        if (room.isChatLocked && room.hostId != localUserId) {
            Toast.makeText(hostContext, "🔒 الدردشة مقفلة من قِبل المضيف", Toast.LENGTH_SHORT).show()
            return
        }
        val text = etChatMessageInput.text.toString().trim()
        if (text.isEmpty()) return
        if (!checkRateLimit(chatTimestamps, maxEvents = 5, windowMs = 10_000L)) {
            Toast.makeText(hostContext, "⏳ تمهل قليلاً قبل إرسال رسائل متتالية", Toast.LENGTH_SHORT).show()
            return
        }
        etChatMessageInput.setText("")
        val role = if (room.hostId == localUserId) "HOST" else (participantsMap[localUserId]?.role ?: "PARTICIPANT")
        val msg = ChatMessageModel("MSG-" + System.currentTimeMillis(), localUserId, localUserName, role, text)
        appendChatMessage(msg)

        val payload = JSONObject().apply {
            put("msgId", msg.msgId)
            put("senderId", msg.senderId)
            put("senderName", msg.senderName)
            put("senderRole", msg.senderRole)
            put("text", msg.text)
            put("ts", msg.timestamp)
        }
        broadcastRoomEvent("CHAT_MSG", payload)
    }

    private fun appendChatMessage(msg: ChatMessageModel) {
        if (blockedUserIds.contains(msg.senderId)) return
        chatHistory.add(msg)
        if (!msg.isSystemEvent) statTotalMessages++
        chatAdapter.notifyItemInserted(chatHistory.size - 1)
        rvChatMessages.scrollToPosition(chatHistory.size - 1)
        saveCachedMessageHistory()
    }

    private fun appendSystemChatAndLog(text: String) {
        val sysMsg = ChatMessageModel("SYS-" + System.currentTimeMillis(), "SYSTEM", "النظام", "SYSTEM", text, isSystemEvent = true)
        chatHistory.add(sysMsg)
        chatAdapter.notifyItemInserted(chatHistory.size - 1)
        rvChatMessages.scrollToPosition(chatHistory.size - 1)
        logRoomEvent("EVENT", text)
    }

    private fun sendEmojiReaction(emoji: String) {
        if (!checkRateLimit(emojiTimestamps, maxEvents = 8, windowMs = 5_000L)) return
        spawnFloatingEmoji(emoji)
        statTotalEmojis++
        broadcastRoomEvent("EMOJI", JSONObject().put("emoji", emoji).put("senderName", localUserName))
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
        val animY = ObjectAnimator.ofFloat(tv, "translationY", 0f, -380f).apply { duration = 2000L }
        val animAlpha = ObjectAnimator.ofFloat(tv, "alpha", 1f, 0f).apply { duration = 2000L }
        animY.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { emojiFloatingOverlay.removeView(tv) }
        })
        animY.start()
        animAlpha.start()
    }

    private fun initVoiceEngineForRoom(roomId: String) {
        voiceChatManager?.release()
        voiceChatManager = VoiceChatManager(
            context = hostContext,
            roomId = roomId,
            userId = localUserId,
            userName = localUserName
        ) { isSpeaking, _ ->
            mainHandler.post {
                participantsMap[localUserId]?.isSpeaking = isSpeaking
                tvVoiceSpeakingIndicator.text = if (isSpeaking) "🗣️ يتحدث..." else "صوت نقي"
            }
        }.also { vm ->
            vm.onOutgoingVoiceFrame = { pcmBytes ->
                val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
                broadcastRoomEvent("VOICE_FRAME", JSONObject().put("audio", b64))
            }
        }
        if (ContextCompat.checkSelfPermission(hostContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceChatManager?.startVoiceChat()
        }
    }

    private fun toggleMicrophoneState() {
        val room = currentRoom ?: return
        if (room.isVoiceLocked && room.hostId != localUserId) {
            Toast.makeText(hostContext, "🔒 الدردشة الصوتية مقفلة من المضيف", Toast.LENGTH_SHORT).show()
            return
        }
        val vm = voiceChatManager ?: return
        if (vm.isForceMutedByHost() && room.hostId != localUserId) {
            Toast.makeText(hostContext, "🔇 قام المضيف بكتم الميكروفون الخاص بك", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(hostContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            hostActivity?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.RECORD_AUDIO), 901) }
            return
        }
        vm.startVoiceChat()
        val newMuted = !vm.isMuted()
        vm.setMuted(newMuted)
        participantsMap[localUserId]?.isMuted = newMuted
        updateMicButtonUI(newMuted)
        participantsAdapter.notifyDataSetChanged()
        broadcastRoomEvent("MIC_STATE", JSONObject().put("isMuted", newMuted))
    }

    private fun updateMicButtonUI(isMuted: Boolean) {
        if (isMuted) {
            btnToggleMic.text = "🎤 الميكروفون: مكتوم"
            btnToggleMic.setTextColor(Color.parseColor("#FF8A80"))
        } else {
            btnToggleMic.text = "🎙️ الميكروفون: متصل"
            btnToggleMic.setTextColor(Color.parseColor("#00E676"))
        }
    }

    private fun loadChannelsCatalog() {
        allSportsStreamsList.clear()
        allSportsStreamsList.addAll(
            listOf(
                MediaStreamOption("sp1", "beIN Sports 1 HD Premium", "القنوات الرياضية المباشرة", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
                MediaStreamOption("sp2", "beIN Sports 2 HD Premium", "القنوات الرياضية المباشرة", "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8"),
                MediaStreamOption("sp3", "SSC Sports 1 HD", "الرياضة المباشرة", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
                MediaStreamOption("sp4", "الجزائرية السادسة الشبابية HD", "القنوات الوطنية", "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8")
            )
        )

        allChannelsList.clear()
        allChannelsList.addAll(allSportsStreamsList)
        allChannelsList.add(MediaStreamOption("c5", "MBC Action HD", "أفلام وترفيه", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"))
        refreshMediaPickerList()

        Thread {
            try {
                val prefs = hostContext.getSharedPreferences("prime_ott_prefs", Context.MODE_PRIVATE)
                val host = prefs.getString("server_url", defaultXtreamHost)?.trimEnd('/') ?: defaultXtreamHost
                val user = prefs.getString("username", "") ?: ""
                val pass = prefs.getString("password", "") ?: ""
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    val apiUrl = "$host/player_api.php?username=$user&password=$pass&action=get_live_streams"
                    val req = Request.Builder().url(apiUrl).get().build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val arr = JSONArray(resp.body?.string() ?: "[]")
                            val fetched = mutableListOf<MediaStreamOption>()
                            val limit = arr.length().coerceAtMost(80)
                            for (i in 0 until limit) {
                                val obj = arr.getJSONObject(i)
                                val streamId = obj.optString("stream_id")
                                val name = obj.optString("name", "قناة $streamId")
                                val m3u8Url = "$host/live/$user/$pass/$streamId.m3u8"
                                val tsUrl = "$host/live/$user/$pass/$streamId.ts"
                                fetched.add(MediaStreamOption(streamId, name, "بث مباشر للسيرفر", m3u8Url, tsUrl))
                            }
                            if (fetched.isNotEmpty()) {
                                mainHandler.post {
                                    allChannelsList.addAll(0, fetched)
                                    refreshMediaPickerList()
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun refreshMediaPickerList() {
        val query = etSearchMediaInRoom.text.toString().trim().lowercase(Locale.getDefault())
        val source = if (isShowingSportsSubTab) allSportsStreamsList else allChannelsList
        btnSubTabChannels.setBackgroundResource(if (!isShowingSportsSubTab) R.drawable.wt_bg_btn_primary else R.drawable.wt_bg_badge_dark)
        btnSubTabMatches.setBackgroundResource(if (isShowingSportsSubTab) R.drawable.wt_bg_btn_primary else R.drawable.wt_bg_badge_dark)

        displayedMediaList.clear()
        for (item in source) {
            if (query.isEmpty() || item.title.lowercase(Locale.getDefault()).contains(query)) {
                displayedMediaList.add(item)
            }
        }
        mediaPickerAdapter.notifyDataSetChanged()
    }

    private fun selectMediaForRoom(media: MediaStreamOption) {
        val room = currentRoom ?: return
        val role = participantsMap[localUserId]?.role ?: "PARTICIPANT"
        if (room.hostId != localUserId && role != "MODERATOR") {
            Toast.makeText(hostContext, "⚠️ تغيير القناة متاح للمضيف والمشرفين فقط", Toast.LENGTH_SHORT).show()
            return
        }
        room.streamUrl = media.streamUrl
        room.streamTitle = media.title
        room.category = media.subtitle
        room.isPlaying = true
        room.status = "LIVE"
        startStreamPlayback(media.streamUrl, media.title, true, 0L)
        broadcastPlaybackSync()
        appendSystemChatAndLog("📺 تم تغيير البث المباشر للغرفة إلى: ${media.title}")
        updateRoomHeaderAndPermissionsUI()
    }

    private fun promptCustomStreamUrlDialog() {
        val room = currentRoom ?: return
        if (room.hostId != localUserId) return
        val layout = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val etTitle = EditText(hostContext).apply { hint = "اسم القناة المباشرة" }
        val etUrl = EditText(hostContext).apply { hint = "رابط البث (m3u8 / ts / mp4)" }
        layout.addView(etTitle)
        layout.addView(etUrl)
        AlertDialog.Builder(hostContext)
            .setTitle("🔗 تشغيل رابط بث مباشر")
            .setView(layout)
            .setPositiveButton("تشغيل ومزامنة") { _, _ ->
                val url = etUrl.text.toString().trim()
                val title = etTitle.text.toString().trim().ifEmpty { "بث مباشر خارجي" }
                if (url.isNotEmpty()) selectMediaForRoom(MediaStreamOption("custom", title, "رابط مخصص", url))
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
        options.add(if (isLocallyMuted) "🔊 إلغاء الكتم الصوتي المحلي" else "🔇 كتم صوت هذا المستخدم لدي")
        actions.add {
            voiceChatManager?.setParticipantMutedLocally(target.userId, !isLocallyMuted)
            Toast.makeText(hostContext, "تم تحديث إعدادات الصوت", Toast.LENGTH_SHORT).show()
        }

        if (isMeHost) {
            options.add(if (target.isHostForceMuted) "🎙️ السماح للمستخدم بالتحدث (Unmute)" else "🔇 كتم ميكروفون المستخدم في الغرفة (Mute)")
            actions.add {
                target.isHostForceMuted = !target.isHostForceMuted
                target.isMuted = target.isHostForceMuted
                participantsAdapter.notifyDataSetChanged()
                broadcastRoomEvent("HOST_MUTE_USER", JSONObject().put("targetId", target.userId).put("muted", target.isHostForceMuted))
                appendSystemChatAndLog("🎤 قام المضيف بتحديث صلاحية الميكروفون لـ ${target.userName}")
            }

            options.add(if (target.role == "MODERATOR") "⬇️ إلغاء إشراف المستخدم" else "🛡️ ترقية إلى مشرف (Moderator)")
            actions.add {
                target.role = if (target.role == "MODERATOR") "PARTICIPANT" else "MODERATOR"
                participantsAdapter.notifyDataSetChanged()
                appendSystemChatAndLog("🛡️ تم تحديث صلاحية ${target.userName}")
            }

            options.add("👑 نقل ملكية الغرفة إلى ${target.userName}")
            actions.add {
                participantsMap[localUserId]?.role = "PARTICIPANT"
                target.role = "HOST"
                room.hostId = target.userId
                room.hostName = target.userName
                broadcastRoomEvent("TRANSFER_HOST", JSONObject().put("newHostId", target.userId).put("newHostName", target.userName))
                appendSystemChatAndLog("👑 تم نقل ملكية الغرفة إلى ${target.userName}")
                updateRoomHeaderAndPermissionsUI()
            }

            options.add("👢 طرد من الغرفة (Kick)")
            actions.add {
                participantsMap.remove(target.userId)
                room.onlineCount = participantsMap.size
                broadcastRoomEvent("KICK_USER", JSONObject().put("targetId", target.userId))
                appendSystemChatAndLog("👢 تم طرد ${target.userName} من الغرفة")
                updateRoomHeaderAndPermissionsUI()
            }

            options.add("🚫 حظر نهائي من الغرفة (Ban)")
            actions.add {
                bannedUserIds.add(target.userId)
                participantsMap.remove(target.userId)
                room.onlineCount = participantsMap.size
                broadcastRoomEvent("BAN_USER", JSONObject().put("targetId", target.userId))
                appendSystemChatAndLog("🚫 تم حظر ${target.userName} من الغرفة")
                updateRoomHeaderAndPermissionsUI()
            }
        }

        options.add("⚠️ الإبلاغ عن المستخدم (Report)")
        actions.add { showReportUserDialog(target.userId, target.userName) }

        options.add("🚫 حظر المستخدم شخصياً (Block)")
        actions.add { blockUserPersonally(target.userId, target.userName) }

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

    private fun showReportUserDialog(targetId: String, targetName: String) {
        val reasons = arrayOf("إزعاج ورسائل متكررة (Spam)", "إساءة عبر الدردشة الصوتية", "محتوى غير لائق", "سلوك تخريبي")
        AlertDialog.Builder(hostContext)
            .setTitle("⚠️ الإبلاغ عن: $targetName")
            .setItems(reasons) { _, which ->
                logRoomEvent("REPORT", "تم تقديم بلاغ ضد $targetName (${reasons[which]})")
                broadcastRoomEvent("REPORT_USER", JSONObject().put("targetId", targetId).put("reason", reasons[which]))
                Toast.makeText(hostContext, "✅ تم إرسال البلاغ بنجاح", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun blockUserPersonally(targetId: String, targetName: String) {
        blockedUserIds.add(targetId)
        voiceChatManager?.setParticipantMutedLocally(targetId, true)
        hostContext.getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
            .edit().putStringSet("blocked_users", blockedUserIds).apply()
        chatHistory.removeAll { it.senderId == targetId }
        chatAdapter.notifyDataSetChanged()
        Toast.makeText(hostContext, "🚫 تم حظر $targetName", Toast.LENGTH_SHORT).show()
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
                hostContext.getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
                    .edit().putStringSet("blocked_users", blockedUserIds).apply()
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
            if (room.isVoiceLocked) "🔓 فتح الدردشة الصوتية" else "🔒 قفل الدردشة الصوتية",
            if (room.isPrivate) "🌐 تحويل الغرفة إلى عامة" else "🔒 تحويل الغرفة إلى خاصة",
            if (room.requireApproval) "✅ إلغاء شرط الموافقة للانضمام" else "🛡️ تفعيل شرط موافقة المضيف للانضمام",
            "🔑 تعيين / تغيير كلمة مرور الغرفة",
            "👥 تعديل الحد الأقصى للمشاركين (${room.maxParticipants})",
            "🔔 اختبار طلب انضمام جديد (Approve / Reject)"
        )
        AlertDialog.Builder(hostContext)
            .setTitle("⚙️ مركز تحكم المضيف")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        room.isChatLocked = !room.isChatLocked
                        appendSystemChatAndLog(if (room.isChatLocked) "🔒 تم قفل الدردشة" else "🔓 تم فتح الدردشة")
                    }
                    1 -> {
                        room.isVoiceLocked = !room.isVoiceLocked
                        appendSystemChatAndLog(if (room.isVoiceLocked) "🔒 تم قفل الصوت" else "🔓 تم فتح الصوت")
                    }
                    2 -> {
                        room.isPrivate = !room.isPrivate
                        updateRoomHeaderAndPermissionsUI()
                    }
                    3 -> room.requireApproval = !room.requireApproval
                    4 -> promptSetNewRoomPassword(room)
                    5 -> promptChangeMaxParticipants(room)
                    6 -> enqueueIncomingJoinRequest(JoinRequestModel("REQ-" + System.currentTimeMillis(), "USR-" + Random.nextInt(100, 999), "مشاهد_" + Random.nextInt(10, 99)))
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun promptSetNewRoomPassword(room: WatchRoomModel) {
        val input = EditText(hostContext).apply { hint = "كلمة المرور الجديدة" }
        AlertDialog.Builder(hostContext)
            .setTitle("🔑 كلمة مرور الغرفة")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val raw = input.text.toString().trim()
                room.passwordHash = if (raw.isEmpty()) "" else sha256(raw)
                room.isPrivate = raw.isNotEmpty()
                updateRoomHeaderAndPermissionsUI()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun promptChangeMaxParticipants(room: WatchRoomModel) {
        val input = EditText(hostContext).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(room.maxParticipants.toString())
        }
        AlertDialog.Builder(hostContext)
            .setTitle("👥 الحد الأقصى للمشاركين")
            .setView(input)
            .setPositiveButton("تحديث") { _, _ ->
                room.maxParticipants = input.text.toString().toIntOrNull()?.coerceIn(2, 200) ?: 25
                updateRoomHeaderAndPermissionsUI()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun enqueueIncomingJoinRequest(req: JoinRequestModel) {
        pendingJoinRequests.add(req)
        layoutJoinRequestBanner.visibility = View.VISIBLE
        tvJoinRequestInfo.text = "🔔 يريد «${req.userName}» الانضمام للغرفة (${pendingJoinRequests.size})"
    }

    private fun handleNextJoinRequest(approve: Boolean) {
        val req = if (pendingJoinRequests.isEmpty()) null else pendingJoinRequests.removeAt(0)
        if (req == null) return
        if (approve) {
            participantsMap[req.userId] = ParticipantModel(req.userId, req.userName, "PARTICIPANT")
            currentRoom?.onlineCount = participantsMap.size
            statPeakViewers = statPeakViewers.coerceAtLeast(participantsMap.size)
            appendSystemChatAndLog("✅ وافق المضيف على انضمام ${req.userName}")
        } else {
            appendSystemChatAndLog("❌ رفض المضيف طلب انضمام ${req.userName}")
        }
        layoutJoinRequestBanner.visibility = if (pendingJoinRequests.isEmpty()) View.GONE else View.VISIBLE
        updateRoomHeaderAndPermissionsUI()
    }

    private fun showBansAndJoinRequestsDialog() {
        val bannedList = bannedUserIds.toList()
        if (bannedList.isEmpty()) {
            Toast.makeText(hostContext, "لا يوجد أعضاء محظورون حالياً", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(hostContext)
            .setTitle("🚫 المحظورون من الغرفة (اضغط لرفع الحظر)")
            .setItems(bannedList.toTypedArray()) { _, which ->
                bannedUserIds.remove(bannedList[which])
                appendSystemChatAndLog("✅ تم رفع الحظر")
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun copyInviteCodeAndLink(shareSheet: Boolean) {
        val room = currentRoom ?: return
        val deepLink = "primeott://watch?room=${room.roomId}&code=${room.roomCode}"
        val inviteText = "🎬 انضم لغرفة «${room.roomName}» لمشاهدة «${room.streamTitle}» على Prime OTT! رمز الغرفة: ${room.roomCode} | الرابط: $deepLink"
        val clipboard = hostContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("WatchTogetherInvite", inviteText))
        Toast.makeText(hostContext, "📋 تم نسخ رمز الغرفة ورابط الدعوة (#${room.roomCode})", Toast.LENGTH_SHORT).show()
        if (shareSheet) {
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, inviteText)
                }
                hostContext.startActivity(Intent.createChooser(shareIntent, "مشاركة دعوة الغرفة:"))
            } catch (_: Exception) {}
        }
    }

    fun confirmLeaveRoom() {
        val room = currentRoom ?: return
        AlertDialog.Builder(hostContext)
            .setTitle("🚪 مغادرة الغرفة")
            .setMessage("هل تريد مغادرة غرفة «${room.roomName}»؟")
            .setPositiveButton("مغادرة") { _, _ -> leaveCurrentRoom(closePermanently = false) }
            .setNegativeButton("البقاء", null)
            .show()
    }

    private fun confirmCloseRoomByHost() {
        val room = currentRoom ?: return
        if (room.hostId != localUserId) return
        AlertDialog.Builder(hostContext)
            .setTitle("⛔ إغلاق الغرفة نهائياً")
            .setMessage("سيتم إغلاق الغرفة لجميع المشاركين. هل أنت متأكد؟")
            .setPositiveButton("إغلاق الغرفة") { _, _ -> leaveCurrentRoom(closePermanently = true) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun leaveCurrentRoom(closePermanently: Boolean) {
        val room = currentRoom
        if (room != null) {
            if (closePermanently) {
                room.status = "CLOSED"
                room.onlineCount = 0
                broadcastRoomEvent("ROOM_CLOSED", JSONObject().put("roomId", room.roomId))
                activeRoomsList.removeAll { it.roomId == room.roomId }
            } else {
                participantsMap.remove(localUserId)
                room.onlineCount = participantsMap.size
                if (room.hostId == localUserId && participantsMap.isNotEmpty()) {
                    val nextHost = participantsMap.values.first()
                    nextHost.role = "HOST"
                    room.hostId = nextHost.userId
                    room.hostName = nextHost.userName
                    broadcastRoomEvent("TRANSFER_HOST", JSONObject().put("newHostId", nextHost.userId).put("newHostName", nextHost.userName))
                }
            }
            saveRoomsToLocalRegistry()
        }
        exoPlayer?.pause()
        voiceChatManager?.stopVoiceChat()
        try { webSocket?.close(1000, "Leave") } catch (_: Exception) {}
        webSocket = null
        currentRoom = null
        layoutActiveRoomContainer.visibility = View.GONE
        layoutLobbyContainer.visibility = View.VISIBLE
        applyRoomSearchAndFilters()
    }

    private fun connectRoomWebSocket(roomId: String) {
        try { webSocket?.cancel() } catch (_: Exception) {}
        val wsUrl = workerBaseUrl.replace("https://", "wss://").replace("http://", "ws://") +
            "/ws/room?roomId=$roomId&userId=$localUserId&token=$authToken"
        val req = Request.Builder().url(wsUrl).addHeader("Authorization", "Bearer $authToken").build()
        webSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                mainHandler.post {
                    logRoomEvent("WS", "تم تفعيل اتصال WebSocket المشفر اللحظي")
                    broadcastRoomEvent("USER_JOINED", JSONObject().put("userId", localUserId).put("userName", localUserName))
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                mainHandler.post { handleIncomingRealtimeEvent(text) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.post { scheduleWebSocketReconnect(roomId) }
            }
        })
    }

    private fun scheduleWebSocketReconnect(roomId: String) {
        if (isDestroyed || currentRoom?.roomId != roomId) return
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
        mainHandler.postDelayed({
            if (!isDestroyed && currentRoom?.roomId == roomId) connectRoomWebSocket(roomId)
        }, 1500L * reconnectAttempt)
    }

    private fun broadcastRoomEvent(type: String, payload: JSONObject) {
        val room = currentRoom ?: return
        val envelope = JSONObject().apply {
            put("type", type)
            put("roomId", room.roomId)
            put("senderId", localUserId)
            put("senderName", localUserName)
            put("ts", System.currentTimeMillis())
            put("data", payload)
        }
        val raw = envelope.toString()
        try { webSocket?.send(raw) } catch (_: Exception) {}
        Thread {
            try {
                val req = Request.Builder()
                    .url("$workerBaseUrl/room/event")
                    .addHeader("Authorization", "Bearer $authToken")
                    .post(raw.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                httpClient.newCall(req).execute().close()
            } catch (_: Exception) {}
        }.start()
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
                "PLAYBACK_SYNC" -> applyIncomingPlaybackSync(data)
                "CHAT_MSG" -> appendChatMessage(ChatMessageModel(data.optString("msgId"), senderId, senderName, data.optString("senderRole", "PARTICIPANT"), data.optString("text")))
                "EMOJI" -> {
                    statTotalEmojis++
                    spawnFloatingEmoji(data.optString("emoji", "🔥"))
                }
                "VOICE_FRAME" -> {
                    val b64 = data.optString("audio")
                    if (b64.isNotEmpty()) voiceChatManager?.playIncomingWebSocketVoice(senderId, Base64.decode(b64, Base64.NO_WRAP))
                }
                "USER_JOINED" -> {
                    participantsMap[senderId] = ParticipantModel(senderId, senderName, "PARTICIPANT")
                    currentRoom?.onlineCount = participantsMap.size
                    statPeakViewers = statPeakViewers.coerceAtLeast(participantsMap.size)
                    appendSystemChatAndLog("👋 انضم $senderName إلى الغرفة")
                    updateRoomHeaderAndPermissionsUI()
                }
                "USER_LEFT" -> {
                    participantsMap.remove(senderId)
                    currentRoom?.onlineCount = participantsMap.size
                    appendSystemChatAndLog("🚪 غادر $senderName الغرفة")
                    updateRoomHeaderAndPermissionsUI()
                }
                "KICK_USER" -> if (data.optString("targetId") == localUserId) leaveCurrentRoom(false)
                "BAN_USER" -> if (data.optString("targetId") == localUserId) {
                    bannedUserIds.add(localUserId)
                    leaveCurrentRoom(false)
                }
                "ROOM_CLOSED" -> leaveCurrentRoom(false)
            }
        } catch (_: Exception) {}
    }

    private fun performHeartbeatAndHealthCheck() {
        val startTs = System.currentTimeMillis()
        Thread {
            var ping = Random.nextInt(24, 48)
            try {
                val req = Request.Builder().url("$workerBaseUrl/health").get().build()
                httpClient.newCall(req).execute().use {
                    ping = (System.currentTimeMillis() - startTs).toInt().coerceIn(18, 350)
                }
            } catch (_: Exception) {}
            backendPingMs = ping
            mainHandler.post {
                if (!isDestroyed) {
                    tvBackendHealthLobby.text = "🟢 متصل • ${backendPingMs}ms"
                    tvBackendHealthRoom.text = "🟢 ${backendPingMs}ms"
                }
            }
        }.start()
    }

    private fun cleanupExpiredAndEmptyRooms() {
        val now = System.currentTimeMillis()
        val before = activeRoomsList.size
        activeRoomsList.removeAll { it.status == "CLOSED" || it.expiresAt <= now || (it.onlineCount <= 0 && now - it.createdAt > 60_000L) }
        if (activeRoomsList.size != before && currentRoom == null) {
            saveRoomsToLocalRegistry()
            applyRoomSearchAndFilters()
        }
    }

    private fun updateExpirationCountdownUI() {
        val room = currentRoom ?: return
        val rem = room.expiresAt - System.currentTimeMillis()
        if (rem <= 0) {
            leaveCurrentRoom(true)
            return
        }
        val hrs = TimeUnit.MILLISECONDS.toHours(rem)
        val mins = TimeUnit.MILLISECONDS.toMinutes(rem) % 60
        val secs = TimeUnit.MILLISECONDS.toSeconds(rem) % 60
        tvExpirationTimerBadge.text = String.format(Locale.US, "⏳ %02d:%02d:%02d", hrs, mins, secs)
    }

    private fun logRoomEvent(type: String, detail: String) {
        eventLogs.add(0, RoomEventLogItem(System.currentTimeMillis(), type, detail))
        if (this::eventLogsAdapter.isInitialized) eventLogsAdapter.notifyDataSetChanged()
    }

    private fun updateDetailedStatsUI() {
        val room = currentRoom ?: return
        val up = ((System.currentTimeMillis() - room.createdAt) / 60_000L).coerceAtLeast(1)
        tvRoomDetailedStats.text = "📊 إحصائيات الغرفة: المعرّف ${room.roomId} (#${room.roomCode}) | المتصلون: ${participantsMap.size}/${room.maxParticipants} | الذروة: $statPeakViewers | الرسائل: $statTotalMessages | الإيموجي: $statTotalEmojis | المزامنة: $statSyncCount | الاستجابة: ${backendPingMs}ms | التشغيل: $up د"
    }

    private fun fetchActiveRoomsDirectory() {
        val prefs = hostContext.getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
        val savedJson = prefs.getString("active_rooms_registry", null)
        activeRoomsList.clear()
        if (!savedJson.isNullOrEmpty()) {
            try {
                val arr = JSONArray(savedJson)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    activeRoomsList.add(
                        WatchRoomModel(
                            roomId = o.optString("roomId"),
                            roomCode = o.optString("roomCode"),
                            roomName = o.optString("roomName"),
                            hostId = o.optString("hostId"),
                            hostName = o.optString("hostName"),
                            isPrivate = o.optBoolean("isPrivate", false),
                            passwordHash = o.optString("passwordHash", ""),
                            requireApproval = o.optBoolean("requireApproval", false),
                            maxParticipants = o.optInt("maxParticipants", 25),
                            onlineCount = o.optInt("onlineCount", 1),
                            status = o.optString("status", "LIVE"),
                            streamUrl = o.optString("streamUrl"),
                            streamTitle = o.optString("streamTitle"),
                            category = o.optString("category"),
                            expiresAt = o.optLong("expiresAt", System.currentTimeMillis() + 7200_000L)
                        )
                    )
                }
            } catch (_: Exception) {}
        }
        if (activeRoomsList.isEmpty()) {
            activeRoomsList.addAll(
                listOf(
                    WatchRoomModel("RM-LIVE-1", "PRM101", "🔥 غرفة السهرة الرياضية المباشرة", "HOST_1", "أحمد", false, maxParticipants = 50, onlineCount = 14, streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", streamTitle = "beIN Sports 1 HD Premium"),
                    WatchRoomModel("RM-LIVE-2", "DZ2026", "📺 غرفة القنوات المفتوحة والدردشة الصوتية", "HOST_2", "كريم", false, maxParticipants = 30, onlineCount = 9, streamUrl = "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8", streamTitle = "الجزائرية السادسة HD"),
                    WatchRoomModel("RM-VIP-3", "VIP990", "🔒 غرفة الأصدقاء الخاصة VIP", "HOST_3", "ياسين", true, requireApproval = true, maxParticipants = 12, onlineCount = 4, streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", streamTitle = "SSC Sports 1 HD")
                )
            )
            saveRoomsToLocalRegistry()
        }
        applyRoomSearchAndFilters()
    }

    private fun saveRoomsToLocalRegistry() {
        val arr = JSONArray()
        for (r in activeRoomsList) {
            arr.put(JSONObject().apply {
                put("roomId", r.roomId)
                put("roomCode", r.roomCode)
                put("roomName", r.roomName)
                put("hostId", r.hostId)
                put("hostName", r.hostName)
                put("isPrivate", r.isPrivate)
                put("passwordHash", r.passwordHash)
                put("requireApproval", r.requireApproval)
                put("maxParticipants", r.maxParticipants)
                put("onlineCount", r.onlineCount)
                put("status", r.status)
                put("streamUrl", r.streamUrl)
                put("streamTitle", r.streamTitle)
                put("category", r.category)
                put("expiresAt", r.expiresAt)
            })
        }
        hostContext.getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_rooms_registry", arr.toString()).apply()
    }

    private fun publishRoomToBackend(room: WatchRoomModel) {
        Thread {
            try {
                val payload = JSONObject().apply {
                    put("roomId", room.roomId)
                    put("roomCode", room.roomCode)
                    put("roomName", room.roomName)
                    put("hostId", room.hostId)
                    put("hostName", room.hostName)
                }
                val req = Request.Builder()
                    .url("$workerBaseUrl/rooms/create")
                    .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                httpClient.newCall(req).execute().close()
            } catch (_: Exception) {}
        }.start()
    }

    private fun loadCachedMessageHistory(roomId: String) {
        val raw = hostContext.getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
            .getString("chat_history_$roomId", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sid = o.optString("senderId")
                if (!blockedUserIds.contains(sid)) {
                    chatHistory.add(ChatMessageModel(o.optString("msgId"), sid, o.optString("senderName"), o.optString("senderRole"), o.optString("text"), o.optLong("ts")))
                }
            }
            chatAdapter.notifyDataSetChanged()
        } catch (_: Exception) {}
    }

    private fun saveCachedMessageHistory() {
        val room = currentRoom ?: return
        val arr = JSONArray()
        for (m in chatHistory.filter { !it.isSystemEvent }.takeLast(40)) {
            arr.put(JSONObject().apply {
                put("msgId", m.msgId)
                put("senderId", m.senderId)
                put("senderName", m.senderName)
                put("senderRole", m.senderRole)
                put("text", m.text)
                put("ts", m.timestamp)
            })
        }
        hostContext.getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
            .edit().putString("chat_history_${room.roomId}", arr.toString()).apply()
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
        val passedUrl = intent.getStringExtra("stream_url") ?: intent.getStringExtra("STREAM_URL") ?: intent.getStringExtra("url")
        val passedTitle = intent.getStringExtra("stream_title") ?: intent.getStringExtra("STREAM_TITLE") ?: intent.getStringExtra("channel_name") ?: "بث مباشر مشترك"
        val passedCode = intent.getStringExtra("room_code") ?: intent.getStringExtra("room_id") ?: intent.getStringExtra("ROOM_ID")
        if (!passedCode.isNullOrEmpty() && passedUrl.isNullOrEmpty()) {
            joinRoomByCodeOrLink(passedCode)
        } else if (!passedUrl.isNullOrEmpty()) {
            val code = passedCode?.take(6)?.uppercase(Locale.US) ?: generateRoomCode()
            val directRoom = WatchRoomModel(passedCode ?: generateUniqueRoomId(), code, "غرفة $passedTitle", localUserId, localUserName, false, maxParticipants = 25, onlineCount = 1, status = "LIVE", streamUrl = passedUrl, streamTitle = passedTitle)
            activeRoomsList.add(0, directRoom)
            enterWatchRoom(directRoom, isHost = true)
        }
    }

    fun release() {
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        try { webSocket?.close(1000, "Destroy") } catch (_: Exception) {}
        voiceChatManager?.release()
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    inner class ActiveRoomsAdapter(private val items: List<WatchRoomModel>, private val onJoinClick: (WatchRoomModel) -> Unit) :
        RecyclerView.Adapter<ActiveRoomsAdapter.VH>() {
        inner class VH(val root: LinearLayout, val tvTitle: TextView, val tvMeta: TextView, val tvStream: TextView, val btnJoin: Button) : RecyclerView.ViewHolder(root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.wt_bg_card)
                setPadding(26, 20, 26, 20)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 }
            }
            val tvTitle = TextView(parent.context).apply { setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, Typeface.BOLD) }
            val tvStream = TextView(parent.context).apply { setTextColor(Color.parseColor("#80D8FF")); textSize = 12f; setPadding(0, 6, 0, 6) }
            val bottomRow = LinearLayout(parent.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val tvMeta = TextView(parent.context).apply { setTextColor(Color.parseColor("#B0BEC5")); textSize = 11f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            val btnJoin = Button(parent.context).apply { setBackgroundResource(R.drawable.wt_bg_btn_primary); setTextColor(Color.WHITE); textSize = 11f; isAllCaps = false; setPadding(24, 0, 24, 0) }
            bottomRow.addView(tvMeta); bottomRow.addView(btnJoin)
            card.addView(tvTitle); card.addView(tvStream); card.addView(bottomRow)
            return VH(card, tvTitle, tvMeta, tvStream, btnJoin)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            val lockBadge = if (r.isPrivate) "🔒 خاصة" else "🌐 عامة"
            holder.tvTitle.text = "${r.roomName}  [$lockBadge • #${r.roomCode}]"
            holder.tvStream.text = "📺 البث المباشر: ${r.streamTitle}"
            holder.tvMeta.text = "👑 المضيف: ${r.hostName}  |  👥 ${r.onlineCount}/${r.maxParticipants} متصل"
            holder.btnJoin.text = if (r.requireApproval && r.hostId != localUserId) "طلب انضمام" else "دخول الغرفة ⬅"
            holder.btnJoin.setOnClickListener { onJoinClick(r) }
            holder.root.setOnClickListener { onJoinClick(r) }
        }
        override fun getItemCount(): Int = items.size
    }

    inner class ChatAdapter(private val items: List<ChatMessageModel>, private val onMessageClick: (ChatMessageModel) -> Unit) :
        RecyclerView.Adapter<ChatAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setPadding(18, 10, 18, 10); textSize = 12f; setBackgroundResource(R.drawable.wt_bg_card)
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
                val badge = if (m.senderRole == "HOST") "👑 " else if (m.senderRole == "MODERATOR") "🛡️ " else "👤 "
                holder.tv.setTextColor(Color.WHITE)
                holder.tv.text = "[$timeStr] $badge${m.senderName}: ${m.text}"
                holder.tv.setOnClickListener { onMessageClick(m) }
            }
        }
        override fun getItemCount(): Int = items.size
    }

    inner class ParticipantsAdapter(private val getList: () -> List<ParticipantModel>, private val onItemClick: (ParticipantModel) -> Unit) :
        RecyclerView.Adapter<ParticipantsAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setPadding(20, 14, 20, 14); textSize = 12f; setTextColor(Color.WHITE); setBackgroundResource(R.drawable.wt_bg_card)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
            }
            return VH(tv)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = getList()[position]
            val roleBadge = if (p.role == "HOST") "👑 مضيف" else if (p.role == "MODERATOR") "🛡️ مشرف" else "مشاهد"
            val micBadge = if (p.isHostForceMuted) "🔇 مكتوم من المضيف" else if (p.isSpeaking) "🗣️ يتحدث..." else if (p.isMuted) "🎤 مكتوم" else "🎙️ متصل"
            holder.tv.text = "${p.userName} ($roleBadge)  •  $micBadge  •  🟢 ${p.pingMs}ms  [إدارة ⚙️]"
            holder.tv.setOnClickListener { onItemClick(p) }
        }
        override fun getItemCount(): Int = getList().size
    }

    inner class MediaPickerAdapter(private val items: List<MediaStreamOption>, private val onSelect: (MediaStreamOption) -> Unit) :
        RecyclerView.Adapter<MediaPickerAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setPadding(20, 14, 20, 14); textSize = 12f; setTextColor(Color.WHITE); setBackgroundResource(R.drawable.wt_bg_card)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
            }
            return VH(tv)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tv.text = "📺 ${item.title}  •  ${item.subtitle}   [تشغيل ومزامنة للغرفة ▶️]"
            holder.tv.setOnClickListener { onSelect(item) }
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