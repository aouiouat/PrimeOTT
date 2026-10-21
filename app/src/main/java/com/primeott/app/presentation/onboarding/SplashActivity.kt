package com.primeott.app.presentation.onboarding

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.primeott.app.MainActivity
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.ActivitySplashBinding
import com.primeott.app.presentation.common.AnimUtils
import com.primeott.app.presentation.common.LocaleHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var selectedLang = "ar"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLayoutDirection(this)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        OttRepository.initFavorites(this)
        selectedLang = SessionManager.getLanguage(this)

        binding.tvSplashLogoIcon.scaleX = 0.75f
        binding.tvSplashLogoIcon.scaleY = 0.75f
        binding.tvSplashLogoIcon.animate().scaleX(1.0f).scaleY(1.0f).setDuration(500).start()

        lifecycleScope.launch {
            OttRepository.syncWithCloudflare()
            delay(750)
            if (SessionManager.isOnboardingCompleted(this@SplashActivity)) {
                goToMain()
            } else {
                showOnboardingStep()
            }
        }
    }

    private fun showOnboardingStep() {
        binding.splashBrandContainer.visibility = View.GONE
        binding.onboardingScroll.visibility = View.VISIBLE
        AnimUtils.animateViewEntry(binding.onboardingScroll)

        binding.etUserDisplayName.setText(SessionManager.getUserName(this))
        updateLangButtonsUI()

        binding.btnLangAr.setOnClickListener { selectLang("ar") }
        binding.btnLangFr.setOnClickListener { selectLang("fr") }
        binding.btnLangEn.setOnClickListener { selectLang("en") }

        binding.btnFinishSetup.setOnClickListener {
            val userName = binding.etUserDisplayName.text.toString().trim().ifEmpty { "مشاهد Prime" }
            val code = binding.etOnboardCode.text.toString().trim()
            SessionManager.setUserName(this, userName)

            if (code.isNotEmpty()) {
                binding.btnFinishSetup.isEnabled = false
                binding.tvAuthStatusMsg.visibility = View.VISIBLE
                binding.tvAuthStatusMsg.text = "⏳ جاري التحقق من كود الاشتراك..."
                lifecycleScope.launch {
                    val (ok, msg) = OttRepository.activateSubscription(code, SessionManager.getDeviceId(this@SplashActivity))
                    binding.btnFinishSetup.isEnabled = true
                    if (ok) {
                        SessionManager.saveActivation(this@SplashActivity, "TOKEN_$code", msg)
                        SessionManager.setOnboardingCompleted(this@SplashActivity, true)
                        Toast.makeText(this@SplashActivity, "✅ تم تفعيل اشتراكك بنجاح!", Toast.LENGTH_SHORT).show()
                        goToMain()
                    } else {
                        binding.tvAuthStatusMsg.setTextColor(Color.parseColor("#FF4757"))
                        binding.tvAuthStatusMsg.text = msg
                    }
                }
            } else {
                SessionManager.setOnboardingCompleted(this, true)
                goToMain()
            }
        }

        binding.btnSkipGuest.setOnClickListener {
            val userName = binding.etUserDisplayName.text.toString().trim().ifEmpty { "مشاهد Prime" }
            SessionManager.setUserName(this, userName)
            SessionManager.setOnboardingCompleted(this, true)
            goToMain()
        }
    }

    private fun selectLang(lang: String) {
        selectedLang = lang
        SessionManager.setLanguage(this, lang)
        LocaleHelper.applyLayoutDirection(this)
        updateLangButtonsUI()
    }

    private fun updateLangButtonsUI() {
        val activeColor = Color.parseColor("#E50914")
        val inactiveColor = Color.parseColor("#181C26")
        binding.btnLangAr.setBackgroundColor(if (selectedLang == "ar") activeColor else inactiveColor)
        binding.btnLangFr.setBackgroundColor(if (selectedLang == "fr") activeColor else inactiveColor)
        binding.btnLangEn.setBackgroundColor(if (selectedLang == "en") activeColor else inactiveColor)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        AnimUtils.applyActivityTransition(this)
        finish()
    }
}
