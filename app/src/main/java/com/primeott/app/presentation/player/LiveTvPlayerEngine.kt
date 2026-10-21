package com.primeott.app.presentation.player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.primeott.app.domain.model.AudioTrackOption
import com.primeott.app.domain.model.StreamSource
import com.primeott.app.domain.model.VideoQualityTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class LiveTvPlayerEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSourceSwitched: (Int, StreamSource) -> Unit = { _, _ -> },
    private val onTracksUpdated: (String, List<VideoQualityTrack>, List<AudioTrackOption>) -> Unit = { _, _, _ -> },
    private val onBufferingChanged: (Boolean) -> Unit = {},
    private val onPlaybackExhausted: () -> Unit = {}
) {
    companion object {
        private const val TAG = "LiveTvPlayerEngine"
        private const val MAX_RETRIES = 2
        private const val RETRY_BACKOFF_MS = 1000L
        private const val STALL_TIMEOUT_MS = 18000L

        private val liveOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    var player: ExoPlayer? = null
        private set

    private var trackSelector: DefaultTrackSelector? = null
    private var httpFactory: OkHttpDataSource.Factory? = null
    private var attachedPlayerView: PlayerView? = null
    private var sourcesList: List<StreamSource> = emptyList()
    private var currentSourceIndex: Int = 0
    private var currentLink: StreamSource? = null
    private var activePlayingUrl: String = ""
    private var selectedQualityKey: String = "AUTO"
    private var retryCount: Int = 0
    private val failedLinkUrls = LinkedHashSet<String>()

    private var detectedHlsQualities: List<VideoQualityTrack> = emptyList()
    private var detectedAudioTracks: List<AudioTrackOption> = emptyList()
    private var currentQualityBadge: String = "HD"

    private var retryJob: Job? = null
    private var stallJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    onBufferingChanged(true)
                    startStallTimer()
                }
                Player.STATE_READY -> {
                    cancelStallTimer()
                    retryCount = 0
                    onBufferingChanged(false)
                }
                Player.STATE_ENDED -> {
                    cancelStallTimer()
                    handleFailure(skipSameLinkRetry = false)
                }
                Player.STATE_IDLE -> {
                    cancelStallTimer()
                }
            }
        }

        override fun onRenderedFirstFrame() {
            cancelStallTimer()
            onBufferingChanged(false)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0) {
                currentQualityBadge = when {
                    videoSize.height >= 2160 -> "4K"
                    videoSize.height >= 1080 -> "FHD"
                    videoSize.height >= 720 -> "HD"
                    else -> "SD"
                }
                onTracksUpdated(currentQualityBadge, detectedHlsQualities, detectedAudioTracks)
            }
        }

        /**
         * قراءة الجودات الحقيقية وسرعة البث (mb/s) والمسارات الصوتية بدون عمل Flush للكودك!
         */
        override fun onTracksChanged(tracks: Tracks) {
            val videoList = ArrayList<VideoQualityTrack>()
            val audioList = ArrayList<AudioTrackOption>()
            val groups = tracks.groups

            for (gIdx in 0 until groups.size) {
                val group = groups[gIdx]
                if (group.type == C.TRACK_TYPE_VIDEO) {
                    for (tIdx in 0 until group.length) {
                        if (!group.isTrackSupported(tIdx)) continue
                        val format = group.getTrackFormat(tIdx)
                        val h = format.height
                        val w = format.width
                        val br = format.bitrate
                        if (h > 0) {
                            val mbpsText = if (br > 0) {
                                String.format(Locale.US, " (%.1fmb/s)", br / 1_000_000f)
                            } else ""
                            val label = "${h}p$mbpsText"
                            if (videoList.none { it.label == label }) {
                                videoList.add(
                                    VideoQualityTrack(
                                        label = label,
                                        width = w,
                                        height = h,
                                        bitrate = br,
                                        groupIndex = gIdx,
                                        trackIndex = tIdx
                                    )
                                )
                            }
                        }
                    }
                } else if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (tIdx in 0 until group.length) {
                        if (!group.isTrackSupported(tIdx)) continue
                        val format = group.getTrackFormat(tIdx)
                        val lang = format.language ?: "und"
                        val rawLabel = format.label ?: when {
                            lang.startsWith("ar", true) -> "العربية (معلق ${audioList.size + 1})"
                            lang.startsWith("en", true) -> "English Audio"
                            lang.startsWith("fr", true) -> "Français Audio"
                            else -> "Audio Track ${audioList.size + 1}"
                        }
                        val isSel = group.isTrackSelected(tIdx)
                        audioList.add(
                            AudioTrackOption(
                                label = rawLabel,
                                language = lang,
                                groupIndex = gIdx,
                                trackIndex = tIdx,
                                isSelected = isSel
                            )
                        )
                    }
                }
            }

            detectedHlsQualities = videoList.sortedWith(compareByDescending<VideoQualityTrack> { it.height }.thenByDescending { it.bitrate })
            detectedAudioTracks = audioList
            onTracksUpdated(currentQualityBadge, detectedHlsQualities, detectedAudioTracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            cancelStallTimer()
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player?.let { p ->
                    p.seekToDefaultPosition()
                    p.prepare()
                    p.playWhenReady = true
                }
                return
            }

            val httpCode = httpResponseCodeOf(error)
            Log.w(TAG, "PlayerError code=${error.errorCodeName}, httpCode=$httpCode")
            val skipSame = httpCode != null && (httpCode in listOf(401, 403, 404, 410, 429, 500, 502, 503, 504))
            handleFailure(skipSameLinkRetry = skipSame)
        }
    }

    fun playSources(
        playerView: PlayerView,
        sources: List<StreamSource>,
        startIndex: Int = 0
    ) {
        if (sources.isEmpty()) return
        attachedPlayerView = playerView
        sourcesList = sources
        currentSourceIndex = startIndex.coerceIn(0, sources.lastIndex)
        val target = sourcesList[currentSourceIndex]

        retryCount = 0
        selectedQualityKey = "AUTO"
        failedLinkUrls.clear()
        initOrSwitchLink(target, target.url)
        onSourceSwitched(currentSourceIndex, target)
    }

    fun switchSource(index: Int) {
        if (sourcesList.isEmpty()) return
        val newIdx = index.coerceIn(0, sourcesList.lastIndex)
        val newLink = sourcesList[newIdx]
        currentSourceIndex = newIdx
        currentLink = newLink
        retryCount = 0
        selectedQualityKey = "AUTO"
        // مسح أي قيد سابق على الجودة ليعمل السيرفر الجديد على Auto حسب سرعة الإنترنت
        clearVideoTrackOverrides()
        initOrSwitchLink(newLink, newLink.url)
        onSourceSwitched(currentSourceIndex, newLink)
    }

    /**
     * اختيار جودة Auto أو جودة HLS محددة أو رابط جودة مخصص لنفس السيرفر
     */
    fun selectQualityForCurrentServer(
        qualityKey: String,
        customQualityUrl: String? = null,
        hlsTrack: VideoQualityTrack? = null
    ) {
        val srv = currentLink ?: return
        selectedQualityKey = qualityKey

        when {
            qualityKey == "AUTO" -> {
                clearVideoTrackOverrides()
                if (activePlayingUrl != srv.url) {
                    initOrSwitchLink(srv, srv.url)
                }
            }
            !customQualityUrl.isNullOrEmpty() -> {
                clearVideoTrackOverrides()
                if (activePlayingUrl != customQualityUrl) {
                    initOrSwitchLink(srv, customQualityUrl)
                }
            }
            hlsTrack != null -> {
                val p = player ?: return
                val selector = trackSelector ?: return
                if (hlsTrack.groupIndex < p.currentTracks.groups.size) {
                    val mediaTrackGroup = p.currentTracks.groups[hlsTrack.groupIndex].mediaTrackGroup
                    val params = selector.buildUponParameters()
                        .setOverrideForType(TrackSelectionOverride(mediaTrackGroup, listOf(hlsTrack.trackIndex)))
                        .build()
                    selector.setParameters(params)
                }
            }
        }
    }

    fun selectAudioTrack(option: AudioTrackOption) {
        val p = player ?: return
        val selector = trackSelector ?: return
        if (option.groupIndex < p.currentTracks.groups.size) {
            val mediaTrackGroup = p.currentTracks.groups[option.groupIndex].mediaTrackGroup
            val params = selector.buildUponParameters()
                .setOverrideForType(TrackSelectionOverride(mediaTrackGroup, listOf(option.trackIndex)))
                .build()
            selector.setParameters(params)
        }
    }

    private fun clearVideoTrackOverrides() {
        val selector = trackSelector ?: return
        val params = selector.buildUponParameters()
            .clearVideoSizeConstraints()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
        selector.setParameters(params)
    }

    fun getSelectedQualityKey(): String = selectedQualityKey
    fun getDetectedHlsQualities(): List<VideoQualityTrack> = detectedHlsQualities
    fun getDetectedAudioTracks(): List<AudioTrackOption> = detectedAudioTracks
    fun getCurrentQualityBadge(): String = currentQualityBadge

    private fun initOrSwitchLink(link: StreamSource, targetPlayUrl: String) {
        retryJob?.cancel()
        cancelStallTimer()
        currentLink = link
        activePlayingUrl = targetPlayUrl
        detectedHlsQualities = emptyList()
        detectedAudioTracks = emptyList()
        onBufferingChanged(true)

        val appCtx = context.applicationContext
        val headers = HashMap<String, String>()
        if (link.referer.isNotEmpty()) {
            headers["Referer"] = link.referer
            val orig = link.origin.ifEmpty {
                runCatching {
                    val u = Uri.parse(link.referer)
                    "${u.scheme}://${u.host}"
                }.getOrDefault("")
            }
            if (orig.isNotEmpty()) headers["Origin"] = orig
        }

        val ua = link.userAgent.ifEmpty { "AndroidXMedia3/1.5.1" }

        val okFactory = OkHttpDataSource.Factory(liveOkHttpClient)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
        httpFactory = okFactory

        val dataSourceFactory = DefaultDataSource.Factory(appCtx, okFactory)

        val mimeType: String? = when {
            targetPlayUrl.contains("/playlist/", ignoreCase = true) || targetPlayUrl.contains(".m3u8", ignoreCase = true) ->
                MimeTypes.APPLICATION_M3U8
            targetPlayUrl.contains(".mpd", ignoreCase = true) ->
                MimeTypes.APPLICATION_MPD
            else -> null
        }

        val mediaItemBuilder = MediaItem.Builder().setUri(targetPlayUrl)
        if (mimeType != null) {
            mediaItemBuilder.setMimeType(mimeType)
        }
        val mediaItem = mediaItemBuilder.build()

        val mediaSource: MediaSource = DefaultMediaSourceFactory(dataSourceFactory)
            .createMediaSource(mediaItem)

        val existingPlayer = player
        if (existingPlayer == null) {
            val renderersFactory = DefaultRenderersFactory(appCtx)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

            val selector = DefaultTrackSelector(appCtx)
            val params = selector.buildUponParameters()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .build()
            selector.setParameters(params)
            trackSelector = selector

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            val newExo = ExoPlayer.Builder(appCtx, renderersFactory)
                .setTrackSelector(selector)
                .setLoadControl(buildLoadControl(appCtx))
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setAudioAttributes(audioAttributes, true)
                .build()

            newExo.addListener(playerListener)
            newExo.setMediaItem(mediaItem, C.TIME_UNSET)
            newExo.prepare()
            newExo.playWhenReady = true

            player = newExo
            attachedPlayerView?.player = newExo
        } else {
            if (attachedPlayerView?.player !== existingPlayer) {
                attachedPlayerView?.player = existingPlayer
            }
            existingPlayer.setMediaSource(mediaSource)
            existingPlayer.prepare()
            existingPlayer.playWhenReady = true
        }
    }

    private fun buildLoadControl(ctx: Context): DefaultLoadControl {
        val defaultMaxBuffer = 35_000
        val defaultTargetBytes = 20 * 1024 * 1024

        val (maxBufferMs, targetBytes) = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val totalMb = memInfo.totalMem / (1024 * 1024)
            when {
                totalMb < 2048L -> Pair(20_000, 10 * 1024 * 1024)
                totalMb < 3584L -> Pair(35_000, 20 * 1024 * 1024)
                else -> Pair(60_000, C.LENGTH_UNSET)
            }
        }.getOrDefault(Pair(defaultMaxBuffer, defaultTargetBytes))

        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, maxBufferMs, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(targetBytes)
            .build()
    }

    private fun handleFailure(skipSameLinkRetry: Boolean) {
        val link = currentLink ?: return

        if (!skipSameLinkRetry && retryCount < MAX_RETRIES) {
            retryCount++
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(RETRY_BACKOFF_MS)
                val p = player
                if (p != null) {
                    p.prepare()
                    p.play()
                } else {
                    initOrSwitchLink(link, activePlayingUrl.ifEmpty { link.url })
                }
            }
            return
        }

        failedLinkUrls.add(link.url)
        val nextIndex = sourcesList.indexOfFirst { !failedLinkUrls.contains(it.url) }
        if (nextIndex != -1) {
            switchSource(nextIndex)
        } else {
            failedLinkUrls.clear()
            retryCount = 0
            onBufferingChanged(false)
            onPlaybackExhausted()
        }
    }

    private fun startStallTimer() {
        cancelStallTimer()
        stallJob = scope.launch {
            delay(STALL_TIMEOUT_MS)
            player?.stop()
            handleFailure(skipSameLinkRetry = false)
        }
    }

    private fun cancelStallTimer() {
        stallJob?.cancel()
        stallJob = null
    }

    fun pauseAndReleaseSocket() {
        retryJob?.cancel()
        cancelStallTimer()
        onBufferingChanged(false)
        player?.let { p ->
            p.playWhenReady = false
            p.stop()
        }
    }

    fun pause() {
        cancelStallTimer()
        player?.playWhenReady = false
    }

    fun resume() {
        val p = player
        if (p == null) {
            currentLink?.let { initOrSwitchLink(it, activePlayingUrl.ifEmpty { it.url }) }
            return
        }
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            p.prepare()
        }
        p.playWhenReady = true
    }

    fun isPlaying(): Boolean = player?.playWhenReady == true && player?.playbackState != Player.STATE_IDLE

    fun getCurrentIndex(): Int = currentSourceIndex

    fun getSources(): List<StreamSource> = sourcesList

    private fun httpResponseCodeOf(error: Throwable?): Int? {
        var cur = error
        while (cur != null) {
            if (cur is HttpDataSource.InvalidResponseCodeException) {
                return cur.responseCode
            }
            cur = cur.cause
        }
        return null
    }

    fun release() {
        retryJob?.cancel()
        cancelStallTimer()
        attachedPlayerView?.player = null
        player?.let {
            it.removeListener(playerListener)
            it.stop()
            it.release()
        }
        player = null
        attachedPlayerView = null
    }
}