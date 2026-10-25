package com.primeott.app.presentation.sections

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min

object PrimeSkeletonHelper {

    enum class MediaSkeletonType {
        MOVIE, SERIES, CAST
    }

    /**
     * كوفر وهمي سينمائي فخم (Netflix / Reyzo Dark Matte Style)
     * يظهر فوراً في مكان الصورة مع شعار حرف R المدمج برمز Play في الوسط،
     * حتى تتحمل صورة كل فيلم وتظهر بالواحد بـ CrossFade سلس.
     */
    fun createCardPlaceholderDrawable(
        context: Context,
        type: MediaSkeletonType = MediaSkeletonType.MOVIE
    ): Drawable {
        val d = context.resources.displayMetrics.density
        return object : Drawable() {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                style = Paint.Style.FILL
            }
            private val subtleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#26FFFFFF")
                style = Paint.Style.STROKE
                strokeWidth = 0.9f * d
            }
            private val rGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#353944")
                style = Paint.Style.FILL
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            private val playCutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#20232B")
                style = Paint.Style.FILL
            }
            private val castIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#383D49")
                style = Paint.Style.STROKE
                strokeWidth = 2.2f * d
                strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: Canvas) {
                val b = bounds
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                if (w <= 0f || h <= 0f) return

                val cx = w / 2f
                val cy = h / 2f

                bgPaint.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(
                        Color.parseColor("#242730"),
                        Color.parseColor("#1D2028"),
                        Color.parseColor("#171920")
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )

                if (type == MediaSkeletonType.CAST) {
                    val r = (min(w, h) / 2f) - (1.5f * d)
                    canvas.drawCircle(cx, cy, r, bgPaint)
                    canvas.drawCircle(cx, cy, r, subtleBorderPaint)
                    val headR = r * 0.26f
                    canvas.drawCircle(cx, cy - r * 0.14f, headR, castIconPaint)
                    val arcRect = RectF(cx - r * 0.44f, cy + r * 0.18f, cx + r * 0.44f, cy + r * 0.82f)
                    canvas.drawArc(arcRect, 195f, 150f, false, castIconPaint)
                    return
                }

                val corner = 14f * d
                val cardRect = RectF(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
                canvas.drawRoundRect(cardRect, corner, corner, bgPaint)
                canvas.drawRoundRect(cardRect, corner, corner, subtleBorderPaint)

                // رسم حرف R الفخم في الوسط مع مثلث Play صغير داخله مثل المنصات العالمية
                val glyphSize = min(w, h) * 0.46f
                rGlyphPaint.textSize = glyphSize
                val textY = cy - ((rGlyphPaint.descent() + rGlyphPaint.ascent()) / 2f)
                canvas.drawText("R", cx, textY, rGlyphPaint)

                val triW = glyphSize * 0.16f
                val triH = glyphSize * 0.18f
                val triX = cx - glyphSize * 0.03f
                val triY = cy - glyphSize * 0.13f
                val playPath = Path().apply {
                    moveTo(triX - triW * 0.4f, triY - triH * 0.5f)
                    lineTo(triX + triW * 0.6f, triY)
                    lineTo(triX - triW * 0.4f, triY + triH * 0.5f)
                    close()
                }
                canvas.drawPath(playPath, playCutoutPaint)
            }

            override fun setAlpha(alpha: Int) {
                bgPaint.alpha = alpha
                rGlyphPaint.alpha = (alpha * 0.25f).toInt().coerceIn(30, 95)
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                bgPaint.colorFilter = colorFilter
                rGlyphPaint.colorFilter = colorFilter
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    private fun startSmoothShimmerPulse(view: View, position: Int) {
        view.alpha = 0f
        view.translationY = 12f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240L)
            .setStartDelay((position % 9) * 45L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                ObjectAnimator.ofFloat(view, "alpha", 1f, 0.52f, 1f).apply {
                    duration = 1450L
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
            .start()
    }

    class VodSkeletonAdapter(
        private val itemCountToShow: Int = 9,
        private val isGrid: Boolean = false,
        private val isSeries: Boolean = false
    ) : RecyclerView.Adapter<VodSkeletonAdapter.VH>() {

        class VH(val root: View) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val d = ctx.resources.displayMetrics.density

            val wrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                layoutParams = if (isGrid) {
                    ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        val m = (5.5f * d).toInt()
                        setMargins(m, m, m, (12 * d).toInt())
                    }
                } else {
                    ViewGroup.MarginLayoutParams(
                        (124 * d).toInt(),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        val mH = (5.5f * d).toInt()
                        val mV = (4 * d).toInt()
                        setMargins(mH, mV, mH, mV)
                    }
                }
            }

            val posterBox = View(ctx).apply {
                background = createCardPlaceholderDrawable(
                    ctx,
                    if (isSeries) MediaSkeletonType.SERIES else MediaSkeletonType.MOVIE
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (178 * d).toInt()
                )
            }

            val titleBar = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 5f * d
                    setColor(Color.parseColor("#232732"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    (88 * d).toInt(),
                    (11 * d).toInt()
                ).apply {
                    topMargin = (8 * d).toInt()
                    marginStart = (4 * d).toInt()
                }
            }

            wrap.addView(posterBox)
            wrap.addView(titleBar)
            return VH(wrap)
        }

        override fun getItemCount(): Int = itemCountToShow

        override fun onBindViewHolder(holder: VH, position: Int) {
            startSmoothShimmerPulse(holder.root, position)
        }
    }

    class CastSkeletonAdapter(private val count: Int = 6) : RecyclerView.Adapter<CastSkeletonAdapter.VH>() {

        class VH(val root: View) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val d = ctx.resources.displayMetrics.density

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = ViewGroup.MarginLayoutParams(
                    (96 * d).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (12 * d).toInt() }
            }

            val avatarCircle = View(ctx).apply {
                background = createCardPlaceholderDrawable(ctx, MediaSkeletonType.CAST)
                layoutParams = LinearLayout.LayoutParams((78 * d).toInt(), (78 * d).toInt())
            }

            val nameLine = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 5f * d
                    setColor(Color.parseColor("#232732"))
                }
                layoutParams = LinearLayout.LayoutParams((68 * d).toInt(), (10 * d).toInt()).apply {
                    topMargin = (8 * d).toInt()
                }
            }

            val roleLine = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4f * d
                    setColor(Color.parseColor("#191C24"))
                }
                layoutParams = LinearLayout.LayoutParams((46 * d).toInt(), (8 * d).toInt()).apply {
                    topMargin = (5 * d).toInt()
                }
            }

            root.addView(avatarCircle)
            root.addView(nameLine)
            root.addView(roleLine)
            return VH(root)
        }

        override fun getItemCount(): Int = count

        override fun onBindViewHolder(holder: VH, position: Int) {
            startSmoothShimmerPulse(holder.root, position)
        }
    }
}