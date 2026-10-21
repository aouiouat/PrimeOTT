package com.primeott.app.presentation.livetv

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.FragmentLiveTvBinding
import com.primeott.app.domain.model.Category
import com.primeott.app.domain.model.Channel
import com.primeott.app.domain.model.StreamSource
import com.primeott.app.presentation.player.LiveTvPlayerEngine
import com.primeott.app.presentation.player.PrimePlayerEngine
import com.primeott.app.presentation.watch.WatchRoomActivity
import kotlinx.coroutines.launch

@UnstableApi
class LiveTvFragment : Fragment() {

    private var _binding: FragmentLiveTvBinding? = null
    private val binding get() = _binding!!

    private var livePlayerEngine: LiveTvPlayerEngine? = null

    private var allCategories: List<Category> = emptyList()
    private var currentCategoryChannels: List<Channel> = emptyList()
    private var selectedCategoryId: String = ""
    private var currentPlayingChannel: Channel? = null
    private var currentSources: List<StreamSource> = emptyList()
    private var currentSourceIndex: Int = 0
    private var showFavoritesOnly: Boolean = false
    private var isInlineFullscreen: Boolean = false
    private var isPlayerLocked: Boolean = false
    private var resizeModeIndex: Int = 0
    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )

    private val autoHideRunnable = Runnable {
        _binding?.inlineControlsOverlay?.visibility = View.GONE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeReeznPillBg(isSelected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor(if (isSelected) "#32353E" else "#23252C"))
            if (isSelected) {
                setStroke(dp(1), Color.parseColor("#525866"))
            }
        }
    }

    private fun togglePlayerOverlayWithAutoHide() {
        val b = _binding ?: return
        b.inlineControlsOverlay.removeCallbacks(autoHideRunnable)
        if (b.inlineControlsOverlay.visibility == View.VISIBLE) {
            b.inlineControlsOverlay.visibility = View.GONE
        } else {
            b.inlineControlsOverlay.visibility = View.VISIBLE
            b.inlineControlsOverlay.postDelayed(autoHideRunnable, 3500)
        }
    }

    private fun resetOverlayAutoHideTimer() {
        val b = _binding ?: return
        b.inlineControlsOverlay.removeCallbacks(autoHideRunnable)
        b.inlineControlsOverlay.postDelayed(autoHideRunnable, 3500)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_live_tv, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentLiveTvBinding.bind(view)

        binding.rvCategories.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChannels.layoutManager = LinearLayoutManager(requireContext())

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isInlineFullscreen) {
                        setFullscreenMode(false)
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )

        initDedicatedLivePlayerEngine()
        setupTapGestureAndControls()
        loadInitialPackagesAndChannels()
    }

    @OptIn(UnstableApi::class)
    private fun initDedicatedLivePlayerEngine() {
        livePlayerEngine = LiveTvPlayerEngine(
            context = requireContext(),
            scope = viewLifecycleOwner.lifecycleScope,
            onSourceSwitched = { idx, _ ->
                currentSourceIndex = idx
                _binding?.btnInlinePlayPause?.text = "⏸"
            },
            onTracksUpdated = { badge, _, _ ->
                _binding?.btnInlineQualityBadge?.text = badge
            },
            onBufferingChanged = { isBuffering ->
                _binding?.liveLoadingSpinner?.visibility = if (isBuffering) View.VISIBLE else View.GONE
            },
            onPlaybackExhausted = {
                _binding?.liveLoadingSpinner?.visibility = View.GONE
            }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapGestureAndControls() {
        val gestureDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    togglePlayerOverlayWithAutoHide()
                    return true
                }
            }
        )

        binding.inlinePlayerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        binding.inlineControlsOverlay.setOnClickListener {
            togglePlayerOverlayWithAutoHide()
        }

        binding.btnTopLeftMenu.setOnClickListener {
            if (isInlineFullscreen) {
                setFullscreenMode(false)
            } else {
                showReeznPreferencesPanel()
            }
        }

        binding.btnLockPlayer.setOnClickListener {
            isPlayerLocked = !isPlayerLocked
            binding.btnLockPlayer.text = if (isPlayerLocked) "🔒" else "🔓"
            Toast.makeText(
                requireContext(),
                if (isPlayerLocked) "🔒 تم قفل اللمس" else "🔓 تم إلغاء القفل",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnInlinePlayPause.setOnClickListener {
            resetOverlayAutoHideTimer()
            val engine = livePlayerEngine ?: return@setOnClickListener
            if (engine.isPlaying()) {
                engine.pause()
                binding.btnInlinePlayPause.text = "▶"
            } else {
                engine.resume()
                binding.btnInlinePlayPause.text = "⏸"
            }
        }

        // الانتقال للقناة السابقة |◀ مثل Reezn
        binding.btnPrevChannel.setOnClickListener {
            resetOverlayAutoHideTimer()
            if (currentCategoryChannels.isEmpty()) return@setOnClickListener
            val curIdx = currentCategoryChannels.indexOfFirst { it.id == currentPlayingChannel?.id }
            val prevIdx = if (curIdx > 0) curIdx - 1 else currentCategoryChannels.lastIndex
            playChannelInline(currentCategoryChannels[prevIdx])
        }

        // الانتقال للقناة التالية ▶| مثل Reezn
        binding.btnNextChannel.setOnClickListener {
            resetOverlayAutoHideTimer()
            if (currentCategoryChannels.isEmpty()) return@setOnClickListener
            val curIdx = currentCategoryChannels.indexOfFirst { it.id == currentPlayingChannel?.id }
            val nextIdx = if (curIdx in 0 until currentCategoryChannels.lastIndex) curIdx + 1 else 0
            playChannelInline(currentCategoryChannels[nextIdx])
        }

        // فتح القائمة الجانبية المزدوجة (Source | Choose Video Quality) مثل Reezn
        binding.btnInlineServers.setOnClickListener {
            resetOverlayAutoHideTimer()
            showReeznPreferencesPanel()
        }
        binding.btnInlineQualityBadge.setOnClickListener {
            resetOverlayAutoHideTimer()
            showReeznPreferencesPanel()
        }

        // فتح نافذة المسارات الصوتية (Subtitle & Audio)
        binding.btnInlineAudio.setOnClickListener {
            resetOverlayAutoHideTimer()
            showAudioAndSubtitlesDialog()
        }

        // تغيير أبعاد الشاشة (Aspect Ratio)
        binding.btnInlineAspect.setOnClickListener {
            resetOverlayAutoHideTimer()
            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
            binding.inlinePlayerView.resizeMode = resizeModes[resizeModeIndex]
            val modeName = when (resizeModes[resizeModeIndex]) {
                AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch Fill (ملء الشاشة)"
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Crop Zoom (تقريب)"
                else -> "Original Fit (أصلي)"
            }
            Toast.makeText(requireContext(), "⛶ $modeName", Toast.LENGTH_SHORT).show()
        }

        // زر My List لإضافة القناة للمفضلة فوراً
        binding.btnFullPip.setOnClickListener {
            resetOverlayAutoHideTimer()
            val ch = currentPlayingChannel ?: return@setOnClickListener
            OttRepository.toggleFavorite(ch.id, requireContext())
            val isFav = OttRepository.isChannelFavorite(ch.id)
            binding.btnFullPip.text = if (isFav) "♥  My List" else "🔖  My List"
            currentCategoryChannels = currentCategoryChannels.map {
                if (it.id == ch.id) it.copy(isFavorite = isFav) else it
            }
            renderChannelsList()
        }

        binding.btnMiniExpand.setOnClickListener {
            setFullscreenMode(!isInlineFullscreen)
        }
        binding.btnInlineFullscreen.setOnClickListener {
            setFullscreenMode(!isInlineFullscreen)
        }

        binding.btnInlineWatchParty.setOnClickListener {
            val ch = currentPlayingChannel ?: return@setOnClickListener
            val url = currentSources.getOrNull(currentSourceIndex)?.url ?: ch.streamUrls.firstOrNull() ?: ""
            viewLifecycleOwner.lifecycleScope.launch {
                val (ok, code) = OttRepository.createWatchRoom(
                    deviceId = SessionManager.getDeviceId(requireContext()),
                    userName = SessionManager.getUserName(requireContext()),
                    roomName = ch.name,
                    roomIcon = "📺",
                    isPrivate = false,
                    password = "",
                    allowGuestsControl = false,
                    contentType = "LIVE",
                    streamId = ch.id,
                    contentTitle = ch.name,
                    contentPoster = PrimePlayerEngine.sanitizeImageUrl(ch.logoUrl),
                    streamUrl = url
                )
                if (ok) {
                    if (isInlineFullscreen) setFullscreenMode(false)
                    livePlayerEngine?.pauseAndReleaseSocket()
                    startActivity(Intent(requireContext(), WatchRoomActivity::class.java).apply {
                        putExtra(WatchRoomActivity.EXTRA_ROOM_CODE, code)
                    })
                }
            }
        }

        binding.etSearchPackage.doAfterTextChanged { text ->
            val q = text?.toString()?.trim().orEmpty()
            val filteredCats = if (q.isEmpty()) allCategories else allCategories.filter {
                it.name.contains(q, ignoreCase = true)
            }
            renderCategoriesList(filteredCats)
        }

        binding.etSearchChannel.doAfterTextChanged {
            renderChannelsList()
        }

        binding.btnFilterFavorites.setOnClickListener {
            showFavoritesOnly = !showFavoritesOnly
            binding.btnFilterFavorites.text = if (showFavoritesOnly) "♥" else "♡"
            binding.btnFilterFavorites.setTextColor(
                if (showFavoritesOnly) Color.parseColor("#FF1E27") else Color.parseColor("#CBD5E1")
            )
            renderChannelsList()
        }
    }

    /**
     * القائمة الجانبية الاحترافية المطابقة 100% لتطبيق Reezn (image_cbefc6.jpg):
     * Preferences -> عمود Source على اليسار (جميع السيرفرات المضافة) | عمود Choose Video Quality على اليمين (Auto + جودات السيرفر)
     */
    private fun showReeznPreferencesPanel() {
        if (currentSources.isEmpty()) return
        val ctx = requireContext()
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val panelWidth = if (isInlineFullscreen) (screenW * 0.58).toInt() else (screenW * 0.94).toInt()
        val panelHeight = if (isInlineFullscreen) screenH else ViewGroup.LayoutParams.WRAP_CONTENT

        val rootPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2101216"))
            setPadding(dp(22), dp(20), dp(22), dp(20))
            layoutParams = ViewGroup.LayoutParams(panelWidth, panelHeight)
        }

        // العنوان العلوي: Preferences
        val tvTitle = TextView(ctx).apply {
            text = "Preferences"
            setTextColor(Color.WHITE)
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(16))
        }
        rootPanel.addView(tvTitle)

        // صف العناوين الفرعية: Source | Choose Video Quality
        val subHeadersRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        val tvSourceHeader = TextView(ctx).apply {
            text = "Source"
            setTextColor(Color.parseColor("#B0B5C0"))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvQualityHeader = TextView(ctx).apply {
            text = "Choose Video Quality"
            setTextColor(Color.parseColor("#B0B5C0"))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        subHeadersRow.addView(tvSourceHeader)
        subHeadersRow.addView(tvQualityHeader)
        rootPanel.addView(subHeadersRow)

        // الحاوية المزدوجة للقائمتين القابلتين للتمرير (عمود السيرفرات + عمود الجودات)
        val columnsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (isInlineFullscreen) 0 else dp(290),
                if (isInlineFullscreen) 1f else 0f
            )
        }

        val leftScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val leftSourcesCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        leftScroll.addView(leftSourcesCol)

        val rightScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        val rightQualitiesCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        rightScroll.addView(rightQualitiesCol)

        columnsContainer.addView(leftScroll)
        columnsContainer.addView(rightScroll)
        rootPanel.addView(columnsContainer)

        fun renderColumns() {
            leftSourcesCol.removeAllViews()
            rightQualitiesCol.removeAllViews()

            val activeKey = livePlayerEngine?.getSelectedQualityKey() ?: "AUTO"

            // 1. بناء عمود السيرفرات (Source) على اليسار (يدعم 15+ سيرفر)
            currentSources.forEachIndexed { idx, src ->
                val isSelectedServer = (idx == currentSourceIndex)
                val btnSource = TextView(ctx).apply {
                    text = if (isSelectedServer) "✓   ${src.label}" else "      ${src.label}"
                    setTextColor(if (isSelectedServer) Color.WHITE else Color.parseColor("#A0A6B2"))
                    textSize = 14.5f
                    setTypeface(typeface, if (isSelectedServer) Typeface.BOLD else Typeface.NORMAL)
                    background = makeReeznPillBg(isSelectedServer)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                    setOnClickListener {
                        currentSourceIndex = idx
                        livePlayerEngine?.switchSource(idx)
                        renderColumns()
                    }
                }
                leftSourcesCol.addView(btnSource)
            }

            // 2. بناء عمود الجودات (Choose Video Quality) على اليمين للسيرفر المختار حالياً فقط!
            val currentServer = currentSources.getOrNull(currentSourceIndex)

            // الخيار الأول دائماً: Auto (يختار تلقائياً حسب سرعة الإنترنت)
            val isAutoSelected = (activeKey == "AUTO")
            val btnAuto = TextView(ctx).apply {
                text = if (isAutoSelected) "✓   Auto" else "      Auto"
                setTextColor(if (isAutoSelected) Color.WHITE else Color.parseColor("#A0A6B2"))
                textSize = 14.5f
                setTypeface(typeface, if (isAutoSelected) Typeface.BOLD else Typeface.NORMAL)
                background = makeReeznPillBg(isAutoSelected)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                setOnClickListener {
                    livePlayerEngine?.selectQualityForCurrentServer("AUTO")
                    dialog.dismiss()
                }
            }
            rightQualitiesCol.addView(btnAuto)

            // أ) إذا كان الأدمن قد وضع روابط جودات مخصصة لهذا السيرفر في اللوحة، نظهرها هنا
            val customQualities = currentServer?.qualities.orEmpty()
            customQualities.forEach { qOpt ->
                val qKey = "CUSTOM_${qOpt.label}"
                val isSel = (activeKey == qKey)
                val btnQ = TextView(ctx).apply {
                    text = if (isSel) "✓   ${qOpt.label}" else "      ${qOpt.label}"
                    setTextColor(if (isSel) Color.WHITE else Color.parseColor("#A0A6B2"))
                    textSize = 14.5f
                    setTypeface(typeface, if (isSel) Typeface.BOLD else Typeface.NORMAL)
                    background = makeReeznPillBg(isSel)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                    setOnClickListener {
                        livePlayerEngine?.selectQualityForCurrentServer(qKey, customQualityUrl = qOpt.url)
                        binding.btnInlineQualityBadge.text = qOpt.label
                        dialog.dismiss()
                    }
                }
                rightQualitiesCol.addView(btnQ)
            }

            // ب) إذا كان السيرفر يحتوي على جودات HLS متعددة (مثل 1080p (5.1mb/s), 720p (2mb/s)) نظهرها تلقائياً!
            val hlsQualities = livePlayerEngine?.getDetectedHlsQualities().orEmpty()
            if (customQualities.isEmpty() && hlsQualities.size > 1) {
                hlsQualities.forEach { hTrack ->
                    val qKey = "HLS_${hTrack.label}"
                    val isSel = (activeKey == qKey)
                    val btnHlsQ = TextView(ctx).apply {
                        text = if (isSel) "✓   ${hTrack.label}" else "      ${hTrack.label}"
                        setTextColor(if (isSel) Color.WHITE else Color.parseColor("#A0A6B2"))
                        textSize = 14f
                        setTypeface(typeface, if (isSel) Typeface.BOLD else Typeface.NORMAL)
                        background = makeReeznPillBg(isSel)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(18), dp(14), dp(18), dp(14))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(10) }
                        setOnClickListener {
                            livePlayerEngine?.selectQualityForCurrentServer(qKey, hlsTrack = hTrack)
                            dialog.dismiss()
                        }
                    }
                    rightQualitiesCol.addView(btnHlsQ)
                }
            }
        }

        renderColumns()

        dialog.setContentView(rootPanel)
        dialog.window?.apply {
            setGravity(if (isInlineFullscreen) Gravity.END else Gravity.CENTER)
            setLayout(panelWidth, panelHeight)
        }
        dialog.show()
    }

    /**
     * نافذة اختيار المسار الصوتي / المعلق (Subtitle & Audio)
     */
    private fun showAudioAndSubtitlesDialog() {
        val ctx = requireContext()
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = (320 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F214161C"))
            setPadding(dp(22), dp(20), dp(22), dp(20))
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val title = TextView(ctx).apply {
            text = "🔊 Audio & Commentary Tracks"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(title)

        val audioTracks = livePlayerEngine?.getDetectedAudioTracks().orEmpty()
        if (audioTracks.isEmpty()) {
            val defaultRow = TextView(ctx).apply {
                text = "✓   Default Stream Audio (Active)"
                setTextColor(Color.WHITE)
                textSize = 14.5f
                background = makeReeznPillBg(true)
                setPadding(dp(16), dp(13), dp(16), dp(13))
                setOnClickListener { dialog.dismiss() }
            }
            root.addView(defaultRow)
        } else {
            audioTracks.forEach { track ->
                val row = TextView(ctx).apply {
                    text = if (track.isSelected) "✓   ${track.label}" else "      ${track.label}"
                    setTextColor(if (track.isSelected) Color.WHITE else Color.parseColor("#A0A6B2"))
                    textSize = 14.5f
                    setTypeface(typeface, if (track.isSelected) Typeface.BOLD else Typeface.NORMAL)
                    background = makeReeznPillBg(track.isSelected)
                    setPadding(dp(16), dp(13), dp(16), dp(13))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    setOnClickListener {
                        livePlayerEngine?.selectAudioTrack(track)
                        dialog.dismiss()
                    }
                }
                root.addView(row)
            }
        }

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun setFullscreenMode(enterFullscreen: Boolean) {
        val act = activity ?: return
        val b = _binding ?: return
        isInlineFullscreen = enterFullscreen
        val bottomBar = act.findViewById<View>(R.id.floatingBottomBar)

        if (enterFullscreen) {
            b.bottomListsContainer.visibility = View.GONE
            b.playerDividerLine.visibility = View.GONE
            bottomBar?.visibility = View.GONE

            b.tvInlineChannelTitle.visibility = View.VISIBLE
            b.inlinePlayerContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            WindowCompat.setDecorFitsSystemWindows(act.window, false)
            WindowInsetsControllerCompat(act.window, b.root).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            WindowCompat.setDecorFitsSystemWindows(act.window, true)
            WindowInsetsControllerCompat(act.window, b.root).show(WindowInsetsCompat.Type.systemBars())

            val heightPx = (235 * resources.displayMetrics.density).toInt()
            b.inlinePlayerContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightPx
            )

            b.playerDividerLine.visibility = View.VISIBLE
            b.bottomListsContainer.visibility = View.VISIBLE
            bottomBar?.visibility = View.VISIBLE
        }
        b.inlinePlayerContainer.requestLayout()
        resetOverlayAutoHideTimer()
    }

    private fun loadInitialPackagesAndChannels() {
        viewLifecycleOwner.lifecycleScope.launch {
            allCategories = OttRepository.fetchLiveCategories()
            if (allCategories.isNotEmpty()) {
                selectedCategoryId = allCategories.first().id
                renderCategoriesList(allCategories)
                loadChannelsForCategory(selectedCategoryId, autoPlayFirst = true)
            }
        }
    }

    private fun renderCategoriesList(categories: List<Category>) {
        val b = _binding ?: return
        b.rvCategories.adapter = CategoriesAdapter(categories, selectedCategoryId) { cat ->
            selectedCategoryId = cat.id
            loadChannelsForCategory(cat.id, autoPlayFirst = false)
        }
    }

    private fun loadChannelsForCategory(categoryId: String, autoPlayFirst: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            currentCategoryChannels = OttRepository.fetchChannelsByCategory(categoryId)
            renderChannelsList()
            if (autoPlayFirst && currentCategoryChannels.isNotEmpty() && currentPlayingChannel == null) {
                playChannelInline(currentCategoryChannels.first())
            }
        }
    }

    private fun renderChannelsList() {
        val b = _binding ?: return
        val q = b.etSearchChannel.text?.toString()?.trim().orEmpty()
        val filtered = currentCategoryChannels.filter { ch ->
            val matchQuery = q.isEmpty() || ch.name.contains(q, ignoreCase = true)
            val matchFav = !showFavoritesOnly || ch.isFavorite
            matchQuery && matchFav
        }

        b.rvChannels.adapter = ChannelsAdapter(
            items = filtered,
            playingChannelId = currentPlayingChannel?.id ?: "",
            onChannelClick = { ch -> playChannelInline(ch) },
            onFavoriteClick = { ch ->
                OttRepository.toggleFavorite(ch.id, requireContext())
                currentCategoryChannels = currentCategoryChannels.map {
                    if (it.id == ch.id) it.copy(isFavorite = !it.isFavorite) else it
                }
                renderChannelsList()
            }
        )
    }

    private fun playChannelInline(ch: Channel) {
        if (currentPlayingChannel?.id == ch.id && livePlayerEngine?.isPlaying() == true) {
            return
        }
        currentPlayingChannel = ch
        binding.tvInlineChannelTitle.text = ch.name
        binding.tvInlineChannelTitle.visibility = View.VISIBLE

        val isFav = OttRepository.isChannelFavorite(ch.id)
        binding.btnFullPip.text = if (isFav) "♥  My List" else "🔖  My List"

        currentSources = if (ch.namedSources.isNotEmpty()) {
            ch.namedSources
        } else {
            ch.streamUrls.mapIndexed { idx, u ->
                val lbl = when (idx) {
                    0 -> "AP"
                    1 -> "Reezn FHD"
                    2 -> "Reezn HD"
                    else -> "Server ${idx + 1}"
                }
                StreamSource(lbl, u)
            }
        }

        currentSourceIndex = 0
        binding.inlineControlsOverlay.visibility = View.GONE
        livePlayerEngine?.playSources(binding.inlinePlayerView, currentSources, 0)
        renderChannelsList()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            if (isInlineFullscreen) setFullscreenMode(false)
            livePlayerEngine?.pauseAndReleaseSocket()
            _binding?.btnInlinePlayPause?.text = "▶"
        } else {
            if (currentPlayingChannel != null && livePlayerEngine?.isPlaying() == false) {
                livePlayerEngine?.resume()
                _binding?.btnInlinePlayPause?.text = "⏸"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden && currentPlayingChannel != null && livePlayerEngine?.isPlaying() == false) {
            livePlayerEngine?.resume()
            _binding?.btnInlinePlayPause?.text = "⏸"
        }
    }

    override fun onPause() {
        super.onPause()
        if (!requireActivity().isChangingConfigurations) {
            livePlayerEngine?.pauseAndReleaseSocket()
            _binding?.btnInlinePlayPause?.text = "▶"
        }
    }

    override fun onDestroyView() {
        _binding?.inlineControlsOverlay?.removeCallbacks(autoHideRunnable)
        livePlayerEngine?.release()
        livePlayerEngine = null
        _binding = null
        super.onDestroyView()
    }
}