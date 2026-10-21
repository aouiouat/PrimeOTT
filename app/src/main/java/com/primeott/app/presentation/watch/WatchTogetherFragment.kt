package com.primeott.app.presentation.watch

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.primeott.app.R
import com.primeott.app.data.remote.SessionManager
import com.primeott.app.data.repository.OttRepository
import com.primeott.app.databinding.FragmentWatchTogetherBinding
import com.primeott.app.databinding.ItemWatchRoomBinding
import com.primeott.app.domain.model.WatchRoom
import kotlinx.coroutines.launch

class WatchTogetherFragment : Fragment() {

    private var _binding: FragmentWatchTogetherBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_watch_together, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentWatchTogetherBinding.bind(view)
        _binding = b

        b.rvActiveRooms.layoutManager = LinearLayoutManager(requireContext())
        b.btnCreateNewRoom.setOnClickListener { showCreateRoomDialog() }
        b.btnJoinByCodeDialog.setOnClickListener { showJoinByCodeDialog() }
        b.btnRefreshRooms.setOnClickListener { loadActiveRooms() }

        loadActiveRooms()
    }

    override fun onResume() {
        super.onResume()
        loadActiveRooms()
    }

    private fun loadActiveRooms() {
        val b = _binding ?: return
        b.tvRoomsCountHeader.text = "⏳ جاري البحث عن الغرف النشطة..."
        viewLifecycleOwner.lifecycleScope.launch {
            val rooms = OttRepository.fetchActiveWatchRooms()
            val safe = _binding ?: return@launch
            safe.tvRoomsCountHeader.text = "🌍 الغرف النشطة المتاحة (${rooms.size})"
            safe.tvEmptyRoomsState.visibility = if (rooms.isEmpty()) View.VISIBLE else View.GONE
            safe.rvActiveRooms.adapter = RoomsAdapter(rooms) { room ->
                if (room.isPrivate) promptPasswordAndOpenRoom(room.roomCode)
                else openWatchRoom(room.roomCode, "")
            }
        }
    }

    private fun showCreateRoomDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val etRoomName = EditText(ctx).apply {
            hint = "اسم الغرفة"
            setText("غرفة ${SessionManager.getUserName(ctx)}")
        }
        val contentChoices = listOf(
            Triple("6171", "beIN SPORTS 1 FHD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6171.m3u8"),
            Triple("6172", "beIN SPORTS 2 FHD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6172.m3u8"),
            Triple("6173", "beIN SPORTS 3 FHD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6173.m3u8"),
            Triple("6174", "SSC SPORTS 1 HD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6174.m3u8"),
            Triple("6175", "الجزائرية السادسة TV6 HD", "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/6175.m3u8")
        )
        val spinnerContent = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, contentChoices.map { "📺 ${it.second}" })
        }
        val cbPrivate = CheckBox(ctx).apply { text = "🔒 غرفة خاصة بكلمة مرور" }
        val etPassword = EditText(ctx).apply {
            hint = "كلمة المرور"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            visibility = View.GONE
        }
        cbPrivate.setOnCheckedChangeListener { _, checked -> etPassword.visibility = if (checked) View.VISIBLE else View.GONE }
        val cbAllowGuests = CheckBox(ctx).apply { text = "🎮 السماح للأصدقاء بالتحكم في المشغل" }

        container.addView(etRoomName)
        container.addView(spinnerContent)
        container.addView(cbPrivate)
        container.addView(etPassword)
        container.addView(cbAllowGuests)

        AlertDialog.Builder(ctx)
            .setTitle("➕ إنشاء غرفة Watch Together")
            .setView(container)
            .setPositiveButton("🚀 إنشاء") { _, _ ->
                val chosen = contentChoices[spinnerContent.selectedItemPosition]
                viewLifecycleOwner.lifecycleScope.launch {
                    val (ok, codeOrMsg) = OttRepository.createWatchRoom(
                        deviceId = SessionManager.getDeviceId(ctx),
                        userName = SessionManager.getUserName(ctx),
                        roomName = etRoomName.text.toString().trim().ifEmpty { "غرفة مشاهدة" },
                        roomIcon = "⚽",
                        isPrivate = cbPrivate.isChecked,
                        password = etPassword.text.toString().trim(),
                        allowGuestsControl = cbAllowGuests.isChecked,
                        contentType = "LIVE",
                        streamId = chosen.first,
                        contentTitle = chosen.second,
                        contentPoster = "",
                        streamUrl = chosen.third
                    )
                    if (ok) openWatchRoom(codeOrMsg, etPassword.text.toString().trim())
                    else Toast.makeText(ctx, codeOrMsg, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showJoinByCodeDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val etCode = EditText(ctx).apply { hint = "كود الغرفة (مثال: PR92X1) أو رابط الدعوة" }
        val etPass = EditText(ctx).apply { hint = "كلمة المرور (إن وجدت)" }
        container.addView(etCode)
        container.addView(etPass)

        AlertDialog.Builder(ctx)
            .setTitle("🔑 الانضمام إلى غرفة")
            .setView(container)
            .setPositiveButton("انضمام") { _, _ ->
                val raw = etCode.text.toString().trim()
                if (raw.isNotEmpty()) openWatchRoom(raw, etPass.text.toString().trim())
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun promptPasswordAndOpenRoom(roomCode: String) {
        val ctx = requireContext()
        val etPass = EditText(ctx).apply { hint = "أدخل كلمة مرور الغرفة" }
        AlertDialog.Builder(ctx)
            .setTitle("🔒 غرفة خاصة")
            .setView(etPass)
            .setPositiveButton("دخول") { _, _ -> openWatchRoom(roomCode, etPass.text.toString().trim()) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun openWatchRoom(codeOrLink: String, password: String) {
        startActivity(Intent(requireContext(), WatchRoomActivity::class.java).apply {
            putExtra(WatchRoomActivity.EXTRA_ROOM_CODE, codeOrLink)
            putExtra(WatchRoomActivity.EXTRA_ROOM_PASS, password)
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class RoomsAdapter(
    private val items: List<WatchRoom>,
    private val onJoinClick: (WatchRoom) -> Unit
) : RecyclerView.Adapter<RoomsAdapter.VH>() {
    inner class VH(val binding: ItemWatchRoomBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemWatchRoomBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val b = holder.binding
        b.tvRoomIcon.text = r.roomIcon
        b.tvRoomTitle.text = r.roomName
        b.tvRoomContent.text = "📺 ${r.contentTitle} • المضيف: ${r.hostName}"
        b.tvRoomMeta.text = "👥 ${r.participantCount} مشاهدين • الكود: ${r.roomCode}"
        b.btnJoinRoomItem.setOnClickListener { onJoinClick(r) }
        b.root.setOnClickListener { onJoinClick(r) }
    }
}
