import os

PACKAGE_PATH = "app/src/main/java/com/primeott/app"

updates = {
    # 1. ربط SessionManager برابط Cloudflare الحقيقي الخاص بك
    f"{PACKAGE_PATH}/data/remote/SessionManager.kt": """package com.primeott.app.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

object SessionManager {
    private const val PREF_NAME = "prime_ott_secure_prefs"
    private const val KEY_TOKEN = "session_token"
    private const val KEY_EXPIRE = "expire_date"

    // رابط لوحة التحكم وقاعدة بيانات Cloudflare D1 الخاصة بك
    const val CLOUDFLARE_API_URL = "https://prime-ott-panel.prime-ott-stream.workers.dev"

    // قيم ديناميكية يتم تحديثها تلقائياً من Cloudflare عند فتح التطبيق
    var XTREAM_BASE_URL = "http://kwick.cc:8080"
    var XTREAM_USER = "Harry123"
    var XTREAM_PASS = "Alicea"
    var AD_URL = "https://www.google.com"

    const val CUSTOM_USER_AGENT = "IPTVSmartersPlayer"

    fun saveActivation(context: Context, token: String, expireDate: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_EXPIRE, expireDate)
            .apply()
    }

    fun getToken(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "") ?: ""
    }

    fun getExpireDate(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_EXPIRE, "غير مفعل (وضع تجريبي)") ?: "غير مفعل"
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEV"
    }
}
""",

    # 2. تحديث OttRepository ليجلب إعدادات السيرفر والمباريات وتفعيل الأكواد من Cloudflare D1
    f"{PACKAGE_PATH}/data/repository/OttRepository.kt": """package com.primeott.app.data.repository

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

object OttRepository {

    private val favoriteChannelIds = mutableSetOf<String>()
    private var isConfigLoaded = false

    private var cachedCategories = listOf<Category>()
    private val cachedChannelsMap = mutableMapOf<String, List<Channel>>()
    private var cachedMovies = listOf<VodItem>()
    private var cachedSeries = listOf<VodItem>()
    private var cachedMatches = listOf<MatchFixture>()

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

    // مزامنة التطبيق مع قاعدة بيانات Cloudflare D1 (جلب السيرفر، الإعلان، وجدول المباريات)
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

    // تفعيل الاشتراك عبر Cloudflare D1 وربطه بالـ Device ID
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
                    val expire = json.optString("expire_date", "2027-01-01")
                    return@withContext Pair(true, expire)
                } else {
                    val msg = json.optString("message", "تعذر التفعيل")
                    return@withContext Pair(false, msg)
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

    suspend fun fetchChannelsByCategory(categoryId: String): List<Channel> = withContext(Dispatchers.IO) {
        cachedChannelsMap[categoryId]?.let { cached ->
            return@withContext cached.map { it.copy(isFavorite = favoriteChannelIds.contains(it.id)) }
        }
        try {
            val url = "${SessionManager.XTREAM_BASE_URL}/player_api.php?username=${SessionManager.XTREAM_USER}&password=${SessionManager.XTREAM_PASS}&action=get_live_streams&category_id=$categoryId"
            val arr = JSONArray(fastGet(url))
            val list = ArrayList<Channel>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val streamId = obj.optString("stream_id")
                val cleanName = cleanHtmlText(obj.optString("name"))
                val icon = obj.optString("stream_icon")

                val hlsUrl = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.m3u8"
                val tsUrl = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.ts"

                list.add(
                    Channel(
                        id = streamId,
                        categoryId = categoryId,
                        name = cleanName,
                        logoUrl = icon,
                        streamUrls = listOf(hlsUrl, tsUrl),
                        isFavorite = favoriteChannelIds.contains(streamId)
                    )
                )
            }
            if (list.isNotEmpty()) {
                cachedChannelsMap[categoryId] = list
                return@withContext list
            }
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
                        streamUrls = listOf("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
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
""",

    # 3. تحديث شاشات المباريات والإعدادات لتعمل مباشرة مع Cloudflare D1
    f"{PACKAGE_PATH}/presentation/sections/AppSectionsFragments.kt": """package com.primeott.app.presentation.sections

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.*
import com.primeott.app.domain.model.Channel
import com.primeott.app.domain.model.MatchFixture
import com.primeott.app.domain.model.VodItem
import com.primeott.app.presentation.player.PlayerActivity
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeBinding.bind(view)

        binding.rvHomeChannels.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        viewLifecycleOwner.lifecycleScope.launch {
            OttRepository.syncWithCloudflare()
            val movies = OttRepository.fetchMovies()
            binding.rvHomeChannels.adapter = VodGridAdapter(movies.take(15)) { vod ->
                val tempChannel = Channel(vod.id, "vod", vod.title, vod.posterUrl, vod.streamUrls)
                startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_CHANNEL, tempChannel)
                })
            }
        }

        binding.btnWatchFeatured.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val matches = OttRepository.fetchMatchesFromCloudflare()
                if (matches.isNotEmpty()) {
                    startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_CHANNEL, matches.first().channel)
                    })
                }
            }
        }
    }
}

class VodFragment : Fragment() {
    companion object {
        fun newInstance(isSeries: Boolean) = VodFragment().apply {
            arguments = Bundle().apply { putBoolean("IS_SERIES", isSeries) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_vod_grid, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentVodGridBinding.bind(view)
        val isSeries = arguments?.getBoolean("IS_SERIES") ?: false

        binding.tvSectionHeader.text = if (isSeries) "جاري تحميل المسلسلات..." else "جاري تحميل الأفلام..."
        binding.rvVodGrid.layoutManager = GridLayoutManager(requireContext(), 2)

        viewLifecycleOwner.lifecycleScope.launch {
            val items = if (isSeries) OttRepository.fetchSeries() else OttRepository.fetchMovies()
            binding.tvSectionHeader.text = if (isSeries) "مكتبة المسلسلات (${items.size}+)" else "مكتبة الأفلام (${items.size}+)"
            binding.rvVodGrid.adapter = VodGridAdapter(items) { vod ->
                val tempChannel = Channel(vod.id, "vod", vod.title, vod.posterUrl, vod.streamUrls)
                startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_CHANNEL, tempChannel)
                })
            }
        }
    }
}

class VodGridAdapter(
    private val items: List<VodItem>,
    private val onClick: (VodItem) -> Unit
) : RecyclerView.Adapter<VodGridAdapter.VH>() {
    inner class VH(val binding: ItemVodCardBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemVodCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvVodTitle.text = item.title
        holder.binding.tvVodMeta.text = "${item.category} • ★ ${item.rating}"
        if (item.posterUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(item.posterUrl)
                .centerCrop()
                .into(holder.binding.ivPoster)
        }
        holder.binding.root.setOnClickListener { onClick(item) }
    }
}

class ScheduleFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_vod_grid, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentVodGridBinding.bind(view)
        binding.tvSectionHeader.text = "جاري جلب جدول المباريات من السيرفر..."
        binding.rvVodGrid.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            val matches = OttRepository.fetchMatchesFromCloudflare()
            binding.tvSectionHeader.text = "جدول مباريات اليوم والقنوات الناقلة"
            binding.rvVodGrid.adapter = MatchesAdapter(matches) { match ->
                startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_CHANNEL, match.channel)
                })
            }
        }
    }
}

class MatchesAdapter(
    private val matches: List<MatchFixture>,
    private val onClick: (MatchFixture) -> Unit
) : RecyclerView.Adapter<MatchesAdapter.VH>() {
    inner class VH(val binding: ItemMatchBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemMatchBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = matches.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = matches[position]
        holder.binding.tvTeams.text = "${m.homeTeam}  ⚡  ${m.awayTeam}"
        holder.binding.tvMatchInfo.text = "🏆 ${m.league}  |  🎙️ ${m.commentator}  |  📺 ${m.channel.name}"
        holder.binding.tvMatchStatus.text = when (m.status) {
            "LIVE" -> "🔴 مباشر الآن"
            "UPCOMING" -> "🕒 ${m.matchTime}"
            else -> "انتهت"
        }
        holder.binding.tvMatchStatus.setTextColor(
            if (m.status == "LIVE") Color.parseColor("#FF3B30") else Color.WHITE
        )
        holder.binding.root.setOnClickListener { onClick(m) }
    }
}

class SettingsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentSettingsBinding.bind(view)
        val devId = SessionManager.getDeviceId(requireContext())
        val exp = SessionManager.getExpireDate(requireContext())
        binding.tvDeviceId.text = "معرف الجهاز (Device ID): $devId\nصلاحية الاشتراك: $exp"

        binding.btnActivateCode.setOnClickListener {
            val code = binding.etActivationCode.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(requireContext(), "الرجاء إدخال كود التفعيل (مثال: PRIME-2026-VIP)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnActivateCode.isEnabled = false
            binding.btnActivateCode.text = "جاري التحقق من السيرفر..."

            viewLifecycleOwner.lifecycleScope.launch {
                val (ok, msgOrExpire) = OttRepository.activateSubscription(code, devId)
                binding.btnActivateCode.isEnabled = true
                binding.btnActivateCode.text = "تفعيل الاشتراك الآن"
                if (ok) {
                    SessionManager.saveActivation(requireContext(), "TOKEN_$code", msgOrExpire)
                    binding.tvDeviceId.text = "معرف الجهاز (Device ID): $devId\nصلاحية الاشتراك: ✅ مفعل حتى $msgOrExpire"
                    Toast.makeText(requireContext(), "تم تفعيل الاشتراك وربطه بجهازك بنجاح! 🎉", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), msgOrExpire, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
"""
}

for filepath, content in updates.items():
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(content.strip() + "\n")
    print(f"[✓] Linked to Cloudflare: {filepath}")

print("\n🚀 تم ربط تطبيق Prime OTT بقاعدة بيانات Cloudflare D1 بنجاح!")
