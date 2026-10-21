import os

PACKAGE_PATH = "app/src/main/java/com/primeott/app"
RES_PATH = "app/src/main/res"

updates = {
    # 1. إضافة روابط بوسترات وصور حقيقية في OttRepository
    f"{PACKAGE_PATH}/data/repository/OttRepository.kt": """package com.primeott.app.data.repository

import com.primeott.app.domain.model.*

object OttRepository {

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
        Channel("ch_1", "cat_bein", "beIN Sports 1 HD Premium", "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8c/BeIN_Sports_logo.svg/512px-BeIN_Sports_logo.svg.png", testStreams, favoriteChannelIds.contains("ch_1")),
        Channel("ch_2", "cat_bein", "beIN Sports 2 HD Premium", "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8c/BeIN_Sports_logo.svg/512px-BeIN_Sports_logo.svg.png", testStreams, favoriteChannelIds.contains("ch_2")),
        Channel("ch_3", "cat_bein", "beIN Sports 3 HD", "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8c/BeIN_Sports_logo.svg/512px-BeIN_Sports_logo.svg.png", testStreams, favoriteChannelIds.contains("ch_3")),
        Channel("ch_4", "cat_ssc", "SSC Sports 1 HD", "https://upload.wikimedia.org/wikipedia/commons/thumb/5/52/SSC_logo.svg/512px-SSC_logo.svg.png", testStreams, favoriteChannelIds.contains("ch_4")),
        Channel("ch_5", "cat_ssc", "SSC Sports 2 HD", "https://upload.wikimedia.org/wikipedia/commons/thumb/5/52/SSC_logo.svg/512px-SSC_logo.svg.png", testStreams, favoriteChannelIds.contains("ch_5")),
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
        VodItem("m1", "The Dark Knight", "أكشن", "9.0", "2024", "https://image.tmdb.org/t/p/w500/qJ2tW6WMUDux911r6m7haRef0WH.jpg", testStreams, false),
        VodItem("m2", "Inception", "خيال علمي", "8.8", "2025", "https://image.tmdb.org/t/p/w500/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg", testStreams, false),
        VodItem("m3", "Gladiator II", "تاريخي", "8.5", "2025", "https://image.tmdb.org/t/p/w500/2cxhvwyEwRlysAmRH4iodkvo0z5.jpg", testStreams, false),
        VodItem("m4", "John Wick 4", "أكشن", "8.2", "2024", "https://image.tmdb.org/t/p/w500/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg", testStreams, false),
        VodItem("m5", "Interstellar", "فضاء", "8.9", "2023", "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg", testStreams, false),
        VodItem("m6", "Oppenheimer", "دراما", "8.7", "2024", "https://image.tmdb.org/t/p/w500/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg", testStreams, false)
    )

    fun getSeries(): List<VodItem> = listOf(
        VodItem("s1", "Breaking Bad (الموسم 1)", "دراما", "9.5", "2024", "https://image.tmdb.org/t/p/w500/ggFHVNu6YYI5L9pCfOacjizRGt.jpg", testStreams, true),
        VodItem("s2", "House of the Dragon", "فانتازيا", "8.6", "2025", "https://image.tmdb.org/t/p/w500/t9XkeE7HzOsdQcDDDapDYh8Rrmt.jpg", testStreams, true),
        VodItem("s3", "Prison Break", "إثارة", "8.4", "2023", "https://image.tmdb.org/t/p/w500/5E1BhkCgjLBlqx557Z5yzcN0i88.jpg", testStreams, true),
        VodItem("s4", "The Last of Us", "أكشن", "8.8", "2025", "https://image.tmdb.org/t/p/w500/uKvVjHNqB5VmOrdxqAt2F7J78ED.jpg", testStreams, true)
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

    # 2. تحديث بطاقة الفيلم/المسلسل لعرض البوستر عبر Glide
    f"{RES_PATH}/layout/item_vod_card.xml": """<?xml version="1.0" encoding="utf-8"?>
<androidx.cardview.widget.CardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="155dp"
    android:layout_height="220dp"
    android:layout_margin="6dp"
    app:cardBackgroundColor="#181A20"
    app:cardCornerRadius="10dp"
    app:cardElevation="4dp">

    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent">

        <ImageView
            android:id="@+id/ivPoster"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:scaleType="centerCrop" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom"
            android:background="#CC0F1014"
            android:orientation="vertical"
            android:padding="10dp">

            <TextView
                android:id="@+id/tvVodTitle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:ellipsize="end"
                android:maxLines="1"
                android:textColor="#FFFFFF"
                android:textSize="13sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tvVodMeta"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:textColor="#FFD700"
                android:textSize="11sp" />
        </LinearLayout>
    </FrameLayout>
</androidx.cardview.widget.CardView>
""",

    # 3. إصلاح تداخل النص في بطاقة المباراة (item_match.xml)
    f"{RES_PATH}/layout/item_match.xml": """<?xml version="1.0" encoding="utf-8"?>
<androidx.cardview.widget.CardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="10dp"
    app:cardBackgroundColor="#181A20"
    app:cardCornerRadius="10dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="14dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <TextView
                android:id="@+id/tvMatchStatus"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:background="#262932"
                android:paddingHorizontal="10dp"
                android:paddingVertical="4dp"
                android:textSize="12sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tvTeams"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:gravity="end"
                android:paddingStart="12dp"
                android:textColor="#FFFFFF"
                android:textSize="16sp"
                android:textStyle="bold" />
        </LinearLayout>

        <View
            android:layout_width="match_parent"
            android:layout_height="1dp"
            android:layout_marginVertical="8dp"
            android:background="#262932" />

        <TextView
            android:id="@+id/tvMatchInfo"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="end"
            android:textColor="#B3B3B3"
            android:textSize="12sp" />
    </LinearLayout>
</androidx.cardview.widget.CardView>
""",

    # 4. تحسين ألوان أزرار المشغل وإخفائها التلقائي عند المشاهدة
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
        app:show_timeout="3500"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <LinearLayout
        android:id="@+id/topPlayerBar"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:padding="12dp"
        android:background="#80000000"
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
            android:layout_height="38dp"
            android:text="تبديل السيرفر"
            android:textColor="#FFFFFF"
            android:textStyle="bold"
            android:textSize="12sp"
            android:backgroundTint="#E50914" />

        <Button
            android:id="@+id/btnAspectRatio"
            android:layout_width="wrap_content"
            android:layout_height="38dp"
            android:layout_marginStart="8dp"
            android:text="الأبعاد"
            android:textColor="#FFFFFF"
            android:textStyle="bold"
            android:textSize="12sp"
            android:backgroundTint="#262932" />
    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
""",

    # 5. ربط Glide في VodGridAdapter لتحميل البوسترات
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
import com.bumptech.glide.Glide
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
    print(f"[✓] Upgraded: {filepath}")

print("\n✨ تم تحديث التصميم وإضافة البوسترات بنجاح!")
