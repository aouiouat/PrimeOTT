package com.primeott.app.data.repository

import android.content.Context
import androidx.core.text.HtmlCompat
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class StreamOverride(
    val streamId: String,
    val customName: String,
    val customIcon: String,
    val customUrl: String,
    val status: String,
    val isDeleted: Boolean
)

data class RoomSyncResult(
    val status: String,
    val room: WatchRoom?,
    val participants: List<RoomParticipant>,
    val messages: List<RoomChatMessage>,
    val errorMessage: String = "",
    val requirePassword: Boolean = false
)

data class HomeCatalogSections(
    val heroItems: List<VodItem>,
    val trendingToday: List<VodItem>,
    val popularMovies: List<VodItem>,
    val popularTvShows: List<VodItem>,
    val topRatedMovies: List<VodItem>,
    val topRatedTv: List<VodItem>,
    val turkishDrama: List<VodItem>,
    val koreanDrama: List<VodItem>,
    val documentaries: List<VodItem>
)

object OttRepository {

    private const val TMDB_API_KEY = "8265bd1679663a7ea12ac168da84d2e8"
    private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w500"
    private const val TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/w780"

    // فلتر صارم لضمان جلب الأفلام والمسلسلات الصادرة والمتوفرة فعلياً على سيرفرات المشاهدة والترجمة
    private const val RELEASED_MOVIE_FILTER = "primary_release_date.lte=2025-12-31&with_release_type=4|5|6&vote_count.gte=120"
    private const val RELEASED_TV_FILTER = "first_air_date.lte=2025-12-31&vote_count.gte=60"

    private val favoriteChannelIds = mutableSetOf<String>()
    private val favoriteMatchIds = mutableSetOf<String>()
    private var cachedCategories = listOf<Category>()
    private var cachedCustomChannels = listOf<Channel>()
    private var cachedMovies = listOf<VodItem>()
    private var cachedSeries = listOf<VodItem>()
    private var cachedMatches = listOf<MatchFixture>()
    private var cachedHomeSections: HomeCatalogSections? = null

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun initFavorites(context: Context) {
        if (favoriteChannelIds.isEmpty()) {
            favoriteChannelIds.addAll(SessionManager.getFavoriteIds(context))
        }
    }

    private fun cleanHtmlText(raw: String): String {
        return HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    private fun fastGet(url: String, customHeaders: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
        customHeaders.forEach { (k, v) -> builder.header(k, v) }
        okHttpClient.newCall(builder.build()).execute().use { response ->
            return response.body?.string() ?: ""
        }
    }

    private fun fastPostJson(url: String, payload: JSONObject): Pair<Int, JSONObject> {
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        okHttpClient.newCall(req).execute().use { res ->
            val str = res.body?.string() ?: "{}"
            val json = try { JSONObject(str) } catch (e: Exception) { JSONObject() }
            return Pair(res.code, json)
        }
    }

    /**
     * مصادر الاستخراج الصامتة الحقيقية لكل فيلم أو حلقة عبر TMDB_ID
     * (تُستخرج منها روابط .m3u8/.mp4 في الخلفية وتشتغل داخل ExoPlayer الخاص بنا فقط)
     */
    fun buildRealProviderSources(tmdbId: String, isSeries: Boolean, season: Int = 1, episode: Int = 1): List<StreamSource> {
        // يتم جلب السيرفرات وأسمائها حصرياً من لوحة تحكم Cloudflare (/api/v2/vod-stream)
        return emptyList()
    }

    private fun parseTmdbArrayToVodList(arr: JSONArray?, defaultCategory: String, forceSeries: Boolean? = null): List<VodItem> {
        if (arr == null) return emptyList()
        val list = ArrayList<VodItem>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val tmdbId = obj.optInt("id", 0).toString()
            if (tmdbId == "0") continue

            val posterPath = obj.optString("poster_path", "")
            if (posterPath.isEmpty() || posterPath == "null") continue
            val backdropPath = obj.optString("backdrop_path", "")

            val mediaType = obj.optString("media_type", "")
            val isTv = forceSeries ?: (mediaType == "tv" || obj.has("first_air_date") || obj.has("name"))

            val rawDate = if (isTv) obj.optString("first_air_date", "") else obj.optString("release_date", "")
            if (rawDate.isEmpty() || rawDate > "2026-01-15") continue

            val voteCount = obj.optInt("vote_count", 0)
            if (voteCount < 15) continue

            val title = if (isTv) {
                obj.optString("name").ifEmpty { obj.optString("original_name", "Series") }
            } else {
                obj.optString("title").ifEmpty { obj.optString("original_title", "Movie") }
            }

            val vote = obj.optDouble("vote_average", 7.2)
            val rating = String.format(Locale.US, "%.1f", if (vote > 0.1) vote else 7.2)
            val year = if (rawDate.length >= 4) rawDate.take(4) else "2025"
            val overview = obj.optString("overview", "")
            val posterUrl = "$TMDB_POSTER_BASE$posterPath"
            val backdropUrl = if (backdropPath.isNotEmpty() && backdropPath != "null") "$TMDB_BACKDROP_BASE$backdropPath" else posterUrl
            val providerSources = buildRealProviderSources(tmdbId, isTv)

            list.add(
                VodItem(
                    id = tmdbId,
                    title = title,
                    category = defaultCategory,
                    rating = rating,
                    year = year,
                    posterUrl = posterUrl,
                    streamUrls = providerSources.map { it.url },
                    isSeries = isTv,
                    description = overview,
                    backdropUrl = backdropUrl
                )
            )
        }
        return list
    }

    suspend fun fetchHomeSections(): HomeCatalogSections = withContext(Dispatchers.IO) {
        cachedHomeSections?.let { return@withContext it }

        val trendingToday = runCatching {
            val json = JSONObject(fastGet("https://api.themoviedb.org/3/trending/movie/week?api_key=$TMDB_API_KEY&language=en-US&page=1"))
            parseTmdbArrayToVodList(json.optJSONArray("results"), "Movie", false)
        }.getOrDefault(emptyList())

        val popularMovies = runCatching {
            val json = JSONObject(fastGet("https://api.themoviedb.org/3/discover/movie?api_key=$TMDB_API_KEY&sort_by=popularity.desc&$RELEASED_MOVIE_FILTER&page=1"))
            parseTmdbArrayToVodList(json.optJSONArray("results"), "Movie", false)
        }.getOrDefault(emptyList())

        val popularTv = runCatching {
            val json = JSONObject(fastGet("https://api.themoviedb.org/3/discover/tv?api_key=$TMDB_API_KEY&sort_by=popularity.desc&$RELEASED_TV_FILTER&page=1"))
            parseTmdbArrayToVodList(json.optJSONArray("results"), "TV Show", true)
        }.getOrDefault(emptyList())

        val topRatedMovies = runCatching {
            val json = JSONObject(fastGet("https://api.themoviedb.org/3/discover/movie?api_key=$TMDB_API_KEY&sort_by=vote_average.desc&vote_count.gte=1500&$RELEASED_MOVIE_FILTER&page=1"))
            parseTmdbArrayToVodList(json.optJSONArray("results"), "Top Rated", false)
        }.getOrDefault(emptyList())

        val topRatedTv = runCatching {
            val json = JSONObject(fastGet("https://api.themoviedb.org/3/discover/tv?api_key=$TMDB_API_KEY&sort_by=vote_average.desc&vote_count.gte=800&$RELEASED_TV_FILTER&page=1"))
            parseTmdbArrayToVodList(json.optJSONArray("results"), "Top Rated TV", true)
        }.getOrDefault(emptyList())

        val turkishDrama = runCatching {
            val json = JSONObject(fastGet("https://api.themoviedb.org/3/discover/tv?api_key=$TMDB_API_KEY&with_origin_country=TR&sort_by=popularity.desc&$RELEASED_TV_FILTER&page=1"))
            parseTmdbArrayToVodList(json.optJSONArray("results"), "Turkish Drama", true)
        }.getOrDefault(emptyList())

        val koreanDrama = runCatching {
            val json = JSONObject(fastGet("https://api.themoviedb.org/3/discover/tv?api_key=$TMDB_API_KEY&with_origin_country=KR&sort_by=popularity.desc&$RELEASED_TV_FILTER&page=1"))
            parseTmdbArrayToVodList(json.optJSONArray("results"), "Korean Drama", true)
        }.getOrDefault(emptyList())

        val documentaries = runCatching {
            val json = JSONObject(fastGet("https://api.themoviedb.org/3/discover/movie?api_key=$TMDB_API_KEY&with_genres=99&sort_by=popularity.desc&$RELEASED_MOVIE_FILTER&page=1"))
            parseTmdbArrayToVodList(json.optJSONArray("results"), "Documentary", false)
        }.getOrDefault(emptyList())

        val hero = (trendingToday.take(6) + popularMovies.take(6)).distinctBy { it.id }
        val result = HomeCatalogSections(
            heroItems = hero,
            trendingToday = trendingToday,
            popularMovies = popularMovies,
            popularTvShows = popularTv,
            topRatedMovies = topRatedMovies,
            topRatedTv = topRatedTv,
            turkishDrama = turkishDrama,
            koreanDrama = koreanDrama,
            documentaries = documentaries
        )
        if (hero.isNotEmpty()) {
            cachedHomeSections = result
        }
        return@withContext result
    }

    suspend fun fetchVodCatalogPage(isSeries: Boolean, page: Int = 1, genreOrFilter: String = ""): List<VodItem> = withContext(Dispatchers.IO) {
        try {
            val endpoint = when {
                genreOrFilter == "TR" -> "https://api.themoviedb.org/3/discover/tv?api_key=$TMDB_API_KEY&with_origin_country=TR&sort_by=popularity.desc&$RELEASED_TV_FILTER&page=$page"
                genreOrFilter == "KR" -> "https://api.themoviedb.org/3/discover/tv?api_key=$TMDB_API_KEY&with_origin_country=KR&sort_by=popularity.desc&$RELEASED_TV_FILTER&page=$page"
                genreOrFilter == "DOC" -> "https://api.themoviedb.org/3/discover/movie?api_key=$TMDB_API_KEY&with_genres=99&sort_by=popularity.desc&$RELEASED_MOVIE_FILTER&page=$page"
                genreOrFilter == "TOP_MOVIE" -> "https://api.themoviedb.org/3/discover/movie?api_key=$TMDB_API_KEY&sort_by=vote_average.desc&vote_count.gte=1500&$RELEASED_MOVIE_FILTER&page=$page"
                genreOrFilter == "TOP_TV" -> "https://api.themoviedb.org/3/discover/tv?api_key=$TMDB_API_KEY&sort_by=vote_average.desc&vote_count.gte=800&$RELEASED_TV_FILTER&page=$page"
                isSeries -> "https://api.themoviedb.org/3/discover/tv?api_key=$TMDB_API_KEY&sort_by=popularity.desc&$RELEASED_TV_FILTER&page=$page"
                else -> "https://api.themoviedb.org/3/discover/movie?api_key=$TMDB_API_KEY&sort_by=popularity.desc&$RELEASED_MOVIE_FILTER&page=$page"
            }
            val root = JSONObject(fastGet(endpoint))
            return@withContext parseTmdbArrayToVodList(root.optJSONArray("results"), if (isSeries) "Series" else "Movie", isSeries)
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }

    suspend fun searchTmdbCatalog(query: String): List<VodItem> = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$TMDB_API_KEY&query=$encoded&page=1"
            val root = JSONObject(fastGet(url))
            return@withContext parseTmdbArrayToVodList(root.optJSONArray("results"), "Search")
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }

    suspend fun fetchMovieOrSeriesFullDetails(vod: VodItem): MovieFullDetails = withContext(Dispatchers.IO) {
        try {
            val type = if (vod.isSeries) "tv" else "movie"
            val url = "https://api.themoviedb.org/3/$type/${vod.id}?api_key=$TMDB_API_KEY&language=en-US&append_to_response=credits,recommendations,similar,videos,external_ids"
            val root = JSONObject(fastGet(url))

            val imdbId = root.optString("imdb_id").ifEmpty {
                root.optJSONObject("external_ids")?.optString("imdb_id", "") ?: ""
            }

            val numSeasons = if (vod.isSeries) {
                root.optInt("number_of_seasons", 1).coerceAtLeast(1)
            } else 1

            val runtimeMins = if (vod.isSeries) {
                root.optJSONArray("episode_run_time")?.optInt(0, 45) ?: 45
            } else {
                root.optInt("runtime", 115)
            }
            val hours = runtimeMins / 60
            val mins = runtimeMins % 60
            val runtimeText = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

            val genresArr = root.optJSONArray("genres") ?: JSONArray()
            val genres = ArrayList<String>()
            for (i in 0 until minOf(genresArr.length(), 3)) {
                val gName = genresArr.optJSONObject(i)?.optString("name", "") ?: ""
                if (gName.isNotEmpty()) genres.add(gName)
            }
            if (genres.isEmpty()) genres.add(vod.category.ifEmpty { "Drama" })

            val castArr = root.optJSONObject("credits")?.optJSONArray("cast") ?: JSONArray()
            val castList = ArrayList<CastMember>()
            for (i in 0 until minOf(castArr.length(), 20)) {
                val c = castArr.optJSONObject(i) ?: continue
                val profilePath = c.optString("profile_path", "")
                val profileUrl = if (profilePath.isNotEmpty() && profilePath != "null") {
                    "https://image.tmdb.org/t/p/w185$profilePath"
                } else ""
                castList.add(
                    CastMember(
                        id = c.optInt("id").toString(),
                        name = c.optString("name", "Actor"),
                        character = c.optString("character", ""),
                        profileUrl = profileUrl
                    )
                )
            }

            val videosArr = root.optJSONObject("videos")?.optJSONArray("results") ?: JSONArray()
            var trailerKey = ""
            for (i in 0 until videosArr.length()) {
                val v = videosArr.optJSONObject(i) ?: continue
                if (v.optString("site").equals("YouTube", true) &&
                    (v.optString("type").equals("Trailer", true) || trailerKey.isEmpty())
                ) {
                    trailerKey = v.optString("key", "")
                    if (v.optString("type").equals("Trailer", true)) break
                }
            }

            val recArr = root.optJSONObject("recommendations")?.optJSONArray("results")
            val simArr = root.optJSONObject("similar")?.optJSONArray("results")
            val recommendedList = parseTmdbArrayToVodList(recArr, vod.category, vod.isSeries)
            val similarList = parseTmdbArrayToVodList(simArr, vod.category, vod.isSeries)

            val overview = root.optString("overview", vod.description).ifEmpty { vod.description }
            val backdropPath = root.optString("backdrop_path", "")
            val backdropUrl = if (backdropPath.isNotEmpty() && backdropPath != "null") "$TMDB_BACKDROP_BASE$backdropPath" else vod.posterUrl

            return@withContext MovieFullDetails(
                id = vod.id,
                imdbId = imdbId,
                title = vod.title,
                rating = vod.rating,
                year = vod.year,
                runtimeText = runtimeText,
                genres = genres,
                overview = overview,
                posterUrl = vod.posterUrl,
                backdropUrl = backdropUrl,
                trailerYoutubeKey = trailerKey,
                cast = castList,
                recommended = recommendedList.ifEmpty { similarList },
                similar = similarList.ifEmpty { recommendedList },
                providerEmbedUrls = buildRealProviderSources(vod.id, vod.isSeries),
                numberOfSeasons = numSeasons
            )
        } catch (e: Exception) {
            return@withContext MovieFullDetails(
                id = vod.id,
                imdbId = "",
                title = vod.title,
                rating = vod.rating,
                year = vod.year,
                runtimeText = "1h 45m",
                genres = listOf(vod.category.ifEmpty { "Movie" }),
                overview = vod.description,
                posterUrl = vod.posterUrl,
                backdropUrl = vod.posterUrl,
                trailerYoutubeKey = "",
                cast = emptyList(),
                recommended = cachedMovies.take(12),
                similar = cachedMovies.drop(12).take(12),
                providerEmbedUrls = buildRealProviderSources(vod.id, vod.isSeries),
                numberOfSeasons = 1
            )
        }
    }

    /**
     * جلب الترجمات الحقيقية وترتيبها ذكياً بحيث تكون نسخ WEB-DL / WEBRip
     * (المطابقة 100% لتوقيت سيرفرات المشاهدة) في المركز الأول دائماً!
     */
    suspend fun fetchRealSubtitlesForMovie(
        tmdbId: String,
        imdbId: String,
        isSeries: Boolean = false,
        season: Int = 1,
        episode: Int = 1
    ): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        val tracks = ArrayList<SubtitleTrack>()

        var resolvedImdbId = imdbId.trim()
        if (resolvedImdbId.isEmpty()) {
            runCatching {
                val type = if (isSeries) "tv" else "movie"
                val extJson = JSONObject(fastGet("https://api.themoviedb.org/3/$type/$tmdbId/external_ids?api_key=$TMDB_API_KEY"))
                resolvedImdbId = extJson.optString("imdb_id", "")
            }
        }

        // 1. Wyzie Subtitles API (يجلب من SubDL + OpenSubtitles مع اسم النسخة الدقيق)
        val idsToQuery = mutableListOf(tmdbId)
        if (resolvedImdbId.isNotEmpty()) idsToQuery.add(resolvedImdbId)

        for (qId in idsToQuery) {
            try {
                val wyzieUrl = if (isSeries) {
                    "https://sub.wyzie.ru/search?id=$qId&season=$season&episode=$episode"
                } else {
                    "https://sub.wyzie.ru/search?id=$qId"
                }
                val raw = fastGet(wyzieUrl)
                if (raw.trim().startsWith("[")) {
                    val arr = JSONArray(raw)
                    for (i in 0 until minOf(arr.length(), 60)) {
                        val o = arr.optJSONObject(i) ?: continue
                        val subUrl = o.optString("url", "")
                        if (subUrl.isEmpty()) continue
                        val langCode = normalizeLangCode(o.optString("language", "en"))
                        val display = formatLangDisplayName(langCode, o.optString("display", ""))
                        val release = o.optString("release", "").ifEmpty {
                            o.optString("source", "WEB-DL.1080p")
                        }
                        if (tracks.none { it.url == subUrl }) {
                            tracks.add(
                                SubtitleTrack(
                                    id = o.optString("id", "wyzie_${qId}_$i"),
                                    languageCode = langCode,
                                    languageLabel = display,
                                    releaseName = release,
                                    url = subUrl
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        // 2. OpenSubtitles v3 Official JSON API
        if (resolvedImdbId.isNotEmpty()) {
            try {
                val cleanImdb = if (resolvedImdbId.startsWith("tt")) resolvedImdbId else "tt$resolvedImdbId"
                val stremioOsUrl = if (isSeries) {
                    "https://opensubtitles-v3.strem.io/subtitles/series/$cleanImdb:$season:$episode.json"
                } else {
                    "https://opensubtitles-v3.strem.io/subtitles/movie/$cleanImdb.json"
                }
                val raw = fastGet(stremioOsUrl)
                if (raw.trim().startsWith("{")) {
                    val root = JSONObject(raw)
                    val subsArr = root.optJSONArray("subtitles") ?: JSONArray()
                    for (i in 0 until minOf(subsArr.length(), 60)) {
                        val o = subsArr.optJSONObject(i) ?: continue
                        val subUrl = o.optString("url", "")
                        if (subUrl.isEmpty()) continue
                        val langCode = normalizeLangCode(o.optString("lang", "eng"))
                        if (langCode !in listOf("ar", "en", "fr", "es", "tr")) continue
                        val display = formatLangDisplayName(langCode, "")
                        if (tracks.none { it.url == subUrl }) {
                            tracks.add(
                                SubtitleTrack(
                                    id = o.optString("id", "osv3_$i"),
                                    languageCode = langCode,
                                    languageLabel = display,
                                    releaseName = "WEB-DL.1080p.Synced.${langCode.uppercase(Locale.ROOT)}",
                                    url = subUrl
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        // ترتيب ذكي جداً: اللغة أولاً، ثم تقديم نسخ WEB-DL / WEBRip / AMZN / NF على نسخ BluRay
        return@withContext tracks.sortedWith(
            compareBy<SubtitleTrack>(
                {
                    when (it.languageCode) {
                        "ar" -> 0
                        "en" -> 1
                        "fr" -> 2
                        "es" -> 3
                        "tr" -> 4
                        else -> 5
                    }
                },
                {
                    val r = it.releaseName.lowercase(Locale.ROOT)
                    when {
                        r.contains("stream") || r.contains("auto") -> 0
                        r.contains("web-dl") || r.contains("webdl") || r.contains("webrip") || r.contains("amzn") || r.contains("nf") -> 1
                        r.contains("bluray") || r.contains("brrip") -> 3
                        else -> 2
                    }
                }
            )
        )
    }

    private fun normalizeLangCode(raw: String): String {
        val r = raw.lowercase(Locale.ROOT).trim()
        return when {
            r == "ara" || r.startsWith("ar") -> "ar"
            r == "eng" || r.startsWith("en") -> "en"
            r == "fre" || r == "fra" || r.startsWith("fr") -> "fr"
            r == "spa" || r.startsWith("es") -> "es"
            r == "tur" || r.startsWith("tr") -> "tr"
            else -> r.take(2)
        }
    }

    private fun formatLangDisplayName(code: String, fallback: String): String {
        return when (code) {
            "ar" -> "Arabic (العربية)"
            "en" -> "English"
            "fr" -> "French (Français)"
            "es" -> "Spanish (Español)"
            "tr" -> "Turkish (Türkçe)"
            else -> fallback.ifEmpty { code.uppercase(Locale.ROOT) }
        }
    }

    suspend fun downloadAndParseSubtitles(track: SubtitleTrack): List<SubtitleCue> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(track.url)
                .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                .header("X-User-Agent", "TemporaryUserAgent")
                .build()
            okHttpClient.newCall(req).execute().use { res ->
                val bytes = res.body?.bytes() ?: return@withContext emptyList()
                val textContent = if (track.url.endsWith(".gz", true) || (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())) {
                    GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).readText()
                } else {
                    String(bytes, Charsets.UTF_8)
                }
                return@withContext parseSrtOrVttContent(textContent)
            }
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }

    fun parseSrtOrVttContent(raw: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val normalized = raw.replace("\uFEFF", "").replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\n\\s*\n"))
        val timeRegex = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})""")

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val timeLineIdx = lines.indexOfFirst { timeRegex.containsMatchIn(it) }
            if (timeLineIdx != -1) {
                val match = timeRegex.find(lines[timeLineIdx]) ?: continue
                val startH = match.groupValues[1].toLongOrNull() ?: 0L
                val startM = match.groupValues[2].toLongOrNull() ?: 0L
                val startS = match.groupValues[3].toLongOrNull() ?: 0L
                val startMs = match.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: 0L

                val endH = match.groupValues[5].toLongOrNull() ?: 0L
                val endM = match.groupValues[6].toLongOrNull() ?: 0L
                val endS = match.groupValues[7].toLongOrNull() ?: 0L
                val endMs = match.groupValues[8].padEnd(3, '0').take(3).toLongOrNull() ?: 0L

                val totalStart = (startH * 3600_000L) + (startM * 60_000L) + (startS * 1000L) + startMs
                val totalEnd = (endH * 3600_000L) + (endM * 60_000L) + (endS * 1000L) + endMs

                val textLines = lines.drop(timeLineIdx + 1)
                if (textLines.isNotEmpty()) {
                    val clean = cleanHtmlText(textLines.joinToString("\n"))
                    if (clean.isNotEmpty()) {
                        cues.add(SubtitleCue(totalStart, totalEnd, clean))
                    }
                }
            }
        }
        return cues
    }

    suspend fun fetchMovies(): List<VodItem> = withContext(Dispatchers.IO) {
        if (cachedMovies.isNotEmpty()) return@withContext cachedMovies
        val page1 = fetchVodCatalogPage(isSeries = false, page = 1)
        val page2 = fetchVodCatalogPage(isSeries = false, page = 2)
        val combined = (page1 + page2).distinctBy { it.id }
        if (combined.isNotEmpty()) cachedMovies = combined
        return@withContext cachedMovies
    }

    suspend fun fetchSeries(): List<VodItem> = withContext(Dispatchers.IO) {
        if (cachedSeries.isNotEmpty()) return@withContext cachedSeries
        val page1 = fetchVodCatalogPage(isSeries = true, page = 1)
        val page2 = fetchVodCatalogPage(isSeries = true, page = 2)
        val combined = (page1 + page2).distinctBy { it.id }
        if (combined.isNotEmpty()) cachedSeries = combined
        return@withContext cachedSeries
    }

    suspend fun syncWithCloudflare() = withContext(Dispatchers.IO) {
        try {
            val jsonStr = fastGet("${SessionManager.CLOUDFLARE_API_URL}/api/app/config")
            val root = JSONObject(jsonStr)
            if (root.optString("status") == "success") {
                root.optJSONObject("server")?.let { srv ->
                    SessionManager.XTREAM_BASE_URL = srv.optString("host", SessionManager.XTREAM_BASE_URL)
                    SessionManager.XTREAM_USER = srv.optString("username", SessionManager.XTREAM_USER)
                    SessionManager.XTREAM_PASS = srv.optString("password", SessionManager.XTREAM_PASS)
                }
                SessionManager.AD_URL = root.optString("ad_url", SessionManager.AD_URL)
                root.optJSONObject("remote_config")?.let { rc ->
                    SessionManager.ADS_ENABLED = rc.optBoolean("ads_enabled", true)
                    SessionManager.AD_SECONDS = rc.optInt("ad_seconds", 10)
                    SessionManager.BANNER_BADGE = rc.optString("banner_badge", SessionManager.BANNER_BADGE)
                    SessionManager.BANNER_TITLE = rc.optString("banner_title", SessionManager.BANNER_TITLE)
                    SessionManager.BANNER_STREAM_ID = rc.optString("banner_stream_id", "6171")
                }

                val matchesArr = root.optJSONArray("matches")
                if (matchesArr != null) {
                    val list = mutableListOf<MatchFixture>()
                    for (i in 0 until matchesArr.length()) {
                        val m = matchesArr.getJSONObject(i)
                        val matchId = m.optString("id", "m_$i")
                        val streamId = m.optString("stream_id", "6171")
                        val altId = m.optString("alt_stream_id", "6172")
                        val chanName = m.optString("channel_name", "beIN SPORTS 1 FHD")
                        val hlsMain = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.m3u8"
                        val tsMain = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.ts"
                        val hlsAlt = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$altId.m3u8"
                        list.add(
                            MatchFixture(
                                id = matchId,
                                homeTeam = m.optString("home_team"),
                                awayTeam = m.optString("away_team"),
                                matchTime = m.optString("match_time"),
                                league = m.optString("league", "League"),
                                commentator = m.optString("commentator", "Live"),
                                status = m.optString("status", "UPCOMING"),
                                channel = Channel(streamId, "sport", chanName, "", listOf(hlsMain, tsMain, hlsAlt)),
                                homeLogo = m.optString("home_logo", ""),
                                awayLogo = m.optString("away_logo", ""),
                                homeScore = m.optString("home_score", "-"),
                                awayScore = m.optString("away_score", "-"),
                                liveMinute = m.optString("live_minute", ""),
                                stadium = m.optString("stadium", "Stadium"),
                                dayTab = m.optString("day_tab", "TODAY"),
                                leagueLogo = m.optString("league_logo", ""),
                                isFavoriteMatch = favoriteMatchIds.contains(matchId)
                            )
                        )
                    }
                    cachedMatches = list
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun activateSubscription(code: String, deviceId: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("code", code)
                put("device_id", deviceId)
            }
            val (httpCode, json) = fastPostJson("${SessionManager.CLOUDFLARE_API_URL}/api/app/activate", payload)
            if (httpCode in 200..299 && json.optString("status") == "success") {
                return@withContext Pair(true, json.optString("expire_date", "2027-01-01"))
            }
            return@withContext Pair(false, json.optString("message", "Invalid Code"))
        } catch (e: Exception) {
            return@withContext Pair(false, "Network Error")
        }
    }

    suspend fun fetchLiveCategories(): List<Category> = withContext(Dispatchers.IO) {
        try {
            val jsonStr = fastGet("${SessionManager.CLOUDFLARE_API_URL}/api/v2/catalog")
            val root = JSONObject(jsonStr)
            if (root.optString("status") == "success") {
                val catsArr = root.optJSONArray("categories") ?: JSONArray()
                val catList = ArrayList<Category>(catsArr.length())
                for (i in 0 until catsArr.length()) {
                    val c = catsArr.getJSONObject(i)
                    catList.add(
                        Category(
                            id = c.optString("id"),
                            name = c.optString("name"),
                            iconUrl = c.optString("icon_url", "")
                        )
                    )
                }
                val chArr = root.optJSONArray("channels") ?: JSONArray()
                val chList = ArrayList<Channel>(chArr.length())
                for (i in 0 until chArr.length()) {
                    val ch = chArr.getJSONObject(i)
                    val srcArr = ch.optJSONArray("sources") ?: JSONArray()
                    val sources = ArrayList<StreamSource>(srcArr.length())
                    for (j in 0 until srcArr.length()) {
                        val s = srcArr.getJSONObject(j)
                        val lbl = s.optString("label", "HD")
                        val u = s.optString("url", "")
                        if (u.isNotEmpty()) sources.add(StreamSource(lbl, u))
                    }
                    if (sources.isNotEmpty()) {
                        val chId = ch.optString("id")
                        chList.add(
                            Channel(
                                id = chId,
                                categoryId = ch.optString("category_id"),
                                name = ch.optString("name"),
                                logoUrl = ch.optString("logo_url"),
                                streamUrls = sources.map { it.url },
                                isFavorite = favoriteChannelIds.contains(chId),
                                namedSources = sources
                            )
                        )
                    }
                }
                cachedCustomChannels = chList
                cachedCategories = catList
                return@withContext catList
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext cachedCategories
    }

    suspend fun fetchChannelsByCategory(categoryId: String): List<Channel> = withContext(Dispatchers.IO) {
        if (cachedCustomChannels.isEmpty()) {
            fetchLiveCategories()
        }
        return@withContext cachedCustomChannels.filter { it.categoryId == categoryId }
    }

    suspend fun fetchMatchesFromCloudflare(): List<MatchFixture> = withContext(Dispatchers.IO) {
        syncWithCloudflare()
        return@withContext cachedMatches.map { it.copy(isFavoriteMatch = favoriteMatchIds.contains(it.id)) }
    }

    fun toggleFavorite(channelId: String, context: Context? = null) {
        if (favoriteChannelIds.contains(channelId)) favoriteChannelIds.remove(channelId)
        else favoriteChannelIds.add(channelId)
        context?.let { SessionManager.saveFavoriteIds(it, favoriteChannelIds) }
    }

    fun isChannelFavorite(channelId: String): Boolean = favoriteChannelIds.contains(channelId)

    fun toggleFavoriteMatch(matchId: String) {
        if (favoriteMatchIds.contains(matchId)) favoriteMatchIds.remove(matchId)
        else favoriteMatchIds.add(matchId)
    }

    suspend fun fetchActiveWatchRooms(): List<WatchRoom> = withContext(Dispatchers.IO) {
        try {
            val str = fastGet("${SessionManager.CLOUDFLARE_API_URL}/api/watch/rooms")
            val json = JSONObject(str)
            val arr = json.optJSONArray("rooms") ?: return@withContext emptyList()
            val list = ArrayList<WatchRoom>(arr.length())
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                list.add(
                    WatchRoom(
                        roomId = r.optString("room_id"),
                        roomCode = r.optString("room_code"),
                        roomName = r.optString("room_name"),
                        roomIcon = r.optString("room_icon", "⚽"),
                        hostName = r.optString("host_name"),
                        isPrivate = r.optBoolean("is_private", false),
                        isLocked = r.optBoolean("is_locked", false),
                        contentType = r.optString("content_type", "LIVE"),
                        streamId = r.optString("stream_id", "6171"),
                        contentTitle = r.optString("content_title"),
                        contentPoster = r.optString("content_poster"),
                        participantCount = r.optInt("participant_count", 1),
                        inviteUrl = r.optString("invite_url")
                    )
                )
            }
            return@withContext list
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }

    suspend fun createWatchRoom(
        deviceId: String,
        userName: String,
        roomName: String,
        roomIcon: String,
        isPrivate: Boolean,
        password: String,
        allowGuestsControl: Boolean,
        contentType: String,
        streamId: String,
        contentTitle: String,
        contentPoster: String,
        streamUrl: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("user_name", userName)
                put("room_name", roomName)
                put("room_icon", roomIcon)
                put("is_private", isPrivate)
                put("password", password)
                put("allow_guests_control", allowGuestsControl)
                put("content_type", contentType)
                put("stream_id", streamId)
                put("content_title", contentTitle)
                put("content_poster", contentPoster)
                put("stream_url", streamUrl)
            }
            val (code, json) = fastPostJson("${SessionManager.CLOUDFLARE_API_URL}/api/watch/create", payload)
            if (code in 200..299 && json.optString("status") == "success") {
                return@withContext Pair(true, json.optString("room_code"))
            }
            return@withContext Pair(false, json.optString("message", "Error creating room"))
        } catch (e: Exception) {
            return@withContext Pair(false, "Network Error")
        }
    }

    suspend fun joinWatchRoom(
        codeOrId: String,
        deviceId: String,
        userName: String,
        password: String = ""
    ): RoomSyncResult = withContext(Dispatchers.IO) {
        try {
            val cleanCode = codeOrId
                .substringAfterLast("/join/")
                .substringAfterLast("code=")
                .substringBefore("&")
                .trim()
                .uppercase()
            val payload = JSONObject().apply {
                put("room_code", cleanCode)
                put("room_id", cleanCode)
                put("device_id", deviceId)
                put("user_name", userName)
                put("password", password)
            }
            val (_, json) = fastPostJson("${SessionManager.CLOUDFLARE_API_URL}/api/watch/join", payload)
            return@withContext parseRoomStateJson(json)
        } catch (e: Exception) {
            return@withContext RoomSyncResult("error", null, emptyList(), emptyList(), "Connection Error")
        }
    }

    suspend fun syncWatchRoom(
        roomId: String,
        deviceId: String,
        lastMessageId: Long,
        isMuted: Boolean,
        inVoice: Boolean,
        playbackUpdate: JSONObject? = null
    ): RoomSyncResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("room_id", roomId)
                put("device_id", deviceId)
                put("last_message_id", lastMessageId)
                put("is_muted", isMuted)
                put("in_voice", inVoice)
                if (playbackUpdate != null) put("playback_update", playbackUpdate)
            }
            val (_, json) = fastPostJson("${SessionManager.CLOUDFLARE_API_URL}/api/watch/sync", payload)
            return@withContext parseRoomStateJson(json)
        } catch (e: Exception) {
            return@withContext RoomSyncResult("network_error", null, emptyList(), emptyList())
        }
    }

    suspend fun sendRoomMessage(
        roomId: String,
        deviceId: String,
        content: String,
        messageType: String = "TEXT"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("room_id", roomId)
                put("device_id", deviceId)
                put("message_type", messageType)
                put("content", content)
            }
            val (code, _) = fastPostJson("${SessionManager.CLOUDFLARE_API_URL}/api/watch/message", payload)
            return@withContext code in 200..299
        } catch (e: Exception) {
            return@withContext false
        }
    }

    suspend fun manageWatchRoom(
        roomId: String,
        deviceId: String,
        action: String,
        targetDeviceId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("room_id", roomId)
                put("device_id", deviceId)
                put("action", action)
                if (targetDeviceId.isNotEmpty()) put("target_device_id", targetDeviceId)
            }
            val (code, _) = fastPostJson("${SessionManager.CLOUDFLARE_API_URL}/api/watch/manage", payload)
            return@withContext code in 200..299
        } catch (e: Exception) {
            return@withContext false
        }
    }

    private fun parseRoomStateJson(json: JSONObject): RoomSyncResult {
        val status = json.optString("status", "error")
        if (status != "success") {
            return RoomSyncResult(
                status = status,
                room = null,
                participants = emptyList(),
                messages = emptyList(),
                errorMessage = json.optString("message", "Error"),
                requirePassword = json.optBoolean("require_password", false)
            )
        }
        val r = json.optJSONObject("room") ?: return RoomSyncResult("error", null, emptyList(), emptyList())
        val roomObj = WatchRoom(
            roomId = r.optString("room_id"),
            roomCode = r.optString("room_code"),
            roomName = r.optString("room_name"),
            roomIcon = r.optString("room_icon", "⚽"),
            hostName = r.optString("host_name"),
            isHost = r.optBoolean("is_host", false),
            isPrivate = r.optBoolean("is_private", false),
            isLocked = r.optBoolean("is_locked", false),
            allowGuestsControl = r.optBoolean("allow_guests_control", false),
            contentType = r.optString("content_type", "LIVE"),
            streamId = r.optString("stream_id", "6171"),
            contentTitle = r.optString("content_title"),
            contentPoster = r.optString("content_poster"),
            streamUrl = r.optString("stream_url"),
            isPlaying = r.optBoolean("is_playing", true),
            playbackPositionMs = r.optLong("playback_position_ms", 0L),
            syncSeq = r.optInt("sync_seq", 1),
            inviteUrl = r.optString("invite_url")
        )
        val partsArr = json.optJSONArray("participants")
        val parts = mutableListOf<RoomParticipant>()
        if (partsArr != null) {
            for (i in 0 until partsArr.length()) {
                val p = partsArr.getJSONObject(i)
                parts.add(
                    RoomParticipant(
                        deviceId = p.optString("device_id"),
                        userName = p.optString("user_name"),
                        role = p.optString("role", "MEMBER"),
                        isMuted = p.optInt("is_muted", 1) == 1,
                        inVoice = p.optInt("in_voice", 0) == 1
                    )
                )
            }
        }
        val msgsArr = json.optJSONArray("messages")
        val msgs = mutableListOf<RoomChatMessage>()
        if (msgsArr != null) {
            for (i in 0 until msgsArr.length()) {
                val m = msgsArr.getJSONObject(i)
                msgs.add(
                    RoomChatMessage(
                        id = m.optLong("id"),
                        senderId = m.optString("sender_id"),
                        senderName = m.optString("sender_name"),
                        messageType = m.optString("message_type", "TEXT"),
                        content = m.optString("content"),
                        createdAt = m.optLong("created_at")
                    )
                )
            }
        }
        return RoomSyncResult("success", roomObj, parts, msgs)
    }
}