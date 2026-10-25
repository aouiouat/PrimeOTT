package com.primeott.app.presentation.watch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import com.primeott.app.data.repository.PrimeVodExtractor
import com.primeott.app.domain.model.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
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

    private val adBlockKeywords = listOf(
        "doubleclick", "googlesyndication", "popads", "popcash", "adsterra",
        "propellerads", "exoclick", "juicyads", "clickadu", "adnxs", "taboola",
        "outbrain", "histats", "google-analytics"
    )

    fun isLikelyDirectStreamUrl(rawUrl: String): Boolean {
        val lower = rawUrl.lowercase(Locale.ROOT)
        if (adBlockKeywords.any { lower.contains(it) }) return false
        if (lower.contains("netnaija.film/wp-content") || lower.contains("promo") || lower.contains("trailer")) {
            return false
        }
        val cleanPath = lower.substringBefore("?").substringBefore("#")
        if (cleanPath.endsWith(".js") || cleanPath.endsWith(".css") || cleanPath.endsWith(".png") ||
            cleanPath.endsWith(".jpg") || cleanPath.endsWith(".jpeg") || cleanPath.endsWith(".webp") ||
            cleanPath.endsWith(".svg") || cleanPath.endsWith(".gif") || cleanPath.endsWith(".woff") ||
            cleanPath.endsWith(".woff2") || cleanPath.endsWith(".ttf") || cleanPath.endsWith(".ico") ||
            cleanPath.endsWith(".json") || cleanPath.endsWith(".xml") || cleanPath.endsWith(".html") ||
            cleanPath.endsWith(".htm") || cleanPath.endsWith(".php") || cleanPath.endsWith(".vtt") ||
            cleanPath.endsWith(".srt") || cleanPath.endsWith(".ass") || cleanPath.endsWith(".wasm") ||
            cleanPath.endsWith(".ts")
        ) return false

        return cleanPath.endsWith(".m3u8") ||
                lower.contains(".m3u8?") ||
                cleanPath.endsWith(".mp4") ||
                lower.contains(".mp4?") ||
                Regex(".*/playlist/\\d+.*").containsMatchIn(lower) ||
                lower.contains("master.txt") ||
                lower.contains("index.txt") ||
                lower.contains("m3u8-proxy") ||
                lower.contains("mime=video") ||
                cleanPath.endsWith(".mpd")
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

        var fastDispatched = false

        snifferExtractionJob = scope.launch {
            val servers = withContext(Dispatchers.IO) {
                runCatching {
                    PrimeVodExtractor.fetchPanelConfiguredServers(
                        tmdbId = cleanTmdbId,
                        imdbId = "",
                        title = media.title,
                        isSeries = false,
                        season = 1,
                        episode = 1,
                        onFastestDirectReady = { fastestSrv ->
                            if (!fastDispatched && !isDestroyed) {
                                fastDispatched = true
                                mainHandler.post {
                                    val directCopy = fastestSrv.copy(isDirectStream = true)
                                    resolvedDirectStreams.add(0, directCopy)
                                    isExtractingNewMovie = false
                                    onStatusUpdate("  ${directCopy.label.ifBlank { "REYZO 1" }}")
                                    onStreamResolved(directCopy, media.title, true)
                                }
                            }
                        }
                    )
                }.getOrDefault(emptyList())
            }

            currentProviderTargets.clear()
            currentProviderTargets.addAll(servers)

            if (servers.isEmpty()) {
                if (!fastDispatched) {
                    isExtractingNewMovie = false
                    onBufferingChanged(false)
                    Toast.makeText(hostContext, "⚠️ لا توجد سيرفرات مفعلة لهذا الفيلم حالياً", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            if (fastDispatched) {
                val activeUrl = resolvedDirectStreams.firstOrNull()?.url
                val idx = servers.indexOfFirst { it.url == activeUrl }
                if (idx != -1) currentProviderIndex = idx
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

            val firstIdx = if (servers.size > 1 &&
                servers[0].url.contains("vixsrc.to", ignoreCase = true) &&
                !servers[0].isDirectStream
            ) 1 else 0
            loadProviderInHeadlessSniffer(firstIdx, media.title)
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

        val skipToNextProviderNow = {
            if (!streamCapturedForThisTarget && !isDestroyed) {
                mainHandler.post {
                    val nextIdx = currentProviderTargets.indices.firstOrNull { it !in triedProviderIndices }
                    if (nextIdx != null) {
                        loadProviderInHeadlessSniffer(nextIdx, currentStreamTitle)
                    } else {
                        isExtractingNewMovie = false
                        onBufferingChanged(false)
                    }
                }
            }
        }

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onStreamCaptured(streamUrl: String, pageOrigin: String) {
                if (streamUrl.startsWith("http") && isLikelyDirectStreamUrl(streamUrl)) {
                    val validOrigin = if (pageOrigin.startsWith("http", ignoreCase = true)) pageOrigin else target.origin
                    val validRef = if (validOrigin.isNotEmpty()) "$validOrigin/" else target.referer
                    dispatchCapturedStream(streamUrl, validRef, validOrigin, snifferUa)
                }
            }
        }, "PrimeSnifferBridge")

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val reqUrl = request?.url?.toString() ?: return true
                val lower = reqUrl.lowercase(Locale.ROOT)
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) return true
                if (adBlockKeywords.any { lower.contains(it) }) return true

                if (request.isForMainFrame == false) {
                    return false
                }

                val targetHost = runCatching { Uri.parse(target.url).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
                val reqHost = runCatching { request.url.host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
                if (targetHost.isNotEmpty() && (reqHost == targetHost || reqHost.endsWith(".$targetHost") || targetHost.endsWith(".$reqHost"))) {
                    return false
                }
                val allowedMainHosts = listOf(
                    "vixsrc", "vidfast", "vidlink", "vidsrc", "2embed", "multiembed",
                    "superembed", "autoembed", "embed.su", "cloudnestra", "streambucket"
                )
                if (allowedMainHosts.any { reqHost.contains(it) }) {
                    return false
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (streamCapturedForThisTarget || isDestroyed || url == "about:blank") return
                val autoClickJs = """
                    (function() {
                        var count = 0;
                        var timer = setInterval(function() {
                            count++;
                            if (count > 12) { clearInterval(timer); return; }
                            var btns = document.querySelectorAll('.play-btn, .jw-icon-display, .vjs-big-play-button, [aria-label="Play"], #play-button, .plyr__control--overlaid');
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
                        }, 350);
                    })();
                """.trimIndent()
                view?.evaluateJavascript(autoClickJs, null)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (streamCapturedForThisTarget) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                val reqUrl = request?.url?.toString() ?: return null
                val lower = reqUrl.lowercase(Locale.ROOT)

                if (adBlockKeywords.any { lower.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
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
            skipToNextProviderNow()
        }
        mainHandler.postDelayed(snifferTimeoutRunnable!!, 8000L)
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