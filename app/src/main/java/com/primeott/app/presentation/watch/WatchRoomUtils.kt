package com.primeott.app.presentation.watch

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max

object WatchRoomUtils {
    val posterBitmapCache = object : LruCache<String, android.graphics.Bitmap>(60) {}

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun dpF(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    fun makeRoundedBg(
        context: Context,
        fillColor: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 1f
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, radiusDp)
            setColor(fillColor)
            if (strokeColor != null) {
                setStroke(max(1, dpF(context, strokeWidthDp).toInt()), strokeColor)
            }
        }
    }

    fun formatTimeMs(ms: Long): String {
        val totalSecs = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    fun loadPosterIntoImageView(context: Context, httpClient: OkHttpClient, scope: CoroutineScope, url: String, imageView: ImageView) {
        imageView.tag = url
        if (url.isBlank()) {
            imageView.setImageDrawable(null)
            imageView.setBackgroundColor(Color.parseColor("#1D2230"))
            return
        }
        val cached = posterBitmapCache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        imageView.setImageDrawable(null)
        imageView.setBackgroundColor(Color.parseColor("#1D2230"))
        scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                posterBitmapCache.put(url, bmp)
                                withContext(Dispatchers.Main) {
                                    if (imageView.tag == url) {
                                        imageView.setImageBitmap(bmp)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    fun cleanAssAndHtmlTags(raw: String): String {
        return raw
            .replace(Regex("""\{[^}]*\}"""), "")
            .replace(Regex("""[0-9a-zA-Z]*&H[0-9A-Fa-f]+&\\?[a-zA-Z0-9\\&]*\}?"""), "")
            .replace(Regex("""<[^>]*>"""), "")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .trim()
    }
}