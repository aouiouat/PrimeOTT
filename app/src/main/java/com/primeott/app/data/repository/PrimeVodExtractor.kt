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

    // عميل HTTP سريع جداً مخصص للاستخراج الفوري (مهلة 5 ثوانٍ فقط لعدم تأخير بدء الفيلم)
    private val fastExtractorClient: OkHttpClient = OttRepository.okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private var xtreamVodCacheLoaded = false
    private val xtreamVodByTmdbId = HashMap<String, StreamSource>()
    private val xtreamVodByTitle = HashMap<String, StreamSource>()

    private fun httpGetFast(url: String, referer: String = "", extraHeaders: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "*/*")
        if (referer.isNotEmpty()) {
            builder.header("Referer", referer)
        }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        fastExtractorClient.newCall(builder.build()).execute().use { res ->
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

                val voteCount = obj.optInt("vote_count", 0)
                if (voteCount < 25) continue

                val mediaType = obj.optString("media_type", "movie")
                val isTv = mediaType == "tv"
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
                val sources = OttRepository.buildRealProviderSources(id, isTv)

                list.add(
                    VodItem(
                        id = id,
                        title = title,
                        category = if (isTv) "Series" else "Movie",
                        rating = rating,
                        year = year,
                        posterUrl = "$TMDB_POSTER_BASE$posterPath",
                        streamUrls = sources.map { it.url },
                        isSeries = isTv,
                        description = overview
                    )
                )
                if (list.size >= 35) break
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext list
    }

    /**
     * استخراج فوري متوازٍ: يرسل السيرفر الأسرع فوراً عبر onFastestSourceFound ليشتغل الفيلم في أقل من ثانية!
     */
    suspend fun resolveDirectStreamsFast(
        tmdbId: String,
        imdbId: String = "",
        title: String = "",
        isSeries: Boolean = false,
        season: Int = 1,
        episode: Int = 1,
        onFastestSourceFound: (StreamSource) -> Unit = {}
    ): List<StreamSource> = coroutineScope {
        val results = ArrayList<StreamSource>()
        var firstDispatched = false

        fun notifyFound(list: List<StreamSource>) {
            synchronized(results) {
                for (src in list) {
                    if (results.none { it.url == src.url }) {
                        results.add(src)
                        if (!firstDispatched) {
                            firstDispatched = true
                            onFastestSourceFound(src)
                        }
                    }
                }
            }
        }

        val jobs = listOf(
            async(Dispatchers.IO) {
                val r = resolveFromVixSrcDirect(tmdbId, isSeries, season, episode)
                if (r.isNotEmpty()) notifyFound(r)
                r
            },
            async(Dispatchers.IO) {
                val r = resolveFromXtreamVodIfAvailable(tmdbId, title, isSeries)
                if (r.isNotEmpty()) notifyFound(r)
                r
            },
            async(Dispatchers.IO) {
                val r = resolveFromVidSrcRcpChain(tmdbId, imdbId, isSeries, season, episode)
                if (r.isNotEmpty()) notifyFound(r)
                r
            },
            async(Dispatchers.IO) {
                val r = resolveFromAutoEmbedDirect(tmdbId, isSeries, season, episode)
                if (r.isNotEmpty()) notifyFound(r)
                r
            }
        )

        jobs.awaitAll()
        results
    }

    private fun resolveFromXtreamVodIfAvailable(tmdbId: String, title: String, isSeries: Boolean): List<StreamSource> {
        if (isSeries) return emptyList()
        try {
            if (!xtreamVodCacheLoaded) {
                xtreamVodCacheLoaded = true
                val base = SessionManager.XTREAM_BASE_URL
                val u = SessionManager.XTREAM_USER
                val p = SessionManager.XTREAM_PASS
                val api = "$base/player_api.php?username=$u&password=$p&action=get_vod_streams"
                val raw = httpGetFast(api)
                if (raw.trim().startsWith("[")) {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val streamId = o.optInt("stream_id", 0)
                        if (streamId == 0) continue
                        val ext = o.optString("container_extension", "mp4").ifEmpty { "mp4" }
                        val directUrl = "$base/movie/$u/$p/$streamId.$ext"
                        val name = o.optString("name", "").lowercase(Locale.ROOT).trim()
                        val itemTmdb = o.optString("tmdb", "").ifEmpty { o.optString("tmdb_id", "") }
                        val src = StreamSource("Prime Direct 1080p", directUrl, isDirectStream = true)
                        if (itemTmdb.isNotEmpty() && itemTmdb != "0") {
                            xtreamVodByTmdbId[itemTmdb] = src
                        }
                        if (name.isNotEmpty()) {
                            xtreamVodByTitle[name] = src
                        }
                    }
                }
            }
            xtreamVodByTmdbId[tmdbId]?.let { return listOf(it) }
            val cleanTitle = title.lowercase(Locale.ROOT).trim()
            if (cleanTitle.isNotEmpty()) {
                xtreamVodByTitle[cleanTitle]?.let { return listOf(it) }
            }
        } catch (e: Exception) {}
        return emptyList()
    }

    /**
     * أسرع مزود عالمي (VixSrc CDN): يستخرج رابط الـ Master HLS `.m3u8` في ~300ms مباشرة عبر HTTP
     */
    private fun resolveFromVixSrcDirect(tmdbId: String, isSeries: Boolean, season: Int, episode: Int): List<StreamSource> {
        val list = ArrayList<StreamSource>()
        try {
            val pageUrl = if (isSeries) {
                "https://vixsrc.to/tv/$tmdbId/$season/$episode"
            } else {
                "https://vixsrc.to/movie/$tmdbId"
            }
            val html = httpGetFast(pageUrl, "https://vixsrc.to/")
            val urlMatch = Regex("""['"]?(https?://[^'"\s<>]+/playlist/\d+[^'"\s<>]*)['"]?""").find(html)
            val tokenMatch = Regex("""['"]token['"]\s*:\s*['"]([^'"]+)['"]""").find(html)
            val expiresMatch = Regex("""['"]expires['"]\s*:\s*['"]([^'"]+)['"]""").find(html)

            if (urlMatch != null && tokenMatch != null && expiresMatch != null) {
                val basePlaylist = urlMatch.groupValues[1]
                val token = tokenMatch.groupValues[1]
                val expires = expiresMatch.groupValues[1]
                val sep = if (basePlaylist.contains("?")) "&" else "?"
                val finalM3u8 = "${basePlaylist}${sep}token=$token&expires=$expires&h=1"
                list.add(
                    StreamSource(
                        label = "Prime Server 1080p (Fast CDN)",
                        url = finalM3u8,
                        referer = pageUrl,
                        origin = "https://vixsrc.to",
                        userAgent = BROWSER_UA,
                        isDirectStream = true
                    )
                )
            }

            val m3u8Regex = Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            m3u8Regex.findAll(html).forEach { m ->
                val link = m.value.replace("\\/", "/")
                if (list.none { it.url == link }) {
                    list.add(
                        StreamSource(
                            label = "Prime Server 1080p (HLS)",
                            url = link,
                            referer = pageUrl,
                            origin = "https://vixsrc.to",
                            userAgent = BROWSER_UA,
                            isDirectStream = true
                        )
                    )
                }
            }
        } catch (e: Exception) {}
        return list
    }

    private fun resolveFromVidSrcRcpChain(
        tmdbId: String,
        imdbId: String,
        isSeries: Boolean,
        season: Int,
        episode: Int
    ): List<StreamSource> {
        val list = ArrayList<StreamSource>()
        val queryId = imdbId.ifEmpty { tmdbId }
        val embedUrls = if (isSeries) {
            listOf(
                "https://vidsrc.xyz/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode",
                "https://vidsrc.net/embed/tv/$queryId/$season-$episode"
            )
        } else {
            listOf(
                "https://vidsrc.xyz/embed/movie?tmdb=$tmdbId",
                "https://vidsrc.net/embed/movie/$queryId"
            )
        }

        val iframeRegex = Regex("""<iframe[^>]+src=['"]((?:https?:)?//[^'"]+/rcp/[^'"]+)['"]""", RegexOption.IGNORE_CASE)
        val proRcpRegex = Regex("""['"](/(?:prorcp|srcrcp)/[A-Za-z0-9+/=_-]+)['"]""")
        val m3u8Regex = Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)

        for (embedUrl in embedUrls) {
            try {
                val html1 = httpGetFast(embedUrl, "https://vidsrc.xyz/")
                m3u8Regex.findAll(html1).forEach { m ->
                    val u = m.value.replace("\\/", "/")
                    list.add(StreamSource("Prime Server 2 (VidSrc FHD)", u, embedUrl, userAgent = BROWSER_UA, isDirectStream = true))
                }

                val iframeMatch = iframeRegex.find(html1) ?: continue
                var rcpUrl = iframeMatch.groupValues[1]
                if (rcpUrl.startsWith("//")) rcpUrl = "https:$rcpUrl"
                val rcpHost = runCatching {
                    val uri = android.net.Uri.parse(rcpUrl)
                    "${uri.scheme}://${uri.host}"
                }.getOrDefault("https://cloudnestra.com")

                val html2 = httpGetFast(rcpUrl, embedUrl)
                m3u8Regex.findAll(html2).forEach { m ->
                    val u = m.value.replace("\\/", "/")
                    list.add(StreamSource("Prime Server 2 (Cloud FHD)", u, "$rcpHost/", rcpHost, BROWSER_UA, true))
                }

                val proMatch = proRcpRegex.find(html2)
                if (proMatch != null) {
                    val proUrl = "$rcpHost${proMatch.groupValues[1]}"
                    val html3 = httpGetFast(proUrl, rcpUrl)
                    m3u8Regex.findAll(html3).forEach { m ->
                        val u = m.value.replace("\\/", "/")
                        if (!u.contains("example.com")) {
                            list.add(StreamSource("Prime Server 2 (VIP 1080p)", u, "$rcpHost/", rcpHost, BROWSER_UA, true))
                        }
                    }
                }
                if (list.isNotEmpty()) break
            } catch (e: Exception) {}
        }
        return list
    }

    private fun resolveFromAutoEmbedDirect(
        tmdbId: String,
        isSeries: Boolean,
        season: Int,
        episode: Int
    ): List<StreamSource> {
        val list = ArrayList<StreamSource>()
        val ep = if (isSeries) {
            "https://autoembed.cc/embed/player.php?id=$tmdbId&s=$season&e=$episode"
        } else {
            "https://autoembed.cc/embed/player.php?id=$tmdbId"
        }
        val m3u8Regex = Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
        val mp4Regex = Regex("""https?://[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)

        try {
            val html = httpGetFast(ep, "https://autoembed.cc/")
            m3u8Regex.findAll(html).forEach { m ->
                val link = m.value.replace("\\/", "/")
                list.add(StreamSource("Prime Server 3 (AutoEmbed)", link, ep, userAgent = BROWSER_UA, isDirectStream = true))
            }
            mp4Regex.findAll(html).forEach { m ->
                val link = m.value.replace("\\/", "/")
                list.add(StreamSource("Prime Server 3 (Direct MP4)", link, ep, userAgent = BROWSER_UA, isDirectStream = true))
            }
        } catch (e: Exception) {}
        return list
    }
}