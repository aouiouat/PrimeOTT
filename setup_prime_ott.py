import os

# إعداد المسارات الأساسية للمشروع
PACKAGE_PATH = "app/src/main/java/com/primeott/app"
RES_PATH = "app/src/main/res"

files = {
    # ==========================================
    # 1. إعدادات Gradle و AndroidManifest
    # ==========================================
    "app/build.gradle.kts": """plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

android {
    namespace = "com.primeott.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.primeott.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // Lifecycle & Coroutines
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Media3 ExoPlayer (HLS & TS Support)
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    // Retrofit & OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Glide for Image Loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
""",

    "app/src/main/AndroidManifest.xml": """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.primeott.app">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:allowBackup="true"
        android:label="Prime OTT"
        android:supportsRtl="true"
        android:usesCleartextTraffic="true"
        android:theme="@style/Theme.MaterialComponents.DayNight.NoActionBar">

        <activity
            android:name=".presentation.main.MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".presentation.player.PlayerActivity"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:exported="false"
            android:screenOrientation="sensorLandscape" />

    </application>
</manifest>
""",

    # ==========================================
    # 2. طبقة الـ Domain (Models)
    # ==========================================
    f"{PACKAGE_PATH}/domain/model/Models.kt": """package com.primeott.app.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

data class Category(
    val id: String,
    val name: String
)

@Parcelize
data class Channel(
    val id: String,
    val categoryId: String,
    val name: String,
    val logoUrl: String,
    val streamUrls: List<String>,
    var isFavorite: Boolean = false
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
    val isSeries: Boolean = false
) : Parcelable

data class MatchFixture(
    val id: String,
    val homeTeam: String,
    val awayTeam: String,
    val matchTime: String,
    val league: String,
    val commentator: String,
    val status: String, // LIVE, UPCOMING, FINISHED
    val channel: Channel
)
""",

    # ==========================================
    # 3. طبقة الـ Data (Security, API & Repository)
    # ==========================================
    f"{PACKAGE_PATH}/data/remote/SessionManager.kt": """package com.primeott.app.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

object SessionManager {
    private const val PREF_NAME = "prime_ott_secure_prefs"
    private const val KEY_TOKEN = "session_token"
    
    // عند تفعيل هذا الخيار يشتغل التطبيق ببيانات تجريبية جاهزة فوراً
    const val USE_MOCK_DATA = true
    const val AD_URL = "https://www.google.com" 
    const val CUSTOM_USER_AGENT = "PrimeOTT-Shield/2.6 (Android; SecureStream)"

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

    f"{PACKAGE_PATH}/data/repository/OttRepository.kt": """package com.primeott.app.data.repository

import com.primeott.app.domain.model.*

object OttRepository {

    // روابط بث تجريبية حقيقية بصيغة HLS (.m3u8) لتجربة المشغل ونظام الـ Fallback
    private val testStreams = listOf(
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8"
    )

    private val favoriteChannelIds = mutableSetOf<String>()

    fun getCategories(): List<Category> = listOf(
        Category("cat_bein", "باقة beIN Sports"),
        Category("cat_ssc", "باقة SSC الرياضية"),
        Category("cat_arabic", "القنوات العربية"),
        Category("cat_movies", "قنوات الأفلام VIP"),
        Category("cat_news", "قنوات الأخبار")
    )

    fun getAllChannels(): List<Channel> = listOf(
        Channel("ch_1", "cat_bein", "beIN Sports 1 HD Premium", "", testStreams, favoriteChannelIds.contains("ch_1")),
        Channel("ch_2", "cat_bein", "beIN Sports 2 HD Premium", "", testStreams, favoriteChannelIds.contains("ch_2")),
        Channel("ch_3", "cat_bein", "beIN Sports 3 HD", "", testStreams, favoriteChannelIds.contains("ch_3")),
        Channel("ch_4", "cat_ssc", "SSC Sports 1 HD", "", testStreams, favoriteChannelIds.contains("ch_4")),
        Channel("ch_5", "cat_ssc", "SSC Sports 2 HD", "", testStreams, favoriteChannelIds.contains("ch_5")),
        Channel("ch_6", "cat_arabic", "MBC 1 HD", "", testStreams, favoriteChannelIds.contains("ch_6")),
        Channel("ch_7", "cat_arabic", "الجزائرية السادسة الشبابية", "", testStreams, favoriteChannelIds.contains("ch_7")),
        Channel("ch_8", "cat_movies", "OSN Movies Action", "", testStreams, favoriteChannelIds.contains("ch_8"))
    )

    fun getChannelsByCategory(categoryId: String): List<Channel> {
        return getAllChannels().filter { it.categoryId == categoryId }
    }

    fun toggleFavorite(channelId: String) {
        if (favoriteChannelIds.contains(channelId)) {
            favoriteChannelIds.remove(channelId)
        } else {
            favoriteChannelIds.add(channelId)
        }
    }

    fun getMovies(): List<VodItem> = listOf(
        VodItem("m1", "The Dark Knight", "أكشن", "9.0", "2024", "", testStreams, false),
        VodItem("m2", "Inception", "خيال علمي", "8.8", "2025", "", testStreams, false),
        VodItem("m3", "Gladiator II", "تاريخي", "8.5", "2025", "", testStreams, false),
        VodItem("m4", "John Wick 4", "أكشن", "8.2", "2024", "", testStreams, false),
        VodItem("m5", "Interstellar", "فضاء", "8.9", "2023", "", testStreams, false),
        VodItem("m6", "Oppenheimer", "دراما", "8.7", "2024", "", testStreams, false)
    )

    fun getSeries(): List<VodItem> = listOf(
        VodItem("s1", "Breaking Bad (الموسم 1)", "دراما", "9.5", "2024", "", testStreams, true),
        VodItem("s2", "House of the Dragon", "فانتازيا", "8.6", "2025", "", testStreams, true),
        VodItem("s3", "Prison Break", "إثارة", "8.4", "2023", "", testStreams, true),
        VodItem("s4", "The Last of Us", "أكشن", "8.8", "2025", "", testStreams, true)
    )

    fun getMatchesSchedule(): List<MatchFixture> {
        val channels = getAllChannels()
        return listOf(
            MatchFixture("mt1", "ريال مدريد", "برشلونة", "21:00", "الدوري الإسباني", "عصام الشوالي", "LIVE", channels[0]),
            MatchFixture("mt2", "مانشستر سيتي", "ليفربول", "18:30", "الدوري الإنجليزي", "حفيظ دراجي", "LIVE", channels[1]),
            MatchFixture("mt3", "النصر", "الهلال", "20:00", "دوري روشن", "فهد العتيبي", "UPCOMING", channels[3]),
            MatchFixture("mt4", "بايرن ميونخ", "أرسنال", "22:00", "دوري أبطال أوروبا", "خليل البلوشي", "FINISHED", channels[2])
        )
    }
}
""",

    # ==========================================
    # 4. نافذة الإعلان الإجباري (AdCountdownDialog)
    # ==========================================
    f"{PACKAGE_PATH}/presentation/ads/AdCountdownDialog.kt": """package com.primeott.app.presentation.ads

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.DialogFragment
import com.primeott.app.R
import com.primeott.app.databinding.DialogAdCountdownBinding

class AdCountdownDialog(
    private val adUrl: String,
    private val onAdFinished: () -> Unit
) : DialogFragment(R.layout.dialog_ad_countdown) {

    private var _binding: DialogAdCountdownBinding? = null
    private val binding get() = _binding!!
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        isCancelable = false // منع إغلاق الإعلان بزر الرجوع
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = DialogAdCountdownBinding.bind(view)

        binding.adWebView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?) = false
            }
            loadUrl(adUrl)
        }

        binding.btnSkipAd.isEnabled = false
        timer = object : CountDownTimer(10_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000) + 1
                binding.btnSkipAd.text = "تخطي الإعلان بعد ($sec ث)"
            }

            override fun onFinish() {
                binding.btnSkipAd.apply {
                    isEnabled = true
                    text = "إغلاق الإعلان وتشغيل البث ✕"
                    setBackgroundColor(0xFFE50914.toInt())
                }
            }
        }.start()

        binding.btnSkipAd.setOnClickListener {
            dismissAllowingStateLoss()
            onAdFinished()
        }
    }

    override fun onDestroyView() {
        timer?.cancel()
        binding.adWebView.destroy()
        _binding = null
        super.onDestroyView()
    }
}
""",

    # ==========================================
    # 5. مشغل الفيديو (ExoPlayer + Fallback + Security)
    # ==========================================
    f"{PACKAGE_PATH}/presentation/player/PlayerActivity.kt": """package com.primeott.app.presentation.player

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
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
        // عرض الإعلان الإجباري أولاً قبل تشغيل البث
        showMandatoryAd()
    }

    @OptIn(UnstableApi::class)
    private fun setupPlayerButtons() {
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

        // حماية الروابط عبر Session Token و Device ID في الهيدر
        val headers = mapOf(
            "X-Session-Token" to SessionManager.getToken(this),
            "X-Device-Id" to SessionManager.getDeviceId(this)
        )

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(SessionManager.CUSTOM_USER_AGENT)
            .setDefaultRequestProperties(headers)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
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
                    "تعذر الاتصال، جاري التحويل التلقائي للسيرفر البديل (${currentServerIndex + 1})...",
                    Toast.LENGTH_SHORT
                ).show()
                playStreamAt(currentServerIndex)
            } else {
                Toast.makeText(this@PlayerActivity, "فشل تشغيل البث من جميع المصادر", Toast.LENGTH_LONG).show()
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
""",

    # ==========================================
    # 6. شاشة البث المباشر المقسمة (LiveTvFragment + Adapters)
    # ==========================================
    f"{PACKAGE_PATH}/presentation/livetv/LiveTvFragment.kt": """package com.primeott.app.presentation.livetv

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.primeott.app.R
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.FragmentLiveTvBinding
import com.primeott.app.domain.model.Channel
import com.primeott.app.presentation.player.PlayerActivity

class LiveTvFragment : Fragment(R.layout.fragment_live_tv) {

    private var _binding: FragmentLiveTvBinding? = null
    private val binding get() = _binding!!

    private lateinit var categoriesAdapter: CategoriesAdapter
    private lateinit var channelsAdapter: ChannelsAdapter

    private var selectedCategoryId: String = "cat_bein"
    private var searchQuery: String = ""
    private var showFavoritesOnly: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentLiveTvBinding.bind(view)

        val categories = OttRepository.getCategories()
        if (categories.isNotEmpty()) selectedCategoryId = categories.first().id

        categoriesAdapter = CategoriesAdapter(categories, selectedCategoryId) { category ->
            selectedCategoryId = category.id
            refreshChannelsList()
        }
        binding.rvCategories.adapter = categoriesAdapter

        channelsAdapter = ChannelsAdapter(
            onChannelClick = { channel -> openPlayer(channel) },
            onFavClick = { channel ->
                OttRepository.toggleFavorite(channel.id)
                refreshChannelsList()
            }
        )
        binding.rvChannels.adapter = channelsAdapter

        binding.etSearchChannel.doAfterTextChanged { text ->
            searchQuery = text?.toString().orEmpty()
            refreshChannelsList()
        }

        binding.btnToggleFavorites.setOnClickListener {
            showFavoritesOnly = !showFavoritesOnly
            binding.btnToggleFavorites.alpha = if (showFavoritesOnly) 1.0f else 0.4f
            refreshChannelsList()
        }

        refreshChannelsList()
    }

    private fun refreshChannelsList() {
        val rawChannels = OttRepository.getChannelsByCategory(selectedCategoryId)
        val filtered = rawChannels.filter { ch ->
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

    f"{PACKAGE_PATH}/presentation/livetv/LiveTvAdapters.kt": """package com.primeott.app.presentation.livetv

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.primeott.app.databinding.ItemCategoryBinding
import com.primeott.app.databinding.ItemChannelBinding
import com.primeott.app.domain.model.Category
import com.primeott.app.domain.model.Channel

class CategoriesAdapter(
    private val items: List<Category>,
    private var selectedId: String,
    private val onClick: (Category) -> Unit
) : RecyclerView.Adapter<CategoriesAdapter.VH>() {

    inner class VH(val binding: ItemCategoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val isSelected = item.id == selectedId
        holder.binding.tvCategoryName.text = item.name
        holder.binding.root.setBackgroundColor(if (isSelected) Color.parseColor("#E50914") else Color.TRANSPARENT)
        holder.binding.tvCategoryName.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#B3B3B3"))

        holder.binding.root.setOnClickListener {
            selectedId = item.id
            notifyDataSetChanged()
            onClick(item)
        }
    }
}

class ChannelsAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onFavClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelsAdapter.VH>() {

    private var list: List<Channel> = emptyList()

    fun submitList(newList: List<Channel>) {
        list = newList
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = list[position]
        holder.binding.tvChannelName.text = channel.name
        holder.binding.btnFav.text = if (channel.isFavorite) "★" else "☆"
        holder.binding.btnFav.setTextColor(if (channel.isFavorite) Color.parseColor("#FFD700") else Color.GRAY)

        holder.binding.root.setOnClickListener { onChannelClick(channel) }
        holder.binding.btnFav.setOnClickListener { onFavClick(channel) }
    }
}
""",

    # ==========================================
    # 7. باقي الشاشات الـ 5 (Home, Movies, Series, Schedule, Settings)
    # ==========================================
    f"{PACKAGE_PATH}/presentation/sections/AppSectionsFragments.kt": """package com.primeott.app.presentation.sections

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.*
import com.primeott.app.domain.model.Channel
import com.primeott.app.domain.model.MatchFixture
import com.primeott.app.domain.model.VodItem
import com.primeott.app.presentation.player.PlayerActivity

// 1. الشاشة الرئيسية (HomeFragment)
class HomeFragment : Fragment(R.layout.fragment_home) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeBinding.bind(view)

        binding.btnWatchFeatured.setOnClickListener {
            val firstChannel = OttRepository.getAllChannels().first()
            startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL, firstChannel)
            })
        }

        binding.rvHomeChannels.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvHomeChannels.adapter = VodGridAdapter(
            OttRepository.getMovies()
        ) { vod -> playVod(vod) }
    }

    private fun playVod(vod: VodItem) {
        val tempChannel = Channel(vod.id, "vod", vod.title, vod.posterUrl, vod.streamUrls)
        startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL, tempChannel)
        })
    }
}

// 2. شاشة الأفلام والمسلسلات (Movies / Series Fragment)
class VodFragment : Fragment(R.layout.fragment_vod_grid) {
    companion object {
        fun newInstance(isSeries: Boolean) = VodFragment().apply {
            arguments = Bundle().apply { putBoolean("IS_SERIES", isSeries) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentVodGridBinding.bind(view)
        val isSeries = arguments?.getBoolean("IS_SERIES") ?: false

        binding.tvSectionHeader.text = if (isSeries) "مكتبة المسلسلات الرمضانية والعالمية" else "أحدث الأفلام الحصرية (4K / FHD)"
        val items = if (isSeries) OttRepository.getSeries() else OttRepository.getMovies()

        binding.rvVodGrid.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvVodGrid.adapter = VodGridAdapter(items) { vod ->
            val tempChannel = Channel(vod.id, "vod", vod.title, vod.posterUrl, vod.streamUrls)
            startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL, tempChannel)
            })
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
        holder.binding.root.setOnClickListener { onClick(item) }
    }
}

// 3. شاشة جدول المباريات (ScheduleFragment)
class ScheduleFragment : Fragment(R.layout.fragment_vod_grid) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentVodGridBinding.bind(view)
        binding.tvSectionHeader.text = "جدول مباريات اليوم والقنوات الناقلة"
        binding.rvVodGrid.layoutManager = LinearLayoutManager(requireContext())
        binding.rvVodGrid.adapter = MatchesAdapter(OttRepository.getMatchesSchedule()) { match ->
            startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL, match.channel)
            })
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
        holder.binding.tvTeams.text = "${m.homeTeam}  VS  ${m.awayTeam}"
        holder.binding.tvMatchInfo.text = "${m.league} | 🎙️ ${m.commentator} | 📺 ${m.channel.name}"
        holder.binding.tvMatchStatus.text = when (m.status) {
            "LIVE" -> "🔴 مباشر الآن"
            "UPCOMING" -> "🕒 ${m.matchTime}"
            else -> "انتهت"
        }
        holder.binding.tvMatchStatus.setTextColor(
            if (m.status == "LIVE") Color.parseColor("#E50914") else Color.WHITE
        )
        holder.binding.root.setOnClickListener { onClick(m) }
    }
}

// 4. شاشة الإعدادات والتفعيل (SettingsFragment)
class SettingsFragment : Fragment(R.layout.fragment_settings) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentSettingsBinding.bind(view)
        binding.tvDeviceId.text = "معرف الجهاز (Device ID): ${SessionManager.getDeviceId(requireContext())}"

        binding.btnActivateCode.setOnClickListener {
            val code = binding.etActivationCode.text.toString().trim()
            if (code.isNotEmpty()) {
                SessionManager.saveToken(requireContext(), "TOKEN_$code")
                Toast.makeText(requireContext(), "تم تفعيل الاشتراك وحفظ الجلسة بنجاح ✅", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), "الرجاء إدخال كود التفعيل", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
""",

    # ==========================================
    # 8. الشاشة الأم (MainActivity مع 6 أقسام في Bottom Navigation)
    # ==========================================
    f"{PACKAGE_PATH}/presentation/main/MainActivity.kt": """package com.primeott.app.presentation.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.primeott.app.R
import com.primeott.app.databinding.ActivityMainBinding
import com.primeott.app.presentation.livetv.LiveTvFragment
import com.primeott.app.presentation.sections.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_movies -> VodFragment.newInstance(isSeries = false)
                R.id.nav_series -> VodFragment.newInstance(isSeries = true)
                R.id.nav_live -> LiveTvFragment()
                R.id.nav_schedule -> ScheduleFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> HomeFragment()
            }
            loadFragment(fragment)
            true
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
""",

    # ==========================================
    # 9. ملفات الـ XML للواجهات والقوائم (Layouts & Menus)
    # ==========================================
    f"{RES_PATH}/menu/bottom_nav_menu.xml": """<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:id="@+id/nav_home" android:icon="@android:drawable/ic_menu_view" android:title="الرئيسية" />
    <item android:id="@+id/nav_movies" android:icon="@android:drawable/ic_media_play" android:title="الأفلام" />
    <item android:id="@+id/nav_series" android:icon="@android:drawable/ic_menu_sort_by_size" android:title="المسلسلات" />
    <item android:id="@+id/nav_live" android:icon="@android:drawable/presence_video_online" android:title="البث المباشر" />
    <item android:id="@+id/nav_schedule" android:icon="@android:drawable/ic_menu_today" android:title="المباريات" />
    <item android:id="@+id/nav_settings" android:icon="@android:drawable/ic_menu_preferences" android:title="الإعدادات" />
</menu>
""",

    f"{RES_PATH}/layout/activity_main.xml": """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0F1014">

    <FrameLayout
        android:id="@+id/fragmentContainer"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toTopOf="@id/bottomNavigation"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottomNavigation"
        android:layout_width="0dp"
        android:layout_height="62dp"
        android:background="#181A20"
        app:itemIconTint="#FFFFFF"
        app:itemTextColor="#FFFFFF"
        app:labelVisibilityMode="labeled"
        app:menu="@menu/bottom_nav_menu"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
""",

    f"{RES_PATH}/layout/fragment_live_tv.xml": """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0F1014">

    <LinearLayout
        android:id="@+id/topBar"
        android:layout_width="0dp"
        android:layout_height="56dp"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingHorizontal="12dp"
        android:background="#181A20"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <EditText
            android:id="@+id/etSearchChannel"
            android:layout_width="0dp"
            android:layout_height="40dp"
            android:layout_weight="1"
            android:background="#262932"
            android:hint="ابحث عن قناة..."
            android:textColor="#FFFFFF"
            android:textColorHint="#888888"
            android:paddingHorizontal="12dp"
            android:singleLine="true" />

        <Button
            android:id="@+id/btnToggleFavorites"
            android:layout_width="wrap_content"
            android:layout_height="40dp"
            android:layout_marginStart="8dp"
            android:backgroundTint="#262932"
            android:text="★ المفضلة"
            android:textColor="#FFD700"
            android:alpha="0.4" />
    </LinearLayout>

    <androidx.constraintlayout.widget.Guideline
        android:id="@+id/splitGuideline"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        app:layout_constraintGuide_percent="0.34" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rvCategories"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:background="#14161C"
        app:layoutManager="androidx.recyclerview.widget.LinearLayoutManager"
        app:layout_constraintTop_toBottomOf="@id/topBar"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toStartOf="@id/splitGuideline" />

    <View
        android:id="@+id/divider"
        android:layout_width="1dp"
        android:layout_height="0dp"
        android:background="#262932"
        app:layout_constraintTop_toBottomOf="@id/topBar"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toEndOf="@id/splitGuideline" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rvChannels"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:padding="6dp"
        app:layoutManager="androidx.recyclerview.widget.LinearLayoutManager"
        app:layout_constraintTop_toBottomOf="@id/topBar"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toEndOf="@id/divider"
        app:layout_constraintEnd_toEndOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
""",

    f"{RES_PATH}/layout/item_category.xml": """<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="52dp"
    android:paddingHorizontal="10dp">

    <TextView
        android:id="@+id/tvCategoryName"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center_vertical"
        android:textSize="13sp"
        android:textStyle="bold"
        android:textColor="#FFFFFF" />
</FrameLayout>
""",

    f"{RES_PATH}/layout/item_channel.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="56dp"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:background="#181A20"
    android:layout_marginBottom="6dp"
    android:paddingHorizontal="12dp">

    <TextView
        android:id="@+id/tvChannelName"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textColor="#FFFFFF"
        android:textSize="14sp"
        android:textStyle="bold" />

    <TextView
        android:id="@+id/btnFav"
        android:layout_width="36dp"
        android:layout_height="36dp"
        android:gravity="center"
        android:text="☆"
        android:textSize="20sp"
        android:textColor="#FFD700" />
</LinearLayout>
""",

    f"{RES_PATH}/layout/dialog_ad_countdown.xml": """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#E6000000">

    <WebView
        android:id="@+id/adWebView"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_margin="20dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <Button
        android:id="@+id/btnSkipAd"
        android:layout_width="wrap_content"
        android:layout_height="44dp"
        android:layout_margin="28dp"
        android:backgroundTint="#262932"
        android:text="تخطي الإعلان (10)"
        android:textColor="#FFFFFF"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
""",

    f"{RES_PATH}/layout/activity_player.xml": """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#000000">

    <androidx.media3.ui.PlayerView
        android:id="@+id/playerView"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:use_controller="true"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:padding="12dp"
        android:background="#66000000"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <TextView
            android:id="@+id/tvChannelTitle"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textColor="#FFFFFF"
            android:textSize="16sp"
            android:textStyle="bold" />

        <Button
            android:id="@+id/btnSwitchServer"
            android:layout_width="wrap_content"
            android:layout_height="36dp"
            android:text="تبديل السيرفر"
            android:textSize="12sp"
            android:backgroundTint="#E50914" />

        <Button
            android:id="@+id/btnAspectRatio"
            android:layout_width="wrap_content"
            android:layout_height="36dp"
            android:layout_marginStart="8dp"
            android:text="الأبعاد"
            android:textSize="12sp"
            android:backgroundTint="#262932" />
    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
""",

    f"{RES_PATH}/layout/fragment_home.xml": """<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0F1014"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="190dp"
            android:background="#1F222A"
            android:padding="20dp"
            android:gravity="bottom"
            android:orientation="vertical">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="🔥 قمة الجولة مباشرة"
                android:textColor="#E50914"
                android:textStyle="bold" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="ريال مدريد VS برشلونة - beIN 1 HD"
                android:textColor="#FFFFFF"
                android:textSize="20sp"
                android:textStyle="bold"
                android:layout_marginTop="4dp" />

            <Button
                android:id="@+id/btnWatchFeatured"
                android:layout_width="wrap_content"
                android:layout_height="42dp"
                android:layout_marginTop="12dp"
                android:backgroundTint="#E50914"
                android:text="▶ شاهد البث المباشر الآن"
                android:textColor="#FFFFFF" />
        </LinearLayout>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="الأفلام الأكثر مشاهدة هذا الأسبوع"
            android:textColor="#FFFFFF"
            android:textSize="17sp"
            android:textStyle="bold"
            android:layout_marginTop="24dp"
            android:layout_marginBottom="12dp" />

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/rvHomeChannels"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />

    </LinearLayout>
</ScrollView>
""",

    f"{RES_PATH}/layout/fragment_vod_grid.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#0F1014"
    android:padding="12dp">

    <TextView
        android:id="@+id/tvSectionHeader"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textColor="#FFFFFF"
        android:textSize="18sp"
        android:textStyle="bold"
        android:layout_marginBottom="12dp" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rvVodGrid"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />
</LinearLayout>
""",

    f"{RES_PATH}/layout/item_vod_card.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="160dp"
    android:layout_height="130dp"
    android:layout_margin="6dp"
    android:background="#181A20"
    android:gravity="bottom"
    android:orientation="vertical"
    android:padding="12dp">

    <TextView
        android:id="@+id/tvVodTitle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textColor="#FFFFFF"
        android:textSize="14sp"
        android:textStyle="bold" />

    <TextView
        android:id="@+id/tvVodMeta"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:textColor="#FFD700"
        android:textSize="12sp" />
</LinearLayout>
""",

    f"{RES_PATH}/layout/item_match.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:background="#181A20"
    android:padding="14dp"
    android:layout_marginBottom="8dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/tvTeams"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textColor="#FFFFFF"
            android:textSize="16sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tvMatchStatus"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textStyle="bold" />
    </LinearLayout>

    <TextView
        android:id="@+id/tvMatchInfo"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="6dp"
        android:textColor="#AAAAAA"
        android:textSize="13sp" />
</LinearLayout>
""",

    f"{RES_PATH}/layout/fragment_settings.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#0F1014"
    android:padding="20dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="إعدادات الحساب والتفعيل (Prime OTT)"
        android:textColor="#FFFFFF"
        android:textSize="19sp"
        android:textStyle="bold" />

    <TextView
        android:id="@+id/tvDeviceId"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:textColor="#AAAAAA"
        android:textSize="14sp" />

    <EditText
        android:id="@+id/etActivationCode"
        android:layout_width="match_parent"
        android:layout_height="50dp"
        android:layout_marginTop="20dp"
        android:background="#181A20"
        android:hint="أدخل كود التفعيل (Activation Code)..."
        android:paddingHorizontal="14dp"
        android:textColor="#FFFFFF"
        android:textColorHint="#777777" />

    <Button
        android:id="@+id/btnActivateCode"
        android:layout_width="match_parent"
        android:layout_height="50dp"
        android:layout_marginTop="12dp"
        android:backgroundTint="#E50914"
        android:text="تفعيل الاشتراك الآن"
        android:textColor="#FFFFFF"
        android:textStyle="bold" />
</LinearLayout>
"""
}

def build_project():
    for filepath, content in files.items():
        dir_name = os.path.dirname(filepath)
        if dir_name:
            os.makedirs(dir_name, exist_ok=True)
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(content.strip() + "\n")
        print(f"[✓] Created: {filepath}")
    print("\n🚀 تم بناء مشروع Prime OTT بنجاح! قم بعمل Sync Project with Gradle Files ثم اضغط Run.")

if __name__ == "__main__":
    build_project()
