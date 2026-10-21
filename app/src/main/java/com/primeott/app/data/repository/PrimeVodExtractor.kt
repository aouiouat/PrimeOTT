package com.primeott.app.data.repository

import com.primeott.app.data.remote.SessionManager
import com.primeott.app.domain.model.StreamSource
import com.primeott.app.domain.model.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SeriesEpisode(
    val episodeNumber: Int,
    val name: String,
    val overview: String,
    val stillUrl: String,
    val runtime: Int
)

object PrimeVodExtractor {

    private const val TMDB_API_KEY = "8265bd1679663a7ea12ac168da84d2e8"
    private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w500"
    private const val TMDB_STILL_BASE = "https://image.tmdb.org/t/p/w300"
    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val fastClient: OkHttpClient = OttRepository.okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private fun httpGetFast(url: String, referer: String = ""): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "*/*")
        if (referer.isNotEmpty()) builder.header("Referer", referer)
        fastClient.newCall(builder.build()).execute().use { res ->
            return res.body?.string() ?: ""
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
                if (date.isEmpty() || date > "2025-12-31") continue

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
        } catch (e: Exception) {}
        return@withContext list
    }

    suspend fun fetchSeasonEpisodes(seriesTmdbId: String, seasonNumber: Int): List<SeriesEpisode> = withContext(Dispatchers.IO) {
        val list = ArrayList<SeriesEpisode>()
        try {
            val url = "https://api.themoviedb.org/3/tv/$seriesTmdbId/season/$seasonNumber?api_key=$TMDB_API_KEY&language=en-US"
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
        } catch (e: Exception) {}
        return@withContext list
    }

    /**
     * يجلب فقط وحصرياً السيرفرات المفعلة في لوحة التحكم (/api/v2/vod-stream)
     * ويفحصها بالتوازي ليختار أسرع سيرفر شغال بينها مع الاحتفاظ بالاسم الذي اختاره الأدمن!
     */
    suspend fun fetchPanelConfiguredServers(
        tmdbId: String,
        imdbId: String = "",
        title: String = "",
        isSeries: Boolean = false,
        season: Int = 1,
        episode: Int = 1,
        onFastestDirectReady: (StreamSource) -> Unit = {}
    ): List<StreamSource> = coroutineScope {
        val panelServers = withContext(Dispatchers.IO) {
            val list = ArrayList<StreamSource>()
            try {
                val typeStr = if (isSeries) "tv" else "movie"
                val encTitle = URLEncoder.encode(title, "UTF-8")
                val apiUrl = "${SessionManager.CLOUDFLARE_API_URL}/api/v2/vod-stream?tmdb_id=$tmdbId&imdb_id=$imdbId&type=$typeStr&s=$season&e=$episode&title=$encTitle"
                val root = JSONObject(httpGetFast(apiUrl))
                val arr = root.optJSONArray("streams") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val url = o.optString("url", "")
                    if (url.isEmpty()) continue
                    val srvName = o.optString("server_name", o.optString("label", "Prime Server"))
                    list.add(
                        StreamSource(
                            label = srvName,
                            url = url,
                            referer = o.optString("referer", ""),
                            origin = o.optString("origin", ""),
                            userAgent = o.optString("user_agent", BROWSER_UA),
                            isDirectStream = o.optBoolean("is_direct", false)
                        )
                    )
                }
            } catch (e: Exception) {}
            list
        }

        if (panelServers.isEmpty()) return@coroutineScope emptyList()

        var fastestDispatched = false
        val lock = Any()

        // فحص متوازٍ لجميع السيرفرات التي وضعها الأدمن لاختيار الأسرع بينها تلقائياً
        val resolvedList = panelServers.map { srv ->
            async(Dispatchers.IO) {
                if (srv.isDirectStream) {
                    synchronized(lock) {
                        if (!fastestDispatched) {
                            fastestDispatched = true
                            onFastestDirectReady(srv)
                        }
                    }
                    srv
                } else {
                    // محاولة استخراج رابط .m3u8 أو .mp4 مباشر من رابط السيرفر الذي وضعه الأدمن
                    val directExtracted = tryExtractM3u8FromAdminServer(srv)
                    if (directExtracted != null) {
                        synchronized(lock) {
                            if (!fastestDispatched) {
                                fastestDispatched = true
                                onFastestDirectReady(directExtracted)
                            }
                        }
                        directExtracted
                    } else {
                        srv
                    }
                }
            }
        }.awaitAll()

        resolvedList
    }

    private fun tryExtractM3u8FromAdminServer(srv: StreamSource): StreamSource? {
        // استبعاد موقع netnaija من الاستخراج النصي العشوائي لأن صفحته تحتوي على فيديو إعلاني ثابت مدته 1:34
        if (srv.url.contains("netnaija", ignoreCase = true)) {
            return null
        }
        try {
            val html = httpGetFast(srv.url, srv.referer)

            // 1. فحص VixSrc Master Playlist (يرجع الفيلم الكامل بدقة 1080p فوراً من هاتف المستخدم)
            val urlMatch = Regex("""['"]?(https?://[^'"\s<>]+/playlist/\d+[^'"\s<>]*)['"]?""").find(html)
            val tokenMatch = Regex("""['"]token['"]\s*:\s*['"]([^'"]+)['"]""").find(html)
            val expiresMatch = Regex("""['"]expires['"]\s*:\s*['"]([^'"]+)['"]""").find(html)
            if (urlMatch != null && tokenMatch != null && expiresMatch != null) {
                val basePlaylist = urlMatch.groupValues[1]
                val sep = if (basePlaylist.contains("?")) "&" else "?"
                val finalM3u8 = "${basePlaylist}${sep}token=${tokenMatch.groupValues[1]}&expires=${expiresMatch.groupValues[1]}&h=1"
                return srv.copy(
                    url = finalM3u8,
                    referer = srv.url,
                    origin = "https://vixsrc.to",
                    isDirectStream = true
                )
            }

            // 2. فحص روابط .m3u8 المباشرة (مع استبعاد أي رابط يحتوي على كلمة promo أو trailer أو ad)
            val m3u8Regex = Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            m3u8Regex.findAll(html).forEach { m ->
                val link = m.value.replace("\\/", "/")
                if (!link.contains("promo", true) && !link.contains("trailer", true) && !link.contains("example.com", true)) {
                    return srv.copy(url = link, isDirectStream = true)
                }
            }

            // 3. فحص سلسلة VidSrc RCP -> ProRCP
            val iframeRegex = Regex("""<iframe[^>]+src=['"]((?:https?:)?//[^'"]+/rcp/[^'"]+)['"]""", RegexOption.IGNORE_CASE)
            val proRcpRegex = Regex("""['"](/(?:prorcp|srcrcp)/[A-Za-z0-9+/=_-]+)['"]""")
            val iframeMatch = iframeRegex.find(html)
            if (iframeMatch != null) {
                var rcpUrl = iframeMatch.groupValues[1]
                if (rcpUrl.startsWith("//")) rcpUrl = "https:$rcpUrl"
                val rcpHost = runCatching {
                    val u = android.net.Uri.parse(rcpUrl)
                    "${u.scheme}://${u.host}"
                }.getOrDefault("https://cloudnestra.com")
                val html2 = httpGetFast(rcpUrl, srv.url)
                m3u8Regex.find(html2)?.let { m ->
                    return srv.copy(
                        url = m.value.replace("\\/", "/"),
                        referer = "$rcpHost/",
                        origin = rcpHost,
                        isDirectStream = true
                    )
                }
                val proMatch = proRcpRegex.find(html2)
                if (proMatch != null) {
                    val html3 = httpGetFast("$rcpHost${proMatch.groupValues[1]}", rcpUrl)
                    m3u8Regex.find(html3)?.let { m ->
                        val link = m.value.replace("\\/", "/")
                        if (!link.contains("example.com")) {
                            return srv.copy(
                                url = link,
                                referer = "$rcpHost/",
                                origin = rcpHost,
                                isDirectStream = true
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return null
    }
}