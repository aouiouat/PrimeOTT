package com.primeott.app.presentation.watch

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.ActivityWatchRoomBinding
import com.primeott.app.databinding.ItemChatMessageBinding
import com.primeott.app.databinding.ItemParticipantBinding
import com.primeott.app.domain.model.RoomChatMessage
import com.primeott.app.domain.model.RoomParticipant
import com.primeott.app.domain.model.WatchRoom
import com.primeott.app.presentation.common.LocaleHelper
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class WatchRoomActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROOM_CODE = "extra_room_code"
        const val EXTRA_ROOM_PASS = "extra_room_pass"
    }

    private lateinit var binding: ActivityWatchRoomBinding
    private var exoPlayer: ExoPlayer? = null
    private var roomWebSocket: WebSocket? = null
    private var syncLoopJob: Job? = null
    private var watchdogJob: Job? = null
    private lateinit var voiceManager: VoiceChatManager

    private var currentRoom: WatchRoom? = null
    private var currentBaseStreamUrl: String = ""
    private var candidateUrls: List<String> = emptyList()
    private var currentCandidateIndex: Int = 0
    private var lastAppliedSyncSeq: Int = 0
    private var isApplyingRemoteSync: Boolean = false
    private var lastMessageId: Long = 0L
    private var lastReactionSentMs: Long = 0L
    private var isFullscreenLandscape: Boolean = false
    private var isVoiceActive: Boolean = false
    private var isMicMuted: Boolean = true
    private var iHaveControlPermission: Boolean = false

    private val chatMessages = mutableListOf<RoomChatMessage>()
    private val participantsList = mutableListOf<RoomParticipant>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var participantsAdapter: ParticipantsAdapter

    private val myDeviceId by lazy { SessionManager.getDeviceId(this) }
    private val myUserName by lazy { SessionManager.getUserName(this) }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceManager.connectVoiceSession()
        } else {
            Toast.makeText(this, "⚠️ يرجى السماح بصلاحية الميكروفون للتحدث مع أصدقائك", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // منع انطفاء الشاشة أو توقف البث أثناء المشاهدة الجماعية
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        LocaleHelper.applyLayoutDirection(this)
        binding = ActivityWatchRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""
        val roomPass = intent.getStringExtra(EXTRA_ROOM_PASS) ?: ""

        if (roomCode.isEmpty()) {
            finish()
            return
        }

        setupAdapters()
        setupVoiceManager()
        setupClickListeners()
        initSharedExoPlayer()
        joinAndInitializeRoom(roomCode, roomPass)
    }

    private fun setupAdapters() {
        chatAdapter = ChatAdapter(chatMessages)
        binding.rvRoomChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvRoomChat.adapter = chatAdapter

        participantsAdapter = ParticipantsAdapter(
            list = participantsList,
            isAdminViewer = { currentRoom?.isHost == true },
            myDeviceId = myDeviceId,
            onGrantControlClick = { part -> toggleUserControlPermission(part) },
            onKickClick = { part -> kickParticipant(part) }
        )
        binding.rvRoomParticipants.layoutManager = LinearLayoutManager(this)
        binding.rvRoomParticipants.adapter = participantsAdapter
    }

    private fun setupVoiceManager() {
        voiceManager = VoiceChatManager(
            context = applicationContext,
            roomIdProvider = { currentRoom?.roomId ?: "" },
            deviceId = myDeviceId,
            userName = myUserName,
            onRemoteSpeakerActive = { speakerName ->
                runOnUiThread {
                    binding.tvSyncStatusBadge.text = "🎙️ يتحدث الآن: $speakerName • 👥 ${participantsList.size}"
                }
            },
            onStateChanged = { connected, muted, statusText ->
                runOnUiThread {
                    isVoiceActive = connected
                    isMicMuted = muted
                    if (connected) {
                        binding.btnVoiceJoinToggle.text = "🔴 قطع الصوت"
                        binding.btnVoiceJoinToggle.setBackgroundColor(Color.parseColor("#EF4444"))
                        binding.btnMicMuteToggle.visibility = View.VISIBLE
                        binding.btnMicMuteToggle.text = if (muted) "🔇 الميكروفون مكتوم" else "🎙️ الميكروفون شغال"
                        binding.btnMicMuteToggle.setBackgroundColor(
                            if (muted) Color.parseColor("#1E2433") else Color.parseColor("#10B981")
                        )
                    } else {
                        binding.btnVoiceJoinToggle.text = "🎙️ اتصال صوتي"
                        binding.btnVoiceJoinToggle.setBackgroundColor(Color.parseColor("#10B981"))
                        binding.btnMicMuteToggle.visibility = View.GONE
                    }
                    Toast.makeText(this, statusText, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun setupClickListeners() {
        binding.btnLeaveRoom.setOnClickListener { confirmLeaveRoom() }

        binding.btnShareInvite.setOnClickListener {
            val r = currentRoom ?: return@setOnClickListener
            val shareText = "🎉 انضم إليّ لمشاهدة (${r.contentTitle}) مباشرة في غرفة Watch Together! | 🔑 كود الغرفة: ${r.roomCode} | 🔗 الرابط: ${r.inviteUrl}"

            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Room Invite", r.roomCode))

            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }, "مشاركة دعوة الغرفة مع الأصدقاء"))
        }

        binding.btnHostManage.setOnClickListener { showHostManagementDialog() }
        binding.btnToggleFullscreen.setOnClickListener { toggleFullscreenMode() }
        binding.btnChangeRoomStream.setOnClickListener { showSwitchChannelDialog() }

        binding.tabRoomChat.setOnClickListener {
            binding.chatSectionContainer.visibility = View.VISIBLE
            binding.rvRoomParticipants.visibility = View.GONE
            binding.tabRoomChat.setBackgroundColor(Color.parseColor("#E50914"))
            binding.tabRoomParticipants.setBackgroundColor(Color.TRANSPARENT)
        }

        binding.tabRoomParticipants.setOnClickListener {
            binding.chatSectionContainer.visibility = View.GONE
            binding.rvRoomParticipants.visibility = View.VISIBLE
            binding.tabRoomParticipants.setBackgroundColor(Color.parseColor("#E50914"))
            binding.tabRoomChat.setBackgroundColor(Color.TRANSPARENT)
        }

        binding.btnSendChat.setOnClickListener {
            val txt = binding.etChatInput.text.toString().trim()
            if (txt.isEmpty()) return@setOnClickListener
            binding.etChatInput.setText("")
            sendChatMessageOrReaction(txt, "TEXT")
        }

        val reactions = listOf(
            binding.reactGoal to "⚽",
            binding.reactFire to "🔥",
            binding.reactHeart to "❤️",
            binding.reactClap to "👏",
            binding.reactLaugh to "😂",
            binding.reactShock to "😱"
        )
        reactions.forEach { (view, emoji) ->
            view.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastReactionSentMs < 600) return@setOnClickListener
                lastReactionSentMs = now
                showFloatingEmoji(emoji, myUserName)
                sendChatMessageOrReaction(emoji, "REACTION")
            }
        }

        binding.btnVoiceJoinToggle.setOnClickListener {
            if (isVoiceActive) {
                voiceManager.disconnectVoice()
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    voiceManager.connectVoiceSession()
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        binding.btnMicMuteToggle.setOnClickListener {
            voiceManager.toggleMute()
        }
    }

    /**
     * بناء قائمة روابط بديلة ذكية لنفس القناة (.ts + .m3u8 + قناة بديلة)
     * لحل مشكلة الشاشة السوداء أو حظر الاتصال المتعدد في سيرفر IPTV
     */
    private fun buildFailoverStreamCandidates(primaryUrl: String, streamId: String): List<String> {
        val list = mutableListOf<String>()
        if (primaryUrl.isNotEmpty()) {
            // إذا كان المستخدم عضوًا (ليس المضيف)، نفضل صيغة .ts أولاً لتفادي تصادم جلسة HLS مع المضيف
            if (currentRoom?.isHost == false && primaryUrl.endsWith(".m3u8")) {
                list.add(primaryUrl.removeSuffix(".m3u8") + ".ts")
                list.add(primaryUrl)
            } else {
                list.add(primaryUrl)
                if (primaryUrl.endsWith(".m3u8")) {
                    list.add(primaryUrl.removeSuffix(".m3u8") + ".ts")
                } else if (primaryUrl.endsWith(".ts")) {
                    list.add(primaryUrl.removeSuffix(".ts") + ".m3u8")
                }
            }
        }
        val base = SessionManager.XTREAM_BASE_URL
        val u = SessionManager.XTREAM_USER
        val p = SessionManager.XTREAM_PASS
        if (streamId.isNotEmpty()) {
            list.add("$base/live/$u/$p/$streamId.ts")
            list.add("$base/live/$u/$p/$streamId.m3u8")
            list.add("$base/$u/$p/$streamId")
        }
        // روابط احتياطية مضمونة العمل 100% في حال تعطل القناة المصدرية
        list.add("$base/live/$u/$p/6172.ts")
        list.add("$base/live/$u/$p/6171.ts")
        return list.distinct()
    }

    @OptIn(UnstableApi::class)
    private fun initSharedExoPlayer() {
        val dataSourceFactory = OkHttpDataSource.Factory(OttRepository.okHttpClient)
            .setUserAgent("${SessionManager.CUSTOM_USER_AGENT}/${myDeviceId.takeLast(4)}")
            .setDefaultRequestProperties(mapOf("Connection" to "keep-alive"))

        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
            )

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(18_000, 60_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttrs, false)
            .build().also { player ->
                binding.sharedPlayerView.player = player
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // انتقال فوري للرابط البديل عند حدوث شاشة سوداء أو انقطاع في التدفق
                        tryNextCandidateStream()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED && currentRoom?.contentType == "LIVE") {
                            // إذا توقف البث المباشر لوحده، نعيد تشغيله فوراً
                            player.seekToDefaultPosition()
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!isApplyingRemoteSync && iHaveControlPermission) {
                            pushLocalPlaybackChangeToRoom()
                        }
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK && !isApplyingRemoteSync && iHaveControlPermission) {
                            pushLocalPlaybackChangeToRoom()
                        }
                    }
                })
            }

        // مراقب ذكي (Watchdog) يمنع توقف البث أو الشاشة السوداء ويعيد تنشيط المشغل تلقائياً
        watchdogJob = lifecycleScope.launch {
            while (isActive) {
                delay(6000)
                val p = exoPlayer ?: continue
                val wantPlay = currentRoom?.isPlaying ?: true
                if (wantPlay && (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED)) {
                    p.prepare()
                    p.playWhenReady = true
                }
            }
        }
    }

    private fun tryNextCandidateStream() {
        val player = exoPlayer ?: return
        if (candidateUrls.isEmpty()) return
        currentCandidateIndex = (currentCandidateIndex + 1) % candidateUrls.size
        val nextUrl = candidateUrls[currentCandidateIndex]
        val mediaItem = MediaItem.fromUri(nextUrl)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = currentRoom?.isPlaying ?: true
    }

    private fun joinAndInitializeRoom(codeOrId: String, password: String) {
        binding.tvSyncStatusBadge.text = "⏳ جاري الاتصال بالغرفة والمزامنة..."
        lifecycleScope.launch {
            val res = OttRepository.joinWatchRoom(codeOrId, myDeviceId, myUserName, password)
            if (res.status != "success" || res.room == null) {
                Toast.makeText(this@WatchRoomActivity, res.errorMessage, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            applyRoomFullState(res.room, res.participants, res.messages)
            connectRoomWebSocket(res.room.roomId)
            startHeartbeatSyncLoop(res.room.roomId)
        }
    }

    private fun connectRoomWebSocket(roomId: String) {
        val wsUrl = SessionManager.CLOUDFLARE_API_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/api/watch/ws?room_id=$roomId"

        val req = Request.Builder().url(wsUrl).build()
        roomWebSocket = OttRepository.okHttpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "PLAYBACK_SYNC" -> {
                            runOnUiThread {
                                applyPlaybackSyncFromServer(
                                    seq = json.optInt("sync_seq", 0),
                                    streamUrl = json.optString("stream_url", currentBaseStreamUrl),
                                    streamId = json.optString("stream_id", currentRoom?.streamId ?: "6171"),
                                    shouldPlay = json.optBoolean("is_playing", true),
                                    targetPosMs = json.optLong("position_ms", 0L)
                                )
                            }
                        }
                        "NEW_MESSAGE" -> {
                            val m = json.optJSONObject("message") ?: return
                            val msgObj = RoomChatMessage(
                                id = m.optLong("id"),
                                senderId = m.optString("sender_id"),
                                senderName = m.optString("sender_name"),
                                messageType = m.optString("message_type", "TEXT"),
                                content = m.optString("content"),
                                createdAt = m.optLong("created_at")
                            )
                            runOnUiThread { appendIncomingMessage(msgObj) }
                        }
                        "VOICE_PACKET" -> {
                            val sender = json.optString("sender_id")
                            if (sender != myDeviceId) {
                                voiceManager.playPcmBase64(json.optString("pcm", ""))
                            }
                        }
                        "ROOM_ENDED" -> {
                            runOnUiThread {
                                Toast.makeText(this@WatchRoomActivity, "⛔ أنهى الأدمن جلسة الغرفة", Toast.LENGTH_LONG).show()
                                finish()
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        })
    }

    private fun startHeartbeatSyncLoop(roomId: String) {
        syncLoopJob?.cancel()
        syncLoopJob = lifecycleScope.launch {
            while (isActive) {
                delay(3000)
                // فقط الأدمن الرئيسي يرسل نبضات الموضع الزمني التلقائية لمنع تضارب الأجهزة
                val isMainAdmin = currentRoom?.isHost == true
                val pbUpdate = if (isMainAdmin && exoPlayer != null && currentBaseStreamUrl.isNotEmpty()) {
                    JSONObject().apply {
                        put("is_playing", exoPlayer?.playWhenReady == true)
                        put("position_ms", exoPlayer?.currentPosition ?: 0L)
                        put("stream_id", currentRoom?.streamId ?: "6171")
                        put("stream_url", currentBaseStreamUrl)
                        put("content_title", currentRoom?.contentTitle ?: "")
                    }
                } else null

                val res = OttRepository.syncWatchRoom(
                    roomId = roomId,
                    deviceId = myDeviceId,
                    lastMessageId = lastMessageId,
                    isMuted = isMicMuted,
                    inVoice = isVoiceActive,
                    playbackUpdate = pbUpdate
                )
                if (res.status == "ended" || res.status == "kicked") {
                    Toast.makeText(this@WatchRoomActivity, res.errorMessage.ifEmpty { "انتهت جلستك في الغرفة" }, Toast.LENGTH_LONG).show()
                    finish()
                    break
                }
                if (res.status == "success" && res.room != null) {
                    applyRoomFullState(res.room, res.participants, res.messages)
                }
            }
        }
    }



    private fun applyRoomFullState(
        room: WatchRoom,
        participants: List<RoomParticipant>,
        newMessages: List<RoomChatMessage>
    ) {
        currentRoom = room
        val myPart = participants.find { it.deviceId == myDeviceId }
        val isGrantedController = myPart?.role == "CONTROLLER" || myPart?.role == "HOST"
        iHaveControlPermission = room.isHost || isGrantedController || room.allowGuestsControl

        binding.tvWatchRoomName.text = "${room.roomIcon} ${room.roomName} (${room.roomCode})"
        binding.btnHostManage.visibility = if (room.isHost) View.VISIBLE else View.GONE
        binding.btnChangeRoomStream.visibility = if (iHaveControlPermission) View.VISIBLE else View.GONE

        // إظهار أزرار إيقاف/تقديم الفيديو للأدمن أو لمن منحه الأدمن الصلاحية فقط!
        binding.sharedPlayerView.useController = iHaveControlPermission

        val roleTag = when {
            room.isHost -> "👑 أنت الأدمن"
            isGrantedController -> "🎮 لديك صلاحية التحكم"
            else -> "🔒 يتحكم الأدمن بالبث"
        }
        binding.tvSyncStatusBadge.text = "$roleTag • 👥 ${participants.size} متصلين"
        binding.tabRoomParticipants.text = "👥 المتواجدون (${participants.size})"

        participantsList.clear()
        participantsList.addAll(participants)
        participantsAdapter.notifyDataSetChanged()

        newMessages.forEach { msg -> appendIncomingMessage(msg) }

        applyPlaybackSyncFromServer(
            seq = room.syncSeq,
            streamUrl = room.streamUrl,
            streamId = room.streamId,
            shouldPlay = room.isPlaying,
            targetPosMs = room.playbackPositionMs
        )
    }

    private fun applyPlaybackSyncFromServer(
        seq: Int,
        streamUrl: String,
        streamId: String,
        shouldPlay: Boolean,
        targetPosMs: Long
    ) {
        val player = exoPlayer ?: return
        isApplyingRemoteSync = true
        try {
            // إذا تغيرت القناة فقط نقوم بتحميل الرابط الجديد (لمنع الشاشة السوداء وإعادة التحميل المتكرر)
            if (streamUrl.isNotEmpty() && streamUrl != currentBaseStreamUrl) {
                currentBaseStreamUrl = streamUrl
                candidateUrls = buildFailoverStreamCandidates(streamUrl, streamId)
                currentCandidateIndex = 0
                val playUrl = candidateUrls.firstOrNull() ?: streamUrl
                player.setMediaItem(MediaItem.fromUri(playUrl))
                player.prepare()
                player.playWhenReady = shouldPlay
            } else {
                if (player.playWhenReady != shouldPlay) {
                    player.playWhenReady = shouldPlay
                }
                // في الفيديوهات المسجلة فقط (وليس البث المباشر LIVE) نزامن الموضع الزمني
                if (currentRoom?.contentType != "LIVE" && targetPosMs > 0) {
                    val drift = abs(player.currentPosition - targetPosMs)
                    if (drift > 3500L) {
                        player.seekTo(targetPosMs)
                    }
                }
            }
            lastAppliedSyncSeq = maxOf(lastAppliedSyncSeq, seq)
        } finally {
            binding.root.postDelayed({ isApplyingRemoteSync = false }, 400)
        }
    }

    private fun pushLocalPlaybackChangeToRoom() {
        val room = currentRoom ?: return
        if (!iHaveControlPermission) return
        val player = exoPlayer ?: return

        val pb = JSONObject().apply {
            put("is_playing", player.playWhenReady)
            put("position_ms", player.currentPosition)
            put("stream_id", room.streamId)
            put("stream_url", currentBaseStreamUrl)
            put("content_title", room.contentTitle)
        }

        roomWebSocket?.send(JSONObject().apply {
            put("type", "PLAYBACK_SYNC")
            put("sync_seq", lastAppliedSyncSeq + 1)
            put("is_playing", player.playWhenReady)
            put("position_ms", player.currentPosition)
            put("stream_id", room.streamId)
            put("stream_url", currentBaseStreamUrl)
            put("content_title", room.contentTitle)
        }.toString())

        lifecycleScope.launch {
            OttRepository.syncWatchRoom(room.roomId, myDeviceId, lastMessageId, isMicMuted, isVoiceActive, pb)
        }
    }

    private fun toggleUserControlPermission(part: RoomParticipant) {
        val room = currentRoom ?: return
        if (!room.isHost) return
        val options = arrayOf(
            if (part.role == "CONTROLLER") "🛡️ سحب صلاحية التحكم في المشغل من ${part.userName}" else "🎮 منح ${part.userName} صلاحية التحكم وتغيير القناة",
            "👑 تعيين ${part.userName} أدمن رئيسي للغرفة (نقل القيادة)"
        )
        AlertDialog.Builder(this)
            .setTitle("👤 صلاحيات العضو: ${part.userName}")
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    if (which == 0) {
                        OttRepository.manageWatchRoom(room.roomId, myDeviceId, "TOGGLE_USER_CONTROL", part.deviceId)
                        Toast.makeText(this@WatchRoomActivity, "✅ تم تحديث صلاحية ${part.userName}", Toast.LENGTH_SHORT).show()
                    } else if (which == 1) {
                        OttRepository.manageWatchRoom(room.roomId, myDeviceId, "TRANSFER_HOST", part.deviceId)
                        Toast.makeText(this@WatchRoomActivity, "👑 تم نقل قيادة الغرفة إلى ${part.userName}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun appendIncomingMessage(msg: RoomChatMessage) {
        if (chatMessages.any { it.id == msg.id }) return
        if (msg.id > lastMessageId) lastMessageId = msg.id

        if (msg.messageType == "REACTION") {
            if (msg.senderId != myDeviceId) {
                showFloatingEmoji(msg.content, msg.senderName)
            }
            return
        }

        chatMessages.add(msg)
        chatAdapter.notifyItemInserted(chatMessages.size - 1)
        binding.rvRoomChat.scrollToPosition(chatMessages.size - 1)
    }

    private fun sendChatMessageOrReaction(content: String, type: String) {
        val roomId = currentRoom?.roomId ?: return
        lifecycleScope.launch {
            OttRepository.sendRoomMessage(roomId, myDeviceId, content, type)
        }
    }

    private fun showFloatingEmoji(emoji: String, senderName: String) {
        val tv = TextView(this).apply {
            text = "$emoji $senderName"
            textSize = 18f
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                marginStart = Random.nextInt(40, 260)
                bottomMargin = 20
            }
            layoutParams = lp
        }
        binding.reactionsOverlay.addView(tv)
        tv.animate()
            .translationY(-320f)
            .alpha(0f)
            .setDuration(1900)
            .withEndAction { binding.reactionsOverlay.removeView(tv) }
            .start()
    }

    private fun showSwitchChannelDialog() {
        val room = currentRoom ?: return
        if (!iHaveControlPermission) {
            Toast.makeText(this, "🔒 فقط الأدمن أو من يملك الصلاحية يمكنه تغيير القناة", Toast.LENGTH_SHORT).show()
            return
        }
        val streams = listOf(
            Triple("6171", "beIN SPORTS 1 FHD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6171.m3u8"),
            Triple("6172", "beIN SPORTS 2 FHD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6172.m3u8"),
            Triple("6173", "beIN SPORTS 3 FHD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6173.m3u8"),
            Triple("6174", "SSC SPORTS 1 HD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6174.m3u8"),
            Triple("6175", "الجزائرية السادسة TV6 HD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6175.m3u8")
        )
        AlertDialog.Builder(this)
            .setTitle("📺 تغيير القناة لجميع أعضاء الغرفة")
            .setItems(streams.map { it.second }.toTypedArray()) { _, which ->
                val chosen = streams[which]
                currentRoom = room.copy(streamId = chosen.first, contentTitle = chosen.second, streamUrl = chosen.third)
                applyPlaybackSyncFromServer(lastAppliedSyncSeq + 1, chosen.third, chosen.first, true, 0L)
                pushLocalPlaybackChangeToRoom()
            }
            .show()
    }

    private fun showHostManagementDialog() {
        val room = currentRoom ?: return
        val lockOption = if (room.isLocked) "🔓 فتح الغرفة لانضمام الأعضاء" else "🔒 قفل الغرفة (منع دخول أعضاء جدد)"
        val ctrlOption = if (room.allowGuestsControl) "🛡️ حصر التحكم في المشغل للأدمن والمصرح لهم فقط" else "🎮 السماح لجميع الأعضاء بالتحكم في المشغل"
        val options = arrayOf(
            lockOption,
            ctrlOption,
            "👤 إدارة الأعضاء ومنح صلاحية التحكم لشخص محدد",
            "🔄 تبديل مصدر البث للغرفة (إصلاح الشاشة السوداء)",
            "⛔ إنهاء وإغلاق الغرفة للجميع"
        )

        AlertDialog.Builder(this)
            .setTitle("⚙️ إدارة الغرفة (صلاحيات الأدمن)")
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    when (which) {
                        0 -> OttRepository.manageWatchRoom(room.roomId, myDeviceId, "LOCK_TOGGLE")
                        1 -> OttRepository.manageWatchRoom(room.roomId, myDeviceId, "GUEST_CONTROL_TOGGLE")
                        2 -> {
                            binding.tabRoomParticipants.performClick()
                            Toast.makeText(this@WatchRoomActivity, "اضغط على زر (🎮 منحه التحكم) بجانب اسم صديقك", Toast.LENGTH_LONG).show()
                        }
                        3 -> tryNextCandidateStream()
                        4 -> {
                            OttRepository.manageWatchRoom(room.roomId, myDeviceId, "END_ROOM")
                            finish()
                        }
                    }
                }
            }
            .show()
    }

    private fun kickParticipant(part: RoomParticipant) {
        val room = currentRoom ?: return
        lifecycleScope.launch {
            OttRepository.manageWatchRoom(room.roomId, myDeviceId, "KICK_USER", part.deviceId)
            Toast.makeText(this@WatchRoomActivity, "تم طرد ${part.userName} من الغرفة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFullscreenMode() {
        isFullscreenLandscape = !isFullscreenLandscape
        if (isFullscreenLandscape) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            binding.watchTopHeader.visibility = View.GONE
            binding.socialControlBar.visibility = View.GONE
            binding.panelSwitchTabs.visibility = View.GONE
            binding.bottomSocialPanel.visibility = View.GONE
            binding.videoPlayerContainer.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.btnToggleFullscreen.text = "🗗 إظهار الدردشة"
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            binding.watchTopHeader.visibility = View.VISIBLE
            binding.socialControlBar.visibility = View.VISIBLE
            binding.panelSwitchTabs.visibility = View.VISIBLE
            binding.bottomSocialPanel.visibility = View.VISIBLE
            val dp240 = (240 * resources.displayMetrics.density).toInt()
            binding.videoPlayerContainer.layoutParams.height = dp240
            binding.btnToggleFullscreen.text = "⛶ شاشة كاملة"
        }
        binding.videoPlayerContainer.requestLayout()
    }

    private fun confirmLeaveRoom() {
        val room = currentRoom
        if (room == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            OttRepository.manageWatchRoom(room.roomId, myDeviceId, "LEAVE")
            finish()
        }
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        syncLoopJob?.cancel()
        roomWebSocket?.close(1000, "Leave")
        voiceManager.disconnectVoice()
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}

typealias RoomParticipantMessageAlias = RoomChatMessage

class ChatAdapter(private val items: List<RoomChatMessage>) : RecyclerView.Adapter<ChatAdapter.VH>() {
    inner class VH(val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val b = holder.binding
        b.tvChatSender.text = if (m.messageType == "SYSTEM") "🔔 إشعار الغرفة" else "👤 ${m.senderName}"
        b.tvChatSender.setTextColor(if (m.messageType == "SYSTEM") Color.parseColor("#10B981") else Color.parseColor("#38BDF8"))
        b.tvChatContent.text = m.content
        b.tvChatTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.createdAt))
    }
}

class ParticipantsAdapter(
    private val list: List<RoomParticipant>,
    private val isAdminViewer: () -> Boolean,
    private val myDeviceId: String,
    private val onGrantControlClick: (RoomParticipant) -> Unit,
    private val onKickClick: (RoomParticipant) -> Unit
) : RecyclerView.Adapter<ParticipantsAdapter.VH>() {
    inner class VH(val binding: ItemParticipantBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemParticipantBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = list.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = list[position]
        val b = holder.binding
        val roleBadge = when (p.role) {
            "HOST" -> "👑 (الأدمن)"
            "CONTROLLER" -> "🎮 (متحكم)"
            else -> "مشاهد"
        }
        b.tvPartName.text = "${p.userName} • $roleBadge"
        b.tvPartMicState.text = when {
            !p.inVoice -> "⚪"
            p.isMuted -> "🔇"
            else -> "🎙️🟢"
        }
        val showAdminBtns = isAdminViewer() && p.deviceId != myDeviceId
        b.btnKickParticipant.visibility = if (showAdminBtns) View.VISIBLE else View.GONE
        b.btnGrantControl.visibility = if (showAdminBtns) View.VISIBLE else View.GONE
        b.btnGrantControl.text = if (p.role == "CONTROLLER") "🛡️ سحب التحكم" else "🎮 منحه التحكم"

        b.btnGrantControl.setOnClickListener { onGrantControlClick(p) }
        b.btnKickParticipant.setOnClickListener { onKickClick(p) }
    }
}
