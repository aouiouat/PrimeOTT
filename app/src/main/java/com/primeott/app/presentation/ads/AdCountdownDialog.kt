package com.primeott.app.presentation.ads

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
