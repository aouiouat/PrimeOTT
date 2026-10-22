package com.primeott.app.presentation.auth

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.primeott.app.R
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

object ProfileSettingsBinder {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    // جلب كائن الحساب الخاص بالإيميل المتصل حالياً فقط
    private fun getCurrentAccountObject(prefs: android.content.SharedPreferences): JSONObject? {
        val currentEmail = (prefs.getString("current_account_email", null)
            ?: prefs.getString("current_email", null) ?: "").trim().lowercase(Locale.US)
        val currentUser = prefs.getString("current_user", "") ?: ""
        val arr = JSONArray(prefs.getString("registered_users", "[]"))
        for (i in 0 until arr.length()) {
            val u = arr.getJSONObject(i)
            val em = u.optString("email", "").trim().lowercase(Locale.US)
            val un = u.optString("username", "")
            if ((currentEmail.isNotEmpty() && em == currentEmail) ||
                (currentEmail.isEmpty() && un.equals(currentUser, ignoreCase = true))) {
                return u
            }
        }
        return null
    }

    // حفظ التعديلات داخل سجل هذا الحساب فقط دون التأثير على أي حساب آخر
    private fun updateCurrentAccountInStorage(
        context: Context,
        updater: (JSONObject) -> Unit
    ) {
        val prefs = context.getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)
        val currentEmail = (prefs.getString("current_account_email", null)
            ?: prefs.getString("current_email", null) ?: "").trim().lowercase(Locale.US)
        val currentUser = prefs.getString("current_user", "") ?: ""
        val arr = JSONArray(prefs.getString("registered_users", "[]"))
        var updatedObj: JSONObject? = null

        for (i in 0 until arr.length()) {
            val u = arr.getJSONObject(i)
            val em = u.optString("email", "").trim().lowercase(Locale.US)
            val un = u.optString("username", "")
            if ((currentEmail.isNotEmpty() && em == currentEmail) ||
                (currentEmail.isEmpty() && un.equals(currentUser, ignoreCase = true))) {
                updater(u)
                updatedObj = u
                break
            }
        }

        if (updatedObj != null) {
            // رفع جميع تعديلات الحساب فوراً إلى سيرفر Cloudflare
            val syncObj = JSONObject(updatedObj.toString())
            Thread {
                try {
                    val req = Request.Builder()
                        .url("https://prime-ott-panel.prime-ott-stream.workers.dev/user/profile")
                        .post(syncObj.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    httpClient.newCall(req).execute().close()
                } catch (_: Exception) {}
            }.start()

            val newUsername = updatedObj.optString("username", currentUser)
            val newAvatar = updatedObj.optString("avatar", "👑")
            val newPhoto = updatedObj.optString("photoUri", "")
            val hasPass = updatedObj.optBoolean("hasPassword", true)

            prefs.edit().apply {
                putString("registered_users", arr.toString())
                putString("current_user", newUsername)
                putString("current_avatar", newAvatar)
                putString("current_photo_uri", newPhoto)
                putBoolean("current_has_password", hasPass)
                apply()
            }
            context.getSharedPreferences("prime_ott_prefs", Context.MODE_PRIVATE)
                .edit().putString("username", newUsername).apply()
            context.getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
                .edit().putString("display_name", newUsername).apply()
        }
    }

    fun bind(
        hostContext: Context,
        rootView: View,
        hostActivity: Activity?,
        isStandalone: Boolean,
        photoPickLauncher: ActivityResultLauncher<Intent>? = null
    ) {
        val prefs = hostContext.getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)

        val btnBack = rootView.findViewById<View>(R.id.btnProfileBack)
        btnBack?.visibility = if (isStandalone) View.VISIBLE else View.GONE
        btnBack?.setOnClickListener { hostActivity?.finish() }

        val tvName = rootView.findViewById<TextView>(R.id.tvProfileDisplayName)
        val tvEmail = rootView.findViewById<TextView>(R.id.tvProfileEmailOrUsername)
        val tvAvatarEmoji = rootView.findViewById<TextView>(R.id.tvUserProfileAvatarEmoji)
        val ivPhoto = rootView.findViewById<ImageView>(R.id.ivUserProfilePhoto)
        val btnCreatePass = rootView.findViewById<View>(R.id.btnCreatePasswordForEmailAccount)

        fun refreshProfileHeader() {
            val acc = getCurrentAccountObject(prefs)
            val user = acc?.optString("username") ?: prefs.getString("current_user", "مستخدم Royal TV") ?: "مستخدم Royal TV"
            val email = acc?.optString("email") ?: prefs.getString("current_email", "") ?: ""
            val avatar = acc?.optString("avatar") ?: prefs.getString("current_avatar", "👑") ?: "👑"
            val photoUriStr = acc?.optString("photoUri") ?: prefs.getString("current_photo_uri", "") ?: ""
            val hasPassword = acc?.optBoolean("hasPassword", true) ?: prefs.getBoolean("current_has_password", true)
            val isGuest = prefs.getBoolean("is_guest", false)

            tvName?.text = user
            tvEmail?.text = if (email.isNotEmpty() && !email.endsWith("@royaltv.local")) {
                "🔒 $email (@$user)"
            } else {
                "@$user"
            }

            // إظهار زر "إنشاء كلمة مرور للحساب" إذا كان الحساب مسجلاً عبر Google وليس له كلمة مرور بعد
            btnCreatePass?.visibility = if (!isGuest && !hasPassword) View.VISIBLE else View.GONE

            if (photoUriStr.isNotEmpty()) {
                try {
                    ivPhoto?.visibility = View.VISIBLE
                    tvAvatarEmoji?.visibility = View.GONE
                    ivPhoto?.setImageURI(Uri.parse(photoUriStr))
                } catch (_: Exception) {
                    ivPhoto?.visibility = View.GONE
                    tvAvatarEmoji?.visibility = View.VISIBLE
                    tvAvatarEmoji?.text = "👑"
                }
            } else {
                ivPhoto?.visibility = View.GONE
                tvAvatarEmoji?.visibility = View.VISIBLE
                tvAvatarEmoji?.text = if (avatar.length <= 4) avatar else "👑"
            }
        }
        refreshProfileHeader()

        // نافذة إنشاء كلمة مرور لحساب الإيميل (ليتمكن من الدخول بـ username + password أو email + password)
        fun showCreateOrChangePasswordDialog(isCreatingFirstTime: Boolean) {
            val acc = getCurrentAccountObject(prefs)
            val currentEmail = acc?.optString("email") ?: prefs.getString("current_email", "") ?: ""
            val currentUsername = acc?.optString("username") ?: prefs.getString("current_user", "") ?: ""

            val layout = LinearLayout(hostContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(44, 28, 44, 16)
            }

            val tvEmailReadOnly = EditText(hostContext).apply {
                setText("🔒 الإيميل: $currentEmail")
                isEnabled = false
                isFocusable = false
                setTextColor(Color.parseColor("#80D8FF"))
            }
            layout.addView(tvEmailReadOnly)

            val etUserForLogin = EditText(hostContext).apply {
                hint = "اسم المستخدم للدخول (Username)"
                setText(currentUsername)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }
            layout.addView(etUserForLogin)

            val etNewPass = EditText(hostContext).apply {
                hint = "كلمة المرور الجديدة (Password)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }
            layout.addView(etNewPass)

            val etConfirmPass = EditText(hostContext).apply {
                hint = "تأكيد كلمة المرور (Confirm Password)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }
            layout.addView(etConfirmPass)

            val dialogTitle = if (isCreatingFirstTime) {
                "🔑 إنشاء كلمة مرور للحساب"
            } else {
                "🔑 تغيير كلمة المرور"
            }

            AlertDialog.Builder(hostContext)
                .setTitle(dialogTitle)
                .setMessage("بعد الحفظ يمكنك تسجيل الدخول إما باسم المستخدم وكلمة المرور أو بالإيميل وكلمة المرور:")
                .setView(layout)
                .setPositiveButton("حفظ وتفعيل ✓") { _, _ ->
                    val chosenUsername = etUserForLogin.text.toString().trim()
                    val pass1 = etNewPass.text.toString()
                    val pass2 = etConfirmPass.text.toString()

                    if (chosenUsername.length < 3) {
                        Toast.makeText(hostContext, "❌ اسم المستخدم يجب أن لا يقل عن 3 أحرف", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (pass1.length < 4) {
                        Toast.makeText(hostContext, "❌ يرجى إدخال كلمة مرور لا تقل عن 4 أحرف", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (pass1 != pass2) {
                        Toast.makeText(hostContext, "❌ كلمتا المرور غير متطابقتين!", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    // التأكد أن اسم المستخدم غير مأخوذ من إيميل آخر
                    val allUsers = JSONArray(prefs.getString("registered_users", "[]"))
                    for (i in 0 until allUsers.length()) {
                        val u = allUsers.getJSONObject(i)
                        val em = u.optString("email", "")
                        val un = u.optString("username", "")
                        if (!em.equals(currentEmail, ignoreCase = true) && un.equals(chosenUsername, ignoreCase = true)) {
                            Toast.makeText(hostContext, "❌ اسم المستخدم هذا مستخدم لحساب آخر", Toast.LENGTH_LONG).show()
                            return@setPositiveButton
                        }
                    }

                    updateCurrentAccountInStorage(hostContext) { accObj ->
                        accObj.put("username", chosenUsername)
                        accObj.put("password", pass1)
                        accObj.put("hasPassword", true)
                    }

                    prefs.edit()
                        .putString("saved_username", chosenUsername)
                        .putString("saved_password", pass1)
                        .apply()

                    // مزامنة الحساب وكلمة المرور مع سيرفر Cloudflare
                    Thread {
                        try {
                            val syncPayload = JSONObject().apply {
                                put("username", chosenUsername)
                                put("email", currentEmail)
                                put("password", pass1)
                                put("fullName", chosenUsername)
                            }
                            val req = Request.Builder()
                                .url("https://prime-ott-panel.prime-ott-stream.workers.dev/auth/register")
                                .post(syncPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                .build()
                            httpClient.newCall(req).execute().close()
                        } catch (_: Exception) {}
                    }.start()

                    refreshProfileHeader()
                    Toast.makeText(
                        hostContext,
                        "✅ تم حفظ كلمة المرور! يمكنك الآن الدخول بـ ($chosenUsername) أو ($currentEmail) وكلمة المرور.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        btnCreatePass?.setOnClickListener {
            showCreateOrChangePasswordDialog(isCreatingFirstTime = true)
        }

        // تغيير الصورة الشخصية (مرتبطة بهذا الحساب فقط)
        val openPhotoOptions = View.OnClickListener {
            val opts = arrayOf(
                "🖼️ اختيار صورة من معرض الهاتف",
                "👑 اختيار رمز أفاتار",
                "🗑️ إزالة الصورة الحالية"
            )
            AlertDialog.Builder(hostContext)
                .setTitle("📷 تغيير الصورة الشخصية للحساب")
                .setItems(opts) { _, which ->
                    when (which) {
                        0 -> {
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "image/*"
                            }
                            if (photoPickLauncher != null) {
                                photoPickLauncher.launch(intent)
                            } else {
                                val fallback = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                hostActivity?.startActivityForResult(fallback, 707)
                            }
                        }
                        1 -> {
                            val avatars = arrayOf("👑 الملكي", "🦁 الأسد", "⚽ الرياضي", "🎬 السينمائي", "🦅 الصقر")
                            val emojis = arrayOf("👑", "🦁", "⚽", "🎬", "🦅")
                            AlertDialog.Builder(hostContext)
                                .setTitle("اختر الأفاتار")
                                .setItems(avatars) { _, idx ->
                                    updateCurrentAccountInStorage(hostContext) { accObj ->
                                        accObj.put("avatar", emojis[idx])
                                        accObj.put("photoUri", "")
                                    }
                                    refreshProfileHeader()
                                }
                                .show()
                        }
                        2 -> {
                            updateCurrentAccountInStorage(hostContext) { accObj ->
                                accObj.put("photoUri", "")
                                accObj.put("avatar", "👑")
                            }
                            refreshProfileHeader()
                        }
                    }
                }
                .setNegativeButton("إغلاق", null)
                .show()
        }
        rootView.findViewById<View>(R.id.flUserProfileAvatar)?.setOnClickListener(openPhotoOptions)
        rootView.findViewById<View>(R.id.btnChangeProfilePhoto)?.setOnClickListener(openPhotoOptions)

        // تعديل اسم المستخدم فقط (مع منع تعديل الإيميل نهائياً)
        rootView.findViewById<View>(R.id.btnEditProfileInfo)?.setOnClickListener {
            val acc = getCurrentAccountObject(prefs)
            val currentUsername = acc?.optString("username") ?: prefs.getString("current_user", "") ?: ""
            val currentEmail = acc?.optString("email") ?: prefs.getString("current_email", "") ?: ""

            val layout = LinearLayout(hostContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(44, 28, 44, 16)
            }

            val tvLockedEmailLabel = TextView(hostContext).apply {
                text = "🔒 البريد الإلكتروني المرتبط بالحساب (لا يمكن تعديله):"
                setTextColor(Color.parseColor("#9AA5B8"))
                textSize = 12f
            }
            layout.addView(tvLockedEmailLabel)

            val etLockedEmail = EditText(hostContext).apply {
                setText(if (currentEmail.isNotEmpty()) currentEmail else "غير محدد")
                isEnabled = false
                isFocusable = false
                setTextColor(Color.parseColor("#64748B"))
            }
            layout.addView(etLockedEmail)

            val tvUsernameLabel = TextView(hostContext).apply {
                text = "✏️ اسم المستخدم (Username):"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(0, 14, 0, 4)
            }
            layout.addView(tvUsernameLabel)

            val etNewName = EditText(hostContext).apply {
                hint = "اسم المستخدم الجديد..."
                setText(currentUsername)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }
            layout.addView(etNewName)

            AlertDialog.Builder(hostContext)
                .setTitle("✏️ تعديل اسم المستخدم")
                .setView(layout)
                .setPositiveButton("حفظ التغييرات") { _, _ ->
                    val newName = etNewName.text.toString().trim()
                    if (newName.length < 3) {
                        Toast.makeText(hostContext, "❌ اسم المستخدم يجب أن لا يقل عن 3 أحرف", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    // التحقق من عدم استخدام الاسم من طرف إيميل آخر
                    val arr = JSONArray(prefs.getString("registered_users", "[]"))
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val em = o.optString("email", "")
                        val un = o.optString("username", "")
                        if (!em.equals(currentEmail, ignoreCase = true) && un.equals(newName, ignoreCase = true)) {
                            Toast.makeText(hostContext, "❌ اسم المستخدم هذا محجوز لحساب آخر", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                    }

                    updateCurrentAccountInStorage(hostContext) { accObj ->
                        accObj.put("username", newName)
                        accObj.put("fullName", newName)
                    }

                    refreshProfileHeader()
                    Toast.makeText(hostContext, "✅ تم تحديث اسم المستخدم لهذا الحساب بنجاح", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        // 1. قسم إعدادات الحساب وكلمة المرور
        rootView.findViewById<View>(R.id.cardSectionAccount)?.setOnClickListener {
            val acc = getCurrentAccountObject(prefs)
            val hasPass = acc?.optBoolean("hasPassword", true) ?: true
            val passOptionLabel = if (hasPass) {
                "🔑 تغيير كلمة المرور الخاصة بالحساب"
            } else {
                "🔑 إنشاء كلمة مرور للحساب (للدخول باسم المستخدم أو الإيميل)"
            }
            val items = arrayOf(
                "✏️ تعديل اسم المستخدم",
                "📷 تغيير الصورة الشخصية",
                passOptionLabel
            )
            AlertDialog.Builder(hostContext)
                .setTitle("🔐 إعدادات الحساب وكلمة المرور")
                .setItems(items) { _, w ->
                    when (w) {
                        0 -> rootView.findViewById<View>(R.id.btnEditProfileInfo)?.performClick()
                        1 -> rootView.findViewById<View>(R.id.btnChangeProfilePhoto)?.performClick()
                        2 -> showCreateOrChangePasswordDialog(isCreatingFirstTime = !hasPass)
                    }
                }
                .setNegativeButton("إغلاق", null)
                .show()
        }

        // 2. قسم إعدادات جودة المشاهدة (معزولة لكل حساب)
        rootView.findViewById<View>(R.id.cardSectionPlayback)?.setOnClickListener {
            val acc = getCurrentAccountObject(prefs)
            val savedQ = acc?.optInt("prefQualityIdx", 0) ?: 0
            val qualities = arrayOf(
                "⚡ جودة تلقائية ذكية (Auto FHD / HD)",
                "🎬 جودة عالية جداً (1080p Full HD)",
                "📺 جودة متوسطة (720p HD)",
                "📱 توفير بيانات الإنترنت (480p)"
            )
            AlertDialog.Builder(hostContext)
                .setTitle("📺 إعدادات جودة المشاهدة")
                .setSingleChoiceItems(qualities, savedQ) { dialog, which ->
                    updateCurrentAccountInStorage(hostContext) { accObj ->
                        accObj.put("prefQualityIdx", which)
                    }
                    Toast.makeText(hostContext, "✅ تم ضبط الجودة لحسابك: ${qualities[which]}", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton("إغلاق", null)
                .show()
        }

        // 3. قسم إعدادات الإشعارات والصوت (معزولة لكل حساب)
        rootView.findViewById<View>(R.id.cardSectionAudioNotif)?.setOnClickListener {
            val acc = getCurrentAccountObject(prefs)
            val labels = arrayOf(
                "تفعيل إشعارات التطبيق",
                "تحسين نقاء الصوت في المشاهدة الجماعية",
                "تشغيل الصوت تلقائياً عند فتح البث"
            )
            val checked = booleanArrayOf(
                acc?.optBoolean("notifEnabled", true) ?: true,
                acc?.optBoolean("voiceClear", true) ?: true,
                acc?.optBoolean("autoAudio", true) ?: true
            )
            AlertDialog.Builder(hostContext)
                .setTitle("🔔 إعدادات الإشعارات والصوت")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("حفظ") { _, _ ->
                    updateCurrentAccountInStorage(hostContext) { accObj ->
                        accObj.put("notifEnabled", checked[0])
                        accObj.put("voiceClear", checked[1])
                        accObj.put("autoAudio", checked[2])
                    }
                    Toast.makeText(hostContext, "✅ تم حفظ الإعدادات لحسابك", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        // 4. قسم اللغة وتنظيف الذاكرة
        rootView.findViewById<View>(R.id.cardSectionLanguageApp)?.setOnClickListener {
            val opts = arrayOf(
                "🌐 اللغة: العربية",
                "🌐 Langue : Français",
                "🌐 Language: English",
                "🧹 مسح الذاكرة المؤقتة لتسريع التطبيق"
            )
            AlertDialog.Builder(hostContext)
                .setTitle("🌐 لغة التطبيق والذاكرة")
                .setItems(opts) { _, w ->
                    if (w == 3) {
                        try { hostContext.cacheDir.deleteRecursively() } catch (_: Exception) {}
                        Toast.makeText(hostContext, "✅ تم تنظيف الذاكرة المؤقتة بنجاح", Toast.LENGTH_SHORT).show()
                    } else {
                        val langCode = when (w) { 1 -> "fr"; 2 -> "en"; else -> "ar" }
                        updateCurrentAccountInStorage(hostContext) { accObj ->
                            accObj.put("appLang", langCode)
                        }
                        Toast.makeText(hostContext, "✅ تم حفظ إعدادات اللغة لحسابك", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("إغلاق", null)
                .show()
        }

        // 5. قسم المساعدة والدعم الفني
        rootView.findViewById<View>(R.id.cardSectionSupport)?.setOnClickListener {
            AlertDialog.Builder(hostContext)
                .setTitle("💬 المساعدة والدعم الفني")
                .setMessage("مرحباً بك في مركز مساعدة Royal TV.\n\n• للتواصل المباشر عبر تيليجرام: @RoyalTV_Support\n• البريد الإلكتروني: support@royaltv.app")
                .setPositiveButton("مراسلة عبر تيليجرام") { _, _ ->
                    try {
                        hostContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/RoyalTV_Support")))
                    } catch (_: Exception) {}
                }
                .setNegativeButton("إغلاق", null)
                .show()
        }

        // زر تسجيل الخروج السفلي الوحيد
        rootView.findViewById<View>(R.id.btnLogoutFromProfile)?.setOnClickListener {
            AlertDialog.Builder(hostContext)
                .setTitle("🚪 تسجيل الخروج")
                .setMessage("هل تريد تسجيل الخروج من حسابك الحالي؟")
                .setPositiveButton("تسجيل الخروج") { _, _ ->
                    prefs.edit()
                        .putBoolean("session_active", false)
                        .putBoolean("is_guest", false)
                        .remove("current_user")
                        .remove("current_account_email")
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

    // حفظ الصورة المختارة من المعرض لهذا الحساب فقط دون الحسابات الأخرى
    fun handlePickedPhoto(context: Context, rootView: View?, uri: Uri?) {
        if (uri == null) return
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        updateCurrentAccountInStorage(context) { accObj ->
            accObj.put("photoUri", uri.toString())
        }

        if (rootView != null) {
            val iv = rootView.findViewById<ImageView>(R.id.ivUserProfilePhoto)
            val tv = rootView.findViewById<TextView>(R.id.tvUserProfileAvatarEmoji)
            iv?.visibility = View.VISIBLE
            tv?.visibility = View.GONE
            iv?.setImageURI(uri)
        }
        Toast.makeText(context, "✅ تم تحديث الصورة الشخصية لحسابك بنجاح", Toast.LENGTH_SHORT).show()
    }
}

class ProfileSettingsActivity : AppCompatActivity() {

    private var rootViewRef: View? = null

    private val photoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            ProfileSettingsBinder.handlePickedPhoto(this, rootViewRef, res.data?.data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_profile_settings)
        val root = findViewById<View>(R.id.rootProfileSettings)
        rootViewRef = root
        ProfileSettingsBinder.bind(this, root, this, isStandalone = true, photoPickLauncher = photoLauncher)
    }
}

class ProfileSettingsFragment : Fragment() {

    private var rootViewRef: View? = null

    private val photoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && context != null) {
            ProfileSettingsBinder.handlePickedPhoto(requireContext(), rootViewRef, res.data?.data)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_profile_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootViewRef = view
        ProfileSettingsBinder.bind(requireContext(), view, activity, isStandalone = false, photoPickLauncher = photoLauncher)
    }
}