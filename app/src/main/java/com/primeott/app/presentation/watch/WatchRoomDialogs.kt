package com.primeott.app.presentation.watch

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.primeott.app.data.repository.OttRepository
import kotlinx.coroutines.*
import okhttp3.OkHttpClient

class WatchRoomDialogs(
    private val hostContext: Context,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope
) {

    fun showMoviesGridModalDialog(
        allMoviesList: List<MediaStreamOption>,
        onSwitchToChannels: () -> Unit,
        onMovieSelected: (MediaStreamOption) -> Unit
    ) {
        val dialog = Dialog(hostContext, android.R.style.Theme_Black_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val screenHeight = hostContext.resources.displayMetrics.heightPixels

        val modalRoot = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(WatchRoomUtils.dp(hostContext, 16), WatchRoomUtils.dp(hostContext, 18), WatchRoomUtils.dp(hostContext, 16), WatchRoomUtils.dp(hostContext, 14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10131C"))
                cornerRadius = WatchRoomUtils.dpF(hostContext, 22f)
                setStroke(WatchRoomUtils.dp(hostContext, 1), Color.parseColor("#2A3042"))
            }
        }

        val headerRow = LinearLayout(hostContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, WatchRoomUtils.dp(hostContext, 12))
        }
        val tvHeader = TextView(hostContext).apply {
            text = "Search Movies"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSwitchToChannels = TextView(hostContext).apply {
            text = "📺 القنوات"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 6), WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#262B3A"))
                cornerRadius = WatchRoomUtils.dpF(hostContext, 12f)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = WatchRoomUtils.dp(hostContext, 10) }
            setOnClickListener {
                dialog.dismiss()
                onSwitchToChannels()
            }
        }
        val btnClose = TextView(hostContext).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#262B3A"))
            }
            layoutParams = LinearLayout.LayoutParams(WatchRoomUtils.dp(hostContext, 38), WatchRoomUtils.dp(hostContext, 38))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(tvHeader)
        headerRow.addView(btnSwitchToChannels)
        headerRow.addView(btnClose)
        modalRoot.addView(headerRow)

        val etSearchModal = EditText(hostContext).apply {
            hint = "🔍  ابحث عن أي فيلم..."
            setHintTextColor(Color.parseColor("#8C96A8"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            setPadding(WatchRoomUtils.dp(hostContext, 16), WatchRoomUtils.dp(hostContext, 12), WatchRoomUtils.dp(hostContext, 16), WatchRoomUtils.dp(hostContext, 12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181C28"))
                cornerRadius = WatchRoomUtils.dpF(hostContext, 14f)
                setStroke(WatchRoomUtils.dp(hostContext, 1), Color.parseColor("#32394E"))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = WatchRoomUtils.dp(hostContext, 12) }
        }
        modalRoot.addView(etSearchModal)

        val modalMoviesList = mutableListOf<MediaStreamOption>().apply { addAll(allMoviesList) }
        val modalAdapter = MediaPickerAdapter(hostContext, httpClient, scope, modalMoviesList) { chosen ->
            dialog.dismiss()
            onMovieSelected(chosen)
        }

        val rvModalGrid = RecyclerView(hostContext).apply {
            layoutManager = GridLayoutManager(hostContext, 3)
            adapter = modalAdapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (screenHeight * 0.68).toInt())
        }
        modalRoot.addView(rvModalGrid)

        val footerRow = LinearLayout(hostContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, WatchRoomUtils.dp(hostContext, 12), 0, 0)
        }
        val btnCancel = Button(hostContext).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#212634"))
                cornerRadius = WatchRoomUtils.dpF(hostContext, 14f)
            }
            layoutParams = LinearLayout.LayoutParams(0, WatchRoomUtils.dp(hostContext, 46), 1f).apply { marginEnd = WatchRoomUtils.dp(hostContext, 8) }
            setOnClickListener { dialog.dismiss() }
        }
        val btnSearch = Button(hostContext).apply {
            text = "Search"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E50914"))
                cornerRadius = WatchRoomUtils.dpF(hostContext, 14f)
            }
            layoutParams = LinearLayout.LayoutParams(0, WatchRoomUtils.dp(hostContext, 46), 1f).apply { marginStart = WatchRoomUtils.dp(hostContext, 8) }
        }
        footerRow.addView(btnCancel)
        footerRow.addView(btnSearch)
        modalRoot.addView(footerRow)

        var searchJob: Job? = null
        val triggerModalSearch = { queryRaw: String ->
            val q = queryRaw.trim()
            searchJob?.cancel()
            if (q.isEmpty()) {
                modalMoviesList.clear()
                modalMoviesList.addAll(allMoviesList)
                modalAdapter.notifyDataSetChanged()
            } else {
                val localFiltered = allMoviesList.filter { it.title.contains(q, ignoreCase = true) }
                modalMoviesList.clear()
                modalMoviesList.addAll(localFiltered)
                modalAdapter.notifyDataSetChanged()

                if (q.length >= 2) {
                    searchJob = scope.launch {
                        delay(220)
                        val tmdbRes = withContext(Dispatchers.IO) {
                            OttRepository.searchTmdbCatalog(q, "MOVIES")
                        }
                        if (tmdbRes.isNotEmpty()) {
                            modalMoviesList.clear()
                            for (vod in tmdbRes) {
                                modalMoviesList.add(
                                    MediaStreamOption(
                                        id = vod.id,
                                        title = vod.title,
                                        subtitle = "🎬 فيلم (${vod.year}) • ⭐ ${vod.rating}",
                                        streamUrl = "",
                                        posterUrl = vod.posterUrl,
                                        rating = vod.rating.ifBlank { "7.5" },
                                        year = vod.year,
                                        isMovie = true
                                    )
                                )
                            }
                            modalAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }

        etSearchModal.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                triggerModalSearch(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSearch.setOnClickListener { triggerModalSearch(etSearchModal.text?.toString() ?: "") }

        dialog.setContentView(modalRoot)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val width = (hostContext.resources.displayMetrics.widthPixels * 0.96).toInt()
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    fun showCreateRoomDialog(
        defaultUserName: String,
        onCreateConfirmed: (String, Boolean, String) -> Unit
    ) {
        val container = ScrollView(hostContext)
        val layout = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 32, 44, 20)
            setBackgroundColor(Color.parseColor("#111726"))
        }
        container.addView(layout)

        val etName = EditText(hostContext).apply {
            hint = "اسم الغرفة"
            setText("غرفة $defaultUserName")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        layout.addView(etName)

        val rgPrivacy = RadioGroup(hostContext).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 16, 0, 8)
        }
        val rbPublic = RadioButton(hostContext).apply {
            text = "🌐 غرفة عامة"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        val rbPrivate = RadioButton(hostContext).apply {
            text = "🔒 غرفة خاصة"
            setTextColor(Color.WHITE)
        }
        rgPrivacy.addView(rbPublic)
        rgPrivacy.addView(rbPrivate)
        layout.addView(rgPrivacy)

        val etPassword = EditText(hostContext).apply {
            hint = "كلمة مرور الغرفة الخاصة"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            visibility = View.GONE
        }
        layout.addView(etPassword)

        rgPrivacy.setOnCheckedChangeListener { _, checkedId ->
            etPassword.visibility = if (checkedId == rbPrivate.id) View.VISIBLE else View.GONE
        }

        AlertDialog.Builder(hostContext)
            .setTitle("➕ إنشاء غرفة مشاهدة جديدة")
            .setView(container)
            .setPositiveButton("فتح الغرفة الآن") { _, _ ->
                val title = etName.text.toString().trim().ifEmpty { "غرفة $defaultUserName" }
                val isPriv = rbPrivate.isChecked
                val rawPass = etPassword.text.toString().trim()
                onCreateConfirmed(title, isPriv, rawPass)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun promptServerRoomPassword(roomCode: String, onPasswordSubmitted: (String, String) -> Unit) {
        val input = EditText(hostContext).apply {
            hint = "أدخل كلمة مرور الغرفة..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(hostContext)
            .setTitle("🔒 غرفة خاصة محمية")
            .setView(input)
            .setPositiveButton("انضمام") { _, _ ->
                onPasswordSubmitted(roomCode, input.text.toString().trim())
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun promptCustomStreamUrlDialog(onCustomUrlSubmitted: (String, String) -> Unit) {
        val layout = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val etTitle = EditText(hostContext).apply { hint = "اسم الفيلم أو القناة" }
        val etUrl = EditText(hostContext).apply { hint = "رابط البث المباشر (m3u8 / mp4)" }
        layout.addView(etTitle)
        layout.addView(etUrl)
        AlertDialog.Builder(hostContext)
            .setTitle("🔗 تشغيل رابط بث خارجي")
            .setView(layout)
            .setPositiveButton("تشغيل ومزامنة") { _, _ ->
                val url = etUrl.text.toString().trim()
                val title = etTitle.text.toString().trim().ifEmpty { "بث خارجي" }
                if (url.isNotEmpty()) {
                    onCustomUrlSubmitted(title, url)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun showParticipantManagementDialog(
        target: ParticipantModel,
        isMeHost: Boolean,
        isLocallyMuted: Boolean,
        onToggleLocalMute: () -> Unit,
        onKickParticipant: () -> Unit
    ) {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add(if (isLocallyMuted) "🔊 إلغاء الكتم المحلي" else "🔇 كتم صوت هذا العضو لدي")
        actions.add { onToggleLocalMute() }

        if (isMeHost) {
            options.add("👢 طرد من الغرفة (Kick)")
            actions.add { onKickParticipant() }
        }

        AlertDialog.Builder(hostContext)
            .setTitle("إدارة العضو: ${target.userName}")
            .setItems(options.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    fun showBlockedUsersDialog(blockedUserIds: MutableSet<String>) {
        if (blockedUserIds.isEmpty()) {
            Toast.makeText(hostContext, "قائمة المحظورين فارغة", Toast.LENGTH_SHORT).show()
            return
        }
        val arr = blockedUserIds.toTypedArray()
        AlertDialog.Builder(hostContext)
            .setTitle("🚫 المحظورون (اضغط لإلغاء الحظر)")
            .setItems(arr) { _, which ->
                blockedUserIds.remove(arr[which])
                Toast.makeText(hostContext, "✅ تم إلغاء الحظر", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    fun showHostControlsCenterDialog(room: WatchRoomModel, onRoomUpdated: () -> Unit) {
        val items = arrayOf(
            if (room.isChatLocked) "🔓 فتح الدردشة الكتابية" else "🔒 قفل الدردشة الكتابية",
            if (room.isPrivate) "🌐 تحويل الغرفة إلى عامة" else "🔒 تحويل الغرفة إلى خاصة"
        )
        AlertDialog.Builder(hostContext)
            .setTitle("⚙️ إعدادات المضيف")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> room.isChatLocked = !room.isChatLocked
                    1 -> {
                        room.isPrivate = !room.isPrivate
                        onRoomUpdated()
                    }
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    fun confirmLeaveRoom(roomName: String, isHost: Boolean, onConfirmLeave: (Boolean) -> Unit) {
        AlertDialog.Builder(hostContext)
            .setTitle("🚪 مغادرة الغرفة")
            .setMessage(if (isHost) "هل تريد مغادرة وإغلاق غرفة «$roomName»؟" else "هل تريد مغادرة غرفة «$roomName»؟")
            .setPositiveButton("مغادرة") { _, _ -> onConfirmLeave(isHost) }
            .setNegativeButton("البقاء", null)
            .show()
    }

    fun confirmCloseRoomByHost(onConfirmClose: () -> Unit) {
        AlertDialog.Builder(hostContext)
            .setTitle("⛔ إغلاق الغرفة نهائياً")
            .setMessage("سيتم إغلاق الغرفة لجميع المشاركين.")
            .setPositiveButton("إغلاق الغرفة") { _, _ -> onConfirmClose() }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}