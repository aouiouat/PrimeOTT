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

    private var lastHomeHeroColor: Int = Color.parseColor("#1B2838")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val d = resources.displayMetrics.density
        ambientBgDrawable = FlixVisionStandardBgDrawable()
        bottomBarGlassDrawable = BottomBarHarmonyGlassDrawable(d)
        activePillGlowDrawable = ActiveNavPillGlowDrawable(d)

        binding.root.background = ambientBgDrawable

        binding.floatingBottomBar.background = bottomBarGlassDrawable
        binding.liveTvCircleBg.background = activePillGlowDrawable
        binding.floatingBottomBar.post {
            (binding.floatingBottomBar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = (18 * d).toInt()
                lp.leftMargin = (16 * d).toInt()
                lp.rightMargin = (16 * d).toInt()
                binding.floatingBottomBar.layoutParams = lp
            }
            binding.floatingBottomBar.elevation = 22f * d
        }

        val royalAuthPrefs = getSharedPreferences("royal_auth_prefs", Context.MODE_PRIVATE)
        val loggedUser = royalAuthPrefs.getString("current_user", null)
        if (!royalAuthPrefs.getBoolean("session_active", false) || loggedUser.isNullOrEmpty()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

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

        // تهدئة سرعة الانتقال بين الصفحات (360ms بدلاً من 190ms) مع تلاشي وانسياب هادئ باش ما ترمشش الشاشة وتحس بالسلاسة
        if (animate) {
            target.view?.let { pageView ->
                val density = resources.displayMetrics.density
                val gentleSlidePx = (14f * density) * if (movingForward) 1f else -1f
                pageView.animate().cancel()
                pageView.scaleX = 0.992f
                pageView.scaleY = 0.992f
                pageView.translationY = 0f
                pageView.alpha = 0f
                pageView.translationX = gentleSlidePx
                pageView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(360L)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
            }
        }

        activeTag = tag

        val tabThemeColor = if (tag == "HOME") lastHomeHeroColor else Color.parseColor("#182230")
        animateAmbientThemeTo(tabThemeColor)

        updateBottomBarIcons(tag, animate, tabDistance)
    }

    private fun updateBottomBarIcons(tag: String, animate: Boolean, tabDistance: Int) {
        val activeTint = ColorStateList.valueOf(Color.parseColor("#FF1E27"))
        val inactiveTint = ColorStateList.valueOf(Color.parseColor("#D0D7E2"))

        val tabs = listOf(
            Triple("HOME", binding.navTabHome, binding.ivNavHome),
            Triple("MOVIES", binding.navTabMovies, binding.ivNavMovies),
            Triple("SERIES", binding.navTabSeries, binding.ivNavSeries),
            Triple("LIVE", binding.navTabLive, binding.ivNavLive),
            Triple("MATCHES", binding.navTabMatches, binding.ivNavMatches),
            Triple("WATCH", binding.navTabWatch, binding.ivNavProfile)
        )

        var selectedTabView: View = binding.navTabHome

        tabs.forEach { (tabTag, tabContainer, iconView) ->
            val isSelected = (tabTag == tag)
            iconView.isSelected = isSelected
            ImageViewCompat.setImageTintList(iconView, if (isSelected) activeTint else inactiveTint)

            iconView.animate().cancel()
            iconView.rotation = 0f
            iconView.translationY = 0f
            if (isSelected) {
                selectedTabView = tabContainer
                if (animate) {
                    iconView.scaleX = 0.90f
                    iconView.scaleY = 0.90f
                    iconView.animate()
                        .scaleX(1.10f)
                        .scaleY(1.10f)
                        .setDuration(310L)
                        .setInterpolator(OvershootInterpolator(1.2f))
                        .start()
                } else {
                    iconView.scaleX = 1.10f
                    iconView.scaleY = 1.10f
                }
            } else {
                if (animate) {
                    iconView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(260L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                } else {
                    iconView.scaleX = 1.0f
                    iconView.scaleY = 1.0f
                }
            }
        }

        moveActiveCircleToTab(selectedTabView, animate, tabDistance)
    }

    private fun moveActiveCircleToTab(targetTab: View, animate: Boolean, tabDistance: Int) {
        val circle = binding.liveTvCircleBg
        val row = binding.navTabsRow

        val applyPosition = {
            val destX = (row.left + targetTab.left - circle.left) +
                    (targetTab.width - circle.width) / 2f
            circleSlideAnimator?.cancel()

            if (animate) {
                val startX = circle.translationX
                val maxStretchX = (1.10f + (tabDistance * 0.025f)).coerceAtMost(1.20f)
                val animDuration = (310L + tabDistance * 20L).coerceAtMost(380L)

                circleSlideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = animDuration
                    interpolator = FastOutSlowInInterpolator()
                    addUpdateListener { va ->
                        val f = va.animatedFraction
                        circle.translationX = startX + (destX - startX) * f
                        circle.translationY = 0f
                        val wave = sin(f * Math.PI).toFloat()
                        circle.scaleX = 1f + (maxStretchX - 1f) * wave
                        circle.scaleY = 1f - (0.04f * wave)
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

class FlixVisionStandardBgDrawable : Drawable() {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val topAuroraPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val bottomVignettePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    var currentAccentColor: Int = Color.parseColor("#1B2838")
        private set

    fun updateAccentColor(color: Int) {
        currentAccentColor = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat().coerceAtLeast(1f)
        val h = b.height().toFloat().coerceAtLeast(1f)

        val deepTop = ColorUtils.blendARGB(Color.parseColor("#0B0F17"), currentAccentColor, 0.18f)
        val deepMid = ColorUtils.blendARGB(Color.parseColor("#06080D"), currentAccentColor, 0.08f)
        basePaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                deepTop,
                deepMid,
                Color.parseColor("#040508"),
                Color.parseColor("#000000")
            ),
            floatArrayOf(0f, 0.36f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, basePaint)

        val topRadius = max(w, h) * 0.62f
        val glowCore = ColorUtils.setAlphaComponent(currentAccentColor, 85)
        val glowMid = ColorUtils.setAlphaComponent(currentAccentColor, 32)
        topAuroraPaint.shader = RadialGradient(
            w * 0.50f,
            h * 0.16f,
            topRadius,
            intArrayOf(glowCore, glowMid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, topAuroraPaint)

        bottomVignettePaint.shader = LinearGradient(
            0f, h * 0.75f, 0f, h,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#CC000000")),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.75f, w, h, bottomVignettePaint)
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        topAuroraPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        topAuroraPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

class BottomBarHarmonyGlassDrawable(private val density: Float) : Drawable() {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val topGlossPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
    }
    private val rectF = RectF()
    private val glossRectF = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat().coerceAtLeast(1f)
        val h = b.height().toFloat().coerceAtLeast(1f)
        val radius = h / 2f
        val inset = strokePaint.strokeWidth / 2f
        rectF.set(inset, inset, w - inset, h - inset)

        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.parseColor("#F2151922"),
                Color.parseColor("#F60E1118"),
                Color.parseColor("#FA090B10")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rectF, radius, radius, bgPaint)

        glossRectF.set(inset + 2f * density, inset + 1f * density, w - inset - 2f * density, h * 0.45f)
        topGlossPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.45f,
            intArrayOf(Color.parseColor("#18FFFFFF"), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(glossRectF, radius, radius, topGlossPaint)

        strokePaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.parseColor("#38FFFFFF"),
                Color.parseColor("#1AFFFFFF")
            ),
            floatArrayOf(0f, 1f),
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

class ActiveNavPillGlowDrawable(private val density: Float) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.15f * density
    }
    private val rectF = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat().coerceAtLeast(1f)
        val h = b.height().toFloat().coerceAtLeast(1f)
        val radius = h / 2f
        val inset = strokePaint.strokeWidth / 2f
        rectF.set(inset, inset, w - inset, h - inset)

        fillPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                Color.parseColor("#42E50914"),
                Color.parseColor("#2499060E"),
                Color.parseColor("#CC10131B")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rectF, radius, radius, fillPaint)

        strokePaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#B3FF2A36"), Color.parseColor("#38FF1E27")),
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