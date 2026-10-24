package com.primeott.app.presentation.watch

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
    private val onSyncResultReceived: (RoomSyncResult) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null

    fun connectWebSocket(roomId: String) {
        disconnectWebSocket()
        val wsUrl = workerBaseUrl.replace("https://", "wss://").replace("http://", "ws://") +
                "/ws/room?roomId=$roomId&userId=$localUserId&token=$authToken"
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
            put("stream_url", packedStream)
            put("content_title", room.streamTitle)
            put("is_playing", isPlaying)
            put("playback_position_ms", currentPosMs)
        }
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
        broadcastRoomEvent(room.roomId, "PLAYBACK_SYNC", payload)
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
            val playbackObj = if (isHost && room.streamUrl.isNotBlank()) {
                JSONObject().apply {
                    put("stream_url", WatchRoomStreamSniffer.packUrlAndReferer(room.streamUrl, room.streamReferer))
                    put("content_title", room.streamTitle)
                    put("is_playing", isPlaying)
                    put("playback_position_ms", currentPosMs)
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