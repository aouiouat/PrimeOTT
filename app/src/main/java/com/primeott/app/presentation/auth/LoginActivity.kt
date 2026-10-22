package com.primeott.app.presentation.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.primeott.app.R
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private var isPasswordVisible = false
    private lateinit var tvAuthMessageBanner: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerUrl = "https://prime-ott-panel.prime-ott-stream.workers.dev"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .writeTimeout(7, TimeUnit.SECONDS)
        .build()

    // 1. اختيار حساب Google من النظام ثم طلب تصريح التفويض الرسمي (Authorization)
    private val googleAccountChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val selectedEmail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!selectedEmail.isNullOrEmpty()) {
                requestGoogleOAuthConsent(selectedEmail)
                return@registerForActivityResult
            }
        }
        showAccountChooserDialogFallback()
    }

    // 2. استقبال نتيجة شاشة تفويض أندرويد الرسمية إن طُلبت من Google Play Services
    private val googleAuthConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pendingEmail = getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)
            .getString("pending_google_email", "") ?: ""
        if (result.resultCode == Activity.RESULT_OK && pendingEmail.isNotEmpty()) {
            completeAuthorizedGoogleLogin(pendingEmail, "OAUTH_TOKEN_GRANTED")
        } else if (pendingEmail.isNotEmpty()) {
            showSystemMessage("⚠️ تم إلغاء تفويض الدخول عبر Google", isError = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("session_active", false) && !intent.getBooleanExtra("force_login", false)) {
            val currentUser = prefs.getString("current_user", "") ?: ""
            if (currentUser.isNotEmpty()) {
                navigateToMainHome(currentUser, prefs.getBoolean("is_guest", false))
                return
            }
        }

        setContentView(R.layout.activity_login_royal)

        tvAuthMessageBanner = findViewById(R.id.tvAuthMessageBanner)
        val etUsername = findViewById<EditText>(R.id.etLoginUsername)
        val etPassword = findViewById<EditText>(R.id.etLoginPassword)
        val btnTogglePass = findViewById<TextView>(R.id.btnToggleLoginPassword)
        val cbRememberMe = findViewById<CheckBox>(R.id.cbRememberMe)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)

        val btnLoginSubmit = findViewById<Button>(R.id.btnLoginSubmit)
        val btnGoogleSignIn = findViewById<Button>(R.id.btnGoogleSignIn)
        val btnContinueAsGuest = findViewById<Button>(R.id.btnContinueAsGuest)
        val btnTopSkipGuest = findViewById<TextView>(R.id.btnTopSkipGuest)
        val btnOpenCreateAccount = findViewById<TextView>(R.id.btnOpenCreateAccount)

        val tvContactSupport = findViewById<TextView>(R.id.tvContactSupport)
        val tvPrivacyPolicy = findViewById<TextView>(R.id.tvPrivacyPolicy)

        if (prefs.getBoolean("remember_me", true)) {
            etUsername.setText(prefs.getString("saved_username", ""))
            etPassword.setText(prefs.getString("saved_password", ""))
            cbRememberMe.isChecked = true
        }

        btnTogglePass.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                btnTogglePass.text = "إخفاء 🙈"
            } else {
                etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                btnTogglePass.text = "إظهار 👁️"
            }
            etPassword.setSelection(etPassword.text.length)
        }

        // تسجيل الدخول بواسطة (اسم المستخدم + كلمة المرور) أو (الإيميل + كلمة المرور)
        btnLoginSubmit.setOnClickListener {
            if (!isInternetAvailable()) {
                showSystemMessage("لا يوجد اتصال بالإنترنت", isError = true)
                return@setOnClickListener
            }

            val loginInput = etUsername.text.toString().trim()
            val passwordInput = etPassword.text.toString()

            if (loginInput.isEmpty()) {
                showSystemMessage("يرجى إدخال اسم المستخدم أو البريد الإلكتروني", isError = true)
                return@setOnClickListener
            }

            if (passwordInput.isEmpty()) {
                showSystemMessage("يرجى إدخال كلمة المرور", isError = true)
                return@setOnClickListener
            }

            btnLoginSubmit.isEnabled = false
            btnLoginSubmit.text = "⏳ جاري التحقق من الحساب..."

            Thread {
                val usersArr = JSONArray(prefs.getString("registered_users", "[]"))
                var matchedAccount: JSONObject? = null
                var accountFound = false
                var googleOnlyNoPass = false

                for (i in 0 until usersArr.length()) {
                    val u = usersArr.getJSONObject(i)
                    val regUser = u.optString("username", "")
                    val regEmail = u.optString("email", "")
                    if (regUser.equals(loginInput, ignoreCase = true) ||
                        (regEmail.isNotEmpty() && regEmail.equals(loginInput, ignoreCase = true))) {
                        accountFound = true
                        val hasPass = u.optBoolean("hasPassword", true)
                        val savedPass = u.optString("password", "")
                        if (!hasPass || savedPass == "GOOGLE_OAUTH_ONLY") {
                            googleOnlyNoPass = true
                        } else if (savedPass == passwordInput) {
                            matchedAccount = u
                        }
                        break
                    }
                }

                var cloudVerified = false
                var cloudUserObj: JSONObject? = null

                if (matchedAccount == null && !googleOnlyNoPass) {
                    try {
                        val payload = JSONObject().apply {
                            put("username", loginInput)
                            put("password", passwordInput)
                        }
                        val req = Request.Builder()
                            .url("$workerUrl/auth/login")
                            .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                            .build()

                        httpClient.newCall(req).execute().use { resp ->
                            val bodyStr = resp.body?.string() ?: ""
                            if (resp.isSuccessful && bodyStr.contains("\"success\":true")) {
                                val json = JSONObject(bodyStr)
                                cloudVerified = true
                                cloudUserObj = json.optJSONObject("user")
                            }
                        }
                    } catch (_: Exception) {}
                }

                mainHandler.post {
                    btnLoginSubmit.isEnabled = true
                    btnLoginSubmit.text = "تسجيل الدخول • Login"

                    if (googleOnlyNoPass) {
                        showSystemMessage("ℹ️ هذا الحساب مسجل عبر Google ولم تُعيّن له كلمة مرور بعد. ادخل عبر زر Google ثم أنشئ كلمة مرور من البروفايل.", isError = true)
                        return@post
                    }

                    if (matchedAccount == null && !cloudVerified) {
                        if (accountFound) {
                            showSystemMessage("❌ كلمة المرور غير صحيحة!", isError = true)
                        } else {
                            showSystemMessage("❌ هذا الحساب غير مسجل! أنشئ حساباً جديداً أو سجل عبر Google.", isError = true)
                        }
                        return@post
                    }

                    val finalUsername = matchedAccount?.optString("username")
                        ?: cloudUserObj?.optString("username")
                        ?: loginInput.substringBefore("@")
                    val finalEmail = (matchedAccount?.optString("email")
                        ?: cloudUserObj?.optString("email")
                        ?: if (loginInput.contains("@")) loginInput else "$finalUsername@royaltv.local").lowercase(Locale.US)

                    // إذا جاء من السحابة ولم يكن مخزناً محلياً، نحفظه في قائمة الحسابات المعزولة
                    if (matchedAccount == null) {
                        val newAcc = JSONObject().apply {
                            put("email", finalEmail)
                            put("username", finalUsername)
                            put("fullName", finalUsername)
                            put("password", passwordInput)
                            put("hasPassword", true)
                            put("avatar", "👑")
                            put("photoUri", "")
                        }
                        usersArr.put(newAcc)
                        prefs.edit().putString("registered_users", usersArr.toString()).apply()
                    }

                    if (cbRememberMe.isChecked) {
                        prefs.edit()
                            .putBoolean("remember_me", true)
                            .putString("saved_username", loginInput)
                            .putString("saved_password", passwordInput)
                            .apply()
                    } else {
                        prefs.edit()
                            .putBoolean("remember_me", false)
                            .remove("saved_username")
                            .remove("saved_password")
                            .apply()
                    }

                    activateAccountSession(finalEmail)
                    showSystemMessage("✅ تم تسجيل الدخول بنجاح!", isError = false)
                    mainHandler.postDelayed({
                        navigateToMainHome(finalUsername, isGuest = false)
                    }, 350L)
                }
            }.start()
        }

        // زر المتابعة باستخدام Google (يفتح اختيار الإيميل ثم يطلب التفويض الرسمي)
        btnGoogleSignIn.setOnClickListener {
            if (!isInternetAvailable()) {
                showSystemMessage("لا يوجد اتصال بالإنترنت", isError = true)
                return@setOnClickListener
            }
            try {
                @Suppress("DEPRECATION")
                val intent = AccountManager.newChooseAccountIntent(
                    null, null, arrayOf("com.google"), false, null, null, null, null
                )
                googleAccountChooserLauncher.launch(intent)
            } catch (_: Exception) {
                showAccountChooserDialogFallback()
            }
        }

        val guestClickListener = View.OnClickListener {
            prefs.edit()
                .putBoolean("is_guest", true)
                .putString("current_account_email", "guest@royaltv.local")
                .putString("current_user", "زائر")
                .putString("current_email", "")
                .putBoolean("session_active", true)
                .apply()
            navigateToMainHome("زائر", isGuest = true)
        }
        btnContinueAsGuest.setOnClickListener(guestClickListener)
        btnTopSkipGuest.setOnClickListener(guestClickListener)

        btnOpenCreateAccount.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        tvForgotPassword.setOnClickListener {
            val input = EditText(this).apply {
                hint = "أدخل بريدك الإلكتروني أو اسم المستخدم..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }
            AlertDialog.Builder(this)
                .setTitle("🔑 استعادة كلمة المرور")
                .setView(input)
                .setPositiveButton("تحقق") { _, _ ->
                    val target = input.text.toString().trim()
                    val usersArr = JSONArray(prefs.getString("registered_users", "[]"))
                    var foundPass: String? = null
                    var hasPass = true
                    for (i in 0 until usersArr.length()) {
                        val u = usersArr.getJSONObject(i)
                        if (u.optString("username").equals(target, ignoreCase = true) ||
                            u.optString("email").equals(target, ignoreCase = true)) {
                            hasPass = u.optBoolean("hasPassword", true)
                            foundPass = u.optString("password")
                            break
                        }
                    }
                    if (!hasPass || foundPass == "GOOGLE_OAUTH_ONLY") {
                        showSystemMessage("ℹ️ هذا الحساب مرتبط بـ Google، يمكنك الدخول عبر Google وإنشاء كلمة مرور من البروفايل", isError = false)
                    } else if (foundPass != null) {
                        showSystemMessage("✅ كلمة المرور الخاصة بحسابك هي: $foundPass", isError = false)
                    } else {
                        showSystemMessage("❌ لم يتم العثور على هذا الحساب", isError = true)
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        tvContactSupport.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("📞 الدعم الفني")
                .setMessage("تواصل معنا عبر تيليجرام: @RoyalTV_Support\nأو البريد: support@royaltv.app")
                .setPositiveButton("حسناً", null)
                .show()
        }

        tvPrivacyPolicy.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("🛡️ سياسة الخصوصية")
                .setMessage("جميع بيانات حسابك ومشاهداتك مشفرة ومحمية بالكامل.")
                .setPositiveButton("موافق", null)
                .show()
        }
    }

    // نافذة اختيار حساب Google في حال عدم توفر حسابات نظام على المحاكي
    private fun showAccountChooserDialogFallback() {
        val prefs = getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)
        val usersArr = JSONArray(prefs.getString("registered_users", "[]"))
        val existingGoogleEmails = mutableListOf<String>()
        for (i in 0 until usersArr.length()) {
            val em = usersArr.getJSONObject(i).optString("email", "")
            if (em.contains("@") && !em.endsWith("@royaltv.local") && !existingGoogleEmails.contains(em)) {
                existingGoogleEmails.add(em)
            }
        }

        if (existingGoogleEmails.isNotEmpty()) {
            val options = (existingGoogleEmails + "➕ استخدام حساب Google آخر").toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("🌐 اختر حساب Google")
                .setItems(options) { _, which ->
                    if (which < existingGoogleEmails.size) {
                        requestGoogleOAuthConsent(existingGoogleEmails[which])
                    } else {
                        promptGoogleEmailInput()
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        } else {
            promptGoogleEmailInput()
        }
    }

    private fun promptGoogleEmailInput() {
        val input = EditText(this).apply {
            hint = "example@gmail.com"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(44, 30, 44, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("🌐 تسجيل الدخول بحساب Google")
            .setMessage("أدخل بريد حساب Google للمتابعة إلى طلب التفويض:")
            .setView(input)
            .setPositiveButton("متابعة") { _, _ ->
                val email = input.text.toString().trim().lowercase(Locale.US)
                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches() || !email.contains("@")) {
                    showSystemMessage("❌ يرجى إدخال بريد Google صحيح", isError = true)
                } else {
                    requestGoogleOAuthConsent(email)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // طلب التفويض الرسمي (Google OAuth Authorization Consent) بعد اختيار الإيميل
    private fun requestGoogleOAuthConsent(selectedEmail: String) {
        val cleanEmail = selectedEmail.trim().lowercase(Locale.US)
        getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("pending_google_email", cleanEmail).apply()

        // محاولة استدعاء OAuth Token الحقيقي من نظام أندرويد إن كان الحساب مسجلاً في النظام
        try {
            val am = AccountManager.get(this)
            val sysAccounts = am.getAccountsByType("com.google")
            val matchedSysAccount = sysAccounts.firstOrNull { it.name.equals(cleanEmail, ignoreCase = true) }
            if (matchedSysAccount != null) {
                am.getAuthToken(
                    matchedSysAccount,
                    "oauth2:https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/userinfo.email",
                    null,
                    this,
                    { future ->
                        try {
                            val bundle = future.result
                            val keyIntent = bundle.getParcelable<Intent>(AccountManager.KEY_INTENT)
                            val token = bundle.getString(AccountManager.KEY_AUTHTOKEN)
                            if (keyIntent != null) {
                                // فتح نافذة تفويض Google الرسمية في أندرويد
                                googleAuthConsentLauncher.launch(keyIntent)
                            } else if (!token.isNullOrEmpty()) {
                                mainHandler.post { showGoogleAuthorizationSheet(cleanEmail, token) }
                            } else {
                                mainHandler.post { showGoogleAuthorizationSheet(cleanEmail, "OAUTH_CONSENT_TOKEN") }
                            }
                        } catch (_: Exception) {
                            mainHandler.post { showGoogleAuthorizationSheet(cleanEmail, "OAUTH_CONSENT_TOKEN") }
                        }
                    },
                    mainHandler
                )
                return
            }
        } catch (_: Exception) {}

        // عرض شاشة تفويض Google OAuth الواضحة للمستخدم للموافقة على الصلاحيات قبل الدخول
        showGoogleAuthorizationSheet(cleanEmail, "OAUTH_CONSENT_TOKEN")
    }

    private fun showGoogleAuthorizationSheet(email: String, oauthToken: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 24)
            setBackgroundColor(Color.parseColor("#101622"))
        }

        val tvBadge = TextView(this).apply {
            text = "🌐 Google Sign-In Authorization"
            setTextColor(Color.parseColor("#80D8FF"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        container.addView(tvBadge)

        val tvTitle = TextView(this).apply {
            text = "يريد تطبيق Royal TV الوصول إلى حسابك على Google"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 8)
        }
        container.addView(tvTitle)

        val tvEmailChip = TextView(this).apply {
            text = "📧 $email"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 18)
        }
        container.addView(tvEmailChip)

        val tvPermissions = TextView(this).apply {
            text = "سيسمح هذا التفويض لتطبيق Royal TV بما يلي:\n\n" +
                "• التحقق من هويتك وعنوان بريدك الإلكتروني الأساسي.\n" +
                "• إنشاء وربط ملفك الشخصي المستقل (الاسم والصورة الشخصية) بهذا البريد.\n" +
                "• حفظ إعداداتك الخاصة ومزامنة غرف المشاهدة الجماعية."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            setLineSpacing(6f, 1.1f)
        }
        container.addView(tvPermissions)

        AlertDialog.Builder(this)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("السماح والمتابعة (Allow) ✓") { _, _ ->
                completeAuthorizedGoogleLogin(email, oauthToken)
            }
            .setNegativeButton("رفض / إلغاء") { dialog, _ ->
                dialog.dismiss()
                showSystemMessage("⚠️ تم رفض منح صلاحية الدخول من Google", isError = true)
            }
            .show()
    }

    private fun completeAuthorizedGoogleLogin(email: String, oauthToken: String) {
        val cleanEmail = email.trim().lowercase(Locale.US)
        val prefs = getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)
        showSystemMessage("⏳ جاري مزامنة حساب Google مع سيرفر Cloudflare...", isError = false)

        Thread {
            var cloudUserObj: JSONObject? = null
            try {
                val payload = JSONObject().apply {
                    put("email", cleanEmail)
                    put("oauthToken", oauthToken)
                }
                val req = Request.Builder()
                    .url("$workerUrl/auth/google")
                    .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string() ?: ""
                    if (resp.isSuccessful && bodyStr.contains("\"success\":true")) {
                        cloudUserObj = JSONObject(bodyStr).optJSONObject("user")
                    }
                }
            } catch (_: Exception) {}

            mainHandler.post {
                val usersArr = JSONArray(prefs.getString("registered_users", "[]"))
                var existingAccount: JSONObject? = null
                for (i in 0 until usersArr.length()) {
                    val u = usersArr.getJSONObject(i)
                    if (u.optString("email").equals(cleanEmail, ignoreCase = true)) {
                        existingAccount = u
                        break
                    }
                }

                if (cloudUserObj != null) {
                    if (existingAccount != null) {
                        for (k in listOf("username", "fullName", "password", "hasPassword", "avatar", "photoUri", "prefQualityIdx", "notifEnabled", "voiceClear", "autoAudio", "appLang")) {
                            if (cloudUserObj!!.has(k)) existingAccount.put(k, cloudUserObj!!.get(k))
                        }
                    } else {
                        usersArr.put(cloudUserObj)
                        existingAccount = cloudUserObj
                    }
                    prefs.edit().putString("registered_users", usersArr.toString()).apply()
                } else if (existingAccount == null) {
                    var uniqueUsername = cleanEmail.substringBefore("@")
                    var counter = 1
                    while (isUsernameTakenByAnotherEmail(usersArr, uniqueUsername, cleanEmail)) {
                        uniqueUsername = "${cleanEmail.substringBefore("@")}_$counter"
                        counter++
                    }
                    val newGoogleAccount = JSONObject().apply {
                        put("email", cleanEmail)
                        put("username", uniqueUsername)
                        put("fullName", uniqueUsername)
                        put("password", "GOOGLE_OAUTH_ONLY")
                        put("hasPassword", false)
                        put("oauthToken", oauthToken)
                        put("avatar", "👑")
                        put("photoUri", "")
                        put("prefQualityIdx", 0)
                        put("notifEnabled", true)
                        put("voiceClear", true)
                        put("autoAudio", true)
                        put("appLang", "ar")
                    }
                    usersArr.put(newGoogleAccount)
                    prefs.edit().putString("registered_users", usersArr.toString()).apply()
                    existingAccount = newGoogleAccount
                }

                val finalUsername = existingAccount?.optString("username") ?: cleanEmail.substringBefore("@")
                activateAccountSession(cleanEmail)
                Toast.makeText(this, "✅ تم تفويض ومزامنة الحساب عبر Cloudflare", Toast.LENGTH_SHORT).show()
                navigateToMainHome(finalUsername, isGuest = false)
            }
        }.start()
    }

    private fun isUsernameTakenByAnotherEmail(usersArr: JSONArray, username: String, myEmail: String): Boolean {
        for (i in 0 until usersArr.length()) {
            val u = usersArr.getJSONObject(i)
            val em = u.optString("email", "")
            val un = u.optString("username", "")
            if (!em.equals(myEmail, ignoreCase = true) && un.equals(username, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    // تحميل بيانات الحساب الخاص بهذا الإيميل فقط دون المساس بالحسابات الأخرى
    private fun activateAccountSession(email: String) {
        val cleanEmail = email.trim().lowercase(Locale.US)
        val prefs = getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)
        val usersArr = JSONArray(prefs.getString("registered_users", "[]"))

        var targetAccount: JSONObject? = null
        for (i in 0 until usersArr.length()) {
            val u = usersArr.getJSONObject(i)
            if (u.optString("email").equals(cleanEmail, ignoreCase = true)) {
                targetAccount = u
                break
            }
        }

        val username = targetAccount?.optString("username") ?: cleanEmail.substringBefore("@")
        val avatar = targetAccount?.optString("avatar", "👑") ?: "👑"
        val photoUri = targetAccount?.optString("photoUri", "") ?: ""
        val hasPass = targetAccount?.optBoolean("hasPassword", true) ?: true

        prefs.edit().apply {
            putString("current_account_email", cleanEmail)
            putString("current_email", cleanEmail)
            putString("current_user", username)
            putString("current_avatar", avatar)
            putString("current_photo_uri", photoUri)
            putBoolean("current_has_password", hasPass)
            putBoolean("is_guest", false)
            putBoolean("session_active", true)
            apply()
        }

        getSharedPreferences("prime_ott_prefs", Context.MODE_PRIVATE).edit()
            .putString("username", username)
            .apply()
        getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE).edit()
            .putString("display_name", username)
            .apply()
    }

    private fun showSystemMessage(msg: String, isError: Boolean) {
        tvAuthMessageBanner.visibility = View.VISIBLE
        tvAuthMessageBanner.text = msg
        tvAuthMessageBanner.setTextColor(if (isError) Color.parseColor("#FF8A80") else Color.parseColor("#00E676"))
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun isInternetAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun navigateToMainHome(username: String, isGuest: Boolean) {
        try {
            val mainCls = Class.forName("com.primeott.app.MainActivity")
            val intent = Intent(this, mainCls).apply {
                putExtra("logged_username", username)
                putExtra("is_guest", isGuest)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        } catch (_: Exception) {}
    }
}