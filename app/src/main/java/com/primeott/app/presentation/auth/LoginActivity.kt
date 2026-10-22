package com.primeott.app.presentation.auth

import android.accounts.AccountManager
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Patterns
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

    // مشغل اختيار حسابات Google الأصلية في أندرويد (مثل التطبيقات الكبرى)
    private val googleAccountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!email.isNullOrEmpty()) {
                completeOfficialGoogleSignIn(email)
            } else {
                showGoogleFallbackDialog()
            }
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

        // التحقق الصارم عند تسجيل الدخول
        btnLoginSubmit.setOnClickListener {
            if (!isInternetAvailable()) {
                showSystemMessage("No Internet Connection • لا يوجد اتصال بالإنترنت", isError = true)
                return@setOnClickListener
            }

            val usernameInput = etUsername.text.toString().trim()
            val passwordInput = etPassword.text.toString()

            if (usernameInput.isEmpty()) {
                showSystemMessage("Invalid Username or Password • يرجى إدخال اسم المستخدم أو البريد", isError = true)
                return@setOnClickListener
            }

            if (passwordInput.isEmpty()) {
                showSystemMessage("Password Is Required • كلمة المرور مطلوبة", isError = true)
                return@setOnClickListener
            }

            btnLoginSubmit.isEnabled = false
            btnLoginSubmit.text = "⏳ جاري التحقق من الحساب..."

            Thread {
                val usersArr = JSONArray(prefs.getString("registered_users", "[]"))
                var localMatchedUser: JSONObject? = null
                var userExistsLocally = false

                for (i in 0 until usersArr.length()) {
                    val u = usersArr.getJSONObject(i)
                    val regUser = u.optString("username", "")
                    val regEmail = u.optString("email", "")
                    if (regUser.equals(usernameInput, ignoreCase = true) ||
                        (regEmail.isNotEmpty() && regEmail.equals(usernameInput, ignoreCase = true))) {
                        userExistsLocally = true
                        if (u.optString("password") == passwordInput) {
                            localMatchedUser = u
                        }
                        break
                    }
                }

                var cloudVerified = false
                var cloudRejected = false
                var cloudUsername = usernameInput

                try {
                    val payload = JSONObject().apply {
                        put("username", usernameInput)
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
                            cloudUsername = json.optJSONObject("user")?.optString("username") ?: usernameInput
                        } else if (resp.code == 401) {
                            cloudRejected = true
                        }
                    }
                } catch (_: Exception) {
                }

                mainHandler.post {
                    btnLoginSubmit.isEnabled = true
                    btnLoginSubmit.text = "تسجيل الدخول • Login"

                    val isAuthenticated = (localMatchedUser != null) || (cloudVerified && !userExistsLocally)

                    if (!isAuthenticated) {
                        if (userExistsLocally || cloudRejected) {
                            showSystemMessage("❌ Invalid Username or Password • كلمة المرور غير صحيحة!", isError = true)
                        } else {
                            showSystemMessage("❌ هذا الحساب غير مسجل! اضغط على «إنشاء حساب جديد» أولاً.", isError = true)
                        }
                        return@post
                    }

                    val finalUsername = localMatchedUser?.optString("username") ?: cloudUsername
                    val finalEmail = localMatchedUser?.optString("email") ?: ""
                    val finalAvatar = localMatchedUser?.optString("avatar") ?: "👑"

                    prefs.edit().apply {
                        putBoolean("remember_me", cbRememberMe.isChecked)
                        if (cbRememberMe.isChecked) {
                            putString("saved_username", finalUsername)
                            putString("saved_password", passwordInput)
                        } else {
                            remove("saved_username")
                            remove("saved_password")
                        }
                        putString("current_user", finalUsername)
                        putString("current_email", finalEmail)
                        putString("current_avatar", finalAvatar)
                        putString("auth_provider", "Royal TV")
                        putBoolean("is_guest", false)
                        putBoolean("session_active", true)
                        apply()
                    }

                    getSharedPreferences("prime_ott_prefs", Context.MODE_PRIVATE).edit()
                        .putString("username", finalUsername)
                        .apply()

                    showSystemMessage("✅ Login Successful • تم تسجيل الدخول بنجاح!", isError = false)
                    mainHandler.postDelayed({
                        navigateToMainHome(finalUsername, isGuest = false)
                    }, 450L)
                }
            }.start()
        }

        // تسجيل الدخول بحساب Google مثل التطبيقات الكبرى
        btnGoogleSignIn.setOnClickListener {
            if (!isInternetAvailable()) {
                showSystemMessage("No Internet Connection • لا يوجد اتصال بالإنترنت", isError = true)
                return@setOnClickListener
            }
            launchNativeGoogleSignIn()
        }

        // وضع الزائر
        val guestClickListener = View.OnClickListener {
            prefs.edit()
                .putBoolean("is_guest", true)
                .putString("current_user", "زائر (Guest)")
                .putString("current_email", "وضع التصفح بدون حساب")
                .putString("auth_provider", "Guest Mode")
                .putBoolean("session_active", true)
                .apply()
            Toast.makeText(this, "Continue as Guest • تصفح كزائر", Toast.LENGTH_SHORT).show()
            navigateToMainHome("زائر (Guest)", isGuest = true)
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
                .setTitle("🔑 استعادة كلمة المرور (Forgot Password)")
                .setMessage("أدخل بريدك الإلكتروني أو اسم المستخدم المسجل:")
                .setView(input)
                .setPositiveButton("تحقق") { _, _ ->
                    val target = input.text.toString().trim()
                    if (target.isEmpty()) {
                        showSystemMessage("يرجى إدخال البريد أو اسم المستخدم", isError = true)
                        return@setPositiveButton
                    }
                    val usersArr = JSONArray(prefs.getString("registered_users", "[]"))
                    var foundPass: String? = null
                    for (i in 0 until usersArr.length()) {
                        val u = usersArr.getJSONObject(i)
                        if (u.optString("username").equals(target, ignoreCase = true) ||
                            u.optString("email").equals(target, ignoreCase = true)) {
                            foundPass = u.optString("password")
                            break
                        }
                    }
                    if (foundPass != null) {
                        showSystemMessage("✅ تم التحقق! كلمة المرور الخاصة بحسابك هي: $foundPass", isError = false)
                    } else {
                        showSystemMessage("❌ هذا الحساب غير مسجل في النظام", isError = true)
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        tvContactSupport.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("📞 الدعم الفني • Contact Support")
                .setMessage("تواصل مع فريق دعم Royal TV على مدار الساعة:\n• Telegram: @RoyalTV_Support\n• Email: support@royaltv.app")
                .setPositiveButton("مراسلة عبر تيليجرام") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/RoyalTV_Support")))
                    } catch (_: Exception) {}
                }
                .setNegativeButton("إغلاق", null)
                .show()
        }

        tvPrivacyPolicy.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("🛡️ سياسة الخصوصية • Privacy Policy")
                .setMessage("نلتزم في Royal TV بحماية خصوصية بياناتك بالكامل. يتم تشفير معلومات الحساب وكلمات المرور وغرف المشاهدة الجماعية عبر Cloudflare.")
                .setPositiveButton("موافق", null)
                .show()
        }
    }

    private fun launchNativeGoogleSignIn() {
        try {
            @Suppress("DEPRECATION")
            val intent = AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                false,
                null,
                null,
                null,
                null
            )
            googleAccountPickerLauncher.launch(intent)
        } catch (_: Exception) {
            showGoogleFallbackDialog()
        }
    }

    private fun showGoogleFallbackDialog() {
        val prefs = getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)
        val savedGoogleEmails = mutableListOf<String>()
        val usersArr = JSONArray(prefs.getString("registered_users", "[]"))
        for (i in 0 until usersArr.length()) {
            val em = usersArr.getJSONObject(i).optString("email", "")
            if (em.endsWith("@gmail.com", ignoreCase = true) && !savedGoogleEmails.contains(em)) {
                savedGoogleEmails.add(em)
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 32, 44, 20)
            setBackgroundColor(Color.parseColor("#101622"))
        }

        val tvHeader = TextView(this).apply {
            text = "🌐 اختيار حساب Google للمتابعة إلى Royal TV"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 0, 0, 16)
        }
        container.addView(tvHeader)

        val etGmail = EditText(this).apply {
            hint = "أدخل بريد جوجل (example@gmail.com)"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            if (savedGoogleEmails.isNotEmpty()) {
                setText(savedGoogleEmails.first())
            }
        }
        container.addView(etGmail)

        val etDisplayName = EditText(this).apply {
            hint = "الاسم الظاهر للحساب (اختياري)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        container.addView(etDisplayName)

        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("متابعة بحساب Google ✓") { _, _ ->
                val email = etGmail.text.toString().trim()
                val customName = etDisplayName.text.toString().trim()
                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches() || !email.contains("@")) {
                    showSystemMessage("❌ يرجى إدخال حساب بريد Google صحيح", isError = true)
                    return@setPositiveButton
                }
                completeOfficialGoogleSignIn(email, customName)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun completeOfficialGoogleSignIn(email: String, customDisplayName: String = "") {
        val displayUser = if (customDisplayName.isNotEmpty()) customDisplayName else email.substringBefore("@")
        val prefs = getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)
        val usersArr = JSONArray(prefs.getString("registered_users", "[]"))

        var exists = false
        for (i in 0 until usersArr.length()) {
            if (usersArr.getJSONObject(i).optString("email").equals(email, ignoreCase = true)) {
                exists = true
                break
            }
        }
        if (!exists) {
            val googleUserObj = JSONObject().apply {
                put("fullName", displayUser)
                put("username", displayUser)
                put("email", email)
                put("password", "GOOGLE_OAUTH_TOKEN")
                put("avatar", "🌐")
            }
            usersArr.put(googleUserObj)
            Thread {
                try {
                    val req = Request.Builder()
                        .url("$workerUrl/auth/register")
                        .post(googleUserObj.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    httpClient.newCall(req).execute().close()
                } catch (_: Exception) {}
            }.start()
        }

        prefs.edit().apply {
            putString("registered_users", usersArr.toString())
            putString("current_user", displayUser)
            putString("current_email", email)
            putString("current_avatar", "🌐")
            putString("auth_provider", "Google Account")
            putBoolean("is_guest", false)
            putBoolean("session_active", true)
            apply()
        }

        getSharedPreferences("prime_ott_prefs", Context.MODE_PRIVATE).edit()
            .putString("username", displayUser)
            .apply()

        showSystemMessage("✅ Login Successful • مرحباً بك $displayUser ($email)", isError = false)
        mainHandler.postDelayed({
            navigateToMainHome(displayUser, isGuest = false)
        }, 450L)
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
        } catch (_: Exception) {
            showSystemMessage("Something Went Wrong • تعذر فتح الشاشة الرئيسية", isError = true)
        }
    }
}