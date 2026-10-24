package com.primeott.app.presentation.watch

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.data.repository.RoomSyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject

class WatchRoomSyncClient(
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val workerBaseUrl: String,
    private val localUserId: String,
    private val localUserName: String,
    private val authToken: String,
    private val blockedUserIds: Set<String>,
    private val onChatMessageReceived: (ChatMessageModel) -> Unit,
    private val onEmojiReceived: (String) -> Unit,
    private val onSyncResultReceived: (RoomSyncResult) -> Unit,
    private val onRealtimePlaybackSync: (String, String, String, Boolean, Long) -> Unit = { _, _, _, _, _ -> },
    private val onRemoteSpeakerChanged: (String, String, Boolean) -> Unit = { _, _, _ -> },
    private val onImmediateSyncRequested: () -> Unit = {},
    private val onForceMuteReceived: () -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null

    fun connectWebSocket(roomCode: String) {
        disconnectWebSocket()
        val encodedName = Uri.encode(localUserName)
        val wsUrl = workerBaseUrl.replace("https://", "wss://").replace("http://", "ws://") +
            "/ws/room?roomId=${roomCode.uppercase()}&userId=$localUserId&userName=$encodedName&token=$authToken"
        val req = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                mainHandler.post { handleIncomingRealtimeEvent(text) }
            }
        })
    }

    fun disconnectWebSocket() {
        try {
            webSocket?.close(1000, "Leave")
            webSocket?.cancel()
        } catch (_: Exception) {}
        webSocket = null
    }

    fun broadcastRoomEvent(roomId: String, type: String, payload: JSONObject) {
        val envelope = JSONObject().apply {
            put("type", type)
            put("roomId", roomId)
            put("senderId", localUserId)
            put("senderName", localUserName)
            put("data", payload)
        }
        try {
            webSocket?.send(envelope.toString())
        } catch (_: Exception) {}
    }

    fun broadcastSpeakingState(roomCode: String, isSpeaking: Boolean) {
        val payload = JSONObject().apply {
            put("isSpeaking", isSpeaking)
        }
        broadcastRoomEvent(roomCode, "VOICE_SPEAKING", payload)
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
                "CHAT_MSG" -> onChatMessageReceived(
                    ChatMessageModel(
                        data.optString("msgId", "WS_${System.nanoTime()}"),
                        senderId,
                        senderName,
                        data.optString("senderRole", "PARTICIPANT"),
                        data.optString("text")
                    )
                )
                "EMOJI" -> onEmojiReceived(data.optString("emoji", "🔥"))
                "PLAYBACK_SYNC" -> {
                    val packedUrl = data.optString("stream_url", "")
                    val streamId = data.optString("stream_id", "")
                    val title = data.optString("content_title", "")
                    val isPlaying = data.optBoolean("is_playing", true)
                    val posMs = data.optLong("playback_position_ms", data.optLong("position_ms", 0L))
                    onRealtimePlaybackSync(packedUrl, streamId, title, isPlaying, posMs)
                }
                "VOICE_SPEAKING" -> {
                    val speaking = data.optBoolean("isSpeaking", true)
                    onRemoteSpeakerChanged(senderId, senderName, speaking)
                }
                "USER_JOINED", "USER_LEFT" -> {
                    onImmediateSyncRequested()
                }
                "MUTE_ALL" -> {
                    onForceMuteReceived()
                }
            }
        } catch (_: Exception) {}
    }

    fun broadcastPlaybackSync(
        room: WatchRoomModel,
        isPlaying: Boolean,
        currentPosMs: Long,
        lastSyncedMessageId: Long,
        isMicMuted: Boolean
    ) {
        val packedStream = WatchRoomStreamSniffer.packUrlAndReferer(room.streamUrl, room.streamReferer)
        val payload = JSONObject().apply {
            put("stream_id", room.streamId)
            put("stream_url", packedStream)
            put("content_title", room.streamTitle)
            put("is_playing", isPlaying)
            put("playback_position_ms", currentPosMs)
            put("position_ms", currentPosMs) // متوافق مع كلا الاسمين في السيرفر
        }
        // إرسال لحظي عبر الـ WebSocket أولاً (0ms تأخير)
        broadcastRoomEvent(room.roomCode, "PLAYBACK_SYNC", payload)

        // ثم حفظ الحالة في قاعدة بيانات السيرفر للضيوف الجدد
        scope.launch {
            OttRepository.syncWatchRoom(
                roomId = room.roomId,
                deviceId = localUserId,
                lastMessageId = lastSyncedMessageId,
                isMuted = isMicMuted,
                inVoice = true,
                playbackUpdate = payload
            )
        }
    }

    fun performCloudflareRoomSync(
        room: WatchRoomModel,
        isHost: Boolean,
        isPlaying: Boolean,
        currentPosMs: Long,
        lastSyncedMessageId: Long,
        isMicMuted: Boolean
    ) {
        scope.launch {
            val playbackObj = if (isHost && (room.streamUrl.isNotBlank() || room.streamId.isNotBlank())) {
                JSONObject().apply {
                    put("stream_id", room.streamId)
                    put("stream_url", WatchRoomStreamSniffer.packUrlAndReferer(room.streamUrl, room.streamReferer))
                    put("content_title", room.streamTitle)
                    put("is_playing", isPlaying)
                    put("playback_position_ms", currentPosMs)
                    put("position_ms", currentPosMs)
                }
            } else null

            val res = OttRepository.syncWatchRoom(
                roomId = room.roomId,
                deviceId = localUserId,
                lastMessageId = lastSyncedMessageId,
                isMuted = isMicMuted,
                inVoice = true,
                playbackUpdate = playbackObj
            )
            if (res.status == "success") {
                onSyncResultReceived(res)
            }
        }
    }
}