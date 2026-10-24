package com.primeott.app.presentation.watch

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.domain.model.SubtitleCue
import com.primeott.app.domain.model.SubtitleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

class WatchRoomSubtitleManager(
    private val hostContext: Context,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope
) {
    var availableSubtitles: List<SubtitleTrack> = emptyList()
    var activeSubtitleCues: List<SubtitleCue> = emptyList()
    var selectedSubtitleId: String = ""
    var subtitleOffsetMs: Long = 0L
    var activeMovieTmdbId: String = ""

    private var subtitleTextSizeSp: Float = 16f
    private var subtitleTextColor: Int = Color.WHITE
    private var subtitleBgAlpha: Int = 0
    private var subtitleExtraLiftDp: Int = 0

    lateinit var tvAppSubtitleOverlay: TextView

    init {
        val subPrefs = hostContext.getSharedPreferences("prime_sub_style_prefs", Context.MODE_PRIVATE)
        subtitleTextSizeSp = subPrefs.getFloat("sub_size_sp", 16f).coerceIn(12f, 26f)
        subtitleTextColor = subPrefs.getInt("sub_text_color", Color.WHITE)
        subtitleBgAlpha = subPrefs.getInt("sub_bg_alpha", 0)
        subtitleExtraLiftDp = subPrefs.getInt("sub_extra_lift_dp", 0).coerceIn(-10, 90)
    }

    fun attachSubtitleOverlay(playerFrameContainer: FrameLayout) {
        tvAppSubtitleOverlay = TextView(hostContext).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(
                WatchRoomUtils.dp(hostContext, 12),
                WatchRoomUtils.dp(hostContext, 4),
                WatchRoomUtils.dp(hostContext, 12),
                WatchRoomUtils.dp(hostContext, 4)
            )
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                setMargins(
                    WatchRoomUtils.dp(hostContext, 20),
                    0,
                    WatchRoomUtils.dp(hostContext, 20),
                    WatchRoomUtils.dp(hostContext, 16)
                )
            }
        }
        applySubtitleStyleToOverlay()
        playerFrameContainer.addView(tvAppSubtitleOverlay)
    }

    fun applySubtitleStyleToOverlay() {
        if (!this::tvAppSubtitleOverlay.isInitialized) return
        tvAppSubtitleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp)
        tvAppSubtitleOverlay.setTextColor(subtitleTextColor)
        if (subtitleBgAlpha <= 10) {
            tvAppSubtitleOverlay.background = null
            tvAppSubtitleOverlay.setShadowLayer(12f, 0f, 3.5f, Color.BLACK)
        } else {
            tvAppSubtitleOverlay.setShadowLayer(6f, 0f, 2f, Color.BLACK)
            tvAppSubtitleOverlay.background = WatchRoomUtils.makeRoundedBg(
                hostContext,
                ColorUtils.setAlphaComponent(Color.parseColor("#0B0D13"), subtitleBgAlpha),
                8f
            )
        }
    }

    fun resetSubtitles() {
        if (this::tvAppSubtitleOverlay.isInitialized) {
            tvAppSubtitleOverlay.text = ""
            tvAppSubtitleOverlay.visibility = View.GONE
        }
        activeSubtitleCues = emptyList()
        availableSubtitles = emptyList()
    }

    fun updateSubtitleTick(currentPositionMs: Long, isPlaying: Boolean) {
        if (!this::tvAppSubtitleOverlay.isInitialized) return
        if (selectedSubtitleId != "NONE" && activeSubtitleCues.isNotEmpty() && isPlaying) {
            val syncedPos = (currentPositionMs - subtitleOffsetMs).coerceAtLeast(0L)
            val cue = activeSubtitleCues.firstOrNull { syncedPos in it.startMs..it.endMs }
            if (cue != null) {
                val cleanCueText = WatchRoomUtils.cleanAssAndHtmlTags(cue.text)
                if (tvAppSubtitleOverlay.text.toString() != cleanCueText) {
                    tvAppSubtitleOverlay.text = cleanCueText
                }
                if (!tvAppSubtitleOverlay.isVisible) tvAppSubtitleOverlay.visibility = View.VISIBLE
            } else if (tvAppSubtitleOverlay.isVisible) {
                tvAppSubtitleOverlay.text = ""
                tvAppSubtitleOverlay.visibility = View.GONE
            }
        } else if (selectedSubtitleId == "NONE" && tvAppSubtitleOverlay.isVisible) {
            tvAppSubtitleOverlay.visibility = View.GONE
        }
    }

    fun loadSubtitlesForMovieAutomatically(tmdbId: String) {
        val cleanId = tmdbId.replace("tmdb_movie_", "").replace("tmdb_tv_", "").replace("tmdb_", "").trim()
        if (cleanId.isEmpty() || cleanId == "0") return
        activeMovieTmdbId = cleanId

        scope.launch {
            val subs = withContext(Dispatchers.IO) { fetchSubtitlesDirectFromApi(cleanId) }
            availableSubtitles = subs
            val preferredArabic = subs.firstOrNull { track ->
                track.languageCode.startsWith("ar", ignoreCase = true) ||
                        track.languageLabel.contains("Arab", ignoreCase = true) ||
                        track.languageLabel.contains("عرب", ignoreCase = true)
            } ?: subs.firstOrNull()

            if (preferredArabic != null) {
                val cues = withContext(Dispatchers.IO) { downloadAndParseSubtitleDirect(preferredArabic.url) }
                if (cues.isNotEmpty()) {
                    selectedSubtitleId = preferredArabic.id
                    activeSubtitleCues = cues
                    if (this@WatchRoomSubtitleManager::tvAppSubtitleOverlay.isInitialized) {
                        tvAppSubtitleOverlay.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    fun showSubtitlesDialog(fallbackStreamId: String) {
        val dialog = Dialog(hostContext)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val screenW = hostContext.resources.displayMetrics.widthPixels
        val dialogWidthPx = min((screenW * 0.88f).toInt(), WatchRoomUtils.dp(hostContext, 360))

        val root = LayoutInflater.from(hostContext).inflate(R.layout.dialog_subtitles_sheet, null, false) as LinearLayout
        root.background = WatchRoomUtils.makeRoundedBg(hostContext, Color.parseColor("#F0101218"), 20f, Color.parseColor("#33FFFFFF"), 1f)

        val tabSwitchBox = root.findViewById<LinearLayout>(R.id.tabSwitchBox)
        tabSwitchBox.background = WatchRoomUtils.makeRoundedBg(hostContext, Color.parseColor("#14FFFFFF"), 12f, Color.parseColor("#22FFFFFF"), 1f)

        val tabTracksBtn = root.findViewById<TextView>(R.id.tabTracksBtn)
        val tabSettingsBtn = root.findViewById<TextView>(R.id.tabSettingsBtn)
        val btnClosePopup = root.findViewById<TextView>(R.id.btnClosePopup)
        val tracksPanel = root.findViewById<LinearLayout>(R.id.tracksPanel)
        val settingsPanel = root.findViewById<LinearLayout>(R.id.settingsPanel)
        val btnDisableSubs = root.findViewById<TextView>(R.id.btnDisableSubs)
        val btnUploadSrt = root.findViewById<TextView>(R.id.btnUploadSrt)
        val btnRefreshSubs = root.findViewById<TextView>(R.id.btnRefreshSubs)
        val filterRow = root.findViewById<LinearLayout>(R.id.filterRow)
        val tracksContainer = root.findViewById<LinearLayout>(R.id.tracksContainer)

        tabTracksBtn.background = WatchRoomUtils.makeRoundedBg(hostContext, Color.WHITE, 10f)
        btnClosePopup.background = WatchRoomUtils.makeRoundedBg(hostContext, Color.parseColor("#1FFFFFFF"), 16f)
        btnClosePopup.setOnClickListener { dialog.dismiss() }
        btnUploadSrt.visibility = View.GONE

        val isOff = selectedSubtitleId == "NONE"
        btnDisableSubs.setTextColor(if (isOff) Color.parseColor("#0E1015") else Color.WHITE)
        btnDisableSubs.background = WatchRoomUtils.makeRoundedBg(
            hostContext,
            if (isOff) Color.WHITE else Color.parseColor("#1AFFFFFF"),
            10f,
            Color.parseColor("#33FFFFFF"),
            0.8f
        )
        btnDisableSubs.setOnClickListener {
            selectedSubtitleId = "NONE"
            tvAppSubtitleOverlay.visibility = View.GONE
            dialog.dismiss()
        }

        tabTracksBtn.setOnClickListener {
            tracksPanel.visibility = View.VISIBLE
            settingsPanel.visibility = View.GONE
            tabTracksBtn.background = WatchRoomUtils.makeRoundedBg(hostContext, Color.WHITE, 10f)
            tabTracksBtn.setTextColor(Color.parseColor("#0E1015"))
            tabSettingsBtn.background = null
            tabSettingsBtn.setTextColor(Color.parseColor("#B0BAC9"))
        }
        tabSettingsBtn.setOnClickListener {
            tracksPanel.visibility = View.GONE
            settingsPanel.visibility = View.VISIBLE
            tabSettingsBtn.background = WatchRoomUtils.makeRoundedBg(hostContext, Color.WHITE, 10f)
            tabSettingsBtn.setTextColor(Color.parseColor("#0E1015"))
            tabTracksBtn.background = null
            tabTracksBtn.setTextColor(Color.parseColor("#B0BAC9"))
        }

        fun renderTracksList() {
            filterRow.removeAllViews()
            tracksContainer.removeAllViews()
            if (availableSubtitles.isEmpty()) {
                val emptyTv = TextView(hostContext).apply {
                    text = "لا توجد ترجمات خارجية متاحة حالياً"
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    setPadding(WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 24), WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 24))
                }
                tracksContainer.addView(emptyTv)
            } else {
                availableSubtitles.forEach { track ->
                    val isSelected = track.id == selectedSubtitleId
                    val itemLayout = LinearLayout(hostContext).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = if (isSelected) WatchRoomUtils.makeRoundedBg(hostContext, Color.parseColor("#26FFFFFF"), 10f, Color.parseColor("#55FFFFFF"), 1f) else null
                        setPadding(WatchRoomUtils.dp(hostContext, 14), WatchRoomUtils.dp(hostContext, 10), WatchRoomUtils.dp(hostContext, 14), WatchRoomUtils.dp(hostContext, 10))
                        setOnClickListener {
                            selectedSubtitleId = track.id
                            subtitleOffsetMs = 0L
                            scope.launch {
                                val parsed = withContext(Dispatchers.IO) { downloadAndParseSubtitleDirect(track.url) }
                                if (parsed.isNotEmpty()) {
                                    activeSubtitleCues = parsed
                                    tvAppSubtitleOverlay.visibility = View.VISIBLE
                                }
                            }
                            dialog.dismiss()
                        }
                    }
                    val langTv = TextView(hostContext).apply {
                        text = track.languageLabel.ifBlank { track.languageCode }
                        setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#D0D7E2"))
                        textSize = 13.5f
                        setTypeface(typeface, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    itemLayout.addView(langTv)
                    tracksContainer.addView(itemLayout)
                }
            }
        }

        btnRefreshSubs.setOnClickListener {
            val tmdbId = activeMovieTmdbId.ifEmpty { fallbackStreamId }
            if (tmdbId.isNotEmpty()) {
                scope.launch {
                    val subs = withContext(Dispatchers.IO) { fetchSubtitlesDirectFromApi(tmdbId) }
                    availableSubtitles = subs
                    renderTracksList()
                }
            }
        }

        renderTracksList()
        if (availableSubtitles.isEmpty()) {
            btnRefreshSubs.performClick()
        }

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(dialogWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun fetchSubtitlesDirectFromApi(cleanTmdbId: String): List<SubtitleTrack> {
        val list = mutableListOf<SubtitleTrack>()
        try {
            val urlsToTry = listOf(
                "${SessionManager.CLOUDFLARE_API_URL}/api/v2/subtitles?tmdb_id=$cleanTmdbId&type=movie",
                "https://sub.wyzie.ru/search?id=$cleanTmdbId"
            )
            for (apiUrl in urlsToTry) {
                val req = Request.Builder().url(apiUrl).header("User-Agent", SessionManager.CUSTOM_USER_AGENT).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val bodyStr = resp.body?.string()?.trim() ?: return@use
                    val arr = if (bodyStr.startsWith("[")) {
                        JSONArray(bodyStr)
                    } else {
                        JSONObject(bodyStr).optJSONArray("subtitles") ?: JSONArray()
                    }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val subUrl = o.optString("url", "")
                        if (subUrl.isBlank()) continue
                        val langCode = o.optString("language", o.optString("lang", "ar"))
                        val langLabel = o.optString("display", o.optString("label", langCode))
                        list.add(
                            SubtitleTrack(
                                id = o.optString("id", "sub_$i"),
                                languageCode = langCode,
                                languageLabel = langLabel,
                                releaseName = o.optString("release", ""),
                                url = subUrl
                            )
                        )
                    }
                }
                if (list.isNotEmpty()) break
            }
        } catch (_: Exception) {}
        return list
    }

    private fun downloadAndParseSubtitleDirect(url: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        try {
            val req = Request.Builder().url(url).header("User-Agent", SessionManager.CUSTOM_USER_AGENT).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val text = resp.body?.string() ?: return emptyList()
                val lines = text.lines()
                var i = 0
                val timeRegex = Regex("""(\d{1,2}:\d{2}:\d{2}[,.]\d{3}|\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{3}|\d{2}:\d{2}[,.]\d{3})""")
                while (i < lines.size) {
                    val line = lines[i].trim()
                    val match = timeRegex.find(line)
                    if (match != null) {
                        val startMs = parseSubTimeMs(match.groupValues[1])
                        val endMs = parseSubTimeMs(match.groupValues[2])
                        i++
                        val sb = StringBuilder()
                        while (i < lines.size && lines[i].trim().isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append(lines[i].trim())
                            i++
                        }
                        if (sb.isNotEmpty() && endMs > startMs) {
                            cues.add(SubtitleCue(startMs, endMs, WatchRoomUtils.cleanAssAndHtmlTags(sb.toString())))
                        }
                    } else {
                        i++
                    }
                }
            }
        } catch (_: Exception) {}
        return cues
    }

    private fun parseSubTimeMs(raw: String): Long {
        val norm = raw.trim().replace(',', '.')
        val parts = norm.split(":")
        return try {
            if (parts.size == 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val secParts = parts[2].split(".")
                val s = secParts[0].toLong()
                val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                (h * 3600_000L) + (m * 60_000L) + (s * 1000L) + ms
            } else if (parts.size == 2) {
                val m = parts[0].toLong()
                val secParts = parts[1].split(".")
                val s = secParts[0].toLong()
                val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                (m * 60_000L) + (s * 1000L) + ms
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }
}