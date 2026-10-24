package com.primeott.app.presentation.watch

import com.primeott.app.data.remote.SessionManager

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