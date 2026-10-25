package com.primeott.app.data.repository

import android.net.Uri
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.domain.model.StreamSource
import com.primeott.app.domain.model.VodItem
import com.primeott.app.presentation.player.PrimePlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class SeriesEpisode(
    val episodeNumber: Int,
    val name: String,
    val overview: String,
    val stillUrl: String,
    val runtime: Int
)

object PrimeVodExtractor {

    private const val TMDB_API_KEY = "8476a7ab80ad76f0936744df0430e67c"
    private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w500"
    private const val TMDB_STILL_BASE = "https://image.tmdb.org/t/p/w300"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private enum class ContentProfile {
        TURKISH,
        KOREAN_ASIAN,
        ANIME,
        WESTERN_GLOBAL
    }

    private data class TmdbContentMeta(
        val imdbId: String,
        val originalLang: String,
        val originCountries: Set<String>,
        val profile: ContentProfile
    )

    data class SmartServerSelection(
        val servers: List<StreamSource>,
        val bestStartIndex: Int,
        val secondBestIndex: Int,
        val rankedWorkingIndices: List<Int>
    )

    private enum class ServerEngine {
        DIRECT_STREAM,
        VIXSRC,
        VIDEASY,
        VIDLINK,
        VIDFAST,
        AUTOEMBED,
        VIDSRC_CC,
        VIDSRC_ICU,
        MULTIEMBED,
        EMBEDSU,
        RIVESTREAM,
        GENERIC_EMBED
    }

    private data class ProbedServerResult(
        val originalIndex: Int,
        val source: StreamSource,
        val engine: ServerEngine,
        val isAlive: Boolean,
        val latencyMs: Long,
        val smartScore: Int
    )

    private val tmdbMetaCache = ConcurrentHashMap<String, TmdbContentMeta>()

    // مشاركة نفس CookieJar و ConnectionPool مع المشغل لتفادي خطأ 403 وتسريع فتح البث
    private val fastClient: OkHttpClient = PrimePlayerEngine.vodOkHttpClient.newBuilder()
        .connectTimeout(3200, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun httpGetFast(url: String, referer: String = "", customHeaders: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "*/*")
        if (referer.isNotEmpty()) {
            builder.header("Referer", referer)
            runCatching {
                val u = Uri.parse(referer)
                if (!u.scheme.isNullOrEmpty() && !u.host.isNullOrEmpty()) {
                    builder.header("Origin", "${u.scheme}://${u.host}")
                }
            }
        }
        customHeaders.forEach { (k, v) -> builder.header(k, v) }
        fastClient.newCall(builder.build()).execute().use { res ->
            if (!res.isSuccessful) return ""
            return res.body?.string() ?: ""
        }
    }

    /**
     * يتحقق بسرعة فائقة أن رابط البث المباشر (.m3u8 / .mp4) شغال فعلاً وليس صفحة خطأ 404
     * بدون إرسال Range header لملفات m3u8 الديناميكية حتى لا يرفضها السيرفر
     */
    private fun verifyDirectStreamAlive(source: StreamSource): Boolean {
        return try {
            val isM3u8 = source.url.contains(".m3u8", true) ||
                    source.url.contains("/playlist/", true) ||
                    source.url.contains("master.txt", true)
            val reqBuilder = Request.Builder()
                .url(source.url)
                .header("User-Agent", source.userAgent.ifBlank { BROWSER_UA })
            if (!isM3u8) {
                reqBuilder.header("Range", "bytes=0-1024")
            }
            if (source.referer.isNotBlank()) {
                reqBuilder.header("Referer", source.referer)
            }
            if (source.origin.isNotBlank()) {
                reqBuilder.header("Origin", source.origin)
            }
            fastClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code !in 200..299 && resp.code != 206) return false
                val ct = (resp.header("Content-Type") ?: "").lowercase(Locale.ROOT)
                if (ct.contains("text/html")) return false
                val peek = resp.body?.source()?.let { src ->
                    src.request(512)
                    src.buffer.clone().readUtf8(minOf(512L, src.buffer.size))
                }.orEmpty().trimStart()
                if (peek.startsWith("<!DOCTYPE", true) || peek.startsWith("<html", true)) {
                    return false
                }
                if (isM3u8) {
                    return peek.contains("#EXTM3U", ignoreCase = true)
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveTmdbContentMeta(cleanTmdbId: String, passedImdbId: String, isSeries: Boolean): TmdbContentMeta {
        val cacheKey = "${if (isSeries) "tv" else "movie"}_$cleanTmdbId"
        tmdbMetaCache[cacheKey]?.let { cached ->
            if (passedImdbId.isNotBlank() && cached.imdbId.isBlank()) {
                return cached.copy(imdbId = passedImdbId)
            }
            return cached
        }

        var resolvedImdb = passedImdbId.trim()
        var origLang = "en"
        val countries = mutableSetOf<String>()

        try {
            val typePath = if (isSeries) "tv" else "movie"
            val apiUrl = "https://api.themoviedb.org/3/$typePath/$cleanTmdbId?api_key=$TMDB_API_KEY&append_to_response=external_ids"
            val raw = httpGetFast(apiUrl)
            if (raw.isNotEmpty()) {
                val json = JSONObject(raw)
                origLang = json.optString("original_language", "en").lowercase(Locale.ROOT)
                if (resolvedImdb.isBlank()) {
                    resolvedImdb = json.optString("imdb_id", "").takeIf { it.startsWith("tt") }.orEmpty()
                }
                val extObj = json.optJSONObject("external_ids")
                if (resolvedImdb.isBlank() && extObj != null) {
                    resolvedImdb = extObj.optString("imdb_id", "").takeIf { it.startsWith("tt") }.orEmpty()
                }
                val cArr = json.optJSONArray("origin_country")
                if (cArr != null) {
                    for (i in 0 until cArr.length()) {
                        countries.add(cArr.optString(i, "").uppercase(Locale.ROOT))
                    }
                }
            }
        } catch (_: Exception) {}

        val profile = when {
            origLang == "tr" || "TR" in countries -> ContentProfile.TURKISH
            origLang in setOf("ko", "zh", "cn", "th", "vi", "tl", "id") ||
                    countries.any { it in setOf("KR", "CN", "TW", "HK", "TH") } -> ContentProfile.KOREAN_ASIAN
            origLang == "ja" || "JP" in countries -> ContentProfile.ANIME
            else -> ContentProfile.WESTERN_GLOBAL
        }

        return TmdbContentMeta(
            imdbId = resolvedImdb,
            originalLang = origLang,
            originCountries = countries,
            profile = profile
        ).also { tmdbMetaCache[cacheKey] = it }
    }

    private fun detectServerEngine(url: String, isDirect: Boolean): ServerEngine {
        if (isDirect) return ServerEngine.DIRECT_STREAM
        val lower = url.lowercase(Locale.ROOT)
        return when {
            lower.contains("vixsrc") -> ServerEngine.VIXSRC
            lower.contains("videasy") -> ServerEngine.VIDEASY
            lower.contains("vidlink") -> ServerEngine.VIDLINK
            lower.contains("vidfast") -> ServerEngine.VIDFAST
            lower.contains("autoembed") -> ServerEngine.AUTOEMBED
            lower.contains("vidsrc.cc") -> ServerEngine.VIDSRC_CC
            lower.contains("vidsrc") -> ServerEngine.VIDSRC_ICU
            lower.contains("multiembed") || lower.contains("superembed") -> ServerEngine.MULTIEMBED
            lower.contains("embed.su") -> ServerEngine.EMBEDSU
            lower.contains("rivestream") -> ServerEngine.RIVESTREAM
            else -> ServerEngine.GENERIC_EMBED
        }
    }

    /**
     * يحسب درجة توافق كل سيرفر مع نوع الفيلم/المسلسل (أجنبي، كوري، تركي، أنمي)
     */
    private fun computeEngineAffinityForProfile(engine: ServerEngine, profile: ContentProfile): Int {
        if (engine == ServerEngine.DIRECT_STREAM) return 1200
        return when (profile) {
            ContentProfile.WESTERN_GLOBAL -> when (engine) {
                ServerEngine.VIXSRC -> 1000
                ServerEngine.VIDLINK -> 920
                ServerEngine.VIDFAST -> 900
                ServerEngine.VIDEASY -> 880
                ServerEngine.VIDSRC_CC -> 840
                ServerEngine.AUTOEMBED -> 800
                ServerEngine.EMBEDSU -> 780
                ServerEngine.VIDSRC_ICU -> 760
                ServerEngine.MULTIEMBED -> 740
                ServerEngine.RIVESTREAM -> 720
                else -> 650
            }
            ContentProfile.KOREAN_ASIAN -> when (engine) {
                ServerEngine.VIDEASY -> 980
                ServerEngine.VIDLINK -> 950
                ServerEngine.VIDSRC_CC -> 920
                ServerEngine.EMBEDSU -> 890
                ServerEngine.VIDFAST -> 860
                ServerEngine.RIVESTREAM -> 840
                ServerEngine.AUTOEMBED -> 810
                ServerEngine.MULTIEMBED -> 790
                ServerEngine.VIDSRC_ICU -> 760
                // VixSrc يأخذ أولوية عالية فقط إذا وُجد فيه العمل فعلاً (يتحول إلى DIRECT_STREAM)، وإلا يُستبعد
                ServerEngine.VIXSRC -> -500
                else -> 650
            }
            ContentProfile.TURKISH -> when (engine) {
                ServerEngine.AUTOEMBED -> 980
                ServerEngine.VIDEASY -> 950
                ServerEngine.MULTIEMBED -> 930
                ServerEngine.VIDLINK -> 900
                ServerEngine.VIDFAST -> 870
                ServerEngine.VIDSRC_ICU -> 840
                ServerEngine.VIDSRC_CC -> 810
                ServerEngine.RIVESTREAM -> 790
                ServerEngine.EMBEDSU -> 760
                ServerEngine.VIXSRC -> -500
                else -> 650
            }
            ContentProfile.ANIME -> when (engine) {
                ServerEngine.VIDLINK -> 980
                ServerEngine.VIDEASY -> 950
                ServerEngine.VIDSRC_CC -> 930
                ServerEngine.EMBEDSU -> 900
                ServerEngine.VIDFAST -> 860
                ServerEngine.AUTOEMBED -> 820
                ServerEngine.VIXSRC -> -300
                else -> 650
            }
        }
    }

    /**
     * يحافظ على سيرفرات الـ Panel بأسمائها وترتيبها الأصلي (REYZO 1, REYZO 2...)،
     * وإذا كان أحد السيرفرات في الـ Panel مكرراً أو معطلاً أو تنقصه محركات الكوري/التركي يرقيه تلقائياً
     */
    private fun enrichPanelServersPreservingOrder(
        panelServers: List<StreamSource>,
        cleanTmdbId: String,
        meta: TmdbContentMeta,
        isSeries: Boolean,
        season: Int,
        episode: Int
    ): List<StreamSource> {
        val imdb = meta.imdbId
        val targetId = imdb.ifBlank { cleanTmdbId }

        val fallbackEnginePool = listOf(
            "https://vixsrc.to".let { host ->
                if (isSeries) "$host/tv/$cleanTmdbId/$season/$episode" else "$host/movie/$cleanTmdbId"
            },
            "https://vidlink.pro".let { host ->
                if (isSeries) "$host/tv/$cleanTmdbId/$season/$episode?autoplay=true" else "$host/movie/$cleanTmdbId?autoplay=true"
            },
            "https://player.videasy.net".let { host ->
                if (isSeries) "$host/tv/$cleanTmdbId/$season/$episode?autoPlay=true" else "$host/movie/$cleanTmdbId?autoPlay=true"
            },
            "https://vidfast.pro".let { host ->
                if (isSeries) "$host/tv/$targetId/$season/$episode?autoPlay=true&theme=E50914&sub=ar" else "$host/movie/$targetId?autoPlay=true&theme=E50914&sub=ar"
            },
            "https://player.autoembed.cc".let { host ->
                if (isSeries) "$host/embed/tv/$cleanTmdbId/$season/$episode?autoplay=true" else "$host/embed/movie/$cleanTmdbId?autoplay=true"
            },
            "https://vidsrc.cc".let { host ->
                if (isSeries) "$host/v2/embed/tv/$cleanTmdbId/$season/$episode?autoPlay=true" else "$host/v2/embed/movie/$cleanTmdbId?autoPlay=true"
            },
            "https://multiembed.mov".let { host ->
                if (isSeries) {
                    if (imdb.isNotBlank()) "$host/directstream.php?video_id=$imdb&s=$season&e=$episode"
                    else "$host/directstream.php?video_id=$cleanTmdbId&tmdb=1&s=$season&e=$episode"
                } else {
                    if (imdb.isNotBlank()) "$host/directstream.php?video_id=$imdb"
                    else "$host/directstream.php?video_id=$cleanTmdbId&tmdb=1"
                }
            },
            "https://embed.su".let { host ->
                if (isSeries) "$host/embed/tv/$cleanTmdbId/$season/$episode" else "$host/embed/movie/$cleanTmdbId"
            }
        )

        if (panelServers.isEmpty()) {
            return fallbackEnginePool.mapIndexed { idx, u ->
                val origin = runCatching { val p = Uri.parse(u); "${p.scheme}://${p.host}" }.getOrDefault("https://vidlink.pro")
                StreamSource(
                    label = "REYZO ${idx + 1}",
                    url = u,
                    referer = "$origin/",
                    origin = origin,
                    userAgent = BROWSER_UA,
                    isDirectStream = false
                )
            }
        }

        val usedUrls = HashSet<String>()
        var poolCursor = 0

        fun nextUnusedFallbackUrl(): String {
            while (poolCursor < fallbackEnginePool.size) {
                val candidate = fallbackEnginePool[poolCursor++]
                if (usedUrls.add(candidate)) return candidate
            }
            return fallbackEnginePool.first()
        }

        return panelServers.mapIndexed { idx, srv ->
            val cleanLabel = srv.label.trim().ifEmpty { "REYZO ${idx + 1}" }
            val isBrokenOrPromo = srv.url.isBlank() ||
                    srv.url.contains("netnaija", ignoreCase = true) ||
                    !usedUrls.add(srv.url)

            if (srv.isDirectStream && !isBrokenOrPromo) {
                srv.copy(label = cleanLabel)
            } else if (isBrokenOrPromo) {
                val replacementUrl = nextUnusedFallbackUrl()
                val origin = runCatching {
                    val p = Uri.parse(replacementUrl)
                    "${p.scheme}://${p.host}"
                }.getOrDefault("https://vidlink.pro")
                srv.copy(
                    label = cleanLabel,
                    url = replacementUrl,
                    referer = "$origin/",
                    origin = origin,
                    userAgent = BROWSER_UA,
                    isDirectStream = false
                )
            } else {
                val origin = srv.origin.ifBlank {
                    runCatching {
                        val p = Uri.parse(srv.url)
                        "${p.scheme}://${p.host}"
                    }.getOrDefault("")
                }
                srv.copy(
                    label = cleanLabel,
                    referer = srv.referer.ifBlank { if (origin.isNotEmpty()) "$origin/" else "" },
                    origin = origin,
                    userAgent = srv.userAgent.ifBlank { BROWSER_UA }
                )
            }
        }
    }

    /**
     * يفحص بسرعة ما إذا كان سيرفر الـ Embed حيّاً ويقيس سرعة استجابته بالمللي ثانية
     */
    private fun quickProbeEmbedAliveAndLatency(source: StreamSource): Pair<Boolean, Long> {
        val t0 = System.currentTimeMillis()
        return try {
            val reqBuilder = Request.Builder()
                .url(source.url)
                .header("User-Agent", source.userAgent.ifBlank { BROWSER_UA })
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            if (source.referer.isNotBlank()) {
                reqBuilder.header("Referer", source.referer)
            }
            fastClient.newCall(reqBuilder.build()).execute().use { resp ->
                val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                if (resp.code in listOf(404, 410, 500, 502, 503, 520, 521, 522)) {
                    return false to elapsed
                }
                val sample = resp.body?.source()?.let { src ->
                    src.request(1400)
                    src.buffer.clone().readUtf8(minOf(1400L, src.buffer.size))
                }.orEmpty().lowercase(Locale.ROOT)

                val isDeadMsg = sample.length in 1..600 && (
                        sample.contains("404 not found") ||
                                sample.contains("video not found") ||
                                sample.contains("media is unavailable") ||
                                sample.contains("no sources found") ||
                                sample.contains("file was deleted")
                        )
                (!isDeadMsg) to elapsed
            }
        } catch (_: Exception) {
            false to 3500L
        }
    }

    suspend fun fetchActorCredits(actorId: String): List<VodItem> = withContext(Dispatchers.IO) {
        val list = ArrayList<VodItem>()
        try {
            val url = "https://api.themoviedb.org/3/person/$actorId/combined_credits?api_key=$TMDB_API_KEY&language=en-US"
            val root = JSONObject(httpGetFast(url))
            val castArr = root.optJSONArray("cast") ?: JSONArray()
            val seen = HashSet<String>()

            for (i in 0 until castArr.length()) {
                val obj = castArr.optJSONObject(i) ?: continue
                val id = obj.optInt("id", 0).toString()
                if (id == "0" || seen.contains(id)) continue
                val posterPath = obj.optString("poster_path", "")
                if (posterPath.isEmpty() || posterPath == "null") continue
                if (obj.optInt("vote_count", 0) < 25) continue

                val isTv = obj.optString("media_type", "movie") == "tv"
                val date = if (isTv) obj.optString("first_air_date", "") else obj.optString("release_date", "")
                if (date.isEmpty() || date > "2026-12-31") continue

                seen.add(id)
                val title = if (isTv) {
                    obj.optString("name").ifEmpty { obj.optString("original_name", "Series") }
                } else {
                    obj.optString("title").ifEmpty { obj.optString("original_title", "Movie") }
                }
                val rating = String.format(Locale.US, "%.1f", obj.optDouble("vote_average", 7.4))
                val year = if (date.length >= 4) date.take(4) else "2024"
                val overview = obj.optString("overview", "")

                list.add(
                    VodItem(
                        id = id,
                        title = title,
                        category = if (isTv) "Series" else "Movie",
                        rating = rating,
                        year = year,
                        posterUrl = "$TMDB_POSTER_BASE$posterPath",
                        streamUrls = emptyList(),
                        isSeries = isTv,
                        description = overview
                    )
                )
                if (list.size >= 35) break
            }
        } catch (_: Exception) {}
        return@withContext list
    }

    suspend fun fetchSeasonEpisodes(seriesTmdbId: String, seasonNumber: Int): List<SeriesEpisode> = withContext(Dispatchers.IO) {
        val list = ArrayList<SeriesEpisode>()
        try {
            val cleanId = seriesTmdbId.filter { it.isDigit() }.ifEmpty { seriesTmdbId }
            val url = "https://api.themoviedb.org/3/tv/$cleanId/season/$seasonNumber?api_key=$TMDB_API_KEY&language=en-US"
            val root = JSONObject(httpGetFast(url))
            val epArr = root.optJSONArray("episodes") ?: JSONArray()
            for (i in 0 until epArr.length()) {
                val e = epArr.optJSONObject(i) ?: continue
                val epNum = e.optInt("episode_number", i + 1)
                val name = e.optString("name", "Episode $epNum")
                val overview = e.optString("overview", "")
                val stillPath = e.optString("still_path", "")
                val stillUrl = if (stillPath.isNotEmpty() && stillPath != "null") "$TMDB_STILL_BASE$stillPath" else ""
                val runtime = e.optInt("runtime", 45)
                list.add(SeriesEpisode(epNum, name, overview, stillUrl, runtime))
            }
        } catch (_: Exception) {}
        return@withContext list
    }

    /**
     * يصحح روابط السيرفرات المبرمجة في الـ Panel
     */
    private fun normalizePanelServerUrl(
        rawUrl: String,
        cleanTmdbId: String,
        imdbId: String,
        isSeries: Boolean,
        season: Int,
        episode: Int
    ): String {
        var u = rawUrl.trim()
        if (u.isEmpty()) return u

        // تحديث دومين vidfast القديم إذا كان موجوداً في اللوحة
        if (u.contains("vidfast.vc", ignoreCase = true) || u.contains("vidfast.to", ignoreCase = true)) {
            u = u.replace("vidfast.vc", "vidfast.pro", ignoreCase = true)
                .replace("vidfast.to", "vidfast.pro", ignoreCase = true)
        }

        val targetId = imdbId.ifBlank { cleanTmdbId }
        val lower = u.lowercase(Locale.ROOT)
        return when {
            lower.contains("vixsrc.to") -> {
                if (isSeries) "https://vixsrc.to/tv/$cleanTmdbId/$season/$episode"
                else "https://vixsrc.to/movie/$cleanTmdbId"
            }
            lower.contains("videasy.net") -> {
                if (isSeries) "https://player.videasy.net/tv/$cleanTmdbId/$season/$episode?autoPlay=true"
                else "https://player.videasy.net/movie/$cleanTmdbId?autoPlay=true"
            }
            lower.contains("vidlink.pro") -> {
                if (isSeries) "https://vidlink.pro/tv/$cleanTmdbId/$season/$episode?autoplay=true"
                else "https://vidlink.pro/movie/$cleanTmdbId?autoplay=true"
            }
            lower.contains("vidfast.pro") -> {
                if (isSeries) "https://vidfast.pro/tv/$targetId/$season/$episode?autoPlay=true&theme=E50914&sub=ar"
                else "https://vidfast.pro/movie/$targetId?autoPlay=true&theme=E50914&sub=ar"
            }
            lower.contains("vidsrc.cc") -> {
                if (isSeries) "https://vidsrc.cc/v2/embed/tv/$cleanTmdbId/$season/$episode?autoPlay=true"
                else "https://vidsrc.cc/v2/embed/movie/$cleanTmdbId?autoPlay=true"
            }
            (lower.contains("vidsrc.to") || lower.contains("vidsrc.icu") || lower.contains("vidsrc.pro") || lower.contains("vidsrc.xyz")) -> {
                val host = runCatching {
                    val parsed = Uri.parse(u)
                    "${parsed.scheme}://${parsed.host}"
                }.getOrDefault("https://vidsrc.icu")
                if (isSeries) "$host/embed/tv/$targetId/$season/$episode"
                else "$host/embed/movie/$targetId"
            }
            lower.contains("embed.su") -> {
                if (isSeries) "https://embed.su/embed/tv/$cleanTmdbId/$season/$episode"
                else "https://embed.su/embed/movie/$cleanTmdbId"
            }
            lower.contains("autoembed.cc") -> {
                if (isSeries) "https://player.autoembed.cc/embed/tv/$cleanTmdbId/$season/$episode?autoplay=true"
                else "https://player.autoembed.cc/embed/movie/$cleanTmdbId?autoplay=true"
            }
            lower.contains("multiembed.mov") || lower.contains("superembed") -> {
                if (isSeries) {
                    if (imdbId.isNotBlank()) "https://multiembed.mov/directstream.php?video_id=$imdbId&s=$season&e=$episode"
                    else "https://multiembed.mov/directstream.php?video_id=$cleanTmdbId&tmdb=1&s=$season&e=$episode"
                } else {
                    if (imdbId.isNotBlank()) "https://multiembed.mov/directstream.php?video_id=$imdbId"
                    else "https://multiembed.mov/directstream.php?video_id=$cleanTmdbId&tmdb=1"
                }
            }
            lower.contains("rivestream.org") -> {
                if (isSeries) "https://rivestream.org/embed?type=tv&id=$cleanTmdbId&season=$season&episode=$episode"
                else "https://rivestream.org/embed?type=movie&id=$cleanTmdbId"
            }
            else -> u
        }
    }

    /**
     * يحلل سيرفرات الـ Panel في نفس اللحظة بالتوازي، ويكتشف تلقائياً نوع العمل (أجنبي، كوري، تركي، أنمي)
     * ويختار وحده السيرفر الشغال والأسرع مباشرة دون التجريب بالواحد
     */
    suspend fun fetchSmartPanelSelection(
        tmdbId: String,
        imdbId: String = "",
        title: String = "",
        isSeries: Boolean = false,
        season: Int = 1,
        episode: Int = 1,
        onFastestDirectReady: (Int, StreamSource) -> Unit = { _, _ -> }
    ): SmartServerSelection = coroutineScope {
        val cleanTmdbId = tmdbId
            .replace("tmdb_movie_", "")
            .replace("tmdb_tv_", "")
            .replace("tmdb_", "")
            .trim()

        val metaDeferred = async(Dispatchers.IO) {
            resolveTmdbContentMeta(cleanTmdbId, imdbId, isSeries)
        }

        val rawPanelDeferred = async(Dispatchers.IO) {
            val list = ArrayList<StreamSource>()
            try {
                val typeStr = if (isSeries) "tv" else "movie"
                val encTitle = URLEncoder.encode(title, "UTF-8")
                val apiUrl = "${SessionManager.CLOUDFLARE_API_URL}/api/v2/vod-stream?tmdb_id=$cleanTmdbId&imdb_id=$imdbId&type=$typeStr&s=$season&e=$episode&title=$encTitle"
                val rawJson = httpGetFast(apiUrl)
                if (rawJson.isNotEmpty()) {
                    val root = JSONObject(rawJson)
                    val arr = root.optJSONArray("streams") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val rawUrl = o.optString("url", "").trim()
                        if (rawUrl.isEmpty()) continue
                        val srvName = o.optString("server_name", o.optString("label", "REYZO ${i + 1}"))
                        list.add(
                            StreamSource(
                                label = srvName,
                                url = rawUrl,
                                referer = o.optString("referer", ""),
                                origin = o.optString("origin", ""),
                                userAgent = o.optString("user_agent", BROWSER_UA),
                                isDirectStream = o.optBoolean("is_direct", false)
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
            list
        }

        val meta = metaDeferred.await()
        val normalizedPanelList = rawPanelDeferred.await().map { srv ->
            srv.copy(
                url = normalizePanelServerUrl(
                    rawUrl = srv.url,
                    cleanTmdbId = cleanTmdbId,
                    imdbId = meta.imdbId,
                    isSeries = isSeries,
                    season = season,
                    episode = episode
                )
            )
        }

        val enrichedPanelServers = enrichPanelServersPreservingOrder(
            panelServers = normalizedPanelList,
            cleanTmdbId = cleanTmdbId,
            meta = meta,
            isSeries = isSeries,
            season = season,
            episode = episode
        )

        if (enrichedPanelServers.isEmpty()) {
            return@coroutineScope SmartServerSelection(emptyList(), 0, 0, emptyList())
        }

        val fastestDispatched = AtomicBoolean(false)

        // فحص متوازي لجميع سيرفرات الـ Panel في نفس اللحظة لاختيار السيرفر الشغال والأسرع حسب لغة الفيلم/المسلسل
        val probedResults = enrichedPanelServers.mapIndexed { idx, srv ->
            async(Dispatchers.IO) {
                val initialEngine = detectServerEngine(srv.url, srv.isDirectStream)
                val t0 = System.currentTimeMillis()

                if (srv.isDirectStream) {
                    val alive = withTimeoutOrNull(1600L) { verifyDirectStreamAlive(srv) } ?: true
                    val latency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    if (alive && fastestDispatched.compareAndSet(false, true)) {
                        onFastestDirectReady(idx, srv)
                    }
                    val score = if (alive) 2000 - (latency / 10).toInt() else -1000
                    return@async ProbedServerResult(idx, srv, ServerEngine.DIRECT_STREAM, alive, latency, score)
                }

                val isFastHtmlExtractor = initialEngine == ServerEngine.VIXSRC ||
                        initialEngine == ServerEngine.VIDSRC_ICU ||
                        initialEngine == ServerEngine.MULTIEMBED

                if (isFastHtmlExtractor) {
                    val extracted = withTimeoutOrNull(1700L) {
                        tryExtractM3u8FromAdminServer(srv)
                    }
                    val latency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    if (extracted != null) {
                        if (fastestDispatched.compareAndSet(false, true)) {
                            onFastestDirectReady(idx, extracted)
                        }
                        val score = 1850 - (latency / 10).toInt()
                        return@async ProbedServerResult(idx, extracted, ServerEngine.DIRECT_STREAM, true, latency, score)
                    }
                    // إذا كان VixSrc ولم يرجع ملف البث (مثلاً مسلسل تركي أو كوري غير متوفر فيه)، نستبعده فوراً
                    if (initialEngine == ServerEngine.VIXSRC) {
                        return@async ProbedServerResult(idx, srv, initialEngine, false, latency, -1000)
                    }
                }

                val baseAffinity = computeEngineAffinityForProfile(initialEngine, meta.profile)
                val (alive, latency) = withTimeoutOrNull(1350L) {
                    quickProbeEmbedAliveAndLatency(srv)
                } ?: (true to 1350L)

                val speedBonus = ((1500L - latency.coerceAtMost(1500L)) / 8L).toInt()
                val finalScore = if (alive) baseAffinity + speedBonus else -800
                ProbedServerResult(idx, srv, initialEngine, alive, latency, finalScore)
            }
        }.awaitAll()

        val finalServersInPanelOrder = probedResults
            .sortedBy { it.originalIndex }
            .map { it.source }

        val rankedAlive = probedResults
            .filter { it.isAlive && it.smartScore > 0 }
            .sortedByDescending { it.smartScore }
            .map { it.originalIndex }

        val finalRankedIndices = if (rankedAlive.isNotEmpty()) {
            rankedAlive + probedResults.map { it.originalIndex }.filter { it !in rankedAlive }
        } else {
            probedResults.sortedByDescending { it.smartScore }.map { it.originalIndex }
        }

        val bestIdx = finalRankedIndices.firstOrNull() ?: 0
        val secondIdx = finalRankedIndices.getOrNull(1) ?: bestIdx

        SmartServerSelection(
            servers = finalServersInPanelOrder,
            bestStartIndex = bestIdx,
            secondBestIndex = secondIdx,
            rankedWorkingIndices = finalRankedIndices
        )
    }

    suspend fun fetchPanelConfiguredServers(
        tmdbId: String,
        imdbId: String = "",
        title: String = "",
        isSeries: Boolean = false,
        season: Int = 1,
        episode: Int = 1,
        onFastestDirectReady: (StreamSource) -> Unit = {}
    ): List<StreamSource> {
        val selection = fetchSmartPanelSelection(
            tmdbId = tmdbId,
            imdbId = imdbId,
            title = title,
            isSeries = isSeries,
            season = season,
            episode = episode,
            onFastestDirectReady = { _, src -> onFastestDirectReady(src) }
        )
        return selection.servers
    }

    private fun tryExtractM3u8FromAdminServer(srv: StreamSource): StreamSource? {
        if (srv.url.contains("netnaija", ignoreCase = true)) {
            return null
        }
        try {
            val html = httpGetFast(srv.url, srv.referer)
            if (html.isEmpty()) return null

            val lowerHtml = html.lowercase(Locale.ROOT)
            if ((lowerHtml.contains("not found") || lowerHtml.contains("404")) && lowerHtml.length < 1800) {
                return null
            }

            // 1. فحص VixSrc Master Playlist الفوري (بدون طلب تحقق ثاني لتفادي التأخير و b=1)
            val urlMatch = Regex("""['"]?(https?://[^'"\s<>]+/playlist/\d+[^'"\s<>]*)['"]?""").find(html)
            val tokenMatch = Regex("""['"]?token['"]?\s*:\s*['"]([^'"]+)['"]""").find(html)
            val expiresMatch = Regex("""['"]?expires['"]?\s*:\s*['"]([^'"]+)['"]""").find(html)
            if (urlMatch != null && tokenMatch != null && expiresMatch != null) {
                val basePlaylist = urlMatch.groupValues[1]
                    .replace("?b=1&", "?")
                    .replace("&b=1", "")
                    .replace("?b=1", "")
                val sep = if (basePlaylist.contains("?")) "&" else "?"
                val finalM3u8 = "${basePlaylist}${sep}token=${tokenMatch.groupValues[1]}&expires=${expiresMatch.groupValues[1]}&h=1"
                return srv.copy(
                    url = finalM3u8,
                    referer = srv.url,
                    origin = "https://vixsrc.to",
                    userAgent = BROWSER_UA,
                    isDirectStream = true
                )
            }

            // 2. فحص روابط .m3u8 المباشرة الصالحة بدون placeholders
            val m3u8Regex = Regex("""https?://[^\s"'<>\\{}]+?\.m3u8[^\s"'<>\\{}]*""", RegexOption.IGNORE_CASE)
            m3u8Regex.findAll(html).forEach { m ->
                val link = m.value.replace("\\/", "/")
                if (!link.contains("promo", true) &&
                    !link.contains("trailer", true) &&
                    !link.contains("example.com", true)
                ) {
                    val hostOrigin = runCatching {
                        val u = Uri.parse(srv.url)
                        "${u.scheme}://${u.host}"
                    }.getOrDefault(srv.origin)
                    val candidate = srv.copy(
                        url = link,
                        referer = srv.referer.ifBlank { srv.url },
                        origin = srv.origin.ifBlank { hostOrigin },
                        userAgent = BROWSER_UA,
                        isDirectStream = true
                    )
                    if (verifyDirectStreamAlive(candidate)) {
                        return candidate
                    }
                }
            }

            // 3. فحص سلسلة VidSrc RCP -> ProRCP / SrcRCP
            val iframeRegex = Regex("""<iframe[^>]+src=['"]((?:https?:)?//[^'"]+/(?:rcp|embed)/[^'"]+)['"]""", RegexOption.IGNORE_CASE)
            val proRcpRegex = Regex("""['"](/(?:prorcp|srcrcp)/[A-Za-z0-9+/=_-]+)['"]""")
            val iframeMatch = iframeRegex.find(html)
            if (iframeMatch != null) {
                var rcpUrl = iframeMatch.groupValues[1]
                if (rcpUrl.startsWith("//")) rcpUrl = "https:$rcpUrl"
                val rcpHost = runCatching {
                    val u = Uri.parse(rcpUrl)
                    "${u.scheme}://${u.host}"
                }.getOrDefault("https://cloudnestra.com")
                val html2 = httpGetFast(rcpUrl, srv.url)
                m3u8Regex.find(html2)?.let { m ->
                    val candidate = srv.copy(
                        url = m.value.replace("\\/", "/"),
                        referer = "$rcpHost/",
                        origin = rcpHost,
                        userAgent = BROWSER_UA,
                        isDirectStream = true
                    )
                    if (verifyDirectStreamAlive(candidate)) return candidate
                }
                val proMatch = proRcpRegex.find(html2)
                if (proMatch != null) {
                    val html3 = httpGetFast("$rcpHost${proMatch.groupValues[1]}", rcpUrl)
                    m3u8Regex.find(html3)?.let { m ->
                        val link = m.value.replace("\\/", "/")
                        if (!link.contains("example.com")) {
                            val candidate = srv.copy(
                                url = link,
                                referer = "$rcpHost/",
                                origin = rcpHost,
                                userAgent = BROWSER_UA,
                                isDirectStream = true
                            )
                            if (verifyDirectStreamAlive(candidate)) return candidate
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}