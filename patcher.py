import os, re

def patch_file(path, replacements):
    if not os.path.exists(path):
        print(f"⚠️ File not found: {path}")
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    for old, new in replacements:
        if old in content:
            content = content.replace(old, new, 1)
        else:
            print(f"ℹ️ Pattern already updated or skipped in {os.path.basename(path)}")
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"✅ Patched successfully: {path}")

# 1. Patch view_movie_inline_player.xml
xml_player_path = "app/src/main/res/layout/view_movie_inline_player.xml"
patch_file(xml_player_path, [
    (
        'android:textSize="13.5sp" />',
        'android:textSize="11.5sp" />'
    ),
    (
        '''            <TextView
                android:id="@+id/btnTopServerOnly"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="8dp"
                android:fontFamily="@font/product_sans_bold"
                android:paddingHorizontal="10dp"
                android:paddingVertical="5dp"
                android:text="Server"
                android:textColor="#FFFFFF"
                android:textSize="11.5sp"
                android:visibility="visible" />

            <TextView
                android:id="@+id/btnTopQualityOnly"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="8dp"
                android:fontFamily="@font/product_sans_bold"
                android:paddingHorizontal="10dp"
                android:paddingVertical="5dp"
                android:text="Auto"
                android:textColor="#FFFFFF"
                android:textSize="11.5sp"
                android:visibility="gone" />''',
        '''            <TextView
                android:id="@+id/btnTopServerOnly"
                android:layout_width="wrap_content"
                android:layout_height="28dp"
                android:layout_marginEnd="6dp"
                android:fontFamily="@font/product_sans_bold"
                android:gravity="center"
                android:drawablePadding="4dp"
                android:paddingHorizontal="8dp"
                android:text="SRV"
                android:textColor="#FFFFFF"
                android:textSize="10sp"
                android:visibility="visible" />

            <TextView
                android:id="@+id/btnTopQualityOnly"
                android:layout_width="wrap_content"
                android:layout_height="28dp"
                android:layout_marginEnd="6dp"
                android:fontFamily="@font/product_sans_bold"
                android:gravity="center"
                android:drawablePadding="4dp"
                android:paddingHorizontal="8dp"
                android:text="FHD"
                android:textColor="#FFFFFF"
                android:textSize="10sp"
                android:visibility="visible" />'''
    ),
    (
        '        <!-- الشريط السفلي (SeekBar + أزرار Fullscreen) -->',
        '''        <!-- شريط الإضاءة الثابت على اليسار في المشغل الكبير -->
        <LinearLayout
            android:id="@+id/fullscreenLeftBrightnessBar"
            android:layout_width="34dp"
            android:layout_height="135dp"
            android:layout_gravity="start|center_vertical"
            android:layout_marginStart="14dp"
            android:gravity="center"
            android:orientation="vertical"
            android:paddingVertical="8dp"
            android:visibility="gone">

            <ImageView
                android:id="@+id/ivSideBrightnessIcon"
                android:layout_width="16dp"
                android:layout_height="16dp"
                android:layout_marginBottom="6dp" />

            <ProgressBar
                android:id="@+id/pbSideBrightness"
                style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="75dp"
                android:layout_height="4dp"
                android:layout_weight="1"
                android:max="100"
                android:progress="60"
                android:progressBackgroundTint="#38FFFFFF"
                android:progressTint="#FFFFFF"
                android:rotation="270" />

            <TextView
                android:id="@+id/tvSideBrightnessPct"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:fontFamily="@font/product_sans_bold"
                android:text="60%"
                android:textColor="#FFFFFF"
                android:textSize="9.5sp" />
        </LinearLayout>

        <!-- شريط الصوت الثابت على اليمين في المشغل الكبير -->
        <LinearLayout
            android:id="@+id/fullscreenRightVolumeBar"
            android:layout_width="34dp"
            android:layout_height="135dp"
            android:layout_gravity="end|center_vertical"
            android:layout_marginEnd="14dp"
            android:gravity="center"
            android:orientation="vertical"
            android:paddingVertical="8dp"
            android:visibility="gone">

            <ImageView
                android:id="@+id/ivSideVolumeIcon"
                android:layout_width="16dp"
                android:layout_height="16dp"
                android:layout_marginBottom="6dp" />

            <ProgressBar
                android:id="@+id/pbSideVolume"
                style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="75dp"
                android:layout_height="4dp"
                android:layout_weight="1"
                android:max="100"
                android:progress="70"
                android:progressBackgroundTint="#38FFFFFF"
                android:progressTint="#FFFFFF"
                android:rotation="270" />

            <TextView
                android:id="@+id/tvSideVolumePct"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:fontFamily="@font/product_sans_bold"
                android:text="70%"
                android:textColor="#FFFFFF"
                android:textSize="9.5sp" />
        </LinearLayout>

        <!-- الشريط السفلي (SeekBar + أزرار Fullscreen) -->'''
    )
])

# 2. Patch ContentDetailActivity.kt
detail_kt_path = "app/src/main/java/com/primeott/app/presentation/detail/ContentDetailActivity.kt"
patch_file(detail_kt_path, [
    (
        '''    private fun activateInlinePlayerSmoothly() {
        isPlayerModeActive = true
        fullScreenAmbientDrawable.setPlayerModeActive(true)

        btnPlayNowMain.visibility = View.GONE
        vHeroTopSpacer.visibility = View.GONE

        heroTopActionsRow.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction { heroTopActionsRow.visibility = View.GONE }
            .start()

        heroBackdropFixedContainer.animate()
            .alpha(0f)
            .setDuration(220L)
            .withEndAction { heroBackdropFixedContainer.visibility = View.GONE }
            .start()

        val pContainer = moviePlayerController.playerContainer
        pContainer.alpha = 1f
        pContainer.translationY = 0f
        pContainer.visibility = View.VISIBLE
        moviePlayerController.showControlsWithFade()

        detailsScrollView.smoothScrollTo(0, 0)

        if (resolvedDirectStreams.isNotEmpty()) {
            moviePlayerController.setLoadingState(true)
            playerEngine.playSources(
                moviePlayerController.playerView,
                resolvedDirectStreams.toList(),
                0,
                pendingResumePositionMs
            )
        } else {
            moviePlayerController.setLoadingState(true)
            val vod = currentVod ?: return
            if (providerTargets.isEmpty()) {
                startStreamExtractionForEpisodeOrMovie(vod, selectedSeason, selectedEpisode, runWebViewNow = true)
            } else {
                loadProviderInHeadlessSniffer(currentProviderIndex)
            }
        }
    }''',
        '''    private fun activateInlinePlayerSmoothly() {
        val alreadyPrepared = playerEngine.player != null && resolvedDirectStreams.isNotEmpty()
        isPlayerModeActive = true
        fullScreenAmbientDrawable.setPlayerModeActive(true)

        btnPlayNowMain.visibility = View.GONE
        vHeroTopSpacer.visibility = View.GONE
        heroTopActionsRow.visibility = View.GONE
        heroBackdropFixedContainer.visibility = View.GONE

        val pContainer = moviePlayerController.playerContainer
        pContainer.alpha = 1f
        pContainer.translationY = 0f
        pContainer.visibility = View.VISIBLE
        detailsScrollView.smoothScrollTo(0, 0)

        if (alreadyPrepared && moviePlayerController.playerView.player != null) {
            moviePlayerController.setLoadingState(false)
            playerEngine.resume()
            moviePlayerController.updatePlayPauseVectorIcon(true)
            moviePlayerController.showControlsWithFade()
        } else if (resolvedDirectStreams.isNotEmpty()) {
            moviePlayerController.setLoadingState(true)
            playerEngine.playSources(
                moviePlayerController.playerView,
                resolvedDirectStreams.toList(),
                0,
                pendingResumePositionMs
            )
            playerEngine.resume()
        } else {
            moviePlayerController.setLoadingState(true)
            val vod = currentVod ?: return
            if (providerTargets.isEmpty()) {
                startStreamExtractionForEpisodeOrMovie(vod, selectedSeason, selectedEpisode, runWebViewNow = true)
            } else {
                loadProviderInHeadlessSniffer(currentProviderIndex)
            }
        }
    }'''
    ),
    (
        '''    fun createSortBarsIcon(context: Context, tintColor: Int): Drawable {''',
        '''    fun createSmallServerStackIcon(context: Context, tintColor: Int): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (13 * d).toInt()
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = 1.4f * d
            }
            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                canvas.drawRoundRect(RectF(w * 0.12f, h * 0.16f, w * 0.88f, h * 0.45f), w * 0.1f, w * 0.1f, paint)
                canvas.drawRoundRect(RectF(w * 0.12f, h * 0.55f, w * 0.88f, h * 0.84f), w * 0.1f, w * 0.1f, paint)
            }
            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createSmallQualitySparkIcon(context: Context, tintColor: Int): Drawable {
        val d = context.resources.displayMetrics.density
        val sizePx = (13 * d).toInt()
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * d
                strokeCap = Paint.Cap.ROUND
            }
            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                canvas.drawLine(w * 0.15f, h * 0.30f, w * 0.85f, h * 0.30f, paint)
                canvas.drawLine(w * 0.15f, h * 0.70f, w * 0.85f, h * 0.70f, paint)
                canvas.drawCircle(w * 0.38f, h * 0.30f, w * 0.12f, paint)
                canvas.drawCircle(w * 0.65f, h * 0.70f, w * 0.12f, paint)
            }
            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun createSortBarsIcon(context: Context, tintColor: Int): Drawable {'''
    )
])

# 3. Patch MovieInlinePlayerController.kt
ctrl_kt_path = "app/src/main/java/com/primeott/app/presentation/detail/MovieInlinePlayerController.kt"
patch_file(ctrl_kt_path, [
    (
        '''    private lateinit var gestureIndicatorCard: LinearLayout''',
        '''    private lateinit var gestureIndicatorCard: LinearLayout
    private lateinit var fullscreenLeftBrightnessBar: LinearLayout
    private lateinit var pbSideBrightness: ProgressBar
    private lateinit var tvSideBrightnessPct: TextView
    private lateinit var fullscreenRightVolumeBar: LinearLayout
    private lateinit var pbSideVolume: ProgressBar
    private lateinit var tvSideVolumePct: TextView'''
    ),
    (
        '''        btnTopServerOnly.background = makeRoundedBg("#2EFFFFFF".toColorInt(), 8f, "#55FFFFFF".toColorInt(), 0.9f)
        btnTopQualityOnly.background = makeRoundedBg("#2EFFFFFF".toColorInt(), 8f, "#55FFFFFF".toColorInt(), 0.9f)''',
        '''        fullscreenLeftBrightnessBar = rootPlayerFrame.findViewById(R.id.fullscreenLeftBrightnessBar)
        pbSideBrightness = rootPlayerFrame.findViewById(R.id.pbSideBrightness)
        tvSideBrightnessPct = rootPlayerFrame.findViewById(R.id.tvSideBrightnessPct)
        fullscreenRightVolumeBar = rootPlayerFrame.findViewById(R.id.fullscreenRightVolumeBar)
        pbSideVolume = rootPlayerFrame.findViewById(R.id.pbSideVolume)
        tvSideVolumePct = rootPlayerFrame.findViewById(R.id.tvSideVolumePct)

        fullscreenLeftBrightnessBar.background = makeRoundedBg("#B310131C".toColorInt(), 18f, "#33FFFFFF".toColorInt(), 1f)
        fullscreenRightVolumeBar.background = makeRoundedBg("#B310131C".toColorInt(), 18f, "#33FFFFFF".toColorInt(), 1f)
        rootPlayerFrame.findViewById<ImageView>(R.id.ivSideBrightnessIcon)
            .setImageDrawable(VectorIcons.createBrightnessSunIcon(activity, Color.WHITE))
        rootPlayerFrame.findViewById<ImageView>(R.id.ivSideVolumeIcon)
            .setImageDrawable(VectorIcons.createAudioSpeakerIcon(activity, Color.WHITE))

        btnTopServerOnly.background = makeRoundedBg("#2EFFFFFF".toColorInt(), 8f, "#55FFFFFF".toColorInt(), 0.9f)
        btnTopQualityOnly.background = makeRoundedBg("#2EFFFFFF".toColorInt(), 8f, "#55FFFFFF".toColorInt(), 0.9f)
        val srvIcon = VectorIcons.createSmallServerStackIcon(activity, Color.WHITE).apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
        }
        val qIcon = VectorIcons.createSmallQualitySparkIcon(activity, Color.WHITE).apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
        }
        btnTopServerOnly.setCompoundDrawables(srvIcon, null, null, null)
        btnTopQualityOnly.setCompoundDrawables(qIcon, null, null, null)'''
    ),
    (
        '''            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isControlsLocked) {
                    val width = playerView.width.coerceAtLeast(1)
                    if (e.x < width * 0.45f) {
                        resetAutoHide()
                        pulseCircleFeedback(btnRewind15BgCircle)
                        seekByDeltaMs(engine, -15_000L)
                    } else if (e.x > width * 0.55f) {
                        resetAutoHide()
                        pulseCircleFeedback(btnForward15BgCircle)
                        seekByDeltaMs(engine, 15_000L)
                    }
                }
                return true
            }''',
        '''            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isControlsLocked) {
                    val width = playerContainer.width.coerceAtLeast(1)
                    if (e.x < width * 0.48f) {
                        resetAutoHide()
                        pulseCircleFeedback(btnRewind15BgCircle)
                        seekByDeltaMs(engine, -15_000L)
                    } else {
                        resetAutoHide()
                        pulseCircleFeedback(btnForward15BgCircle)
                        seekByDeltaMs(engine, 15_000L)
                    }
                }
                return true
            }'''
    ),
    (
        '''        pbGestureLevel.progress = clamped
        tvGesturePercent.text = "$clamped%"''',
        '''        pbGestureLevel.progress = clamped
        tvGesturePercent.text = "$clamped%"
        if (isBrightness) {
            pbSideBrightness.progress = clamped
            tvSideBrightnessPct.text = "$clamped%"
        } else {
            pbSideVolume.progress = clamped
            tvSideVolumePct.text = "$clamped%"
        }'''
    ),
    (
        '''    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            controlsOverlay.visibility = View.GONE
            btnSkipIntro.visibility = View.GONE
            holdSpeed2xPill.visibility = View.GONE
            gestureIndicatorCard.visibility = View.GONE
            animateSubtitlePositionForControls(controlsVisible = false, animate = false)
        } else {
            showControlsWithFade()
        }
    }''',
        '''    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            controlsOverlay.visibility = View.GONE
            btnSkipIntro.visibility = View.GONE
            holdSpeed2xPill.visibility = View.GONE
            gestureIndicatorCard.visibility = View.GONE
            tvSubtitleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            tvSubtitleOverlay.setPadding(dp(6), dp(1), dp(6), dp(1))
            (tvSubtitleOverlay.layoutParams as? FrameLayout.LayoutParams)?.apply {
                setMargins(dp(6), 0, dp(6), dp(4))
                tvSubtitleOverlay.layoutParams = this
            }
            animateSubtitlePositionForControls(controlsVisible = false, animate = false)
        } else {
            tvSubtitleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp)
            tvSubtitleOverlay.setPadding(dp(12), dp(4), dp(12), dp(4))
            (tvSubtitleOverlay.layoutParams as? FrameLayout.LayoutParams)?.apply {
                setMargins(dp(20), 0, dp(20), dp(14))
                tvSubtitleOverlay.layoutParams = this
            }
            showControlsWithFade()
        }
    }'''
    ),
    (
        '''        if (enterFullscreen) {
            btnMinimizePlayer.visibility = View.VISIBLE
            btnTopServerOnly.visibility = View.VISIBLE
            btnTopQualityOnly.visibility = View.VISIBLE
            btnTopLock.visibility = View.VISIBLE
            fullscreenBottomActionsRow.visibility = View.VISIBLE
            vBottomPlayerSeparator.visibility = View.GONE''',
        '''        if (enterFullscreen) {
            btnMinimizePlayer.visibility = View.VISIBLE
            btnTopServerOnly.visibility = View.VISIBLE
            btnTopQualityOnly.visibility = View.VISIBLE
            btnTopLock.visibility = View.VISIBLE
            fullscreenBottomActionsRow.visibility = View.VISIBLE
            fullscreenLeftBrightnessBar.visibility = View.VISIBLE
            fullscreenRightVolumeBar.visibility = View.VISIBLE
            vBottomPlayerSeparator.visibility = View.GONE'''
    ),
    (
        '''            btnMinimizePlayer.visibility = View.GONE
            btnTopServerOnly.visibility = View.VISIBLE
            btnTopQualityOnly.visibility = View.GONE
            btnTopLock.visibility = View.GONE
            fullscreenBottomActionsRow.visibility = View.GONE
            vBottomPlayerSeparator.visibility = View.VISIBLE''',
        '''            btnMinimizePlayer.visibility = View.GONE
            btnTopServerOnly.visibility = View.VISIBLE
            btnTopQualityOnly.visibility = View.VISIBLE
            btnTopLock.visibility = View.GONE
            fullscreenBottomActionsRow.visibility = View.GONE
            fullscreenLeftBrightnessBar.visibility = View.GONE
            fullscreenRightVolumeBar.visibility = View.GONE
            vBottomPlayerSeparator.visibility = View.VISIBLE'''
    ),
    (
        '''        btnSubPosDown.setOnClickListener {
            subtitleExtraLiftDp = (subtitleExtraLiftDp - 6).coerceIn(-10, 90)
            tvPositionStatus.text = String.format(Locale.US, "Position: %+ddp", subtitleExtraLiftDp)
            saveAndApplySubtitleStyle()
        }
        btnSubPosReset.setOnClickListener {
            subtitleExtraLiftDp = 0
            tvPositionStatus.text = "Position: +0dp"
            saveAndApplySubtitleStyle()
        }
        btnSubPosUp.setOnClickListener {
            subtitleExtraLiftDp = (subtitleExtraLiftDp + 6).coerceIn(-10, 90)
            tvPositionStatus.text = String.format(Locale.US, "Position: %+ddp", subtitleExtraLiftDp)
            saveAndApplySubtitleStyle()
        }''',
        '''        fun flashTap(v: TextView, isWhiteRest: Boolean = false, action: () -> Unit) {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(65L).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(95L).start()
            }.start()
            v.background = makeRoundedBg(Color.WHITE, 8f)
            v.setTextColor("#0E1015".toColorInt())
            v.postDelayed({
                v.background = makeRoundedBg(if (isWhiteRest) Color.WHITE else "#24FFFFFF".toColorInt(), 8f)
                v.setTextColor(if (isWhiteRest) "#0E1015".toColorInt() else Color.WHITE)
            }, 130L)
            action()
        }

        btnSubPosDown.setOnClickListener {
            flashTap(btnSubPosDown) {
                subtitleExtraLiftDp = (subtitleExtraLiftDp - 6).coerceIn(-10, 90)
                tvPositionStatus.text = String.format(Locale.US, "Position: %+ddp", subtitleExtraLiftDp)
                saveAndApplySubtitleStyle()
            }
        }
        btnSubPosReset.setOnClickListener {
            flashTap(btnSubPosReset, isWhiteRest = true) {
                subtitleExtraLiftDp = 0
                tvPositionStatus.text = "Position: +0dp"
                saveAndApplySubtitleStyle()
            }
        }
        btnSubPosUp.setOnClickListener {
            flashTap(btnSubPosUp) {
                subtitleExtraLiftDp = (subtitleExtraLiftDp + 6).coerceIn(-10, 90)
                tvPositionStatus.text = String.format(Locale.US, "Position: %+ddp", subtitleExtraLiftDp)
                saveAndApplySubtitleStyle()
            }
        }'''
    ),
    (
        '''        btnMinusHalf.setOnClickListener {
            subtitleOffsetMs -= 500L
            tvSyncStatus.text = String.format(Locale.US, "Offset: %+.1fs", subtitleOffsetMs / 1000.0)
        }
        btnResetSync.setOnClickListener {
            subtitleOffsetMs = 0L
            tvSyncStatus.text = "Offset: +0.0s"
        }
        btnPlusHalf.setOnClickListener {
            subtitleOffsetMs += 500L
            tvSyncStatus.text = String.format(Locale.US, "Offset: %+.1fs", subtitleOffsetMs / 1000.0)
        }''',
        '''        btnMinusHalf.setOnClickListener {
            flashTap(btnMinusHalf) {
                subtitleOffsetMs -= 500L
                tvSyncStatus.text = String.format(Locale.US, "Offset: %+.1fs", subtitleOffsetMs / 1000.0)
            }
        }
        btnResetSync.setOnClickListener {
            flashTap(btnResetSync, isWhiteRest = true) {
                subtitleOffsetMs = 0L
                tvSyncStatus.text = "Offset: +0.0s"
            }
        }
        btnPlusHalf.setOnClickListener {
            flashTap(btnPlusHalf) {
                subtitleOffsetMs += 500L
                tvSyncStatus.text = String.format(Locale.US, "Offset: %+.1fs", subtitleOffsetMs / 1000.0)
            }
        }'''
    )
])