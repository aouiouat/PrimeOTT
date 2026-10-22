package com.primeott.app.presentation.auth

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
import android.provider.MediaStore
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

class RegisterActivity : AppCompatActivity() {

    private var isPasswordVisible = false
    private var selectedImageUri: Uri? = null
    private var selectedDefaultAvatar: String? = "👑"

    private lateinit var ivProfileAvatar: ImageView
    private lateinit var tvEmojiAvatarOverlay: TextView
    private lateinit var btnRemoveProfilePic: TextView
    private lateinit var tvRegMessageBanner: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerUrl = "https://prime-ott-panel.prime-ott-stream.workers.dev"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uri = res.data?.data
            if (uri != null) {
                selectedImageUri = uri
                selectedDefaultAvatar = null
                tvEmojiAvatarOverlay.visibility = View.GONE
                ivProfileAvatar.visibility = View.VISIBLE
                ivProfileAvatar.setPadding(0, 0, 0, 0)
                ivProfileAvatar.setImageURI(uri)
                btnRemoveProfilePic.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register_royal)

        ivProfileAvatar = findViewById(R.id.ivProfileAvatar)
        tvEmojiAvatarOverlay = findViewById(R.id.tvEmojiAvatarOverlay)
        btnRemoveProfilePic = findViewById(R.id.btnRemoveProfilePic)
        tvRegMessageBanner = findViewById(R.id.tvRegMessageBanner)

        val flProfilePictureContainer = findViewById<FrameLayout>(R.id.flProfilePictureContainer)
        val btnUploadProfilePic = findViewById<TextView>(R.id.btnUploadProfilePic)

        val etFullName = findViewById<EditText>(R.id.etRegFullName)
        val etUsername = findViewById<EditText>(R.id.etRegUsername)
        val etEmail = findViewById<EditText>(R.id.etRegEmail)
        val etPassword = findViewById<EditText>(R.id.etRegPassword)
        val etConfirmPassword = findViewById<EditText>(R.id.etRegConfirmPassword)
        val btnToggleRegPassword = findViewById<TextView>(R.id.btnToggleRegPassword)
        val cbAgreeTerms = findViewById<CheckBox>(R.id.cbAgreeTerms)

        val btnCreateAccountSubmit = findViewById<Button>(R.id.btnCreateAccountSubmit)
        val btnRegGoogleSignIn = findViewById<Button>(R.id.btnRegGoogleSignIn)
        val tvAlreadyHaveAccountLogin = findViewById<TextView>(R.id.tvAlreadyHaveAccountLogin)
        val btnTopBackToLogin = findViewById<TextView>(R.id.btnTopBackToLogin)

        val openAvatarMenu = View.OnClickListener {
            val options = arrayOf(
                "🖼️ اختيار صورة من المعرض (Choose from Gallery)",
                "👑 اختيار أفاتار افتراضي (Choose Default Avatar)",
                "🗑️ إزالة الصورة (Remove Picture)"
            )
            AlertDialog.Builder(this)
                .setTitle("الصورة الشخصية • Profile Picture")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                            galleryLauncher.launch(intent)
                        }
                        1 -> showDefaultAvatarPicker()
                        2 -> removeProfilePicture()
                    }
                }
                .setNegativeButton("إغلاق", null)
                .show()
        }
        flProfilePictureContainer.setOnClickListener(openAvatarMenu)
        btnUploadProfilePic.setOnClickListener(openAvatarMenu)
        btnRemoveProfilePic.setOnClickListener { removeProfilePicture() }

        btnToggleRegPassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            val type = if (isPasswordVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etPassword.inputType = type
            etConfirmPassword.inputType = type
            btnToggleRegPassword.text = if (isPasswordVisible) "إخفاء 🙈" else "إظهار 👁️"
            etPassword.setSelection(etPassword.text.length)
        }

        btnCreateAccountSubmit.setOnClickListener {
            if (!isInternetAvailable()) {
                showSystemMessage("No Internet Connection • لا يوجد اتصال بالإنترنت", isError = true)
                return@setOnClickListener
            }

            val fullName = etFullName.text.toString().trim()
            val username = etUsername.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString()
            val confirmPassword = etConfirmPassword.text.toString()

            if (fullName.isEmpty() || username.isEmpty() || email.isEmpty()) {
                showSystemMessage("Something Went Wrong • يرجى ملء الاسم، اسم المستخدم، والبريد الإلكتروني", isError = true)
                return@setOnClickListener
            }

            if (username.length < 3) {
                showSystemMessage("يرجى اختيار اسم مستخدم لا يقل عن 3 أحرف", isError = true)
                return@setOnClickListener
            }

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                showSystemMessage("يرجى إدخال بريد إلكتروني صحيح (example@mail.com)", isError = true)
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                showSystemMessage("Password Is Required • كلمة المرور مطلوبة", isError = true)
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                showSystemMessage("Passwords Do Not Match • كلمتا المرور غير متطابقتين", isError = true)
                return@setOnClickListener
            }

            if (!cbAgreeTerms.isChecked) {
                showSystemMessage("Please Accept Terms & Conditions • يرجى الموافقة على الشروط والأحكام", isError = true)
                return@setOnClickListener
            }

            val prefs = getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)
            val usersArr = JSONArray(prefs.getString("registered_users", "[]"))

            for (i in 0 until usersArr.length()) {
                val u = usersArr.getJSONObject(i)
                if (u.optString("username").equals(username, ignoreCase = true)) {
                    showSystemMessage("Username Already Exists • اسم المستخدم مسجل مسبقاً", isError = true)
                    return@setOnClickListener
                }
                if (u.optString("email").equals(email, ignoreCase = true)) {
                    showSystemMessage("Email Already Registered • البريد الإلكتروني مسجل مسبقاً", isError = true)
                    return@setOnClickListener
                }
            }

            btnCreateAccountSubmit.isEnabled = false
            btnCreateAccountSubmit.text = "⏳ جاري إنشاء الحساب..."

            val avatarVal = selectedDefaultAvatar ?: "👑"
            val photoUriVal = selectedImageUri?.toString() ?: ""
            val newUser = JSONObject().apply {
                put("fullName", fullName)
                put("username", username)
                put("email", email.lowercase(java.util.Locale.US))
                put("password", password)
                put("hasPassword", true)
                put("avatar", avatarVal)
                put("photoUri", photoUriVal)
                put("prefQualityIdx", 0)
                put("notifEnabled", true)
                put("voiceClear", true)
                put("autoAudio", true)
                put("appLang", "ar")
            }

            Thread {
                try {
                    val req = Request.Builder()
                        .url("$workerUrl/auth/register")
                        .post(newUser.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    httpClient.newCall(req).execute().close()
                } catch (_: Exception) {}

                mainHandler.post {
                    btnCreateAccountSubmit.isEnabled = true
                    btnCreateAccountSubmit.text = "إنشاء الحساب • Create Account"

                    usersArr.put(newUser)
                    prefs.edit().apply {
                        putString("registered_users", usersArr.toString())
                        putString("saved_username", username)
                        putString("saved_password", password)
                        apply()
                    }

                    showSystemMessage("✅ Account Created Successfully • تم إنشاء الحساب بنجاح!", isError = false)
                    mainHandler.postDelayed({ finish() }, 800L)
                }
            }.start()
        }

        btnRegGoogleSignIn.setOnClickListener { finish() }
        tvAlreadyHaveAccountLogin.setOnClickListener { finish() }
        btnTopBackToLogin.setOnClickListener { finish() }
    }

    private fun showDefaultAvatarPicker() {
        val avatars = arrayOf("👑 الملكي (Royal)", "🦁 الأسد الذهبي", "⚽ نجم الملاعب", "🎬 عاشق السينما", "🦅 الصقر")
        val emojis = arrayOf("👑", "🦁", "⚽", "🎬", "🦅")
        AlertDialog.Builder(this)
            .setTitle("اختر الأفاتار الافتراضي (Choose Default Avatar)")
            .setItems(avatars) { _, which ->
                selectedDefaultAvatar = emojis[which]
                selectedImageUri = null
                ivProfileAvatar.visibility = View.GONE
                tvEmojiAvatarOverlay.visibility = View.VISIBLE
                tvEmojiAvatarOverlay.text = emojis[which]
                btnRemoveProfilePic.visibility = View.VISIBLE
            }
            .show()
    }

    private fun removeProfilePicture() {
        selectedImageUri = null
        selectedDefaultAvatar = null
        tvEmojiAvatarOverlay.visibility = View.GONE
        ivProfileAvatar.visibility = View.VISIBLE
        ivProfileAvatar.setPadding(36, 36, 36, 36)
        ivProfileAvatar.setImageResource(android.R.drawable.ic_menu_camera)
        btnRemoveProfilePic.visibility = View.GONE
    }

    private fun showSystemMessage(msg: String, isError: Boolean) {
        tvRegMessageBanner.visibility = View.VISIBLE
        tvRegMessageBanner.text = msg
        tvRegMessageBanner.setTextColor(if (isError) Color.parseColor("#FF8A80") else Color.parseColor("#00E676"))
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
}