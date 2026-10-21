import os

PACKAGE_PATH = "app/src/main/java/com/primeott/app"
RES_PATH = "app/src/main/res"

updates = {
    # 1. إعدادات السيرفر وكلاودفلير (SessionManager.kt)
    f"{PACKAGE_PATH}/data/remote/SessionManager.kt": """package com.primeott.app.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

object SessionManager {
    private const val PREF_NAME = "prime_ott_secure_prefs"
    private const val KEY_TOKEN = "session_token"

    // بيانات سيرفر Xtream / Cloudflare (لاحقاً ننقلها داخل Cloudflare Worker لحمايتها 100%)
    const val XTREAM_BASE_URL = "http://kwick.cc:8080"
    const val XTREAM_USER = "Harry123"
    const val XTREAM_PASS = "Alicea"

    // رابط Cloudflare Worker الخاص بك (لوحة التحكم + قاعدة بيانات D1)
    const val CLOUDFLARE_API_URL = "https://prime-ott-api.your-subdomain.workers.dev"

    const val AD_URL = "https://www.google.com"
    const val CUSTOM_USER_AGENT = "IPTVSmartersPlayer"

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply()
    }

    fun getToken(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "DEMO_SESSION_TOKEN_2026") ?: "DEMO_SESSION_TOKEN_2026"
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEV"
    }
}
""",

    # 2. محرك جلب القنوات والباقات والأفلام من السيرفر الحقيقي + الكاش السريع
    f"{PACKAGE_PATH}/data/repository/OttRepository.kt": """package com.primeott.app.data.repository

import com.primeott.app.data.remote.SessionManager
import com.primeott.app.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

object OttRepository {

    private val favoriteChannelIds = mutableSetOf<String>()
    private var cachedCategories = mutableListOf<Category>()
    private var cachedChannelsByCategory = mutableMapOf<String, List<Channel>>()
    private var cachedMovies = mutableListOf<VodItem>()
    private var cachedSeries = mutableListOf<VodItem>()

    private val fallbackStreams = listOf(
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8"
    )

    // جلب باقات البث المباشر من السيرفر الحقيقي (Xtream API)
    suspend fun fetchLiveCategories(): List<Category> = withContext(Dispatchers.IO) {
        if (cachedCategories.isNotEmpty()) return@withContext cachedCategories
        try {
            val url = "${SessionManager.XTREAM_BASE_URL}/player_api.php?username=${SessionManager.XTREAM_USER}&password=${SessionManager.XTREAM_PASS}&action=get_live_categories"
            val jsonStr = httpGet(url)
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<Category>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    Category(
                        id = obj.optString("category_id"),
                        name = obj.optString("category_name")
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
        return@withContext getDefaultCategories()
    }

    // جلب قنوات باقة معينة من السيرفر الحقيقي بصيغة TS و M3U8 (Fallback)
    suspend fun fetchChannelsByCategory(categoryId: String): List<Channel> = withContext(Dispatchers.IO) {
        cachedChannelsByCategory[categoryId]?.let { raw ->
            return@withContext raw.map { it.copy(isFavorite = favoriteChannelIds.contains(it.id)) }
        }
        try {
            val url = "${SessionManager.XTREAM_BASE_URL}/player_api.php?username=${SessionManager.XTREAM_USER}&password=${SessionManager.XTREAM_PASS}&action=get_live_streams&category_id=$categoryId"
            val jsonStr = httpGet(url)
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<Channel>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val streamId = obj.optString("stream_id")
                val name = obj.optString("name")
                val icon = obj.optString("stream_icon")

                // رابط TS الأساسي + رابط M3U8 كسيرفر بديل (Fallback)
                val tsUrl = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.ts"
                val hlsUrl = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.m3u8"

                list.add(
                    Channel(
                        id = streamId,
                        categoryId = categoryId,
                        name = name,
                        logoUrl = icon,
                        streamUrls = listOf(tsUrl, hlsUrl),
                        isFavorite = favoriteChannelIds.contains(streamId)
                    )
                )
            }
            if (list.isNotEmpty()) {
                cachedChannelsByCategory[categoryId] = list
                return@withContext list
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext getChannelsByCategory(categoryId)
    }

    // جلب الأفلام الحقيقية من السيرفر (VOD)
    suspend fun fetchMovies(): List<VodItem> = withContext(Dispatchers.IO) {
        if (cachedMovies.isNotEmpty()) return@withContext cachedMovies
        try {
            val url = "${SessionManager.XTREAM_BASE_URL}/player_api.php?username=${SessionManager.XTREAM_USER}&password=${SessionManager.XTREAM_PASS}&action=get_vod_streams"
            val jsonStr = httpGet(url)
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<VodItem>()
            val limit = minOf(jsonArray.length(), 60) // عرض أحدث 60 فيلم لسرعة الأداء
            for (i in 0 until limit) {
                val obj = jsonArray.getJSONObject(i)
                val streamId = obj.optString("stream_id")
                val ext = obj.optString("container_extension", "mp4")
                val movieUrl = "${SessionManager.XTREAM_BASE_URL}/movie/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$streamId.$ext"
                list.add(
                    VodItem(
                        id = streamId,
                        title = obj.optString("name"),
                        category = "VOD",
                        rating = obj.optString("rating", "8.5"),
                        year = "2025",
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
        return@withContext getMovies()
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", SessionManager.CUSTOM_USER_AGENT)
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    fun getDefaultCategories(): List<Category> = listOf(
        Category("cat_bein", "باقة beIN Sports VIP"),
        Category("cat_ssc", "باقة SSC الرياضية"),
        Category("cat_arabic", "القنوات العربية"),
        Category("cat_movies", "قنوات الأفلام VIP")
    )

    fun getAllChannels(): List<Channel> = listOf(
        Channel("ch_1", "cat_bein", "beIN Sports 1 FHD (50Fps)", "", fallbackStreams, favoriteChannelIds.contains("ch_1")),
        Channel("ch_2", "cat_bein", "beIN Sports 2 FHD (50Fps)", "", fallbackStreams, favoriteChannelIds.contains("ch_2")),
        Channel("ch_4", "cat_ssc", "SSC Sports 1 HD", "", fallbackStreams, favoriteChannelIds.contains("ch_4"))
    )

    fun getChannelsByCategory(categoryId: String): List<Channel> {
        return getAllChannels().filter { it.categoryId == categoryId }.ifEmpty { getAllChannels() }
    }

    fun toggleFavorite(channelId: String) {
        if (favoriteChannelIds.contains(channelId)) favoriteChannelIds.remove(channelId)
        else favoriteChannelIds.add(channelId)
    }

    fun getMovies(): List<VodItem> = listOf(
        VodItem("m1", "The Dark Knight", "أكشن", "9.0", "2024", "https://image.tmdb.org/t/p/w500/qJ2tW6WMUDux911r6m7haRef0WH.jpg", fallbackStreams, false),
        VodItem("m2", "Inception", "خيال علمي", "8.8", "2025", "https://image.tmdb.org/t/p/w500/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg", fallbackStreams, false),
        VodItem("m3", "Gladiator II", "تاريخي", "8.5", "2025", "https://image.tmdb.org/t/p/w500/2cxhvwyEwRlysAmRH4iodkvo0z5.jpg", fallbackStreams, false),
        VodItem("m4", "John Wick 4", "أكشن", "8.2", "2024", "https://image.tmdb.org/t/p/w500/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg", fallbackStreams, false)
    )

    fun getSeries(): List<VodItem> = listOf(
        VodItem("s1", "Breaking Bad (الموسم 1)", "دراما", "9.5", "2024", "https://image.tmdb.org/t/p/w500/ggFHVNu6YYI5L9pCfOacjizRGt.jpg", fallbackStreams, true),
        VodItem("s2", "House of the Dragon", "فانتازيا", "8.6", "2025", "https://image.tmdb.org/t/p/w500/t9XkeE7HzOsdQcDDDapDYh8Rrmt.jpg", fallbackStreams, true)
    )

    fun getMatchesSchedule(): List<MatchFixture> {
        val channels = getAllChannels()
        return listOf(
            MatchFixture("mt1", "ريال مدريد", "برشلونة", "21:00", "الدوري الإسباني", "عصام الشوالي", "LIVE", channels[0]),
            MatchFixture("mt2", "مانشستر سيتي", "ليفربول", "18:30", "الدوري الإنجليزي", "حفيظ دراجي", "LIVE", channels[1]),
            MatchFixture("mt3", "النصر", "الهلال", "20:00", "دوري روشن", "فهد العتيبي", "UPCOMING", channels[2])
        )
    }
}
""",

    # 3. تحديث LiveTvFragment لجلب الباقات والقنوات الحقيقية من السيرفر باستخدام Coroutines
    f"{PACKAGE_PATH}/presentation/livetv/LiveTvFragment.kt": """package com.primeott.app.presentation.livetv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.primeott.app.R
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.FragmentLiveTvBinding
import com.primeott.app.domain.model.Category
import com.primeott.app.domain.model.Channel
import com.primeott.app.presentation.player.PlayerActivity
import kotlinx.coroutines.launch

class LiveTvFragment : Fragment() {

    private var _binding: FragmentLiveTvBinding? = null
    private val binding get() = _binding!!

    private var categoriesList: List<Category> = emptyList()
    private var currentCategoryChannels: List<Channel> = emptyList()
    private lateinit var channelsAdapter: ChannelsAdapter

    private var selectedCategoryId: String = ""
    private var searchQuery: String = ""
    private var showFavoritesOnly: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_live_tv, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentLiveTvBinding.bind(view)

        channelsAdapter = ChannelsAdapter(
            onChannelClick = { channel -> openPlayer(channel) },
            onFavClick = { channel ->
                OttRepository.toggleFavorite(channel.id)
                loadChannelsForCategory(selectedCategoryId)
            }
        )
        binding.rvChannels.adapter = channelsAdapter

        binding.etSearchChannel.doAfterTextChanged { text ->
            searchQuery = text?.toString().orEmpty()
            applyLocalFilter()
        }

        binding.btnToggleFavorites.setOnClickListener {
            showFavoritesOnly = !showFavoritesOnly
            binding.btnToggleFavorites.alpha = if (showFavoritesOnly) 1.0f else 0.4f
            applyLocalFilter()
        }

        loadServerCategories()
    }

    private fun loadServerCategories() {
        viewLifecycleOwner.lifecycleScope.launch {
            categoriesList = OttRepository.fetchLiveCategories()
            if (categoriesList.isNotEmpty()) {
                selectedCategoryId = categoriesList.first().id
                binding.rvCategories.adapter = CategoriesAdapter(categoriesList, selectedCategoryId) { category ->
                    selectedCategoryId = category.id
                    loadChannelsForCategory(selectedCategoryId)
                }
                loadChannelsForCategory(selectedCategoryId)
            }
        }
    }

    private fun loadChannelsForCategory(categoryId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            currentCategoryChannels = OttRepository.fetchChannelsByCategory(categoryId)
            applyLocalFilter()
        }
    }

    private fun applyLocalFilter() {
        val filtered = currentCategoryChannels.filter { ch ->
            val matchesQuery = ch.name.contains(searchQuery, ignoreCase = true)
            val matchesFav = if (showFavoritesOnly) ch.isFavorite else true
            matchesQuery && matchesFav
        }
        channelsAdapter.submitList(filtered)
    }

    private fun openPlayer(channel: Channel) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL, channel)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
""",

    # 4. تحسين المشغل لإخفاء الشريط العلوي مع الـ Controller ودعم روابط TS
    f"{PACKAGE_PATH}/presentation/player/PlayerActivity.kt": """package com.primeott.app.presentation.player

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.databinding.ActivityPlayerBinding
import com.primeott.app.domain.model.Channel
import com.primeott.app.presentation.ads.AdCountdownDialog

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL = "extra_channel"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private var currentChannel: Channel? = null
    private var currentServerIndex = 0
    private var isAdCompleted = false
    private var resizeModeIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CHANNEL)
        }

        binding.tvChannelTitle.text = currentChannel?.name ?: "البث المباشر"

        setupPlayerButtons()
        showMandatoryAd()
    }

    @OptIn(UnstableApi::class)
    private fun setupPlayerButtons() {
        // إخفاء وإظهار الشريط العلوي تلقائياً مع أزرار المشغل
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                binding.topPlayerBar.visibility = visibility
            }
        )

        binding.btnSwitchServer.setOnClickListener {
            val urls = currentChannel?.streamUrls ?: return@setOnClickListener
            currentServerIndex = (currentServerIndex + 1) % urls.size
            Toast.makeText(this, "تم التبديل إلى سيرفر ${currentServerIndex + 1}", Toast.LENGTH_SHORT).show()
            playStreamAt(currentServerIndex)
        }

        binding.btnAspectRatio.setOnClickListener {
            val modes = listOf(
                AspectRatioFrameLayout.RESIZE_MODE_FIT,
                AspectRatioFrameLayout.RESIZE_MODE_FILL,
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            )
            resizeModeIndex = (resizeModeIndex + 1) % modes.size
            binding.playerView.resizeMode = modes[resizeModeIndex]
        }
    }

    private fun showMandatoryAd() {
        AdCountdownDialog(
            adUrl = SessionManager.AD_URL,
            onAdFinished = {
                isAdCompleted = true
                initAndPlayExoPlayer()
            }
        ).show(supportFragmentManager, "MandatoryAd")
    }

    @OptIn(UnstableApi::class)
    private fun initAndPlayExoPlayer() {
        val urls = currentChannel?.streamUrls ?: return
        if (urls.isEmpty()) return

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(SessionManager.CUSTOM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(12000)

        // تحسين الـ Buffer لبث قنوات 50fps TS بدون تقطيع
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 1500, 3000)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
            .also { player ->
                binding.playerView.player = player
                player.addListener(fallbackListener)
                playStreamAt(currentServerIndex)
            }
    }

    private fun playStreamAt(index: Int) {
        val urls = currentChannel?.streamUrls ?: return
        if (index >= urls.size) {
            Toast.makeText(this, "جميع سيرفرات البث متوقفة حالياً", Toast.LENGTH_LONG).show()
            return
        }
        exoPlayer?.apply {
            setMediaItem(MediaItem.fromUri(urls[index]))
            prepare()
            playWhenReady = true
        }
    }

    private val fallbackListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val urls = currentChannel?.streamUrls ?: return
            if (currentServerIndex + 1 < urls.size) {
                currentServerIndex++
                Toast.makeText(
                    this@PlayerActivity,
                    "جاري التحويل التلقائي للسيرفر البديل (${currentServerIndex + 1})...",
                    Toast.LENGTH_SHORT
                ).show()
                playStreamAt(currentServerIndex)
            } else {
                Toast.makeText(this@PlayerActivity, "تعذر تشغيل القناة من السيرفر حالياً", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onStart() {
        super.onStart()
        if (isAdCompleted && exoPlayer == null) {
            initAndPlayExoPlayer()
        }
    }
}
"""
}

for filepath, content in updates.items():
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(content.strip() + "\n")
    print(f"[✓] Updated: {filepath}")

print("\n🚀 تم ربط سيرفر Xtream (kwick.cc:8080) وتفعيل دعم TS + M3U8 بنجاح!")
