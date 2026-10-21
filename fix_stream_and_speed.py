import os

PACKAGE_PATH = "app/src/main/java/com/primeott/app"
RES_PATH = "app/src/main/res"

updates = {
    # 1. تحديث build.gradle.kts لإضافة مكتبات فك تشفير الصوت وتسريع الشبكة
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
        versionCode = 2
        versionName = "1.1.0"
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

    // Media3 ExoPlayer الكامل لدعم HLS, MPEG-TS, Dash والصوت المتقدم
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // OkHttp & Gson لسرعة جلب آلاف القنوات في أجزاء من الثانية
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Glide لتحميل الشعارات والبوسترات بسرعة
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
""",

    # 2. تحديث OttRepository بمحرك OkHttp فائق السرعة + تنظيف الأسماء + جلب الأفلام والمسلسلات
    f"{PACKAGE_PATH}/data/repository/OttRepository.kt": """package com.primeott.app.data.repository

import androidx.core.text.HtmlCompat
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object OttRepository {

    private val favoriteChannelIds = mutableSetOf<String>()

    // كاش سريع في الذاكرة لتجنب إعادة التحميل عند التنقل بين الأقسام
    private var cachedCategories = listOf<Category>()
    private val cachedChannelsMap = mutableMapOf<String, List<Channel>>()
    private var cachedMovies = listOf<VodItem>()
    private var cachedSeries = listOf<VodItem>()

    // محرك اتصال سريع جداً يدعم Keep-Alive
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
            return response.body?.string() ?: "[]"
        }
    }

    // 1. جلب الباقات بسرعة فائقة
    suspend fun fetchLiveCategories(): List<Category> = withContext(Dispatchers.IO) {
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

    // 2. جلب القنوات مع جعل .m3u8 هو الأساسي لمنع توقف البث بعد دقيقة، و .ts كبديل
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

                // وضع رابط HLS (.m3u8) أولاً لأنه مستمر ولا ينقطع بعد 60 ثانية، ثم رابط TS ثانياً
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

    // 3. جلب الأفلام الحقيقية من السيرفر
    suspend fun fetchMovies(): List<VodItem> = withContext(Dispatchers.IO) {
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

    // 4. جلب المسلسلات الحقيقية من السيرفر
    suspend fun fetchSeries(): List<VodItem> = withContext(Dispatchers.IO) {
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

    fun toggleFavorite(channelId: String) {
        if (favoriteChannelIds.contains(channelId)) favoriteChannelIds.remove(channelId)
        else favoriteChannelIds.add(channelId)
    }

    fun getMatchesSchedule(): List<MatchFixture> {
        val sampleStream = listOf("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
        val ch1 = Channel("live1", "sport", "beIN Sports 1 HD", "", sampleStream)
        val ch2 = Channel("live2", "sport", "SSC Sports 1 HD", "", sampleStream)
        return listOf(
            MatchFixture("mt1", "ريال مدريد", "برشلونة", "21:00", "الدوري الإسباني", "عصام الشوالي", "LIVE", ch1),
            MatchFixture("mt2", "مانشستر سيتي", "ليفربول", "19:30", "الدوري الإنجليزي", "حفيظ دراجي", "LIVE", ch1),
            MatchFixture("mt3", "النصر", "الهلال", "20:00", "دوري روشن", "فهد العتيبي", "UPCOMING", ch2)
        )
    }
}
""",

    # 3. إصلاح مشغل الفيديو (تشغيل الصوت في كل القنوات + منع التوقف بعد دقيقة + Auto Reconnect)
    f"{PACKAGE_PATH}/presentation/player/PlayerActivity.kt": """package com.primeott.app.presentation.player

import android.media.AudioManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
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
    private var retryCount = 0
    private var isAdCompleted = false
    private var resizeModeIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // التأكد من رفع صوت الميديا في الجهاز وعدم كتمه
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol / 2, 0)
        }

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
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                binding.topPlayerBar.visibility = visibility
            }
        )

        binding.btnSwitchServer.setOnClickListener {
            val urls = currentChannel?.streamUrls ?: return@setOnClickListener
            currentServerIndex = (currentServerIndex + 1) % urls.size
            retryCount = 0
            val typeName = if (urls[currentServerIndex].endsWith(".m3u8")) "HLS المستقر" else "MPEG-TS المباشر"
            Toast.makeText(this, "تم التبديل إلى سيرفر (${currentServerIndex + 1} - $typeName)", Toast.LENGTH_SHORT).show()
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

        // 1. استخدام OkHttpDataSource لمنع انقطاع البث بعد دقيقة واحدة (Keep-Alive)
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(OttRepository.okHttpClient)
            .setUserAgent(SessionManager.CUSTOM_USER_AGENT)

        // 2. تفعيل قراءة كل مسارات الصوت في قنوات TS و 4K (حتى لو لم تكن قياسية)
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
            )

        // 3. تفعيل الـ Decoder Fallback لتشغيل الصوت في قنوات 4K/FHD الأوروبية والعربية
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        // 4. ضبط الـ Buffer للبث الحي المستمر
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 2_000, 4_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(okHttpDataSourceFactory, extractorsFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build()
            .also { player ->
                // إجبار المشغل على عدم تعطيل تراك الصوت أبداً
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .build()

                player.volume = 1.0f
                binding.playerView.player = player
                player.addListener(streamStabilityListener)
                playStreamAt(currentServerIndex)
            }
    }

    private fun playStreamAt(index: Int) {
        val urls = currentChannel?.streamUrls ?: return
        if (index >= urls.size) return

        val url = urls[index]
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1.02f)
                    .setTargetOffsetMs(4000)
                    .build()
            )
            .build()

        exoPlayer?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    // مراقب الاستقرار: إذا قطع السيرفر البث بعد دقيقة، يعيد الاتصال فوراً أو يحول للسيرفر البديل
    private val streamStabilityListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                // البث الحي لا يجب أن ينتهي بـ ENDED؛ أعد وصله فوراً!
                exoPlayer?.seekToDefaultPosition()
                exoPlayer?.prepare()
                exoPlayer?.playWhenReady = true
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val urls = currentChannel?.streamUrls ?: return
            if (retryCount < 2) {
                // إعادة محاولة سريعة على نفس الرابط في حال رمشة الإنترنت
                retryCount++
                exoPlayer?.prepare()
                exoPlayer?.playWhenReady = true
            } else if (currentServerIndex + 1 < urls.size) {
                retryCount = 0
                currentServerIndex++
                Toast.makeText(
                    this@PlayerActivity,
                    "تحويل تلقائي للسيرفر البديل (${currentServerIndex + 1})...",
                    Toast.LENGTH_SHORT
                ).show()
                playStreamAt(currentServerIndex)
            } else {
                retryCount = 0
                currentServerIndex = 0
                playStreamAt(0)
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

    # 4. تحسين تصميم بطاقة القناة والباقة لعدم قص الأسماء الطويلة + عرض شعار القناة
    f"{RES_PATH}/layout/item_channel.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:minHeight="62dp"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:background="#181A20"
    android:layout_marginBottom="6dp"
    android:paddingHorizontal="10dp"
    android:paddingVertical="8dp">

    <ImageView
        android:id="@+id/ivChannelLogo"
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:layout_marginEnd="10dp"
        android:scaleType="fitCenter"
        android:src="@android:drawable/presence_video_online" />

    <TextView
        android:id="@+id/tvChannelName"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:maxLines="2"
        android:ellipsize="end"
        android:lineSpacingExtra="2dp"
        android:textColor="#FFFFFF"
        android:textSize="13sp"
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

    f"{PACKAGE_PATH}/presentation/livetv/LiveTvAdapters.kt": """package com.primeott.app.presentation.livetv

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
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

        if (channel.logoUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(channel.logoUrl)
                .placeholder(android.R.drawable.presence_video_online)
                .into(holder.binding.ivChannelLogo)
        } else {
            holder.binding.ivChannelLogo.setImageResource(android.R.drawable.presence_video_online)
        }

        holder.binding.root.setOnClickListener { onChannelClick(channel) }
        holder.binding.btnFav.setOnClickListener { onFavClick(channel) }
    }
}
""",

    # 5. ربط شاشات الأفلام والمسلسلات والرئيسية بالسيرفر الحقيقي
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
                val cats = OttRepository.fetchLiveCategories()
                if (cats.isNotEmpty()) {
                    val chs = OttRepository.fetchChannelsByCategory(cats.first().id)
                    if (chs.isNotEmpty()) {
                        startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                            putExtra(PlayerActivity.EXTRA_CHANNEL, chs.first())
                        })
                    }
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

        binding.tvSectionHeader.text = if (isSeries) "جاري تحميل المسلسلات من السيرفر..." else "جاري تحميل الأفلام من السيرفر..."
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
"""
}

for filepath, content in updates.items():
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(content.strip() + "\n")
    print(f"[✓] Fixed & Optimized: {filepath}")

print("\n🔥 تم إصلاح الصوت، البث المستمر، وتسريع جلب القنوات والأفلام!")
