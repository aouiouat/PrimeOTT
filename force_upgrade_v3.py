import os

PACKAGE_PATH = "app/src/main/java/com/primeott/app"
RES_PATH = "app/src/main/res"

# 1. حذف أي ملف MainActivity.kt قديم مكرر في المجلدات الفرعية لمنع فشل البناء
for root, dirs, files_list in os.walk(PACKAGE_PATH):
    for file in files_list:
        if file == "MainActivity.kt":
            full_p = os.path.join(root, file)
            if os.path.abspath(full_p) != os.path.abspath(os.path.join(PACKAGE_PATH, "MainActivity.kt")):
                os.remove(full_p)
                print(f"[!] Removed old duplicate: {full_p}")

# 2. تحديث activity_main.xml بشريط سفلي مخصص يدعم 6 أقسام (بما فيها 👥 جماعية Watch Together) بدون قيود
activity_main_xml = r"""<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0B0D13"
    android:orientation="vertical">

    <!-- الشريط العلوي الاحترافي -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="54dp"
        android:background="#11151F"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingHorizontal="12dp">

        <TextView
            android:layout_width="30dp"
            android:layout_height="30dp"
            android:background="#E50914"
            android:gravity="center"
            android:text="▶"
            android:textColor="#FFFFFF"
            android:textSize="15sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tvAppHeaderTitle"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:layout_weight="1"
            android:text="PRIME OTT"
            android:textColor="#FFFFFF"
            android:textSize="16sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/btnTopWatchRoom"
            android:layout_width="wrap_content"
            android:layout_height="34dp"
            android:layout_marginEnd="6dp"
            android:background="#2563EB"
            android:gravity="center"
            android:paddingHorizontal="10dp"
            android:text="👥 Watch Together"
            android:textColor="#FFFFFF"
            android:textSize="11.5sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/btnTopLang"
            android:layout_width="34dp"
            android:layout_height="34dp"
            android:layout_marginEnd="6dp"
            android:background="#181C26"
            android:gravity="center"
            android:text="🌐"
            android:textSize="14sp" />

        <TextView
            android:id="@+id/btnTopSettings"
            android:layout_width="wrap_content"
            android:layout_height="34dp"
            android:background="#181C26"
            android:gravity="center"
            android:paddingHorizontal="10dp"
            android:text="⚙️ حسابي"
            android:textColor="#FFFFFF"
            android:textSize="11.5sp"
            android:textStyle="bold" />
    </LinearLayout>

    <!-- حاوية الصفحات -->
    <FrameLayout
        android:id="@+id/fragmentContainer"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <!-- شريط التنقل السفلي المحدث بـ 6 تبويبات واضحة -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="60dp"
        android:background="#11151F"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:weightSum="6"
        android:paddingHorizontal="4dp">

        <TextView
            android:id="@+id/navTabHome"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="🏠\nالرئيسية"
            android:textColor="#E50914"
            android:textSize="10.5sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/navTabLive"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="📡\nالمباشر"
            android:textColor="#94A3B8"
            android:textSize="10.5sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/navTabMatches"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="⚽\nالمباريات"
            android:textColor="#94A3B8"
            android:textSize="10.5sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/navTabWatch"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="👥\nجماعية"
            android:textColor="#38BDF8"
            android:textSize="10.5sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/navTabMovies"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="🎬\nالأفلام"
            android:textColor="#94A3B8"
            android:textSize="10.5sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/navTabSeries"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center"
            android:text="📺\nالمسلسلات"
            android:textColor="#94A3B8"
            android:textSize="10.5sp"
            android:textStyle="bold" />
    </LinearLayout>

</LinearLayout>
"""

with open(f"{RES_PATH}/layout/activity_main.xml", "w", encoding="utf-8") as f:
    f.write(activity_main_xml.strip() + "\n")

# 3. تحديث MainActivity.kt الموحد
main_activity_kt = r"""package com.primeott.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.ActivityMainBinding
import com.primeott.app.presentation.common.AppStrings
import com.primeott.app.presentation.common.LocaleHelper
import com.primeott.app.presentation.livetv.LiveTvFragment
import com.primeott.app.presentation.sections.*
import com.primeott.app.presentation.watch.WatchRoomActivity
import com.primeott.app.presentation.watch.WatchTogetherFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var activeTag: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLayoutDirection(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        OttRepository.initFavorites(this)
        applyLocalizedTitles()

        switchToTab("HOME")

        binding.navTabHome.setOnClickListener { switchToTab("HOME") }
        binding.navTabLive.setOnClickListener { switchToTab("LIVE") }
        binding.navTabMatches.setOnClickListener { switchToTab("MATCHES") }
        binding.navTabWatch.setOnClickListener { switchToTab("WATCH") }
        binding.navTabMovies.setOnClickListener { switchToTab("MOVIES") }
        binding.navTabSeries.setOnClickListener { switchToTab("SERIES") }

        binding.btnTopWatchRoom.setOnClickListener { switchToTab("WATCH") }
        binding.btnTopSettings.setOnClickListener { switchToTab("SETTINGS") }
        binding.btnTopLang.setOnClickListener { showQuickLanguageDialog() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeTag != "HOME") {
                    switchToTab("HOME")
                } else {
                    finish()
                }
            }
        })

        handleIncomingDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingDeepLink(intent)
    }

    private fun handleIncomingDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        val codeFromQuery = data.getQueryParameter("code")
        val codeFromPath = if (data.path?.startsWith("/join/") == true) {
            data.path?.substringAfter("/join/")?.trim()
        } else null
        val roomCode = (codeFromQuery ?: codeFromPath)?.uppercase() ?: return

        if (roomCode.isNotEmpty()) {
            startActivity(Intent(this, WatchRoomActivity::class.java).apply {
                putExtra(WatchRoomActivity.EXTRA_ROOM_CODE, roomCode)
            })
        }
    }

    private fun switchToTab(tag: String) {
        if (activeTag == tag && supportFragmentManager.findFragmentByTag(tag) != null) return
        val tx = supportFragmentManager.beginTransaction()
        tx.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)

        supportFragmentManager.fragments.forEach { frag ->
            if (frag.isVisible) tx.hide(frag)
        }

        var target = supportFragmentManager.findFragmentByTag(tag)
        if (target == null) {
            target = when (tag) {
                "LIVE" -> LiveTvFragment()
                "MATCHES" -> ScheduleFragment()
                "WATCH" -> WatchTogetherFragment()
                "MOVIES" -> VodFragment.newInstance(false)
                "SERIES" -> VodFragment.newInstance(true)
                "SETTINGS" -> SettingsFragment()
                else -> HomeFragment()
            }
            tx.add(R.id.fragmentContainer, target, tag)
        } else {
            tx.show(target)
        }
        tx.commitAllowingStateLoss()
        activeTag = tag
        updateBottomBarHighlight(tag)
    }

    private fun updateBottomBarHighlight(tag: String) {
        val tabs = mapOf(
            "HOME" to binding.navTabHome,
            "LIVE" to binding.navTabLive,
            "MATCHES" to binding.navTabMatches,
            "WATCH" to binding.navTabWatch,
            "MOVIES" to binding.navTabMovies,
            "SERIES" to binding.navTabSeries
        )
        tabs.forEach { (key, tv) ->
            if (key == tag) {
                tv.setTextColor(Color.parseColor("#E50914"))
                tv.setBackgroundColor(Color.parseColor("#181C26"))
            } else {
                tv.setTextColor(if (key == "WATCH") Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
                tv.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private fun applyLocalizedTitles() {
        binding.navTabHome.text = AppStrings.get(this, "🏠\nالرئيسية", "🏠\nAccueil", "🏠\nHome")
        binding.navTabLive.text = AppStrings.get(this, "📡\nالمباشر", "📡\nDirect", "📡\nLive TV")
        binding.navTabMatches.text = AppStrings.get(this, "⚽\nالمباريات", "⚽\nMatchs", "⚽\nMatches")
        binding.navTabWatch.text = AppStrings.get(this, "👥\nجماعية", "👥\nEnsemble", "👥\nWatch Party")
        binding.navTabMovies.text = AppStrings.get(this, "🎬\nالأفلام", "🎬\nFilms", "🎬\nMovies")
        binding.navTabSeries.text = AppStrings.get(this, "📺\nالمسلسلات", "📺\nSéries", "📺\nSeries")
        binding.btnTopSettings.text = AppStrings.get(this, "⚙️ حسابي", "⚙️ Profil", "⚙️ Settings")
    }

    private fun showQuickLanguageDialog() {
        val langs = arrayOf("🇩🇿 العربية (RTL)", "🇫🇷 Français", "🇬🇧 English")
        val codes = arrayOf("ar", "fr", "en")
        AlertDialog.Builder(this)
            .setTitle(AppStrings.get(this, "🌐 اختر لغة التطبيق", "🌐 Choisir la langue", "🌐 Select Language"))
            .setItems(langs) { _, which ->
                SessionManager.setLanguage(this, codes[which])
                LocaleHelper.applyLayoutDirection(this)
                recreate()
            }
            .show()
    }
}
"""

with open(f"{PACKAGE_PATH}/MainActivity.kt", "w", encoding="utf-8") as f:
    f.write(main_activity_kt.strip() + "\n")

# 4. ضمان ظهور شاشة اختيار اللغة والترحيب الجديدة في الإصدار v3
with open(f"{PACKAGE_PATH}/data/remote/SessionManager.kt", "r", encoding="utf-8") as f:
    sm_code = f.read()

sm_code = sm_code.replace('private const val KEY_ONBOARDED = "onboarding_done"', 'private const val KEY_ONBOARDED = "onboarding_done_v3"')
with open(f"{PACKAGE_PATH}/data/remote/SessionManager.kt", "w", encoding="utf-8") as f:
    f.write(sm_code)

print("[✓] Fixed duplicate MainActivity & updated 6-Tab Bottom Navigation with Watch Together!")
