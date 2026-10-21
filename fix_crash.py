import os

PACKAGE_PATH = "app/src/main/java/com/primeott/app"
RES_PATH = "app/src/main/res"

updates = {
    # 1. تحديث واجهة MainActivity لدعم 6 أقسام في الشريط السفلي بدون Crash
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
        app:layout_constraintBottom_toTopOf="@id/bottomNavBar"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- شريط سفلي مخصص يحتوي على الـ 6 أقسام كاملة بدون قيود Material -->
    <LinearLayout
        android:id="@+id/bottomNavBar"
        android:layout_width="0dp"
        android:layout_height="60dp"
        android:orientation="horizontal"
        android:background="#181A20"
        android:gravity="center"
        android:weightSum="6"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <TextView
            android:id="@+id/navHome"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="🏠\nالرئيسية"
            android:textColor="#E50914"
            android:textSize="11sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/navMovies"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="🎬\nالأفلام"
            android:textColor="#B3B3B3"
            android:textSize="11sp" />

        <TextView
            android:id="@+id/navSeries"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="📺\nالمسلسلات"
            android:textColor="#B3B3B3"
            android:textSize="11sp" />

        <TextView
            android:id="@+id/navLive"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="📡\nالبث المباشر"
            android:textColor="#B3B3B3"
            android:textSize="11sp" />

        <TextView
            android:id="@+id/navSchedule"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="⚽\nالمباريات"
            android:textColor="#B3B3B3"
            android:textSize="11sp" />

        <TextView
            android:id="@+id/navSettings"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="⚙️\nالإعدادات"
            android:textColor="#B3B3B3"
            android:textSize="11sp" />

    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
""",

    # 2. تحديث كود MainActivity لربط الـ 6 أقسام
    f"{PACKAGE_PATH}/presentation/main/MainActivity.kt": """package com.primeott.app.presentation.main

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.primeott.app.R
import com.primeott.app.databinding.ActivityMainBinding
import com.primeott.app.presentation.livetv.LiveTvFragment
import com.primeott.app.presentation.sections.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navItems: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        navItems = listOf(
            binding.navHome,
            binding.navMovies,
            binding.navSeries,
            binding.navLive,
            binding.navSchedule,
            binding.navSettings
        )

        if (savedInstanceState == null) {
            selectTab(binding.navHome, HomeFragment())
        }

        binding.navHome.setOnClickListener { selectTab(binding.navHome, HomeFragment()) }
        binding.navMovies.setOnClickListener { selectTab(binding.navMovies, VodFragment.newInstance(false)) }
        binding.navSeries.setOnClickListener { selectTab(binding.navSeries, VodFragment.newInstance(true)) }
        binding.navLive.setOnClickListener { selectTab(binding.navLive, LiveTvFragment()) }
        binding.navSchedule.setOnClickListener { selectTab(binding.navSchedule, ScheduleFragment()) }
        binding.navSettings.setOnClickListener { selectTab(binding.navSettings, SettingsFragment()) }
    }

    private fun selectTab(selectedView: TextView, fragment: Fragment) {
        navItems.forEach { tv ->
            tv.setTextColor(if (tv == selectedView) Color.parseColor("#E50914") else Color.parseColor("#B3B3B3"))
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
""",

    # 3. إصلاح دورة حياة الواجهات في LiveTvFragment
    f"{PACKAGE_PATH}/presentation/livetv/LiveTvFragment.kt": """package com.primeott.app.presentation.livetv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.primeott.app.R
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.FragmentLiveTvBinding
import com.primeott.app.domain.model.Channel
import com.primeott.app.presentation.player.PlayerActivity

class LiveTvFragment : Fragment() {

    private var _binding: FragmentLiveTvBinding? = null
    private val binding get() = _binding!!

    private lateinit var categoriesAdapter: CategoriesAdapter
    private lateinit var channelsAdapter: ChannelsAdapter

    private var selectedCategoryId: String = "cat_bein"
    private var searchQuery: String = ""
    private var showFavoritesOnly: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_live_tv, container, false)
    }

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

    # 4. إصلاح AdCountdownDialog
    f"{PACKAGE_PATH}/presentation/ads/AdCountdownDialog.kt": """package com.primeott.app.presentation.ads

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.DialogFragment
import com.primeott.app.R
import com.primeott.app.databinding.DialogAdCountdownBinding

class AdCountdownDialog(
    private val adUrl: String = "https://www.google.com",
    private val onAdFinished: () -> Unit = {}
) : DialogFragment() {

    private var _binding: DialogAdCountdownBinding? = null
    private val binding get() = _binding!!
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        isCancelable = false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_ad_countdown, container, false)
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

    # 5. إصلاح باقي الشاشات (AppSectionsFragments.kt)
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

class HomeFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

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
        binding.rvHomeChannels.adapter = VodGridAdapter(OttRepository.getMovies()) { vod ->
            val tempChannel = Channel(vod.id, "vod", vod.title, vod.posterUrl, vod.streamUrls)
            startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL, tempChannel)
            })
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
    print(f"[✓] Fixed: {filepath}")

print("\n✅ تم تطبيق التعديلات وإصلاح المشكل بنجاح!")
