package com.primeott.app.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.primeott.app.domain.model.VodItem
import com.primeott.app.domain.model.WatchHistoryEntry
import org.json.JSONArray
import org.json.JSONObject

object SessionManager {
    private const val PREF_NAME = "prime_ott_secure_prefs"
    private const val KEY_TOKEN = "session_token"
    private const val KEY_EXPIRE = "expire_date"
    private const val KEY_LANG = "app_language"
    private const val KEY_ONBOARDED = "onboarding_done_v3"
    private const val KEY_USER_NAME = "user_display_name"
    private const val KEY_FAV_IDS = "favorite_stream_ids"
    private const val KEY_RECENT_IDS = "recent_stream_ids"
    private const val KEY_WATCH_HISTORY_JSON = "watch_history_v2_json"

    const val CURRENT_APP_VERSION = 3
    const val CLOUDFLARE_API_URL = "https://prime-ott-panel.prime-ott-stream.workers.dev"

    var XTREAM_BASE_URL = "http://kwick.cc:8080"
    var XTREAM_USER = "Harry123"
    var XTREAM_PASS = "Alicea"

    var AD_URL = "https://www.google.com"
    var ADS_ENABLED = true
    var AD_SECONDS = 10
    var BANNER_BADGE = "🔥 LIVE"
    var BANNER_TITLE = "Real Madrid VS Barcelona"
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
        return if (saved.isNotBlank()) saved else "Prime User"
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
        prefs(context).getString(KEY_EXPIRE, "Free Mode") ?: "Free Mode"

    fun getFavoriteIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FAV_IDS, emptySet()) ?: emptySet()

    fun saveFavoriteIds(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_FAV_IDS, ids.toSet()).apply()
    }

    /**
     * حفظ تقدم المشاهدة الحقيقي (Continue Watching) مع الموضع الزمني والمدة الكلية
     */
    fun saveWatchProgress(
        context: Context,
        vod: VodItem,
        positionMs: Long,
        durationMs: Long
    ) {
        val currentList = getWatchHistoryList(context).toMutableList()
        val existingIdx = currentList.indexOfFirst { it.id == vod.id }
        val safePos = if (positionMs > 1000L) positionMs else (currentList.getOrNull(existingIdx)?.positionMs ?: 0L)
        val safeDur = if (durationMs > 1000L) durationMs else (currentList.getOrNull(existingIdx)?.durationMs ?: 0L)

        if (existingIdx != -1) {
            currentList.removeAt(existingIdx)
        }

        currentList.add(
            0,
            WatchHistoryEntry(
                id = vod.id,
                title = vod.title,
                posterUrl = vod.posterUrl,
                backdropUrl = vod.backdropUrl.ifEmpty { vod.posterUrl },
                rating = vod.rating,
                year = vod.year,
                isSeries = vod.isSeries,
                positionMs = safePos,
                durationMs = safeDur,
                updatedAt = System.currentTimeMillis()
            )
        )

        persistWatchHistoryList(context, currentList.take(40))
    }

    fun getWatchHistoryList(context: Context): List<WatchHistoryEntry> {
        val raw = prefs(context).getString(KEY_WATCH_HISTORY_JSON, "[]") ?: "[]"
        val list = ArrayList<WatchHistoryEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    WatchHistoryEntry(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        posterUrl = o.optString("posterUrl"),
                        backdropUrl = o.optString("backdropUrl"),
                        rating = o.optString("rating", "7.5"),
                        year = o.optString("year", "2026"),
                        isSeries = o.optBoolean("isSeries", false),
                        positionMs = o.optLong("positionMs", 0L),
                        durationMs = o.optLong("durationMs", 0L),
                        updatedAt = o.optLong("updatedAt", 0L)
                    )
                )
            }
        } catch (e: Exception) {}
        return list
    }

    fun getSavedPositionForVod(context: Context, vodId: String): Long {
        return getWatchHistoryList(context).firstOrNull { it.id == vodId }?.positionMs ?: 0L
    }

    fun deleteWatchHistoryItems(context: Context, idsToRemove: Set<String>) {
        val remaining = getWatchHistoryList(context).filterNot { idsToRemove.contains(it.id) }
        persistWatchHistoryList(context, remaining)
    }

    fun clearAllWatchHistory(context: Context) {
        prefs(context).edit()
            .remove(KEY_WATCH_HISTORY_JSON)
            .remove(KEY_RECENT_IDS)
            .apply()
    }

    private fun persistWatchHistoryList(context: Context, list: List<WatchHistoryEntry>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("posterUrl", item.posterUrl)
                    put("backdropUrl", item.backdropUrl)
                    put("rating", item.rating)
                    put("year", item.year)
                    put("isSeries", item.isSeries)
                    put("positionMs", item.positionMs)
                    put("durationMs", item.durationMs)
                    put("updatedAt", item.updatedAt)
                }
            )
        }
        prefs(context).edit().putString(KEY_WATCH_HISTORY_JSON, arr.toString()).apply()
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