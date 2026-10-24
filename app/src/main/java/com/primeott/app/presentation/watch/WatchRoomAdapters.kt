package com.primeott.app.presentation.watch

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.recyclerview.widget.RecyclerView
import com.primeott.app.R
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActiveRoomsAdapter(
    private val context: Context,
    private val items: List<WatchRoomModel>,
    private val onJoinClick: (WatchRoomModel) -> Unit
) : RecyclerView.Adapter<ActiveRoomsAdapter.VH>() {
    inner class VH(val root: LinearLayout, val tvTitle: TextView, val tvMeta: TextView, val tvStream: TextView, val btnJoin: AppCompatButton) : RecyclerView.ViewHolder(root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.wt_bg_card)
            setPadding(28, 22, 28, 22)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 14 }
        }
        val tvTitle = TextView(context).apply { setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, Typeface.BOLD) }
        val tvStream = TextView(context).apply { setTextColor(Color.parseColor("#80D8FF")); textSize = 12f; setPadding(0, 6, 0, 8) }
        val bottomRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tvMeta = TextView(context).apply { setTextColor(Color.parseColor("#9AA5B8")); textSize = 11f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val btnJoin = AppCompatButton(context).apply {
            setBackgroundResource(R.drawable.wt_bg_btn_primary)
            setTextColor(Color.WHITE)
            textSize = 11f
            isAllCaps = false
            setPadding(28, 0, 28, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 76)
        }
        bottomRow.addView(tvMeta); bottomRow.addView(btnJoin)
        card.addView(tvTitle); card.addView(tvStream); card.addView(bottomRow)
        return VH(card, tvTitle, tvMeta, tvStream, btnJoin)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val lockBadge = if (r.isPrivate) "🔒 خاصة" else "🌐 عامة"
        holder.tvTitle.text = "${r.roomName}  •  $lockBadge (#${r.roomCode})"
        holder.tvStream.text = "🎬 ${r.streamTitle}"
        holder.tvMeta.text = "👑 ${r.hostName}   •   🟢 ${r.onlineCount} متصل الآن"
        holder.btnJoin.text = "دخول الغرفة ⬅"
        holder.btnJoin.setOnClickListener { onJoinClick(r) }
        holder.root.setOnClickListener { onJoinClick(r) }
    }
    override fun getItemCount(): Int = items.size
}

class ChatAdapter(
    private val context: Context,
    private val items: List<ChatMessageModel>,
    private val onMessageClick: (ChatMessageModel) -> Unit
) : RecyclerView.Adapter<ChatAdapter.VH>() {
    inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(context).apply {
            setPadding(20, 12, 20, 12); textSize = 12f; setBackgroundResource(R.drawable.wt_bg_card)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6 }
        }
        return VH(tv)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(m.timestamp))
        if (m.isSystemEvent) {
            holder.tv.setTextColor(Color.parseColor("#FFE082"))
            holder.tv.text = "[$timeStr] ${m.text}"
        } else {
            val badge = if (m.senderRole == "HOST") "👑 " else "👤 "
            holder.tv.setTextColor(Color.WHITE)
            holder.tv.text = "[$timeStr] $badge${m.senderName}: ${m.text}"
            holder.tv.setOnClickListener { onMessageClick(m) }
        }
    }
    override fun getItemCount(): Int = items.size
}

class ParticipantsAdapter(
    private val context: Context,
    private val getList: () -> List<ParticipantModel>,
    private val onItemClick: (ParticipantModel) -> Unit
) : RecyclerView.Adapter<ParticipantsAdapter.VH>() {
    inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(context).apply {
            setPadding(22, 14, 22, 14); textSize = 12f; setTextColor(Color.WHITE); setBackgroundResource(R.drawable.wt_bg_card)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
        }
        return VH(tv)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = getList()[position]
        val roleBadge = if (p.role == "HOST") "👑 مضيف" else "مشاهد"
        val micBadge = if (p.isSpeaking) "🟢 يتحدث الآن..." else if (p.isMuted) "🎤 مكتوم" else "🎙️ متصل"
        holder.tv.text = "${p.userName} ($roleBadge)   •   $micBadge"
        holder.tv.setOnClickListener { onItemClick(p) }
    }
    override fun getItemCount(): Int = getList().size
}

class MediaPickerAdapter(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val items: List<MediaStreamOption>,
    private val onSelect: (MediaStreamOption) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    inner class MoviePosterVH(
        val cardRoot: FrameLayout,
        val ivPoster: ImageView,
        val tvRatingBadge: TextView,
        val tvMovieTitle: TextView
    ) : RecyclerView.ViewHolder(cardRoot)

    inner class ChannelRowVH(
        val rowRoot: LinearLayout,
        val tvIcon: TextView,
        val tvTitle: TextView,
        val tvSub: TextView,
        val btnPlay: AppCompatButton
    ) : RecyclerView.ViewHolder(rowRoot)

    override fun getItemViewType(position: Int): Int = if (items[position].isMovie) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 1) {
            val card = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1E29"))
                    cornerRadius = WatchRoomUtils.dpF(context, 16f)
                }
                clipToOutline = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WatchRoomUtils.dp(context, 182)
                ).apply {
                    setMargins(WatchRoomUtils.dp(context, 5), WatchRoomUtils.dp(context, 6), WatchRoomUtils.dp(context, 5), WatchRoomUtils.dp(context, 6))
                }
            }

            val ivPoster = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }

            val bottomScrim = View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.parseColor("#EE0B0D14"), Color.parseColor("#880B0D14"), Color.TRANSPARENT)
                )
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WatchRoomUtils.dp(context, 82), Gravity.BOTTOM)
            }

            val tvRating = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(WatchRoomUtils.dp(context, 8), WatchRoomUtils.dp(context, 3), WatchRoomUtils.dp(context, 8), WatchRoomUtils.dp(context, 3))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#CC141720"))
                    cornerRadius = WatchRoomUtils.dpF(context, 10f)
                }
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    setMargins(WatchRoomUtils.dp(context, 6), WatchRoomUtils.dp(context, 6), WatchRoomUtils.dp(context, 6), WatchRoomUtils.dp(context, 6))
                }
            }

            val tvTitle = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(WatchRoomUtils.dp(context, 8), WatchRoomUtils.dp(context, 4), WatchRoomUtils.dp(context, 8), WatchRoomUtils.dp(context, 8))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
            }

            card.addView(ivPoster); card.addView(bottomScrim); card.addView(tvRating); card.addView(tvTitle)
            return MoviePosterVH(card, ivPoster, tvRating, tvTitle)
        } else {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.wt_bg_card)
                setPadding(20, 16, 20, 16)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 }
            }
            val tvIcon = TextView(context).apply {
                textSize = 20f; gravity = Gravity.CENTER; setBackgroundResource(R.drawable.wt_bg_badge_dark); setPadding(18, 14, 18, 14)
            }
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 16; marginEnd = 12 }
            }
            val tvTitle = TextView(context).apply { setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD); maxLines = 1 }
            val tvSub = TextView(context).apply { setTextColor(Color.parseColor("#80D8FF")); textSize = 11f; setPadding(0, 4, 0, 0) }
            textCol.addView(tvTitle); textCol.addView(tvSub)
            
            val btnPlay = AppCompatButton(context).apply {
                setBackgroundResource(R.drawable.wt_bg_btn_primary); setTextColor(Color.WHITE); textSize = 11f; isAllCaps = false
                text = "▶️ تشغيل"; setPadding(24, 0, 24, 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 72)
            }
            row.addView(tvIcon); row.addView(textCol); row.addView(btnPlay)
            return ChannelRowVH(row, tvIcon, tvTitle, tvSub, btnPlay)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is MoviePosterVH) {
            holder.tvMovieTitle.text = item.title
            holder.tvRatingBadge.text = "★ ${item.rating}"
            WatchRoomUtils.loadPosterIntoImageView(context, httpClient, scope, item.posterUrl, holder.ivPoster)
            holder.cardRoot.setOnClickListener { onSelect(item) }
        } else if (holder is ChannelRowVH) {
            holder.tvIcon.text = "📺"
            holder.tvTitle.text = item.title
            holder.tvSub.text = item.subtitle
            holder.btnPlay.setOnClickListener { onSelect(item) }
            holder.rowRoot.setOnClickListener { onSelect(item) }
        }
    }
    override fun getItemCount(): Int = items.size
}

class EventLogsAdapter(private val context: Context, private val items: List<RoomEventLogItem>) : RecyclerView.Adapter<EventLogsAdapter.VH>() {
    inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(TextView(context).apply { setPadding(14, 8, 14, 8); textSize = 11f; setTextColor(Color.parseColor("#B0BEC5")) })
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val log = items[position]
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(log.timestamp))
        holder.tv.text = "[$timeStr] [${log.type}] ${log.detail}"
    }
    override fun getItemCount(): Int = items.size
}