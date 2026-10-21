import os

PKG = "app/src/main/java/com/primeott/app"
RES = "app/src/main/res"

files = {
    f"{RES}/anim/fade_in.xml": """<?xml version="1.0" encoding="utf-8"?>
<alpha xmlns:android="http://schemas.android.com/apk/res/android"
    android:duration="220"
    android:fromAlpha="0.0"
    android:toAlpha="1.0" />
""",

    f"{RES}/anim/fade_out.xml": """<?xml version="1.0" encoding="utf-8"?>
<alpha xmlns:android="http://schemas.android.com/apk/res/android"
    android:duration="180"
    android:fromAlpha="1.0"
    android:toAlpha="0.0" />
""",

    "app/src/main/AndroidManifest.xml": """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.primeott.app">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

    <application
        android:allowBackup="true"
        android:label="Prime OTT"
        android:supportsRtl="true"
        android:usesCleartextTraffic="true"
        android:theme="@style/Theme.MaterialComponents.DayNight.NoActionBar">

        <activity
            android:name=".presentation.onboarding.SplashActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:windowSoftInputMode="adjustPan">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="primeott" android:host="watch" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data
                    android:scheme="https"
                    android:host="prime-ott-panel.prime-ott-stream.workers.dev"
                    android:pathPrefix="/join/" />
            </intent-filter>
        </activity>

        <activity
            android:name=".presentation.detail.ContentDetailActivity"
            android:exported="false" />

        <activity
            android:name=".presentation.watch.WatchRoomActivity"
            android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout"
            android:exported="false"
            android:windowSoftInputMode="adjustResize" />

        <activity
            android:name=".presentation.player.PlayerActivity"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:exported="false"
            android:screenOrientation="sensorLandscape" />
    </application>
</manifest>
""",

    f"{PKG}/presentation/common/UiUtils.kt": """package com.primeott.app.presentation.common

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import java.util.Locale

object AppStrings {
    fun get(context: Context, ar: String, fr: String, en: String): String {
        return when (SessionManager.getLanguage(context)) {
            "fr" -> fr
            "en" -> en
            else -> ar
        }
    }
}

object LocaleHelper {
    fun applyLocale(context: Context): Context {
        val lang = SessionManager.getLanguage(context)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    fun applyLayoutDirection(activity: Activity) {
        val lang = SessionManager.getLanguage(activity)
        activity.window.decorView.layoutDirection =
            if (lang == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }
}

object AnimUtils {
    private fun isAnimationEnabled(context: Context): Boolean {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
        return scale > 0f
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachPressBounce(view: View) {
        if (!isAnimationEnabled(view.context)) return
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(130)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
            }
            false
        }
    }

    fun animateViewEntry(view: View) {
        if (!isAnimationEnabled(view.context)) return
        view.alpha = 0f
        view.translationY = 20f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun applyActivityTransition(activity: Activity) {
        if (!isAnimationEnabled(activity)) return
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}
""",

    f"{PKG}/data/remote/SessionManager.kt": """package com.primeott.app.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

object SessionManager {
    private const val PREF_NAME = "prime_ott_secure_prefs"
    private const val KEY_TOKEN = "session_token"
    private const val KEY_EXPIRE = "expire_date"
    private const val KEY_LANG = "app_language"
    private const val KEY_ONBOARDED = "onboarding_done_v3"
    private const val KEY_USER_NAME = "user_display_name"
    private const val KEY_FAV_IDS = "favorite_stream_ids"
    private const val KEY_RECENT_IDS = "recent_stream_ids"

    const val CURRENT_APP_VERSION = 3
    const val CLOUDFLARE_API_URL = "https://prime-ott-panel.prime-ott-stream.workers.dev"

    var XTREAM_BASE_URL = "http://kwick.cc:8080"
    var XTREAM_USER = "Harry123"
    var XTREAM_PASS = "Alicea"

    var AD_URL = "https://www.google.com"
    var ADS_ENABLED = true
    var AD_SECONDS = 10
    var BANNER_BADGE = "🔥 قمة الجولة مباشرة"
    var BANNER_TITLE = "ريال مدريد VS برشلونة - beIN 1 FHD"
    var BANNER_STREAM_ID = "6171"
    var ANNOUNCEMENT_TEXT = ""
    var REMOTE_UPDATE_VERSION = 3
    var REMOTE_UPDATE_URL = ""
    var REMOTE_UPDATE_MSG = ""
    var LIVEKIT_URL = ""

    const val CUSTOM_USER_AGENT = "IPTVSmartersPlayer"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isOnboardingCompleted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDED, false)

    fun setOnboardingCompleted(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDED, done).apply()
    }

    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANG, "ar") ?: "ar"

    fun setLanguage(context: Context, langCode: String) {
        prefs(context).edit().putString(KEY_LANG, langCode).apply()
    }

    fun getUserName(context: Context): String {
        val saved = prefs(context).getString(KEY_USER_NAME, "") ?: ""
        return if (saved.isNotBlank()) saved else "مشاهد Prime"
    }

    fun setUserName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_USER_NAME, name.trim()).apply()
    }

    fun saveActivation(context: Context, token: String, expireDate: String) {
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_EXPIRE, expireDate)
            .apply()
    }

    fun clearSession(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_EXPIRE)
            .apply()
    }

    fun getToken(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "") ?: ""

    fun getExpireDate(context: Context): String =
        prefs(context).getString(KEY_EXPIRE, "وضع مجاني / تجريبي") ?: "وضع مجاني"

    fun getFavoriteIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FAV_IDS, emptySet()) ?: emptySet()

    fun saveFavoriteIds(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_FAV_IDS, ids.toSet()).apply()
    }

    fun addRecentChannel(context: Context, channelId: String, name: String, streamUrl: String) {
        val current = prefs(context).getString(KEY_RECENT_IDS, "") ?: ""
        val entry = "$channelId|$name|$streamUrl"
        val items = current.split(";;").filter { it.isNotBlank() && !it.startsWith("$channelId|") }.toMutableList()
        items.add(0, entry)
        val trimmed = items.take(12).joinToString(";;")
        prefs(context).edit().putString(KEY_RECENT_IDS, trimmed).apply()
    }

    fun getRecentEntries(context: Context): List<Triple<String, String, String>> {
        val raw = prefs(context).getString(KEY_RECENT_IDS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(";;").mapNotNull { part ->
            val p = part.split("|")
            if (p.size >= 3) Triple(p[0], p[1], p[2]) else null
        }
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEV"
    }
}
""",

    f"{PKG}/domain/model/Models.kt": """package com.primeott.app.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Category(
    val id: String,
    val name: String
) : Parcelable

@Parcelize
data class Channel(
    val id: String,
    val categoryId: String,
    val name: String,
    val logoUrl: String,
    val streamUrls: List<String>,
    val isFavorite: Boolean = false
) : Parcelable

@Parcelize
data class VodItem(
    val id: String,
    val title: String,
    val category: String,
    val rating: String,
    val year: String,
    val posterUrl: String,
    val streamUrls: List<String>,
    val isSeries: Boolean = false,
    val description: String = "محتوى ترفيهي عالي الدقة متوفر على سيرفر Prime OTT."
) : Parcelable

@Parcelize
data class MatchFixture(
    val id: String,
    val homeTeam: String,
    val awayTeam: String,
    val matchTime: String,
    val league: String,
    val commentator: String,
    val status: String,
    val channel: Channel,
    val homeLogo: String = "",
    val awayLogo: String = "",
    val homeScore: String = "-",
    val awayScore: String = "-",
    val liveMinute: String = "",
    val stadium: String = "ملعب المباراة",
    val dayTab: String = "TODAY",
    val leagueLogo: String = "",
    val isFavoriteMatch: Boolean = false
) : Parcelable

@Parcelize
data class WatchRoom(
    val roomId: String,
    val roomCode: String,
    val roomName: String,
    val roomIcon: String,
    val hostName: String,
    val isHost: Boolean = false,
    val isPrivate: Boolean = false,
    val isLocked: Boolean = false,
    val allowGuestsControl: Boolean = false,
    val contentType: String = "LIVE",
    val streamId: String = "6171",
    val contentTitle: String = "",
    val contentPoster: String = "",
    val streamUrl: String = "",
    val isPlaying: Boolean = true,
    val playbackPositionMs: Long = 0L,
    val syncSeq: Int = 1,
    val participantCount: Int = 1,
    val inviteUrl: String = ""
) : Parcelable

data class RoomParticipant(
    val deviceId: String,
    val userName: String,
    val role: String,
    val isMuted: Boolean,
    val inVoice: Boolean
)

data class RoomChatMessage(
    val id: Long,
    val senderId: String,
    val senderName: String,
    val messageType: String,
    val content: String,
    val createdAt: Long
)
""",

    f"{PKG}/data/repository/OttRepository.kt": """package com.primeott.app.data.repository

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
import java.util.concurrent.TimeUnit

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

object OttRepository {

    private val favoriteChannelIds = mutableSetOf<String>()
    private val favoriteMatchIds = mutableSetOf<String>()

    private var cachedCategories = listOf<Category>()
    private var cachedMovies = listOf<VodItem>()
    private var cachedSeries = listOf<VodItem>()
    private var cachedMatches = listOf<MatchFixture>()
    private val streamOverridesMap = mutableMapOf<String, StreamOverride>()

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

    private fun fastPostJson(url: String, payload: JSONObject): Pair<Int, JSONObject> {
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        okHttpClient.newCall(req).execute().use { res ->
            val str = res.body?.string() ?: "{}"
            val json = try { JSONObject(str) } catch (e: Exception) { JSONObject() }
            return Pair(res.code, json)
        }
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
                    SessionManager.ANNOUNCEMENT_TEXT = rc.optString("announcement", "")
                    SessionManager.REMOTE_UPDATE_VERSION = rc.optInt("update_version", 3)
                    SessionManager.REMOTE_UPDATE_URL = rc.optString("update_url", "")
                    SessionManager.REMOTE_UPDATE_MSG = rc.optString("update_message", "")
                    SessionManager.LIVEKIT_URL = rc.optString("livekit_url", "")
                }

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
                                league = m.optString("league", "بطولة كروية"),
                                commentator = m.optString("commentator", "بث مباشر"),
                                status = m.optString("status", "UPCOMING"),
                                channel = Channel(streamId, "sport", chanName, "", listOf(hlsMain, tsMain, hlsAlt)),
                                homeLogo = m.optString("home_logo", ""),
                                awayLogo = m.optString("away_logo", ""),
                                homeScore = m.optString("home_score", "-"),
                                awayScore = m.optString("away_score", "-"),
                                liveMinute = m.optString("live_minute", ""),
                                stadium = m.optString("stadium", "ملعب المباراة"),
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
            return@withContext Pair(false, json.optString("message", "كود التفعيل غير صالح"))
        } catch (e: Exception) {
            return@withContext Pair(false, "خطأ في الاتصال بالخادم")
        }
    }

    suspend fun fetchLiveCategories(): List<Category> = withContext(Dispatchers.IO) {
        if (cachedCategories.isNotEmpty()) return@withContext cachedCategories
        syncWithCloudflare()
        try {
            val url = "${SessionManager.XTREAM_BASE_URL}/player_api.php?username=${SessionManager.XTREAM_USER}&password=${SessionManager.XTREAM_PASS}&action=get_live_categories"
            val arr = JSONArray(fastGet(url))
            val list = ArrayList<Category>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Category(obj.optString("category_id"), cleanHtmlText(obj.optString("category_name"))))
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

    suspend fun fetchChannelsByCategory(categoryId: String): List<Channel> = withContext(Dispatchers.IO) {
        try {
            val url = "${SessionManager.XTREAM_BASE_URL}/player_api.php?username=${SessionManager.XTREAM_USER}&password=${SessionManager.XTREAM_PASS}&action=get_live_streams&category_id=$categoryId"
            val arr = JSONArray(fastGet(url))
            val list = ArrayList<Channel>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val streamId = obj.optString("stream_id")
                val ov = streamOverridesMap[streamId]
                if (ov != null && (ov.isDeleted || ov.status == "Offline")) continue

                val finalName = if (!ov?.customName.isNullOrEmpty()) ov!!.customName else cleanHtmlText(obj.optString("name"))
                val finalIcon = if (!ov?.customIcon.isNullOrEmpty()) ov!!.customIcon else obj.optString("stream_icon")

                val defaultHls = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.m3u8"
                val defaultTs = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.ts"
                val urls = if (!ov?.customUrl.isNullOrEmpty()) listOf(ov!!.customUrl, defaultHls, defaultTs) else listOf(defaultHls, defaultTs)

                list.add(Channel(streamId, categoryId, finalName, finalIcon, urls, favoriteChannelIds.contains(streamId)))
            }
            return@withContext list
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext emptyList()
    }

    suspend fun fetchMovies(): List<VodItem> = withContext(Dispatchers.IO) {
        if (cachedMovies.isNotEmpty()) return@withContext cachedMovies
        syncWithCloudflare()
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
                        category = "فيلم سينمائي",
                        rating = obj.optString("rating", "8.2").take(3),
                        year = "FHD",
                        posterUrl = obj.optString("stream_icon"),
                        streamUrls = listOf(movieUrl),
                        isSeries = false,
                        description = "مشاهدة مباشرة بجودة عالية عبر مكتبة أفلام Prime OTT مع دعم المشاهدة الجماعية مع الأصدقاء."
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

    suspend fun fetchSeries(): List<VodItem> = withContext(Dispatchers.IO) {
        if (cachedSeries.isNotEmpty()) return@withContext cachedSeries
        syncWithCloudflare()
        try {
            val url = "${SessionManager.XTREAM_BASE_URL}/player_api.php?username=${SessionManager.XTREAM_USER}&password=${SessionManager.XTREAM_PASS}&action=get_series"
            val arr = JSONArray(fastGet(url))
            val limit = minOf(arr.length(), 80)
            val list = ArrayList<VodItem>(limit)
            for (i in 0 until limit) {
                val obj = arr.getJSONObject(i)
                val seriesId = obj.optString("series_id")
                val plot = cleanHtmlText(obj.optString("plot", "مسلسل درامي متكامل متوفر بجودة عالية."))
                list.add(
                    VodItem(
                        id = seriesId,
                        title = cleanHtmlText(obj.optString("name")),
                        category = cleanHtmlText(obj.optString("genre", "مسلسل")),
                        rating = obj.optString("rating", "8.5").take(3),
                        year = obj.optString("releaseDate", "VIP").take(4).ifEmpty { "VIP" },
                        posterUrl = obj.optString("cover"),
                        streamUrls = listOf("${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6171.m3u8"),
                        isSeries = true,
                        description = plot.ifEmpty { "مسلسل متوفر بجودة عالية على مكتبة Prime OTT." }
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
            return@withContext Pair(false, json.optString("message", "تعذر إنشاء الغرفة"))
        } catch (e: Exception) {
            return@withContext Pair(false, "خطأ في الاتصال بالسيرفر")
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
            return@withContext RoomSyncResult("error", null, emptyList(), emptyList(), "تعذر الاتصال بالغرفة")
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

    suspend fun fetchVoiceToken(
        roomId: String,
        deviceId: String,
        userName: String
    ): JSONObject = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("room_id", roomId)
                put("device_id", deviceId)
                put("user_name", userName)
            }
            val (_, json) = fastPostJson("${SessionManager.CLOUDFLARE_API_URL}/api/watch/voice-token", payload)
            return@withContext json
        } catch (e: Exception) {
            return@withContext JSONObject().put("status", "error")
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
                errorMessage = json.optString("message", "تعذر الانضمام للغرفة"),
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
""",

    f"{RES}/layout/activity_splash.xml": """<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0B0D13"
    android:padding="24dp">

    <LinearLayout
        android:id="@+id/splashBrandContainer"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center"
        android:orientation="vertical">

        <TextView
            android:id="@+id/tvSplashLogoIcon"
            android:layout_width="86dp"
            android:layout_height="86dp"
            android:background="#E50914"
            android:gravity="center"
            android:text="▶"
            android:textColor="#FFFFFF"
            android:textSize="40sp"
            android:textStyle="bold" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="PRIME OTT"
            android:textColor="#FFFFFF"
            android:textSize="28sp"
            android:textStyle="bold" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="6dp"
            android:text="LIVE TV • MATCH CENTER • WATCH TOGETHER"
            android:textColor="#94A3B8"
            android:textSize="11sp"
            android:textStyle="bold" />
    </LinearLayout>

    <ScrollView
        android:id="@+id/onboardingScroll"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_horizontal"
            android:orientation="vertical"
            android:paddingVertical="20dp">

            <TextView
                android:id="@+id/tvOnboardTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="مرحباً بك في Prime OTT"
                android:textColor="#FFFFFF"
                android:textSize="22sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tvOnboardSub"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:layout_marginBottom="20dp"
                android:gravity="center"
                android:text="اختر لغتك المفضلة وأكمل الإعداد السريع للبدء"
                android:textColor="#94A3B8"
                android:textSize="13sp" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:weightSum="3"
                android:layout_marginBottom="18dp">

                <Button
                    android:id="@+id/btnLangAr"
                    android:layout_width="0dp"
                    android:layout_height="46dp"
                    android:layout_weight="1"
                    android:layout_marginEnd="6dp"
                    android:backgroundTint="#E50914"
                    android:text="العربية"
                    android:textColor="#FFFFFF" />

                <Button
                    android:id="@+id/btnLangFr"
                    android:layout_width="0dp"
                    android:layout_height="46dp"
                    android:layout_weight="1"
                    android:layout_marginEnd="6dp"
                    android:backgroundTint="#181C26"
                    android:text="Français"
                    android:textColor="#FFFFFF" />

                <Button
                    android:id="@+id/btnLangEn"
                    android:layout_width="0dp"
                    android:layout_height="46dp"
                    android:layout_weight="1"
                    android:backgroundTint="#181C26"
                    android:text="English"
                    android:textColor="#FFFFFF" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:background="#151A26"
                android:orientation="vertical"
                android:padding="16dp"
                android:layout_marginBottom="14dp">

                <TextView
                    android:id="@+id/tvNameLabel"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="👤 اسمك في غرف المشاهدة الجماعية (Watch Together)"
                    android:textColor="#FFFFFF"
                    android:textSize="13sp"
                    android:textStyle="bold" />

                <EditText
                    android:id="@+id/etUserDisplayName"
                    android:layout_width="match_parent"
                    android:layout_height="44dp"
                    android:layout_marginTop="8dp"
                    android:background="#0B0D13"
                    android:hint="مثال: عادل / Adel"
                    android:paddingHorizontal="12dp"
                    android:singleLine="true"
                    android:textColor="#FFFFFF"
                    android:textColorHint="#64748B" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:background="#151A26"
                android:orientation="vertical"
                android:padding="16dp"
                android:layout_marginBottom="18dp">

                <TextView
                    android:id="@+id/tvCodeLabel"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="🔑 كود تفعيل الاشتراك VIP (اختياري)"
                    android:textColor="#FFFFFF"
                    android:textSize="13sp"
                    android:textStyle="bold" />

                <EditText
                    android:id="@+id/etOnboardCode"
                    android:layout_width="match_parent"
                    android:layout_height="44dp"
                    android:layout_marginTop="8dp"
                    android:background="#0B0D13"
                    android:hint="PRIME-2026-VIP"
                    android:paddingHorizontal="12dp"
                    android:singleLine="true"
                    android:textColor="#FFFFFF"
                    android:textColorHint="#64748B" />

                <TextView
                    android:id="@+id/tvAuthStatusMsg"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="6dp"
                    android:textColor="#10B981"
                    android:visibility="gone" />
            </LinearLayout>

            <Button
                android:id="@+id/btnFinishSetup"
                android:layout_width="match_parent"
                android:layout_height="50dp"
                android:backgroundTint="#E50914"
                android:text="🚀 حفظ والانتقال إلى الصفحة الرئيسية"
                android:textColor="#FFFFFF"
                android:textStyle="bold" />

            <Button
                android:id="@+id/btnSkipGuest"
                android:layout_width="match_parent"
                android:layout_height="44dp"
                android:layout_marginTop="8dp"
                android:backgroundTint="#1E2433"
                android:text="المتابعة مباشرة بدون كود"
                android:textColor="#CBD5E1" />
        </LinearLayout>
    </ScrollView>
</FrameLayout>
""",

    f"{RES}/layout/activity_content_detail.xml": """<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0B0D13">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="260dp">

            <ImageView
                android:id="@+id/ivDetailCover"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:scaleType="centerCrop"
                android:src="@android:drawable/ic_media_play" />

            <Button
                android:id="@+id/btnDetailBack"
                android:layout_width="wrap_content"
                android:layout_height="38dp"
                android:layout_margin="14dp"
                android:backgroundTint="#99151A26"
                android:text="🔙 رجوع"
                android:textColor="#FFFFFF" />
        </FrameLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="18dp">

            <TextView
                android:id="@+id/tvDetailMetaBadge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:background="#3B0D14"
                android:paddingHorizontal="10dp"
                android:paddingVertical="4dp"
                android:text="FHD • ★ 8.5"
                android:textColor="#FF4757"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tvDetailTitle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="10dp"
                android:textColor="#FFFFFF"
                android:textSize="20sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tvDetailDescription"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:textColor="#94A3B8"
                android:textSize="13sp" />

            <Button
                android:id="@+id/btnDetailPlayNow"
                android:layout_width="match_parent"
                android:layout_height="48dp"
                android:layout_marginTop="18dp"
                android:backgroundTint="#E50914"
                android:text="▶ تشغيل ومشاهدة الآن"
                android:textColor="#FFFFFF"
                android:textStyle="bold" />

            <Button
                android:id="@+id/btnDetailWatchTogether"
                android:layout_width="match_parent"
                android:layout_height="48dp"
                android:layout_marginTop="8dp"
                android:backgroundTint="#2563EB"
                android:text="👥 مشاهدة مع الأصدقاء (إنشاء غرفة Watch Together)"
                android:textColor="#FFFFFF"
                android:textStyle="bold" />

            <Button
                android:id="@+id/btnDetailFavorite"
                android:layout_width="match_parent"
                android:layout_height="44dp"
                android:layout_marginTop="8dp"
                android:backgroundTint="#181C26"
                android:text="☆ إضافة إلى المفضلة"
                android:textColor="#FFD700" />
        </LinearLayout>
    </LinearLayout>
</ScrollView>
""",

    f"{RES}/layout/item_watch_room.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="10dp"
    android:background="#151A26"
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:padding="14dp">

    <TextView
        android:id="@+id/tvRoomIcon"
        android:layout_width="44dp"
        android:layout_height="44dp"
        android:background="#1E2433"
        android:gravity="center"
        android:text="⚽"
        android:textSize="20sp" />

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginHorizontal="12dp"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/tvRoomTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="#FFFFFF"
            android:textSize="14sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tvRoomContent"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textColor="#38BDF8"
            android:textSize="12sp" />

        <TextView
            android:id="@+id/tvRoomMeta"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textColor="#94A3B8"
            android:textSize="11sp" />
    </LinearLayout>

    <Button
        android:id="@+id/btnJoinRoomItem"
        android:layout_width="wrap_content"
        android:layout_height="38dp"
        android:backgroundTint="#E50914"
        android:text="انضمام ▶"
        android:textColor="#FFFFFF"
        android:textSize="12sp"
        android:textStyle="bold" />
</LinearLayout>
""",

    f"{RES}/layout/fragment_watch_together.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0B0D13"
    android:orientation="vertical"
    android:padding="14dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="#151A26"
        android:orientation="vertical"
        android:padding="16dp"
        android:layout_marginBottom="14dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="👥 Watch Together — المشاهدة الجماعية"
            android:textColor="#FFFFFF"
            android:textSize="17sp"
            android:textStyle="bold" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="شاهد المباريات والأفلام مع أصدقائك في غرفة مشتركة مع دردشة صوتية وكتابية مباشرة!"
            android:textColor="#94A3B8"
            android:textSize="12sp" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:orientation="horizontal"
            android:weightSum="2">

            <Button
                android:id="@+id/btnCreateNewRoom"
                android:layout_width="0dp"
                android:layout_height="46dp"
                android:layout_marginEnd="6dp"
                android:layout_weight="1"
                android:backgroundTint="#E50914"
                android:text="➕ إنشاء غرفة جديدة"
                android:textColor="#FFFFFF"
                android:textSize="12sp"
                android:textStyle="bold" />

            <Button
                android:id="@+id/btnJoinByCodeDialog"
                android:layout_width="0dp"
                android:layout_height="46dp"
                android:layout_weight="1"
                android:backgroundTint="#2563EB"
                android:text="🔑 انضمام بكود / رابط"
                android:textColor="#FFFFFF"
                android:textSize="12sp"
                android:textStyle="bold" />
        </LinearLayout>
    </LinearLayout>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:layout_marginBottom="10dp">

        <Button
            android:id="@+id/btnRefreshRooms"
            android:layout_width="wrap_content"
            android:layout_height="34dp"
            android:backgroundTint="#1E2433"
            android:text="🔄 تحديث"
            android:textColor="#FFFFFF"
            android:textSize="11sp" />

        <TextView
            android:id="@+id/tvRoomsCountHeader"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="end"
            android:text="🌍 الغرف النشطة المتاحة حالياً"
            android:textColor="#FFFFFF"
            android:textSize="14sp"
            android:textStyle="bold" />
    </LinearLayout>

    <TextView
        android:id="@+id/tvEmptyRoomsState"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="#11151F"
        android:gravity="center"
        android:padding="24dp"
        android:text="لا توجد غرف نشطة حالياً. اضغط على إنشاء غرفة جديدة وادع أصدقاءك!"
        android:textColor="#64748B"
        android:textSize="13sp"
        android:visibility="gone" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rvActiveRooms"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />
</LinearLayout>
""",

    f"{RES}/layout/item_chat_message.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="6dp"
    android:background="#151A26"
    android:orientation="vertical"
    android:padding="10dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/tvChatTime"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="#64748B"
            android:textSize="10sp" />

        <TextView
            android:id="@+id/tvChatSender"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="end"
            android:textColor="#38BDF8"
            android:textSize="12sp"
            android:textStyle="bold" />
    </LinearLayout>

    <TextView
        android:id="@+id/tvChatContent"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="2dp"
        android:textColor="#FFFFFF"
        android:textSize="13sp" />
</LinearLayout>
""",

    f"{RES}/layout/item_participant.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="6dp"
    android:background="#151A26"
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:padding="10dp">

    <Button
        android:id="@+id/btnKickParticipant"
        android:layout_width="wrap_content"
        android:layout_height="32dp"
        android:backgroundTint="#EF4444"
        android:text="🚫 طرد"
        android:textColor="#FFFFFF"
        android:textSize="11sp"
        android:visibility="gone" />

    <TextView
        android:id="@+id/tvPartMicState"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginHorizontal="8dp"
        android:text="🔇"
        android:textSize="15sp" />

    <TextView
        android:id="@+id/tvPartName"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:gravity="end"
        android:textColor="#FFFFFF"
        android:textSize="13sp"
        android:textStyle="bold" />
</LinearLayout>
""",

    f"{RES}/layout/activity_watch_room.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/watchRoomRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0B0D13"
    android:orientation="vertical">

    <LinearLayout
        android:id="@+id/watchTopHeader"
        android:layout_width="match_parent"
        android:layout_height="48dp"
        android:background="#11151F"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingHorizontal="8dp">

        <Button
            android:id="@+id/btnLeaveRoom"
            android:layout_width="wrap_content"
            android:layout_height="34dp"
            android:backgroundTint="#EF4444"
            android:text="🚪 مغادرة"
            android:textColor="#FFFFFF"
            android:textSize="11sp" />

        <Button
            android:id="@+id/btnShareInvite"
            android:layout_width="wrap_content"
            android:layout_height="34dp"
            android:layout_marginStart="6dp"
            android:backgroundTint="#2563EB"
            android:text="🔗 دعوة"
            android:textColor="#FFFFFF"
            android:textSize="11sp" />

        <Button
            android:id="@+id/btnHostManage"
            android:layout_width="wrap_content"
            android:layout_height="34dp"
            android:layout_marginStart="6dp"
            android:backgroundTint="#1E2433"
            android:text="⚙️ إدارة"
            android:textColor="#FFFFFF"
            android:textSize="11sp"
            android:visibility="gone" />

        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="end"
            android:orientation="vertical">

            <TextView
                android:id="@+id/tvWatchRoomName"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:maxLines="1"
                android:text="⚽ غرفة المشاهدة"
                android:textColor="#FFFFFF"
                android:textSize="12.5sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tvSyncStatusBadge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="🟢 متزامن • 👥 1"
                android:textColor="#10B981"
                android:textSize="10sp" />
        </LinearLayout>
    </LinearLayout>

    <FrameLayout
        android:id="@+id/videoPlayerContainer"
        android:layout_width="match_parent"
        android:layout_height="230dp"
        android:background="#000000">

        <androidx.media3.ui.PlayerView
            android:id="@+id/sharedPlayerView"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            app:resize_mode="fit"
            app:use_controller="true" />

        <FrameLayout
            android:id="@+id/reactionsOverlay"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:clickable="false" />

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="top|end"
            android:layout_margin="8dp"
            android:orientation="horizontal">

            <Button
                android:id="@+id/btnChangeRoomStream"
                android:layout_width="wrap_content"
                android:layout_height="32dp"
                android:layout_marginEnd="6dp"
                android:backgroundTint="#99151A26"
                android:text="📺 القناة"
                android:textColor="#FFFFFF"
                android:textSize="10sp" />

            <Button
                android:id="@+id/btnToggleFullscreen"
                android:layout_width="wrap_content"
                android:layout_height="32dp"
                android:backgroundTint="#99151A26"
                android:text="⛶ تكبير"
                android:textColor="#FFFFFF"
                android:textSize="10sp" />
        </LinearLayout>
    </FrameLayout>

    <LinearLayout
        android:id="@+id/socialControlBar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="#151A26"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:padding="6dp">

        <Button
            android:id="@+id/btnVoiceJoinToggle"
            android:layout_width="wrap_content"
            android:layout_height="36dp"
            android:backgroundTint="#10B981"
            android:text="🎙️ اتصال صوتي"
            android:textColor="#FFFFFF"
            android:textSize="11sp" />

        <Button
            android:id="@+id/btnMicMuteToggle"
            android:layout_width="wrap_content"
            android:layout_height="36dp"
            android:layout_marginStart="6dp"
            android:backgroundTint="#1E2433"
            android:text="🔇 كتم"
            android:textColor="#FFFFFF"
            android:textSize="11sp"
            android:visibility="gone" />

        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="end"
            android:orientation="horizontal">

            <TextView android:id="@+id/reactGoal" android:layout_width="32dp" android:layout_height="32dp" android:gravity="center" android:text="⚽" android:textSize="17sp" />
            <TextView android:id="@+id/reactFire" android:layout_width="32dp" android:layout_height="32dp" android:gravity="center" android:text="🔥" android:textSize="17sp" />
            <TextView android:id="@+id/reactHeart" android:layout_width="32dp" android:layout_height="32dp" android:gravity="center" android:text="❤️" android:textSize="17sp" />
            <TextView android:id="@+id/reactClap" android:layout_width="32dp" android:layout_height="32dp" android:gravity="center" android:text="👏" android:textSize="17sp" />
            <TextView android:id="@+id/reactLaugh" android:layout_width="32dp" android:layout_height="32dp" android:gravity="center" android:text="😂" android:textSize="17sp" />
            <TextView android:id="@+id/reactShock" android:layout_width="32dp" android:layout_height="32dp" android:gravity="center" android:text="😱" android:textSize="17sp" />
        </LinearLayout>
    </LinearLayout>

    <LinearLayout
        android:id="@+id/panelSwitchTabs"
        android:layout_width="match_parent"
        android:layout_height="36dp"
        android:background="#11151F"
        android:orientation="horizontal"
        android:weightSum="2">

        <TextView
            android:id="@+id/tabRoomParticipants"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="👥 المتواجدون (1)"
            android:textColor="#94A3B8"
            android:textSize="12sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tabRoomChat"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:background="#E50914"
            android:gravity="center"
            android:text="💬 الدردشة المباشرة"
            android:textColor="#FFFFFF"
            android:textSize="12sp"
            android:textStyle="bold" />
    </LinearLayout>

    <FrameLayout
        android:id="@+id/bottomSocialPanel"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:padding="8dp">

        <LinearLayout
            android:id="@+id/chatSectionContainer"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical">

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/rvRoomChat"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_weight="1" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="44dp"
                android:layout_marginTop="4dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <Button
                    android:id="@+id/btnSendChat"
                    android:layout_width="wrap_content"
                    android:layout_height="40dp"
                    android:backgroundTint="#E50914"
                    android:text="إرسال"
                    android:textColor="#FFFFFF"
                    android:textSize="12sp" />

                <EditText
                    android:id="@+id/etChatInput"
                    android:layout_width="0dp"
                    android:layout_height="40dp"
                    android:layout_marginStart="6dp"
                    android:layout_weight="1"
                    android:background="#151A26"
                    android:hint="اكتب رسالة لأصدقائك..."
                    android:paddingHorizontal="12dp"
                    android:singleLine="true"
                    android:textColor="#FFFFFF"
                    android:textColorHint="#64748B"
                    android:textSize="13sp" />
            </LinearLayout>
        </LinearLayout>

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/rvRoomParticipants"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:visibility="gone" />
    </FrameLayout>
</LinearLayout>
""",

    f"{RES}/layout/fragment_home.xml": """<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0B0D13"
    android:padding="14dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <TextView
            android:id="@+id/tvRemoteAnnouncement"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="12dp"
            android:background="#2610B981"
            android:padding="10dp"
            android:textColor="#34D399"
            android:textSize="13sp"
            android:textStyle="bold"
            android:visibility="gone" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="#151A26"
            android:orientation="vertical"
            android:padding="18dp">

            <TextView
                android:id="@+id/tvBannerBadge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:background="#3B0D14"
                android:paddingHorizontal="10dp"
                android:paddingVertical="3dp"
                android:text="🔥 قمة الجولة مباشرة"
                android:textColor="#FF4757"
                android:textSize="12sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tvBannerTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:text="ريال مدريد VS برشلونة - beIN 1 FHD"
                android:textColor="#FFFFFF"
                android:textSize="19sp"
                android:textStyle="bold" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="14dp"
                android:orientation="horizontal">

                <Button
                    android:id="@+id/btnWatchFeatured"
                    android:layout_width="wrap_content"
                    android:layout_height="42dp"
                    android:backgroundTint="#E50914"
                    android:text="▶ شاهد الآن"
                    android:textColor="#FFFFFF"
                    android:textSize="12sp"
                    android:textStyle="bold" />

                <Button
                    android:id="@+id/btnWatchTogetherFeatured"
                    android:layout_width="wrap_content"
                    android:layout_height="42dp"
                    android:layout_marginStart="8dp"
                    android:backgroundTint="#2563EB"
                    android:text="👥 شاهد مع أصدقائك"
                    android:textColor="#FFFFFF"
                    android:textSize="12sp"
                    android:textStyle="bold" />
            </LinearLayout>
        </LinearLayout>

        <TextView
            android:id="@+id/tvRecentHeader"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:layout_marginBottom="8dp"
            android:text="🕒 تابعته مؤخراً"
            android:textColor="#FFFFFF"
            android:textSize="15sp"
            android:textStyle="bold"
            android:visibility="gone" />

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/rvRecentWatched"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:visibility="gone" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:layout_marginBottom="8dp"
            android:text="📡 قنوات البث المباشر المميزة"
            android:textColor="#FFFFFF"
            android:textSize="15sp"
            android:textStyle="bold" />

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/rvQuickChannels"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:layout_marginBottom="8dp"
            android:text="🎬 أحدث الأفلام المختارة"
            android:textColor="#FFFFFF"
            android:textSize="15sp"
            android:textStyle="bold" />

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/rvHomeChannels"
            android:layout_width="match_parent"
            android:layout_height="230dp" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:layout_marginBottom="8dp"
            android:text="📺 أحدث المسلسلات الحصرية"
            android:textColor="#FFFFFF"
            android:textSize="15sp"
            android:textStyle="bold" />

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/rvHomeSeries"
            android:layout_width="match_parent"
            android:layout_height="230dp" />
    </LinearLayout>
</ScrollView>
""",

    f"{RES}/layout/fragment_settings.xml": """<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0B0D13"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginBottom="14dp"
            android:text="⚙️ الملف الشخصي والإعدادات"
            android:textColor="#FFFFFF"
            android:textSize="20sp"
            android:textStyle="bold" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="#151A26"
            android:orientation="vertical"
            android:padding="16dp"
            android:layout_marginBottom="14dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="👤 الاسم المستعار في غرف Watch Together"
                android:textColor="#FFFFFF"
                android:textSize="14sp"
                android:textStyle="bold" />

            <EditText
                android:id="@+id/etSettingsUserName"
                android:layout_width="match_parent"
                android:layout_height="44dp"
                android:layout_marginTop="8dp"
                android:background="#0B0D13"
                android:paddingHorizontal="12dp"
                android:textColor="#FFFFFF" />

            <Button
                android:id="@+id/btnSaveProfileName"
                android:layout_width="wrap_content"
                android:layout_height="40dp"
                android:layout_marginTop="8dp"
                android:backgroundTint="#10B981"
                android:text="💾 حفظ الاسم"
                android:textColor="#FFFFFF" />
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="#151A26"
            android:orientation="vertical"
            android:padding="16dp"
            android:layout_marginBottom="14dp">

            <TextView
                android:id="@+id/tvDeviceId"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textColor="#94A3B8"
                android:textSize="12sp" />

            <EditText
                android:id="@+id/etActivationCode"
                android:layout_width="match_parent"
                android:layout_height="44dp"
                android:layout_marginTop="10dp"
                android:background="#0B0D13"
                android:hint="أدخل كود التفعيل (PRIME-2026-VIP)"
                android:paddingHorizontal="12dp"
                android:textColor="#FFFFFF"
                android:textColorHint="#64748B" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="10dp"
                android:orientation="horizontal">

                <Button
                    android:id="@+id/btnActivateCode"
                    android:layout_width="0dp"
                    android:layout_height="44dp"
                    android:layout_weight="1"
                    android:backgroundTint="#E50914"
                    android:text="تفعيل الاشتراك الآن"
                    android:textColor="#FFFFFF" />

                <Button
                    android:id="@+id/btnLogoutSession"
                    android:layout_width="wrap_content"
                    android:layout_height="44dp"
                    android:layout_marginStart="8dp"
                    android:backgroundTint="#1E2433"
                    android:text="تسجيل الخروج"
                    android:textColor="#F87171" />
            </LinearLayout>
        </LinearLayout>

        <Button
            android:id="@+id/btnChangeAppLanguage"
            android:layout_width="match_parent"
            android:layout_height="44dp"
            android:backgroundTint="#1E2433"
            android:text="🌐 تغيير لغة التطبيق (العربية / Français / English)"
            android:textColor="#FFFFFF" />

        <Button
            android:id="@+id/btnCheckMicPermission"
            android:layout_width="match_parent"
            android:layout_height="44dp"
            android:layout_marginTop="8dp"
            android:backgroundTint="#1E2433"
            android:text="🎙️ فحص وتفعيل صلاحية الميكروفون للدردشة الصوتية"
            android:textColor="#38BDF8" />
    </LinearLayout>
</ScrollView>
""",

    f"{PKG}/presentation/onboarding/SplashActivity.kt": """package com.primeott.app.presentation.onboarding

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.primeott.app.MainActivity
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.ActivitySplashBinding
import com.primeott.app.presentation.common.AnimUtils
import com.primeott.app.presentation.common.LocaleHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var selectedLang = "ar"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLayoutDirection(this)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        OttRepository.initFavorites(this)
        selectedLang = SessionManager.getLanguage(this)

        binding.tvSplashLogoIcon.scaleX = 0.75f
        binding.tvSplashLogoIcon.scaleY = 0.75f
        binding.tvSplashLogoIcon.animate().scaleX(1.0f).scaleY(1.0f).setDuration(500).start()

        lifecycleScope.launch {
            OttRepository.syncWithCloudflare()
            delay(750)
            if (SessionManager.isOnboardingCompleted(this@SplashActivity)) {
                goToMain()
            } else {
                showOnboardingStep()
            }
        }
    }

    private fun showOnboardingStep() {
        binding.splashBrandContainer.visibility = View.GONE
        binding.onboardingScroll.visibility = View.VISIBLE
        AnimUtils.animateViewEntry(binding.onboardingScroll)

        binding.etUserDisplayName.setText(SessionManager.getUserName(this))
        updateLangButtonsUI()

        binding.btnLangAr.setOnClickListener { selectLang("ar") }
        binding.btnLangFr.setOnClickListener { selectLang("fr") }
        binding.btnLangEn.setOnClickListener { selectLang("en") }

        binding.btnFinishSetup.setOnClickListener {
            val userName = binding.etUserDisplayName.text.toString().trim().ifEmpty { "مشاهد Prime" }
            val code = binding.etOnboardCode.text.toString().trim()
            SessionManager.setUserName(this, userName)

            if (code.isNotEmpty()) {
                binding.btnFinishSetup.isEnabled = false
                binding.tvAuthStatusMsg.visibility = View.VISIBLE
                binding.tvAuthStatusMsg.text = "⏳ جاري التحقق من كود الاشتراك..."
                lifecycleScope.launch {
                    val (ok, msg) = OttRepository.activateSubscription(code, SessionManager.getDeviceId(this@SplashActivity))
                    binding.btnFinishSetup.isEnabled = true
                    if (ok) {
                        SessionManager.saveActivation(this@SplashActivity, "TOKEN_$code", msg)
                        SessionManager.setOnboardingCompleted(this@SplashActivity, true)
                        Toast.makeText(this@SplashActivity, "✅ تم تفعيل اشتراكك بنجاح!", Toast.LENGTH_SHORT).show()
                        goToMain()
                    } else {
                        binding.tvAuthStatusMsg.setTextColor(Color.parseColor("#FF4757"))
                        binding.tvAuthStatusMsg.text = msg
                    }
                }
            } else {
                SessionManager.setOnboardingCompleted(this, true)
                goToMain()
            }
        }

        binding.btnSkipGuest.setOnClickListener {
            val userName = binding.etUserDisplayName.text.toString().trim().ifEmpty { "مشاهد Prime" }
            SessionManager.setUserName(this, userName)
            SessionManager.setOnboardingCompleted(this, true)
            goToMain()
        }
    }

    private fun selectLang(lang: String) {
        selectedLang = lang
        SessionManager.setLanguage(this, lang)
        LocaleHelper.applyLayoutDirection(this)
        updateLangButtonsUI()
    }

    private fun updateLangButtonsUI() {
        val activeColor = Color.parseColor("#E50914")
        val inactiveColor = Color.parseColor("#181C26")
        binding.btnLangAr.setBackgroundColor(if (selectedLang == "ar") activeColor else inactiveColor)
        binding.btnLangFr.setBackgroundColor(if (selectedLang == "fr") activeColor else inactiveColor)
        binding.btnLangEn.setBackgroundColor(if (selectedLang == "en") activeColor else inactiveColor)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        AnimUtils.applyActivityTransition(this)
        finish()
    }
}
""",

    f"{PKG}/presentation/detail/ContentDetailActivity.kt": """package com.primeott.app.presentation.detail

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.ActivityContentDetailBinding
import com.primeott.app.domain.model.Channel
import com.primeott.app.domain.model.VodItem
import com.primeott.app.presentation.common.LocaleHelper
import com.primeott.app.presentation.player.PlayerActivity
import com.primeott.app.presentation.watch.WatchRoomActivity
import kotlinx.coroutines.launch

class ContentDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VOD = "extra_vod_item"
    }

    private lateinit var binding: ActivityContentDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLayoutDirection(this)
        binding = ActivityContentDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val item = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_VOD, VodItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_VOD)
        } ?: run {
            finish()
            return
        }

        binding.tvDetailTitle.text = item.title
        binding.tvDetailMetaBadge.text = "${item.category} • ${item.year} • ★ ${item.rating}"
        binding.tvDetailDescription.text = item.description

        if (item.posterUrl.isNotEmpty()) {
            Glide.with(this).load(item.posterUrl).centerCrop().into(binding.ivDetailCover)
        }

        updateFav(item.id)

        binding.btnDetailBack.setOnClickListener { finish() }

        binding.btnDetailPlayNow.setOnClickListener {
            val streamUrl = item.streamUrls.firstOrNull() ?: ""
            SessionManager.addRecentChannel(this, item.id, item.title, streamUrl)
            val ch = Channel(item.id, "vod", item.title, item.posterUrl, item.streamUrls)
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL, ch)
            })
        }

        binding.btnDetailWatchTogether.setOnClickListener {
            val streamUrl = item.streamUrls.firstOrNull() ?: ""
            lifecycleScope.launch {
                val (ok, codeOrErr) = OttRepository.createWatchRoom(
                    deviceId = SessionManager.getDeviceId(this@ContentDetailActivity),
                    userName = SessionManager.getUserName(this@ContentDetailActivity),
                    roomName = "مشاهدة: ${item.title.take(25)}",
                    roomIcon = "🎬",
                    isPrivate = false,
                    password = "",
                    allowGuestsControl = true,
                    contentType = "VOD",
                    streamId = item.id,
                    contentTitle = item.title,
                    contentPoster = item.posterUrl,
                    streamUrl = streamUrl
                )
                if (ok) {
                    startActivity(Intent(this@ContentDetailActivity, WatchRoomActivity::class.java).apply {
                        putExtra(WatchRoomActivity.EXTRA_ROOM_CODE, codeOrErr)
                    })
                } else {
                    Toast.makeText(this@ContentDetailActivity, codeOrErr, Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnDetailFavorite.setOnClickListener {
            OttRepository.toggleFavorite(item.id, this)
            updateFav(item.id)
        }
    }

    private fun updateFav(id: String) {
        val isFav = OttRepository.isChannelFavorite(id)
        binding.btnDetailFavorite.text = if (isFav) "★ مضاف إلى المفضلة" else "☆ إضافة إلى المفضلة"
    }
}
""",

    f"{PKG}/presentation/watch/VoiceChatManager.kt": """package com.primeott.app.presentation.watch

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.util.Base64
import kotlinx.coroutines.*

class VoiceChatManager(
    private val context: Context,
    private val onSendVoicePacket: (String) -> Unit,
    private val onStateChanged: (Boolean, Boolean, String) -> Unit
) {
    private var isConnected = false
    private var isMuted = true
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sampleRate = 8000

    @SuppressLint("MissingPermission")
    fun connectVoiceSession(liveKitUrl: String, liveKitToken: String) {
        if (isConnected) return
        isConnected = true
        isMuted = false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        try {
            val minOutBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(1600)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minOutBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().apply { play() }

            val minInBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(1600)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minInBuf)
            audioRecord?.startRecording()

            captureJob = scope.launch {
                val buffer = ByteArray(1600)
                while (isActive && isConnected) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0 && !isMuted) {
                        onSendVoicePacket(Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP))
                    }
                    delay(90)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        onStateChanged(true, isMuted, "🟢 متصل بالقناة الصوتية")
    }

    fun playIncomingVoicePacket(base64Pcm: String) {
        if (!isConnected) return
        scope.launch {
            try {
                val bytes = Base64.decode(base64Pcm, Base64.NO_WRAP)
                audioTrack?.write(bytes, 0, bytes.size)
            } catch (e: Exception) {}
        }
    }

    fun toggleMute(): Boolean {
        if (!isConnected) return true
        isMuted = !isMuted
        onStateChanged(isConnected, isMuted, if (isMuted) "🔇 الميكروفون مكتوم" else "🎙️ الميكروفون مفعل")
        return isMuted
    }

    fun disconnectVoice() {
        isConnected = false
        isMuted = true
        captureJob?.cancel()
        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null
        onStateChanged(false, true, "⚪ تم قطع الاتصال الصوتي")
    }
}
""",

    f"{PKG}/presentation/watch/WatchTogetherFragment.kt": """package com.primeott.app.presentation.watch

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.FragmentWatchTogetherBinding
import com.primeott.app.databinding.ItemWatchRoomBinding
import com.primeott.app.domain.model.WatchRoom
import kotlinx.coroutines.launch

class WatchTogetherFragment : Fragment() {

    private var _binding: FragmentWatchTogetherBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_watch_together, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentWatchTogetherBinding.bind(view)
        _binding = b

        b.rvActiveRooms.layoutManager = LinearLayoutManager(requireContext())
        b.btnCreateNewRoom.setOnClickListener { showCreateRoomDialog() }
        b.btnJoinByCodeDialog.setOnClickListener { showJoinByCodeDialog() }
        b.btnRefreshRooms.setOnClickListener { loadActiveRooms() }

        loadActiveRooms()
    }

    override fun onResume() {
        super.onResume()
        loadActiveRooms()
    }

    private fun loadActiveRooms() {
        val b = _binding ?: return
        b.tvRoomsCountHeader.text = "⏳ جاري البحث عن الغرف النشطة..."
        viewLifecycleOwner.lifecycleScope.launch {
            val rooms = OttRepository.fetchActiveWatchRooms()
            val safe = _binding ?: return@launch
            safe.tvRoomsCountHeader.text = "🌍 الغرف النشطة المتاحة (${rooms.size})"
            safe.tvEmptyRoomsState.visibility = if (rooms.isEmpty()) View.VISIBLE else View.GONE
            safe.rvActiveRooms.adapter = RoomsAdapter(rooms) { room ->
                if (room.isPrivate) promptPasswordAndOpenRoom(room.roomCode)
                else openWatchRoom(room.roomCode, "")
            }
        }
    }

    private fun showCreateRoomDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val etRoomName = EditText(ctx).apply {
            hint = "اسم الغرفة"
            setText("غرفة ${SessionManager.getUserName(ctx)}")
        }
        val contentChoices = listOf(
            Triple("6171", "beIN SPORTS 1 FHD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6171.m3u8"),
            Triple("6172", "beIN SPORTS 2 FHD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6172.m3u8"),
            Triple("6173", "beIN SPORTS 3 FHD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6173.m3u8"),
            Triple("6174", "SSC SPORTS 1 HD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6174.m3u8"),
            Triple("6175", "الجزائرية السادسة TV6 HD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6175.m3u8")
        )
        val spinnerContent = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, contentChoices.map { "📺 ${it.second}" })
        }
        val cbPrivate = CheckBox(ctx).apply { text = "🔒 غرفة خاصة بكلمة مرور" }
        val etPassword = EditText(ctx).apply {
            hint = "كلمة المرور"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            visibility = View.GONE
        }
        cbPrivate.setOnCheckedChangeListener { _, checked -> etPassword.visibility = if (checked) View.VISIBLE else View.GONE }
        val cbAllowGuests = CheckBox(ctx).apply { text = "🎮 السماح للأصدقاء بالتحكم في المشغل" }

        container.addView(etRoomName)
        container.addView(spinnerContent)
        container.addView(cbPrivate)
        container.addView(etPassword)
        container.addView(cbAllowGuests)

        AlertDialog.Builder(ctx)
            .setTitle("➕ إنشاء غرفة Watch Together")
            .setView(container)
            .setPositiveButton("🚀 إنشاء") { _, _ ->
                val chosen = contentChoices[spinnerContent.selectedItemPosition]
                viewLifecycleOwner.lifecycleScope.launch {
                    val (ok, codeOrMsg) = OttRepository.createWatchRoom(
                        deviceId = SessionManager.getDeviceId(ctx),
                        userName = SessionManager.getUserName(ctx),
                        roomName = etRoomName.text.toString().trim().ifEmpty { "غرفة مشاهدة" },
                        roomIcon = "⚽",
                        isPrivate = cbPrivate.isChecked,
                        password = etPassword.text.toString().trim(),
                        allowGuestsControl = cbAllowGuests.isChecked,
                        contentType = "LIVE",
                        streamId = chosen.first,
                        contentTitle = chosen.second,
                        contentPoster = "",
                        streamUrl = chosen.third
                    )
                    if (ok) openWatchRoom(codeOrMsg, etPassword.text.toString().trim())
                    else Toast.makeText(ctx, codeOrMsg, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showJoinByCodeDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val etCode = EditText(ctx).apply { hint = "كود الغرفة (مثال: PR92X1) أو رابط الدعوة" }
        val etPass = EditText(ctx).apply { hint = "كلمة المرور (إن وجدت)" }
        container.addView(etCode)
        container.addView(etPass)

        AlertDialog.Builder(ctx)
            .setTitle("🔑 الانضمام إلى غرفة")
            .setView(container)
            .setPositiveButton("انضمام") { _, _ ->
                val raw = etCode.text.toString().trim()
                if (raw.isNotEmpty()) openWatchRoom(raw, etPass.text.toString().trim())
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun promptPasswordAndOpenRoom(roomCode: String) {
        val ctx = requireContext()
        val etPass = EditText(ctx).apply { hint = "أدخل كلمة مرور الغرفة" }
        AlertDialog.Builder(ctx)
            .setTitle("🔒 غرفة خاصة")
            .setView(etPass)
            .setPositiveButton("دخول") { _, _ -> openWatchRoom(roomCode, etPass.text.toString().trim()) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun openWatchRoom(codeOrLink: String, password: String) {
        startActivity(Intent(requireContext(), WatchRoomActivity::class.java).apply {
            putExtra(WatchRoomActivity.EXTRA_ROOM_CODE, codeOrLink)
            putExtra(WatchRoomActivity.EXTRA_ROOM_PASS, password)
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class RoomsAdapter(
    private val items: List<WatchRoom>,
    private val onJoinClick: (WatchRoom) -> Unit
) : RecyclerView.Adapter<RoomsAdapter.VH>() {
    inner class VH(val binding: ItemWatchRoomBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemWatchRoomBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val b = holder.binding
        b.tvRoomIcon.text = r.roomIcon
        b.tvRoomTitle.text = r.roomName
        b.tvRoomContent.text = "📺 ${r.contentTitle} • المضيف: ${r.hostName}"
        b.tvRoomMeta.text = "👥 ${r.participantCount} مشاهدين • الكود: ${r.roomCode}"
        b.btnJoinRoomItem.setOnClickListener { onJoinClick(r) }
        b.root.setOnClickListener { onJoinClick(r) }
    }
}
""",

    f"{PKG}/presentation/watch/WatchRoomActivity.kt": """package com.primeott.app.presentation.watch

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.ActivityWatchRoomBinding
import com.primeott.app.databinding.ItemChatMessageBinding
import com.primeott.app.databinding.ItemParticipantBinding
import com.primeott.app.domain.model.RoomChatMessage
import com.primeott.app.domain.model.RoomParticipant
import com.primeott.app.domain.model.WatchRoom
import com.primeott.app.presentation.common.LocaleHelper
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class WatchRoomActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROOM_CODE = "extra_room_code"
        const val EXTRA_ROOM_PASS = "extra_room_pass"
    }

    private lateinit var binding: ActivityWatchRoomBinding
    private var exoPlayer: ExoPlayer? = null
    private var roomWebSocket: WebSocket? = null
    private var syncLoopJob: Job? = null
    private lateinit var voiceManager: VoiceChatManager

    private var currentRoom: WatchRoom? = null
    private var currentStreamUrl: String = ""
    private var lastAppliedSyncSeq: Int = 0
    private var isApplyingRemoteSync: Boolean = false
    private var lastMessageId: Long = 0L
    private var isFullscreenLandscape: Boolean = false
    private var isVoiceActive: Boolean = false
    private var isMicMuted: Boolean = true

    private val chatMessages = mutableListOf<RoomChatMessage>()
    private val participantsList = mutableListOf<RoomParticipant>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var participantsAdapter: ParticipantsAdapter

    private val myDeviceId by lazy { SessionManager.getDeviceId(this) }
    private val myUserName by lazy { SessionManager.getUserName(this) }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceConnection()
        else Toast.makeText(this, "⚠️ يلزم السماح بصلاحية الميكروفون للتحدث", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLayoutDirection(this)
        binding = ActivityWatchRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""
        val roomPass = intent.getStringExtra(EXTRA_ROOM_PASS) ?: ""
        if (roomCode.isEmpty()) { finish(); return }

        setupAdapters()
        setupVoiceManager()
        setupClickListeners()
        initSharedExoPlayer()
        joinAndInitializeRoom(roomCode, roomPass)
    }

    private fun setupAdapters() {
        chatAdapter = ChatAdapter(chatMessages)
        binding.rvRoomChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvRoomChat.adapter = chatAdapter

        participantsAdapter = ParticipantsAdapter(
            list = participantsList,
            canKick = { currentRoom?.isHost == true },
            myDeviceId = myDeviceId,
            onKickClick = { part ->
                val room = currentRoom ?: return@ParticipantsAdapter
                lifecycleScope.launch { OttRepository.manageWatchRoom(room.roomId, myDeviceId, "KICK_USER", part.deviceId) }
            }
        )
        binding.rvRoomParticipants.layoutManager = LinearLayoutManager(this)
        binding.rvRoomParticipants.adapter = participantsAdapter
    }

    private fun setupVoiceManager() {
        voiceManager = VoiceChatManager(
            context = applicationContext,
            onSendVoicePacket = { base64Pcm ->
                val pkt = JSONObject().apply {
                    put("type", "VOICE_PACKET")
                    put("sender_id", myDeviceId)
                    put("pcm", base64Pcm)
                }
                roomWebSocket?.send(pkt.toString())
            },
            onStateChanged = { connected, muted, statusText ->
                runOnUiThread {
                    isVoiceActive = connected
                    isMicMuted = muted
                    if (connected) {
                        binding.btnVoiceJoinToggle.text = "🔴 قطع الصوت"
                        binding.btnVoiceJoinToggle.setBackgroundColor(Color.parseColor("#EF4444"))
                        binding.btnMicMuteToggle.visibility = View.VISIBLE
                        binding.btnMicMuteToggle.text = if (muted) "🔇 مكتوم" else "🎙️ يتحدث"
                    } else {
                        binding.btnVoiceJoinToggle.text = "🎙️ اتصال صوتي"
                        binding.btnVoiceJoinToggle.setBackgroundColor(Color.parseColor("#10B981"))
                        binding.btnMicMuteToggle.visibility = View.GONE
                    }
                    Toast.makeText(this, statusText, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun setupClickListeners() {
        binding.btnLeaveRoom.setOnClickListener {
            val room = currentRoom
            if (room != null) lifecycleScope.launch { OttRepository.manageWatchRoom(room.roomId, myDeviceId, "LEAVE") }
            finish()
        }

        binding.btnShareInvite.setOnClickListener {
            val r = currentRoom ?: return@setOnClickListener
            val shareText = "🎉 انضم إلي لمشاهدة (${r.contentTitle}) في غرفة Watch Together! | كود الغرفة: ${r.roomCode} | الرابط: ${r.inviteUrl}"
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Room Invite", r.roomCode))
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }, "مشاركة دعوة الغرفة"))
        }

        binding.btnHostManage.setOnClickListener { showHostManagementDialog() }
        binding.btnToggleFullscreen.setOnClickListener { toggleFullscreenMode() }
        binding.btnChangeRoomStream.setOnClickListener { showSwitchChannelDialog() }

        binding.tabRoomChat.setOnClickListener {
            binding.chatSectionContainer.visibility = View.VISIBLE
            binding.rvRoomParticipants.visibility = View.GONE
            binding.tabRoomChat.setBackgroundColor(Color.parseColor("#E50914"))
            binding.tabRoomParticipants.setBackgroundColor(Color.TRANSPARENT)
        }

        binding.tabRoomParticipants.setOnClickListener {
            binding.chatSectionContainer.visibility = View.GONE
            binding.rvRoomParticipants.visibility = View.VISIBLE
            binding.tabRoomParticipants.setBackgroundColor(Color.parseColor("#E50914"))
            binding.tabRoomChat.setBackgroundColor(Color.TRANSPARENT)
        }

        binding.btnSendChat.setOnClickListener {
            val txt = binding.etChatInput.text.toString().trim()
            if (txt.isEmpty()) return@setOnClickListener
            binding.etChatInput.setText("")
            sendChatMessageOrReaction(txt, "TEXT")
        }

        listOf(
            binding.reactGoal to "⚽",
            binding.reactFire to "🔥",
            binding.reactHeart to "❤️",
            binding.reactClap to "👏",
            binding.reactLaugh to "😂",
            binding.reactShock to "😱"
        ).forEach { (view, emoji) ->
            view.setOnClickListener {
                showFloatingEmoji(emoji, myUserName)
                sendChatMessageOrReaction(emoji, "REACTION")
            }
        }

        binding.btnVoiceJoinToggle.setOnClickListener {
            if (isVoiceActive) voiceManager.disconnectVoice()
            else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceConnection()
            else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        binding.btnMicMuteToggle.setOnClickListener { voiceManager.toggleMute() }
    }

    private fun startVoiceConnection() {
        val roomId = currentRoom?.roomId ?: return
        lifecycleScope.launch {
            val tk = OttRepository.fetchVoiceToken(roomId, myDeviceId, myUserName)
            voiceManager.connectVoiceSession(tk.optString("livekit_url", ""), tk.optString("token", ""))
        }
    }

    @OptIn(UnstableApi::class)
    private fun initSharedExoPlayer() {
        val dataSourceFactory = OkHttpDataSource.Factory(OttRepository.okHttpClient).setUserAgent(SessionManager.CUSTOM_USER_AGENT)
        val extractorsFactory = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
        )
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 1_500, 3_000).build()
        val audioAttrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttrs, false)
            .build().also { player ->
                binding.sharedPlayerView.player = player
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!isApplyingRemoteSync) pushLocalPlaybackChangeToRoom()
                    }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK && !isApplyingRemoteSync) pushLocalPlaybackChangeToRoom()
                    }
                })
            }
    }

    private fun joinAndInitializeRoom(codeOrId: String, password: String) {
        lifecycleScope.launch {
            val res = OttRepository.joinWatchRoom(codeOrId, myDeviceId, myUserName, password)
            if (res.status != "success" || res.room == null) {
                Toast.makeText(this@WatchRoomActivity, res.errorMessage, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            applyRoomFullState(res.room, res.participants, res.messages)
            connectRoomWebSocket(res.room.roomId)
            startHeartbeatSyncLoop(res.room.roomId)
        }
    }

    private fun connectRoomWebSocket(roomId: String) {
        val wsUrl = SessionManager.CLOUDFLARE_API_URL.replace("https://", "wss://").replace("http://", "ws://") + "/api/watch/ws?room_id=$roomId"
        roomWebSocket = OttRepository.okHttpClient.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "PLAYBACK_SYNC" -> runOnUiThread {
                            applyPlaybackSyncFromServer(
                                json.optInt("sync_seq", 0),
                                json.optString("stream_url", currentStreamUrl),
                                json.optBoolean("is_playing", true),
                                json.optLong("position_ms", 0L)
                            )
                        }
                        "NEW_MESSAGE" -> {
                            val m = json.optJSONObject("message") ?: return
                            val msgObj = RoomChatMessage(
                                m.optLong("id"),
                                m.optString("sender_id"),
                                m.optString("sender_name"),
                                m.optString("message_type", "TEXT"),
                                m.optString("content"),
                                m.optLong("created_at")
                            )
                            runOnUiThread { appendIncomingMessage(msgObj) }
                        }
                        "VOICE_PACKET" -> {
                            if (json.optString("sender_id") != myDeviceId) {
                                voiceManager.playIncomingVoicePacket(json.optString("pcm", ""))
                            }
                        }
                        "ROOM_ENDED" -> runOnUiThread { finish() }
                    }
                } catch (e: Exception) {}
            }
        })
    }

    private fun startHeartbeatSyncLoop(roomId: String) {
        syncLoopJob?.cancel()
        syncLoopJob = lifecycleScope.launch {
            while (isActive) {
                delay(3000)
                val pbUpdate = if (currentRoom?.isHost == true && exoPlayer != null) {
                    JSONObject().apply {
                        put("is_playing", exoPlayer?.isPlaying == true)
                        put("position_ms", exoPlayer?.currentPosition ?: 0L)
                        put("stream_id", currentRoom?.streamId ?: "6171")
                        put("stream_url", currentStreamUrl)
                        put("content_title", currentRoom?.contentTitle ?: "")
                    }
                } else null

                val res = OttRepository.syncWatchRoom(roomId, myDeviceId, lastMessageId, isMicMuted, isVoiceActive, pbUpdate)
                if (res.status == "ended" || res.status == "kicked") { finish(); break }
                if (res.status == "success" && res.room != null) {
                    applyRoomFullState(res.room, res.participants, res.messages)
                }
            }
        }
    }

    private fun applyRoomFullState(room: WatchRoom, participants: List<RoomParticipant>, newMessages: List<RoomChatMessage>) {
        currentRoom = room
        binding.tvWatchRoomName.text = "${room.roomIcon} ${room.roomName} (${room.roomCode})"
        binding.btnHostManage.visibility = if (room.isHost) View.VISIBLE else View.GONE
        binding.tvSyncStatusBadge.text = "🟢 متزامن • 👥 ${participants.size}"
        binding.tabRoomParticipants.text = "👥 المتواجدون (${participants.size})"

        participantsList.clear()
        participantsList.addAll(participants)
        participantsAdapter.notifyDataSetChanged()

        newMessages.forEach { appendIncomingMessage(it) }
        applyPlaybackSyncFromServer(room.syncSeq, room.streamUrl, room.isPlaying, room.playbackPositionMs)
    }

    private fun applyPlaybackSyncFromServer(seq: Int, streamUrl: String, shouldPlay: Boolean, targetPosMs: Long) {
        val player = exoPlayer ?: return
        isApplyingRemoteSync = true
        try {
            if (streamUrl.isNotEmpty() && streamUrl != currentStreamUrl) {
                currentStreamUrl = streamUrl
                player.setMediaItem(MediaItem.fromUri(streamUrl))
                player.prepare()
                player.playWhenReady = shouldPlay
            } else {
                if (player.playWhenReady != shouldPlay) player.playWhenReady = shouldPlay
                if (currentRoom?.contentType != "LIVE" && targetPosMs > 0 && abs(player.currentPosition - targetPosMs) > 2500L) {
                    player.seekTo(targetPosMs)
                }
            }
            lastAppliedSyncSeq = maxOf(lastAppliedSyncSeq, seq)
        } finally {
            binding.root.postDelayed({ isApplyingRemoteSync = false }, 300)
        }
    }

    private fun pushLocalPlaybackChangeToRoom() {
        val room = currentRoom ?: return
        if (!room.isHost && !room.allowGuestsControl) return
        val player = exoPlayer ?: return
        val pb = JSONObject().apply {
            put("is_playing", player.isPlaying)
            put("position_ms", player.currentPosition)
            put("stream_id", room.streamId)
            put("stream_url", currentStreamUrl)
            put("content_title", room.contentTitle)
        }
        lifecycleScope.launch {
            OttRepository.syncWatchRoom(room.roomId, myDeviceId, lastMessageId, isMicMuted, isVoiceActive, pb)
        }
    }

    private fun appendIncomingMessage(msg: RoomChatMessage) {
        if (chatMessages.any { it.id == msg.id }) return
        if (msg.id > lastMessageId) lastMessageId = msg.id
        if (msg.messageType == "REACTION") {
            if (msg.senderId != myDeviceId) showFloatingEmoji(msg.content, msg.senderName)
            return
        }
        chatMessages.add(msg)
        chatAdapter.notifyItemInserted(chatMessages.size - 1)
        binding.rvRoomChat.scrollToPosition(chatMessages.size - 1)
    }

    private fun sendChatMessageOrReaction(content: String, type: String) {
        val roomId = currentRoom?.roomId ?: return
        lifecycleScope.launch { OttRepository.sendRoomMessage(roomId, myDeviceId, content, type) }
    }

    private fun showFloatingEmoji(emoji: String, senderName: String) {
        val tv = TextView(this).apply {
            text = "$emoji $senderName"
            textSize = 17f
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                marginStart = Random.nextInt(30, 220)
                bottomMargin = 16
            }
        }
        binding.reactionsOverlay.addView(tv)
        tv.animate().translationY(-280f).alpha(0f).setDuration(1800).withEndAction { binding.reactionsOverlay.removeView(tv) }.start()
    }

    private fun showSwitchChannelDialog() {
        val room = currentRoom ?: return
        val streams = listOf(
            Triple("6171", "beIN SPORTS 1 FHD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6171.m3u8"),
            Triple("6172", "beIN SPORTS 2 FHD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6172.m3u8"),
            Triple("6173", "beIN SPORTS 3 FHD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6173.m3u8"),
            Triple("6174", "SSC SPORTS 1 HD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6174.m3u8")
        )
        AlertDialog.Builder(this)
            .setTitle("📺 تغيير القناة للغرفة")
            .setItems(streams.map { it.second }.toTypedArray()) { _, which ->
                val chosen = streams[which]
                currentRoom = room.copy(streamId = chosen.first, contentTitle = chosen.second, streamUrl = chosen.third)
                applyPlaybackSyncFromServer(lastAppliedSyncSeq + 1, chosen.third, true, 0L)
                pushLocalPlaybackChangeToRoom()
            }
            .show()
    }

    private fun showHostManagementDialog() {
        val room = currentRoom ?: return
        val options = arrayOf(
            if (room.isLocked) "🔓 فتح الغرفة" else "🔒 قفل الغرفة",
            if (room.allowGuestsControl) "🛡️ حصر التحكم للمضيف" else "🎮 السماح للأعضاء بالتحكم",
            "⛔ إنهاء الغرفة للجميع"
        )
        AlertDialog.Builder(this)
            .setTitle("⚙️ إدارة الغرفة")
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    when (which) {
                        0 -> OttRepository.manageWatchRoom(room.roomId, myDeviceId, "LOCK_TOGGLE")
                        1 -> OttRepository.manageWatchRoom(room.roomId, myDeviceId, "GUEST_CONTROL_TOGGLE")
                        2 -> { OttRepository.manageWatchRoom(room.roomId, myDeviceId, "END_ROOM"); finish() }
                    }
                }
            }
            .show()
    }

    private fun toggleFullscreenMode() {
        isFullscreenLandscape = !isFullscreenLandscape
        if (isFullscreenLandscape) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            binding.watchTopHeader.visibility = View.GONE
            binding.socialControlBar.visibility = View.GONE
            binding.panelSwitchTabs.visibility = View.GONE
            binding.bottomSocialPanel.visibility = View.GONE
            binding.videoPlayerContainer.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            binding.watchTopHeader.visibility = View.VISIBLE
            binding.socialControlBar.visibility = View.VISIBLE
            binding.panelSwitchTabs.visibility = View.VISIBLE
            binding.bottomSocialPanel.visibility = View.VISIBLE
            binding.videoPlayerContainer.layoutParams.height = (230 * resources.displayMetrics.density).toInt()
        }
        binding.videoPlayerContainer.requestLayout()
    }

    override fun onDestroy() {
        syncLoopJob?.cancel()
        roomWebSocket?.close(1000, "Leave")
        voiceManager.disconnectVoice()
        exoPlayer?.release()
        super.onDestroy()
    }
}

class ChatAdapter(private val items: List<RoomChatMessage>) : RecyclerView.Adapter<ChatAdapter.VH>() {
    inner class VH(val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.binding.tvChatSender.text = if (m.messageType == "SYSTEM") "🔔 إشعار" else "👤 ${m.senderName}"
        holder.binding.tvChatContent.text = m.content
        holder.binding.tvChatTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.createdAt))
    }
}

class ParticipantsAdapter(
    private val list: List<RoomParticipant>,
    private val canKick: () -> Boolean,
    private val myDeviceId: String,
    private val onKickClick: (RoomParticipant) -> Unit
) : RecyclerView.Adapter<ParticipantsAdapter.VH>() {
    inner class VH(val binding: ItemParticipantBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemParticipantBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = list.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = list[position]
        holder.binding.tvPartName.text = "${p.userName} (${p.role})"
        holder.binding.tvPartMicState.text = if (!p.inVoice) "⚪" else if (p.isMuted) "🔇" else "🎙️🟢"
        holder.binding.btnKickParticipant.visibility = if (canKick() && p.deviceId != myDeviceId) View.VISIBLE else View.GONE
        holder.binding.btnKickParticipant.setOnClickListener { onKickClick(p) }
    }
}
"""
}

for path, content in files.items():
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content.strip() + "\n")
    print(f"[✓] Wrote: {path}")

