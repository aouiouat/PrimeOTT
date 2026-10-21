package com.primeott.app.presentation.player

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.ActivityPlayerBinding
import com.primeott.app.domain.model.Channel
import com.primeott.app.domain.model.StreamSource
import com.primeott.app.presentation.watch.WatchRoomActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL = "extra_channel"
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerEngine: PrimePlayerEngine
    private var progressJob: Job? = null
    private var channel: Channel? = null
    private var sourcesList: List<StreamSource> = emptyList()
    private var currentSourceIndex: Int = 0
    private var isUserSeeking = false
    private var liveElapsedSecs = 4
    private var isWebEmbedMode = false

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private var currentResizeIndex = 0

    private val autoHideRunnable = Runnable {
        binding.fullControlsOverlay.visibility = View.GONE
    }

    private fun toggleOverlay() {
        binding.fullControlsOverlay.removeCallbacks(autoHideRunnable)
        if (binding.fullControlsOverlay.visibility == View.VISIBLE) {
            binding.fullControlsOverlay.visibility = View.GONE
        } else {
            binding.fullControlsOverlay.visibility = View.VISIBLE
            binding.fullControlsOverlay.postDelayed(autoHideRunnable, 3000)
        }
    }

    private fun resetAutoHideTimer() {
        binding.fullControlsOverlay.removeCallbacks(autoHideRunnable)
        binding.fullControlsOverlay.postDelayed(autoHideRunnable, 3000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemUI()

        channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CHANNEL)
        }

        val ch = channel ?: run {
            finish()
            return
        }
        binding.tvChannelTitle.text = ch.name
        binding.tvWebMovieTitle.text = ch.name

        sourcesList = if (ch.namedSources.isNotEmpty()) {
            ch.namedSources
        } else {
            ch.streamUrls.mapIndexed { idx: Int, url: String ->
                val defaultLabel = when (idx) {
                    0 -> "HD"
                    1 -> "FHD"
                    2 -> "SD"
                    else -> "Server ${idx + 1}"
                }
                StreamSource(defaultLabel, url)
            }
        }

        playerEngine = PrimePlayerEngine(
            context = this,
            scope = lifecycleScope,
            onSourceSwitched = { idx, src ->
                currentSourceIndex = idx
                binding.btnQualityBadge.text = src.label
                binding.btnPlayPause.text = "⏸"
            },
            onPlaybackExhausted = {
                Toast.makeText(this, "⚠️ تعذر تشغيل المصدر، جرب سيرفراً آخر من القائمة", Toast.LENGTH_SHORT).show()
            }
        )

        setupWebPlayer()
        setupControls()
        activateSourceAtIndex(0)
        startProgressLoop()
    }

    private fun isEmbedUrl(url: String): Boolean {
        val u = url.lowercase(Locale.ROOT)
        return u.contains("vidsrc.") ||
                u.contains("vidlink.") ||
                u.contains("multiembed.") ||
                u.contains("autoembed.") ||
                u.contains("/embed/")
    }

    private fun activateSourceAtIndex(index: Int) {
        if (sourcesList.isEmpty()) return
        currentSourceIndex = index.coerceIn(0, sourcesList.lastIndex)
        val src = sourcesList[currentSourceIndex]
        binding.btnQualityBadge.text = src.label
        binding.btnWebServers.text = "🖥️ ${src.label} ▾"

        if (isEmbedUrl(src.url)) {
            isWebEmbedMode = true
            playerEngine.pauseAndReleaseSocket()
            binding.playerView.visibility = View.GONE
            binding.fullControlsOverlay.visibility = View.GONE
            binding.webPlayerView.visibility = View.VISIBLE
            binding.webTopOverlayBar.visibility = View.VISIBLE
            binding.webPlayerView.loadUrl(src.url)
        } else {
            isWebEmbedMode = false
            binding.webPlayerView.loadUrl("about:blank")
            binding.webPlayerView.visibility = View.GONE
            binding.webTopOverlayBar.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
            binding.fullControlsOverlay.visibility = View.GONE
            playerEngine.playSources(binding.playerView, sourcesList, currentSourceIndex, 0L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebPlayer() {
        binding.webPlayerView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            setSupportMultipleWindows(false)
        }

        binding.webPlayerView.webChromeClient = WebChromeClient()
        binding.webPlayerView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return true
                // السماح فقط بنطاقات مشغلات الفيديو الرسمية وحظر أي نافذة إعلانية منبثقة
                val allowed = listOf("vidsrc", "vidlink", "multiembed", "autoembed", " cloudnestra", "tmstr", "rabbitstream", "megacloud")
                return !allowed.any { target.contains(it, ignoreCase = true) }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqUrl = request?.url?.toString()?.lowercase(Locale.ROOT) ?: return null
                if (reqUrl.contains("doubleclick") || reqUrl.contains("popads") || reqUrl.contains("adsterra") || reqUrl.contains("onclick")) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        binding.btnWebBack.setOnClickListener { finish() }
        binding.btnWebServers.setOnClickListener { showServersQualityDialog() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    toggleOverlay()
                    return true
                }
            }
        )

        binding.playerView.setOnTouchListener { _: View, event: MotionEvent ->
            gestureDetector.onTouchEvent(event)
            true
        }

        binding.fullControlsOverlay.setOnClickListener {
            toggleOverlay()
        }

        binding.btnBack.setOnClickListener {
            resetAutoHideTimer()
            showServersQualityDialog()
        }

        binding.btnExitFullscreen.setOnClickListener { finish() }

        binding.btnPlayPause.setOnClickListener {
            resetAutoHideTimer()
            if (playerEngine.isPlaying()) {
                playerEngine.pause()
                binding.btnPlayPause.text = "▶"
            } else {
                playerEngine.resume()
                binding.btnPlayPause.text = "⏸"
            }
        }

        binding.btnSwitchServer.setOnClickListener {
            resetAutoHideTimer()
            showServersQualityDialog()
        }

        binding.btnQualityBadge.setOnClickListener {
            resetAutoHideTimer()
            showServersQualityDialog()
        }

        binding.btnAudioTrack.setOnClickListener {
            resetAutoHideTimer()
            Toast.makeText(this, "🔊 المسار الصوتي الافتراضي نشط", Toast.LENGTH_SHORT).show()
        }

        binding.btnWatchPartyFull.setOnClickListener {
            createWatchTogetherForCurrentChannel()
        }

        binding.btnAspectRatio.setOnClickListener {
            resetAutoHideTimer()
            currentResizeIndex = (currentResizeIndex + 1) % resizeModes.size
            binding.playerView.resizeMode = resizeModes[currentResizeIndex]
        }

        binding.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) resetAutoHideTimer()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
                resetAutoHideTimer()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                val p = playerEngine.player ?: return
                val dur = p.duration
                if (dur > 0) {
                    val targetPos = (dur * (seekBar?.progress ?: 0)) / 1000L
                    p.seekTo(targetPos)
                }
            }
        })
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                if (isWebEmbedMode) continue
                val p = playerEngine.player ?: continue
                if (!p.isPlaying || isUserSeeking) continue

                val dur = p.duration
                val pos = p.currentPosition
                if (dur > 0 && channel?.categoryId == "vod") {
                    binding.playerSeekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                    binding.tvPlayerElapsed.text = formatTimeMs(pos)
                    binding.tvPlayerDuration.text = formatTimeMs(dur)
                } else {
                    liveElapsedSecs++
                    val m = liveElapsedSecs / 60
                    val s = liveElapsedSecs % 60
                    val bm = (liveElapsedSecs + 20) / 60
                    val bs = (liveElapsedSecs + 20) % 60
                    binding.tvPlayerElapsed.text = String.format(Locale.US, "LIVE  %02d:%02d", m, s)
                    binding.tvPlayerDuration.text = String.format(Locale.US, "%02d:%02d", bm, bs)
                }
            }
        }
    }

    private fun formatTimeMs(ms: Long): String {
        val totalSecs = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    private fun createWatchTogetherForCurrentChannel() {
        val ch = channel ?: return
        val currentUrl = sourcesList.getOrNull(currentSourceIndex)?.url ?: return
        Toast.makeText(this, "⏳ جاري إنشاء غرفة Watch Together...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val (ok, codeOrMsg) = OttRepository.createWatchRoom(
                deviceId = SessionManager.getDeviceId(this@PlayerActivity),
                userName = SessionManager.getUserName(this@PlayerActivity),
                roomName = ch.name.take(25),
                roomIcon = "🎬",
                isPrivate = false,
                password = "",
                allowGuestsControl = false,
                contentType = if (ch.categoryId == "vod") "VOD" else "LIVE",
                streamId = ch.id,
                contentTitle = ch.name,
                contentPoster = PrimePlayerEngine.sanitizeImageUrl(ch.logoUrl),
                streamUrl = currentUrl
            )
            if (ok) {
                startActivity(Intent(this@PlayerActivity, WatchRoomActivity::class.java).apply {
                    putExtra(WatchRoomActivity.EXTRA_ROOM_CODE, codeOrMsg)
                })
                finish()
            } else {
                Toast.makeText(this@PlayerActivity, codeOrMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showServersQualityDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = (310 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_servers_dialog_card)
            setPadding(48, 36, 48, 28)
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val titleView = TextView(this).apply {
            text = "Servers"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 24)
        }
        root.addView(titleView)

        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#262932"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                bottomMargin = 12
            }
        }
        root.addView(divider)

        sourcesList.forEachIndexed { idx: Int, src: StreamSource ->
            val isSelected = idx == currentSourceIndex
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 24, 12, 24)
                setOnClickListener {
                    activateSourceAtIndex(idx)
                    dialog.dismiss()
                }
            }

            val labelTv = TextView(this).apply {
                text = src.label
                textSize = 16f
                setTextColor(if (isSelected) Color.parseColor("#FF2A2A") else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val checkTv = TextView(this).apply {
                text = if (isSelected) "✓" else ""
                textSize = 18f
                setTextColor(Color.parseColor("#FF2A2A"))
            }

            row.addView(labelTv)
            row.addView(checkTv)
            root.addView(row)
        }

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        progressJob?.cancel()
        binding.fullControlsOverlay.removeCallbacks(autoHideRunnable)
        binding.webPlayerView.destroy()
        playerEngine.release()
        super.onDestroy()
    }
}