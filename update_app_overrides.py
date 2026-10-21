import os

PACKAGE_PATH = "app/src/main/java/com/primeott/app"

code = """package com.primeott.app.data.repository

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
import java.util.concurrent.TimeUnit

data class StreamOverride(
    val streamId: String,
    val customName: String,
    val customIcon: String,
    val customUrl: String,
    val status: String,
    val isDeleted: Boolean
)

object OttRepository {

    private val favoriteChannelIds = mutableSetOf<String>()
    private var isConfigLoaded = false

    private var cachedCategories = listOf<Category>()
    private val cachedChannelsMap = mutableMapOf<String, List<Channel>>()
    private var cachedMovies = listOf<VodItem>()
    private var cachedSeries = listOf<VodItem>()
    private var cachedMatches = listOf<MatchFixture>()
    private val streamOverridesMap = mutableMapOf<String, StreamOverride>()

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun cleanHtmlText(raw: String): String {
        return HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    private fun fastGet(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", SessionManager.CUSTOM_USER_AGENT)
            .header("Connection", "keep-alive")
            .build()
        okHttpClient.newCall(req).execute().use { response ->
            return response.body?.string() ?: ""
        }
    }

    // مزامنة التطبيق مع Cloudflare D1 (جلب السيرفر، الإعلان، المباريات، وتعديلات القنوات الفردية)
    suspend fun syncWithCloudflare() = withContext(Dispatchers.IO) {
        if (isConfigLoaded) return@withContext
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

                // قراءة التعديلات الفردية على القنوات (Stream Overrides)
                streamOverridesMap.clear()
                val ovArr = root.optJSONArray("overrides")
                if (ovArr != null) {
                    for (i in 0 until ovArr.length()) {
                        val o = ovArr.getJSONObject(i)
                        val sid = o.optString("stream_id")
                        streamOverridesMap[sid] = StreamOverride(
                            streamId = sid,
                            customName = o.optString("custom_name"),
                            customIcon = o.optString("custom_icon"),
                            customUrl = o.optString("custom_url"),
                            status = o.optString("status", "Online"),
                            isDeleted = o.optInt("is_deleted", 0) == 1
                        )
                    }
                }

                val matchesArr = root.optJSONArray("matches")
                if (matchesArr != null) {
                    val list = mutableListOf<MatchFixture>()
                    for (i in 0 until matchesArr.length()) {
                        val m = matchesArr.getJSONObject(i)
                        val streamId = m.optString("stream_id", "6171")
                        val chanName = m.optString("channel_name", "beIN Sports HD")
                        val hlsUrl = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.m3u8"
                        val tsUrl = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.ts"

                        list.add(
                            MatchFixture(
                                id = m.optString("id"),
                                homeTeam = m.optString("home_team"),
                                awayTeam = m.optString("away_team"),
                                matchTime = m.optString("match_time"),
                                league = m.optString("league"),
                                commentator = m.optString("commentator"),
                                status = m.optString("status", "LIVE"),
                                channel = Channel(streamId, "sport", chanName, "", listOf(hlsUrl, tsUrl))
                            )
                        )
                    }
                    cachedMatches = list
                }
                isConfigLoaded = true
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
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("${SessionManager.CLOUDFLARE_API_URL}/api/app/activate")
                .post(body)
                .build()

            okHttpClient.newCall(req).execute().use { res ->
                val respStr = res.body?.string() ?: "{}"
                val json = JSONObject(respStr)
                if (res.isSuccessful && json.optString("status") == "success") {
                    return@withContext Pair(true, json.optString("expire_date", "2027-01-01"))
                } else {
                    return@withContext Pair(false, json.optString("message", "تعذر التفعيل"))
                }
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "خطأ في الاتصال بالسيرفر")
        }
    }

    suspend fun fetchLiveCategories(): List<Category> = withContext(Dispatchers.IO) {
        syncWithCloudflare()
        if (cachedCategories.isNotEmpty()) return@withContext cachedCategories
        try {
            val url = "${SessionManager.XTREAM_BASE_URL}/player_api.php?username=${SessionManager.XTREAM_USER}&password=${SessionManager.XTREAM_PASS}&action=get_live_categories"
            val arr = JSONArray(fastGet(url))
            val list = ArrayList<Category>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    Category(
                        id = obj.optString("category_id"),
                        name = cleanHtmlText(obj.optString("category_name"))
                    )
                )
            }
            if (list.isNotEmpty()) {
                cachedCategories = list
                return@withContext list
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext emptyList()
    }

    // جلب القنوات مع تطبيق تعديلات لوحة التحكم (تغيير الاسم، اللوغو، الرابط، أو الحذف/Offline)
    suspend fun fetchChannelsByCategory(categoryId: String): List<Channel> = withContext(Dispatchers.IO) {
        syncWithCloudflare()
        try {
            val url = "${SessionManager.XTREAM_BASE_URL}/player_api.php?username=${SessionManager.XTREAM_USER}&password=${SessionManager.XTREAM_PASS}&action=get_live_streams&category_id=$categoryId"
            val arr = JSONArray(fastGet(url))
            val list = ArrayList<Channel>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val streamId = obj.optString("stream_id")
                val ov = streamOverridesMap[streamId]

                // إذا قام الأدمن بحذف القناة أو جعلها Offline من اللوحة، لا نعرضها
                if (ov != null && (ov.isDeleted || ov.status == "Offline")) continue

                val finalName = if (!ov?.customName.isNullOrEmpty()) ov!!.customName else cleanHtmlText(obj.optString("name"))
                val finalIcon = if (!ov?.customIcon.isNullOrEmpty()) ov!!.customIcon else obj.optString("stream_icon")

                val defaultHls = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.m3u8"
                val defaultTs = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.ts"

                val urls = if (!ov?.customUrl.isNullOrEmpty()) {
                    listOf(ov!!.customUrl, defaultHls, defaultTs)
                } else {
                    listOf(defaultHls, defaultTs)
                }

                list.add(
                    Channel(
                        id = streamId,
                        categoryId = categoryId,
                        name = finalName,
                        logoUrl = finalIcon,
                        streamUrls = urls,
                        isFavorite = favoriteChannelIds.contains(streamId)
                    )
                )
            }
            return@withContext list
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext emptyList()
    }

    suspend fun fetchMovies(): List<VodItem> = withContext(Dispatchers.IO) {
        syncWithCloudflare()
        if (cachedMovies.isNotEmpty()) return@withContext cachedMovies
        try {
            val url = "${SessionManager.XTREAM_BASE_URL}/player_api.php?username=${SessionManager.XTREAM_USER}&password=${SessionManager.XTREAM_PASS}&action=get_vod_streams"
            val arr = JSONArray(fastGet(url))
            val limit = minOf(arr.length(), 80)
            val list = ArrayList<VodItem>(limit)
            for (i in 0 until limit) {
                val obj = arr.getJSONObject(i)
                val streamId = obj.optString("stream_id")
                val ext = obj.optString("container_extension", "mp4")
                val movieUrl = "${SessionManager.XTREAM_BASE_URL}/movie/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.$ext"
                list.add(
                    VodItem(
                        id = streamId,
                        title = cleanHtmlText(obj.optString("name")),
                        category = "فيلم",
                        rating = obj.optString("rating", "8.0").take(3),
                        year = "HD",
                        posterUrl = obj.optString("stream_icon"),
                        streamUrls = listOf(movieUrl),
                        isSeries = false
                    )
                )
            }
            if (list.isNotEmpty()) {
                cachedMovies = list
                return@withContext list
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext emptyList()
    }

    // جلب المسلسلات مع جلب رابط أول حلقة حقيقية عند الضغط
    suspend fun fetchSeries(): List<VodItem> = withContext(Dispatchers.IO) {
        syncWithCloudflare()
        if (cachedSeries.isNotEmpty()) return@withContext cachedSeries
        try {
            val url = "${SessionManager.XTREAM_BASE_URL}/player_api.php?username=${SessionManager.XTREAM_USER}&password=${SessionManager.XTREAM_PASS}&action=get_series"
            val arr = JSONArray(fastGet(url))
            val limit = minOf(arr.length(), 80)
            val list = ArrayList<VodItem>(limit)
            for (i in 0 until limit) {
                val obj = arr.getJSONObject(i)
                val seriesId = obj.optString("series_id")
                list.add(
                    VodItem(
                        id = seriesId,
                        title = cleanHtmlText(obj.optString("name")),
                        category = "مسلسل",
                        rating = obj.optString("rating", "8.5").take(3),
                        year = "VIP",
                        posterUrl = obj.optString("cover"),
                        streamUrls = listOf("SERIES_ID:$seriesId"),
                        isSeries = true
                    )
                )
            }
            if (list.isNotEmpty()) {
                cachedSeries = list
                return@withContext list
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext emptyList()
    }

    suspend fun fetchMatchesFromCloudflare(): List<MatchFixture> = withContext(Dispatchers.IO) {
        isConfigLoaded = false
        syncWithCloudflare()
        return@withContext cachedMatches
    }

    fun toggleFavorite(channelId: String) {
        if (favoriteChannelIds.contains(channelId)) favoriteChannelIds.remove(channelId)
        else favoriteChannelIds.add(channelId)
    }
}
"""

with open(f"{PACKAGE_PATH}/data/repository/OttRepository.kt", "w", encoding="utf-8") as f:
    f.write(code.strip() + "\n")

print("✅ تم تحديث التطبيق لدعم تعديلات كل قناة على حدة (Stream Overrides)!")
