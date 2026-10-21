package com.primeott.app.presentation.livetv

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

@UnstableApi
class LiveTvFragment : Fragment() {

    private var _binding: FragmentLiveTvBinding? = null
    private val binding get() = _binding!!

    private var livePlayerEngine: LiveTvPlayerEngine? = null
    private var timerJob: Job? = null
    private var elapsedSeconds: Int = 1

    private var allCategories: List<Category> = emptyList()
    private var currentCategoryChannels: List<Channel> = emptyList()
    private var selectedCategoryId: String = ""
    private var currentPlayingChannel: Channel? = null
    private var currentSources: List<StreamSource> = emptyList()
    private var currentSourceIndex: Int = 0
    private var showFavoritesOnly: Boolean = false
    private var isInlineFullscreen: Boolean = false
    private var resizeModeIndex: Int = 0
    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )

    private val autoHideRunnable = Runnable {
        _binding?.inlineControlsOverlay?.visibility = View.GONE
    }

    private fun togglePlayerOverlayWithAutoHide() {
        val b = _binding ?: return
        b.inlineControlsOverlay.removeCallbacks(autoHideRunnable)
        if (b.inlineControlsOverlay.visibility == View.VISIBLE) {
            b.inlineControlsOverlay.visibility = View.GONE
        } else {
            b.inlineControlsOverlay.visibility = View.VISIBLE
            b.inlineControlsOverlay.postDelayed(autoHideRunnable, 3000)
        }
    }

    private fun resetOverlayAutoHideTimer() {
        val b = _binding ?: return
        b.inlineControlsOverlay.removeCallbacks(autoHideRunnable)
        b.inlineControlsOverlay.postDelayed(autoHideRunnable, 3000)
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
        startLiveClockCounter()
        loadInitialPackagesAndChannels()
    }

    @OptIn(UnstableApi::class)
    private fun initDedicatedLivePlayerEngine() {
        livePlayerEngine = LiveTvPlayerEngine(
            context = requireContext(),
            scope = viewLifecycleOwner.lifecycleScope,
            onSourceSwitched = { idx, src ->
                currentSourceIndex = idx
                _binding?.btnInlineQualityBadge?.text = src.label
                _binding?.btnInlinePlayPause?.text = "⏸"
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

        binding.btnInlineServers.setOnClickListener {
            resetOverlayAutoHideTimer()
            showServersPopupDialog()
        }
        binding.btnInlineQualityBadge.setOnClickListener {
            resetOverlayAutoHideTimer()
            showServersPopupDialog()
        }
        binding.btnTopLeftMenu.setOnClickListener {
            resetOverlayAutoHideTimer()
            showServersPopupDialog()
        }

        binding.btnInlineAudio.setOnClickListener {
            resetOverlayAutoHideTimer()
            Toast.makeText(requireContext(), "🔊 المسار الصوتي الافتراضي نشط", Toast.LENGTH_SHORT).show()
        }

        binding.btnInlineAspect.setOnClickListener {
            resetOverlayAutoHideTimer()
            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
            binding.inlinePlayerView.resizeMode = resizeModes[resizeModeIndex]
        }

        binding.btnMiniExpand.setOnClickListener {
            setFullscreenMode(true)
        }
        binding.btnInlineFullscreen.setOnClickListener {
            setFullscreenMode(!isInlineFullscreen)
        }

        val startWatchTogetherAction = View.OnClickListener {
            val ch = currentPlayingChannel ?: return@OnClickListener
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

        binding.btnInlineWatchParty.setOnClickListener(startWatchTogetherAction)
        binding.btnFullPip.setOnClickListener(startWatchTogetherAction)

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

    private fun setFullscreenMode(enterFullscreen: Boolean) {
        val act = activity ?: return
        val b = _binding ?: return
        isInlineFullscreen = enterFullscreen
        val bottomBar = act.findViewById<View>(R.id.floatingBottomBar)

        if (enterFullscreen) {
            b.bottomListsContainer.visibility = View.GONE
            b.playerDividerLine.visibility = View.GONE
            bottomBar?.visibility = View.GONE

            b.btnMiniExpand.visibility = View.GONE
            b.btnInlineWatchParty.visibility = View.GONE
            b.btnTopLeftMenu.visibility = View.VISIBLE
            b.fullBottomActionsRow.visibility = View.VISIBLE

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

            b.btnMiniExpand.visibility = View.VISIBLE
            b.btnInlineWatchParty.visibility = View.VISIBLE
            b.btnTopLeftMenu.visibility = View.GONE
            b.fullBottomActionsRow.visibility = View.GONE

            b.playerDividerLine.visibility = View.VISIBLE
            b.bottomListsContainer.visibility = View.VISIBLE
            bottomBar?.visibility = View.VISIBLE
        }
        b.inlinePlayerContainer.requestLayout()
        resetOverlayAutoHideTimer()
    }

    private fun startLiveClockCounter() {
        timerJob?.cancel()
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                if (livePlayerEngine?.isPlaying() == true) {
                    elapsedSeconds++
                    val b = _binding ?: continue
                    // تحديث النص فقط عندما تكون لوحة التحكم ظاهرة لمنع إعادة رسم حاوية الفيديو كل ثانية!
                    if (b.inlineControlsOverlay.visibility == View.VISIBLE) {
                        val mins = elapsedSeconds / 60
                        val secs = elapsedSeconds % 60
                        val bufferedSecs = (elapsedSeconds + 23)
                        val bMins = bufferedSecs / 60
                        val bSecs = bufferedSecs % 60
                        b.tvLiveElapsed.text = String.format(Locale.US, "LIVE   %02d:%02d", mins, secs)
                        b.tvLiveDuration.text = String.format(Locale.US, "%02d:%02d", bMins, bSecs)
                    }
                }
            }
        }
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
        // إذا ضغط المستخدم على نفس القناة الشغالة حالياً لا نعيد تحميلها
        if (currentPlayingChannel?.id == ch.id && livePlayerEngine?.isPlaying() == true) {
            return
        }
        currentPlayingChannel = ch
        elapsedSeconds = 1
        binding.tvInlineChannelTitle.text = ch.name

        currentSources = if (ch.namedSources.isNotEmpty()) {
            ch.namedSources
        } else {
            ch.streamUrls.mapIndexed { idx, u ->
                val lbl = when (idx) {
                    0 -> "HD"
                    1 -> "FHD"
                    2 -> "SD"
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

    private fun showServersPopupDialog() {
        if (currentSources.isEmpty()) return
        val ctx = requireContext()
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val widthPx = (310 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_servers_dialog_card)
            setPadding(48, 38, 48, 28)
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val titleTv = TextView(ctx).apply {
            text = "Servers"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 24)
        }
        root.addView(titleTv)

        val divider = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#262932"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                bottomMargin = 12
            }
        }
        root.addView(divider)

        currentSources.forEachIndexed { idx, src ->
            val isSelected = idx == currentSourceIndex
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 24, 8, 24)
                setOnClickListener {
                    livePlayerEngine?.switchSource(idx)
                    dialog.dismiss()
                }
            }

            val labelTv = TextView(ctx).apply {
                text = src.label
                textSize = 16f
                setTextColor(if (isSelected) Color.parseColor("#FF2A2A") else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val checkTv = TextView(ctx).apply {
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
        timerJob?.cancel()
        _binding?.inlineControlsOverlay?.removeCallbacks(autoHideRunnable)
        livePlayerEngine?.release()
        livePlayerEngine = null
        _binding = null
        super.onDestroyView()
    }
}