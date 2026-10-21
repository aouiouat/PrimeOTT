package com.primeott.app

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.ImageViewCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.ActivityMainBinding
import com.primeott.app.presentation.livetv.LiveTvFragment
import com.primeott.app.presentation.sections.*
import com.primeott.app.presentation.watch.WatchRoomActivity
import com.primeott.app.presentation.watch.WatchTogetherFragment
import kotlin.math.abs
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var activeTag: String = ""
    private val tabOrder = listOf("HOME", "MOVIES", "SERIES", "LIVE", "MATCHES", "WATCH")
    private var circleSlideAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                putExtra(WatchRoomActivity.EXTRA_ROOM_CODE, roomCode)
            })
        }
    }

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
                "MATCHES" -> ScheduleFragment()
                "WATCH" -> WatchTogetherFragment()
                else -> HomeFragment()
            }
            tx.add(R.id.fragmentContainer, target, tag)
        } else {
            tx.show(target)
        }

        tx.commitNowAllowingStateLoss()

        if (animate) {
            target.view?.let { pageView ->
                val slidePx = (14f * resources.displayMetrics.density) * if (movingForward) 1f else -1f
                pageView.animate().cancel()
                pageView.alpha = 0.88f
                pageView.translationX = slidePx
                pageView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()
            }
        }

        activeTag = tag
        updateBottomBarIcons(tag, animate, tabDistance)
    }

    private fun updateBottomBarIcons(tag: String, animate: Boolean, tabDistance: Int) {
        val activeTint = ColorStateList.valueOf(Color.parseColor("#FF1E27"))
        val inactiveTint = ColorStateList.valueOf(Color.parseColor("#E2E6F0"))

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
            if (isSelected) {
                selectedTabView = tabContainer
                if (animate) {
                    iconView.scaleX = 0.92f
                    iconView.scaleY = 0.92f
                    iconView.translationY = 0f
                    iconView.animate()
                        .scaleX(1.13f)
                        .scaleY(1.13f)
                        .translationY(-1.5f * resources.displayMetrics.density)
                        .setDuration(250)
                        .setInterpolator(OvershootInterpolator(1.6f))
                        .start()
                } else {
                    iconView.scaleX = 1.13f
                    iconView.scaleY = 1.13f
                    iconView.translationY = -1.5f * resources.displayMetrics.density
                }
            } else {
                if (animate) {
                    iconView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationY(0f)
                        .setDuration(180)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                } else {
                    iconView.scaleX = 1.0f
                    iconView.scaleY = 1.0f
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
                val maxLiftPx = -4.5f * density
                val maxStretchX = (1.16f + (tabDistance * 0.05f)).coerceAtMost(1.38f)
                // Ki yroh men a9sa yamin l a9sa yasar yroh rapide b slide chaba
                val animDuration = if (tabDistance >= 3) 215L else 255L

                circleSlideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = animDuration
                    interpolator = FastOutSlowInInterpolator()
                    addUpdateListener { va ->
                        val f = va.animatedFraction
                        // Slide X
                        circle.translationX = startX + (destX - startX) * f
                        // Arc Lift Y (yethaz fel wasat apres yetsagam ki yawsal)
                        val wave = sin(f * Math.PI).toFloat()
                        circle.translationY = maxLiftPx * wave
                        // Liquid Stretch X & Squash Y
                        circle.scaleX = 1f + (maxStretchX - 1f) * wave
                        circle.scaleY = 1f - (0.08f * wave)
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