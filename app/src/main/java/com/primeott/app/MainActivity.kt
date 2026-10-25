package com.primeott.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.media3.common.util.UnstableApi
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.ActivityMainBinding
import com.primeott.app.presentation.auth.LoginActivity
import com.primeott.app.presentation.livetv.LiveTvFragment
import com.primeott.app.presentation.sections.HomeFragment
import com.primeott.app.presentation.sections.VodFragment
import com.primeott.app.presentation.watch.WatchRoomActivity
import com.primeott.app.presentation.watch.WatchTogetherFragment
import com.primeott.app.presentation.watch.WatchTogetherTabFragment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var activeTag: String = ""
    private val tabOrder = listOf("HOME", "MOVIES", "SERIES", "LIVE", "MATCHES", "WATCH")
    private var circleSlideAnimator: ValueAnimator? = null
    private var ambientColorAnimator: ValueAnimator? = null

    private lateinit var ambientBgDrawable: FlixVisionStandardBgDrawable
    private lateinit var bottomBarGlassDrawable: BottomBarHarmonyGlassDrawable
    private lateinit var activePillGlowDrawable: ActiveNavPillGlowDrawable

    private var lastHomeHeroColor: Int = Color.parseColor("#1E3A5F")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val d = resources.displayMetrics.density
        ambientBgDrawable = FlixVisionStandardBgDrawable()
        bottomBarGlassDrawable = BottomBarHarmonyGlassDrawable(d)
        activePillGlowDrawable = ActiveNavPillGlowDrawable(d)

        // تطبيق الخلفية السينمائية المتفاعلة (Aurora Ambient Background)
        binding.root.background = ambientBgDrawable

        // رفع الـ Bottom Bar للأعلى قليلاً مع تصميم زجاجي متناسق مع الخلفية
        binding.floatingBottomBar.background = bottomBarGlassDrawable
        binding.liveTvCircleBg.background = activePillGlowDrawable
        binding.floatingBottomBar.post {
            (binding.floatingBottomBar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = (22 * d).toInt()
                lp.leftMargin = (18 * d).toInt()
                lp.rightMargin = (18 * d).toInt()
                binding.floatingBottomBar.layoutParams = lp
            }
            binding.floatingBottomBar.elevation = 24f * d
        }

        // BEGIN_ROYAL_AUTH_HOOK
        val royalAuthPrefs = getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)
        val loggedUser = royalAuthPrefs.getString("current_user", null)
        if (!royalAuthPrefs.getBoolean("session_active", false) || loggedUser.isNullOrEmpty()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        // END_ROYAL_AUTH_HOOK

        OttRepository.initFavorites(this)

        if (savedInstanceState == null) {
            switchToTab("HOME", animate = false)
        }

        binding.navTabHome.setOnClickListener { switchToTab("HOME", animate = true) }
        binding.navTabMovies.setOnClickListener { switchToTab("MOVIES", animate = true) }
        binding.navTabSeries.setOnClickListener { switchToTab("SERIES", animate = true) }
        binding.navTabLive.setOnClickListener { switchToTab("LIVE", animate = true) }
        binding.navTabMatches.setOnClickListener { switchToTab("MATCHES", animate = true) }
        binding.navTabWatch.setOnClickListener { switchToTab("WATCH", animate = true) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeTag != "HOME") {
                    switchToTab("HOME", animate = true)
                } else {
                    finish()
                }
            }
        })

        handleIncomingDeepLink(intent)
    }

    /**
     * تحديث لون الإضاءة الخلفية والـ Bottom Bar تلقائياً مع تغير بوستر الـ Banner في Home
     */
    fun updateAmbientColorFromHero(extractedColor: Int) {
        lastHomeHeroColor = extractedColor
        if (activeTag == "HOME") {
            animateAmbientThemeTo(extractedColor)
        }
    }

    private fun animateAmbientThemeTo(targetColor: Int) {
        ambientColorAnimator?.cancel()
        val startColor = ambientBgDrawable.currentAccentColor
        val evaluator = ArgbEvaluator()
        ambientColorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { va ->
                val blended = evaluator.evaluate(va.animatedFraction, startColor, targetColor) as Int
                ambientBgDrawable.updateAccentColor(blended)
                bottomBarGlassDrawable.updateAccentTint(blended)
            }
            start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingDeepLink(intent)
    }

    private fun handleIncomingDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        val codeFromQuery = data.getQueryParameter("code")
        val codeFromPath = if (data.path?.startsWith("/join/") == true) {
            data.path?.substringAfter("/join/")?.trim()
        } else null
        val roomCode = (codeFromQuery ?: codeFromPath)?.uppercase() ?: return
        if (roomCode.isNotEmpty()) {
            startActivity(Intent(this, WatchRoomActivity::class.java).apply {
                putExtra("room_code", roomCode)
            })
        }
    }

    @OptIn(UnstableApi::class)
    private fun switchToTab(tag: String, animate: Boolean = true) {
        if (activeTag == tag && supportFragmentManager.findFragmentByTag(tag) != null) return

        val oldIndex = tabOrder.indexOf(activeTag)
        val newIndex = tabOrder.indexOf(tag)
        val movingForward = oldIndex == -1 || newIndex >= oldIndex
        val tabDistance = if (oldIndex == -1) 1 else abs(newIndex - oldIndex).coerceAtLeast(1)

        val tx = supportFragmentManager.beginTransaction()

        supportFragmentManager.fragments.forEach { frag ->
            if (frag.isVisible) tx.hide(frag)
        }

        var target = supportFragmentManager.findFragmentByTag(tag)
        if (target == null) {
            target = when (tag) {
                "HOME" -> HomeFragment()
                "MOVIES" -> VodFragment.newInstance(false)
                "SERIES" -> VodFragment.newInstance(true)
                "LIVE" -> LiveTvFragment()
                "MATCHES" -> WatchTogetherTabFragment()
                "WATCH" -> WatchTogetherFragment()
                else -> HomeFragment()
            }
            tx.add(R.id.fragmentContainer, target, tag)
        } else {
            tx.show(target)
        }

        tx.commitNowAllowingStateLoss()

        // أنيميشن سينمائية فخمة عند التنقل بين الصفحات (Slide + Scale + Elevation Fade)
        if (animate) {
            target.view?.let { pageView ->
                val density = resources.displayMetrics.density
                val slidePx = (36f * density) * if (movingForward) 1f else -1f
                pageView.animate().cancel()
                pageView.alpha = 0f
                pageView.translationX = slidePx
                pageView.translationY = 10f * density
                pageView.scaleX = 0.94f
                pageView.scaleY = 0.94f
                pageView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(310L)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
            }
        }

        activeTag = tag

        // تغيير الإضاءة المحيطية حسب الصفحة المختارة
        val tabThemeColor = when (tag) {
            "HOME" -> lastHomeHeroColor
            "MOVIES" -> Color.parseColor("#3B1525")
            "SERIES" -> Color.parseColor("#182B49")
            "LIVE" -> Color.parseColor("#12353C")
            "MATCHES" -> Color.parseColor("#251B42")
            "WATCH" -> Color.parseColor("#2E1D24")
            else -> lastHomeHeroColor
        }
        animateAmbientThemeTo(tabThemeColor)

        updateBottomBarIcons(tag, animate, tabDistance)
    }

    private fun updateBottomBarIcons(tag: String, animate: Boolean, tabDistance: Int) {
        val activeTint = ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
        val inactiveTint = ColorStateList.valueOf(Color.parseColor("#94A3B8"))

        val tabs = listOf(
            Triple("HOME", binding.navTabHome, binding.ivNavHome),
            Triple("MOVIES", binding.navTabMovies, binding.ivNavMovies),
            Triple("SERIES", binding.navTabSeries, binding.ivNavSeries),
            Triple("LIVE", binding.navTabLive, binding.ivNavLive),
            Triple("MATCHES", binding.navTabMatches, binding.ivNavMatches),
            Triple("WATCH", binding.navTabWatch, binding.ivNavProfile)
        )

        var selectedTabView: View = binding.navTabHome
        val density = resources.displayMetrics.density

        tabs.forEach { (tabTag, tabContainer, iconView) ->
            val isSelected = (tabTag == tag)
            iconView.isSelected = isSelected
            ImageViewCompat.setImageTintList(iconView, if (isSelected) activeTint else inactiveTint)

            iconView.animate().cancel()
            if (isSelected) {
                selectedTabView = tabContainer
                if (animate) {
                    iconView.scaleX = 0.78f
                    iconView.scaleY = 0.78f
                    iconView.rotation = -8f
                    iconView.animate()
                        .scaleX(1.16f)
                        .scaleY(1.16f)
                        .rotation(0f)
                        .translationY(-2f * density)
                        .setDuration(320L)
                        .setInterpolator(OvershootInterpolator(2.1f))
                        .start()
                } else {
                    iconView.scaleX = 1.16f
                    iconView.scaleY = 1.16f
                    iconView.rotation = 0f
                    iconView.translationY = -2f * density
                }
            } else {
                if (animate) {
                    iconView.animate()
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .rotation(0f)
                        .translationY(0f)
                        .setDuration(220L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                } else {
                    iconView.scaleX = 0.96f
                    iconView.scaleY = 0.96f
                    iconView.rotation = 0f
                    iconView.translationY = 0f
                }
            }
        }

        moveActiveCircleToTab(selectedTabView, animate, tabDistance)
    }

    private fun moveActiveCircleToTab(targetTab: View, animate: Boolean, tabDistance: Int) {
        val circle = binding.liveTvCircleBg
        val row = binding.navTabsRow
        val density = resources.displayMetrics.density

        val applyPosition = {
            val destX = (row.left + targetTab.left - circle.left) +
                    (targetTab.width - circle.width) / 2f
            circleSlideAnimator?.cancel()

            if (animate) {
                val startX = circle.translationX
                val maxLiftPx = -5.5f * density
                val maxStretchX = (1.22f + (tabDistance * 0.06f)).coerceAtMost(1.48f)
                val animDuration = (240L + tabDistance * 20L).coerceAtMost(320L)

                circleSlideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = animDuration
                    interpolator = FastOutSlowInInterpolator()
                    addUpdateListener { va ->
                        val f = va.animatedFraction
                        circle.translationX = startX + (destX - startX) * f
                        val wave = sin(f * Math.PI).toFloat()
                        circle.translationY = maxLiftPx * wave
                        circle.scaleX = 1f + (maxStretchX - 1f) * wave
                        circle.scaleY = 1f - (0.12f * wave)
                    }
                    start()
                }
            } else {
                circle.translationX = destX
                circle.translationY = 0f
                circle.scaleX = 1.0f
                circle.scaleY = 1.0f
            }
        }

        if (targetTab.width == 0 || circle.width == 0) {
            targetTab.post { applyPosition() }
        } else {
            applyPosition()
        }
    }
}

/**
 * خلفية التطبيق الفخمة (Cinema Aurora Ambient Background)
 * تتفاعل مع لون الفيلم المعروض في الـ Banner وتعطي عمقاً سينمائياً رائعاً
 */
class FlixVisionStandardBgDrawable : Drawable() {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val topAuroraPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val sideCrimsonGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val bottomVignettePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    var currentAccentColor: Int = Color.parseColor("#213B5C")
        private set

    fun updateAccentColor(color: Int) {
        currentAccentColor = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat().coerceAtLeast(1f)
        val h = b.height().toFloat().coerceAtLeast(1f)

        // 1. التدرج الأساسي العميق (Deep Obsidian Midnight)
        val deepTop = ColorUtils.blendARGB(Color.parseColor("#0A0F18"), currentAccentColor, 0.24f)
        val deepMid = ColorUtils.blendARGB(Color.parseColor("#06080E"), currentAccentColor, 0.10f)
        basePaint.shader = LinearGradient(
            0f, 0f, w * 0.85f, h,
            intArrayOf(
                deepTop,
                deepMid,
                Color.parseColor("#04060A"),
                Color.parseColor("#010204")
            ),
            floatArrayOf(0f, 0.38f, 0.74f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, basePaint)

        // 2. هالة إضاءة علوية خلف الـ Hero Banner تتلون بلون بوستر الفيلم
        val topRadius = max(w, h) * 0.68f
        val glowCore = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(currentAccentColor, Color.WHITE, 0.12f),
            125
        )
        val glowMid = ColorUtils.setAlphaComponent(currentAccentColor, 55)
        topAuroraPaint.shader = RadialGradient(
            w * 0.50f,
            h * 0.18f,
            topRadius,
            intArrayOf(glowCore, glowMid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, topAuroraPaint)

        // 3. لمسة إضاءة جانبية خفيفة تزيد الفخامة
        val secondaryRadius = max(w, h) * 0.55f
        val crimsonHint = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(currentAccentColor, Color.parseColor("#E50914"), 0.35f),
            42
        )
        sideCrimsonGlowPaint.shader = RadialGradient(
            w * 0.12f,
            h * 0.06f,
            secondaryRadius,
            intArrayOf(crimsonHint, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, sideCrimsonGlowPaint)

        // 4. تظليل سفلي ناعم يبرز الـ Bottom Bar العائم
        bottomVignettePaint.shader = LinearGradient(
            0f, h * 0.72f, 0f, h,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#B8020306")),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.72f, w, h, bottomVignettePaint)
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        topAuroraPaint.alpha = alpha
        sideCrimsonGlowPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        topAuroraPaint.colorFilter = colorFilter
        sideCrimsonGlowPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * خلفية الـ Bottom Bar الزجاجية المتناسقة مع لون الخلفية
 */
class BottomBarHarmonyGlassDrawable(private val density: Float) : Drawable() {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val topGlossPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * density
    }
    private val rectF = RectF()
    private val glossRectF = RectF()
    private var accentTint: Int = Color.parseColor("#213B5C")

    fun updateAccentTint(color: Int) {
        accentTint = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat().coerceAtLeast(1f)
        val h = b.height().toFloat().coerceAtLeast(1f)
        val radius = h / 2f
        val inset = strokePaint.strokeWidth / 2f
        rectF.set(inset, inset, w - inset, h - inset)

        val topGlass = ColorUtils.blendARGB(Color.parseColor("#F0131926"), accentTint, 0.22f)
        val bottomGlass = Color.parseColor("#F8090C14")

        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(topGlass, bottomGlass),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rectF, radius, radius, bgPaint)

        // لمعة علوية زجاجية (Specular Highlight)
        glossRectF.set(inset + 2f * density, inset + 1f * density, w - inset - 2f * density, h * 0.48f)
        topGlossPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.48f,
            intArrayOf(Color.parseColor("#24FFFFFF"), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(glossRectF, radius, radius, topGlossPaint)

        // إطار متدرج يدمج الإضاءة المحيطية مع اللمسة الحمراء الفخمة
        val borderAccent = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(accentTint, Color.parseColor("#FF2A36"), 0.45f),
            135
        )
        strokePaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                Color.parseColor("#55FFFFFF"),
                borderAccent,
                Color.parseColor("#24FFFFFF")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rectF, radius, radius, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * الكبسولة المضيئة للأيقونة المفعلة في الـ Bottom Bar
 */
class ActiveNavPillGlowDrawable(private val density: Float) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val rectF = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat().coerceAtLeast(1f)
        val h = b.height().toFloat().coerceAtLeast(1f)
        val radius = 20f * density
        val inset = strokePaint.strokeWidth / 2f
        rectF.set(inset, inset, w - inset, h - inset)

        fillPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                Color.parseColor("#D9FF1E27"),
                Color.parseColor("#B8C80812"),
                Color.parseColor("#997A040A")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rectF, radius, radius, fillPaint)

        strokePaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#CCFF8A8F"), Color.parseColor("#40FF1E27")),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rectF, radius, radius, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}