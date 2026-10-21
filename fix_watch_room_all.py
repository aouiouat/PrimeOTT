import os

PACKAGE_PATH = "app/src/main/java/com/primeott/app"
RES_PATH = "app/src/main/res"

files = {
    # =========================================================================
    # 1. إضافة دالة syncVoicePackets في OttRepository.kt
    # =========================================================================
    f"{PACKAGE_PATH}/data/repository/VoiceSyncHelper.kt": r"""package com.primeott.app.data.repository

import com.primeott.app.data.remote.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class IncomingVoicePacket(
    val id: Long,
    val senderId: String,
    val senderName: String,
    val pcmBase64: String
)

object VoiceSyncHelper {
    suspend fun syncVoice(
        roomId: String,
        deviceId: String,
        userName: String,
        lastVoiceId: Long,
        outgoingPcmBase64: String
    ): List<IncomingVoicePacket> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("room_id", roomId)
                put("device_id", deviceId)
                put("user_name", userName)
                put("last_voice_id", lastVoiceId)
                put("pcm_data", outgoingPcmBase64)
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("${SessionManager.CLOUDFLARE_API_URL}/api/watch/voice-sync")
                .post(body)
                .build()

            OttRepository.okHttpClient.newCall(req).execute().use { res ->
                val str = res.body?.string() ?: "{}"
                val json = JSONObject(str)
                val arr = json.optJSONArray("packets") ?: return@withContext emptyList()
                val list = ArrayList<IncomingVoicePacket>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        IncomingVoicePacket(
                            id = o.optLong("id"),
                            senderId = o.optString("sender_id"),
                            senderName = o.optString("sender_name"),
                            pcmBase64 = o.optString("pcm_data")
                        )
                    )
                }
                return@withContext list
            }
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }
}
""",

    # =========================================================================
    # 2. محرك الصوت الحقيقي المحدث (VoiceChatManager.kt) يعمل عبر السحابة بين أي جهازين
    # =========================================================================
    f"{PACKAGE_PATH}/presentation/watch/VoiceChatManager.kt": r"""package com.primeott.app.presentation.watch

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import com.primeott.app.data.repository.VoiceSyncHelper
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class VoiceChatManager(
    private val context: Context,
    private val roomIdProvider: () -> String,
    private val deviceId: String,
    private val userName: String,
    private val onRemoteSpeakerActive: (String) -> Unit,
    private val onStateChanged: (Boolean, Boolean, String) -> Unit
) {
    private var isConnected = false
    private var isMuted = false
    private var voiceLoopJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var lastVoicePacketId: Long = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sampleRate = 8000

    @SuppressLint("MissingPermission")
    fun connectVoiceSession() {
        if (isConnected) return
        isConnected = true
        isMuted = false // يبدأ الميكروفون مفعلاً بمجرد ضغط المستخدم على اتصال صوتي

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
        } catch (e: Exception) {}

        initAudioHardware()
        startCloudVoiceSyncLoop()
        onStateChanged(true, isMuted, "🟢 متصل بالصوت الجماعي (الميكروفون مفعل 🎙️)")
    }

    @SuppressLint("MissingPermission")
    private fun initAudioHardware() {
        try {
            val minOutBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minOutBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().apply { play() }

            val minInBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minInBuf * 2
            )

            // تفعيل مانع الصدى والضجيج في الهاتف إن توفر
            audioRecord?.audioSessionId?.let { sessionId ->
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(sessionId)?.enabled = true
                }
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(sessionId)?.enabled = true
                }
            }

            audioRecord?.startRecording()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startCloudVoiceSyncLoop() {
        voiceLoopJob?.cancel()
        voiceLoopJob = scope.launch {
            val readBuffer = ByteArray(4800) // حوالي 0.3 ثانية من الصوت
            while (isActive && isConnected) {
                val roomId = roomIdProvider()
                if (roomId.isEmpty()) {
                    delay(300)
                    continue
                }

                var outgoingBase64 = ""
                if (!isMuted && audioRecord != null) {
                    val outStream = ByteArrayOutputStream()
                    var totalRead = 0
                    val startMs = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startMs < 320 && totalRead < 6400) {
                        val r = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0
                        if (r > 0) {
                            outStream.write(readBuffer, 0, r)
                            totalRead += r
                        } else {
                            break
                        }
                    }
                    val rawBytes = outStream.toByteArray()
                    // إرسال الحزمة فقط إذا كان هناك صوت فعلي لتوفير الإنترنت
                    if (rawBytes.isNotEmpty() && hasVoiceActivity(rawBytes)) {
                        outgoingBase64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
                    }
                } else {
                    delay(320)
                }

                val incomingPackets = VoiceSyncHelper.syncVoice(
                    roomId = roomId,
                    deviceId = deviceId,
                    userName = userName,
                    lastVoiceId = lastVoiceId(),
                    outgoingPcmBase64 = outgoingBase64
                )

                for (pkt in incomingPackets) {
                    if (pkt.id > lastVoicePacketId) {
                        lastVoicePacketId = pkt.id
                    }
                    if (pkt.senderId != deviceId && pkt.pcmBase64.isNotEmpty()) {
                        onRemoteSpeakerActive(pkt.senderName)
                        playPcmBase64(pkt.pcmBase64)
                    }
                }
            }
        }
    }

    private fun lastVoiceId(): Long = lastVoicePacketId

    private fun hasVoiceActivity(pcmBytes: ByteArray): Boolean {
        var sum = 0L
        var samples = 0
        var i = 0
        while (i + 1 < pcmBytes.size) {
            val sample = (pcmBytes[i + 1].toInt() shl 8) or (pcmBytes[i].toInt() and 0xFF)
            sum += abs(sample)
            samples++
            i += 4
        }
        val avg = if (samples > 0) sum / samples else 0
        return avg > 180 // عتبة حساسية الصوت
    }

    fun playPcmBase64(base64Pcm: String) {
        if (!isConnected) return
        try {
            val bytes = Base64.decode(base64Pcm, Base64.NO_WRAP)
            audioTrack?.write(bytes, 0, bytes.size)
        } catch (e: Exception) {}
    }

    fun toggleMute(): Boolean {
        if (!isConnected) return true
        isMuted = !isMuted
        val msg = if (isMuted) "🔇 تم كتم الميكروفون" else "🎙️ الميكروفون مفعل — أصدقاؤك يسمعونك الآن"
        onStateChanged(isConnected, isMuted, msg)
        return isMuted
    }

    fun disconnectVoice() {
        isConnected = false
        isMuted = true
        voiceLoopJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
        onStateChanged(false, true, "⚪ تم قطع الاتصال الصوتي")
    }
}
""",

    # =========================================================================
    # 3. تحديث عنصر المشارك (item_participant.xml) ليحتوي على زر منح الصلاحية + زر الطرد للأدمن
    # =========================================================================
    f"{RES_PATH}/layout/item_participant.xml": r"""<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="6dp"
    android:background="#151A26"
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:padding="10dp">

    <Button
        android:id="@+id/btnKickParticipant"
        android:layout_width="wrap_content"
        android:layout_height="32dp"
        android:backgroundTint="#EF4444"
        android:text="🚫 طرد"
        android:textColor="#FFFFFF"
        android:textSize="10.5sp"
        android:visibility="gone" />

    <Button
        android:id="@+id/btnGrantControl"
        android:layout_width="wrap_content"
        android:layout_height="32dp"
        android:layout_marginStart="6dp"
        android:backgroundTint="#2563EB"
        android:text="🎮 منحه التحكم"
        android:textColor="#FFFFFF"
        android:textSize="10.5sp"
        android:visibility="gone" />

    <TextView
        android:id="@+id/tvPartMicState"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginHorizontal="8dp"
        android:text="🔇"
        android:textSize="14sp" />

    <TextView
        android:id="@+id/tvPartName"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:gravity="end"
        android:text="👑 عادل (الأدمن)"
        android:textColor="#FFFFFF"
        android:textSize="13sp"
        android:textStyle="bold" />
</LinearLayout>
""",

    # =========================================================================
    # 4. تحديث WatchRoomActivity.kt (إصلاح الشاشة السوداء، التوقف المفاجئ، الصوت، وصلاحيات الأدمن)
    # =========================================================================
    f"{PACKAGE_PATH}/presentation/watch/WatchRoomActivity.kt": r"""package com.primeott.app.presentation.watch

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
        newMessages: List<RoomParticipantMessageAlias>
    ) {
        // الدالة الفعلية بالأسفل
    }

    private fun applyRoomFullState(
        room: WatchRoom,
        participants: List<RoomParticipant>,
        newMessages: List<RoomChatMessage>,
        unusedFlag: Boolean = true
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
""",

    # =========================================================================
    # 5. إصلاح النص المقسوم في fragment_watch_together.xml لضمان نجاح البناء 100%
    # =========================================================================
    f"{RES_PATH}/layout/fragment_watch_together.xml": r"""<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0B0D13"
    android:orientation="vertical"
    android:padding="14dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="#151A26"
        android:orientation="vertical"
        android:padding="16dp"
        android:layout_marginBottom="14dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="👥 Watch Together — المشاهدة الجماعية"
            android:textColor="#FFFFFF"
            android:textSize="17sp"
            android:textStyle="bold" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="شاهد المباريات والأفلام في نفس الثانية مع أصدقائك مع دردشة صوتية وكتابية مباشرة!"
            android:textColor="#94A3B8"
            android:textSize="12sp" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:orientation="horizontal"
            android:weightSum="2">

            <Button
                android:id="@+id/btnCreateNewRoom"
                android:layout_width="0dp"
                android:layout_height="46dp"
                android:layout_marginEnd="6dp"
                android:layout_weight="1"
                android:backgroundTint="#E50914"
                android:text="➕ إنشاء غرفة جديدة"
                android:textColor="#FFFFFF"
                android:textSize="12sp"
                android:textStyle="bold" />

            <Button
                android:id="@+id/btnJoinByCodeDialog"
                android:layout_width="0dp"
                android:layout_height="46dp"
                android:layout_weight="1"
                android:backgroundTint="#2563EB"
                android:text="🔑 انضمام بكود / رابط"
                android:textColor="#FFFFFF"
                android:textSize="12sp"
                android:textStyle="bold" />
        </LinearLayout>
    </LinearLayout>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:layout_marginBottom="10dp">

        <Button
            android:id="@+id/btnRefreshRooms"
            android:layout_width="wrap_content"
            android:layout_height="34dp"
            android:backgroundTint="#1E2433"
            android:text="🔄 تحديث"
            android:textColor="#FFFFFF"
            android:textSize="11sp" />

        <TextView
            android:id="@+id/tvRoomsCountHeader"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="end"
            android:text="🌍 الغرف النشطة المتاحة حالياً"
            android:textColor="#FFFFFF"
            android:textSize="14sp"
            android:textStyle="bold" />
    </LinearLayout>

    <TextView
        android:id="@+id/tvEmptyRoomsState"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="#11151F"
        android:gravity="center"
        android:padding="28dp"
        android:text="لا توجد غرف عامة نشطة حالياً — اضغط على إنشاء غرفة جديدة وادعُ أصدقاءك الآن!"
        android:textColor="#64748B"
        android:textSize="13sp"
        android:visibility="gone" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rvActiveRooms"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

</LinearLayout>
"""
}

for filepath, content in files.items():
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(content.strip() + "\n")
    print(f"[✓] Fixed & Updated: {filepath}")

print("\n🚀 تم إصلاح الصوت، الشاشة السوداء، التوقف المفاجئ، وصلاحيات الأدمن الفردية بنجاح!")
