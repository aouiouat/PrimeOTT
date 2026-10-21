package com.primeott.app.presentation.common

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import java.util.Locale

object AppStrings {
    fun get(context: Context, ar: String, fr: String, en: String): String {
        return when (SessionManager.getLanguage(context)) {
            "fr" -> fr
            "en" -> en
            else -> ar
        }
    }
}

object LocaleHelper {
    fun applyLocale(context: Context): Context {
        val lang = SessionManager.getLanguage(context)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    fun applyLayoutDirection(activity: Activity) {
        val lang = SessionManager.getLanguage(activity)
        activity.window.decorView.layoutDirection =
            if (lang == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }
}

object AnimUtils {
    private fun isAnimationEnabled(context: Context): Boolean {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
        return scale > 0f
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachPressBounce(view: View) {
        if (!isAnimationEnabled(view.context)) return
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(130)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
            }
            false
        }
    }

    fun animateViewEntry(view: View) {
        if (!isAnimationEnabled(view.context)) return
        view.alpha = 0f
        view.translationY = 20f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun applyActivityTransition(activity: Activity) {
        if (!isAnimationEnabled(activity)) return
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}
