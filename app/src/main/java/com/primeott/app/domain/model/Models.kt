package com.primeott.app.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class StreamSource(
    val label: String,
    val url: String,
    val referer: String = "",
    val origin: String = "",
    val userAgent: String = "",
    val isDirectStream: Boolean = true
) : Parcelable

data class VideoQualityTrack(
    val label: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val groupIndex: Int,
    val trackIndex: Int
)

@Parcelize
data class Category(
    val id: String,
    val name: String,
    val iconUrl: String = ""
) : Parcelable

@Parcelize
data class Channel(
    val id: String,
    val categoryId: String,
    val name: String,
    val logoUrl: String,
    val streamUrls: List<String>,
    val isFavorite: Boolean = false,
    val namedSources: List<StreamSource> = emptyList()
) : Parcelable

@Parcelize
data class VodItem(
    val id: String,
    val title: String,
    val category: String,
    val rating: String,
    val year: String,
    val posterUrl: String,
    val streamUrls: List<String>,
    val isSeries: Boolean = false,
    val description: String = "",
    val backdropUrl: String = "",
    val savedPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L
) : Parcelable

data class WatchHistoryEntry(
    val id: String,
    val title: String,
    val posterUrl: String,
    val backdropUrl: String,
    val rating: String,
    val year: String,
    val isSeries: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

data class CastMember(
    val id: String,
    val name: String,
    val character: String,
    val profileUrl: String
)

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class SubtitleTrack(
    val id: String,
    val languageCode: String,
    val languageLabel: String,
    val releaseName: String,
    val url: String
)

data class MovieFullDetails(
    val id: String,
    val imdbId: String,
    val title: String,
    val rating: String,
    val year: String,
    val runtimeText: String,
    val genres: List<String>,
    val overview: String,
    val posterUrl: String,
    val backdropUrl: String,
    val trailerYoutubeKey: String,
    val cast: List<CastMember>,
    val recommended: List<VodItem>,
    val similar: List<VodItem>,
    val providerEmbedUrls: List<StreamSource>,
    val numberOfSeasons: Int = 1
)

@Parcelize
data class MatchFixture(
    val id: String,
    val homeTeam: String,
    val awayTeam: String,
    val matchTime: String,
    val league: String,
    val commentator: String,
    val status: String,
    val channel: Channel,
    val homeLogo: String = "",
    val awayLogo: String = "",
    val homeScore: String = "-",
    val awayScore: String = "-",
    val liveMinute: String = "",
    val stadium: String = "",
    val dayTab: String = "TODAY",
    val leagueLogo: String = "",
    val isFavoriteMatch: Boolean = false
) : Parcelable

@Parcelize
data class WatchRoom(
    val roomId: String,
    val roomCode: String,
    val roomName: String,
    val roomIcon: String,
    val hostName: String,
    val isHost: Boolean = false,
    val isPrivate: Boolean = false,
    val isLocked: Boolean = false,
    val allowGuestsControl: Boolean = false,
    val contentType: String = "LIVE",
    val streamId: String = "6171",
    val contentTitle: String = "",
    val contentPoster: String = "",
    val streamUrl: String = "",
    val isPlaying: Boolean = true,
    val playbackPositionMs: Long = 0L,
    val syncSeq: Int = 1,
    val participantCount: Int = 1,
    val inviteUrl: String = ""
) : Parcelable

data class RoomParticipant(
    val deviceId: String,
    val userName: String,
    val role: String,
    val isMuted: Boolean,
    val inVoice: Boolean
)

data class RoomChatMessage(
    val id: Long,
    val senderId: String,
    val senderName: String,
    val messageType: String,
    val content: String,
    val createdAt: Long
)