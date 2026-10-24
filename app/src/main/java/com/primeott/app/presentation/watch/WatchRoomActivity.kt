package com.primeott.app.presentation.watch

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.data.repository.RoomSyncResult
import com.primeott.app.domain.model.StreamSource
import com.primeott.app.presentation.player.PrimePlayerEngine
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WatchTogetherController(
    private val hostContext: Context,
    private val rootView: View,
    private val hostActivity: Activity?,
    private val isStandaloneActivity: Boolean
) {
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OttRepository.okHttpClient

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
    private var isHandlingRemoteSync = false

    private var voiceChatManager: VoiceChatManager? = null
    private lateinit var playerEngine: PrimePlayerEngine
    private lateinit var streamSniffer: WatchRoomStreamSniffer
    private lateinit var subtitleManager: WatchRoomSubtitleManager
    private lateinit var playerUI: WatchRoomPlayerUI
    private lateinit var dialogsManager: WatchRoomDialogs
    private lateinit var syncClient: WatchRoomSyncClient

    private lateinit var playerFrameContainer: FrameLayout
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

    private val syncAndHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            val room = currentRoom
            if (room != null && !streamSniffer.isExtractingNewMovie) {
                syncClient.performCloudflareRoomSync(
                    room = room,
                    isHost = room.hostId == localUserId,
                    isPlaying = playerEngine.isPlaying(),
                    currentPosMs = playerEngine.player?.currentPosition ?: 0L,
                    lastSyncedMessageId = lastSyncedMessageId,
                    isMicMuted = voiceChatManager?.isMuted() ?: true
                )
            }
            updateExpirationCountdownUI()
            mainHandler.postDelayed(this, 4000L)
        }
    }

    fun init() {
        initIdentityAndPreferences()
        bindViews()
        eliminateBottomEmptySpace()
        initSubModules()
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
        authToken = WatchRoomUtils.sha256("$localUserId:$localUserName:PRIME_OTT_SECRET").take(32)

        val prefs = hostContext.getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
        blockedUserIds.addAll(prefs.getStringSet("blocked_users", emptySet()) ?: emptySet())
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

    private fun initSubModules() {
        dialogsManager = WatchRoomDialogs(hostContext, httpClient, controllerScope)
        subtitleManager = WatchRoomSubtitleManager(hostContext, httpClient, controllerScope)

        syncClient = WatchRoomSyncClient(
            httpClient = httpClient,
            scope = controllerScope,
            workerBaseUrl = workerBaseUrl,
            localUserId = localUserId,
            localUserName = localUserName,
            authToken = authToken,
            blockedUserIds = blockedUserIds,
            onChatMessageReceived = { msg -> appendChatMessage(msg) },
            onEmojiReceived = { emoji -> spawnFloatingEmoji(emoji) },
            onSyncResultReceived = { res -> applyServerSyncResult(res) }
        )

        streamSniffer = WatchRoomStreamSniffer(
            hostContext = hostContext,
            rootView = rootView,
            scope = controllerScope,
            onStatusUpdate = { statusText ->
                if (this::playerUI.isInitialized) {
                    playerUI.updateServerPillText(statusText)
                }
            },
            onBufferingChanged = { isBuffering ->
                if (this::playerUI.isInitialized) playerUI.setBuffering(isBuffering)
            },
            onStreamResolved = { source, title, isMovie ->
                applyResolvedStreamSourceToRoom(source, title, isMovie)
            }
        )

        playerEngine = PrimePlayerEngine(
            context = hostContext,
            scope = controllerScope,
            onSourceSwitched = { _, src ->
                mainHandler.post {
                    val srvLabel = src.label.ifBlank { "REYZO ${streamSniffer.currentProviderIndex + 1}" }
                    currentRoom?.streamServerLabel = srvLabel
                    currentRoom?.streamUrl = src.url
                    currentRoom?.streamReferer = src.referer
                    currentRoom?.streamOrigin = src.origin
                    if (this::playerUI.isInitialized) {
                        playerUI.updateServerPillText("  $srvLabel")
                    }
                }
            },
            onRealQualityDetected = { qualityLabel, _ ->
                mainHandler.post {
                    if (this::playerUI.isInitialized && playerUI.selectedQualityHeight == null) {
                        playerUI.updateQualityPillText("  $qualityLabel")
                    }
                }
            },
            onFirstFrameRendered = {
                mainHandler.post {
                    playerUI.setBuffering(false)
                    playerUI.updatePlayPauseIcon(true)
                    playerUI.scheduleAutoHideControls()
                }
            },
            onPlaybackExhausted = {
                mainHandler.post {
                    val nextIdx = streamSniffer.currentProviderTargets.indices.firstOrNull { it !in streamSniffer.triedProviderIndices }
                    if (nextIdx != null) {
                        streamSniffer.loadProviderInHeadlessSniffer(nextIdx, currentRoom?.streamTitle ?: "")
                    } else {
                        streamSniffer.isExtractingNewMovie = false
                        playerUI.setBuffering(false)
                    }
                }
            }
        )

        playerUI = WatchRoomPlayerUI(
            hostContext = hostContext,
            rootView = rootView,
            hostActivity = hostActivity,
            watchPlayerView = watchPlayerView,
            playerFrameContainer = playerFrameContainer,
            playerEngine = playerEngine,
            subtitleManager = subtitleManager,
            scope = controllerScope,
            onTogglePlayPause = { toggleSyncPlayPauseAction() },
            onSeekByDeltaMs = { deltaMs -> seekRoomPlayerByDeltaMs(deltaMs) },
            onSeekToExactMs = { targetPos ->
                val room = currentRoom
                val exo = playerEngine.player
                if (room != null && exo != null) {
                    if (room.hostId == localUserId) {
                        exo.seekTo(targetPos)
                        room.playbackPositionMs = targetPos
                        triggerPlaybackSyncBroadcast()
                    } else {
                        Toast.makeText(hostContext, "⚠️ تقديم الفيديو متاح للمضيف فقط", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onServerSelected = { idx, srv -> switchServerInRoom(idx, srv) },
            getProviderTargets = { streamSniffer.currentProviderTargets },
            getCurrentProviderIndex = { streamSniffer.currentProviderIndex },
            getCurrentStreamId = { currentRoom?.streamId ?: "" },
            onPlayStateTextUpdate = { isPlaying ->
                btnSyncPlayPause.text = if (isPlaying) "⏸️ إيقاف مؤقت" else "▶️ تشغيل متزامن"
            }
        )
        playerUI.buildOverlayUI()
    }

    private fun triggerPlaybackSyncBroadcast() {
        val room = currentRoom ?: return
        if (streamSniffer.isExtractingNewMovie || room.streamUrl.isBlank()) return
        statSyncCount++
        val player = playerEngine.player
        syncClient.broadcastPlaybackSync(
            room = room,
            isPlaying = player?.isPlaying ?: room.isPlaying,
            currentPosMs = player?.currentPosition ?: room.playbackPositionMs,
            lastSyncedMessageId = lastSyncedMessageId,
            isMicMuted = voiceChatManager?.isMuted() ?: true
        )
        updateDetailedStatsUI()
    }

    private fun switchServerInRoom(index: Int, target: StreamSource) {
        val room = currentRoom ?: return
        if (room.hostId != localUserId) {
            Toast.makeText(hostContext, "⚠️ تغيير السيرفر متاح للمضيف فقط", Toast.LENGTH_SHORT).show()
            return
        }
        streamSniffer.currentProviderIndex = index
        playerUI.selectedQualityHeight = null
        playerUI.updateServerPillText("  ${target.label.ifBlank { "REYZO ${index + 1}" }}")
        playerUI.setBuffering(true)

        if (target.isDirectStream || streamSniffer.isLikelyDirectStreamUrl(target.url)) {
            playStreamSourceInEngine(target, room.streamTitle, seekMs = playerEngine.player?.currentPosition ?: 0L)
            triggerPlaybackSyncBroadcast()
        } else {
            streamSniffer.loadProviderInHeadlessSniffer(index, room.streamTitle)
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
        playerUI.updateSeekProgressUI(newPos, dur)
        triggerPlaybackSyncBroadcast()
        playerUI.resetAutoHideControls()
    }

    private fun eliminateBottomEmptySpace() {
        val bottomPad = if (isStandaloneActivity) WatchRoomUtils.dp(hostContext, 6) else WatchRoomUtils.dp(hostContext, 56)
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
            p.setPadding(p.paddingLeft, p.paddingTop, p.paddingRight, WatchRoomUtils.dp(hostContext, 4))
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
            bottomMargin = WatchRoomUtils.dp(hostContext, 4)
        }
    }

    private fun setupRecyclerViews() {
        activeRoomsAdapter = ActiveRoomsAdapter(hostContext, filteredRoomsList) { room -> joinRoomByCodeOrLink(room.roomCode) }
        rvActiveRooms.layoutManager = LinearLayoutManager(hostContext)
        rvActiveRooms.adapter = activeRoomsAdapter

        chatAdapter = ChatAdapter(hostContext, chatHistory) { msg -> showUserInteractionDialog(msg.senderId, msg.senderName) }
        rvChatMessages.layoutManager = LinearLayoutManager(hostContext)
        rvChatMessages.adapter = chatAdapter

        participantsAdapter = ParticipantsAdapter(
            hostContext,
            getList = { participantsMap.values.sortedByDescending { it.role == "HOST" } }
        ) { participant -> openParticipantDialog(participant) }
        rvParticipantsList.layoutManager = LinearLayoutManager(hostContext)
        rvParticipantsList.adapter = participantsAdapter

        mediaPickerAdapter = MediaPickerAdapter(hostContext, httpClient, controllerScope, displayedMediaList) { mediaOption -> selectMediaForRoom(mediaOption) }
        updateMediaPickerLayoutManager()
        rvMediaPickerList.adapter = mediaPickerAdapter

        eventLogsAdapter = EventLogsAdapter(hostContext, eventLogs)
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

    private fun openMoviesGridModal() {
        dialogsManager.showMoviesGridModalDialog(
            allMoviesList = allMoviesList,
            onSwitchToChannels = {
                isShowingMoviesSubTab = false
                refreshMediaPickerList()
                switchRoomInternalTab("MEDIA")
            },
            onMovieSelected = { chosen -> selectMediaForRoom(chosen) }
        )
    }

    private fun setupLobbyListeners() {
        rootView.findViewById<View>(R.id.btnBlockedUsersLobby).setOnClickListener {
            dialogsManager.showBlockedUsersDialog(blockedUserIds)
        }
        rootView.findViewById<View>(R.id.btnCreateRoomOpen).setOnClickListener {
            dialogsManager.showCreateRoomDialog(localUserName) { title, isPriv, rawPass ->
                createNewRoomOnServer(title, isPriv, rawPass)
            }
        }
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

    private fun createNewRoomOnServer(title: String, isPriv: Boolean, rawPass: String) {
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
                passwordHash = if (isPriv && rawPass.isNotEmpty()) WatchRoomUtils.sha256(rawPass) else "",
                onlineCount = 1,
                status = "LIVE",
                streamUrl = "",
                streamTitle = "اختر فيلماً أو قناة للبدء",
                isPlaying = false
            )
            enterWatchRoom(newRoom, isHost = true)
        }
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
                val (parsedUrl, parsedRef) = WatchRoomStreamSniffer.unpackUrlAndReferer(r.streamUrl)
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
                dialogsManager.promptServerRoomPassword(extractedCode) { code, pass ->
                    joinRoomByCodeOrLink(code, pass)
                }
            } else {
                Toast.makeText(hostContext, "❌ لم يتم العثور على الغرفة", Toast.LENGTH_LONG).show()
            }
        }
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

        playerUI.updatePlayerTitleText(room.streamTitle)

        // التحقق أن الرابط هو بث مباشر حقيقي (m3u8/mp4) وليس صفحة Embed
        val hasValidDirectUrl = room.streamUrl.isNotBlank() &&
            (!room.isMovie || streamSniffer.isLikelyDirectStreamUrl(room.streamUrl))

        if (hasValidDirectUrl) {
            val initialSource = StreamSource(
                label = room.streamServerLabel.ifBlank { "REYZO 1" },
                url = room.streamUrl,
                referer = room.streamReferer,
                origin = room.streamOrigin.ifBlank { WatchRoomStreamSniffer.extractOriginFromUrl(room.streamReferer) },
                userAgent = room.streamUserAgent,
                isDirectStream = true
            )
            playStreamSourceInEngine(initialSource, room.streamTitle, room.playbackPositionMs)
            if (room.streamId.isNotBlank() && room.streamId != "0") {
                subtitleManager.loadSubtitlesForMovieAutomatically(room.streamId)
                streamSniffer.fetchBackupServersInBackground(room.streamId, room.streamTitle)
            }
        } else if (room.streamId.isNotBlank() && room.streamId != "0") {
            // إذا كان الفيلم قادماً من الخارج بدون رابط مباشر، نستخرجه عبر الـ Sniffer تماماً كما نفعل داخل الغرفة
            playerUI.setBuffering(true)
            playerUI.updateServerPillText("⏳ جاري الجلب...")
            streamSniffer.extractAndPlayMovie(
                MediaStreamOption(
                    id = room.streamId,
                    title = room.streamTitle,
                    subtitle = "🎬 فيلم",
                    streamUrl = "",
                    isMovie = true
                )
            ) { cleanTmdbId ->
                subtitleManager.loadSubtitlesForMovieAutomatically(cleanTmdbId)
            }
        } else {
            playerEngine.pauseAndReleaseSocket()
            playerUI.setBuffering(false)
            if (isHost) {
                openMoviesGridModal()
            }
        }

        syncClient.connectWebSocket(room.roomCode)
        initVoiceEngineForRoom(room.roomCode)
        updateDetailedStatsUI()
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
        val (cleanUrl, embeddedRef) = WatchRoomStreamSniffer.unpackUrlAndReferer(source.url.trim())
        if (cleanUrl.isEmpty()) return

        val finalRef = source.referer.ifBlank { embeddedRef.ifBlank { WatchRoomStreamSniffer.extractOriginFromUrl(cleanUrl) } }
        val finalOrigin = source.origin.ifBlank { WatchRoomStreamSniffer.extractOriginFromUrl(finalRef).ifBlank { WatchRoomStreamSniffer.extractOriginFromUrl(cleanUrl) } }

        val readySource = source.copy(
            url = cleanUrl,
            referer = finalRef,
            origin = finalOrigin,
            userAgent = source.userAgent.ifBlank { SessionManager.CUSTOM_USER_AGENT },
            isDirectStream = true
        )

        playerUI.updatePlayerTitleText(title)
        playerUI.updateServerPillText("  ${readySource.label.ifBlank { "REYZO 1" }}")
        playerUI.setBuffering(true)
        playerUI.showControlsWithFade()

        val allToPlay = mutableListOf(readySource)
        streamSniffer.resolvedDirectStreams.forEach { s ->
            if (s.url != readySource.url) allToPlay.add(s)
        }

        playerEngine.playSources(
            playerView = watchPlayerView,
            sources = allToPlay,
            startIndex = 0,
            startPositionMs = seekMs
        )
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
        playerUI.resetProgressUI()

        room.streamTitle = media.title
        room.streamId = media.id
        room.streamUrl = ""
        room.playbackPositionMs = 0L

        triggerPlaybackSyncBroadcast()

        if (media.isMovie && media.streamUrl.isEmpty()) {
            playerUI.updatePlayerTitleText(media.title)
            playerUI.showControlsWithFade()
            streamSniffer.extractAndPlayMovie(media) { cleanTmdbId ->
                subtitleManager.loadSubtitlesForMovieAutomatically(cleanTmdbId)
            }
        } else {
            streamSniffer.isExtractingNewMovie = false
            val chSource = StreamSource(
                label = "LIVE TV",
                url = media.streamUrl,
                referer = media.referer,
                isDirectStream = true
            )
            applyResolvedStreamSourceToRoom(chSource, media.title, isMovie = false)
        }
    }

    private fun applyResolvedStreamSourceToRoom(
        source: StreamSource,
        title: String,
        isMovie: Boolean
    ) {
        val room = currentRoom ?: return
        streamSniffer.isExtractingNewMovie = false
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
        triggerPlaybackSyncBroadcast()
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
            playerUI.updatePlayPauseIcon(false)
        } else {
            playerEngine.resume()
            room.isPlaying = true
            room.status = "LIVE"
            playerUI.updatePlayPauseIcon(true)
        }
        room.playbackPositionMs = exo.currentPosition
        triggerPlaybackSyncBroadcast()
        updateRoomHeaderAndPermissionsUI()
    }

    private fun setupRoomListeners() {
        rootView.findViewById<View>(R.id.btnLeaveRoom).setOnClickListener { confirmLeaveRoom() }
        btnCloseRoomHost.setOnClickListener {
            val room = currentRoom ?: return@setOnClickListener
            if (room.hostId == localUserId) {
                dialogsManager.confirmCloseRoomByHost { leaveCurrentRoom(closePermanently = true) }
            }
        }
        btnHostControlsOpen.setOnClickListener {
            val room = currentRoom ?: return@setOnClickListener
            if (room.hostId == localUserId) {
                dialogsManager.showHostControlsCenterDialog(room) { updateRoomHeaderAndPermissionsUI() }
            }
        }
        tvRoomCodeBadge.setOnClickListener { copyInviteCodeAndLink(shareSheet = false) }
        rootView.findViewById<View>(R.id.btnInviteShare).setOnClickListener { copyInviteCodeAndLink(shareSheet = true) }
        tvOnlineCountBadge.setOnClickListener { switchRoomInternalTab("PARTICIPANTS") }

        btnSyncPlayPause.setOnClickListener { toggleSyncPlayPauseAction() }

        rootView.findViewById<View>(R.id.btnForceSyncNow).setOnClickListener {
            val room = currentRoom ?: return@setOnClickListener
            if (room.hostId == localUserId) {
                triggerPlaybackSyncBroadcast()
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

        rootView.findViewById<View>(R.id.btnPickMatchOpen).setOnClickListener { openMoviesGridModal() }
        rootView.findViewById<View>(R.id.btnAspectRatioToggle).setOnClickListener { playerUI.toggleAspectRatio() }
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
        tabBtnMediaPicker.setOnClickListener { openMoviesGridModal() }
        tabBtnLogsStats.setOnClickListener { switchRoomInternalTab("LOGS") }

        rootView.findViewById<View>(R.id.btnSendChatMessage).setOnClickListener { sendChatMessage() }

        rootView.findViewById<View>(R.id.btnMuteAllParticipants).setOnClickListener {
            val room = currentRoom ?: return@setOnClickListener
            if (room.hostId != localUserId) return@setOnClickListener
            controllerScope.launch {
                OttRepository.manageWatchRoom(room.roomId, localUserId, "MUTE_ALL")
            }
            syncClient.broadcastRoomEvent(room.roomId, "MUTE_ALL", JSONObject())
            appendSystemChatAndLog("🔇 قام المضيف بكتم الميكروفون للجميع")
        }

        btnSubTabChannels.setOnClickListener {
            isShowingMoviesSubTab = false
            refreshMediaPickerList()
        }
        btnSubTabMatches.setOnClickListener { openMoviesGridModal() }
        rootView.findViewById<View>(R.id.btnCustomStreamUrl).setOnClickListener {
            val room = currentRoom ?: return@setOnClickListener
            if (room.hostId == localUserId) {
                dialogsManager.promptCustomStreamUrlDialog { title, url ->
                    val customSrc = StreamSource(label = "CUSTOM", url = url, isDirectStream = true)
                    applyResolvedStreamSourceToRoom(customSrc, title, isMovie = true)
                }
            }
        }

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
        syncClient.broadcastRoomEvent(room.roomId, "CHAT_MSG", payload)
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
        syncClient.broadcastRoomEvent(room.roomId, "EMOJI", JSONObject().put("emoji", emoji))
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

    private fun applyServerSyncResult(res: RoomSyncResult) {
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

        if (room.hostId != localUserId && !streamSniffer.isExtractingNewMovie) {
            isHandlingRemoteSync = true
            try {
                val (srvCleanUrl, srvRef) = WatchRoomStreamSniffer.unpackUrlAndReferer(srvRoom.streamUrl)
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
                        if (srvRoom.playbackPositionMs > 0 && abs(player.currentPosition - srvRoom.playbackPositionMs) > 8000L) {
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

                val isCompletelyDead = r.participantCount <= 0

                if (isMyAbandonedRoom || isCompletelyDead) {
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

    private fun openParticipantDialog(target: ParticipantModel) {
        val room = currentRoom ?: return
        if (target.userId == localUserId) return
        val isMeHost = room.hostId == localUserId
        val isLocallyMuted = voiceChatManager?.isParticipantMutedLocally(target.userId) == true

        dialogsManager.showParticipantManagementDialog(
            target = target,
            isMeHost = isMeHost,
            isLocallyMuted = isLocallyMuted,
            onToggleLocalMute = {
                voiceChatManager?.setParticipantMutedLocally(target.userId, !isLocallyMuted)
            },
            onKickParticipant = {
                controllerScope.launch {
                    OttRepository.manageWatchRoom(room.roomId, localUserId, "KICK", target.userId)
                }
                participantsMap.remove(target.userId)
                updateRoomHeaderAndPermissionsUI()
            }
        )
    }

    private fun showUserInteractionDialog(targetId: String, targetName: String) {
        if (targetId == "SYSTEM" || targetId == localUserId) return
        val existing = participantsMap[targetId] ?: ParticipantModel(targetId, targetName)
        openParticipantDialog(existing)
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
        if (playerUI.isFullscreenPlayer) {
            playerUI.toggleFullscreen(false)
            return
        }
        val room = currentRoom ?: return
        val isHost = room.hostId == localUserId
        dialogsManager.confirmLeaveRoom(room.roomName, isHost) { closePerm ->
            leaveCurrentRoom(closePermanently = closePerm)
        }
    }

    fun leaveCurrentRoom(closePermanently: Boolean) {
        if (playerUI.isFullscreenPlayer) playerUI.toggleFullscreen(false)
        val room = currentRoom
        if (room != null) {
            val action = if (closePermanently || room.hostId == localUserId) "CLOSE_ROOM" else "LEAVE"
            controllerScope.launch {
                OttRepository.manageWatchRoom(room.roomId, localUserId, action)
                fetchActiveRoomsDirectory(cleanOldSelfRooms = false)
            }
        }
        streamSniffer.cleanupHeadlessSniffer()
        playerEngine.pauseAndReleaseSocket()
        voiceChatManager?.stopVoiceChat()
        syncClient.disconnectWebSocket()
        currentRoom = null
        layoutActiveRoomContainer.visibility = View.GONE
        layoutLobbyContainer.visibility = View.VISIBLE
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
            ?: intent.getStringExtra("movie_url")
            ?: ""
        val passedStreamId = intent.getStringExtra("stream_id")
            ?: intent.getStringExtra("tmdb_id")
            ?: intent.getStringExtra("movie_id")
            ?: intent.getStringExtra("id")
            ?: ""
        val passedReferer = intent.getStringExtra("stream_referer") ?: ""
        val passedUa = intent.getStringExtra("stream_ua") ?: SessionManager.CUSTOM_USER_AGENT
        val passedTitle = intent.getStringExtra(WatchRoomActivity.EXTRA_STREAM_TITLE)
            ?: intent.getStringExtra(WatchRoomActivity.EXTRA_CHANNEL_NAME)
            ?: intent.getStringExtra("title")
            ?: intent.getStringExtra("movie_title")
            ?: ""
        val passedRoomName = intent.getStringExtra(WatchRoomActivity.EXTRA_ROOM_NAME) ?: ""
        val passedCode = intent.getStringExtra(WatchRoomActivity.EXTRA_ROOM_CODE)
            ?: intent.getStringExtra(WatchRoomActivity.EXTRA_ROOM_ID)
            ?: ""
        val isDirectMovie = intent.getBooleanExtra("direct_movie_launch", false)
        val isHostFlag = intent.getBooleanExtra(WatchRoomActivity.EXTRA_IS_HOST, true)

        if (isDirectMovie || passedUrl.isNotEmpty() || passedStreamId.isNotEmpty()) {
            val titleToUse = passedTitle.ifEmpty { "فيلم مشترك" }
            val rName = passedRoomName.ifEmpty { "غرفة $titleToUse" }
            val isDirectValid = passedUrl.isNotEmpty() && streamSniffer.isLikelyDirectStreamUrl(passedUrl)

            controllerScope.launch {
                val finalCode = if (passedCode.isNotEmpty()) {
                    passedCode
                } else {
                    val (ok, srvCode) = OttRepository.createWatchRoom(
                        deviceId = localUserId,
                        userName = localUserName,
                        roomName = rName,
                        roomIcon = "🎬",
                        isPrivate = false,
                        password = "",
                        allowGuestsControl = true,
                        contentType = "VOD",
                        streamId = passedStreamId.ifEmpty { "0" },
                        contentTitle = titleToUse,
                        contentPoster = "",
                        streamUrl = if (isDirectValid) passedUrl else ""
                    )
                    if (ok && srvCode.isNotEmpty()) srvCode else generateRoomCode()
                }

                val directRoom = WatchRoomModel(
                    roomId = finalCode,
                    roomCode = finalCode,
                    roomName = rName,
                    hostId = localUserId,
                    hostName = localUserName,
                    isPrivate = false,
                    onlineCount = 1,
                    status = "LIVE",
                    streamId = passedStreamId,
                    streamUrl = if (isDirectValid) passedUrl else "",
                    streamReferer = passedReferer,
                    streamUserAgent = passedUa,
                    streamTitle = titleToUse,
                    category = "مشاهدة جماعية",
                    isMovie = true,
                    isPlaying = isDirectValid
                )

                enterWatchRoom(directRoom, isHost = isHostFlag)

                if (isDirectValid) {
                    triggerPlaybackSyncBroadcast()
                } else if (passedStreamId.isEmpty() && passedUrl.isNotEmpty()) {
                    // حالة احتياطية: إذا لم يتم تمرير TMDB ID وتم تمرير رابط صفحة Embed فقط
                    streamSniffer.currentProviderTargets.clear()
                    streamSniffer.currentProviderTargets.add(
                        StreamSource(
                            label = "REYZO 1",
                            url = passedUrl,
                            referer = passedReferer,
                            userAgent = passedUa,
                            isDirectStream = false
                        )
                    )
                    streamSniffer.loadProviderInHeadlessSniffer(0, titleToUse)
                }
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
        playerUI.release()
        streamSniffer.release()
        syncClient.disconnectWebSocket()
        controllerScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        voiceChatManager?.release()
        playerEngine.release()
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