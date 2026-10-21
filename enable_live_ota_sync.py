import os

PACKAGE_PATH = "app/src/main/java/com/primeott/app"
RES_PATH = "app/src/main/res"

updates = {
    # 1. إضافة متغيرات التحكم عن بعد (Remote Config) في SessionManager.kt
    f"{PACKAGE_PATH}/data/remote/SessionManager.kt": """package com.primeott.app.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

object SessionManager {
    private const val PREF_NAME = "prime_ott_secure_prefs"
    private const val KEY_TOKEN = "session_token"
    private const val KEY_EXPIRE = "expire_date"

    const val CURRENT_APP_VERSION = 2
    const val CLOUDFLARE_API_URL = "https://prime-ott-panel.prime-ott-stream.workers.dev"

    var XTREAM_BASE_URL = "http://kwick.cc:8080"
    var XTREAM_USER = "Harry123"
    var XTREAM_PASS = "Alicea"

    // إعدادات التحكم عن بعد (تتحدث لحظياً بدون تحديث التطبيق)
    var AD_URL = "https://www.google.com"
    var ADS_ENABLED = true
    var AD_SECONDS = 10
    var BANNER_BADGE = "🔥 قمة الجولة مباشرة"
    var BANNER_TITLE = "ريال مدريد VS برشلونة - beIN 1 FHD"
    var BANNER_STREAM_ID = "6171"
    var ANNOUNCEMENT_TEXT = ""
    var REMOTE_UPDATE_VERSION = 2
    var REMOTE_UPDATE_URL = ""
    var REMOTE_UPDATE_MSG = ""

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

    # 2. تحديث واجهة الرئيسية (fragment_home.xml) لإضافة شريط الرسائل العاجلة والبانر المتغير عن بعد
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

        <!-- شريط التنبيهات العاجلة عن بعد (يظهر فقط عندما تكتب رسالة في لوحة التحكم) -->
        <TextView
            android:id="@+id/tvRemoteAnnouncement"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="#2610B981"
            android:padding="10dp"
            android:layout_marginBottom="12dp"
            android:textColor="#34D399"
            android:textSize="13sp"
            android:textStyle="bold"
            android:visibility="gone" />

        <!-- البانر الرئيسي المتغير عن بعد (Dynamic Remote Hero Banner) -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="190dp"
            android:background="#181A20"
            android:gravity="bottom"
            android:orientation="vertical"
            android:padding="18dp">

            <TextView
                android:id="@+id/tvBannerBadge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="🔥 قمة الجولة مباشرة"
                android:textColor="#E50914"
                android:textSize="13sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tvBannerTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="ريال مدريد VS برشلونة - beIN 1 FHD"
                android:textColor="#FFFFFF"
                android:textSize="20sp"
                android:textStyle="bold" />

            <Button
                android:id="@+id/btnWatchFeatured"
                android:layout_width="wrap_content"
                android:layout_height="40dp"
                android:layout_marginTop="12dp"
                android:backgroundTint="#E50914"
                android:text="▶ شاهد البث المباشر الآن"
                android:textColor="#FFFFFF"
                android:textStyle="bold" />
        </LinearLayout>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            android:layout_marginBottom="10dp"
            android:text="🎬 أحدث الأفلام المضافة في السيرفر"
            android:textColor="#FFFFFF"
            android:textSize="16sp"
            android:textStyle="bold" />

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/rvHomeChannels"
            android:layout_width="match_parent"
            android:layout_height="235dp" />

    </LinearLayout>
</ScrollView>
""",

    # 3. تحديث AdCountdownDialog ليقرأ مدة الإعلان بالثواني من لوحة التحكم عن بعد
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
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.databinding.DialogAdCountdownBinding

class AdCountdownDialog(
    private val adUrl: String = SessionManager.AD_URL,
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

        val durationMs = (SessionManager.AD_SECONDS.coerceAtLeast(1)) * 1000L
        binding.btnSkipAd.isEnabled = false
        timer = object : CountDownTimer(durationMs, 1_000) {
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
"""
}

for filepath, content in updates.items():
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(content.strip() + "\n")
    print(f"[✓] Updated: {filepath}")
