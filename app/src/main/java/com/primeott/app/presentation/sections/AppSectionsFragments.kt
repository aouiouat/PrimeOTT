package com.primeott.app.presentation.sections

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.primeott.app.MainActivity
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.FragmentHomeBinding
import com.primeott.app.databinding.FragmentSettingsBinding
import com.primeott.app.databinding.FragmentVodGridBinding
import com.primeott.app.databinding.ItemHeroBannerBinding
import com.primeott.app.databinding.ItemMatchBinding
import com.primeott.app.databinding.ItemVodCardBinding
import com.primeott.app.domain.model.MatchFixture
import com.primeott.app.domain.model.VodItem
import com.primeott.app.domain.model.WatchHistoryEntry
import com.primeott.app.presentation.common.AnimUtils
import com.primeott.app.presentation.common.LocaleHelper
import com.primeott.app.presentation.detail.ContentDetailActivity
import com.primeott.app.presentation.watch.WatchTogetherController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private val bannerColorCache = ConcurrentHashMap<String, Int>()

private fun optimizeCardPosterUrl(rawUrl: String): String {
    if (rawUrl.isEmpty()) return ""
    return rawUrl.replace("/w500/", "/w342/")
}

/**
 * استجابة لمس فورية وخفيفة جداً للكروت بلا ما تثقل فتح الفيلم
 */
@Suppress("ClickableViewAccessibility")
private fun attachSnappyCardTouch(view: View) {
    view.setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().cancel()
                v.animate()
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(55L)
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().cancel()
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(85L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
        false
    }
}

/**
 * تنسيق عنوان مزدوج اللون (أبيض + أحمر سينمائي) لصفحات Movies / TV Shows / See All
 */
private fun buildTwoToneHeaderTitle(rawTitle: String): SpannableStringBuilder {
    val clean = rawTitle.trim().uppercase()
    val words = clean.split(" ").filter { it.isNotBlank() }
    val ssb = SpannableStringBuilder()
    if (words.size >= 2) {
        val firstPart = words.dropLast(1).joinToString(" ") + " "
        val lastWord = words.last()
        ssb.append(firstPart)
        val start = ssb.length
        ssb.append(lastWord)
        ssb.setSpan(
            ForegroundColorSpan(Color.parseColor("#FF2A36")),
            start,
            ssb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    } else {
        val half = (clean.length / 2).coerceAtLeast(2)
        ssb.append(clean.substring(0, half))
        val start = ssb.length
        ssb.append(clean.substring(half))
        ssb.setSpan(
            ForegroundColorSpan(Color.parseColor("#E50914")),
            start,
            ssb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    return ssb
}

private fun extractDominantBannerColor(bitmap: Bitmap): Int {
    return try {
        val scaled = Bitmap.createScaledBitmap(bitmap, 28, 28, true)
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var weightSum = 0L
        val hsv = FloatArray(3)

        for (y in 2 until 26) {
            for (x in 2 until 26) {
                val pixel = scaled.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                val sat = hsv[1]
                val v = hsv[2]
                if (v in 0.15f..0.85f && sat >= 0.20f) {
                    val w = ((sat * 100).toLong()).coerceAtLeast(10L)
                    rSum += Color.red(pixel) * w
                    gSum += Color.green(pixel) * w
                    bSum += Color.blue(pixel) * w
                    weightSum += w
                }
            }
        }
        if (weightSum > 0L) {
            val r = (rSum / weightSum).toInt().coerceIn(0, 255)
            val g = (gSum / weightSum).toInt().coerceIn(0, 255)
            val b = (bSum / weightSum).toInt().coerceIn(0, 255)
            Color.colorToHSV(Color.rgb(r, g, b), hsv)
            hsv[1] = hsv[1].coerceIn(0.40f, 0.85f)
            hsv[2] = hsv[2].coerceIn(0.28f, 0.46f)
            Color.HSVToColor(hsv)
        } else {
            Color.parseColor("#1E3A5F")
        }
    } catch (_: Exception) {
        Color.parseColor("#1E3A5F")
    }
}

private fun applyTrueFullscreenDialog(dialog: Dialog, rootView: View) {
    dialog.window?.let { w ->
        WindowCompat.setDecorFitsSystemWindows(w, false)
        w.setBackgroundDrawable(com.primeott.app.FlixVisionStandardBgDrawable())
        w.statusBarColor = Color.parseColor("#08080C")
        w.navigationBarColor = Color.parseColor("#08080C")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    val initialLeft = rootView.paddingLeft
    val initialTop = rootView.paddingTop
    val initialRight = rootView.paddingRight
    val initialBottom = rootView.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(
            initialLeft + bars.left,
            initialTop + bars.top,
            initialRight + bars.right,
            initialBottom + bars.bottom
        )
        insets
    }
}

private fun showLuxurySearchDialog(
    ctx: Context,
    scope: LifecycleCoroutineScope,
    filterType: String,
    onSelectVod: (VodItem) -> Unit
) {
    val d = ctx.resources.displayMetrics.density
    val dialog = Dialog(ctx)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

    val headerFont = ResourcesCompat.getFont(ctx, R.font.product_sans_bold)
        ?: Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val bodyFont = ResourcesCompat.getFont(ctx, R.font.product_sans_egular)
        ?: Typeface.create("sans-serif", Typeface.NORMAL)

    val screenW = ctx.resources.displayMetrics.widthPixels
    val screenH = ctx.resources.displayMetrics.heightPixels

    val rootCard = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * d
            colors = intArrayOf(Color.parseColor("#F7141620"), Color.parseColor("#FA0B0C12"))
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setStroke((1.2f * d).toInt(), Color.parseColor("#33FFFFFF"))
        }
        setPadding((18 * d).toInt(), (18 * d).toInt(), (18 * d).toInt(), (16 * d).toInt())
    }

    val topRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, (12 * d).toInt())
    }

    val dialogTitle = when (filterType) {
        "MOVIES" -> "Search Movies"
        "SERIES" -> "Search TV Shows"
        else -> "Search Movies & Series"
    }

    val tvTitle = TextView(ctx).apply {
        text = dialogTitle
        setTextColor(Color.WHITE)
        textSize = 20f
        typeface = headerFont
        letterSpacing = 0.02f
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    val btnClose = TextView(ctx).apply {
        text = "✕"
        setTextColor(Color.parseColor("#94A3B8"))
        textSize = 16f
        typeface = headerFont
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1FFFFFFF"))
        }
        layoutParams = LinearLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt())
        setOnClickListener { dialog.dismiss() }
    }

    topRow.addView(tvTitle)
    topRow.addView(btnClose)
    rootCard.addView(topRow)

    val searchBoxRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * d
            setColor(Color.parseColor("#141722"))
            setStroke((1.2f * d).toInt(), Color.parseColor("#38FFFFFF"))
        }
        setPadding((12 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (48 * d).toInt()
        )
    }

    val ivSearchIcon = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_search)
        layoutParams = LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()).apply {
            marginEnd = (10 * d).toInt()
        }
    }

    val hintText = when (filterType) {
        "MOVIES" -> "Type movie title..."
        "SERIES" -> "Type series title..."
        else -> "Search among 1,000,000+ Movies & Series..."
    }

    val etQuery = EditText(ctx).apply {
        hint = hintText
        setHintTextColor(Color.parseColor("#64748B"))
        setTextColor(Color.WHITE)
        textSize = 13.5f
        typeface = bodyFont
        background = null
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
    }

    val spinner = ProgressBar(ctx).apply {
        visibility = View.GONE
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E50914"))
        layoutParams = LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt())
    }

    searchBoxRow.addView(ivSearchIcon)
    searchBoxRow.addView(etQuery)
    searchBoxRow.addView(spinner)
    rootCard.addView(searchBoxRow)

    val tvStatus = TextView(ctx).apply {
        text = "Type at least 2 characters to search"
        setTextColor(Color.parseColor("#94A3B8"))
        textSize = 12f
        typeface = bodyFont
        gravity = Gravity.CENTER
        setPadding(0, (14 * d).toInt(), 0, (10 * d).toInt())
    }
    rootCard.addView(tvStatus)

    val resultsList = mutableListOf<VodItem>()
    val rvResults = RecyclerView(ctx).apply {
        layoutManager = GridLayoutManager(ctx, 3)
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (screenH * 0.48f).toInt()
        ).apply {
            topMargin = (8 * d).toInt()
        }
    }
    val resultsAdapter = VodGridAdapter(resultsList, isGrid = true) { vod ->
        dialog.dismiss()
        onSelectVod(vod)
    }
    rvResults.adapter = resultsAdapter
    rootCard.addView(rvResults)

    val btnsRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        weightSum = 2f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (14 * d).toInt()
        }
    }

    val btnCancel = TextView(ctx).apply {
        text = "Cancel"
        setTextColor(Color.parseColor("#E2E8F0"))
        textSize = 14f
        typeface = headerFont
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * d
            setColor(Color.parseColor("#202431"))
            setStroke((1 * d).toInt(), Color.parseColor("#29FFFFFF"))
        }
        layoutParams = LinearLayout.LayoutParams(0, (44 * d).toInt(), 1f).apply {
            marginEnd = (6 * d).toInt()
        }
        setOnClickListener { dialog.dismiss() }
    }

    var searchJob: Job? = null
    fun triggerSearch(q: String) {
        val clean = q.trim()
        if (clean.length < 2) {
            spinner.visibility = View.GONE
            rvResults.visibility = View.GONE
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Type at least 2 characters to search"
            return
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            spinner.visibility = View.VISIBLE
            val found = OttRepository.searchTmdbCatalog(clean, filterType)
            spinner.visibility = View.GONE
            resultsList.clear()
            resultsList.addAll(found)
            resultsAdapter.notifyDataSetChanged()
            if (found.isNotEmpty()) {
                tvStatus.visibility = View.GONE
                rvResults.visibility = View.VISIBLE
            } else {
                rvResults.visibility = View.GONE
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "No results found for \"$clean\""
            }
        }
    }

    val btnSearchNow = TextView(ctx).apply {
        text = "Search"
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = headerFont
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_btn_watch_now_luxury)
        layoutParams = LinearLayout.LayoutParams(0, (44 * d).toInt(), 1f).apply {
            marginStart = (6 * d).toInt()
        }
        setOnClickListener {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(etQuery.windowToken, 0)
            triggerSearch(etQuery.text?.toString().orEmpty())
        }
    }

    etQuery.doAfterTextChanged { text ->
        val q = text?.toString()?.trim().orEmpty()
        if (q.length >= 2) {
            searchJob?.cancel()
            searchJob = scope.launch {
                delay(300L)
                triggerSearch(q)
            }
        } else {
            spinner.visibility = View.GONE
            rvResults.visibility = View.GONE
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Type at least 2 characters to search"
        }
    }

    etQuery.setOnEditorActionListener { _, actionId, event ->
        if (actionId == EditorInfo.IME_ACTION_SEARCH ||
            (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            btnSearchNow.performClick()
            true
        } else false
    }

    btnsRow.addView(btnCancel)
    btnsRow.addView(btnSearchNow)
    rootCard.addView(btnsRow)

    dialog.setContentView(rootCard)
    dialog.show()
    dialog.window?.setLayout((screenW * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
}

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private var autoSlideJob: Job? = null
    private var heroItems: List<VodItem> = emptyList()
    private var extraSectionsContainer: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeBinding.bind(view)
        _binding = binding

        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val sectionTitleTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

        binding.tvRecentHeader.typeface = sectionTitleTypeface

        binding.rvHomeChannels.layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        binding.rvHomeSeries.layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentWatched.layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)

        binding.rvHomeChannels.adapter = PrimeSkeletonHelper.VodSkeletonAdapter(6, isGrid = false, isSeries = false)
        binding.rvHomeSeries.adapter = PrimeSkeletonHelper.VodSkeletonAdapter(6, isGrid = false, isSeries = false)

        val parentLinear = binding.rvHomeSeries.parent as? LinearLayout
        val firstHeaderLayout = parentLinear?.getChildAt(parentLinear.indexOfChild(binding.rvHomeChannels) - 1) as? LinearLayout
        (firstHeaderLayout?.getChildAt(0) as? TextView)?.typeface = sectionTitleTypeface

        val secondHeaderLayout = parentLinear?.getChildAt(parentLinear.indexOfChild(binding.rvHomeSeries) - 1) as? LinearLayout
        (secondHeaderLayout?.getChildAt(0) as? TextView)?.apply {
            text = "Popular Movies"
            typeface = sectionTitleTypeface
        }

        if (parentLinear != null && extraSectionsContainer == null) {
            extraSectionsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            parentLinear.addView(extraSectionsContainer)
        }

        // عرض رشيق (58dp) وطول متوازن غير مبالغ فيه (342dp) باش يبان الكوفر الجاي واللي فات بوضوح بلا ما يهبط بزاف لتحت
        val sidePeekPadding = (58 * d).toInt()
        binding.heroViewPager.layoutParams = binding.heroViewPager.layoutParams.apply {
            height = (362 * d).toInt()
        }
        binding.heroViewPager.apply {
            clipToPadding = false
            clipChildren = false
            offscreenPageLimit = 3
            setPadding(sidePeekPadding, (4 * d).toInt(), sidePeekPadding, (6 * d).toInt())
            (getChildAt(0) as? RecyclerView)?.apply {
                clipToPadding = false
                clipChildren = false
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            }
            setPageTransformer { page, position ->
                val clamped = position.coerceIn(-1.5f, 1.5f)
                val absPos = abs(clamped)
                val focus = (1f - absPos.coerceAtMost(1f))

                page.cameraDistance = 18000f * d
                page.rotation = clamped * 5.8f
                page.rotationY = clamped * -9.5f
                page.translationX = -clamped * (20f * d)
                page.translationY = absPos * (12f * d)

                val scale = 0.86f + (focus * 0.14f)
                page.scaleX = scale
                page.scaleY = scale
                page.alpha = (0.68f + focus * 0.32f).coerceIn(0.60f, 1f)
                page.translationZ = focus * (16f * d)
            }
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    updateHeroFavoriteButtonIcon(binding)
                    syncHeroPosterAmbientColor(position)
                }
            })
        }

        AnimUtils.attachPressBounce(binding.btnWatchFeatured)
        AnimUtils.attachPressBounce(binding.btnHeroFavorite)

        loadRecentHistorySection(binding)

        binding.recentHeaderRow.setOnClickListener {
            showWatchHistoryFullDialog()
        }
        binding.btnClearHistory.setOnClickListener {
            showWatchHistoryFullDialog()
        }

        binding.btnHomeSearch.setOnClickListener {
            showLuxurySearchDialog(ctx, viewLifecycleOwner.lifecycleScope, "ALL") { vod ->
                openVodDetail(vod)
            }
        }

        binding.btnSeeAllMovies.setOnClickListener {
            showSeeAllInfiniteDialog("Trending Today", false, "")
        }

        binding.btnSeeAllSeries.setOnClickListener {
            showSeeAllInfiniteDialog("Popular Movies", false, "")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val sections = OttRepository.fetchHomeSections()

            heroItems = (sections.heroItems + sections.trendingToday.take(8))
                .distinctBy { it.id }
                .filter { it.posterUrl.isNotEmpty() }

            if (heroItems.isNotEmpty()) {
                binding.heroViewPager.adapter = HeroBannerAdapter(heroItems) { vod ->
                    openVodDetail(vod)
                }
                val midStart = (Int.MAX_VALUE / 2) - ((Int.MAX_VALUE / 2) % heroItems.size)
                binding.heroViewPager.setCurrentItem(midStart, false)
                updateHeroFavoriteButtonIcon(binding)
                syncHeroPosterAmbientColor(midStart)
                startHeroAutoSlider(binding)
            }

            // عرض الصفوف فوراً وبالتدريج باش الكوفرات يطلعو بالواحد بنعومة كيما Netflix
            binding.rvHomeChannels.adapter = VodGridAdapter(sections.trendingToday, isGrid = false) { vod ->
                openVodDetail(vod)
            }

            binding.rvHomeSeries.adapter = VodGridAdapter(sections.popularMovies, isGrid = false) { vod ->
                openVodDetail(vod)
            }

            extraSectionsContainer?.removeAllViews()
            addHomeRowSection("Popular TV Shows", sections.popularTvShows, true, "")
            addHomeRowSection("Top Rated Movies", sections.topRatedMovies, false, "TOP_MOVIE")
            addHomeRowSection("Top Rated TV", sections.topRatedTv, true, "TOP_TV")
            addHomeRowSection("Turkish Drama", sections.turkishDrama, true, "TR")
            addHomeRowSection("Korean Drama", sections.koreanDrama, true, "KR")
            addHomeRowSection("Action & Thriller", sections.actionMovies, false, "ACTION")
            addHomeRowSection("Anime & Animation", sections.animeShows, true, "ANIME")
            addHomeRowSection("Sci-Fi & Fantasy", sections.sciFiMovies, false, "SCIFI")
            addHomeRowSection("Documentaries", sections.documentaries, false, "DOC")
        }

        binding.btnWatchFeatured.setOnClickListener {
            if (heroItems.isNotEmpty()) {
                val realIdx = binding.heroViewPager.currentItem % heroItems.size
                openVodDetail(heroItems[realIdx])
            }
        }

        binding.btnHeroFavorite.setOnClickListener {
            if (heroItems.isNotEmpty()) {
                val realIdx = binding.heroViewPager.currentItem % heroItems.size
                val vod = heroItems[realIdx]
                OttRepository.toggleFavorite(vod.id, requireContext())
                updateHeroFavoriteButtonIcon(binding)
            }
        }
    }

    private fun syncHeroPosterAmbientColor(pagerPos: Int) {
        if (heroItems.isEmpty() || !isAdded) return
        val vod = heroItems[pagerPos % heroItems.size]
        val posterUrl = vod.posterUrl
        if (posterUrl.isEmpty()) return

        bannerColorCache[vod.id]?.let { cached ->
            (activity as? MainActivity)?.updateAmbientColorFromHero(cached)
            return
        }

        Glide.with(this)
            .asBitmap()
            .load(posterUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(object : CustomTarget<Bitmap>(120, 180) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (!isAdded) return
                    val color = extractDominantBannerColor(resource)
                    bannerColorCache[vod.id] = color
                    val currentRealIdx = (_binding?.heroViewPager?.currentItem ?: 0) % heroItems.size.coerceAtLeast(1)
                    if (heroItems.getOrNull(currentRealIdx)?.id == vod.id) {
                        (activity as? MainActivity)?.updateAmbientColorFromHero(color)
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    private fun updateHeroFavoriteButtonIcon(binding: FragmentHomeBinding) {
        if (heroItems.isEmpty()) return
        val realIdx = binding.heroViewPager.currentItem % heroItems.size
        val vod = heroItems[realIdx]
        val isFav = OttRepository.isChannelFavorite(vod.id)
        binding.btnHeroFavorite.setImageResource(
            if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        )
    }

    private fun addHomeRowSection(title: String, items: List<VodItem>, isSeries: Boolean, filterCode: String) {
        if (items.isEmpty()) return
        val container = extraSectionsContainer ?: return
        val rowView = layoutInflater.inflate(R.layout.item_home_section_row, container, false)

        val tvTitle = rowView.findViewById<TextView>(R.id.tvSectionRowTitle)
        val btnSeeAll = rowView.findViewById<TextView>(R.id.btnSectionRowSeeAll)
        val rvRow = rowView.findViewById<RecyclerView>(R.id.rvSectionRowItems)

        tvTitle.text = title
        tvTitle.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        btnSeeAll.setOnClickListener {
            showSeeAllInfiniteDialog(title, isSeries, filterCode)
        }

        rvRow.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvRow.adapter = VodGridAdapter(items, isGrid = false) { vod -> openVodDetail(vod) }
        container.addView(rowView)
    }

    private fun showSeeAllInfiniteDialog(sectionTitle: String, isSeries: Boolean, filterCode: String) {
        val ctx = requireContext()
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = layoutInflater.inflate(R.layout.dialog_see_all_grid, null, false)
        root.background = com.primeott.app.FlixVisionStandardBgDrawable()

        val btnBack = root.findViewById<ImageView>(R.id.btnSeeAllBack)
        val tvHeader = root.findViewById<TextView>(R.id.tvSeeAllHeaderTitle)
        val rv = root.findViewById<RecyclerView>(R.id.rvSeeAllGrid)

        btnBack.setOnClickListener { dialog.dismiss() }
        tvHeader.text = buildTwoToneHeaderTitle(sectionTitle)

        val allItems = mutableListOf<VodItem>()
        var currentPage = 1
        var isLoadingPage = false

        val gridLayoutManager = GridLayoutManager(ctx, 3)
        rv.layoutManager = gridLayoutManager
        val gridAdapter = VodGridAdapter(allItems, isGrid = true) { vod ->
            openVodDetail(vod)
        }
        rv.adapter = gridAdapter

        fun loadNextPage() {
            if (isLoadingPage) return
            isLoadingPage = true
            viewLifecycleOwner.lifecycleScope.launch {
                val newItems = OttRepository.fetchVodCatalogPage(isSeries, currentPage, filterCode)
                if (newItems.isNotEmpty()) {
                    val startPos = allItems.size
                    allItems.addAll(newItems)
                    gridAdapter.notifyItemRangeInserted(startPos, newItems.size)
                    currentPage++
                }
                isLoadingPage = false
            }
        }

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) {
                    val total = gridLayoutManager.itemCount
                    val lastVisible = gridLayoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= total - 6) {
                        loadNextPage()
                    }
                }
            }
        })

        loadNextPage()
        dialog.setContentView(root)
        applyTrueFullscreenDialog(dialog, root)
        dialog.show()
    }

    private fun startHeroAutoSlider(binding: FragmentHomeBinding) {
        autoSlideJob?.cancel()
        autoSlideJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(4800L)
                if (heroItems.size > 1 && binding.heroViewPager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
                    binding.heroViewPager.setCurrentItem(binding.heroViewPager.currentItem + 1, true)
                }
            }
        }
    }

    /**
     * فتح صفحة الفيلم مباشرة في 0ms بلا قراءة ملف JSON على الـ Main Thread
     */
    private fun openVodDetail(vod: VodItem) {
        startActivity(Intent(requireContext(), ContentDetailActivity::class.java).apply {
            putExtra(ContentDetailActivity.EXTRA_VOD, vod)
        })
    }

    private fun openHistoryEntryDetail(entry: WatchHistoryEntry) {
        val vod = VodItem(
            id = entry.id,
            title = entry.title,
            category = if (entry.isSeries) "Series" else "Movie",
            rating = entry.rating,
            year = entry.year,
            posterUrl = entry.posterUrl,
            backdropUrl = entry.backdropUrl,
            streamUrls = emptyList(),
            isSeries = entry.isSeries,
            savedPositionMs = entry.positionMs,
            totalDurationMs = entry.durationMs
        )
        startActivity(Intent(requireContext(), ContentDetailActivity::class.java).apply {
            putExtra(ContentDetailActivity.EXTRA_VOD, vod)
        })
    }

    private fun loadRecentHistorySection(binding: FragmentHomeBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            val historyList = withContext(Dispatchers.IO) {
                SessionManager.getWatchHistoryList(requireContext())
                    .filter { it.positionMs > 1000L && it.durationMs > 0L }
            }

            if (historyList.isEmpty()) {
                binding.recentHeaderRow.visibility = View.GONE
                binding.rvRecentWatched.visibility = View.GONE
                return@launch
            }
            binding.recentHeaderRow.visibility = View.VISIBLE
            binding.rvRecentWatched.visibility = View.VISIBLE
            binding.rvRecentWatched.adapter = RecentLandscapeAdapter(
                items = historyList,
                onClick = { entry -> openHistoryEntryDetail(entry) },
                onRemoveRequest = { entry ->
                    showRemoveWatchHistoryPopup(
                        titleText = "Remove from Continue Watching?",
                        messageText = "Remove \"${entry.title}\" from your watch history?",
                        confirmLabel = "Remove"
                    ) {
                        SessionManager.deleteWatchHistoryItems(requireContext(), setOf(entry.id))
                        _binding?.let { loadRecentHistorySection(it) }
                    }
                }
            )
        }
    }

    private fun showRemoveWatchHistoryPopup(
        titleText: String,
        messageText: String,
        confirmLabel: String = "Remove",
        onConfirm: () -> Unit
    ) {
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val mediumFont = ResourcesCompat.getFont(ctx, R.font.product_sans_bold)
            ?: Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val regularFont = ResourcesCompat.getFont(ctx, R.font.product_sans_egular)
            ?: Typeface.create("sans-serif", Typeface.NORMAL)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f * d
                colors = intArrayOf(Color.parseColor("#F5171923"), Color.parseColor("#FA0E1016"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke((1.1f * d).toInt(), Color.parseColor("#33FFFFFF"))
            }
            setPadding((22 * d).toInt(), (22 * d).toInt(), (22 * d).toInt(), (18 * d).toInt())
        }

        val tvTitle = TextView(ctx).apply {
            text = titleText
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = mediumFont
        }

        val tvMsg = TextView(ctx).apply {
            text = messageText
            setTextColor(Color.parseColor("#A6B0C2"))
            textSize = 13f
            typeface = regularFont
            setPadding(0, (8 * d).toInt(), 0, (20 * d).toInt())
        }

        val btnsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 2f
        }

        val btnCancel = TextView(ctx).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 14f
            typeface = mediumFont
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * d
                setColor(Color.parseColor("#242836"))
                setStroke((1 * d).toInt(), Color.parseColor("#2EFFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(0, (44 * d).toInt(), 1f).apply {
                marginEnd = (6 * d).toInt()
            }
            setOnClickListener { dialog.dismiss() }
        }

        val btnRemove = TextView(ctx).apply {
            text = confirmLabel
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = mediumFont
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_btn_watch_now_luxury)
            layoutParams = LinearLayout.LayoutParams(0, (44 * d).toInt(), 1f).apply {
                marginStart = (6 * d).toInt()
            }
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }

        btnsRow.addView(btnCancel)
        btnsRow.addView(btnRemove)
        card.addView(tvTitle)
        card.addView(tvMsg)
        card.addView(btnsRow)

        dialog.setContentView(card)
        dialog.show()
        val widthPx = (ctx.resources.displayMetrics.widthPixels * 0.86).toInt()
        dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showWatchHistoryFullDialog() {
        val ctx = requireContext()
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val d = ctx.resources.displayMetrics.density
        val headerFont = ResourcesCompat.getFont(ctx, R.font.product_sans_bold)
            ?: Typeface.create("sans-serif-condensed", Typeface.BOLD)

        var isSelectionMode = false
        val selectedIds = mutableSetOf<String>()
        val itemsList = SessionManager.getWatchHistoryList(ctx).toMutableList()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = com.primeott.app.FlixVisionStandardBgDrawable()
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (16 * d).toInt())
        }

        val btnBack = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_back_chevron)
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt())
            setOnClickListener { dialog.dismiss() }
        }

        val tvHeaderTitle = TextView(ctx).apply {
            text = "Watch History"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = headerFont
            letterSpacing = 0.02f
            setPadding((8 * d).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnSelectMode = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_edit_select)
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt()).apply {
                marginEnd = (6 * d).toInt()
            }
        }

        val btnTrashAction = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_trash_bin)
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt())
        }

        topBar.addView(btnBack)
        topBar.addView(tvHeaderTitle)
        topBar.addView(btnSelectMode)
        topBar.addView(btnTrashAction)
        root.addView(topBar)

        val rvGrid = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, 2)
            clipToPadding = false
            setPadding(0, 0, 0, (24 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(rvGrid)

        fun refreshGrid() {
            rvGrid.adapter = RecentLandscapeAdapter(
                items = itemsList,
                isGridMode = true,
                isSelectionMode = isSelectionMode,
                selectedIds = selectedIds,
                onClick = { entry ->
                    if (isSelectionMode) {
                        if (selectedIds.contains(entry.id)) selectedIds.remove(entry.id)
                        else selectedIds.add(entry.id)
                        tvHeaderTitle.text = if (selectedIds.isEmpty()) "Select Items" else "Selected (${selectedIds.size})"
                        refreshGrid()
                    } else {
                        dialog.dismiss()
                        openHistoryEntryDetail(entry)
                    }
                },
                onRemoveRequest = { entry ->
                    showRemoveWatchHistoryPopup(
                        titleText = "Remove from Watch History?",
                        messageText = "Remove \"${entry.title}\" from your history?",
                        confirmLabel = "Remove"
                    ) {
                        SessionManager.deleteWatchHistoryItems(ctx, setOf(entry.id))
                        itemsList.removeAll { it.id == entry.id }
                        selectedIds.remove(entry.id)
                        _binding?.let { loadRecentHistorySection(it) }
                        if (itemsList.isEmpty()) dialog.dismiss() else refreshGrid()
                    }
                }
            )
        }

        btnSelectMode.setOnClickListener {
            isSelectionMode = !isSelectionMode
            selectedIds.clear()
            btnSelectMode.setColorFilter(
                if (isSelectionMode) Color.parseColor("#FF1E27") else Color.WHITE
            )
            tvHeaderTitle.text = if (isSelectionMode) "Select Items" else "Watch History"
            refreshGrid()
        }

        btnTrashAction.setOnClickListener {
            if (isSelectionMode && selectedIds.isNotEmpty()) {
                val count = selectedIds.size
                showRemoveWatchHistoryPopup(
                    titleText = "Remove $count Selected?",
                    messageText = "Do you want to remove the selected items from Watch History?",
                    confirmLabel = "Remove ($count)"
                ) {
                    SessionManager.deleteWatchHistoryItems(ctx, selectedIds.toSet())
                    itemsList.removeAll { selectedIds.contains(it.id) }
                    selectedIds.clear()
                    isSelectionMode = false
                    btnSelectMode.setColorFilter(Color.WHITE)
                    tvHeaderTitle.text = "Watch History"
                    _binding?.let { loadRecentHistorySection(it) }
                    if (itemsList.isEmpty()) dialog.dismiss() else refreshGrid()
                }
            } else {
                showRemoveWatchHistoryPopup(
                    titleText = "Clear Watch History?",
                    messageText = "Are you sure you want to clear all movies and series from your Watch History?",
                    confirmLabel = "Clear All"
                ) {
                    SessionManager.clearAllWatchHistory(ctx)
                    itemsList.clear()
                    _binding?.let { loadRecentHistorySection(it) }
                    dialog.dismiss()
                }
            }
        }

        refreshGrid()
        dialog.setContentView(root)
        applyTrueFullscreenDialog(dialog, root)
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        _binding?.let {
            loadRecentHistorySection(it)
            updateHeroFavoriteButtonIcon(it)
        }
    }

    override fun onDestroyView() {
        autoSlideJob?.cancel()
        extraSectionsContainer = null
        _binding = null
        super.onDestroyView()
    }
}

class RecentLandscapeAdapter(
    private val items: List<WatchHistoryEntry>,
    private val isGridMode: Boolean = false,
    private val isSelectionMode: Boolean = false,
    private val selectedIds: Set<String> = emptySet(),
    private val onClick: (WatchHistoryEntry) -> Unit,
    private val onRemoveRequest: (WatchHistoryEntry) -> Unit = {}
) : RecyclerView.Adapter<RecentLandscapeAdapter.VH>() {

    class VH(
        val root: LinearLayout,
        val cardView: CardView,
        val ivThumb: ImageView,
        val selectBadge: TextView,
        val progressFillView: View,
        val tvTitle: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val screenW = ctx.resources.displayMetrics.widthPixels
        val cardWidthPx = if (isGridMode) {
            (screenW - (38 * d).toInt()) / 2
        } else {
            (170 * d).toInt()
        }
        val cardHeightPx = (cardWidthPx * 0.56f).toInt()
        val itemTitleFont = ResourcesCompat.getFont(ctx, R.font.product_sans_bold)
            ?: Typeface.create("sans-serif", Typeface.BOLD)

        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.MarginLayoutParams(cardWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (isGridMode) {
                    setMargins((5 * d).toInt(), (4 * d).toInt(), (5 * d).toInt(), (14 * d).toInt())
                } else {
                    marginEnd = (12 * d).toInt()
                }
            }
        }

        val card = CardView(ctx).apply {
            radius = 14f * d
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#14161E"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardHeightPx)
        }

        val frame = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val iv = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val selBadge = TextView(ctx).apply {
            text = "✓"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = itemTitleFont
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt(), Gravity.TOP or Gravity.END).apply {
                setMargins((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            }
        }

        val progressTrack = FrameLayout(ctx).apply {
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (3.5f * d).toInt(),
                Gravity.BOTTOM
            )
        }

        val progressFill = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#E50914"))
            layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        }

        progressTrack.addView(progressFill)
        frame.addView(iv)
        frame.addView(selBadge)
        frame.addView(progressTrack)
        card.addView(frame)

        val tv = TextView(ctx).apply {
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 12f
            typeface = itemTitleFont
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (2 * d).toInt())
        }

        wrap.addView(card)
        wrap.addView(tv)
        attachSnappyCardTouch(card)
        return VH(wrap, card, iv, selBadge, progressFill, tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        val d = ctx.resources.displayMetrics.density
        val screenW = ctx.resources.displayMetrics.widthPixels
        val cardWidthPx = if (isGridMode) {
            (screenW - (38 * d).toInt()) / 2
        } else {
            (170 * d).toInt()
        }

        holder.tvTitle.text = item.title
        val thumbUrl = item.backdropUrl.ifEmpty { item.posterUrl }
        val placeholder = PrimeSkeletonHelper.createCardPlaceholderDrawable(ctx, PrimeSkeletonHelper.MediaSkeletonType.MOVIE)
        if (thumbUrl.isNotEmpty()) {
            Glide.with(ctx)
                .load(thumbUrl)
                .placeholder(placeholder)
                .transition(DrawableTransitionOptions.withCrossFade(220))
                .centerCrop()
                .into(holder.ivThumb)
        } else {
            holder.ivThumb.setImageDrawable(placeholder)
        }

        val posMs = item.positionMs
        val durMs = item.durationMs
        val ratio = if (posMs > 0L && durMs > 0L) {
            (posMs.toFloat() / durMs.toFloat()).coerceIn(0.02f, 1.0f)
        } else {
            0f
        }
        holder.progressFillView.layoutParams = FrameLayout.LayoutParams(
            (cardWidthPx * ratio).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.START
        )

        if (isSelectionMode) {
            val isSelected = selectedIds.contains(item.id)
            holder.selectBadge.visibility = View.VISIBLE
            holder.selectBadge.text = if (isSelected) "✓" else ""
            holder.selectBadge.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isSelected) Color.parseColor("#E50914") else Color.parseColor("#880E1017"))
                setStroke((1.5f * d).toInt(), Color.WHITE)
            }
            holder.ivThumb.alpha = if (isSelected) 0.72f else 1.0f
        } else {
            holder.selectBadge.visibility = View.GONE
            holder.ivThumb.alpha = 1.0f
        }

        holder.cardView.setOnClickListener { onClick(item) }
        holder.cardView.setOnLongClickListener {
            onRemoveRequest(item)
            true
        }
        holder.tvTitle.setOnClickListener {
            if (isSelectionMode) onClick(item) else onRemoveRequest(item)
        }
        holder.tvTitle.setOnLongClickListener {
            onRemoveRequest(item)
            true
        }
    }
}

class HeroBannerAdapter(
    private val items: List<VodItem>,
    private val onClick: (VodItem) -> Unit
) : RecyclerView.Adapter<HeroBannerAdapter.VH>() {

    class VH(val binding: ItemHeroBannerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHeroBannerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        attachSnappyCardTouch(binding.root)
        return VH(binding)
    }

    override fun getItemCount(): Int = if (items.isEmpty()) 0 else Int.MAX_VALUE

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (items.isEmpty()) return
        val item = items[position % items.size]
        val ctx = holder.itemView.context

        holder.binding.tvHeroTitle.text = item.title
        holder.binding.tvHeroRating.text = item.rating
        holder.binding.tvHeroYear.text = item.year

        val placeholder = PrimeSkeletonHelper.createCardPlaceholderDrawable(
            ctx,
            if (item.isSeries) PrimeSkeletonHelper.MediaSkeletonType.SERIES
            else PrimeSkeletonHelper.MediaSkeletonType.MOVIE
        )

        if (item.posterUrl.isNotEmpty()) {
            Glide.with(ctx)
                .load(item.posterUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(placeholder)
                .error(placeholder)
                .transition(DrawableTransitionOptions.withCrossFade(240))
                .centerCrop()
                .into(holder.binding.ivHeroPoster)
        } else {
            holder.binding.ivHeroPoster.setImageDrawable(placeholder)
        }
        holder.binding.root.setOnClickListener { onClick(item) }
    }
}

class VodFragment : Fragment() {
    companion object {
        fun newInstance(isSeries: Boolean) = VodFragment().apply {
            arguments = Bundle().apply { putBoolean("IS_SERIES", isSeries) }
        }
    }

    private val catalogList = mutableListOf<VodItem>()
    private var currentPage = 1
    private var isLoadingMore = false
    private var selectedSortMode = "POPULAR"
    private var selectedGenreCode = "ALL"
    private val usedInitialPages = mutableSetOf<Int>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_vod_grid, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentVodGridBinding.bind(view)
        val isSeries = arguments?.getBoolean("IS_SERIES") ?: false
        val ctx = requireContext()

        binding.tvSectionHeader.text = buildTwoToneHeaderTitle(
            if (isSeries) "TV SHOWS" else "MOVIES"
        )

        val gridLayoutManager = GridLayoutManager(ctx, 3)
        binding.rvVodGrid.layoutManager = gridLayoutManager

        val gridAdapter = VodGridAdapter(catalogList, isGrid = true) { vod ->
            startActivity(Intent(ctx, ContentDetailActivity::class.java).apply {
                putExtra(ContentDetailActivity.EXTRA_VOD, vod)
            })
        }
        binding.rvVodGrid.adapter = gridAdapter

        fun reloadCatalogFromFirstPage() {
            isLoadingMore = true
            catalogList.clear()
            usedInitialPages.clear()
            gridAdapter.resetAnimationTracking()

            binding.rvVodGrid.adapter = PrimeSkeletonHelper.VodSkeletonAdapter(
                itemCountToShow = 12,
                isGrid = true,
                isSeries = isSeries
            )

            viewLifecycleOwner.lifecycleScope.launch {
                usedInitialPages.add(1)
                val firstPageItems = withContext(Dispatchers.IO) {
                    OttRepository.fetchVodCatalogPage(
                        isSeries = isSeries,
                        page = 1,
                        filterCode = selectedGenreCode,
                        sortMode = selectedSortMode
                    )
                }

                binding.rvVodGrid.adapter = gridAdapter
                if (firstPageItems.isNotEmpty()) {
                    catalogList.addAll(firstPageItems)
                    gridAdapter.notifyItemRangeInserted(0, firstPageItems.size)
                    currentPage = 2
                }
                isLoadingMore = false

                val bonusPage = (2..4).random()
                if (usedInitialPages.add(bonusPage)) {
                    val bonusItems = withContext(Dispatchers.IO) {
                        OttRepository.fetchVodCatalogPage(
                            isSeries = isSeries,
                            page = bonusPage,
                            filterCode = selectedGenreCode,
                            sortMode = selectedSortMode
                        )
                    }.filter { b -> catalogList.none { it.id == b.id } }

                    if (bonusItems.isNotEmpty()) {
                        val insertStart = catalogList.size
                        catalogList.addAll(bonusItems)
                        gridAdapter.notifyItemRangeInserted(insertStart, bonusItems.size)
                    }
                }
            }
        }

        fun loadNextCatalogPage() {
            if (isLoadingMore) return
            isLoadingMore = true
            while (usedInitialPages.contains(currentPage)) {
                currentPage++
            }
            val pageToFetch = currentPage
            usedInitialPages.add(pageToFetch)

            viewLifecycleOwner.lifecycleScope.launch {
                val pageItems = OttRepository.fetchVodCatalogPage(
                    isSeries = isSeries,
                    page = pageToFetch,
                    filterCode = selectedGenreCode,
                    sortMode = selectedSortMode
                ).filter { newItem -> catalogList.none { it.id == newItem.id } }

                if (pageItems.isNotEmpty()) {
                    val start = catalogList.size
                    catalogList.addAll(pageItems)
                    gridAdapter.notifyItemRangeInserted(start, pageItems.size)
                    currentPage = pageToFetch + 1
                }
                isLoadingMore = false
            }
        }

        val genres = if (isSeries) {
            listOf(
                "ALL" to "All",
                "10759" to "Action & Adventure",
                "16" to "Animation",
                "35" to "Comedy",
                "80" to "Crime",
                "18" to "Drama",
                "9648" to "Mystery",
                "10765" to "Sci-Fi & Fantasy",
                "10751" to "Family",
                "99" to "Documentary",
                "TR" to "Turkish",
                "KR" to "Korean"
            )
        } else {
            listOf(
                "ALL" to "All",
                "28" to "Action",
                "12" to "Adventure",
                "16" to "Animation",
                "35" to "Comedy",
                "80" to "Crime",
                "18" to "Drama",
                "14" to "Fantasy",
                "27" to "Horror",
                "9648" to "Mystery",
                "10749" to "Romance",
                "878" to "Sci-Fi",
                "53" to "Thriller",
                "TR" to "Turkish",
                "KR" to "Korean"
            )
        }

        binding.rvGenreChips.layoutManager =
            LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        binding.rvGenreChips.adapter = GenreChipsAdapter(genres, selectedGenreCode) { code ->
            selectedGenreCode = code
            reloadCatalogFromFirstPage()
        }

        val sortOptions = if (isSeries) {
            listOf(
                "POPULAR" to "Order by Popular",
                "TOP_RATED" to "Order by Top Rated",
                "UPCOMING" to "Order by On The Air",
                "NOW_PLAYING" to "Order by Airing Today"
            )
        } else {
            listOf(
                "POPULAR" to "Order by Popular",
                "TOP_RATED" to "Order by Top Rated",
                "UPCOMING" to "Order by Upcoming",
                "NOW_PLAYING" to "Order by Now Playing"
            )
        }

        binding.btnOrderFilter.setOnClickListener { anchor ->
            showOrderFilterPopupWindow(anchor, sortOptions, selectedSortMode) { chosenCode, chosenLabel ->
                selectedSortMode = chosenCode
                binding.tvOrderFilterLabel.text = chosenLabel
                reloadCatalogFromFirstPage()
            }
        }

        binding.btnVodSearch.setOnClickListener {
            showLuxurySearchDialog(
                ctx = ctx,
                scope = viewLifecycleOwner.lifecycleScope,
                filterType = if (isSeries) "SERIES" else "MOVIES"
            ) { vod ->
                startActivity(Intent(ctx, ContentDetailActivity::class.java).apply {
                    putExtra(ContentDetailActivity.EXTRA_VOD, vod)
                })
            }
        }

        binding.rvVodGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) {
                    val total = gridLayoutManager.itemCount
                    val lastVisible = gridLayoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= total - 6) {
                        loadNextCatalogPage()
                    }
                }
            }
        })

        if (catalogList.isEmpty()) {
            reloadCatalogFromFirstPage()
        }
    }

    private fun showOrderFilterPopupWindow(
        anchor: View,
        options: List<Pair<String, String>>,
        currentCode: String,
        onSelect: (String, String) -> Unit
    ) {
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        val itemFont = ResourcesCompat.getFont(ctx, R.font.product_sans_bold)
            ?: Typeface.create("sans-serif", Typeface.BOLD)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * d
                colors = intArrayOf(Color.parseColor("#F71B1E28"), Color.parseColor("#FA12141C"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke((1.2f * d).toInt(), Color.parseColor("#45FFFFFF"))
            }
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        }

        val popupWidth = (195 * d).toInt()
        val popup = PopupWindow(
            container,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = 20f * d
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        options.forEach { (code, label) ->
            val isSel = (code == currentCode)
            val tvItem = TextView(ctx).apply {
                text = label
                textSize = 13f
                typeface = itemFont
                setTextColor(if (isSel) Color.WHITE else Color.parseColor("#CBD5E1"))
                background = if (isSel) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 10f * d
                        setColor(Color.parseColor("#40E50914"))
                        setStroke((1 * d).toInt(), Color.parseColor("#A6E50914"))
                    }
                } else null
                setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (2 * d).toInt()
                }
                setOnClickListener {
                    popup.dismiss()
                    onSelect(code, label)
                }
            }
            container.addView(tvItem)
        }

        popup.showAsDropDown(anchor, 0, (6 * d).toInt(), Gravity.END)
    }
}

class GenreChipsAdapter(
    private val items: List<Pair<String, String>>,
    private var selectedCode: String,
    private val onSelectGenre: (String) -> Unit
) : RecyclerView.Adapter<GenreChipsAdapter.VH>() {

    class VH(
        val chipContainer: View,
        val dotIndicator: View,
        val tvChip: TextView
    ) : RecyclerView.ViewHolder(chipContainer)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_genre_chip, parent, false)
        val dot = view.findViewById<View>(R.id.vChipDot)
        val tv = view.findViewById<TextView>(R.id.tvChipLabel)
        return VH(view, dot, tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (code, label) = items[position]
        val isSelected = (code == selectedCode)
        val d = holder.itemView.context.resources.displayMetrics.density

        holder.tvChip.text = label
        holder.chipContainer.isSelected = isSelected

        if (isSelected) {
            holder.dotIndicator.visibility = View.VISIBLE
            holder.tvChip.setTextColor(Color.WHITE)
            holder.chipContainer.elevation = 6f * d
            holder.chipContainer.animate()
                .scaleX(1.04f)
                .scaleY(1.04f)
                .setDuration(180L)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        } else {
            holder.dotIndicator.visibility = View.GONE
            holder.tvChip.setTextColor(Color.parseColor("#CBD5E1"))
            holder.chipContainer.elevation = 0f
            holder.chipContainer.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(150L)
                .start()
        }

        holder.chipContainer.setOnClickListener {
            if (selectedCode != code) {
                val prevCode = selectedCode
                selectedCode = code
                val prevIdx = items.indexOfFirst { it.first == prevCode }
                if (prevIdx != -1) notifyItemChanged(prevIdx)
                notifyItemChanged(position)
                onSelectGenre(code)
            }
        }
    }
}

class VodGridAdapter(
    private val items: List<VodItem>,
    private val isGrid: Boolean = false,
    private val onClick: (VodItem) -> Unit
) : RecyclerView.Adapter<VodGridAdapter.VH>() {

    class VH(val binding: ItemVodCardBinding) : RecyclerView.ViewHolder(binding.root)

    private var lastAnimatedPosition = -1

    fun resetAnimationTracking() {
        lastAnimatedPosition = -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val b = ItemVodCardBinding.inflate(LayoutInflater.from(ctx), parent, false)
        val d = ctx.resources.displayMetrics.density

        if (isGrid) {
            b.root.layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (194 * d).toInt()
            ).apply {
                val m = (5.5f * d).toInt()
                setMargins(m, m, m, m)
            }
        } else {
            b.root.layoutParams = ViewGroup.MarginLayoutParams(
                (124 * d).toInt(),
                (196 * d).toInt()
            ).apply {
                val mH = (5.5f * d).toInt()
                val mV = (4 * d).toInt()
                setMargins(mH, mV, mH, mV)
            }
        }

        attachSnappyCardTouch(b.root)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.binding.tvVodTitle.text = item.title
        holder.binding.tvVodMeta.text = item.rating.ifEmpty { "7.5" }
        holder.binding.tvVodYear.text = item.year.ifEmpty { "2026" }

        val skeletonDrawable = PrimeSkeletonHelper.createCardPlaceholderDrawable(
            ctx,
            if (item.isSeries) PrimeSkeletonHelper.MediaSkeletonType.SERIES
            else PrimeSkeletonHelper.MediaSkeletonType.MOVIE
        )

        val fastPosterUrl = optimizeCardPosterUrl(item.posterUrl)
        if (fastPosterUrl.isNotEmpty()) {
            Glide.with(ctx)
                .load(fastPosterUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(skeletonDrawable)
                .error(skeletonDrawable)
                .transition(DrawableTransitionOptions.withCrossFade(250))
                .centerCrop()
                .into(holder.binding.ivPoster)
        } else {
            holder.binding.ivPoster.setImageDrawable(skeletonDrawable)
        }

        if (position > lastAnimatedPosition) {
            lastAnimatedPosition = position
            val v = holder.binding.root
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = 14f * ctx.resources.displayMetrics.density
            val staggerDelay = ((position % 6) * 35L).coerceAtMost(180L)
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(staggerDelay)
                .setDuration(230L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            holder.binding.root.alpha = 1f
            holder.binding.root.translationY = 0f
        }

        holder.binding.root.setOnClickListener { onClick(item) }
    }
}

class ScheduleFragment : Fragment() {
    private var controller: WatchTogetherController? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.activity_watch_room, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller = WatchTogetherController(
            hostContext = requireContext(),
            rootView = view,
            hostActivity = activity,
            isStandaloneActivity = false
        ).also { it.init() }
    }

    override fun onDestroyView() {
        controller?.release()
        controller = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(): ScheduleFragment = ScheduleFragment()
    }
}

class MatchesAdapter(
    private val matches: List<MatchFixture>,
    private val onWatchClick: (MatchFixture) -> Unit,
    private val onCardDetailsClick: (MatchFixture) -> Unit,
    private val onFavClick: (MatchFixture) -> Unit,
    private val onNotifyClick: (MatchFixture) -> Unit
) : RecyclerView.Adapter<MatchesAdapter.VH>() {
    class VH(val binding: ItemMatchBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemMatchBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = matches.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = matches[position]
        val b = holder.binding
        b.tvHomeTeam.text = m.homeTeam
        b.tvAwayTeam.text = m.awayTeam
        b.tvLeagueTitle.text = m.league
        b.tvMatchInfo.text = "${m.channel.name} | ${m.commentator}"
        b.tvScoreOrTime.text = if (m.status == "UPCOMING") m.matchTime else "${m.homeScore} - ${m.awayScore}"
        b.btnFavMatch.text = if (m.isFavoriteMatch) "★" else "☆"
        b.btnFavMatch.setOnClickListener { onFavClick(m) }
        b.btnNotifyMatch.setOnClickListener { onNotifyClick(m) }
        b.btnWatchMatch.setOnClickListener { onWatchClick(m) }
        b.root.setOnClickListener { onCardDetailsClick(m) }
    }
}

class SettingsFragment : Fragment() {
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && isAdded) {
            Toast.makeText(requireContext(), "Microphone Ready!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentSettingsBinding.bind(view)
        val ctx = requireContext()
        val devId = SessionManager.getDeviceId(ctx)
        binding.etSettingsUserName.setText(SessionManager.getUserName(ctx))
        binding.tvDeviceId.text = "Device ID: $devId | Status: ${SessionManager.getExpireDate(ctx)}"

        binding.btnSaveProfileName.setOnClickListener {
            val n = binding.etSettingsUserName.text.toString().trim()
            if (n.isNotEmpty()) {
                SessionManager.setUserName(ctx, n)
                Toast.makeText(ctx, "Saved!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnActivateCode.setOnClickListener {
            val code = binding.etActivationCode.text.toString().trim()
            if (code.isEmpty()) return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                val (ok, msg) = OttRepository.activateSubscription(code, devId)
                if (ok) {
                    SessionManager.saveActivation(ctx, "TOKEN_$code", msg)
                    binding.tvDeviceId.text = "Device ID: $devId | Active: $msg"
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnLogoutSession.setOnClickListener {
            SessionManager.clearSession(ctx)
            binding.tvDeviceId.text = "Device ID: $devId | Free Mode"
        }

        binding.btnChangeAppLanguage.setOnClickListener {
            val languageOptions = arrayOf("العربية (RTL)", "Français", "English")
            val codes = arrayOf("ar", "fr", "en")
            AlertDialog.Builder(ctx)
                .setTitle("Language")
                .setItems(languageOptions) { _, which ->
                    SessionManager.setLanguage(ctx, codes[which])
                    LocaleHelper.applyLayoutDirection(requireActivity())
                    requireActivity().recreate()
                }
                .show()
        }

        binding.btnCheckMicPermission.setOnClickListener {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(ctx, "Microphone Ready!", Toast.LENGTH_SHORT).show()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}