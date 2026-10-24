package com.primeott.app.presentation.sections
import com.primeott.app.presentation.watch.WatchTogetherController

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
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
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.FragmentHomeBinding
import com.primeott.app.databinding.FragmentMatchCenterBinding
import com.primeott.app.databinding.FragmentSettingsBinding
import com.primeott.app.databinding.FragmentVodGridBinding
import com.primeott.app.databinding.ItemMatchBinding
import com.primeott.app.databinding.ItemVodCardBinding
import com.primeott.app.domain.model.Category
import com.primeott.app.domain.model.MatchFixture
import com.primeott.app.domain.model.VodItem
import com.primeott.app.domain.model.WatchHistoryEntry
import com.primeott.app.presentation.common.AnimUtils
import com.primeott.app.presentation.common.LocaleHelper
import com.primeott.app.presentation.detail.ContentDetailActivity
import com.primeott.app.presentation.livetv.CategoriesAdapter
import com.primeott.app.presentation.player.PlayerActivity
import com.primeott.app.presentation.watch.WatchRoomActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * دالة مشتركة تخلي أي Dialog يدي الشاشة كاملة 100% (حتى فوق الـ Status Bar والكاميرا)
 * باش ما يبانش الكوفر تاع الخلفية من الفوق كيما كان فـ image_dd32d8.jpg
 */
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

/**
 * Popup بحث فخمة ومنظمة (تعوض AlertDialog البشعة لي فـ image_dd32f6.jpg)
 * تخدم فـ Home (الكل)، وفـ Movies (أفلام برك)، وفـ Series (مسلسلات برك)
 */
private fun showLuxurySearchDialog(
    ctx: Context,
    scope: LifecycleCoroutineScope,
    filterType: String, // "ALL", "MOVIES", "SERIES"
    onSelectVod: (VodItem) -> Unit
) {
    val d = ctx.resources.displayMetrics.density
    val dialog = Dialog(ctx)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

    val headerFont = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val bodyFont = Typeface.create("sans-serif", Typeface.NORMAL)
    val btnFont = Typeface.create("sans-serif-medium", Typeface.BOLD)

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

    // Header Row: Title + Close X
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
        typeface = btnFont
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

    // Search Input Box (b ic_search + design glass)
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
        "MOVIES" -> "Type movie title (e.g. Spider-Man, Batman)..."
        "SERIES" -> "Type series title (e.g. Breaking Bad, From)..."
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

    // Status / Results Header
    val tvStatus = TextView(ctx).apply {
        text = "Type at least 2 characters to search"
        setTextColor(Color.parseColor("#94A3B8"))
        textSize = 12f
        typeface = bodyFont
        gravity = Gravity.CENTER
        setPadding(0, (14 * d).toInt(), 0, (10 * d).toInt())
    }
    rootCard.addView(tvStatus)

    // Results Grid (3 columns)
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

    // Bottom Buttons Row (Cancel + Search)
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
        typeface = btnFont
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
        typeface = btnFont
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
                delay(320L)
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

        // تطبيق خط العناوين المميز (sans-serif-condensed Bold) على عناوين Home
        val titleTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        binding.tvRecentHeader.typeface = titleTypeface
        binding.tvRecentHeader.letterSpacing = 0.02f

        binding.rvHomeChannels.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvHomeSeries.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentWatched.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        // 1. إظهار الـ Skeleton فوراً قبل ما يطلعو الأفلام من الإنترنت
        binding.rvHomeChannels.adapter = PrimeSkeletonHelper.VodSkeletonAdapter(6, isGrid = false, isSeries = false)
        binding.rvHomeSeries.adapter = PrimeSkeletonHelper.VodSkeletonAdapter(6, isGrid = false, isSeries = false)

        val parentLinear = binding.rvHomeSeries.parent as? LinearLayout
        val firstHeaderLayout = parentLinear?.getChildAt(parentLinear.indexOfChild(binding.rvHomeChannels) - 1) as? LinearLayout
        (firstHeaderLayout?.getChildAt(0) as? TextView)?.apply {
            typeface = titleTypeface
            letterSpacing = 0.02f
        }
        val secondHeaderLayout = parentLinear?.getChildAt(parentLinear.indexOfChild(binding.rvHomeSeries) - 1) as? LinearLayout
        (secondHeaderLayout?.getChildAt(0) as? TextView)?.apply {
            text = "Popular Movies"
            typeface = titleTypeface
            letterSpacing = 0.02f
        }

        if (parentLinear != null && extraSectionsContainer == null) {
            extraSectionsContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            parentLinear.addView(extraSectionsContainer)
        }
        // 2. إعداد الـ Hero Banner بتصميم مائل على الجوانب (Tilted 3D Carousel kima image_8ce4be.jpg)
        binding.heroViewPager.apply {
            clipToPadding = false
            clipChildren = false
            offscreenPageLimit = 3
            (getChildAt(0) as? RecyclerView)?.apply {
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER
                clipToPadding = false
                clipChildren = false
            }

            val d = resources.displayMetrics.density
            val cameraScale = resources.displayMetrics.density * 10000f

            setPageTransformer { page, position ->
                val clampedPos = position.coerceIn(-1.5f, 1.5f)
                val absPos = abs(clampedPos)
                val focusFactor = (1f - absPos).coerceAtLeast(0f)

                // تفعيل منظور الكاميرا ثلاثي الأبعاد
                page.cameraDistance = cameraScale
                page.pivotX = page.width * 0.5f
                page.pivotY = page.height * 0.5f

                if (absPos < 0.001f) {
                    // الكارت المركزي في الوسط: واضح، مسطح 100% وفي المقدمة
                    page.rotationY = 0f
                    page.rotation = 0f
                    page.scaleX = 1.0f
                    page.scaleY = 1.0f
                    page.translationX = 0f
                    page.translationY = 0f
                    page.translationZ = 24f * d
                    page.alpha = 1.0f
                } else {
                    // الكروت الجانبية: عكسنا الإشارة باش يدورو بالظهر تاعهم نحو الخارج (Reverse Y-axis Rotation)
                    page.rotationY = clampedPos * 42f // إشارة موجبة بدل سالبة باش يلتف العكس

                    // ميلان خفيف متناسق
                    page.rotation = clampedPos * -5.0f

                    // سحب الكروت الجانبية للخارج
                    page.translationX = clampedPos * (page.width * 0.18f)
                    page.translationY = absPos * (18f * d)

                    // وضع الكروت الجانبية في الخلف عمودياً
                    page.translationZ = (1f - absPos) * (-12f * d)

                    // تصغير الحجم والتعتيم باش يبقى التركيز على الكارت الوسطاني
                    page.scaleX = 0.82f + (focusFactor * 0.18f)
                    page.scaleY = 0.80f + (focusFactor * 0.20f)
                    page.alpha = 0.45f + (focusFactor * 0.55f)
                }
            }

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    updateHeroFavoriteButtonIcon(binding)
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
            showLuxurySearchDialog(requireContext(), viewLifecycleOwner.lifecycleScope, "ALL") { vod ->
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

            // جلب صفحات إضافية متجددة باش ما يبقاوش نفس الـ 20 فيلم تاع الصفحة الأولى لمدة 3 أيام
            val freshMoviesPage2 = runCatching {
                OttRepository.fetchVodCatalogPage(isSeries = false, page = (2..5).random(), filterCode = "ALL", sortMode = "POPULAR")
            }.getOrDefault(emptyList())

            val freshSeriesPage2 = runCatching {
                OttRepository.fetchVodCatalogPage(isSeries = true, page = (2..4).random(), filterCode = "ALL", sortMode = "POPULAR")
            }.getOrDefault(emptyList())

            // دمج وتجديد قائمة الـ Hero Banner في كل دخلة للتطبيق
            val combinedHeroPool = (sections.heroItems + sections.trendingToday.take(10) + freshMoviesPage2.take(6))
                .distinctBy { it.id }
                .filter { it.posterUrl.isNotEmpty() }
                .shuffled()

            heroItems = combinedHeroPool.ifEmpty { sections.heroItems }

            if (heroItems.isNotEmpty()) {
                binding.heroViewPager.adapter = HeroBannerAdapter(heroItems) { vod ->
                    openVodDetail(vod)
                }
                val midStart = (Int.MAX_VALUE / 2) - ((Int.MAX_VALUE / 2) % heroItems.size)
                binding.heroViewPager.setCurrentItem(midStart, false)
                updateHeroFavoriteButtonIcon(binding)
                startHeroAutoSlider(binding)
            }

            // تجديد ترتيب Trending Today و Popular Movies باش ديما يبانو أفلام جدد فـ الأول
            val dynamicTrending = (sections.trendingToday + freshMoviesPage2.take(8))
                .distinctBy { it.id }
                .shuffled()

            val dynamicPopularMovies = (sections.popularMovies + freshMoviesPage2.drop(8))
                .distinctBy { it.id }
                .shuffled()

            val dynamicPopularSeries = (sections.popularTvShows + freshSeriesPage2)
                .distinctBy { it.id }
                .shuffled()

            binding.rvHomeChannels.adapter = VodGridAdapter(dynamicTrending, isGrid = false) { vod ->
                openVodDetail(vod)
            }

            binding.rvHomeSeries.adapter = VodGridAdapter(dynamicPopularMovies, isGrid = false) { vod ->
                openVodDetail(vod)
            }

            extraSectionsContainer?.removeAllViews()
            addHomeRowSection("Popular TV Shows", dynamicPopularSeries, true, "")
            addHomeRowSection("Top Rated Movies", sections.topRatedMovies.shuffled(), false, "TOP_MOVIE")
            addHomeRowSection("Top Rated TV", sections.topRatedTv.shuffled(), true, "TOP_TV")
            addHomeRowSection("Turkish Drama", sections.turkishDrama.shuffled(), true, "TR")
            addHomeRowSection("Korean Drama", sections.koreanDrama.shuffled(), true, "KR")
            addHomeRowSection("Action & Thriller", sections.actionMovies.shuffled(), false, "ACTION")
            addHomeRowSection("Anime & Animation", sections.animeShows.shuffled(), true, "ANIME")
            addHomeRowSection("Sci-Fi & Fantasy", sections.sciFiMovies.shuffled(), false, "SCIFI")
            addHomeRowSection("Documentaries", sections.documentaries.shuffled(), false, "DOC")
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
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        val container = extraSectionsContainer ?: return
        val headerFont = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val actionFont = Typeface.create("sans-serif-medium", Typeface.BOLD)

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (18 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }
        val tvTitle = TextView(ctx).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = headerFont
            letterSpacing = 0.02f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvSeeAll = TextView(ctx).apply {
            text = "See All"
            setTextColor(Color.parseColor("#E50914"))
            textSize = 13f
            typeface = actionFont
            setOnClickListener {
                showSeeAllInfiniteDialog(title, isSeries, filterCode)
            }
        }
        headerRow.addView(tvTitle)
        headerRow.addView(tvSeeAll)
        container.addView(headerRow)

        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            clipToPadding = false
            setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (202 * d).toInt())
            adapter = VodGridAdapter(items, isGrid = false) { vod -> openVodDetail(vod) }
        }
        container.addView(rv)
    }

    private fun showSeeAllInfiniteDialog(sectionTitle: String, isSeries: Boolean, filterCode: String) {
        val ctx = requireContext()
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val d = ctx.resources.displayMetrics.density
        val headerFont = Typeface.create("sans-serif-condensed", Typeface.BOLD)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = com.primeott.app.FlixVisionStandardBgDrawable()
            setPadding((8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt(), 0)
        }

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (12 * d).toInt())
        }
        val btnBack = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_back_chevron)
            setPadding((8 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt())
            setOnClickListener { dialog.dismiss() }
        }
        val tvHeader = TextView(ctx).apply {
            text = sectionTitle
            setTextColor(Color.WHITE)
            textSize = 21f
            typeface = headerFont
            letterSpacing = 0.02f
        }
        topBar.addView(btnBack)
        topBar.addView(tvHeader)
        root.addView(topBar)

        val allItems = mutableListOf<VodItem>()
        var currentPage = 1
        var isLoadingPage = false

        val gridLayoutManager = GridLayoutManager(ctx, 3)
        val rv = RecyclerView(ctx).apply {
            layoutManager = gridLayoutManager
            clipToPadding = false
            setPadding(0, 0, 0, (24 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val gridAdapter = VodGridAdapter(allItems, isGrid = true) { vod ->
            openVodDetail(vod)
        }
        rv.adapter = gridAdapter
        root.addView(rv)

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
                delay(4500L)
                if (heroItems.size > 1) {
                    // يمشي للقدام بلا ما يكمل وبلا ما يعاود يرجع للوراء!
                    binding.heroViewPager.setCurrentItem(binding.heroViewPager.currentItem + 1, true)
                }
            }
        }
    }

    private fun openVodDetail(vod: VodItem) {
        val savedEntry = SessionManager.getWatchHistoryList(requireContext()).firstOrNull { it.id == vod.id }
        val vodWithProgress = if (savedEntry != null) {
            vod.copy(savedPositionMs = savedEntry.positionMs, totalDurationMs = savedEntry.durationMs)
        } else {
            SessionManager.saveWatchProgress(requireContext(), vod, 0L, 0L)
            vod
        }
        startActivity(Intent(requireContext(), ContentDetailActivity::class.java).apply {
            putExtra(ContentDetailActivity.EXTRA_VOD, vodWithProgress)
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
        val historyList = SessionManager.getWatchHistoryList(requireContext())
        if (historyList.isEmpty()) {
            binding.recentHeaderRow.visibility = View.GONE
            binding.rvRecentWatched.visibility = View.GONE
            return
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

        val mediumFont = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val regularFont = Typeface.create("sans-serif", Typeface.NORMAL)

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
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
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
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
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

    /**
     * صفحة Watch History الكاملة 100% Fullscreen (بلا ما يبان الكوفر الفوقاني)
     */
    private fun showWatchHistoryFullDialog() {
        val ctx = requireContext()
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val d = ctx.resources.displayMetrics.density
        val headerFont = Typeface.create("sans-serif-condensed", Typeface.BOLD)

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
        val itemTitleFont = Typeface.create("sans-serif", Typeface.BOLD)

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
        if (thumbUrl.isNotEmpty()) {
            Glide.with(ctx).load(thumbUrl).centerCrop().into(holder.ivThumb)
        }

        val posMs = item.positionMs
        val durMs = item.durationMs
        val ratio = if (posMs > 5000L && durMs > 10000L) {
            (posMs.toFloat() / durMs.toFloat()).coerceIn(0.12f, 1.0f)
        } else {
            0.28f
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

    class VH(
        val rootCard: CardView,
        val ivHeroPoster: ImageView,
        val tvHeroRating: TextView,
        val tvHeroYear: TextView,
        val tvHeroTitle: TextView
    ) : RecyclerView.ViewHolder(rootCard)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val titleFont = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.product_sans_bold)
            ?: Typeface.create("sans-serif", Typeface.BOLD)
        val pillFont = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.product_sans_egular)
            ?: Typeface.create("sans-serif-medium", Typeface.BOLD)

        val card = CardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                val marginH = (4 * density).toInt()
                setMargins(marginH, 0, marginH, 0)
            }
            radius = 22f * density
            cardElevation = 10f * density
            setCardBackgroundColor(Color.parseColor("#0D1015"))
        }

        val container = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val ivPoster = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val bottomOverlay = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (155 * density).toInt(),
                Gravity.BOTTOM
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.parseColor("#8005070A"),
                    Color.parseColor("#E605070A")
                )
            )
        }

        val badgeBg = {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(Color.parseColor("#B80A0D12"))
            }
        }

        val tvRating = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (30 * density).toInt(),
                Gravity.TOP or Gravity.START
            ).apply {
                val m = (14 * density).toInt()
                setMargins(m, m, m, m)
            }
            background = badgeBg()
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_star_gold, 0, 0, 0)
            compoundDrawablePadding = (5 * density).toInt()
            gravity = Gravity.CENTER
            val padH = (11 * density).toInt()
            setPadding(padH, 0, padH, 0)
            setTextColor(Color.parseColor("#FFC107"))
            textSize = 13f
            typeface = titleFont
        }

        val tvYear = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (30 * density).toInt(),
                Gravity.TOP or Gravity.END
            ).apply {
                val m = (14 * density).toInt()
                setMargins(m, m, m, m)
            }
            background = badgeBg()
            gravity = Gravity.CENTER
            val padH = (11 * density).toInt()
            setPadding(padH, 0, padH, 0)
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = pillFont
        }

        val tvTitle = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                setMargins((16 * density).toInt(), 0, (16 * density).toInt(), (20 * density).toInt())
            }
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            maxLines = 1
            letterSpacing = 0.01f
            setShadowLayer(10f, 0f, 3f, Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = titleFont
        }

        container.addView(ivPoster)
        container.addView(bottomOverlay)
        container.addView(tvRating)
        container.addView(tvYear)
        container.addView(tvTitle)
        card.addView(container)

        return VH(card, ivPoster, tvRating, tvYear, tvTitle)
    }

    // عدد لا نهائي باش الـ Banner ما يحبسش وما يعاودش يرجع للوراء
    override fun getItemCount(): Int = if (items.isEmpty()) 0 else Int.MAX_VALUE

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (items.isEmpty()) return
        val item = items[position % items.size]
        val ctx = holder.itemView.context

        holder.tvHeroTitle.text = item.title
        holder.tvHeroRating.text = item.rating
        holder.tvHeroYear.text = item.year

        val placeholder = PrimeSkeletonHelper.createCardPlaceholderDrawable(
            ctx,
            if (item.isSeries) PrimeSkeletonHelper.MediaSkeletonType.SERIES
            else PrimeSkeletonHelper.MediaSkeletonType.MOVIE
        )

        if (item.posterUrl.isNotEmpty()) {
            Glide.with(ctx)
                .load(item.posterUrl)
                .placeholder(placeholder)
                .error(placeholder)
                .centerCrop()
                .into(holder.ivHeroPoster)
        } else {
            holder.ivHeroPoster.setImageDrawable(placeholder)
        }
        holder.rootCard.setOnClickListener { onClick(item) }
    }
}

/**
 * صفحة Movies و TV Shows (مع الفلتر المنبثق Order by + شريط الكروت الصغار تاع التصنيفات + البحث المخصص)
 */
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_vod_grid, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentVodGridBinding.bind(view)
        val isSeries = arguments?.getBoolean("IS_SERIES") ?: false

        binding.tvSectionHeader.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        binding.tvSectionHeader.letterSpacing = 0.03f
        binding.tvSectionHeader.text = if (isSeries) "TV Shows" else "Movies"

        val gridLayoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvVodGrid.layoutManager = gridLayoutManager

        val gridAdapter = VodGridAdapter(catalogList, isGrid = true) { vod ->
            val savedPos = SessionManager.getSavedPositionForVod(requireContext(), vod.id)
            SessionManager.saveWatchProgress(requireContext(), vod, savedPos, 0L)
            startActivity(Intent(requireContext(), ContentDetailActivity::class.java).apply {
                putExtra(ContentDetailActivity.EXTRA_VOD, vod.copy(savedPositionMs = savedPos))
            })
        }
        binding.rvVodGrid.adapter = gridAdapter

        fun reloadCatalogFromFirstPage() {
            currentPage = 1
            isLoadingMore = false
            catalogList.clear()
            // عرض Skeleton خاص بـ Movies أو Series بينما توجد البيانات
            binding.rvVodGrid.adapter = PrimeSkeletonHelper.VodSkeletonAdapter(
                itemCountToShow = 12,
                isGrid = true,
                isSeries = isSeries
            )
            isLoadingMore = true
            viewLifecycleOwner.lifecycleScope.launch {
                val pageItems = OttRepository.fetchVodCatalogPage(
                    isSeries = isSeries,
                    page = 1,
                    filterCode = selectedGenreCode,
                    sortMode = selectedSortMode
                )
                binding.rvVodGrid.adapter = gridAdapter
                if (pageItems.isNotEmpty()) {
                    catalogList.addAll(pageItems)
                    gridAdapter.notifyDataSetChanged()
                    currentPage = 2
                }
                isLoadingMore = false
            }
        }

        fun loadNextCatalogPage() {
            if (isLoadingMore) return
            isLoadingMore = true
            viewLifecycleOwner.lifecycleScope.launch {
                val pageItems = OttRepository.fetchVodCatalogPage(
                    isSeries = isSeries,
                    page = currentPage,
                    filterCode = selectedGenreCode,
                    sortMode = selectedSortMode
                )
                if (pageItems.isNotEmpty()) {
                    val start = catalogList.size
                    catalogList.addAll(pageItems)
                    gridAdapter.notifyItemRangeInserted(start, pageItems.size)
                    currentPage++
                }
                isLoadingMore = false
            }
        }

        // 1. إعداد شريط الكروت الصغار تاع التصنيفات (All, Action, Adventure, Animation...)
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
                "53" to "Thriller"
            )
        }

        binding.rvGenreChips.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvGenreChips.adapter = GenreChipsAdapter(genres, selectedGenreCode) { code ->
            selectedGenreCode = code
            reloadCatalogFromFirstPage()
        }

        // 2. إعداد الفلتر المنبثق (Order by Popular / Top Rated / Upcoming / Now Playing) كيما image_dd35fc.png
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

        // 3. إعداد زر البحث الخاص بـ Movies برك أو Series برك
        binding.btnVodSearch.setOnClickListener {
            showLuxurySearchDialog(
                ctx = requireContext(),
                scope = viewLifecycleOwner.lifecycleScope,
                filterType = if (isSeries) "SERIES" else "MOVIES"
            ) { vod ->
                val savedPos = SessionManager.getSavedPositionForVod(requireContext(), vod.id)
                SessionManager.saveWatchProgress(requireContext(), vod, savedPos, 0L)
                startActivity(Intent(requireContext(), ContentDetailActivity::class.java).apply {
                    putExtra(ContentDetailActivity.EXTRA_VOD, vod.copy(savedPositionMs = savedPos))
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

    /**
     * نافذة الفلتر الصغيرة المنبثقة (PopupWindow) مطابقة لـ image_dd35fc.png
     */
    private fun showOrderFilterPopupWindow(
        anchor: View,
        options: List<Pair<String, String>>,
        currentCode: String,
        onSelect: (String, String) -> Unit
    ) {
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        val itemFont = Typeface.create("sans-serif", Typeface.BOLD)

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

/**
 * Adapter تاع شريط كروت التصنيفات (All, Action, Adventure, Animation... كيما image_dd3621.jpg)
 */
class GenreChipsAdapter(
    private val items: List<Pair<String, String>>,
    private var selectedCode: String,
    private val onSelectGenre: (String) -> Unit
) : RecyclerView.Adapter<GenreChipsAdapter.VH>() {

    class VH(val tvChip: TextView) : RecyclerView.ViewHolder(tvChip)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val tv = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 12.5f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (34 * d).toInt()
            ).apply {
                marginEnd = (8 * d).toInt()
                topMargin = (3 * d).toInt()
                bottomMargin = (3 * d).toInt()
            }
        }
        return VH(tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (code, label) = items[position]
        val isSelected = (code == selectedCode)
        val d = holder.itemView.context.resources.displayMetrics.density

        holder.tvChip.text = label
        if (isSelected) {
            holder.tvChip.setTextColor(Color.WHITE)
            holder.tvChip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * d
                setColor(Color.parseColor("#E50914"))
                setStroke((1 * d).toInt(), Color.parseColor("#80FF6B72"))
            }
            holder.tvChip.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
        } else {
            holder.tvChip.setTextColor(Color.parseColor("#D5D9E2"))
            holder.tvChip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * d
                setColor(Color.parseColor("#11131A"))
                setStroke((1.1f * d).toInt(), Color.parseColor("#38FFFFFF"))
            }
            holder.tvChip.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
        }

        holder.tvChip.setOnClickListener {
            if (selectedCode != code) {
                selectedCode = code
                notifyDataSetChanged()
                onSelectGenre(code)
            }
        }
    }
}

/**
 * VodGridAdapter (مضبوط التباعد فـ الـ Grid باش ما يتلاصقوش الكروت فـ بعضاهم كيما image_dd359d.jpg)
 */
class VodGridAdapter(
    private val items: List<VodItem>,
    private val isGrid: Boolean = false,
    private val onClick: (VodItem) -> Unit
) : RecyclerView.Adapter<VodGridAdapter.VH>() {
    class VH(val binding: ItemVodCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemVodCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val d = parent.context.resources.displayMetrics.density
        if (isGrid) {
            b.root.layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (186 * d).toInt()
            ).apply {
                val m = (5.5f * d).toInt()
                setMargins(m, m, m, m)
            }
        } else {
            b.root.layoutParams = ViewGroup.MarginLayoutParams(
                (116 * d).toInt(),
                (188 * d).toInt()
            ).apply {
                val mH = (5.5f * d).toInt()
                val mV = (4 * d).toInt()
                setMargins(mH, mV, mH, mV)
            }
        }
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.binding.tvVodTitle.text = item.title
        holder.binding.tvVodMeta.text = item.rating

        val skeletonDrawable = PrimeSkeletonHelper.createCardPlaceholderDrawable(
            ctx,
            if (item.isSeries) PrimeSkeletonHelper.MediaSkeletonType.SERIES
            else PrimeSkeletonHelper.MediaSkeletonType.MOVIE
        )

        if (item.posterUrl.isNotEmpty()) {
            Glide.with(ctx)
                .load(item.posterUrl)
                .placeholder(skeletonDrawable)
                .error(skeletonDrawable)
                .centerCrop()
                .into(holder.binding.ivPoster)
        } else {
            holder.binding.ivPoster.setImageDrawable(skeletonDrawable)
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