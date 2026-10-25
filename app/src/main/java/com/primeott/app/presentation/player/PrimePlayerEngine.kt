package com.primeott.app.presentation.player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import com.primeott.app.domain.model.StreamSource
import com.primeott.app.domain.model.VideoQualityTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class PrimePlayerEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSourceSwitched: (Int, StreamSource) -> Unit = { _, _ -> },
    private val onRealQualityDetected: (String, List<VideoQualityTrack>) -> Unit = { _, _ -> },
    private val onFirstFrameRendered: () -> Unit = {},
    private val onPlaybackExhausted: () -> Unit = {}
) {
    companion object {
        private const val TAG = "PrimePlayerEngine"
        private const val MAX_RETRIES = 2
        private const val RETRY_BACKOFF_MS = 450L
        const val STALL_TIMEOUT_MS = 16_000L
        private const val TS_FLAGS =
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

        private val cookieStore = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

        private val sessionCookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostMap = cookieStore.getOrPut(url.host) { ConcurrentHashMap() }
                for (c in cookies) {
                    hostMap[c.name] = c
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host]?.values?.toList() ?: emptyList()
            }
        }

        // محرك OkHttp سريع مع ConnectionPool بدون استدعاء CookieManager IPC في كل قطعة فيديو
        val vodOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .cookieJar(sessionCookieJar)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

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
    private var httpFactory: OkHttpDataSource.Factory? = null
    private var attachedPlayerView: PlayerView? = null
    private var sourcesList: List<StreamSource> = emptyList()
    private var currentSourceIndex: Int = 0
    private var currentLink: StreamSource? = null
    private var retryCount: Int = 0
    private var userLockedQualityHeight: Int? = null
    private val failedLinkUrls = LinkedHashSet<String>()
    private var availableQualityTracks: List<VideoQualityTrack> = emptyList()
    private var currentDetectedQualityLabel: String = "1080p"
    private var retryJob: Job? = null
    private var stallJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> startStallTimer()
                Player.STATE_READY -> {
                    cancelStallTimer()
                    retryCount = 0
                    val p = player ?: return
                    val url = currentLink?.url ?: ""
                    if (url.contains("netnaija", ignoreCase = true) && p.duration in 1_000L..90_000L) {
                        Log.w(TAG, "Rejected short NetNaija promo clip (duration=${p.duration}ms) — switching server")
                        handleFailure(skipSameLinkRetry = true)
                        return
                    }
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

        override fun onRenderedFirstFrame() {
            cancelStallTimer()
            onFirstFrameRendered()
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

            // نترك Adaptive Bitrate يشتغل بحرية مثل PrimeTV لمنع التقطيع، إلا إذا اختار المستخدم جودة معينة يدوياً
            val activeHeight = player?.videoSize?.height ?: availableQualityTracks.firstOrNull()?.height ?: 0
            if (activeHeight > 0 && userLockedQualityHeight == null) {
                currentDetectedQualityLabel = formatHeightToQualityLabel(activeHeight)
            }
            onRealQualityDetected(currentDetectedQualityLabel, availableQualityTracks)
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
            val skipSame = httpCode != null && (httpCode in listOf(401, 403, 404, 410))
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
            else -> "1080p"
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
        userLockedQualityHeight = maxHeight
        val selector = trackSelector ?: return
        val paramsBuilder = selector.buildUponParameters()
        if (maxHeight == null || maxHeight <= 0) {
            paramsBuilder.clearVideoSizeConstraints()
            paramsBuilder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            currentDetectedQualityLabel = "Auto"
        } else {
            paramsBuilder.setMaxVideoSize(Int.MAX_VALUE, maxHeight)
            paramsBuilder.setMinVideoSize(0, maxHeight)
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

        if (player != null && currentLink?.url == target.url && player?.playbackState != Player.STATE_IDLE) {
            if (attachedPlayerView?.player !== player) {
                attachedPlayerView?.player = player
            }
            player?.playWhenReady = true
            return
        }

        retryCount = 0
        userLockedQualityHeight = null
        failedLinkUrls.clear()
        prepareOrSwitchPlayer(target, startPositionMs)
        onSourceSwitched(currentSourceIndex, target)
    }

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
        userLockedQualityHeight = null
        prepareOrSwitchPlayer(newLink, seekToMs)
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
            currentLink?.let { prepareOrSwitchPlayer(it, 0L) }
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

    /**
     * يبدل الرابط أو يهيئ المشغل بنفس طريقة PrimeTV (إعادة استخدام ExoPlayer لتفادي التشنج والـ Crash)
     */
    private fun prepareOrSwitchPlayer(link: StreamSource, seekToMs: Long) {
        retryJob?.cancel()
        cancelStallTimer()

        val appCtx = context.applicationContext
        val rawUrl = link.url.trim()
        val cleanUrl = rawUrl.substringBefore("|Referer=").trim()
        val inlineRef = if (rawUrl.contains("|Referer=")) rawUrl.substringAfter("|Referer=").trim() else ""
        val effectiveReferer = when {
            inlineRef.startsWith("http", ignoreCase = true) -> inlineRef
            link.referer.startsWith("http", ignoreCase = true) -> link.referer.trim()
            else -> ""
        }

        val sanitizedLink = link.copy(url = cleanUrl, referer = effectiveReferer)
        currentLink = sanitizedLink

        val isVixSrc = cleanUrl.contains("vixsrc.to", ignoreCase = true)
        val ua = when {
            sanitizedLink.userAgent.isNotBlank() -> sanitizedLink.userAgent
            isVixSrc -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            effectiveReferer.isNotBlank() -> "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            else -> "AndroidXMedia3/1.5.1"
        }

        val defaultHeaders = HashMap<String, String>()
        if (effectiveReferer.isNotBlank()) {
            defaultHeaders["Referer"] = effectiveReferer
            val orig = if (sanitizedLink.origin.startsWith("http", ignoreCase = true)) {
                sanitizedLink.origin.trim()
            } else {
                runCatching {
                    val uri = Uri.parse(effectiveReferer)
                    if (!uri.scheme.isNullOrEmpty() && !uri.host.isNullOrEmpty()) {
                        "${uri.scheme}://${uri.host}"
                    } else ""
                }.getOrDefault("")
            }
            if (orig.isNotBlank()) {
                defaultHeaders["Origin"] = orig
            }
        }

        // جلب الكوكيز مرة واحدة فقط عند تحضير الرابط بدلاً من كل قطعة فيديو لتفادي الـ Freeze
        val mergedCookies = buildList {
            runCatching { CookieManager.getInstance().getCookie(cleanUrl) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (effectiveReferer.isNotBlank()) {
                runCatching { CookieManager.getInstance().getCookie(effectiveReferer) }
                    .getOrNull()?.takeIf { it.isNotBlank() && !contains(it) }?.let { add(it) }
            }
        }.joinToString("; ")
        if (mergedCookies.isNotBlank()) {
            defaultHeaders["Cookie"] = mergedCookies
        }

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(vodOkHttpClient)
            .setUserAgent(ua)
            .setDefaultRequestProperties(defaultHeaders)
        httpFactory = okHttpDataSourceFactory

        val dataSourceFactory = DefaultDataSource.Factory(appCtx, okHttpDataSourceFactory)
        val mediaSource = createMediaSourceForUrl(dataSourceFactory, cleanUrl)

        val existingExo = player
        if (existingExo == null) {
            val newExo = createExoPlayerInstance(appCtx, dataSourceFactory)
            player = newExo
            attachedPlayerView?.player = newExo
            val startPos = if (seekToMs > 0L && !isLiveStreamUrl(cleanUrl)) seekToMs else C.TIME_UNSET
            newExo.setMediaSource(mediaSource, startPos)
            newExo.prepare()
            newExo.playWhenReady = true
        } else {
            if (attachedPlayerView?.player !== existingExo) {
                attachedPlayerView?.player = existingExo
            }
            val startPos = if (seekToMs > 0L && !isLiveStreamUrl(cleanUrl)) seekToMs else C.TIME_UNSET
            existingExo.setMediaSource(mediaSource, startPos)
            existingExo.prepare()
            existingExo.playWhenReady = true
        }
    }

    private fun createExoPlayerInstance(appCtx: Context, dataSourceFactory: DataSource.Factory): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val renderersFactory = DefaultRenderersFactory(appCtx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .forceEnableMediaCodecAsynchronousQueueing()

        val selector = DefaultTrackSelector(appCtx)
        // تعطيل TRACK_TYPE_TEXT المدمج في HLS لأن بعض سيرفرات الأفلام تضع ملفات ترجمة 404 داخل المانيفيست فتوقف المشغل!
        val paramsBuilder = selector.buildUponParameters()
            .setAllowVideoMixedMimeTypeAdaptiveness(true)
            .setAllowAudioMixedMimeTypeAdaptiveness(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setTunnelingEnabled(false)
            .build()
        selector.setParameters(paramsBuilder)
        trackSelector = selector

        return ExoPlayer.Builder(appCtx, renderersFactory)
            .setTrackSelector(selector)
            .setLoadControl(buildLoadControl(appCtx))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes, true)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()
            .also { it.addListener(playerListener) }
    }

    private fun createMediaSourceForUrl(
        dataSourceFactory: DataSource.Factory,
        url: String
    ): MediaSource {
        val mimeType: String? = when {
            isHlsUrl(url) -> MimeTypes.APPLICATION_M3U8
            url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }

        val itemBuilder = MediaItem.Builder().setUri(url)
        if (mimeType != null) {
            itemBuilder.setMimeType(mimeType)
        }
        val mediaItem = itemBuilder.build()
        val errorPolicy = DefaultLoadErrorHandlingPolicy(4)

        return if (isHlsUrl(url)) {
            val hlsExtractor = DefaultHlsExtractorFactory(TS_FLAGS, true)
            HlsMediaSource.Factory(dataSourceFactory)
                .setExtractorFactory(hlsExtractor)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(errorPolicy)
                .createMediaSource(mediaItem)
        } else {
            val extractors = DefaultExtractorsFactory()
                .setTsExtractorFlags(TS_FLAGS)
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true)
            DefaultMediaSourceFactory(dataSourceFactory, extractors)
                .setLoadErrorHandlingPolicy(errorPolicy)
                .createMediaSource(mediaItem)
        }
    }

    /**
     * إعدادات Buffer مخصصة لمنع التقطيع والـ Freeze في الأفلام والمسلسلات (إقلاع في 1.2 ثانية وتخزين مسبق 20-55 ثانية)
     */
    private fun buildLoadControl(ctx: Context): DefaultLoadControl {
        val maxBufferMs = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val totalMb = memInfo.totalMem / (1024 * 1024)
            when {
                totalMb < 2048L -> 30_000
                totalMb < 3584L -> 45_000
                else -> 60_000
            }
        }.getOrDefault(50_000)

        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, maxBufferMs, 1_200, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .setBackBuffer(15_000, true)
            .build()
    }

    private fun isHlsUrl(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true) ||
                url.contains("/playlist/", ignoreCase = true) ||
                url.contains("/playlist", ignoreCase = true) ||
                url.contains("master.txt", ignoreCase = true) ||
                url.contains("index.txt", ignoreCase = true) ||
                url.contains("/hls/", ignoreCase = true) ||
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
                prepareOrSwitchPlayer(link, if (isLiveStreamUrl(link.url)) 0L else savedPos)
            }
            return
        }

        failedLinkUrls.add(link.url)
        val nextIndex = sourcesList.indexOfFirst { !failedLinkUrls.contains(it.url) }
        if (nextIndex != -1) {
            val savedPos = (player?.currentPosition ?: 0L).coerceAtLeast(0L)
            switchSource(nextIndex, if (isLiveStreamUrl(link.url)) 0L else savedPos)
        } else {
            onPlaybackExhausted()
        }
    }

    private fun startStallTimer() {
        cancelStallTimer()
        stallJob = scope.launch {
            delay(STALL_TIMEOUT_MS)
            val p = player
            if (p != null && p.playbackState == Player.STATE_BUFFERING && !p.isPlaying && p.totalBufferedDuration < 1500L) {
                p.stop()
                handleFailure(skipSameLinkRetry = true)
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