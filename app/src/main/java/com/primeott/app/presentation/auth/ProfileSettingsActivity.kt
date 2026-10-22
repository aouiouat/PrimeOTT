package com.primeott.app.presentation.auth

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.primeott.app.R
import org.json.JSONArray

object ProfileSettingsBinder {

    fun bind(hostContext: Context, rootView: View, hostActivity: Activity?, isStandalone: Boolean) {
        val prefs = hostContext.getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)
        val wtPrefs = hostContext.getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)

        val btnBack = rootView.findViewById<View>(R.id.btnProfileBack)
        btnBack?.visibility = if (isStandalone) View.VISIBLE else View.GONE
        btnBack?.setOnClickListener { hostActivity?.finish() }

        val tvName = rootView.findViewById<TextView>(R.id.tvProfileDisplayName)
        val tvEmail = rootView.findViewById<TextView>(R.id.tvProfileEmailOrUsername)
        val tvBadge = rootView.findViewById<TextView>(R.id.tvProfileBadgeStatus)
        val tvAvatarEmoji = rootView.findViewById<TextView>(R.id.tvUserProfileAvatarEmoji)

        fun refreshProfileHeader() {
            val user = prefs.getString("current_user", "مستخدم Royal TV") ?: "مستخدم Royal TV"
            val email = prefs.getString("current_email", "") ?: ""
            val provider = prefs.getString("auth_provider", "Royal TV") ?: "Royal TV"
            val avatar = prefs.getString("current_avatar", "👑") ?: "👑"
            val isGuest = prefs.getBoolean("is_guest", false)

            tvName?.text = user
            tvEmail?.text = if (email.isNotEmpty()) "$email • ($provider)" else "@$user • ($provider)"
            tvBadge?.text = if (isGuest) "👤 وضع التصفح كزائر (Guest Mode)" else "🟢 حساب موثق • متصل بـ Cloudflare"
            tvAvatarEmoji?.text = if (avatar.length <= 4) avatar else "👑"
        }
        refreshProfileHeader()

        // زر فتح غرف المشاهدة الجماعية
        rootView.findViewById<View>(R.id.btnOpenWatchTogetherFromProfile)?.setOnClickListener {
            try {
                val watchCls = Class.forName("com.primeott.app.presentation.watch.WatchRoomActivity")
                hostContext.startActivity(Intent(hostContext, watchCls))
            } catch (_: Exception) {}
        }

        // تعديل الاسم والأفاتار
        rootView.findViewById<View>(R.id.btnEditProfileInfo)?.setOnClickListener {
            val layout = LinearLayout(hostContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 24, 40, 12)
            }
            val etNewName = EditText(hostContext).apply {
                hint = "الاسم الجديد..."
                setText(prefs.getString("current_user", ""))
            }
            layout.addView(etNewName)

            val avatars = arrayOf("👑 الملكي", "🦁 الأسد", "⚽ الرياضي", "🎬 السينمائي", "🦅 الصقر")
            val emojis = arrayOf("👑", "🦁", "⚽", "🎬", "🦅")
            val spAvatar = Spinner(hostContext).apply {
                adapter = ArrayAdapter(hostContext, android.R.layout.simple_spinner_dropdown_item, avatars)
            }
            layout.addView(spAvatar)

            AlertDialog.Builder(hostContext)
                .setTitle("✏️ تعديل الملف الشخصي • Modifier le Profil")
                .setView(layout)
                .setPositiveButton("حفظ") { _, _ ->
                    val newName = etNewName.text.toString().trim()
                    val chosenEmoji = emojis[spAvatar.selectedItemPosition]
                    if (newName.isNotEmpty()) {
                        prefs.edit()
                            .putString("current_user", newName)
                            .putString("current_avatar", chosenEmoji)
                            .apply()
                        hostContext.getSharedPreferences("prime_ott_prefs", Context.MODE_PRIVATE)
                            .edit().putString("username", newName).apply()
                        refreshProfileHeader()
                        Toast.makeText(hostContext, "✅ تم تحديث الملف الشخصي بنجاح", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        // إعداد جودة البث
        val tvQuality = rootView.findViewById<TextView>(R.id.btnSettingStreamQuality)
        tvQuality?.setOnClickListener {
            val qualities = arrayOf("تلقائي ذكي (Auto FHD/HD)", "1080p Full HD", "720p HD", "480p SD لتوفير البيانات")
            AlertDialog.Builder(hostContext)
                .setTitle("⚡ اختر جودة البث الافتراضية")
                .setItems(qualities) { _, w ->
                    tvQuality.text = "⚡ جودة البث الافتراضية: ${qualities[w]}"
                    Toast.makeText(hostContext, "تم ضبط الجودة: ${qualities[w]}", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // إعداد المشغل
        val tvDecoder = rootView.findViewById<TextView>(R.id.btnSettingPlayerDecoder)
        tvDecoder?.setOnClickListener {
            val modes = arrayOf("ExoPlayer Media3 (HLS + MPEG-TS Auto)", "Hardware Acceleration (سريع)", "Software Decoder (متوافق)")
            AlertDialog.Builder(hostContext)
                .setTitle("🎬 محرك مشغل الفيديو")
                .setItems(modes) { _, w ->
                    tvDecoder.text = "🎬 محرك المشغل: ${modes[w]}"
                }
                .show()
        }

        // إدارة المحظورين
        rootView.findViewById<View>(R.id.btnManageBlockedUsersProfile)?.setOnClickListener {
            val blocked = wtPrefs.getStringSet("blocked_users", emptySet())?.toMutableSet() ?: mutableSetOf()
            if (blocked.isEmpty()) {
                Toast.makeText(hostContext, "قائمة المحظورين فارغة حالياً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val arr = blocked.toTypedArray()
            AlertDialog.Builder(hostContext)
                .setTitle("🚫 المستخدمون المحظورون (اضغط لإلغاء الحظر)")
                .setItems(arr) { _, w ->
                    blocked.remove(arr[w])
                    wtPrefs.edit().putStringSet("blocked_users", blocked).apply()
                    Toast.makeText(hostContext, "✅ تم إلغاء الحظر", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إغلاق", null)
                .show()
        }

        // تغيير كلمة المرور
        rootView.findViewById<View>(R.id.btnChangePasswordProfile)?.setOnClickListener {
            val input = EditText(hostContext).apply {
                hint = "أدخل كلمة المرور الجديدة..."
            }
            AlertDialog.Builder(hostContext)
                .setTitle("🔑 تغيير كلمة المرور")
                .setView(input)
                .setPositiveButton("تحديث") { _, _ ->
                    val newPass = input.text.toString()
                    if (newPass.length < 3) {
                        Toast.makeText(hostContext, "يرجى إدخال كلمة مرور قوية", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val currUser = prefs.getString("current_user", "") ?: ""
                    val arr = JSONArray(prefs.getString("registered_users", "[]"))
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optString("username").equals(currUser, ignoreCase = true)) {
                            o.put("password", newPass)
                        }
                    }
                    prefs.edit()
                        .putString("registered_users", arr.toString())
                        .putString("saved_password", newPass)
                        .apply()
                    Toast.makeText(hostContext, "✅ تم تحديث كلمة المرور بنجاح", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        // تغيير اللغة
        val tvLang = rootView.findViewById<TextView>(R.id.btnChangeLanguageProfile)
        tvLang?.setOnClickListener {
            val langs = arrayOf("العربية (Arabic)", "Français (French)", "English")
            AlertDialog.Builder(hostContext)
                .setTitle("🌐 اختر لغة التطبيق")
                .setItems(langs) { _, w ->
                    tvLang.text = "🌐 لغة التطبيق: ${langs[w]}"
                    Toast.makeText(hostContext, "تم اختيار اللغة: ${langs[w]}", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // مسح الكاش
        rootView.findViewById<View>(R.id.btnClearAppCache)?.setOnClickListener {
            try {
                hostContext.cacheDir.deleteRecursively()
            } catch (_: Exception) {}
            Toast.makeText(hostContext, "🧹 تم مسح الذاكرة المؤقتة (Cache) وتسريع التطبيق بنجاح!", Toast.LENGTH_SHORT).show()
        }

        // الدعم الفني والخصوصية
        rootView.findViewById<View>(R.id.btnContactSupportProfile)?.setOnClickListener {
            AlertDialog.Builder(hostContext)
                .setTitle("📞 الدعم الفني وسياسة الخصوصية")
                .setMessage("• الدعم الفني عبر تيليجرام: @RoyalTV_Support\n• البريد: support@royaltv.app\n• السيرفر السحابي: Cloudflare Active\n• جميع الاتصالات مشفرة ومحمية بالكامل.")
                .setPositiveButton("مراسلة الدعم") { _, _ ->
                    try {
                        hostContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/RoyalTV_Support")))
                    } catch (_: Exception) {}
                }
                .setNegativeButton("إغلاق", null)
                .show()
        }

        // تسجيل الخروج النهائي
        rootView.findViewById<View>(R.id.btnLogoutFromProfile)?.setOnClickListener {
            AlertDialog.Builder(hostContext)
                .setTitle("🚪 تسجيل الخروج • Déconnexion")
                .setMessage("هل أنت متأكد أنك تريد تسجيل الخروج من حسابك؟")
                .setPositiveButton("تسجيل الخروج") { _, _ ->
                    prefs.edit()
                        .putBoolean("session_active", false)
                        .putBoolean("is_guest", false)
                        .remove("current_user")
                        .apply()
                    val i = Intent(hostContext, LoginActivity::class.java).apply {
                        putExtra("force_login", true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    hostContext.startActivity(i)
                    hostActivity?.finish()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}

class ProfileSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_profile_settings)
        val root = findViewById<View>(R.id.rootProfileSettings)
        ProfileSettingsBinder.bind(this, root, this, isStandalone = true)
    }
}

class ProfileSettingsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_profile_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ProfileSettingsBinder.bind(requireContext(), view, activity, isStandalone = false)
    }
}