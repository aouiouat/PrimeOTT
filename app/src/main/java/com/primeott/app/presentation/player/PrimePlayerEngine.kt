package com.primeott.app.presentation.player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.primeott.app.domain.model.StreamSource
import com.primeott.app.domain.model.VideoQualityTrack
import kotlinx.coroutines.*

@OptIn(UnstableApi::class)
class PrimePlayerEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSourceSwitched: (Int, StreamSource) -> Unit = { _, _ -> },
    private val onRealQualityDetected: (String, List<VideoQualityTrack>) -> Unit = { _, _ -> },
    private val onPlaybackExhausted: () -> Unit = {}
) {
    companion object {
        private const val TAG = "PrimePlayerEngine"
        private const val MAX_RETRIES = 2
        private const val RETRY_BACKOFF_MS = 900L
        const val STALL_TIMEOUT_MS = 18000L
        private const val TS_FLAGS = 9

        fun sanitizeImageUrl(url: String?): String {
            if (url.isNullOrBlank()) return ""
            return url.trim()
                .replace("https://github.com/", "https://raw.githubusercontent.com/")
                .replace("http://github.com/", "https://raw.githubusercontent.com/")
                .replace("/blob/", "/")
        }
    }

    var player: ExoPlayer? = null
        private set

    private var trackSelector: DefaultTrackSelector? = null
    private var attachedPlayerView: PlayerView? = null
    private var sourcesList: List<StreamSource> = emptyList()
    private var currentSourceIndex: Int = 0
    private var currentLink: StreamSource? = null
    private var retryCount: Int = 0
    private val failedLinkUrls = LinkedHashSet<String>()
    private var availableQualityTracks: List<VideoQualityTrack> = emptyList()
    private var currentDetectedQualityLabel: String = "FHD"
    private var retryJob: Job? = null
    private var stallJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> startStallTimer()
                Player.STATE_READY -> {
                    cancelStallTimer()
                    retryCount = 0
                }
                Player.STATE_ENDED -> {
                    cancelStallTimer()
                    val url = currentLink?.url ?: ""
                    if (isLiveStreamUrl(url)) {
                        handleFailure(skipSameLinkRetry = false)
                    }
                }
                Player.STATE_IDLE -> cancelStallTimer()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                cancelStallTimer()
                retryCount = 0
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0) {
                currentDetectedQualityLabel = formatHeightToQualityLabel(videoSize.height)
                onRealQualityDetected(currentDetectedQualityLabel, availableQualityTracks)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val parsedList = ArrayList<VideoQualityTrack>()
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
                            val label = "${h}p (${formatHeightBadge(h)})"
                            if (parsedList.none { it.height == h }) {
                                parsedList.add(
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
                }
            }
            availableQualityTracks = parsedList.sortedByDescending { it.height }
            val activeHeight = player?.videoSize?.height ?: availableQualityTracks.firstOrNull()?.height ?: 0
            if (activeHeight > 0) {
                currentDetectedQualityLabel = formatHeightToQualityLabel(activeHeight)
            }
            onRealQualityDetected(currentDetectedQualityLabel, availableQualityTracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            cancelStallTimer()
            val httpCode = httpResponseCodeOf(error)
            Log.w(TAG, "PlayerError code=${error.errorCodeName}, httpCode=$httpCode")
            val skipSame = httpCode != null && (httpCode in listOf(401, 403, 404, 410, 429, 500, 502, 503, 504))
            handleFailure(skipSameLinkRetry = skipSame)
        }
    }

    private fun formatHeightToQualityLabel(height: Int): String {
        return when {
            height >= 2160 -> "4K"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height >= 480 -> "480p"
            height > 0 -> "${height}p"
            else -> "FHD"
        }
    }

    private fun formatHeightBadge(height: Int): String {
        return when {
            height >= 2160 -> "4K UHD"
            height >= 1080 -> "FHD"
            height >= 720 -> "HD"
            else -> "SD"
        }
    }

    fun selectVideoQualityHeight(maxHeight: Int?) {
        val selector = trackSelector ?: return
        val paramsBuilder = selector.buildUponParameters()
        if (maxHeight == null || maxHeight <= 0) {
            paramsBuilder.clearVideoSizeConstraints()
            paramsBuilder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            currentDetectedQualityLabel = "Auto"
        } else {
            paramsBuilder.setMaxVideoSize(Int.MAX_VALUE, maxHeight)
            val p = player
            if (p != null) {
                val matchingTrack = availableQualityTracks.firstOrNull { it.height == maxHeight }
                if (matchingTrack != null && matchingTrack.groupIndex < p.currentTracks.groups.size) {
                    val mediaTrackGroup = p.currentTracks.groups[matchingTrack.groupIndex].mediaTrackGroup
                    paramsBuilder.setOverrideForType(
                        TrackSelectionOverride(mediaTrackGroup, listOf(matchingTrack.trackIndex))
                    )
                }
            }
            currentDetectedQualityLabel = formatHeightToQualityLabel(maxHeight)
        }
        onRealQualityDetected(currentDetectedQualityLabel, availableQualityTracks)
        selector.setParameters(paramsBuilder)
    }

    fun getAvailableQualityTracks(): List<VideoQualityTrack> = availableQualityTracks
    fun getCurrentDetectedQualityLabel(): String = currentDetectedQualityLabel

    fun playSources(
        playerView: PlayerView,
        sources: List<StreamSource>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L
    ) {
        if (sources.isEmpty()) return
        attachedPlayerView = playerView
        sourcesList = sources
        currentSourceIndex = startIndex.coerceIn(0, sources.lastIndex)
        val target = sourcesList[currentSourceIndex]

        // منع إعادة بناء المشغل إذا كان نفس الرابط يشتغل حالياً
        if (player != null && currentLink?.url == target.url && player?.playbackState != Player.STATE_IDLE) {
            return
        }

        retryCount = 0
        failedLinkUrls.clear()
        rebuildPlayer(target, startPositionMs)
        onSourceSwitched(currentSourceIndex, target)
    }

    /**
     * إضافة مصادر احتياطية بصمت للقائمة دون مقاطعة الفيديو الشغال حالياً
     */
    fun appendBackupSourcesSilently(extraSources: List<StreamSource>) {
        if (extraSources.isEmpty()) return
        val updated = sourcesList.toMutableList()
        extraSources.forEach { src ->
            if (updated.none { it.url == src.url }) {
                updated.add(src)
            }
        }
        sourcesList = updated
    }

    fun switchSource(index: Int, seekToMs: Long = 0L) {
        if (sourcesList.isEmpty()) return
        val newIdx = index.coerceIn(0, sourcesList.lastIndex)
        val newLink = sourcesList[newIdx]
        currentSourceIndex = newIdx
        currentLink = newLink
        retryCount = 0
        rebuildPlayer(newLink, seekToMs)
        onSourceSwitched(currentSourceIndex, newLink)
    }

    fun pauseAndReleaseSocket() {
        retryJob?.cancel()
        cancelStallTimer()
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
            currentLink?.let { rebuildPlayer(it, 0L) }
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

    private fun rebuildPlayer(link: StreamSource, seekToMs: Long) {
        retryJob?.cancel()
        cancelStallTimer()

        attachedPlayerView?.player = null
        player?.let { old ->
            old.removeListener(playerListener)
            old.stop()
            old.release()
        }
        player = null

        val appCtx = context.applicationContext
        currentLink = link
        val newExo = buildExoPlayer(appCtx, link)
        if (seekToMs > 0L && !isLiveStreamUrl(link.url)) {
            newExo.seekTo(seekToMs)
        }
        player = newExo
        attachedPlayerView?.player = newExo
    }

    private fun buildExoPlayer(appCtx: Context, link: StreamSource): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val ua = when {
            link.userAgent.isNotEmpty() -> link.userAgent
            link.referer.isNotEmpty() -> "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            else -> "AndroidXMedia3/1.5.1"
        }

        val defaultHeaders = HashMap<String, String>()
        if (link.referer.isNotEmpty()) {
            defaultHeaders["Referer"] = link.referer
            val orig = if (link.origin.isNotEmpty()) {
                link.origin
            } else {
                runCatching {
                    val uri = Uri.parse(link.referer)
                    "${uri.scheme}://${uri.host}"
                }.getOrDefault("")
            }
            if (orig.isNotEmpty()) {
                defaultHeaders["Origin"] = orig
            }
        }

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(defaultHeaders)

        val dataSourceFactory = DefaultDataSource.Factory(appCtx, http)

        // تفعيل المعالجة العتادية المتوازية (Async Queueing) لمنع أي تقطيع أو لاغ في أفلام 1080p
        val renderersFactory = DefaultRenderersFactory(appCtx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val selector = DefaultTrackSelector(appCtx)
        val paramsBuilder = selector.buildUponParameters()
            .setAllowVideoMixedMimeTypeAdaptiveness(true)
            .setAllowAudioMixedMimeTypeAdaptiveness(true)
            .setTunnelingEnabled(false)
        selector.setParameters(paramsBuilder)
        trackSelector = selector

        val exo = ExoPlayer.Builder(appCtx, renderersFactory)
            .setTrackSelector(selector)
            .setLoadControl(buildLoadControl(appCtx))
            .setMediaSourceFactory(buildMediaSourceFactory(dataSourceFactory, link.url))
            .setAudioAttributes(audioAttributes, true)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .build()

        exo.addListener(playerListener)
        exo.setMediaItem(MediaItem.fromUri(link.url))
        exo.prepare()
        exo.playWhenReady = true
        return exo
    }

    private fun buildLoadControl(ctx: Context): DefaultLoadControl {
        val defaultMaxBuffer = 50_000
        val defaultTargetBytes = -1

        val (maxBufferMs, targetBytes) = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val totalMb = memInfo.totalMem / (1024 * 1024)
            when {
                totalMb < 2048L -> Pair(25_000, 16 * 1024 * 1024)
                totalMb < 3584L -> Pair(45_000, 32 * 1024 * 1024)
                else -> Pair(60_000, -1)
            }
        }.getOrDefault(Pair(defaultMaxBuffer, defaultTargetBytes))

        // يشتغل الفيديو فوراً بعد 500ms فقط من التحميل ويحتفظ بـ 60 ثانية كاملة لمنع أي لاغ
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, maxBufferMs, 500, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(targetBytes)
            .setBackBuffer(30_000, true)
            .build()
    }

    private fun buildMediaSourceFactory(
        dataSourceFactory: DataSource.Factory,
        url: String
    ): MediaSource.Factory {
        return if (isHlsUrl(url)) {
            val hlsExtractor = DefaultHlsExtractorFactory(TS_FLAGS, true)
            HlsMediaSource.Factory(dataSourceFactory)
                .setExtractorFactory(hlsExtractor)
                .setAllowChunklessPreparation(true)
        } else {
            val extractors = DefaultExtractorsFactory()
                .setTsExtractorFlags(TS_FLAGS)
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true)
            DefaultMediaSourceFactory(dataSourceFactory, extractors)
        }
    }

    private fun isHlsUrl(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true) ||
                url.contains("/playlist", ignoreCase = true) ||
                url.contains("master.txt", ignoreCase = true) ||
                url.contains("m3u8", ignoreCase = true)
    }

    private fun isLiveStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/live/") || lower.endsWith(".ts")
    }

    private fun handleFailure(skipSameLinkRetry: Boolean) {
        val link = currentLink ?: return

        if (!skipSameLinkRetry && retryCount < MAX_RETRIES) {
            retryCount++
            val savedPos = (player?.currentPosition ?: 0L).coerceAtLeast(0L)
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(RETRY_BACKOFF_MS)
                rebuildPlayer(link, if (isLiveStreamUrl(link.url)) 0L else savedPos)
            }
            return
        }

        failedLinkUrls.add(link.url)
        val nextIndex = sourcesList.indexOfFirst { !failedLinkUrls.contains(it.url) }
        if (nextIndex != -1) {
            switchSource(nextIndex, 0L)
        } else {
            onPlaybackExhausted()
        }
    }

    private fun startStallTimer() {
        cancelStallTimer()
        stallJob = scope.launch {
            delay(STALL_TIMEOUT_MS)
            val p = player
            if (p != null && p.playbackState == Player.STATE_BUFFERING && !p.isPlaying) {
                p.stop()
                handleFailure(skipSameLinkRetry = false)
            }
        }
    }

    private fun cancelStallTimer() {
        stallJob?.cancel()
        stallJob = null
    }

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