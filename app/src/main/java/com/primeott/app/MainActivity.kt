package com.primeott.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.ImageViewCompat
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.ActivityMainBinding
import com.primeott.app.presentation.livetv.LiveTvFragment
import com.primeott.app.presentation.sections.*
import com.primeott.app.presentation.watch.WatchRoomActivity
import com.primeott.app.presentation.watch.WatchTogetherFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var activeTag: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        OttRepository.initFavorites(this)

        // الدخول مباشرة إلى صفحة HOME عند فتح التطبيق
        if (savedInstanceState == null) {
            switchToTab("HOME")
        }

        binding.navTabHome.setOnClickListener { switchToTab("HOME") }
        binding.navTabMovies.setOnClickListener { switchToTab("MOVIES") }
        binding.navTabSeries.setOnClickListener { switchToTab("SERIES") }
        binding.navTabLive.setOnClickListener { switchToTab("LIVE") }
        binding.navTabMatches.setOnClickListener { switchToTab("MATCHES") }
        binding.navTabWatch.setOnClickListener { switchToTab("WATCH") }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeTag != "HOME") {
                    switchToTab("HOME")
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

    private fun switchToTab(tag: String) {
        if (activeTag == tag && supportFragmentManager.findFragmentByTag(tag) != null) return

        val tx = supportFragmentManager.beginTransaction()
        tx.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)

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

        tx.commitAllowingStateLoss()
        activeTag = tag
        updateBottomBarIcons(tag)
    }

    private fun updateBottomBarIcons(tag: String) {
        val activeTint = ColorStateList.valueOf(Color.parseColor("#E50914"))
        val inactiveTint = ColorStateList.valueOf(Color.parseColor("#C4C7CE"))

        ImageViewCompat.setImageTintList(binding.ivNavHome, if (tag == "HOME") activeTint else inactiveTint)
        ImageViewCompat.setImageTintList(binding.ivNavMovies, if (tag == "MOVIES") activeTint else inactiveTint)
        ImageViewCompat.setImageTintList(binding.ivNavSeries, if (tag == "SERIES") activeTint else inactiveTint)
        ImageViewCompat.setImageTintList(binding.ivNavMatches, if (tag == "MATCHES") activeTint else inactiveTint)
        ImageViewCompat.setImageTintList(binding.ivNavProfile, if (tag == "WATCH") activeTint else inactiveTint)

        binding.liveTvCircleBg.alpha = if (tag == "LIVE") 1.0f else 0.65f
    }
}