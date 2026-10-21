package com.primeott.app.presentation.sections

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
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

        binding.rvHomeChannels.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvHomeSeries.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentWatched.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        val parentLinear = binding.rvHomeSeries.parent as? LinearLayout
        // تغيير عنوان القسم الثاني في XML ليكون Popular Movies مباشرة تحت Trending Today
        val secondHeaderLayout = parentLinear?.getChildAt(parentLinear.indexOfChild(binding.rvHomeSeries) - 1) as? LinearLayout
        (secondHeaderLayout?.getChildAt(0) as? TextView)?.text = "Popular Movies"

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

        binding.heroViewPager.apply {
            clipToPadding = false
            clipChildren = false
            offscreenPageLimit = 3
            (getChildAt(0) as? RecyclerView)?.overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            val transformer = CompositePageTransformer().apply {
                addTransformer(MarginPageTransformer(20))
                addTransformer { page, position ->
                    val r = 1 - abs(position)
                    page.scaleY = 0.84f + r * 0.16f
                    page.alpha = 0.55f + r * 0.45f
                }
            }
            setPageTransformer(transformer)
        }

        loadRecentHistorySection(binding)

        binding.btnClearHistory.setOnClickListener {
            SessionManager.clearAllWatchHistory(requireContext())
            loadRecentHistorySection(binding)
        }

        binding.btnHomeSearch.setOnClickListener {
            showFullTmdbSearchDialog()
        }

        binding.btnSeeAllMovies.setOnClickListener {
            showSeeAllInfiniteDialog("Trending Today", false, "")
        }

        binding.btnSeeAllSeries.setOnClickListener {
            showSeeAllInfiniteDialog("Popular Movies", false, "")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val sections = OttRepository.fetchHomeSections()

            heroItems = sections.heroItems
            if (heroItems.isNotEmpty()) {
                binding.heroViewPager.adapter = HeroBannerAdapter(heroItems) { vod ->
                    openVodDetail(vod)
                }
                startHeroAutoSlider(binding)
            }

            // 1. القسم الأول: Trending Today
            binding.rvHomeChannels.adapter = VodGridAdapter(sections.trendingToday) { vod ->
                openVodDetail(vod)
            }

            // 2. القسم الثاني: Popular Movies
            binding.rvHomeSeries.adapter = VodGridAdapter(sections.popularMovies) { vod ->
                openVodDetail(vod)
            }

            // 3. باقي الأقسام بالترتيب المطلوب بالضبط
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
                val currentIdx = binding.heroViewPager.currentItem.coerceIn(0, heroItems.lastIndex)
                openVodDetail(heroItems[currentIdx])
            }
        }

        binding.btnHeroFavorite.setOnClickListener {
            if (heroItems.isNotEmpty()) {
                val currentIdx = binding.heroViewPager.currentItem.coerceIn(0, heroItems.lastIndex)
                val vod = heroItems[currentIdx]
                OttRepository.toggleFavorite(vod.id, requireContext())
                val isFav = OttRepository.isChannelFavorite(vod.id)
                binding.btnHeroFavorite.text = if (isFav) "♥" else "♡"
                binding.btnHeroFavorite.setTextColor(if (isFav) Color.parseColor("#E50914") else Color.WHITE)
            }
        }
    }

    private fun addHomeRowSection(title: String, items: List<VodItem>, isSeries: Boolean, filterCode: String) {
        if (items.isEmpty()) return
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        val container = extraSectionsContainer ?: return

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (18 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }

        val tvTitle = TextView(ctx).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvSeeAll = TextView(ctx).apply {
            text = "See All"
            setTextColor(Color.parseColor("#E50914"))
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (205 * d).toInt())
            adapter = VodGridAdapter(items) { vod -> openVodDetail(vod) }
        }
        container.addView(rv)
    }

    private fun showSeeAllInfiniteDialog(sectionTitle: String, isSeries: Boolean, filterCode: String) {
        val ctx = requireContext()
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#08080C"))
            setPadding((12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt(), 0)
        }

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * d).toInt(), 0, (6 * d).toInt(), (12 * d).toInt())
        }

        val btnBack = TextView(ctx).apply {
            text = "← Back"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding((12 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            setOnClickListener { dialog.dismiss() }
        }

        val tvHeader = TextView(ctx).apply {
            text = sectionTitle
            setTextColor(Color.WHITE)
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val gridAdapter = VodGridAdapter(allItems) { vod ->
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
        dialog.show()
    }

    private fun startHeroAutoSlider(binding: FragmentHomeBinding) {
        autoSlideJob?.cancel()
        autoSlideJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(5000L)
                val count = heroItems.size
                if (count > 1) {
                    val next = (binding.heroViewPager.currentItem + 1) % count
                    binding.heroViewPager.setCurrentItem(next, true)
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

    private fun loadRecentHistorySection(binding: FragmentHomeBinding) {
        val historyList = SessionManager.getWatchHistoryList(requireContext())
        if (historyList.isEmpty()) {
            binding.recentHeaderRow.visibility = View.GONE
            binding.rvRecentWatched.visibility = View.GONE
            return
        }
        binding.recentHeaderRow.visibility = View.VISIBLE
        binding.rvRecentWatched.visibility = View.VISIBLE

        binding.rvRecentWatched.adapter = RecentLandscapeAdapter(historyList) { entry ->
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
    }

    private fun showFullTmdbSearchDialog() {
        val ctx = requireContext()
        val et = EditText(ctx).apply {
            hint = "Search among 1,000,000+ Movies & Series..."
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(ctx)
            .setTitle("🔍 Global Movie & Series Search")
            .setView(et)
            .setPositiveButton("Search") { _, _ ->
                val q = et.text.toString().trim()
                if (q.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val results = OttRepository.searchTmdbCatalog(q)
                        if (results.isNotEmpty()) {
                            val names = results.map { "${it.title} (${it.year}) ★ ${it.rating}" }.toTypedArray()
                            AlertDialog.Builder(ctx)
                                .setTitle("Results for: $q")
                                .setItems(names) { _, which ->
                                    openVodDetail(results[which])
                                }
                                .show()
                        } else {
                            Toast.makeText(ctx, "No result found for: $q", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        _binding?.let { loadRecentHistorySection(it) }
    }

    override fun onDestroyView() {
        autoSlideJob?.cancel()
        extraSectionsContainer = null
        _binding = null
        super.onDestroyView()
    }
}

/**
 * بطاقات Recent الأفقية (Landscape 16:9) مع شريط التقدم الأحمر في الأسفل والدقيقة التي توقف عندها المشاهد
 */
class RecentLandscapeAdapter(
    private val items: List<WatchHistoryEntry>,
    private val onClick: (WatchHistoryEntry) -> Unit
) : RecyclerView.Adapter<RecentLandscapeAdapter.VH>() {

    class VH(
        val root: LinearLayout,
        val ivThumb: ImageView,
        val tvResumeBadge: TextView,
        val progressFillView: View,
        val tvTitle: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val cardWidthPx = (168 * d).toInt()
        val cardHeightPx = (96 * d).toInt()

        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.MarginLayoutParams(cardWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (10 * d).toInt()
            }
        }

        val card = CardView(ctx).apply {
            radius = 12f * d
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

        // شارة الدقيقة التي توقف عندها المستخدم (مثلاً ▶ 56m)
        val tvBadge = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#CC08080C"))
            setPadding((7 * d).toInt(), (3 * d).toInt(), (7 * d).toInt(), (3 * d).toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                setMargins((6 * d).toInt(), 0, 0, (8 * d).toInt())
            }
        }

        // الخلفية الرمادية لشريط التقدم أسفل البطاقة
        val progressTrack = FrameLayout(ctx).apply {
            setBackgroundColor(Color.parseColor("#66FFFFFF"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (4 * d).toInt(),
                Gravity.BOTTOM
            )
        }

        // الخط الأحمر الذي يوضح مقدار التقدم في الفيلم
        val progressFill = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#E50914"))
            layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        }
        progressTrack.addView(progressFill)

        frame.addView(iv)
        frame.addView(tvBadge)
        frame.addView(progressTrack)
        card.addView(frame)

        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), 0)
        }

        wrap.addView(card)
        wrap.addView(tv)
        return VH(wrap, iv, tvBadge, progressFill, tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val d = holder.itemView.context.resources.displayMetrics.density
        val cardWidthPx = (168 * d).toInt()

        holder.tvTitle.text = item.title
        val thumbUrl = item.backdropUrl.ifEmpty { item.posterUrl }
        if (thumbUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context).load(thumbUrl).centerCrop().into(holder.ivThumb)
        }

        val posMs = item.positionMs
        val durMs = item.durationMs
        if (posMs > 5000L && durMs > 10000L) {
            val ratio = (posMs.toFloat() / durMs.toFloat()).coerceIn(0.06f, 1.0f)
            holder.progressFillView.layoutParams = FrameLayout.LayoutParams(
                (cardWidthPx * ratio).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
            val minsWatched = (posMs / 60000L).toInt()
            val secsWatched = ((posMs % 60000L) / 1000L).toInt()
            holder.tvResumeBadge.visibility = View.VISIBLE
            holder.tvResumeBadge.text = if (minsWatched > 0) {
                "▶ ${minsWatched}m ${secsWatched}s"
            } else {
                "▶ ${secsWatched}s"
            }
        } else {
            holder.progressFillView.layoutParams = FrameLayout.LayoutParams(
                (cardWidthPx * 0.08f).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
            holder.tvResumeBadge.visibility = View.GONE
        }

        holder.root.setOnClickListener { onClick(item) }
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

        val card = CardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                val marginH = (6 * density).toInt()
                setMargins(marginH, 0, marginH, 0)
            }
            radius = 20f * density
            cardElevation = 8f * density
            setCardBackgroundColor(Color.parseColor("#12131A"))
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
                (130 * density).toInt(),
                Gravity.BOTTOM
            )
            setBackgroundColor(Color.parseColor("#CC08080C"))
        }

        val tvRating = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (28 * density).toInt(),
                Gravity.TOP or Gravity.START
            ).apply {
                val m = (14 * density).toInt()
                setMargins(m, m, m, m)
            }
            setBackgroundResource(R.drawable.bg_search_box_dark)
            gravity = Gravity.CENTER
            val padH = (10 * density).toInt()
            setPadding(padH, 0, padH, 0)
            setTextColor(Color.parseColor("#FFC107"))
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
        }

        val tvYear = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (28 * density).toInt(),
                Gravity.TOP or Gravity.END
            ).apply {
                val m = (14 * density).toInt()
                setMargins(m, m, m, m)
            }
            setBackgroundResource(R.drawable.bg_search_box_dark)
            gravity = Gravity.CENTER
            val padH = (10 * density).toInt()
            setPadding(padH, 0, padH, 0)
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
        }

        val tvTitle = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                val mH = (16 * density).toInt()
                val mB = (18 * density).toInt()
                setMargins(mH, 0, mH, mB)
            }
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            maxLines = 1
            setShadowLayer(8f, 0f, 3f, Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
        }

        container.addView(ivPoster)
        container.addView(bottomOverlay)
        container.addView(tvRating)
        container.addView(tvYear)
        container.addView(tvTitle)
        card.addView(container)

        return VH(card, ivPoster, tvRating, tvYear, tvTitle)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvHeroTitle.text = item.title
        holder.tvHeroRating.text = "★ ${item.rating}"
        holder.tvHeroYear.text = item.year
        if (item.posterUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(item.posterUrl)
                .centerCrop()
                .into(holder.ivHeroPoster)
        }
        holder.rootCard.setOnClickListener { onClick(item) }
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_vod_grid, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentVodGridBinding.bind(view)
        val isSeries = arguments?.getBoolean("IS_SERIES") ?: false

        val gridLayoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvVodGrid.layoutManager = gridLayoutManager
        binding.tvSectionHeader.text = if (isSeries) "TV Series" else "Movies"

        val gridAdapter = VodGridAdapter(catalogList) { vod ->
            val savedPos = SessionManager.getSavedPositionForVod(requireContext(), vod.id)
            SessionManager.saveWatchProgress(requireContext(), vod, savedPos, 0L)
            startActivity(Intent(requireContext(), ContentDetailActivity::class.java).apply {
                putExtra(ContentDetailActivity.EXTRA_VOD, vod.copy(savedPositionMs = savedPos))
            })
        }
        binding.rvVodGrid.adapter = gridAdapter

        fun loadNextCatalogPage() {
            if (isLoadingMore) return
            isLoadingMore = true
            viewLifecycleOwner.lifecycleScope.launch {
                val pageItems = OttRepository.fetchVodCatalogPage(isSeries, currentPage)
                if (pageItems.isNotEmpty()) {
                    val start = catalogList.size
                    catalogList.addAll(pageItems)
                    gridAdapter.notifyItemRangeInserted(start, pageItems.size)
                    currentPage++
                }
                isLoadingMore = false
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
            loadNextCatalogPage()
        }
    }
}

class VodGridAdapter(
    private val items: List<VodItem>,
    private val onClick: (VodItem) -> Unit
) : RecyclerView.Adapter<VodGridAdapter.VH>() {

    class VH(val binding: ItemVodCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemVodCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvVodTitle.text = item.title
        holder.binding.tvVodMeta.text = "★ ${item.rating}"
        if (item.posterUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context).load(item.posterUrl).centerCrop().into(holder.binding.ivPoster)
        }
        holder.binding.root.setOnClickListener { onClick(item) }
    }
}

class ScheduleFragment : Fragment() {

    private var allMatches: List<MatchFixture> = emptyList()
    private var selectedDayTab: String = "TODAY"
    private var selectedLeague: String = "ALL"
    private var searchQuery: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_match_center, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentMatchCenterBinding.bind(view)

        binding.rvMatchesList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLeagueFilters.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, true)

        val dayTabs = listOf(
            binding.tabYesterday to "YESTERDAY",
            binding.tabToday to "TODAY",
            binding.tabTomorrow to "TOMORROW",
            binding.tabLiveNow to "LIVE",
            binding.tabFavMatches to "FAV"
        )

        dayTabs.forEach { (tv, code) ->
            tv.setOnClickListener {
                selectedDayTab = code
                dayTabs.forEach { (t, c) ->
                    t.setBackgroundColor(if (c == selectedDayTab) Color.parseColor("#E50914") else Color.TRANSPARENT)
                }
                applyFilters(binding)
            }
        }

        binding.etSearchMatch.doAfterTextChanged { text ->
            searchQuery = text?.toString()?.trim().orEmpty()
            applyFilters(binding)
        }

        binding.btnRefreshMatches.setOnClickListener { loadMatchesData(binding) }
        loadMatchesData(binding)
    }

    private fun loadMatchesData(binding: FragmentMatchCenterBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            allMatches = OttRepository.fetchMatchesFromCloudflare()
            val timeNow = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            binding.tvLastSync.text = "Updated: $timeNow"

            val distinctLeagues = allMatches.map { it.league }.distinct()
            val leagueCategories = mutableListOf(Category("ALL", "All (${allMatches.size})"))
            distinctLeagues.forEach { l -> leagueCategories.add(Category(l, l)) }

            binding.rvLeagueFilters.adapter = CategoriesAdapter(leagueCategories, selectedLeague) { cat ->
                selectedLeague = cat.id
                applyFilters(binding)
            }
            applyFilters(binding)
        }
    }

    private fun applyFilters(binding: FragmentMatchCenterBinding) {
        val filtered = allMatches.filter { m ->
            val matchDay = when (selectedDayTab) {
                "LIVE" -> m.status == "LIVE"
                "FAV" -> m.isFavoriteMatch
                else -> m.dayTab == selectedDayTab
            }
            val matchLeague = (selectedLeague == "ALL" || m.league == selectedLeague)
            val matchSearch = searchQuery.isEmpty() || m.homeTeam.contains(searchQuery, true) || m.awayTeam.contains(searchQuery, true)
            matchDay && matchLeague && matchSearch
        }

        binding.rvMatchesList.adapter = MatchesAdapter(
            matches = filtered,
            onWatchClick = { match ->
                startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_CHANNEL, match.channel)
                })
            },
            onCardDetailsClick = { match ->
                val options = arrayOf("▶ Watch Match", "👥 Watch Together")
                AlertDialog.Builder(requireContext())
                    .setTitle("${match.homeTeam} VS ${match.awayTeam}")
                    .setItems(options) { _, which ->
                        if (which == 0) {
                            startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                                putExtra(PlayerActivity.EXTRA_CHANNEL, match.channel)
                            })
                        } else {
                            viewLifecycleOwner.lifecycleScope.launch {
                                val (ok, code) = OttRepository.createWatchRoom(
                                    deviceId = SessionManager.getDeviceId(requireContext()),
                                    userName = SessionManager.getUserName(requireContext()),
                                    roomName = "${match.homeTeam} VS ${match.awayTeam}",
                                    roomIcon = "⚽",
                                    isPrivate = false,
                                    password = "",
                                    allowGuestsControl = false,
                                    contentType = "LIVE",
                                    streamId = match.channel.id,
                                    contentTitle = "${match.homeTeam} VS ${match.awayTeam}",
                                    contentPoster = "",
                                    streamUrl = match.channel.streamUrls.firstOrNull() ?: ""
                                )
                                if (ok) {
                                    startActivity(Intent(requireContext(), WatchRoomActivity::class.java).apply {
                                        putExtra(WatchRoomActivity.EXTRA_ROOM_CODE, code)
                                    })
                                }
                            }
                        }
                    }
                    .show()
            },
            onFavClick = { match ->
                OttRepository.toggleFavoriteMatch(match.id)
                applyFilters(binding)
            },
            onNotifyClick = {
                Toast.makeText(requireContext(), "🔔 Reminder set!", Toast.LENGTH_SHORT).show()
            }
        )
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
            Toast.makeText(requireContext(), "🎙️ Microphone Ready!", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(ctx, "🎙️ Microphone Ready!", Toast.LENGTH_SHORT).show()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}