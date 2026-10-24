package com.primeott.app.presentation.watch

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.PrimeVodExtractor
import com.primeott.app.domain.model.StreamSource
import kotlinx.coroutines.*
import java.util.Locale

class WatchRoomStreamSniffer(
    private val hostContext: Context,
    private val rootView: View,
    private val scope: CoroutineScope,
    private val onStatusUpdate: (String) -> Unit,
    private val onBufferingChanged: (Boolean) -> Unit,
    private val onStreamResolved: (StreamSource, String, Boolean) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    val currentProviderTargets = mutableListOf<StreamSource>()
    val resolvedDirectStreams = mutableListOf<StreamSource>()
    val triedProviderIndices = mutableSetOf<Int>()
    var currentProviderIndex = 0
    var isExtractingNewMovie = false

    private var headlessSnifferWebView: WebView? = null
    private var snifferExtractionJob: Job? = null
    private var snifferTimeoutRunnable: Runnable? = null
    private var isDestroyed = false

    fun isLikelyDirectStreamUrl(rawUrl: String): Boolean {
        val lower = rawUrl.lowercase(Locale.ROOT)
        return (lower.contains(".m3u8") ||
                lower.contains(".mp4") ||
                lower.contains("/playlist/") ||
                lower.contains("master.txt") ||
                lower.contains("m3u8-proxy") ||
                lower.contains("/hls/") ||
                lower.contains("mime=video")) &&
                !lower.contains("netnaija.film/wp-content") &&
                !lower.contains("promo") &&
                !lower.contains("trailer") &&
                !lower.endsWith(".js") &&
                !lower.endsWith(".css") &&
                !lower.endsWith(".png") &&
                !lower.endsWith(".jpg") &&
                !lower.contains("doubleclick") &&
                !lower.contains("googlesyndication")
    }

    fun fetchBackupServersInBackground(tmdbId: String, title: String) {
        val cleanId = tmdbId.replace("tmdb_movie_", "").replace("tmdb_tv_", "").replace("tmdb_", "").trim()
        if (cleanId.isEmpty() || cleanId == "0") return
        scope.launch {
            val servers = withContext(Dispatchers.IO) {
                PrimeVodExtractor.fetchPanelConfiguredServers(
                    tmdbId = cleanId,
                    imdbId = "",
                    title = title,
                    isSeries = false,
                    season = 1,
                    episode = 1
                )
            }
            if (servers.isNotEmpty()) {
                currentProviderTargets.clear()
                currentProviderTargets.addAll(servers)
                val firstName = servers.first().label.ifBlank { "REYZO 1" }
                onStatusUpdate("  $firstName")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun extractAndPlayMovie(media: MediaStreamOption, onSubtitlesTrigger: (String) -> Unit) {
        isExtractingNewMovie = true
        snifferExtractionJob?.cancel()
        cleanupHeadlessSniffer()
        currentProviderTargets.clear()
        resolvedDirectStreams.clear()
        triedProviderIndices.clear()
        currentProviderIndex = 0

        val cleanTmdbId = media.id
            .replace("tmdb_movie_", "")
            .replace("tmdb_tv_", "")
            .replace("tmdb_", "")
            .trim()

        onSubtitlesTrigger(cleanTmdbId)
        onBufferingChanged(true)
        onStatusUpdate("  جاري الاتصال...")

        snifferExtractionJob = scope.launch {
            val servers = withContext(Dispatchers.IO) {
                runCatching {
                    PrimeVodExtractor.fetchPanelConfiguredServers(
                        tmdbId = cleanTmdbId,
                        imdbId = "",
                        title = media.title,
                        isSeries = false,
                        season = 1,
                        episode = 1
                    )
                }.getOrDefault(emptyList())
            }

            currentProviderTargets.clear()
            currentProviderTargets.addAll(servers)

            if (servers.isEmpty()) {
                isExtractingNewMovie = false
                onBufferingChanged(false)
                Toast.makeText(hostContext, "⚠️ لا توجد سيرفرات مفعلة لهذا الفيلم حالياً", Toast.LENGTH_SHORT).show()
                return@launch
            }

            onStatusUpdate("  ${servers.first().label.ifBlank { "REYZO 1" }}")

            val directIdx = servers.indexOfFirst { (it.isDirectStream || isLikelyDirectStreamUrl(it.url)) && it.url.isNotBlank() }
            if (directIdx != -1) {
                currentProviderIndex = directIdx
                triedProviderIndices.add(directIdx)
                val directSrv = servers[directIdx].copy(isDirectStream = true)
                resolvedDirectStreams.add(0, directSrv)
                isExtractingNewMovie = false
                onStreamResolved(directSrv, media.title, true)
                return@launch
            }

            loadProviderInHeadlessSniffer(0, media.title)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface")
    fun loadProviderInHeadlessSniffer(index: Int, currentStreamTitle: String) {
        if (isDestroyed || currentProviderTargets.isEmpty()) return
        currentProviderIndex = index.coerceIn(0, currentProviderTargets.lastIndex)
        triedProviderIndices.add(currentProviderIndex)
        val target = currentProviderTargets[currentProviderIndex]

        onStatusUpdate("  ${target.label.ifBlank { "REYZO ${currentProviderIndex + 1}" }}")
        onBufferingChanged(true)

        if (target.isDirectStream || isLikelyDirectStreamUrl(target.url)) {
            val directSrc = target.copy(isDirectStream = true)
            resolvedDirectStreams.add(0, directSrc)
            isExtractingNewMovie = false
            onStreamResolved(directSrc, currentStreamTitle, true)
            return
        }

        cleanupHeadlessSniffer()

        val snifferUa = target.userAgent.ifBlank {
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        var streamCapturedForThisTarget = false
        val wv = WebView(hostContext)
        headlessSnifferWebView = wv

        if (rootView is ViewGroup) {
            val hiddenParams = FrameLayout.LayoutParams(
                WatchRoomUtils.dp(hostContext, 10),
                WatchRoomUtils.dp(hostContext, 10)
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
            wv.alpha = 0.01f
            rootView.addView(wv, 0, hiddenParams)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = snifferUa
            blockNetworkImage = true
            setSupportMultipleWindows(false)
        }

        val dispatchCapturedStream = { rawUrl: String, refererHeader: String, originHeader: String, uaHeader: String ->
            if (!streamCapturedForThisTarget && !isDestroyed && isLikelyDirectStreamUrl(rawUrl)) {
                streamCapturedForThisTarget = true
                val capturedSource = StreamSource(
                    label = target.label.ifBlank { "REYZO ${currentProviderIndex + 1}" },
                    url = rawUrl,
                    referer = refererHeader.ifBlank { target.referer.ifBlank { "https://vidlink.pro/" } },
                    origin = originHeader.ifBlank { target.origin },
                    userAgent = uaHeader.ifBlank { snifferUa },
                    isDirectStream = true
                )
                mainHandler.post {
                    cleanupHeadlessSniffer()
                    resolvedDirectStreams.add(0, capturedSource)
                    isExtractingNewMovie = false
                    onStreamResolved(capturedSource, currentStreamTitle, true)
                }
            }
        }

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onStreamCaptured(streamUrl: String, pageOrigin: String) {
                if (streamUrl.startsWith("http") && !streamUrl.contains("doubleclick")) {
                    dispatchCapturedStream(streamUrl, "$pageOrigin/", pageOrigin, snifferUa)
                }
            }
        }, "PrimeSnifferBridge")

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val reqTarget = request?.url?.toString() ?: return true
                val allowed = listOf("vidlink", "vixsrc", "vidsrc", "embed.su", "autoembed", "multiembed", "cloudnestra", "tmstr", "rabbitstream", "megacloud")
                return !allowed.any { reqTarget.contains(it, ignoreCase = true) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (streamCapturedForThisTarget || isDestroyed || url == "about:blank") return
                val autoClickJs = """
                    (function() {
                        var count = 0;
                        var timer = setInterval(function() {
                            count++;
                            if (count > 20) { clearInterval(timer); return; }
                            var btns = document.querySelectorAll('button, .play-btn, .jw-icon-display, .vjs-big-play-button, [aria-label="Play"], #play-button, .plyr__control--overlaid');
                            for (var i = 0; i < btns.length; i++) {
                                try { btns[i].click(); } catch(e) {}
                            }
                            var v = document.querySelector('video');
                            if (v) {
                                v.muted = true;
                                try { v.play(); } catch(e) {}
                                if (v.src && v.src.indexOf('http') === 0) {
                                    window.PrimeSnifferBridge.onStreamCaptured(v.src, window.location.origin);
                                    clearInterval(timer);
                                }
                            }
                        }, 300);
                    })();
                """.trimIndent()
                view?.evaluateJavascript(autoClickJs, null)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (streamCapturedForThisTarget) {
                    return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                val reqUrl = request?.url?.toString() ?: return null
                val lower = reqUrl.lowercase(Locale.ROOT)

                if (lower.contains("doubleclick") || lower.contains("popads") || lower.contains("adsterra") || lower.contains("googlesyndication") || lower.contains("analytics")) {
                    return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }

                if (isLikelyDirectStreamUrl(reqUrl)) {
                    val headers = request.requestHeaders
                    val ref = headers["Referer"] ?: headers["referer"] ?: target.referer.ifBlank { "https://vidlink.pro/" }
                    val orig = headers["Origin"] ?: headers["origin"] ?: target.origin
                    val ua = headers["User-Agent"] ?: headers["user-agent"] ?: snifferUa
                    dispatchCapturedStream(reqUrl, ref, orig, ua)
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        wv.onResume()
        wv.resumeTimers()
        val headers = if (target.referer.isNotEmpty()) mapOf("Referer" to target.referer) else emptyMap()
        wv.loadUrl(target.url, headers)

        snifferTimeoutRunnable = Runnable {
            if (!streamCapturedForThisTarget && !isDestroyed) {
                if (currentProviderIndex + 1 < currentProviderTargets.size) {
                    loadProviderInHeadlessSniffer(currentProviderIndex + 1, currentStreamTitle)
                } else {
                    isExtractingNewMovie = false
                    onBufferingChanged(false)
                }
            }
        }
        mainHandler.postDelayed(snifferTimeoutRunnable!!, 6500L)
    }

    fun cleanupHeadlessSniffer() {
        snifferTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        snifferTimeoutRunnable = null
        try {
            headlessSnifferWebView?.let { wv ->
                wv.stopLoading()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        } catch (_: Exception) {}
        headlessSnifferWebView = null
    }

    fun release() {
        isDestroyed = true
        snifferExtractionJob?.cancel()
        cleanupHeadlessSniffer()
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        fun extractOriginFromUrl(url: String): String {
            return try {
                val uri = Uri.parse(url)
                if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else ""
            } catch (_: Exception) {
                ""
            }
        }

        fun packUrlAndReferer(url: String, referer: String): String {
            val clean = url.substringBefore("|Referer=")
            return if (referer.isNotBlank()) "$clean|Referer=$referer" else clean
        }

        fun unpackUrlAndReferer(raw: String): Pair<String, String> {
            if (raw.contains("|Referer=")) {
                val parts = raw.split("|Referer=", limit = 2)
                return Pair(parts[0].trim(), parts.getOrNull(1)?.trim() ?: "")
            }
            return Pair(raw.trim(), "")
        }
    }
}